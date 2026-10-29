package com.gkld.adclean;

import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import com.gkld.adclean.hook.AppHooks;
import com.gkld.adclean.hook.DnsHooks;
import com.gkld.adclean.hook.HttpHooks;
import com.gkld.adclean.hook.ViewHooks;

/**
 * 模块入口，登记在 assets/xposed_init。
 *
 * 公考雷达 6.7.3.1 用网易易盾加固，App 的真类运行时才解密。
 *
 * ---------------------------------------------------------------------------
 * v1.6 的核心变化：**不再碰加固壳的启动路径**。
 *
 * 真机对照实验：
 *   v1.5.0-safe（SAFE_MODE=true，一个钩子都不装）→ 不闪退，应用内广告在；
 *   v1.5.0     （全钩子）                        → 广告干净，首次启动必闪退。
 *
 * 结论：闪退是钩子造成的，不是模块注入本身。v1.1~v1.5 一直在加固壳「动手术」
 * 的那几毫秒里去抢东西 —— hook ClassLoader.loadClass、hook Runtime.exit ——
 * 这是易盾最敏感的位置，且只在首启（要解密 106MB dex）走这条路，第二次启动
 * 有缓存所以正常，与"只有第一次崩"的现象完全一致。
 *
 * v1.6 的加载顺序只保留三样，全部避开创世阶段：
 *   1. DnsHooks   —— hook InetAddress，命中黑名单域名让它解析失败。
 *                    与 AdGuard Home 完全相同的失败方式，用户实测有效且不崩。
 *   2. HttpHooks  —— URL.openConnection 立即装；OkHttpClient 延到首次 onResume
 *                    之后在后台线程慢慢试（见 HttpHooks#scheduleLateInstall）。
 *   3. ViewHooks  —— LayoutInflater.inflate 的 after 钩子，按布局名 / 控件 id
 *                    隐藏自营运营位，不 hook 任何 framework 核心方法。
 *
 * 被删除的钩子（以及原因）：
 *   ClassWatcher（ClassLoader.loadClass）—— 首启崩的头号嫌疑，见上。
 *   ExitGuard（Runtime.exit / Process.killProcess）—— 易盾反调试面，可能就是
 *       "无 Java 栈的 native 崩溃"的来源。
 *   CrashGuard（Thread.setDefaultUncaughtExceptionHandler）—— 同上。
 *   BootTrace（ContextWrapper.attachBaseContext）—— 打点用途，已经不需要了，
 *       顺带去掉一个 hook 面。
 *   SdkHooks / FeedHooks —— 依赖 ClassWatcher 才能装，一起下线。
 * ---------------------------------------------------------------------------
 */
public class MainHook implements IXposedHookZygoteInit, IXposedHookLoadPackage {

    @Override
    public void initZygote(StartupParam startupParam) {
        Log.i("模块装载完成，等待目标进程");
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        if (!Config.TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }
        if (Config.MAIN_PROCESS_ONLY && lpparam.processName != null
                && !lpparam.processName.equals(lpparam.packageName)) {
            Log.d("跳过子进程: " + lpparam.processName);
            return;
        }

        // 这一行是「模块是否被注入」的唯一判据。日志里连它都没有，
        // 说明进程在注入前就死了，跟模块的 hook 逻辑无关。
        Log.i("命中目标: " + lpparam.packageName + "（模块 v" + Config.MODULE_VERSION
                + " / 适配 App " + Config.TARGET_VERSION + "）");

        if (Config.SAFE_MODE) {
            Log.i("安全模式：本版本不安装任何钩子，仅用于确认闪退是否与模块有关");
            return;
        }

        final ClassLoader cl = lpparam.classLoader;

        // 0. 看门狗：上次启动没撑过 5 秒（= 崩了）就本次只装网络层，保证至少能用。
        //    正常情况下这里的第一次读到的都是 boot=0，不影响任何功能。
        final boolean degraded = Watchdog.crashedLastTime();
        if (degraded) {
            Log.i("上次启动异常中断 → 本次降级为纯网络层（不装 inflate / onResume 钩子）");
        } else {
            Watchdog.markBooting();
            armHealthyTimer();
        }

        // 1. Application 创建时机：抢开屏广告的请求窗口 + 弹 Toast 说明注入状态。
        //    这是最早能安全拿到 ClassLoader 的地方（晚于壳解密、早于任何 Activity）。
        safeRun("AppHooks", new Runnable() {
            @Override
            public void run() {
                AppHooks.install(cl, degraded);
            }
        });

        // 2. 网络层：只比对 host 字符串，命中就抛 UnknownHostException。
        //    这是 AdGuard Home 的失败方式，用户实测既去广告又不崩。
        safeRun("DnsHooks", new Runnable() {
            @Override
            public void run() {
                DnsHooks.install();
            }
        });

        // 3. URLConnection 立即装；OkHttpClient 由 AppHooks 在 Application.onCreate
        //    之后立刻抢装（那时开屏广告还没发请求）。
        safeRun("HttpHooks", new Runnable() {
            @Override
            public void run() {
                HttpHooks.install();
            }
        });

        // 4. 视图层：inflate 之后隐藏运营位 + setVisibility 兜底。
        if (!degraded) {
            safeRun("ViewHooks", new Runnable() {
                @Override
                public void run() {
                    ViewHooks.install(cl);
                }
            });
        }
    }

    /**
     * 撑过 5 秒就把「启动进行中」标记清掉。
     * 真崩了的话这行永远执行不到，下次启动 Watchdog 就会读到 boot=1 并自动降级。
     */
    private static void armHealthyTimer() {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    Watchdog.markHealthy();
                }
            }, Watchdog.HEALTHY_AFTER_MS);
        } catch (Throwable t) {
            // 排不进主线程消息队列（极早期调用）就算正常，宁可少降级一次
            Watchdog.markHealthy();
        }
    }

    private static void safeRun(String name, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            Log.e(name + " 初始化失败", t);
        }
    }
}
