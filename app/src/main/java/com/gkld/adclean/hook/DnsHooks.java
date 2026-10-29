package com.gkld.adclean.hook;

import java.net.InetAddress;
import java.net.UnknownHostException;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import com.gkld.adclean.BlockList;
import com.gkld.adclean.Config;
import com.gkld.adclean.Log;

/**
 * DNS 层拦截（v1.5 新增）—— 和 AdGuard Home 完全同一种失败方式。
 *
 * 为什么不满足于改 URL：
 *   之前是把命中黑名单的请求整条换成 `http://127.0.0.1:1/__adblock__`，
 *   等于**协议从 https 变 http、host 变 IP、路径和 query 全丢**。广告 SDK 内部
 *   往往按 URL 做状态判断，拿到一个面目全非的 URL 再收到 ConnectException，
 *   容易走进未定义分支（真机日志显示首启渲染开屏广告时进程直接消失，无 Java 堆栈）。
 *
 *   而 AdGuard Home 只是让域名解析不出来 —— URL 一个字节都不动，SDK 收到的是
 *   标准的 UnknownHostException，走的是它自己写好的「无网络/域名不可达」分支。
 *   用户实测这个方式既去掉了开屏广告，也没崩。
 *
 * 实现：hook InetAddress.getAllByName / getByName，命中黑名单就直接抛
 * UnknownHostException。OkHttp 的 Dns.SYSTEM、HttpURLConnection、以及绝大多数
 * 直接调 InetAddress 的 SDK 都会经过这里。
 *
 * 注意：走自家 HTTPDNS（自己拿 IP 直连）的 SDK 绕得过这里，那部分交给
 * HttpHooks 的 URL 改写兜底。
 */
public final class DnsHooks {

    private static volatile boolean installed;

    private DnsHooks() {
    }

    public static void install() {
        if (!Config.BLOCK_VIA_DNS || installed) {
            return;
        }
        installed = true;

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object a0 = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                    if (!(a0 instanceof String)) {
                        return;
                    }
                    String host = (String) a0;
                    if (host.isEmpty() || !BlockList.isBlockedHost(host)) {
                        return;
                    }
                    // 只让它解析失败，URL、Header、请求体全部保持原样
                    param.setThrowable(new UnknownHostException(host));
                    if (SEEN.add(host)) {
                        Log.i("拦截 DNS: " + host);
                    }
                } catch (Throwable ignored) {
                    // 任何异常都不能回流到 DNS 解析路径
                }
            }
        };

        try {
            XposedBridge.hookAllMethods(InetAddress.class, "getAllByName", hook);
            Log.i("DNS 拦截已装: InetAddress.getAllByName");
        } catch (Throwable t) {
            Log.d("getAllByName 安装失败: " + t);
        }

        try {
            XposedBridge.hookAllMethods(InetAddress.class, "getByName", hook);
            Log.i("DNS 拦截已装: InetAddress.getByName");
        } catch (Throwable t) {
            Log.d("getByName 安装失败: " + t);
        }
    }

    /** 同一个域名只记一次日志，避免被高频解析刷屏 */
    private static final java.util.Set<String> SEEN =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
}
