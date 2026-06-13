package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.raincat.dolby_beta.db.CloudDao;
import com.raincat.dolby_beta.helper.ClassHelper;
import com.raincat.dolby_beta.helper.EApiHookHelper;
import com.raincat.dolby_beta.helper.EAPIHelper;
import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.net.HTTPSTrustManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/04/16
 *     desc   : 网络访问hook - 拦截EAPI请求响应并修改内容
 *              旧版：通过ClassHelper.HttpResponse.getResultMethod() hook响应处理方法
 *              新版（9.5.30+）：通过hook EAPI解密拦截器interceptor.s.intercept()方法
 *     version: 2.1
 * </pre>
 */
public class EAPIHook {
    private static final String TAG = "dolby_beta";
    private final Context appContext;

    public EAPIHook(final Context context) {
        this.appContext = context;
        // 优先尝试新版hook方式（hook EAPI解密拦截器）
        boolean hooked = hookNewVersion(context);
        // 如果新版hook失败，回退到旧版方式
        if (!hooked) {
            hookOldVersion(context);
        }
    }

    /**
     * 新版hook方式：hook EAPI解密拦截器 interceptor.s.intercept()
     *
     * 设计思路（参考旧版逻辑简化）：
     * 1. beforeHookedMethod：仅记录请求URL路径，不做任何修改
     * 2. afterHookedMethod：读取解密后的响应内容，根据路径进行修改，重建ResponseBody
     *
     * 关键点：
     * - EAPI加密拦截器会将请求路径从/eapi/改为/xeapi/，但代理服务器只识别/eapi/路径
     * - 这个路径问题由ProxyHook在OkHttpClient层面解决（设置代理+SSL工厂+hostnameVerifier）
     * - 本Hook只负责在解密后修改响应内容，不干预加密/解密过程
     *
     * @param context 应用上下文
     * @return 是否hook成功
     */
    private boolean hookNewVersion(final Context context) {
        try {
            // 查找EAPI解密拦截器类 com.netease.cloudmusic.network.interceptor.s
            Class<?> eapiDecryptInterceptorClass = XposedHelpers.findClassIfExists(
                    "com.netease.cloudmusic.network.interceptor.s", context.getClassLoader());
            if (eapiDecryptInterceptorClass == null) {
                XposedBridge.log("EAPIHook: 新版拦截器类interceptor.s未找到，回退旧版方式");
                Log.d(TAG, "EAPIHook: 新版拦截器类interceptor.s未找到，回退旧版方式");
                return false;
            }

            // 查找intercept方法
            Method interceptMethod = null;
            for (Method m : eapiDecryptInterceptorClass.getDeclaredMethods()) {
                if (m.getName().equals("intercept")) {
                    interceptMethod = m;
                    break;
                }
            }
            if (interceptMethod == null) {
                XposedBridge.log("EAPIHook: interceptor.s.intercept方法未找到，回退旧版方式");
                return false;
            }

            XposedBridge.log("EAPIHook: 使用新版hook方式");
            XposedBridge.hookMethod(interceptMethod, new XC_MethodHook() {
                // 用于在beforeHookedMethod和afterHookedMethod之间传递请求URL
                private final ThreadLocal<String> requestUrlPath = new ThreadLocal<>();

                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Object chain = param.args[0];
                        Object request = XposedHelpers.callMethod(chain, "request");
                        Object httpUrl = XposedHelpers.callMethod(request, "url");
                        String urlPath = (String) XposedHelpers.callMethod(httpUrl, "encodedPath");
                        requestUrlPath.set(urlPath);
                    } catch (Exception e) {
                        requestUrlPath.set("unknown");
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String urlPath = requestUrlPath.get();
                    requestUrlPath.remove();

                    // 代理和黑胶都未开启则跳过（与旧版逻辑一致）
                    if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)
                            && !SettingHelper.getInstance().isEnable(SettingHelper.proxy_master_key))
                        return;

                    // 检查原始方法是否抛出了异常（如SSL证书验证失败、DNS解析失败等）
                    // 重要：网络异常会导致intercept()抛出异常，如果不处理会传播到应用层导致闪退
                    Throwable throwable = param.getThrowable();
                    if (throwable != null) {
                        Log.e(TAG, "EAPIHook: intercept异常 - " + throwable.getMessage());
                        if (throwable.getCause() != null) {
                            Log.e(TAG, "EAPIHook: 异常根因 - " + throwable.getCause().getMessage());
                        }
                        try {
                            String errorContent;
                            if (urlPath != null && (urlPath.contains("song/enhance/player/url")
                                    || urlPath.contains("song/enhance/download/url"))) {
                                errorContent = "{\"code\":200,\"data\":[]}";
                            } else {
                                errorContent = "{\"code\":500,\"message\":\"network error\"}";
                            }
                            Object errorResponse = buildErrorResponse(param, errorContent);
                            if (errorResponse != null) {
                                param.setResult(errorResponse);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "EAPIHook: 构造错误响应失败 - " + e.getMessage());
                            param.setResult(null);
                        }
                        return;
                    }

                    // 获取okhttp3.Response响应对象
                    Object response = param.getResult();
                    if (response == null) return;

                    // 从响应中读取解密后的内容
                    Object responseBody = XposedHelpers.callMethod(response, "body");
                    if (responseBody == null) return;

                    // 只处理EAPI请求，非EAPI请求直接放行
                    // 注意：EAPI加密拦截器会将/eapi/改为/xeapi/，所以需要同时匹配两种路径
                    if (urlPath == null || (!urlPath.contains("/eapi/") && !urlPath.contains("/xeapi/"))) return;

                    // 读取body内容（string()会消耗body，只能读取一次）
                    String original;
                    Object contentType;
                    try {
                        contentType = XposedHelpers.callMethod(responseBody, "contentType");
                        original = (String) XposedHelpers.callMethod(responseBody, "string");
                    } catch (Exception e) {
                        XposedBridge.log("EAPIHook: 读取responseBody失败 - " + e.getMessage());
                        Log.e(TAG, "EAPIHook: 读取responseBody失败 - " + e.getMessage());
                        return;
                    }
                    if (TextUtils.isEmpty(original)) return;

                    // 获取请求参数（从request中提取，与旧版从eapi对象提取类似）
                    Object chain = param.args[0];
                    Object request = XposedHelpers.callMethod(chain, "request");
                    LinkedHashMap<String, String> paramsMap = EApiHookHelper.getRequestParams(request);

                    // 统一使用processEapiResponse处理响应内容
                    // processEapiResponse内部会判断代理模式，决定是否跳过player/url的本地修改
                    String modified = processEapiResponse(context, urlPath, original, paramsMap);

                    // 重要：由于string()已经消耗了原始body，无论是否修改内容都必须重建ResponseBody
                    // 否则后续读取body会失败，导致页面无法访问
                    String finalContent = (modified != null) ? modified : original;
                    rebuildResponseBody(param, response, contentType, finalContent);
                }
            });
            return true;
        } catch (Throwable t) {
            XposedBridge.log("EAPIHook: 新版hook方式失败: " + t.getMessage());
            Log.e(TAG, "EAPIHook: 新版hook方式失败: " + t.getMessage());
            return false;
        }
    }

    /**
     * 重建ResponseBody和Response
     * 由于responseBody.string()会消耗原始body（OkHttp的设计，body只能读取一次），
     * 所以无论是否修改了响应内容，都必须重建ResponseBody，否则后续读取会失败
     *
     * @param param       Xposed方法钩子参数
     * @param response    原始okhttp3.Response对象
     * @param contentType 原始ResponseBody的contentType
     * @param content     要设置的响应内容
     */
    private void rebuildResponseBody(XC_MethodHook.MethodHookParam param, Object response,
                                      Object contentType, String content) throws Exception {
        // 使用app的classloader查找okhttp3.ResponseBody，不能用null
        // okhttp3是打包在app内部的，不在系统classloader中
        Class<?> responseBodyClass = XposedHelpers.findClass("okhttp3.ResponseBody", appContext.getClassLoader());
        // 使用MediaType + String创建新的ResponseBody
        Object newBody;
        try {
            // OkHttp 3.x: ResponseBody.create(MediaType, String)
            newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create", contentType, content);
        } catch (Exception e) {
            // OkHttp 4.x: ResponseBody.create(String, MediaType?) 参数顺序可能不同
            try {
                newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create", content, contentType);
            } catch (Exception e2) {
                // 最终回退：使用MediaType + long + String重载
                newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create",
                        contentType, content.length(), content);
            }
        }
        // 使用Response.newBuilder重建Response
        Object newResponse = XposedHelpers.callMethod(response, "newBuilder");
        newResponse = XposedHelpers.callMethod(newResponse, "body", newBody);
        newResponse = XposedHelpers.callMethod(newResponse, "header",
                "Content-Length", String.valueOf(content.length()));
        newResponse = XposedHelpers.callMethod(newResponse, "build");
        param.setResult(newResponse);
    }

    /**
     * 旧版hook方式：通过ClassHelper.HttpResponse.getResultMethod() hook响应处理方法
     * 适用于旧版网易云，其中EAPI响应通过HttpResponse类的方法返回String/JSONObject
     *
     * @param context 应用上下文
     */
    private void hookOldVersion(final Context context) {
        // 获取要hook的方法，如果为null说明目标类未找到（可能是网易云版本不匹配），跳过hook避免崩溃
        Method resultMethod = ClassHelper.HttpResponse.getResultMethod(context);
        if (resultMethod == null) {
            XposedBridge.log("EAPIHook: getResultMethod返回null，跳过hook");
            Log.w(TAG, "EAPIHook: getResultMethod返回null，跳过hook");
            return;
        }
        XposedBridge.log("EAPIHook: 使用旧版hook方式（HttpResponse.getResultMethod）");
        Log.d(TAG, "EAPIHook: 使用旧版hook方式（HttpResponse.getResultMethod）");
        XposedBridge.hookMethod(resultMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                // 代理和黑胶都未开启
                if (!SettingHelper.getInstance().isEnable(SettingHelper.black_key)
                        && !SettingHelper.getInstance().isEnable(SettingHelper.proxy_master_key))
                    return;
                // 返回参数不对
                if ((!(param.getResult() instanceof String) && !(param.getResult() instanceof JSONObject)))
                    return;
                // 返回参数为空
                String original = param.getResult().toString();
                if (TextUtils.isEmpty(original)) {
                    return;
                }
                ClassHelper.HttpResponse httpResponse = new ClassHelper.HttpResponse(param.thisObject);
                Object eapi = httpResponse.getEapi(context);
                Uri uri = ClassHelper.HttpUrl.getUri(context, eapi);
                if (!uri.getPath().contains("/eapi/"))
                    return;
                String path = uri.getPath();

                // 获取请求参数
                LinkedHashMap<String, String> paramsMap = ClassHelper.HttpParams.getParams(context, eapi);
                String modified = processEapiResponse(context, path, original, paramsMap);

                if (modified != null) {
                    param.setResult(param.getResult() instanceof JSONObject ? new JSONObject(modified) : modified);
                }
            }
        });
    }

    /**
     * 检查音源响应中是否有空URL，如果有则通过代理服务器获取替换音源
     *
     * 设计思路（参考旧版代理逻辑）：
     * 1. 先让请求正常走网易云服务器，获取原始响应
     * 2. 检查响应中是否有歌曲的URL为空（无法播放的付费/下架歌曲）
     * 3. 只有URL为空的歌曲才通过代理服务器获取替换音源
     * 4. 将替换音源合并到原始响应中
     *
     * 代理请求方式：
     * 使用HttpURLConnection通过HTTP代理发送请求到网易云API，
     * 代理服务器（UnblockNeteaseMusic）会拦截请求并返回替换音源。
     * 请求使用非EAPI的普通API端点，避免EAPI加解密兼容性问题。
     *
     * @param context    应用上下文
     * @param modified   经过modifyPlayer处理后的响应JSON
     * @param paramsMap  原始请求参数
     * @param path       请求路径
     * @return 合并替换音源后的响应JSON，如果没有空URL则原样返回
     */
    private String replaceEmptyUrlWithProxy(Context context, String modified,
                                             LinkedHashMap<String, String> paramsMap,
                                             String path) {
        try {
            JSONObject responseJson = new JSONObject(modified);
            JSONArray dataArray = responseJson.optJSONArray("data");
            if (dataArray == null || dataArray.length() == 0) return modified;

            // 收集URL为空的歌曲ID
            StringBuilder emptyIds = new StringBuilder();
            int emptyCount = 0;
            for (int i = 0; i < dataArray.length(); i++) {
                JSONObject songObj = dataArray.optJSONObject(i);
                if (songObj == null) continue;
                boolean urlEmpty = songObj.isNull("url") || songObj.optString("url", "").isEmpty();
                long songId = songObj.optLong("id", 0);
                int code = songObj.optInt("code", 0);
                if (songId != 0 && (urlEmpty || code != 200)) {
                    if (emptyIds.length() > 0) emptyIds.append(",");
                    emptyIds.append(songId).append("_0");
                    emptyCount++;
                }
            }

            if (emptyCount == 0) return modified;

            Log.d(TAG, "EAPIHook: 代理替换，发现" + emptyCount + "首空音源 IDs=" + emptyIds);

            // 从请求参数中提取音质等级
            String level = "exhigh";
            String encodeType = "aac";
            if (paramsMap != null) {
                try {
                    String paramsStr = paramsMap.get("params");
                    if (paramsStr != null) {
                        JSONObject paramsJson = EAPIHelper.decrypt(paramsStr);
                        if (paramsJson != null) {
                            level = paramsJson.optString("level", level);
                            encodeType = paramsJson.optString("encodeType", encodeType);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "EAPIHook: 解析请求参数失败 - " + e.getMessage());
                }
            }

            String proxyResult = requestProxyForSongUrl(context, emptyIds.toString(), level, encodeType);
            if (proxyResult == null || proxyResult.isEmpty()) {
                Log.w(TAG, "EAPIHook: 代理未返回替换音源");
                return modified;
            }

            // 解析代理返回的替换音源，合并到原始响应中
            try {
                JSONObject proxyJson = new JSONObject(proxyResult);
                if (proxyJson.optInt("code") != 200) {
                    Log.w(TAG, "EAPIHook: 代理返回code=" + proxyJson.optInt("code") + "，替换失败");
                    return modified;
                }
                JSONArray proxyData = proxyJson.optJSONArray("data");
                if (proxyData == null) {
                    Log.w(TAG, "EAPIHook: 代理返回data为null");
                    return modified;
                }

                // 将代理返回的音源信息合并到原始响应
                for (int i = 0; i < proxyData.length(); i++) {
                    JSONObject proxySong = proxyData.optJSONObject(i);
                    if (proxySong == null) continue;
                    long proxySongId = proxySong.optLong("id", 0);
                    String proxyUrl = proxySong.optString("url", "");
                    if (proxySongId == 0 || proxyUrl == null || proxyUrl.isEmpty()) continue;

                    String actualUrl = decodePackageUrl(proxyUrl);
                    if (actualUrl != null) proxyUrl = actualUrl;

                    // 在原始响应中找到对应歌曲，替换URL
                    for (int j = 0; j < dataArray.length(); j++) {
                        JSONObject songObj = dataArray.optJSONObject(j);
                        if (songObj != null && songObj.optLong("id") == proxySongId) {
                            songObj.put("url", proxyUrl);
                            songObj.put("code", 200);
                            if (proxySong.has("br")) songObj.put("br", proxySong.optInt("br"));
                            if (proxySong.has("size")) songObj.put("size", proxySong.optInt("size"));
                            if (proxySong.has("md5")) songObj.put("md5", proxySong.optString("md5"));
                            if (proxySong.has("type")) songObj.put("type", proxySong.optString("type"));
                            if (proxySong.has("level")) songObj.put("level", proxySong.optString("level"));
                            if (proxySong.has("encodeType")) songObj.put("encodeType", proxySong.optString("encodeType"));
                            dataArray.put(j, songObj);
                            Log.d(TAG, "EAPIHook: 歌曲ID=" + proxySongId + " 代理替换成功");
                            break;
                        }
                    }
                }
                responseJson.put("data", dataArray);
                return responseJson.toString();
            } catch (Exception e) {
                Log.e(TAG, "EAPIHook: 合并代理音源失败 - " + e.getMessage());
                return modified;
            }
        } catch (Exception e) {
            Log.e(TAG, "EAPIHook: replaceEmptyUrlWithProxy异常 - " + e.getMessage());
            return modified;
        }
    }

    /**
     * 解码代理服务器返回的/package/格式URL
     *
     * 代理服务器（UnblockNeteaseMusic）返回的URL格式为：
     * https://music.163.com/package/{base64编码的实际URL}/{songId}.{ext}
     *
     * 客户端无法直接访问/package/路径（返回404），
     * 需要解码Base64部分获取实际的音源URL（如酷我、QQ音乐的直链）
     *
     * @param packageUrl 代理服务器返回的/package/格式URL
     * @return 解码后的实际音源URL，如果不是/package/格式或解码失败返回null
     */
    private String decodePackageUrl(String packageUrl) {
        try {
            if (packageUrl == null || !packageUrl.contains("/package/")) return null;

            // 提取/package/后面的Base64部分
            // URL格式: https://music.163.com/package/{base64}/{songId}.{ext}
            int packageStart = packageUrl.indexOf("/package/");
            String afterPackage = packageUrl.substring(packageStart + "/package/".length());

            // Base64部分在第一个/之前
            int slashIndex = afterPackage.indexOf('/');
            if (slashIndex <= 0) return null;

            String base64Part = afterPackage.substring(0, slashIndex);

            // Base64解码（URL安全的Base64可能将+替换为-，/替换为_）
            byte[] decoded = android.util.Base64.decode(base64Part, android.util.Base64.DEFAULT);
            String actualUrl = new String(decoded, "UTF-8");

            // 验证解码结果是有效的URL
            if (actualUrl.startsWith("http://") || actualUrl.startsWith("https://")) {
                return actualUrl;
            }
            Log.w(TAG, "EAPIHook: /package/ Base64解码结果不是有效URL: " + actualUrl);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "EAPIHook: /package/ URL解码失败 - " + e.getMessage());
            return null;
        }
    }

    /**
     * 通过代理服务器请求替换音源URL
     *
     * 使用HttpURLConnection配置HTTP代理，向网易云API发送请求。
     * 代理服务器（UnblockNeteaseMusic）会拦截请求，对无法播放的歌曲返回替换音源。
     * 使用非EAPI的普通API端点，避免EAPI加解密兼容性问题。
     *
     * @param context     应用上下文
     * @param ids         需要替换的歌曲ID列表，格式: "id1_0,id2_0"
     * @param level       音质等级（如exhigh, lossless等）
     * @param encodeType  编码类型（如aac, flac等）
     * @return 代理服务器返回的JSON字符串，失败返回null
     */
    private String requestProxyForSongUrl(Context context, String ids, String level, String encodeType) {
        try {
            // 获取代理配置
            String proxyHost = SettingHelper.getInstance().getSetting(SettingHelper.proxy_server_key) ?
                    SettingHelper.getInstance().getHttpProxy() : "127.0.0.1";
            int proxyPort = SettingHelper.getInstance().getProxyPort();

            Log.d(TAG, "EAPIHook: 代理请求 ids=" + ids + " level=" + level);

            // 构建请求URL（使用非EAPI的普通API端点，代理服务器能识别）
            // ids格式必须与原始请求一致：JSON字符串数组，如 ["2648942804_0","1234567_0"]
            // 每个id需要用双引号包裹
            String[] idArray = ids.split(",");
            StringBuilder idsJson = new StringBuilder("[");
            for (int i = 0; i < idArray.length; i++) {
                if (i > 0) idsJson.append(",");
                idsJson.append("\"").append(idArray[i]).append("\"");
            }
            idsJson.append("]");

            String urlStr = "https://interface3.music.163.com/api/song/enhance/player/url/v1?"
                    + "ids=" + java.net.URLEncoder.encode(idsJson.toString(), "UTF-8")
                    + "&level=" + level
                    + "&encodeType=" + encodeType;

            // 配置HTTP代理
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(proxyHost, proxyPort));

            // 创建独立的SSL上下文，不影响全局设置
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, new javax.net.ssl.TrustManager[]{new HTTPSTrustManager()}, new java.security.SecureRandom());

            java.net.URL url = new java.net.URL(urlStr);
            javax.net.ssl.HttpsURLConnection conn = (javax.net.ssl.HttpsURLConnection) url.openConnection(proxy);
            conn.setSSLSocketFactory(sslContext.getSocketFactory());
            conn.setHostnameVerifier((hostname, session) -> true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Cookie", "os=android");
            conn.setRequestProperty("User-Agent", "NeteaseMusic/8.10.05");
            conn.setRequestProperty("Accept", "*/*");
            // 禁用gzip压缩，避免HttpURLConnection自动解压与代理服务器压缩行为冲突
            // 导致 "ID1ID2: actual 0x7b22 != expected 0x1f8b" 异常
            conn.setRequestProperty("Accept-Encoding", "identity");

            int responseCode = conn.getResponseCode();

            if (responseCode == 200) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                return response.toString();
            } else {
                java.io.InputStream errorStream = conn.getErrorStream();
                if (errorStream != null) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(errorStream, "UTF-8"));
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    reader.close();
                    Log.e(TAG, "EAPIHook: 代理请求失败 code=" + responseCode + " " + errorResponse.toString());
                }
                return null;
            }
        } catch (java.net.ConnectException e) {
            Log.e(TAG, "EAPIHook: 代理连接失败 - " + e.getMessage());
            return null;
        } catch (java.net.SocketTimeoutException e) {
            Log.e(TAG, "EAPIHook: 代理请求超时");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "EAPIHook: 代理请求异常 - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 处理EAPI响应内容，根据请求路径进行不同的修改
     *
     * 代理模式下的音源替换策略（参考旧版逻辑）：
     * 1. 所有音源请求先正常走网易云服务器，获取原始响应
     * 2. 对响应执行modifyPlayer（黑胶逻辑：设置fee=0, flag=0等）
     * 3. 检查响应中是否有歌曲URL为空（无法播放的付费/下架歌曲）
     * 4. 只有URL为空的歌曲才通过代理服务器获取替换音源
     * 5. 将替换音源合并到原始响应中
     *
     * 这样正常可播放的歌曲不受影响，只有获取不到音源的歌曲才走代理替换。
     *
     * @param context    应用上下文
     * @param path       请求路径（可能包含/eapi/或/xeapi/）
     * @param original   原始响应内容
     * @param paramsMap  请求参数Map
     * @return 修改后的响应内容，如果不需要修改返回null
     */
    private String processEapiResponse(Context context, String path, String original,
                                        LinkedHashMap<String, String> paramsMap) throws Throwable {
        // 检查代理模式是否已启动：代理主开关开启 且 脚本状态为1（已启动）
        boolean proxyActive = SettingHelper.getInstance().isEnable(SettingHelper.proxy_master_key)
                && "1".equals(ExtraHelper.getExtraDate(ExtraHelper.SCRIPT_STATUS));

        if (path.contains("song/enhance/player/url")) {
            // 先执行本地黑胶修改（设置fee=0, flag=0等），无论是否开启代理
            String modified = EAPIHelper.modifyPlayer(original);

            // 代理模式下，检查音源是否为空，为空时通过代理获取替换音源
            if (proxyActive) {
                modified = replaceEmptyUrlWithProxy(context, modified, paramsMap, path);
            }
            return modified;
        } else if (path.contains("song/enhance/download/url")) {
            JSONObject jsonObject = new JSONObject(original);
            JSONObject object = jsonObject.getJSONObject("data");
            JSONArray array = new JSONArray();
            array.put(object);
            jsonObject.put("data", array);
            String modified = EAPIHelper.modifyPlayer(jsonObject.toString())
                    .replace("[", "").replace("]", "");

            // 代理模式下，检查下载音源是否为空，为空时通过代理获取替换
            if (proxyActive) {
                modified = replaceEmptyUrlWithProxy(context, modified, paramsMap, path);
            }
            return modified;
        } else if (path.contains("v1/playlist/manipulate/tracks")) {
            return EAPIHelper.modifyManipulate(paramsMap, original);
        } else if (path.contains("song/like")) {
            return EAPIHelper.modifyLike(paramsMap, original);
        } else if (path.contains("sound/mobile") || path.contains("page=audio_effect")) {
            return EAPIHelper.modifyEffect(original);
        } else if (path.contains("batch")) {
            return processBatchResponse(context, original);
        } else if (path.contains("upload/cloud/info/v2")) {
            JSONObject jsonObject = new JSONObject(original);
            jsonObject = jsonObject.getJSONObject("privateCloud");
            jsonObject = jsonObject.getJSONObject("simpleSong");
            original = original.replace("\"waitTime\":60,", "\"waitTime\":5,");
            CloudDao.getInstance(context).saveSong(Integer.parseInt(jsonObject.getString("id")), original);
            return original;
        } else if (path.contains("cloud/pub/v2")) {
            String songid = EAPIHelper.decrypt(paramsMap.get("params")).getString("songid");
            EAPIHelper.uploadCloud(songid);
            return CloudDao.getInstance(context).getSong(Integer.parseInt(songid));
        }
        return null;
    }

    /**
     * 处理batch请求的响应
     */
    private String processBatchResponse(Context context, String original) throws Throwable {
        if (original.contains("comment\\/banner\\/get")) {
            JSONObject jsonObject = new JSONObject(original);
            if (!jsonObject.isNull("/api/content/exposure/comment/banner/get")) {
                JSONObject object = new JSONObject();
                object.put("code", 200);
                object.put("data", new JSONObject());
                jsonObject.put("/api/content/exposure/comment/banner/get", object);
            }
            if (!jsonObject.isNull("/api/v1/content/exposure/comment/banner/get")) {
                JSONObject object = jsonObject.getJSONObject("/api/v1/content/exposure/comment/banner/get");
                JSONObject data = object.getJSONObject("data");
                data.put("count", 0);
                data.put("offset", 999999999);
                data.put("records", new JSONArray());
                data.put("message", "");
                object.put("data", data);
                jsonObject.put("/api/v1/content/exposure/comment/banner/get", object);
            }
            return jsonObject.toString();
        } else if (SettingHelper.getInstance().isEnable(SettingHelper.fix_comment_key) &&
                original.contains("\\/api\\/resource\\/comment\\/musiciansaid\\/authors")) {
            JSONObject jsonObject = new JSONObject(original);
            JSONObject object = jsonObject.getJSONObject("/api/resource/comment/musiciansaid/authors");
            JSONObject data = object.getJSONObject("data");
            JSONArray team = data.getJSONArray("team");
            for (int i = 0; i < team.length(); i++) {
                JSONObject o = team.getJSONObject(i);
                String s = o.optString("authorTypeText");
                if (s != null && s.equals("作者")) {
                    long uid = o.optLong("uid");
                    long artistId = o.optLong("artistId");
                    if (uid > 2147483647) {
                        JSONObject artistJSONObject = jsonObject.getJSONObject("/api/auth/artist");
                        JSONObject authJSONObject = artistJSONObject.getJSONObject("auth");
                        while (uid > 2147483647)
                            uid = uid / 10;
                        authJSONObject.put(artistId + "", uid);
                        artistJSONObject.put("auth", authJSONObject);
                        jsonObject.put("/api/auth/artist", artistJSONObject);
                        return jsonObject.toString();
                    }
                }
            }
        }
        return null;
    }

    private void logcat(String msg) {
        int max_str_length = 1800;
        //大于4000时
        while (msg.length() > max_str_length) {
            XposedBridge.log(msg.substring(0, max_str_length));
            msg = msg.substring(max_str_length);
        }
        //剩余部分
        XposedBridge.log(msg);
    }

    /**
     * 构造一个HTTP错误响应对象
     * 当intercept()方法抛出异常（如SSL证书验证失败、DNS解析失败等）时，
     * 需要构造一个错误响应来替代异常，防止异常传播导致应用闪退。
     *
     * @param param   Xposed方法钩子参数，用于获取原始Chain构建Response
     * @param content 响应体内容（JSON格式的错误信息）
     * @return 构造的okhttp3.Response对象，如果构造失败返回null
     */
    private Object buildErrorResponse(XC_MethodHook.MethodHookParam param, String content) {
        try {
            Object chain = param.args[0];
            Object request = XposedHelpers.callMethod(chain, "request");

            // 创建包含错误信息的ResponseBody
            Class<?> responseBodyClass = XposedHelpers.findClass("okhttp3.ResponseBody", appContext.getClassLoader());
            Object mediaType = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("okhttp3.MediaType", appContext.getClassLoader()),
                    "parse", "application/json; charset=utf-8");
            Object errorBody = XposedHelpers.callStaticMethod(responseBodyClass, "create", mediaType, content);

            // 构建错误Response：code=500, message="Network Error"
            Class<?> responseBuilderClass = XposedHelpers.findClass(
                    "okhttp3.Response$Builder", appContext.getClassLoader());
            Object responseBuilder = responseBuilderClass.newInstance();
            XposedHelpers.callMethod(responseBuilder, "request", request);
            XposedHelpers.callMethod(responseBuilder, "protocol",
                    XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("okhttp3.Protocol", appContext.getClassLoader()),
                            "HTTP_1_1"));
            XposedHelpers.callMethod(responseBuilder, "code", 500);
            XposedHelpers.callMethod(responseBuilder, "message", "Network Error");
            XposedHelpers.callMethod(responseBuilder, "body", errorBody);
            return XposedHelpers.callMethod(responseBuilder, "build");
        } catch (Exception e) {
            Log.e(TAG, "EAPIHook: buildErrorResponse失败 - " + e.getMessage());
            XposedBridge.log("EAPIHook: buildErrorResponse失败 - " + e.getMessage());
            return null;
        }
    }
}
