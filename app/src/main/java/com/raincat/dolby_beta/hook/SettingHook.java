package com.raincat.dolby_beta.hook;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.model.SidebarEnum;
import com.raincat.dolby_beta.utils.Tools;
import com.raincat.dolby_beta.view.BaseDialogInputItem;
import com.raincat.dolby_beta.view.BaseDialogItem;
import com.raincat.dolby_beta.view.beauty.BeautyBannerHideView;
import com.raincat.dolby_beta.view.beauty.BeautyBlackHideView;
import com.raincat.dolby_beta.view.beauty.BeautyBubbleHideView;
import com.raincat.dolby_beta.view.beauty.BeautyCommentHotView;
import com.raincat.dolby_beta.view.beauty.BeautyKSongHideView;
import com.raincat.dolby_beta.view.beauty.BeautyNightModeView;
import com.raincat.dolby_beta.view.beauty.BeautyRotationView;
import com.raincat.dolby_beta.view.beauty.BeautySidebarHideItem;
import com.raincat.dolby_beta.view.beauty.BeautySidebarHideView;
import com.raincat.dolby_beta.view.beauty.BeautyTabHideView;
import com.raincat.dolby_beta.view.beauty.BeautyTitleView;
import com.raincat.dolby_beta.view.beauty.PlayerBackgroundView;
import com.raincat.dolby_beta.view.beauty.background.BackgroundMasterView;
import com.raincat.dolby_beta.view.beauty.background.BackgroundTitleView;
import com.raincat.dolby_beta.view.beauty.background.BackgroundPictureUrlView;
import com.raincat.dolby_beta.view.beauty.background.BackgroundBlurRadiusView;
import com.raincat.dolby_beta.view.proxy.*;
import com.raincat.dolby_beta.view.proxy.configuration.*;
import com.raincat.dolby_beta.view.setting.AboutView;
import com.raincat.dolby_beta.view.setting.BeautyView;
import com.raincat.dolby_beta.view.setting.BlackView;
import com.raincat.dolby_beta.view.setting.DexView;
import com.raincat.dolby_beta.view.setting.FixCommentView;
import com.raincat.dolby_beta.view.setting.MasterView;
import com.raincat.dolby_beta.view.setting.ProxyView;
import com.raincat.dolby_beta.view.setting.ResetModuleView;
import com.raincat.dolby_beta.view.setting.SignSongDailyView;
import com.raincat.dolby_beta.view.setting.SignSongSelfView;
import com.raincat.dolby_beta.view.setting.SignView;
import com.raincat.dolby_beta.view.setting.TitleView;
import com.raincat.dolby_beta.view.setting.UpdateView;
import com.raincat.dolby_beta.view.setting.ListenView;
import com.raincat.dolby_beta.view.setting.WarnView;


import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;



/**
 * <pre>
 *     author : RainCat
 *     time   : 2019/10/26
 *     desc   : 设置
 *     version: 1.0
 * </pre>
 */
public class SettingHook {
    private static final String TAG = "dolby_beta";
    private String SettingActivity;
    private TextView titleView, subView;
    private LinearLayout dialogRoot, dialogProxyRoot, dialogBeautyRoot, dialogSidebarRoot;

    private BroadcastReceiver broadcastReceiver;
    // 标记是否已经注入过菜单，防止onResume多次调用导致重复注入
    private boolean menuInjected = false;
    // 标记是否已经在侧边栏中注入过菜单
    private boolean sidebarMenuInjected = false;
    // 保存版本代码，供后续使用
    private int versionCode;

    public SettingHook(Context context,int versionCode) {
        //一切的前提，没这个页面连设置都进不去
        if(versionCode>=8007000)
        {
            SettingActivity="com.netease.cloudmusic.music.biz.setting.activity.SettingActivity";
        }else
        {
            SettingActivity="com.netease.cloudmusic.activity.SettingActivity";
        }
        this.versionCode = versionCode;

        XposedBridge.log("SettingHook: 初始化，目标类=" + SettingActivity);
        Log.d(TAG, "SettingHook: 初始化，目标类=" + SettingActivity);

        // 策略1：直接注册ActivityLifecycleCallbacks
        if (context instanceof Application) {
            registerLifecycleCallbacks((Application) context);
        }

        // 策略2：同时hook Application.dispatchActivityResumed作为双重保障
        // registerActivityLifecycleCallbacks在某些情况下可能不触发回调，
        // 但dispatchActivityResumed是系统内部方法，一定会被调用
        hookDispatchActivityResumed();
    }

    /**
     * hook Application.dispatchActivityResumed方法
     * 这是registerActivityLifecycleCallbacks内部调用的分发方法，
     * 所有Activity的onResume都会触发此方法，比LifecycleCallbacks更底层更可靠
     *
     * 新版网易云（9.5.30+）中，设置页面可能不再使用SettingActivity，
     * 因此改为检测Activity布局中是否包含preferenceRoot来识别设置页面
     */
    private void hookDispatchActivityResumed() {
        try {
            XposedBridge.hookAllMethods(Application.class, "dispatchActivityResumed", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.args[0];
                    String className = activity.getClass().getName();

                    // 记录所有Activity的resume，用于调试
                    XposedBridge.log("SettingHook: dispatchActivityResumed - " + className);
                    Log.d(TAG, "dispatchActivityResumed - " + className);

                    // 策略1：匹配SettingActivity类名（旧版网易云）
                    if (className.equals(SettingActivity)) {
                        if (menuInjected) return;
                        menuInjected = true;
                        XposedBridge.log("SettingHook: 检测到SettingActivity，开始注入菜单");
                        Log.d(TAG, "检测到SettingActivity，开始注入菜单");
                        registerBroadcastReceiver(activity);
                        initView(activity);
                        return;
                    }

                    // 策略2：检测布局中是否包含preferenceRoot（兼容其他原生载体）
                    if (!menuInjected) {
                        try {
                            int resId = activity.getResources().getIdentifier("preferenceRoot", "id", activity.getPackageName());
                            if (resId != 0) {
                                View rootView = activity.findViewById(resId);
                                if (rootView != null) {
                                    menuInjected = true;
                                    XposedBridge.log("SettingHook: 通过preferenceRoot检测到设置页面，Activity=" + className);
                                    Log.d(TAG, "通过preferenceRoot检测到设置页面，Activity=" + className);
                                    registerBroadcastReceiver(activity);
                                    initView(activity);
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            // 资源查找失败，忽略
                        }
                    }

                    // 策略3：在MainActivity中注入侧边栏菜单
                    // 新版网易云（9.5.30+）设置页面已迁移到RN，无法在原生布局中注入
                    // 改为在侧边栏底部添加入口菜单
                    if (className.equals("com.netease.cloudmusic.activity.MainActivity")) {
                        if (!sidebarMenuInjected) {
                            // 延迟执行，等待侧边栏懒加载完成
                            activity.getWindow().getDecorView().post(() -> {
                                tryInjectSidebarMenu(activity);
                            });
                            // 再次延迟尝试，侧边栏可能还没加载
                            activity.getWindow().getDecorView().postDelayed(() -> {
                                tryInjectSidebarMenu(activity);
                            }, 2000);
                        }
                    }
                }
            });

            // hook dispatchActivityDestroyed用于清理
            XposedBridge.hookAllMethods(Application.class, "dispatchActivityDestroyed", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.args[0];
                    if (!activity.getClass().getName().equals(SettingActivity)) return;
                    menuInjected = false;
                    if (broadcastReceiver != null) {
                        try {
                            activity.unregisterReceiver(broadcastReceiver);
                        } catch (Exception ignored) {}
                    }
                }
            });

            XposedBridge.log("SettingHook: 已hook Application.dispatchActivityResumed");
            Log.d(TAG, "已hook Application.dispatchActivityResumed");
        } catch (Exception e) {
            XposedBridge.log("SettingHook: hook dispatchActivityResumed失败 - " + e.getMessage());
        }
    }

    /**
     * 注册ActivityLifecycleCallbacks，监听SettingActivity的生命周期
     * 在Application完全初始化后调用此方法是可靠的
     */
    private void registerLifecycleCallbacks(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {
                // 记录所有Activity的创建，用于调试
                String className = activity.getClass().getName();
                XposedBridge.log("SettingHook: onActivityCreated - " + className);
                Log.d(TAG, "onActivityCreated - " + className);
            }

            @Override
            public void onActivityStarted(Activity activity) {}

            @Override
            public void onActivityResumed(Activity activity) {
                String className = activity.getClass().getName();
                // 记录所有Activity的resume，用于调试
                XposedBridge.log("SettingHook: onActivityResumed - " + className);
                Log.d(TAG, "onActivityResumed - " + className);

                // 策略1：匹配SettingActivity类名
                if (className.equals(SettingActivity)) {
                    if (menuInjected) return;
                    menuInjected = true;
                    XposedBridge.log("SettingHook: 检测到SettingActivity onResume，类名=" + className);
                    Log.d(TAG, "检测到SettingActivity onResume，类名=" + className);
                    registerBroadcastReceiver(activity);
                    initView(activity);
                    return;
                }

                // 策略2：检测布局中是否包含preferenceRoot
                if (!menuInjected) {
                    try {
                        int resId = activity.getResources().getIdentifier("preferenceRoot", "id", activity.getPackageName());
                        if (resId != 0) {
                            View rootView = activity.findViewById(resId);
                            if (rootView != null) {
                                menuInjected = true;
                                XposedBridge.log("SettingHook: LifecycleCallbacks通过preferenceRoot检测到设置页面，Activity=" + className);
                                Log.d(TAG, "LifecycleCallbacks通过preferenceRoot检测到设置页面，Activity=" + className);
                                registerBroadcastReceiver(activity);
                                initView(activity);
                            }
                        }
                    } catch (Exception e) {
                        // 资源查找失败，忽略
                    }
                }
            }

            @Override
            public void onActivityPaused(Activity activity) {}

            @Override
            public void onActivityStopped(Activity activity) {}

            @Override
            public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity activity) {
                if (!activity.getClass().getName().equals(SettingActivity)) return;
                menuInjected = false;
                if (broadcastReceiver != null) {
                    try {
                        activity.unregisterReceiver(broadcastReceiver);
                    } catch (Exception ignored) {}
                }
            }
        });
        XposedBridge.log("SettingHook: ActivityLifecycleCallbacks注册成功");
        Log.d(TAG, "ActivityLifecycleCallbacks注册成功");
    }

    /**
     * onResume时调用，视图已创建完毕，直接执行注入
     */
    private void initView(Context context) {
        Activity activity = (Activity) context;
        try {
            doInitView(activity);
        } catch (Exception e) {
            XposedBridge.log("SettingHook: 菜单注入失败 - " + e.getMessage());
            Log.e(TAG, "菜单注入失败 - " + e.getMessage(), e);
            e.printStackTrace();
        }
    }

    /**
     * 实际执行菜单注入的方法
     * 将入口菜单添加到设置页面的preferenceRoot布局中
     */
    private void doInitView(Activity activity) {
        // 通过资源ID名称查找preferenceRoot
        LinearLayout preferenceRoot = findPreferenceRoot(activity);
        if (preferenceRoot == null) {
            XposedBridge.log("SettingHook: 未找到preferenceRoot，尝试遍历整个视图树");
            Log.w(TAG, "未找到preferenceRoot，尝试遍历整个视图树");
            // 兜底方案：遍历整个视图树，找到包含最多子View的LinearLayout（通常是preferenceRoot）
            preferenceRoot = findPreferenceRootByTraversal(activity);
        }
        if (preferenceRoot == null) {
            XposedBridge.log("SettingHook: 所有方式均未找到preferenceRoot，跳过菜单注入");
            Log.e(TAG, "所有方式均未找到preferenceRoot，跳过菜单注入");
            return;
        }

        XposedBridge.log("SettingHook: 找到preferenceRoot，子View数量=" + preferenceRoot.getChildCount());
        Log.d(TAG, "找到preferenceRoot，子View数量=" + preferenceRoot.getChildCount());

        // 从已有的设置项中获取样式参考（找第一个包含TextView的子ViewGroup）
        TextView originalText = findFirstTextView(preferenceRoot);

        // 创建菜单入口布局，模仿已有设置项的样式
        LinearLayout linearLayout = new LinearLayout(activity);
        linearLayout.setGravity(Gravity.CENTER_VERTICAL);
        linearLayout.setOrientation(LinearLayout.HORIZONTAL);
        // 如果找到了参考样式，复制布局参数和背景
        if (preferenceRoot.getChildCount() > 0 && preferenceRoot.getChildAt(0) instanceof ViewGroup) {
            ViewGroup firstItem = (ViewGroup) preferenceRoot.getChildAt(0);
            ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (firstItem.getLayoutParams() != null) {
                layoutParams.width = firstItem.getLayoutParams().width;
                layoutParams.height = firstItem.getLayoutParams().height;
            }
            linearLayout.setLayoutParams(layoutParams);
            linearLayout.setBackground(firstItem.getBackground());
            // 复制内边距，确保与已有设置项对齐
            linearLayout.setPadding(
                    firstItem.getPaddingLeft(), firstItem.getPaddingTop(),
                    firstItem.getPaddingRight(), firstItem.getPaddingBottom());
        } else {
            linearLayout.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 将菜单入口添加到preferenceRoot的最前面（位置0）
        // 父菜单：preferenceRoot（LinearLayout，包含所有设置项的垂直列表）
        // 兄弟菜单：位置1是原第一个子项（如"账号与安全"设置项）
        preferenceRoot.addView(linearLayout, 0);

        titleView = new TextView(activity);
        linearLayout.addView(titleView);
        subView = new TextView(activity);
        linearLayout.addView(subView);
        refresh();

        // 应用文字样式
        if (originalText != null) {
            titleView.setTextColor(originalText.getTextColors());
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalText.getTextSize());
            titleView.setPadding(originalText.getPaddingLeft() == 0 ? Tools.dp2px(activity, 10) : originalText.getPaddingLeft(), 0, 0, 0);
            subView.setTextColor(originalText.getTextColors());
            subView.setTextSize(TypedValue.COMPLEX_UNIT_PX, (int) (originalText.getTextSize() / 3.0 * 2.0));
        }

        linearLayout.setOnClickListener(view -> showSettingDialog(activity));

        XposedBridge.log("SettingHook: 菜单注入成功，已添加到preferenceRoot位置0");
        Log.d(TAG, "菜单注入成功，已添加到preferenceRoot位置0");
    }

    /**
     * 查找设置页面的根布局（preferenceRoot）
     * 优先通过资源ID名称查找（最可靠），其次通过视图层级遍历查找（兜底方案）
     */
    private LinearLayout findPreferenceRoot(Activity activity) {
        // 方法1：通过资源ID名称"preferenceRoot"直接查找（最可靠的方式）
        int resId = activity.getResources().getIdentifier("preferenceRoot", "id", activity.getPackageName());
        if (resId != 0) {
            View view = activity.findViewById(resId);
            if (view instanceof LinearLayout) {
                XposedBridge.log("SettingHook: 通过资源ID找到preferenceRoot");
                return (LinearLayout) view;
            }
            XposedBridge.log("SettingHook: 资源ID找到的view不是LinearLayout，type=" + (view != null ? view.getClass().getName() : "null"));
        } else {
            XposedBridge.log("SettingHook: 资源ID未找到preferenceRoot，resId=0");
        }

        // 方法2：通过视图层级遍历查找（兜底方案）
        View contentView = activity.findViewById(android.R.id.content);
        if (contentView instanceof ViewGroup) {
            return findLinearLayoutUnderScrollView((ViewGroup) contentView);
        }

        return null;
    }

    /**
     * 兜底方案：遍历整个视图树，找到包含最多子View的垂直LinearLayout
     * 设置页面的preferenceRoot通常包含最多的子View（所有设置项）
     */
    private LinearLayout findPreferenceRootByTraversal(Activity activity) {
        View contentView = activity.findViewById(android.R.id.content);
        if (!(contentView instanceof ViewGroup)) {
            return null;
        }
        // 遍历视图树，找到子View最多的LinearLayout
        LinearLayout[] result = new LinearLayout[1];
        int[] maxChildren = new int[]{0};
        findMaxChildrenLinearLayout((ViewGroup) contentView, result, maxChildren, 0);
        return result[0];
    }

    /**
     * 递归遍历视图树，找到子View数量最多的垂直LinearLayout
     * 限制递归深度避免性能问题
     */
    private void findMaxChildrenLinearLayout(ViewGroup viewGroup, LinearLayout[] result, int[] maxChildren, int depth) {
        if (depth > 5) return; // 限制递归深度
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout ll = (LinearLayout) child;
                // 只考虑垂直方向的LinearLayout（设置列表是垂直的）
                if (ll.getOrientation() == LinearLayout.VERTICAL && ll.getChildCount() > maxChildren[0]) {
                    maxChildren[0] = ll.getChildCount();
                    result[0] = ll;
                }
            }
            if (child instanceof ViewGroup) {
                findMaxChildrenLinearLayout((ViewGroup) child, result, maxChildren, depth + 1);
            }
        }
    }

    /**
     * 递归查找ScrollView下的LinearLayout（即preferenceRoot）
     * 网易云设置页面的布局结构为：ScrollView -> LinearLayout(preferenceRoot)
     */
    private LinearLayout findLinearLayoutUnderScrollView(ViewGroup viewGroup) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            // 找到ScrollView，其第一个子View就是preferenceRoot
            if (child instanceof ScrollView) {
                ScrollView scrollView = (ScrollView) child;
                if (scrollView.getChildCount() > 0 && scrollView.getChildAt(0) instanceof LinearLayout) {
                    return (LinearLayout) scrollView.getChildAt(0);
                }
            }
            // 继续在子ViewGroup中递归查找
            if (child instanceof ViewGroup) {
                LinearLayout result = findLinearLayoutUnderScrollView((ViewGroup) child);
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * 尝试在侧边栏底部注入入口菜单
     * 新版网易云（9.5.30+）设置页面已迁移到RN，无法在原生布局中注入
     * 改为在侧边栏底部（"我的客服"下方）添加入口菜单
     *
     * 侧边栏布局结构（推测）：
     * MainActivity -> DrawerLayout -> 侧边栏容器 -> 菜单列表
     * 需要找到包含"设置"和"我的客服"等菜单项的父布局
     */
    private void tryInjectSidebarMenu(Activity activity) {
        if (sidebarMenuInjected) return;

        try {
            // 查找侧边栏中的菜单区域
            // 方法1：通过资源ID查找侧边栏容器
            View sidebarContainer = null;

            // 尝试查找常见的侧边栏容器ID
            String[] containerIds = {"mainDrawerContainer", "sidebar_container", "drawer_container", "nav_view"};
            for (String idName : containerIds) {
                int resId = activity.getResources().getIdentifier(idName, "id", activity.getPackageName());
                if (resId != 0) {
                    sidebarContainer = activity.findViewById(resId);
                    if (sidebarContainer != null) {
                        XposedBridge.log("SettingHook: 通过资源ID找到侧边栏容器: " + idName);
                        Log.d(TAG, "通过资源ID找到侧边栏容器: " + idName);
                        break;
                    }
                }
            }

            // 方法2：遍历视图树查找侧边栏
            if (sidebarContainer == null) {
                // 打印MainActivity的布局结构用于诊断
                View contentView = activity.findViewById(android.R.id.content);
                String layoutInfo = dumpViewHierarchy(contentView, 0, 4);
                XposedBridge.log("SettingHook: MainActivity 布局结构:\n" + layoutInfo);
                Log.d(TAG, "MainActivity 布局结构:\n" + layoutInfo);

                // 查找DrawerLayout
                sidebarContainer = findViewByType(contentView, "HackyDrawerLayout");
                if (sidebarContainer == null) {
                    sidebarContainer = findViewByType(contentView, "DrawerLayout");
                }
            }

            if (sidebarContainer == null) {
                XposedBridge.log("SettingHook: 未找到侧边栏容器，跳过侧边栏菜单注入");
                Log.d(TAG, "未找到侧边栏容器，跳过侧边栏菜单注入");
                return;
            }

            // 诊断：打印侧边栏容器的视图层级
            String sidebarInfo = dumpViewHierarchy(sidebarContainer, 0, 5);
            XposedBridge.log("SettingHook: mainDrawerContainer 布局结构:\n" + sidebarInfo);
            Log.d(TAG, "mainDrawerContainer 布局结构:\n" + sidebarInfo);

            // 在侧边栏容器中查找包含"我的客服"文字的视图
            // 通过遍历找到侧边栏底部的菜单列表
            View customerServiceView = findViewByText(sidebarContainer, "我的客服");
            // 也尝试查找"设置"文字
            if (customerServiceView == null) {
                customerServiceView = findViewByText(sidebarContainer, "设置");
            }
            if (customerServiceView == null) {
                XposedBridge.log("SettingHook: 未找到'我的客服'或'设置'菜单项，侧边栏可能未加载或使用RN渲染");
                Log.d(TAG, "未找到'我的客服'或'设置'菜单项，侧边栏可能未加载或使用RN渲染");

                // 如果侧边栏是RN渲染的，改用悬浮按钮方式
                // 在侧边栏容器中添加一个悬浮入口按钮
                injectFloatingMenuInSidebar(activity, sidebarContainer);
                return;
            }

            XposedBridge.log("SettingHook: 找到'我的客服'菜单项，开始注入侧边栏菜单");
            Log.d(TAG, "找到'我的客服'菜单项，开始注入侧边栏菜单");

            // 获取"我的客服"菜单项的样式参考
            ViewGroup menuItemParent = (ViewGroup) customerServiceView.getParent();
            if (menuItemParent == null) {
                XposedBridge.log("SettingHook: '我的客服'没有父视图");
                return;
            }

            // 诊断：打印"我的客服"的视图层级信息
            XposedBridge.log("SettingHook: '我的客服'视图类型=" + customerServiceView.getClass().getName());
            XposedBridge.log("SettingHook: '我的客服'父视图类型=" + menuItemParent.getClass().getName() + ", 子View数=" + menuItemParent.getChildCount());
            Log.d(TAG, "'我的客服'视图类型=" + customerServiceView.getClass().getName());
            Log.d(TAG, "'我的客服'父视图类型=" + menuItemParent.getClass().getName() + ", 子View数=" + menuItemParent.getChildCount());

            // 检查是否是RecyclerView，RecyclerView不能直接addView
            ViewParent currentParent = menuItemParent.getParent();
            ViewGroup menuList = null;
            boolean isRecyclerView = false;

            // 向上遍历查找合适的菜单列表容器
            for (int i = 0; i < 8 && currentParent != null; i++) {
                if (currentParent instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) currentParent;
                    String parentType = group.getClass().getName();
                    XposedBridge.log("SettingHook: 第" + i + "层父视图: " + parentType + ", 子View数=" + group.getChildCount());
                    Log.d(TAG, "第" + i + "层父视图: " + parentType + ", 子View数=" + group.getChildCount());

                    // 检测是否是RecyclerView
                    if (parentType.contains("RecyclerView")) {
                        isRecyclerView = true;
                        XposedBridge.log("SettingHook: 检测到RecyclerView，需要使用Adapter方式注入");
                        Log.d(TAG, "检测到RecyclerView，需要使用Adapter方式注入");
                        break;
                    }

                    // 找到包含多个菜单项的容器（至少2个子View）
                    if (group.getChildCount() >= 2 && menuList == null) {
                        menuList = group;
                    }
                }
                currentParent = currentParent.getParent();
            }

            if (isRecyclerView) {
                // RecyclerView不能直接addView，需要换一种方式
                // 改为在"我的客服"的父视图中添加
                XposedBridge.log("SettingHook: 侧边栏使用RecyclerView，改为在menuItemParent中添加");
                Log.d(TAG, "侧边栏使用RecyclerView，改为在menuItemParent中添加");

                // 创建入口菜单项，模仿"我的客服"的样式
                LinearLayout menuItem = createSidebarMenuItem(activity, menuItemParent);

                // 找到menuItemParent在RecyclerView中的位置
                // 在menuItemParent后面插入一个新的菜单项
                // 由于RecyclerView管理子View，我们需要在RecyclerView外层操作
                // 改为在menuItemParent的同级容器中添加
                ViewParent grandParent = menuItemParent.getParent();
                if (grandParent instanceof ViewGroup) {
                    ViewGroup gp = (ViewGroup) grandParent;
                    int index = gp.indexOfChild(menuItemParent);
                    XposedBridge.log("SettingHook: grandParent类型=" + gp.getClass().getName() + ", menuItemParent索引=" + index);
                    Log.d(TAG, "grandParent类型=" + gp.getClass().getName() + ", menuItemParent索引=" + index);
                    gp.addView(menuItem, index + 1);
                    sidebarMenuInjected = true;
                    XposedBridge.log("SettingHook: 侧边栏菜单注入成功（RecyclerView方式）");
                    Log.d(TAG, "侧边栏菜单注入成功（RecyclerView方式）");
                } else {
                    XposedBridge.log("SettingHook: grandParent不是ViewGroup，无法注入");
                }
                return;
            }

            if (menuList == null) {
                XposedBridge.log("SettingHook: 未找到菜单列表父布局");
                Log.d(TAG, "未找到菜单列表父布局");
                return;
            }

            // 创建入口菜单项，模仿"我的客服"的样式
            LinearLayout menuItem = createSidebarMenuItem(activity, menuItemParent);

            // 将入口菜单添加到"我的客服"下方
            int customerServiceIndex = menuList.indexOfChild(menuItemParent);
            XposedBridge.log("SettingHook: menuList类型=" + menuList.getClass().getName() + ", customerServiceIndex=" + customerServiceIndex);
            Log.d(TAG, "menuList类型=" + menuList.getClass().getName() + ", customerServiceIndex=" + customerServiceIndex);
            menuList.addView(menuItem, customerServiceIndex + 1);

            sidebarMenuInjected = true;
            XposedBridge.log("SettingHook: 侧边栏菜单注入成功");
            Log.d(TAG, "侧边栏菜单注入成功");

        } catch (Exception e) {
            XposedBridge.log("SettingHook: 侧边栏菜单注入失败 - " + e.getMessage());
            Log.e(TAG, "侧边栏菜单注入失败", e);
        }
    }

    /**
     * 在侧边栏容器中注入悬浮入口按钮
     * 当侧边栏使用RN渲染，无法找到原生菜单项时使用此方法
     * 在侧边栏容器的顶部5%位置添加一个居中的入口按钮
     */
    private void injectFloatingMenuInSidebar(Activity activity, View sidebarContainer) {
        if (sidebarMenuInjected) return;
        if (!(sidebarContainer instanceof ViewGroup)) return;

        ViewGroup container = (ViewGroup) sidebarContainer;

        // 注册广播接收器，确保子对话框（音源代理设置等）能正常弹出
        registerBroadcastReceiver(activity);

        // 创建悬浮入口按钮
        TextView floatingBtn = new TextView(activity);
        floatingBtn.setText("杜比大喇叭β");
        floatingBtn.setTextSize(16);
        floatingBtn.setTextColor(Color.BLACK); // 纯黑文字
        // 高度等文字高，仅保留水平内边距
        floatingBtn.setPadding(Tools.dp2px(activity, 16), 0,
                Tools.dp2px(activity, 16), 0);
        floatingBtn.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT); // 文字居左
        floatingBtn.setBackgroundColor(Color.WHITE); // 纯白背景

        // 使用FrameLayout.LayoutParams
        // 宽度与文字等宽（WRAP_CONTENT），居中显示
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        // 放在窗口顶部往下5%的位置，水平居中
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        // 顶部边距为容器高度的5%（延迟设置，因为容器可能还没布局完成）
        floatingBtn.setLayoutParams(params);

        // 在布局完成后设置顶部边距（需要知道容器实际高度）
        container.post(() -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) floatingBtn.getLayoutParams();
            lp.topMargin = (int) (container.getHeight() * 0.05);
            floatingBtn.setLayoutParams(lp);
        });

        // 点击事件：显示设置对话框
        floatingBtn.setOnClickListener(view -> showSettingDialog(activity));

        // 将按钮添加到侧边栏容器
        container.addView(floatingBtn);

        sidebarMenuInjected = true;
        XposedBridge.log("SettingHook: 悬浮入口按钮注入成功（顶部5%）");
        Log.d(TAG, "悬浮入口按钮注入成功（顶部5%）");
    }

    /**
     * 创建侧边栏菜单项，模仿已有菜单项的样式
     */
    private LinearLayout createSidebarMenuItem(Activity activity, ViewGroup referenceItem) {
        LinearLayout menuItem = new LinearLayout(activity);
        menuItem.setGravity(Gravity.CENTER_VERTICAL);
        menuItem.setOrientation(LinearLayout.HORIZONTAL);

        // 复制参考菜单项的布局参数和样式
        ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (referenceItem.getLayoutParams() != null) {
            layoutParams.width = referenceItem.getLayoutParams().width;
            layoutParams.height = referenceItem.getLayoutParams().height;
        }
        menuItem.setLayoutParams(layoutParams);
        menuItem.setBackground(referenceItem.getBackground());
        menuItem.setPadding(
                referenceItem.getPaddingLeft(), referenceItem.getPaddingTop(),
                referenceItem.getPaddingRight(), referenceItem.getPaddingBottom());

        // 查找参考菜单项中的TextView获取样式
        TextView refText = findFirstTextView(referenceItem);

        titleView = new TextView(activity);
        titleView.setText("杜比大喇叭β");
        if (refText != null) {
            titleView.setTextColor(refText.getTextColors());
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, refText.getTextSize());
            titleView.setPadding(refText.getPaddingLeft() == 0 ? Tools.dp2px(activity, 10) : refText.getPaddingLeft(), 0, 0, 0);
        }
        menuItem.addView(titleView);

        // 点击事件：显示设置对话框
        menuItem.setOnClickListener(view -> showSettingDialog(activity));

        return menuItem;
    }

    /**
     * 在视图树中查找包含指定文字的视图
     */
    private View findViewByText(View root, String text) {
        if (root instanceof TextView) {
            CharSequence viewText = ((TextView) root).getText();
            if (viewText != null && viewText.toString().contains(text)) {
                return root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findViewByText(group.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * 查找菜单项的父布局（菜单列表容器）
     * 从"我的客服"视图向上遍历，找到包含多个菜单项的父布局
     */
    private ViewGroup findMenuListParent(View menuItemView) {
        ViewParent parent = menuItemView.getParent();
        // 向上遍历3层，找到合适的菜单列表容器
        for (int i = 0; i < 5 && parent != null; i++) {
            if (parent instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) parent;
                // 如果这个ViewGroup包含多个子View（至少2个菜单项），可能是菜单列表
                if (group.getChildCount() >= 2) {
                    return group;
                }
            }
            parent = parent.getParent();
        }
        return null;
    }

    /**
     * 在视图树中查找指定类型的视图
     */
    private View findViewByType(View root, String typeName) {
        if (root == null) return null;
        if (root.getClass().getSimpleName().contains(typeName)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findViewByType(group.getChildAt(i), typeName);
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * 递归打印视图层级结构，用于诊断布局
     * @param view 要分析的视图
     * @param depth 当前递归深度
     * @param maxDepth 最大递归深度
     * @return 视图层级描述字符串
     */
    private String dumpViewHierarchy(View view, int depth, int maxDepth) {
        if (view == null || depth > maxDepth) return "";
        StringBuilder sb = new StringBuilder();
        String indent = "  ".repeat(depth);
        String className = view.getClass().getSimpleName();
        String idStr = "";
        if (view.getId() != View.NO_ID && view.getId() > 0) {
            try {
                idStr = " id=" + view.getResources().getResourceEntryName(view.getId());
            } catch (Exception e) {
                idStr = " id=0x" + Integer.toHexString(view.getId());
            }
        }
        sb.append(indent).append(className).append(idStr);
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0 && text.length() < 50) {
                sb.append(" text=\"").append(text).append("\"");
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            sb.append(" children=").append(vg.getChildCount());
            for (int i = 0; i < vg.getChildCount(); i++) {
                sb.append("\n").append(dumpViewHierarchy(vg.getChildAt(i), depth + 1, maxDepth));
            }
        }
        return sb.toString();
    }

    /**
     * 在ViewGroup中查找第一个可见的TextView，用于获取文字样式
     */
    private TextView findFirstTextView(ViewGroup viewGroup) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof TextView && child.getVisibility() == View.VISIBLE) {
                return (TextView) child;
            }
            if (child instanceof ViewGroup) {
                TextView result = findFirstTextView((ViewGroup) child);
                if (result != null) return result;
            }
        }
        return null;
    }

    @SuppressLint("SetTextI18n")
    private void refresh() {
        if (titleView == null || subView == null) return;
        titleView.setText("杜比大喇叭β");
        if (ExtraHelper.getExtraDate(ExtraHelper.USER_ID).equals("-1")) {
            subView.setText("（USERID获取失败）");
        } else if (!SettingHelper.getInstance().getSetting(SettingHelper.master_key))
            subView.setText("（已关闭）");
        else if (ExtraHelper.getExtraDate(ExtraHelper.SCRIPT_STATUS).equals("1"))
            subView.setText("（UnblockNeteaseMusic正在运行）");
        else
            subView.setText("（UnblockNeteaseMusic停止运行）");
    }

    private void registerBroadcastReceiver(final Context context) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(SettingHelper.refresh_setting);
        intentFilter.addAction(SettingHelper.proxy_setting);
        intentFilter.addAction(SettingHelper.beauty_setting);
        intentFilter.addAction(SettingHelper.sidebar_setting);
        intentFilter.addAction(SettingHelper.background_setting);
        intentFilter.addAction(SettingHelper.proxy_configuration_setting);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent.getAction().equals(SettingHelper.refresh_setting)) {
                    if (dialogRoot != null)
                        for (int i = 0; i < dialogRoot.getChildCount(); i++) {
                            if (dialogRoot.getChildAt(i) instanceof BaseDialogItem)
                                ((BaseDialogItem) dialogRoot.getChildAt(i)).refresh();
                        }
                    if (dialogProxyRoot != null)
                        for (int i = 0; i < dialogProxyRoot.getChildCount(); i++) {
                            if (dialogProxyRoot.getChildAt(i) instanceof BaseDialogItem)
                                ((BaseDialogItem) dialogProxyRoot.getChildAt(i)).refresh();
                            else if (dialogProxyRoot.getChildAt(i) instanceof BaseDialogInputItem)
                                ((BaseDialogInputItem) dialogProxyRoot.getChildAt(i)).refresh();
                        }
                    if (dialogBeautyRoot != null)
                        for (int i = 0; i < dialogBeautyRoot.getChildCount(); i++) {
                            if (dialogBeautyRoot.getChildAt(i) instanceof BaseDialogItem)
                                ((BaseDialogItem) dialogBeautyRoot.getChildAt(i)).refresh();
                        }
                } else if (intent.getAction().equals(SettingHelper.proxy_setting)) {
                    showProxyDialog(context);
                } else if (intent.getAction().equals(SettingHelper.beauty_setting)) {
                    showBeautyDialog(context);
                } else if (intent.getAction().equals(SettingHelper.sidebar_setting)) {
                    showSidebarDialog(context);
                } else if (intent.getAction().equals(SettingHelper.background_setting)) {
                    showPlayerBackgroundDialog(context);
                } else if (intent.getAction().equals(SettingHelper.proxy_configuration_setting)) {
                    showProxyConfigurationDialog(context);
                }
            }
        };
        context.registerReceiver(broadcastReceiver, intentFilter);
    }

    private void showSettingDialog(final Context context) {
        dialogRoot = new BaseDialogItem(context);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(dialogRoot);

        MasterView masterView = new MasterView(context);
        DexView dexView = new DexView(context);
        dexView.setBaseOnView(masterView);
        WarnView warnView = new WarnView(context);
        warnView.setBaseOnView(masterView);
        BlackView blackView = new BlackView(context);
        blackView.setBaseOnView(masterView);
        ListenView listenView = new ListenView(context);
        listenView.setBaseOnView(masterView);
        FixCommentView fixCommentView = new FixCommentView(context);
        fixCommentView.setBaseOnView(masterView);
        UpdateView updateView = new UpdateView(context);
        updateView.setBaseOnView(masterView);
        SignView signView = new SignView(context);
        signView.setBaseOnView(masterView);
        SignSongDailyView signSongDailyView = new SignSongDailyView(context);
        signSongDailyView.setBaseOnView(masterView);
        SignSongSelfView signSongSelfView = new SignSongSelfView(context);
        signSongSelfView.setBaseOnView(masterView);
        ProxyView proxyView = new ProxyView(context);
        proxyView.setBaseOnView(masterView);
        BeautyView beautyView = new BeautyView(context);
        beautyView.setBaseOnView(masterView);
        ResetModuleView resetModuleView = new ResetModuleView(context);


        dialogRoot.addView(new TitleView(context));
        dialogRoot.addView(masterView);
        dialogRoot.addView(dexView);
        dialogRoot.addView(warnView);
        dialogRoot.addView(blackView);
        dialogRoot.addView(listenView);
        dialogRoot.addView(fixCommentView);
        dialogRoot.addView(updateView);
        dialogRoot.addView(signView);
        dialogRoot.addView(signSongDailyView);
        dialogRoot.addView(signSongSelfView);
        dialogRoot.addView(proxyView);
        dialogRoot.addView(beautyView);
        dialogRoot.addView(resetModuleView);

        dialogRoot.addView(new AboutView(context));
        new AlertDialog.Builder(context)
                .setView(scrollView)
                .setCancelable(false)
                .setPositiveButton("确定", (dialogInterface, i) -> refresh())
                .setNegativeButton("重启网易云", (dialogInterface, i) -> restartApplication(context)).show();
    }

    private void showProxyDialog(final Context context) {
        dialogProxyRoot = new BaseDialogItem(context);
        dialogProxyRoot.setOrientation(LinearLayout.VERTICAL);
        ProxyMasterView proxyMasterView = new ProxyMasterView(context);
        ProxyCoverView proxyCoverView = new ProxyCoverView(context);
        proxyCoverView.setBaseOnView(proxyMasterView);
        ProxyServerView ProxyServerView = new ProxyServerView(context);
        ProxyServerView.setBaseOnView(proxyMasterView);
        ProxyPriorityView proxyPriorityView = new ProxyPriorityView(context);
        proxyPriorityView.setBaseOnView(proxyMasterView);
        ProxyFlacView proxyFlacView = new ProxyFlacView(context);
        proxyFlacView.setBaseOnView(proxyMasterView);
        ProxyGrayView proxyGrayView = new ProxyGrayView(context);
        proxyGrayView.setBaseOnView(proxyMasterView);
        ProxyConfigurationView proxyConfigurationView = new ProxyConfigurationView(context);
        proxyConfigurationView.setBaseOnView(proxyMasterView);


        dialogProxyRoot.addView(new ProxyTitleView(context));
        dialogProxyRoot.addView(proxyMasterView);
        dialogProxyRoot.addView(proxyCoverView);
        dialogProxyRoot.addView(ProxyServerView);
        dialogProxyRoot.addView(proxyPriorityView);
        dialogProxyRoot.addView(proxyFlacView);
        dialogProxyRoot.addView(proxyGrayView);
        dialogProxyRoot.addView(proxyConfigurationView);

        new AlertDialog.Builder(context)
                .setView(dialogProxyRoot)
                .setCancelable(true)
                .setPositiveButton("仅保存", (dialogInterface, i) -> refresh())
                .setNegativeButton("保存并重启", (dialogInterface, i) -> restartApplication(context)).show();
    }
    private void showProxyConfigurationDialog(final Context context) {
        dialogProxyRoot = new BaseDialogItem(context);
        dialogProxyRoot.setOrientation(LinearLayout.VERTICAL);
        ProxyHttpView proxyHttpView = new ProxyHttpView(context);
        ProxyPortView proxyPortView = new ProxyPortView(context);
       // ProxyKuwoView proxykuwoView = new ProxyKuwoView(context);
        ProxyQqView proxyqqView = new ProxyQqView(context);
        ProxyMiguView proxymiguView = new ProxyMiguView(context);

        dialogProxyRoot.addView(new ProxyConfigurationTitleView(context));
        dialogProxyRoot.addView(proxyHttpView);
        dialogProxyRoot.addView(proxyPortView);
        // 隐藏代理源和QQCookie配置（当前代理逻辑不再需要用户手动配置）
       // dialogProxyRoot.addView(new ProxyOriginalView(context));
       // dialogProxyRoot.addView(proxyqqView);
       // dialogProxyRoot.addView(proxymiguView);
        new AlertDialog.Builder(context)
                .setView(dialogProxyRoot)
                .setCancelable(true)
                .setPositiveButton("仅保存", (dialogInterface, i) -> refresh())
                .setNegativeButton("保存并重启", (dialogInterface, i) -> restartApplication(context)).show();
    }
    private void showPlayerBackgroundDialog(final Context context) {
        dialogBeautyRoot = new BaseDialogItem(context);
        dialogBeautyRoot.setOrientation(LinearLayout.VERTICAL);
        BackgroundMasterView backgroundMasterView = new BackgroundMasterView(context);
        BackgroundPictureUrlView backgroundPictureUrlView = new BackgroundPictureUrlView(context);
        BackgroundBlurRadiusView backgroundBlurRadiusView = new BackgroundBlurRadiusView(context);

        dialogBeautyRoot.addView(new BackgroundTitleView(context));
        dialogBeautyRoot.addView(backgroundMasterView);
        dialogBeautyRoot.addView(backgroundPictureUrlView);
        dialogBeautyRoot.addView(backgroundBlurRadiusView);

        new AlertDialog.Builder(context)
                .setView(dialogBeautyRoot)
                .setCancelable(true)
                .setPositiveButton("仅保存", (dialogInterface, i) -> refresh())
                .setNegativeButton("保存并重启", (dialogInterface, i) -> restartApplication(context)).show();
    }
    private void showBeautyDialog(final Context context) {
        dialogBeautyRoot = new BaseDialogItem(context);
        dialogBeautyRoot.setOrientation(LinearLayout.VERTICAL);
        dialogBeautyRoot.addView(new BeautyTitleView(context));
        dialogBeautyRoot.addView(new BeautyNightModeView(context));
        dialogBeautyRoot.addView(new BeautyTabHideView(context));
        dialogBeautyRoot.addView(new BeautyBannerHideView(context));
        dialogBeautyRoot.addView(new BeautyBubbleHideView(context));
        dialogBeautyRoot.addView(new BeautyKSongHideView(context));
        dialogBeautyRoot.addView(new BeautyBlackHideView(context));
        dialogBeautyRoot.addView(new BeautyRotationView(context));
        dialogBeautyRoot.addView(new BeautyCommentHotView(context));
        dialogBeautyRoot.addView(new PlayerBackgroundView(context));
        dialogBeautyRoot.addView(new BeautySidebarHideView(context));
        new AlertDialog.Builder(context)
                .setView(dialogBeautyRoot)
                .setCancelable(true)
                .setPositiveButton("仅保存", (dialogInterface, i) -> refresh())
                .setNegativeButton("保存并重启", (dialogInterface, i) -> restartApplication(context)).show();
    }

    private void showSidebarDialog(final Context context) {
        dialogSidebarRoot = new BaseDialogItem(context);
        dialogSidebarRoot.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollView = new ScrollView(context);
        scrollView.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(dialogSidebarRoot);

        final LinkedHashMap<String, String> sidebarMap = SidebarEnum.getSidebarEnum();
        final HashMap<String, Boolean> sidebarSettingMap = SettingHelper.getInstance().getSidebarSetting(sidebarMap);
        for (Map.Entry<String, String> entry : sidebarMap.entrySet()) {
            BeautySidebarHideItem item = new BeautySidebarHideItem(context);
            item.initData(sidebarMap, sidebarSettingMap, entry.getKey());
            dialogSidebarRoot.addView(item);
        }

        new AlertDialog.Builder(context)
                .setView(scrollView)
                .setCancelable(true)
                .setPositiveButton("确定", (dialogInterface, i) -> refresh()).show();
    }

    private void restartApplication(Context context) {
        ExtraHelper.setExtraDate(ExtraHelper.SCRIPT_STATUS, "0");
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningAppProcessInfoListist = activityManager.getRunningAppProcesses();
        for (ActivityManager.RunningAppProcessInfo runningAppProcessInfo : runningAppProcessInfoListist) {
            if (runningAppProcessInfo.processName.contains(":play")) {
                android.os.Process.killProcess(runningAppProcessInfo.pid);
                break;
            }
        }
        final Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
}
