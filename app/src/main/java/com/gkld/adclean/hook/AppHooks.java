package com.gkld.adclean.hook;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import com.gkld.adclean.Config;
import com.gkld.adclean.Log;

/**
 * Application 创建时机（v1.7 新增）—— 抢开屏广告的请求窗口。
 *
 * ---------------------------------------------------------------------------
 * 为什么必须在这个时机动手：
 *
 * 开屏广告的请求链是 Application.onCreate 初始化网络库 → SplashActivity.onCreate
 * → SDK 发请求。v1.6 把 OkHttp 钩子放到「首次 onResume + 2.5 秒」，那时开屏广告
 * 早就请求完、素材都下完开始播了 —— 这就是 v1.6 首屏广告重新出现的原因。
 *
 * 而 hook ClassLoader.loadClass 抢早的代价是首启闪退（v1.5 的教训）。
 * 折中办法：hook Instrumentation.callApplicationOnCreate —— 这是 framework 里
 * 「调用 Application.onCreate」的唯一入口，晚于壳解密、早于任何 Activity 创建。
 * 在这个点上用 Class.forName 主动找 okhttp3 完全安全（壳已经解完密了），
 * 拉到就直接装，一点不碰类加载机制。
 * ---------------------------------------------------------------------------
 *
 * 顺带承担第二个职责：弹一条 Toast 说明注入状态。
 * 之前排障最耗时的一步就是「到底装没装上」，日志要另存、另传、另看；
 * 现在打开 App 一眼就知道。
 */
public final class AppHooks {

    private static volatile boolean installed;

    private static volatile boolean degraded;

    private AppHooks() {
    }

    public static void install(ClassLoader cl, boolean isDegraded) {
        degraded = isDegraded;
        if (installed) {
            return;
        }
        installed = true;
        try {
            XposedHelpers.findAndHookMethod("android.app.Instrumentation", cl,
                    "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args == null || param.args.length < 1
                                        || !(param.args[0] instanceof Application)) {
                                    return;
                                }
                                onApplicationCreated((Application) param.args[0]);
                            } catch (Throwable t) {
                                Log.e("Application 创建回调异常", t);
                            }
                        }
                    });
            Log.i("Application 创建监听已就绪");
        } catch (Throwable t) {
            Log.e("Application 创建监听安装失败", t);
        }
    }

    private static void onApplicationCreated(final Application app) {
        try {
            Log.i("Application 已创建: " + app.getClass().getName()
                    + "（壳是否已替换：" + (!app.getClass().getName().startsWith("com.netease"))
                    + "）");
        } catch (Throwable ignored) {
        }

        // 1. 立刻在后台抢装 OkHttp 钩子 —— 开屏广告就在接下来的几百毫秒里发请求
        try {
            HttpHooks.installEarly(app.getClassLoader());
        } catch (Throwable t) {
            Log.e("OkHttp 早期安装调度失败", t);
        }

        // 2. Toast 说明注入状态。延后 1.5 秒，等启动页出来再弹，
        //    否则会被开屏 Activity 的窗口盖住看不见。
        if (Config.SHOW_TOAST) {
            try {
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String msg = "公考雷达去广告 v" + Config.MODULE_VERSION
                                    + (degraded ? " · 降级模式（仅网络层）" : " · 已注入");
                            Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
                        } catch (Throwable ignored) {
                        }
                    }
                }, 1500L);
            } catch (Throwable t) {
                Log.d("Toast 调度失败: " + t);
            }
        }
    }
}
