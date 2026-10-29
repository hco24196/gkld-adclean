package com.gkld.adclean.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import com.gkld.adclean.Config;
import com.gkld.adclean.Log;

/**
 * 自杀捕获（v1.4 新增）。
 *
 * 加固壳（网易易盾 / 枭龙等）发现运行环境有 Xposed、调试器或有模块注入时，
 * 最常见的处理不是抛异常，而是**自己把进程杀掉** —— 表现就是"闪退"，但 Java 层
 * 崩溃处理器、LSPosed 日志一条都没有。这种情况下只看崩溃堆栈永远找不到原因。
 *
 * 这里挂住四个自杀出口，在进程咽气前把调用堆栈写进日志：
 *   java.lang.Runtime.exit / halt
 *   android.os.Process.killProcess / killProcessQuiet
 *
 * 只记录、不拦截（原方法照常执行），所以不会改变 App 的原有行为。
 *
 * 判读：
 *   - 栈里出现 com.netease.nis.*   → 壳的自我保护，跟模块的 hook 逻辑无关，
 *                                     但说明注入被它看见了；
 *   - 栈里出现 com.maidu.gkld.*    → App 自己的退出逻辑（例如隐私协议未同意）；
 *   - 什么都没抓到                 → native 崩溃（SIGSEGV/SIGABRT），只能抓 tombstone。
 */
public final class ExitGuard {

    private static volatile boolean installed;

    private static volatile boolean reporting;

    private ExitGuard() {
    }

    public static void install(ClassLoader cl) {
        if (installed) {
            return;
        }
        installed = true;

        try {
            XposedHelpers.findAndHookMethod(Runtime.class, "exit", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    report("Runtime.exit", param.args);
                }
            });
            XposedHelpers.findAndHookMethod(Runtime.class, "halt", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    report("Runtime.halt", param.args);
                }
            });
            Log.i("自杀捕获已就绪: Runtime.exit/halt");
        } catch (Throwable t) {
            Log.d("Runtime 自杀捕获失败: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.os.Process", cl, "killProcess",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            report("Process.killProcess", param.args);
                        }
                    });
            Log.i("自杀捕获已就绪: Process.killProcess");
        } catch (Throwable t) {
            Log.d("Process.killProcess 捕获失败: " + t);
        }
    }

    private static void report(String api, Object[] args) {
        if (reporting) {
            return;
        }
        reporting = true;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("!!! 进程被结束: ").append(api).append('(');
            if (args != null && args.length > 0) {
                sb.append(args[0]);
            }
            sb.append(") 线程=").append(Thread.currentThread().getName());
            for (StackTraceElement e : new Throwable().getStackTrace()) {
                sb.append("\n\tat ").append(e);
            }
            XposedBridge.log(Config.TAG + " " + sb);
        } catch (Throwable ignored) {
        } finally {
            reporting = false;
        }
    }
}
