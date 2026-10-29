package com.gkld.adclean.hook;

import android.app.Application;
import android.content.Context;
import android.os.SystemClock;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import com.gkld.adclean.Config;
import com.gkld.adclean.Log;

/**
 * 启动面包屑（v1.4 新增）。
 *
 * 背景：真机反馈「首次启动闪退、第二次正常」，但 LSPosed 日志里**连一行都没有** ——
 * 说明那个进程在模块写下第一条日志之前就死了（或者根本没被注入）。
 * 这种情况下光有崩溃堆栈没用，只能靠「看它走到哪一步」来定位。
 *
 * 这里在启动关键路径上打 5 个点，全部走 Log.i（LSPosed 日志实时落盘，进程猝死也不会丢）：
 *
 *   [启动+xms] handleBindApplication 进入（加固壳开始工作）
 *   [启动+xms] 壳 attachBaseContext 完成
 *   [启动+xms] handleBindApplication 返回（Application 已创建）
 *   [启动+xms] Application.onCreate 进入 / 返回
 *   [启动+xms] 首个 Activity 创建完成: xxx.SplashActivity
 *
 * 判读：
 *   - 一条都没有                          → 崩在 LSPosed 注入之前，或 native 崩溃；
 *   - 停在「handleBindApplication 进入」  → 崩在加固壳解密阶段；
 *   - 停在「attachBaseContext 完成」      → 崩在壳解密之后、onCreate 之前；
 *   - 停在「Activity 创建完成」之后        → 崩在首屏绘制 / 首页逻辑。
 */
public final class BootTrace {

    /** ActivityThread 是 @hide 类，android.jar 里没有，只能走字符串 */
    private static final String ACTIVITY_THREAD = "android.app.ActivityThread";

    private static volatile long bootAt;

    private static volatile boolean installed;

    private BootTrace() {
    }

    public static void install(ClassLoader cl) {
        if (!Config.BOOT_TRACE || installed) {
            return;
        }
        installed = true;
        bootAt = SystemClock.elapsedRealtime();

        hookActivityThread();
        hookApplication();

        Log.i("启动追踪已就绪");
    }

    private static void hookActivityThread() {
        try {
            XposedHelpers.findAndHookMethod(ACTIVITY_THREAD, null, "handleBindApplication",
                    ACTIVITY_THREAD + "$AppBindData", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            mark("handleBindApplication 进入（加固壳开始工作）");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            mark("handleBindApplication 返回（Application 已创建）");
                        }
                    });
        } catch (Throwable t) {
            Log.d("handleBindApplication 打点失败: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(ACTIVITY_THREAD, null, "performLaunchActivity",
                    ACTIVITY_THREAD + "$ActivityClientRecord", "android.content.Intent",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String cls = "?";
                            try {
                                Object record = param.args.length > 0 ? param.args[0] : null;
                                Object info = XposedHelpers.getObjectField(record, "activityInfo");
                                Object name = XposedHelpers.getObjectField(info, "name");
                                if (name instanceof String) {
                                    cls = (String) name;
                                }
                            } catch (Throwable ignored) {
                            }
                            mark("Activity 创建完成: " + cls);
                        }
                    });
        } catch (Throwable t) {
            Log.d("performLaunchActivity 打点失败: " + t);
        }
    }

    private static void hookApplication() {
        // Application 自己没覆盖 attachBaseContext，方法在 ContextWrapper 上 ——
        // v1.4 挂在 Application.class 上，一直报 NoSuchMethodError，这个点从来没打出来过。
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", null,
                    "attachBaseContext", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Application)) {
                                return;
                            }
                            mark("壳 attachBaseContext 完成（dex 解密通常在这一步之后）");
                        }
                    });
        } catch (Throwable t) {
            Log.d("attachBaseContext 打点失败: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            mark("Application.onCreate 进入");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            mark("Application.onCreate 返回");
                        }
                    });
        } catch (Throwable t) {
            Log.d("Application.onCreate 打点失败: " + t);
        }
    }

    private static void mark(String what) {
        try {
            long ms = bootAt == 0 ? 0 : SystemClock.elapsedRealtime() - bootAt;
            Log.i("[启动+" + ms + "ms] " + what);
        } catch (Throwable ignored) {
        }
    }
}
