package com.raincat.dolby_beta.view.proxy;

import android.content.Context;
import android.util.AttributeSet;
import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.view.BaseDialogItem;

/**
 * 脚本参数配置入口视图
 * 点击后弹出脚本参数配置对话框
 */
public class ScriptConfigurationView extends BaseDialogItem {
    public ScriptConfigurationView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public ScriptConfigurationView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ScriptConfigurationView(Context context) {
        super(context);
    }

    @Override
    public void init(Context context, AttributeSet attrs) {
        super.init(context, attrs);
        title = SettingHelper.script_configuration_title;
        key = SettingHelper.script_configuration_key;
        sub = SettingHelper.script_configuration_sub;
        setData(false, false);

        setOnClickListener(view -> {
            sendBroadcast(SettingHelper.script_configuration_setting);
        });
    }
}
