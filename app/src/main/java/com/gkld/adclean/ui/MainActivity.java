package com.gkld.adclean.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.Toast;

import com.gkld.adclean.R;

/**
 * 只是个说明页 + 快捷入口 + 设置开关，不承载任何 hook 逻辑。
 */
public class MainActivity extends Activity {

    private static final String LSPOSED_PKG = "org.lsposed.manager";

    /** 桌面入口（v1.7.2 起直接切换本组件的启用状态，不再用 activity-alias） */
    private static final ComponentName LAUNCHER_ACTIVITY =
            new ComponentName("com.gkld.adclean", "com.gkld.adclean.ui.MainActivity");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button open = findViewById(R.id.btn_lsposed);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLsposed();
            }
        });

        Button copy = findViewById(R.id.btn_copy_tag);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("tag", "GkldAdClean"));
                    Toast.makeText(MainActivity.this, "已复制：GkldAdClean", Toast.LENGTH_SHORT).show();
                }
            }
        });

        setupHideIconSwitch();
    }

    /**
     * 「隐藏桌面图标」开关。
     *
     * 原理：桌面上唯一的 LAUNCHER 就是 MainActivity 本体，把它 DISABLED，
     * 图标就从桌面消失。代价是它自己也没法被显式启动了 —— 想找回必须：
     *   root 终端：pm enable com.gkld.adclean/.ui.MainActivity
     *   或卸载重装（组件状态随卸载清除，回默认显示）。
     * LSPosed 管理器的模块页入口对已禁用组件无效，别指望它。
     *
     * v1.7.1 曾用 activity-alias 做入口来绕开这个代价，但 aapt2 打包的 alias
     * 在部分安装器上直接装不上（badging 连 launchable-activity 都没有），
     * 权衡后回到本方案 —— 代价换兼容性。
     *
     * 状态直接读系统（PackageManager），不需要自己存。
     */
    private void setupHideIconSwitch() {
        Switch hide = findViewById(R.id.switch_hide_icon);
        if (hide == null) {
            return;
        }
        final PackageManager pm = getPackageManager();
        int state = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        try {
            state = pm.getComponentEnabledSetting(LAUNCHER_ACTIVITY);
        } catch (Throwable ignored) {
        }
        // DEFAULT = 没动过 = 图标在桌面
        hide.setChecked(state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED);

        hide.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                try {
                    pm.setComponentEnabledSetting(LAUNCHER_ACTIVITY,
                            isChecked
                                    ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                    : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP);
                    Toast.makeText(MainActivity.this,
                            isChecked ? "已隐藏。找回：root 执行 pm enable com.gkld.adclean/.ui.MainActivity"
                                      : "桌面图标已恢复",
                            Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "设置失败: " + t, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void openLsposed() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(LSPOSED_PKG);
        if (launch != null) {
            startActivity(launch);
            return;
        }
        try {
            Intent i = new Intent();
            i.setClassName(LSPOSED_PKG, "org.lsposed.manager.ui.activity.MainActivity");
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没找到 LSPosed 管理器，请手动打开", Toast.LENGTH_LONG).show();
        }
    }
}
