package com.gkld.adclean;

import de.robv.android.xposed.XposedBridge;

/**
 * 日志封装：同时写 LSPosed 日志和 logcat。
 * 注意 XposedBridge.log 在模块自己的进程里调用会抛异常，所以这里全部 try 住。
 */
public final class Log {

    private Log() {
    }

    private static void write(String level, String msg) {
        String line = "[" + Config.TAG + "] " + level + " " + msg;
        try {
            XposedBridge.log(line);
        } catch (Throwable ignored) {
        }
        try {
            android.util.Log.println(android.util.Log.INFO, Config.TAG, level + " " + msg);
        } catch (Throwable ignored) {
        }
    }

    public static void i(String msg) {
        write("I", msg);
    }

    public static void d(String msg) {
        if (Config.DEBUG) {
            write("D", msg);
        }
    }

    public static void e(String msg, Throwable t) {
        write("E", msg + " -> " + t);
        if (t != null) {
            try {
                XposedBridge.log(t);
            } catch (Throwable ignored) {
            }
        }
    }
}
