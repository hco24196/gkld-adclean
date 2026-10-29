package com.gkld.adclean.hook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import com.gkld.adclean.Config;
import com.gkld.adclean.Log;

/**
 * 延迟挂钩子 —— 解决"加固 App 不能提前 findClass"的问题。
 *
 * 公考雷达用网易易盾加固：application 是 com.netease.nis.wrapper.MyApplication，
 * classes.dex 里只有 30 个壳类，真正的类要等 Application 起来后解密才有。
 *
 * 两条互补的路：
 *   1. hook ClassLoader.loadClass —— 类被加载的那一刻顺手接管（最准、零延迟）；
 *   2. 延时扫描（1.5s / 4s / 9s）—— 兜住被虚拟机隐式加载、不走 Java 层 loadClass 的类。
 *
 * v1.4 的两个改动（对应「首次启动闪退」）：
 *   - **不再自己装 loadClass 钩子**，而是由 MainHook 延后到主线程空闲后调用 install()。
 *     加固壳首启要在主线程解密 106MB dex 并替换 ClassLoader，此时抢挂 loadClass 就是
 *     在它动手术时按住手术刀，这也是"只有第一次崩、第二次正常"的唯一合理解释。
 *   - **扫描改为主动调度**。v1.3 靠 hook Application.attachBaseContext 来触发扫描，
 *     而延后安装时 attachBaseContext 早就调完了，扫描会永不启动。现在 install() 里直接
 *     用主线程 postDelayed 排三轮，不再依赖任何生命周期回调。
 *
 * 另外 ClassLoader 也不再写死用 lpparam.classLoader：加固壳解密后会把 Application 的
 * ClassLoader 换成一个指向解密 dex 的 DexClassLoader，只有那个才能加载出 App 的真类。
 * 这里每轮扫描前都会从 ActivityThread.mInitialApplication 重新取一次。
 */
public final class ClassWatcher {

    private static final Set<String> WATCHED = new HashSet<>();

    private static final Set<String> DONE = new HashSet<>();

    private static final long[] SWEEP_DELAYS = {1500L, 4000L, 9000L};

    private static boolean installed;

    private static ClassLoader loader;

    /**
     * 类加载监听的生效截止时间（进程启动后 5 分钟）。
     * 「我的」页这类二级页面要用户点开才加载，窗口给足 5 分钟；
     * 过了窗口自动失效，避免长期挂着 ClassLoader 的核心方法给整个 App 增加开销。
     */
    private static volatile long hookUntil = Long.MAX_VALUE;

    private ClassWatcher() {
    }

    public static void install(ClassLoader cl) {
        if (installed) {
            return;
        }
        installed = true;
        loader = cl;

        for (String[] row : Config.BLOCK_METHODS) {
            WATCHED.add(row[0]);
        }
        for (String c : Config.SPLASH_CLASSES) {
            WATCHED.add(c);
        }
        for (String c : Config.FEED_VIEW_HOLDERS) {
            WATCHED.add(c);
        }
        WATCHED.add(Config.BANNER_CLASS);

        hookUntil = SystemClock.elapsedRealtime() + 300_000L;
        hookLoadClass();
        scheduleSweep();

        Log.i("类监听已就绪，待接管 " + WATCHED.size() + " 个类");
    }

    // ------------------------------------------------------ 路径 1：类加载

    private static void hookLoadClass() {
        try {
            // 只挂 loadClass(String, boolean)。ClassLoader.loadClass(String) 内部就是转调它，
            // 两个都挂会让每次类加载回调两遍 —— 加固 App 启动期类加载上万次，这一下省一半开销。
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass",
                    String.class, boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (SystemClock.elapsedRealtime() > hookUntil) {
                            return;
                        }
                        Object a0 = param.args.length > 0 ? param.args[0] : null;
                        if (!(a0 instanceof String)) {
                            return;
                        }
                        String name = (String) a0;
                        Object result = param.getResult();
                        if (!(result instanceof Class)) {
                            return;
                        }
                        Class<?> loaded = (Class<?>) result;

                        // okhttp3 是被 App/广告 SDK 懒加载的；这里已经拿到 Class，直接装钩子，
                        // 不再 findClass（否则每加载一个 okhttp3.* 类都要抛一次异常）
                        if ("okhttp3.OkHttpClient".equals(name)) {
                            HttpHooks.installOn(loaded);
                            return;
                        }
                        if (!WATCHED.contains(name) || DONE.contains(name)) {
                            return;
                        }
                        dispatch(loaded);
                    } catch (Throwable t) {
                        // 绝不让异常回到 ClassLoader.loadClass，否则直接崩在启动路径上
                        Log.d("loadClass 回调异常: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e("loadClass 钩子失败", t);
        }
    }

    // ------------------------------------------------------ 路径 2：延时扫描

    private static void scheduleSweep() {
        try {
            Handler h = new Handler(Looper.getMainLooper());
            for (final long delay : SWEEP_DELAYS) {
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // 只负责触发，真正的反射活放到后台线程，绝不占主线程
                        final long d = delay;
                        Thread t = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                sweep(d);
                            }
                        }, "GkldAdClean-sweep");
                        t.setPriority(Thread.MIN_PRIORITY);
                        t.start();
                    }
                }, delay);
            }
        } catch (Throwable t) {
            Log.e("扫描调度失败", t);
        }
    }

    /**
     * 取当前真正能加载出 App 类的 ClassLoader。
     *
     * 加固壳解密完成后会把 Application 的 ClassLoader 换成指向解密 dex 的
     * DexClassLoader，用最初 lpparam.classLoader 是扫不到 com.maidu.* 的。
     * 每轮扫描前重新取一次，壳中途再换也能跟上。
     */
    private static ClassLoader resolveLoader() {
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object current = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            if (current != null) {
                Object app = XposedHelpers.getObjectField(current, "mInitialApplication");
                if (app instanceof Context) {
                    ClassLoader l = ((Context) app).getClassLoader();
                    if (l != null) {
                        return l;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return loader;
    }

    private static void sweep(long delay) {
        ClassLoader cl = resolveLoader();
        if (cl == null) {
            return;
        }
        int hit = 0;
        // okhttp3 可能被隐式加载（不走 Java 层 loadClass），每轮扫描补一次
        HttpHooks.tryInstallOkHttp(cl);
        for (String name : WATCHED) {
            if (DONE.contains(name)) {
                continue;
            }
            try {
                Class<?> c = Class.forName(name, false, cl);
                if (c != null) {
                    dispatch(c);
                    hit++;
                }
            } catch (Throwable ignored) {
                // 这个 App 没集成该 SDK
            }
        }
        if (hit > 0) {
            Log.i("延时扫描(" + delay + "ms) 新接管 " + hit + " 个类");
        }
    }

    // ------------------------------------------------------------ 分发

    private static void dispatch(Class<?> clazz) {
        String name = clazz.getName();
        if (DONE.contains(name)) {
            return;
        }
        DONE.add(name);
        try {
            for (String holder : Config.FEED_VIEW_HOLDERS) {
                if (holder.equals(name)) {
                    ClassLoader own = clazz.getClassLoader();
                    FeedHooks.hookViewHolder(clazz, own != null ? own : loader);
                    return;
                }
            }
            if (Config.BANNER_CLASS.equals(name)) {
                ViewHooks.hookBanner(clazz);
                return;
            }
            for (String s : Config.SPLASH_CLASSES) {
                if (s.equals(name)) {
                    SdkHooks.hookSplash(clazz);
                    return;
                }
            }
            for (String[] row : Config.BLOCK_METHODS) {
                if (row[0].equals(name)) {
                    String[] methods = new String[row.length - 1];
                    System.arraycopy(row, 1, methods, 0, methods.length);
                    SdkHooks.killMethods(clazz, methods);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.e("接管 " + name + " 失败", t);
        }
    }
}
