package com.gkld.adclean.hook;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import com.gkld.adclean.Config;
import com.gkld.adclean.Log;

/**
 * 方法级拦截。所有 hook 都由 ClassWatcher 在目标类真正加载后触发，
 * 所以对加固过的 App 同样安全。
 */
public final class SdkHooks {

    private static final Map<String, Integer> COUNT = new ConcurrentHashMap<>();

    private SdkHooks() {
    }

    /** 短路指定方法：按方法名匹配所有重载，不依赖参数签名，跨版本更耐改 */
    public static void killMethods(Class<?> clazz, String[] methodNames) {
        if (clazz.isInterface()) {
            Log.d("跳过接口（拦不到实现类）: " + clazz.getName());
            return;
        }
        int hooked = 0;
        for (String name : methodNames) {
            try {
                Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(clazz, name, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String key = param.method.getDeclaringClass().getName() + "#" + param.method.getName();
                        if (logOnce(key) >= 0) {
                            Log.i("拦截广告加载: " + key);
                        }
                        param.setResult(defaultValue(param.method));
                        if (Config.NOTIFY_AD_LISTENER) {
                            try {
                                AdListenerNotifier.notifyFailure(param.args);
                            } catch (Throwable t) {
                                Log.d("回调失败: " + t);
                            }
                        }
                    }
                });
                hooked += set == null ? 0 : set.size();
            } catch (Throwable t) {
                Log.d("hook 失败 " + clazz.getName() + "#" + name + " : " + t);
            }
        }
        if (hooked > 0) {
            Log.i("已接管 " + clazz.getName() + " → " + hooked + " 个方法");
        } else {
            Log.d("类里没找到该名字的方法: " + clazz.getName());
        }
    }

    /**
     * 开屏类：v1.1 起真正拦截。
     *
     * v1.0 只挂监听、指望域名拦截让它失败，实测无效 —— 竞价上报被拦了，
     * SDK 仍然用本地已缓存的素材把广告播了出来。所以这里两手：
     *   1. 短路 loadAd / loadSplashAd（并回调 onError，让 App 立刻进主页）；
     *   2. 万一还有缓存 ad 对象、App 直接调 show()，就跳过展示；
     *      如果宿主 Activity 名字里带 Splash（确定是开屏页），300ms 后替它 finish，
     *      避免 App 停在开屏页等一个永远不会来的回调。
     */
    public static void hookSplash(Class<?> clazz) {
        int load = 0;
        for (String name : new String[]{"loadAd", "loadSplashAd", "loadSplash"}) {
            load += killLoad(clazz, name);
        }
        int show = hookShow(clazz);
        Log.i("开屏拦截已装: " + clazz.getName() + "（load " + load + " / show " + show + "）");
    }

    private static int killLoad(Class<?> clazz, final String methodName) {
        try {
            Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(clazz, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Log.i("拦截开屏加载: " + param.method.getDeclaringClass().getName()
                            + "#" + param.method.getName());
                    param.setResult(defaultValue(param.method));
                    if (Config.NOTIFY_AD_LISTENER) {
                        try {
                            AdListenerNotifier.notifyFailure(param.args);
                            AdListenerNotifier.notifyFields(param.thisObject);
                        } catch (Throwable t) {
                            Log.d("开屏回调失败: " + t);
                        }
                    }
                }
            });
            return set == null ? 0 : set.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int hookShow(Class<?> clazz) {
        try {
            Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(clazz, "show", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Log.i("跳过开屏展示: " + param.method.getDeclaringClass().getName());
                    param.setResult(null);
                    final Activity act = findActivity(param.args);
                    if (act != null && act.getClass().getName().toLowerCase().contains("splash")) {
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (!act.isFinishing()) {
                                        Log.i("开屏页兜底结束: " + act.getClass().getName());
                                        act.finish();
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }, 300L);
                    }
                }
            });
            return set == null ? 0 : set.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 从参数里找 Activity：可能是 Activity 本身，也可能是 View 的 Context */
    private static Activity findActivity(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a instanceof Activity) {
                return (Activity) a;
            }
            if (a instanceof View) {
                Context c = ((View) a).getContext();
                while (c instanceof ContextWrapper) {
                    if (c instanceof Activity) {
                        return (Activity) c;
                    }
                    c = ((ContextWrapper) c).getBaseContext();
                }
            }
        }
        return null;
    }

    /** 返回 -1 表示已超过打印上限 */
    private static int logOnce(String key) {
        Integer n = COUNT.get(key);
        int c = n == null ? 0 : n;
        if (c < Config.MAX_LOG_PER_METHOD) {
            COUNT.put(key, c + 1);
            return c;
        }
        return -1;
    }

    private static Object defaultValue(Member member) {
        if (!(member instanceof Method)) {
            return null;
        }
        Class<?> type = ((Method) member).getReturnType();
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }
}
