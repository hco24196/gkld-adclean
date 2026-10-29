package com.gkld.adclean.hook;

import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import com.gkld.adclean.BlockList;
import com.gkld.adclean.Config;
import com.gkld.adclean.Log;

/**
 * 网络层拦截：命中广告域名时把目标改写成 127.0.0.1:1。
 * 连接毫秒级被拒，SDK 自己走失败回调，不用猜回调签名，也不会卡界面。
 *
 * 关键点（v1.1 修复）：公考雷达用网易易盾加固，模块启动那一刻 okhttp3 还没被加载，
 * 旧版这里 findClass 失败就永久放弃了，结果 TopOn/穿山甲 的请求全走了 OkHttp 没被拦。
 * 现在改成「谁先到谁装」：
 *   - 启动时试一次；
 *   - ClassWatcher 每次看到 okhttp3.* 类加载就再试；
 *   - 1.5s/4s/8s 的延时扫描里也各试一次。
 * 装成功后用 OKHTTP_DONE 去重，不会重复 hook。
 */
public final class HttpHooks {

    /**
     * v1.5：不再把整条 URL 换成死地址，只把 host/port 换成本机未监听端口，
     * **协议、路径、query 全部保留**。
     *
     * 旧做法 `http://127.0.0.1:1/__adblock__` 会让 https 请求变成 http、
     * 路径和 query 全丢，SDK 拿到面目全非的 URL 再收到 ConnectException，
     * 容易走进未定义分支。现在 SDK 看到的 URL 除 host 外与原来一致。
     */
    private static final String DEAD_HOST = "127.0.0.1";

    private static final int DEAD_PORT = 1;

    private static final Set<ClassLoader> OKHTTP_DONE =
            Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());

    private static boolean urlHooked;

    /**
     * 重入保护：本方法会被 ClassLoader.loadClass 的钩子调用，
     * 而里面的 findClass 又会触发 loadClass —— 不加这个标记会无限递归。
     */
    private static boolean installing;

    private HttpHooks() {
    }

    public static void install() {
        if (Config.REWRITE_URL && !urlHooked) {
            hookUrlConnection();
            urlHooked = true;
        }
    }

    /**
     * 启动早期抢装 OkHttp 钩子（v1.7 新增）—— 专治"首屏广告又回来了"。
     *
     * 时机：Application.onCreate 刚执行完。此时 App 的网络库（Retrofit/OkHttp）
     * 必然已经初始化，okhttp3.OkHttpClient 这个类一定加载过了，Class.forName 一发必中。
     * 而开屏广告的请求还在后面（SplashActivity.onCreate 才发），所以能赶在它之前装好。
     *
     * 为什么不是 hook ClassLoader.loadClass：那正是 v1.5 首启闪退的根因。
     * 这里换成「壳解密完之后主动 Class.forName」，同样能拿到类，但完全不侵入类加载流程。
     *
     * 轮询间隔 100ms、20 次（共 2 秒）—— 覆盖 Application.onCreate 里
     * 异步初始化网络库的情况。
     */
    public static void installEarly(final ClassLoader cl) {
        if (cl == null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 20; i++) {
                    if (OKHTTP_DONE.contains(cl)) {
                        return;
                    }
                    if (tryInstallOkHttp(cl)) {
                        Log.i("OkHttp 钩子已在启动早期装好（第 " + i + " 次尝试）");
                        return;
                    }
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                Log.d("启动早期没等到 okhttp3，交给 onResume 后的长尾重试");
            }
        }, "GkldAdClean-okhttp-early");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 延后安装 OkHttp 钩子（v1.6）。
     *
     * 为什么不在启动时装：okhttp3 是被 App / 广告 SDK 懒加载的，模块刚注入时它还不存在，
     * 唯一能"提前拿到"的办法是 hook ClassLoader.loadClass —— 而那个钩子正是 v1.5 首启
     * 闪退的头号嫌疑（加固壳首启要解密 106MB dex 并替换 ClassLoader，被按住就崩）。
     *
     * 现在改成：从第一次 Activity.onResume 起算 2.5 秒，在**后台线程**里用 Class.forName
     * 每秒试一次，最多一分钟 —— 那时壳早已解密完毕，主动触发类加载完全安全。
     */
    public static void scheduleLateInstall(final ClassLoader cl) {
        if (cl == null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(Config.OKHTTP_INSTALL_DELAY_MS);
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < Config.OKHTTP_MAX_TRY; i++) {
                    if (OKHTTP_DONE.contains(cl)) {
                        return;
                    }
                    if (tryInstallOkHttp(cl)) {
                        return;
                    }
                    try {
                        Thread.sleep(Config.OKHTTP_RETRY_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                Log.d("OkHttp 迟迟没有加载，放弃兜底改写（DNS 层仍然有效）");
            }
        }, "GkldAdClean-okhttp");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    // ---------------------------------------------------------------- OkHttp

    /**
     * v1.2：由 ClassWatcher 在 loadClass 拿到 okhttp3.OkHttpClient 的 Class 之后直接调用。
     * 好处是彻底不用 findClass —— 不管这个类是 App 的 ClassLoader 加载的，
     * 还是 TopOn/gdt 插件 jar 自己的 DexClassLoader 加载的，都一定装得上。
     */
    public static synchronized void installOn(Class<?> clientCls) {
        if (clientCls == null) {
            return;
        }
        ClassLoader cl = clientCls.getClassLoader();
        if (cl != null && OKHTTP_DONE.contains(cl)) {
            return;
        }
        if (cl != null) {
            OKHTTP_DONE.add(cl);
        }
        hookNewCall(clientCls);
        if (cl != null) {
            hookBuilderBuild(cl);
        }
    }

    /**
     * 幂等：装过就直接返回 true。由 {@link #scheduleLateInstall(ClassLoader)} 在后台线程
     * 每秒重试，所以这里不再自己记账「试了几次」—— 重试节奏交给调度方控制。
     *
     * @return true 表示 OkHttpClient 已经就绪并装好（或本来就已经装过）
     */
    public static synchronized boolean tryInstallOkHttp(ClassLoader cl) {
        if (cl == null || installing) {
            return false;
        }
        if (OKHTTP_DONE.contains(cl)) {
            return true;
        }
        installing = true;
        try {
            Class<?> clientCls;
            try {
                // false = 不初始化静态块，只是把类找出来，副作用最小
                clientCls = XposedHelpers.findClass("okhttp3.OkHttpClient", cl);
            } catch (Throwable t) {
                return false;
            }
            OKHTTP_DONE.add(cl);
            hookNewCall(clientCls);
            hookBuilderBuild(cl);
            return true;
        } finally {
            installing = false;
        }
    }

    /**
     * 主拦截点：OkHttpClient.newCall(Request)。
     * 所有请求（不管是用 url(String) 还是 HttpUrl.parse 拼的、同步还是异步）
     * 最终都要经过这里，命中黑名单就把 Request 换成指向死地址的新 Request。
     */
    private static void hookNewCall(Class<?> clientCls) {
        try {
            XposedBridge.hookAllMethods(clientCls, "newCall", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length != 1 || param.args[0] == null) {
                        return;
                    }
                    try {
                        Object request = param.args[0];
                        Object httpUrl = XposedHelpers.callMethod(request, "url");
                        if (httpUrl == null) {
                            return;
                        }
                        String host = (String) XposedHelpers.callMethod(httpUrl, "host");
                        String path = (String) XposedHelpers.callMethod(httpUrl, "encodedPath");
                        if (!BlockList.shouldBlock(host, path)) {
                            return;
                        }
                        Object dead = buildRequest(request, httpUrl);
                        if (dead != null) {
                            param.args[0] = dead;
                            Log.i("拦截 OkHttp: " + httpUrl);
                        }
                    } catch (Throwable t) {
                        Log.d("OkHttp 处理异常: " + t);
                    }
                }
            });
            Log.i("OkHttp.newCall 钩子已安装");
        } catch (Throwable t) {
            Log.d("OkHttp.newCall 安装失败: " + t);
        }
    }

    /** 兜底：如果有代码绕过 newCall 直接用已有 Call，就在 build() 之后改 url 字段 */
    private static void hookBuilderBuild(ClassLoader cl) {
        try {
            final Class<?> builderCls = XposedHelpers.findClass("okhttp3.Request$Builder", cl);
            XposedHelpers.findAndHookMethod(builderCls, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object request = param.getResult();
                    if (request == null) {
                        return;
                    }
                    try {
                        Object httpUrl = XposedHelpers.callMethod(request, "url");
                        if (httpUrl == null) {
                            return;
                        }
                        String host = (String) XposedHelpers.callMethod(httpUrl, "host");
                        String path = (String) XposedHelpers.callMethod(httpUrl, "encodedPath");
                        if (!BlockList.shouldBlock(host, path)) {
                            return;
                        }
                        Object dead = rewriteHttpUrl(httpUrl);
                        if (dead != null) {
                            XposedHelpers.setObjectField(request, "url", dead);
                            Log.i("拦截 OkHttp(兜底): " + httpUrl);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            Log.d("OkHttp.build 兜底安装失败: " + t);
        }
    }

    /**
     * 只换 host 和 port，scheme / path / query 原样保留。
     * HttpUrl.newBuilder().host(..).port(..).build()
     */
    private static Object rewriteHttpUrl(Object httpUrl) {
        try {
            Object builder = XposedHelpers.callMethod(httpUrl, "newBuilder");
            XposedHelpers.callMethod(builder, "host", DEAD_HOST);
            XposedHelpers.callMethod(builder, "port", DEAD_PORT);
            return XposedHelpers.callMethod(builder, "build");
        } catch (Throwable t) {
            Log.d("改写 HttpUrl 失败: " + t);
            return null;
        }
    }

    /** 用新 URL 重建 Request：Request.newBuilder().url(newUrl).build() */
    private static Object buildRequest(Object request, Object httpUrl) {
        try {
            Object url = rewriteHttpUrl(httpUrl);
            if (url == null) {
                return null;
            }
            Object builder = XposedHelpers.callMethod(request, "newBuilder");
            XposedHelpers.callMethod(builder, "url", url);
            return XposedHelpers.callMethod(builder, "build");
        } catch (Throwable t) {
            Log.d("构造死请求失败: " + t);
            return null;
        }
    }

    // ------------------------------------------------------- HttpURLConnection

    private static void hookUrlConnection() {
        try {
            XposedBridge.hookAllMethods(URL.class, "openConnection", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        URL self = (URL) param.thisObject;
                        if (self == null || !BlockList.shouldBlock(self.getHost(), self.getPath())) {
                            return;
                        }
                        Object conn = param.getResult();
                        if (!(conn instanceof URLConnection)) {
                            return;
                        }
                        Log.i("拦截 URLConnection: " + self);
                        // 同样只换 host/port，协议与路径保留
                        URL dead = new URL(self.getProtocol(), DEAD_HOST, DEAD_PORT, self.getFile());
                        XposedHelpers.setObjectField(conn, "url", dead);
                    } catch (Throwable t) {
                        Log.d("URLConnection 处理异常: " + t);
                    }
                }
            });
            Log.i("URLConnection 钩子已安装");
        } catch (Throwable t) {
            Log.e("URLConnection 钩子安装失败", t);
        }
    }
}
