package com.gkld.adclean.hook;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import com.gkld.adclean.Config;

/**
 * 崩溃捕获（v1.3 新增）。
 *
 * 真机反馈「第一次启动闪退、第二次才进得去」，但 LSPosed 日志里没有堆栈，
 * 没法判断是模块 hook 引起的，还是网易易盾壳自己的首启校验/解密失败。
 *
 * 做法：hook Thread.setDefaultUncaughtExceptionHandler，把 App 自己的处理器
 * 包一层 —— 崩的时候先把完整堆栈写进 LSPosed 日志，再原样转交给 App 的处理器，
 * 不改变 App 的崩溃上报行为。
 *
 * 判读：
 *   - 日志里出现 `!!! 崩溃` 且栈顶是 com.gkld.adclean.*  → 是模块的问题，按栈改；
 *   - 栈顶是 com.netease.nis.* / com.maidu.gkld.*       → 是加固壳或 App 自己的问题；
 *   - 完全没有 `!!! 崩溃`                               → 是 native 崩溃（SIGSEGV），
 *     只能靠抓 logcat 里的 tombstone。
 */
public final class CrashGuard {

    /** 防止自己在 after 钩子里再次调用 setDefaultUncaughtExceptionHandler 造成递归 */
    private static volatile boolean replacing;

    private CrashGuard() {
    }

    public static void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Thread.class, "setDefaultUncaughtExceptionHandler",
                    Thread.UncaughtExceptionHandler.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (replacing) {
                                return;
                            }
                            Object h = param.args.length > 0 ? param.args[0] : null;
                            if (!(h instanceof Thread.UncaughtExceptionHandler)) {
                                return;
                            }
                            if (h instanceof Wrap) {
                                return;
                            }
                            replacing = true;
                            try {
                                Thread.setDefaultUncaughtExceptionHandler(
                                        new Wrap((Thread.UncaughtExceptionHandler) h));
                            } catch (Throwable ignored) {
                            } finally {
                                replacing = false;
                            }
                        }
                    });
            com.gkld.adclean.Log.i("崩溃捕获已就绪");
        } catch (Throwable t) {
            com.gkld.adclean.Log.e("崩溃捕获安装失败", t);
        }
    }

    private static final class Wrap implements Thread.UncaughtExceptionHandler {

        private final Thread.UncaughtExceptionHandler inner;

        Wrap(Thread.UncaughtExceptionHandler inner) {
            this.inner = inner;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try {
                String stack = Log.getStackTraceString(e);
                XposedBridge.log(Config.TAG + " !!! 崩溃[" + t.getName() + "]: " + stack);
            } catch (Throwable ignored) {
            }
            if (inner != null) {
                inner.uncaughtException(t, e);
            }
        }
    }
}
