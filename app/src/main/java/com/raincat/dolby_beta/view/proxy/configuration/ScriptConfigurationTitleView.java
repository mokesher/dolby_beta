package com.raincat.dolby_beta.view.proxy.configuration;

import android.content.Context;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import com.raincat.dolby_beta.helper.SettingHelper;
import com.raincat.dolby_beta.view.BaseDialogItem;

/**
 * 脚本参数配置标题视图
 */
public class ScriptConfigurationTitleView extends BaseDialogItem {
    public ScriptConfigurationTitleView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public ScriptConfigurationTitleView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ScriptConfigurationTitleView(Context context) {
        super(context);
    }

    public void init(Context context, AttributeSet attrs) {
        super.init(context, attrs);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        TextPaint paint = titleView.getPaint();
        paint.setFakeBoldText(true);

        title = SettingHelper.script_configuration_title;
        setData(false, false);
    }
}
