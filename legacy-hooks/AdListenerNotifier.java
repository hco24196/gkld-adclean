package com.gkld.adclean.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.gkld.adclean.Log;

/**
 * 广告回调的"友好失败"处理。
 *
 * 原理：SDK 的 loadXxx 方法一般形如 loadXxx(adSlot, listener)，
 * 我们短路掉这个方法后，遍历入参，谁实现了 onError(int, String) / onNoAd(...) 就调一下，
 * 让 App 走"广告加载失败"的正常分支，而不是干等超时。
 *
 * 只调用 参数类型为 (int,String) / (int) / (String) 的方法，
 * 避免把字符串塞进 AdError 这类对象参数里导致 ClassCastException。
 */
final class AdListenerNotifier {

    private static final Class<?>[] SIG_I_S = {int.class, String.class};
    private static final Class<?>[] SIG_I = {int.class};
    private static final Class<?>[] SIG_S = {String.class};

    private static final String[] ERROR_METHODS = {
            "onError", "onNoAd", "onAdFailed", "onAdFail", "onLoadFailed",
            "onLoadError", "onAdLoadFailed", "onAdError", "onRenderFail",
            "onRenderError", "onFailed"
    };

    private AdListenerNotifier() {
    }

    static void notifyFailure(Object[] args) {
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            String cn = arg.getClass().getName();
            if (cn.startsWith("java.") || cn.startsWith("android.")) {
                continue;
            }
            if (notifyOne(arg)) {
                return;
            }
        }
    }

    /**
     * 有些 SDK（比如倍孜 SplashAd）的监听器是 setAdInteractionListener(...) 挂在对象上，
     * loadAd() 本身不带监听器参数。这里反射遍历 thisObject 的字段把监听器挖出来。
     */
    static void notifyFields(Object owner) {
        if (owner == null) {
            return;
        }
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(owner);
                    if (v == null) {
                        continue;
                    }
                    String cn = v.getClass().getName();
                    if (cn.startsWith("java.") || cn.startsWith("android.")) {
                        continue;
                    }
                    if (notifyOne(v)) {
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean notifyOne(Object listener) {
        for (String name : ERROR_METHODS) {
            if (call(listener, name, SIG_I_S, 0, "adblocked")) {
                return true;
            }
            if (call(listener, name, SIG_I, 0)) {
                return true;
            }
            if (call(listener, name, SIG_S, "adblocked")) {
                return true;
            }
        }
        return false;
    }

    private static boolean call(Object target, String name, Class<?>[] sig, Object... args) {
        try {
            Method m = target.getClass().getMethod(name, sig);
            m.setAccessible(true);
            m.invoke(target, args);
            Log.d("已回调 " + target.getClass().getSimpleName() + "#" + name);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Throwable t) {
            // 方法存在但 App 内部实现抛了异常：视作已通知，不再尝试其它签名
            Log.d("回调 " + name + " 抛异常（已忽略）: " + t);
            return true;
        }
    }

    /** 调试用：打印一个监听器的公开方法，方便适配新 SDK */
    @SuppressWarnings("unused")
    static void dump(Object listener) {
        if (listener == null) {
            return;
        }
        try {
            for (Method m : listener.getClass().getMethods()) {
                Log.d("  listener: " + m);
            }
        } catch (Throwable ignored) {
        }
    }
}
