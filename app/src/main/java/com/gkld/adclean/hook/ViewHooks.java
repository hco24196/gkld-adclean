package com.gkld.adclean.hook;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import com.gkld.adclean.Config;
import com.gkld.adclean.Log;

/**
 * 视图层：隐藏自营运营位。
 *
 * 分两类，都不 hook 任何「框架核心方法」：
 *
 *   A. 按布局名（自营卡片 / 悬浮球）
 *      hook LayoutInflater.inflate(int, ViewGroup, boolean) 的 **after** ——
 *      原方法照常执行，我们只在返回后看一眼布局名，命中黑名单就 setVisibility(GONE)。
 *      布局名稳定是因为 AndResGuard 只混淆文件名，resources.arsc 里的名字没动。
 *
 *   B. 按控件 id（「我的」页那条广告轮播）
 *      这类不是独立布局，而是 fragment_user_center.xml 里直接写的
 *      com.maidu.gkld.view.banner.Banner 控件，按布局名抓不到。
 *      做法：inflate 返回后对根视图 findViewById 命中黑名单 id，就置 GONE，
 *      并挂一个 OnPreDraw 监听 —— App 之后想再 setVisibility(VISIBLE) 时，
 *      下一帧绘制前会被立刻压回 GONE。
 *
 * v1.6 删掉了 v1.3~v1.5 的 View.setVisibility 全局钩子：
 *   它要等 Banner 类加载才装得上（依赖 ClassWatcher → loadClass 钩子 → 首启闪退），
 *   而且全 App 每一次可见性变化都要跨 Xposed 桥，成本最高、收益最低。
 *   现在换成「只对命中的那个 View 挂 OnPreDraw」——影响面从全 App 收缩到 2 个控件。
 *
 * 另外，Activity.onResume 是顺带的两个用途：
 *   1. 重扫一遍 decorView，把 fragment 切换 / 重新 attach 后漏网的控件再压回 GONE；
 *   2. 首次 resume 时启动 OkHttp 钩子的后台延时安装（见 HttpHooks#scheduleLateInstall）。
 */
public final class ViewHooks {

    private static final Map<Integer, String> NAME_CACHE = new ConcurrentHashMap<>();

    private static final Set<String> BLOCKED = new HashSet<>();

    private static final Set<String> SEEN = new HashSet<>();

    /** 已经打过日志的广告控件 id，避免刷屏 */
    private static final Set<Integer> ID_LOGGED = new HashSet<>();

    private static final String NONE = "";

    /**
     * 已挂上「绘制前压回 GONE」监听的控件。
     * key 用弱引用（View 生命周期由 App 掌握），value 里的 listener 也只弱引用 View，
     * 否则 WeakHashMap 的 value 强引用 key 会造成永不回收的泄漏。
     */
    private static final Map<View, ViewTreeObserver.OnPreDrawListener> GUARDED =
            Collections.synchronizedMap(new WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>());

    private static volatile boolean installed;

    private static volatile boolean lateInstallStarted;

    private ViewHooks() {
    }

    public static void install(ClassLoader cl) {
        if (installed) {
            return;
        }
        installed = true;
        for (String n : Config.LAYOUT_BLOCKLIST) {
            BLOCKED.add(n);
        }
        if (Config.HIDE_LAYOUTS) {
            hookInflate(cl);
        }
        // setVisibility 兜底（v1.7）：不依赖任何 App 类，纯按控件 id 精确匹配。
        // 这是唯一被真机验证过、确实能让「我的」页轮播消失的手段。
        if (Config.BLOCK_VIEW_ID_VIA_SETVISIBILITY) {
            hookSetVisibility();
        }
        // onResume 有两个用途：补挂控件 + 触发 OkHttp 延时安装，
        // 任意一个需要就装
        if (Config.HIDE_BY_VIEW_ID || Config.REWRITE_URL) {
            hookResume(cl);
        }
    }

    // ------------------------------------------------------------- inflate

    private static void hookInflate(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.view.LayoutInflater", cl, "inflate",
                    int.class, ViewGroup.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                handleInflate(param);
                            } catch (Throwable ignored) {
                                // 绝不让异常回流到 inflate 调用方
                            }
                        }
                    });
            Log.i("布局拦截已就绪，黑名单 " + BLOCKED.size() + " 个");
        } catch (Throwable t) {
            Log.e("布局拦截安装失败", t);
        }
    }

    private static void handleInflate(XC_MethodHook.MethodHookParam param) {
        Object result = param.getResult();
        if (!(result instanceof View) || param.args.length < 1
                || !(param.args[0] instanceof Integer)) {
            return;
        }
        View view = (View) result;
        int id = (Integer) param.args[0];
        String name = entryName(view.getContext(), id);

        if (name != null && Config.HIDE_LAYOUTS) {
            if (BLOCKED.contains(name)) {
                hide(view);
                Log.i("隐藏运营位布局: " + name);
            } else if (Config.DEBUG && looksLikeAd(name) && SEEN.add(name)) {
                Log.d("疑似广告布局（未隐藏，可加进 Config.LAYOUT_BLOCKLIST）: " + name);
            }
        }

        // 不管布局名如何都查一遍控件 id —— 「我的」页那个轮播所在的
        // fragment_user_center 不在黑名单里（整页都要留着）
        if (Config.HIDE_BY_VIEW_ID) {
            hideBlockedIds(view);
        }
    }

    // ---------------------------------------------------------- 控件 id

    /**
     * 在 inflate 出来的视图树里找广告控件。
     *
     * findViewById 是递归遍历，但只在根视图上各查 2 个 id，且命中的控件一次性挂上
     * OnPreDraw 之后就永久生效 —— 列表滚动时的重复 inflate 会走到 GUARDED 命中并直接返回。
     */
    private static void hideBlockedIds(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        for (int want : Config.BLOCK_VIEW_IDS) {
            View hit;
            try {
                hit = root.findViewById(want);
            } catch (Throwable t) {
                continue;
            }
            if (hit != null) {
                guard(hit, want);
            }
        }
    }

    /**
     * 把这个控件永久锁死在 GONE。
     *
     * Banner 在 XML 里默认 GONE，App 拿到广告数据后才 setVisibility(VISIBLE)。
     * 挂 OnPreDraw：每帧绘制前检查一次，一旦它变成可见就压回 GONE —— 相当于
     * 「每一次 setVisibility(VISIBLE) 都被撤销」，但不需要 hook framework 的
     * View.setVisibility，影响面只在这一个控件上。
     */
    private static void guard(final View v, final int id) {
        synchronized (GUARDED) {
            if (GUARDED.containsKey(v)) {
                hide(v);
                return;
            }
            final WeakReference<View> ref = new WeakReference<>(v);
            ViewTreeObserver.OnPreDrawListener listener = new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    View view = ref.get();
                    if (view == null) {
                        return true;
                    }
                    try {
                        if (view.getVisibility() != View.GONE) {
                            view.setVisibility(View.GONE);
                            if (ID_LOGGED.add(id)) {
                                Log.i("拦截广告轮播显示: id=0x" + Integer.toHexString(id)
                                        + " (" + entryName(view.getContext(), id) + ")");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            };
            GUARDED.put(v, listener);
            try {
                v.getViewTreeObserver().addOnPreDrawListener(listener);
            } catch (Throwable t) {
                Log.d("OnPreDraw 注册失败: " + t);
            }
        }
        hide(v);
        if (ID_LOGGED.add(id)) {
            Log.i("锁定广告控件: id=0x" + Integer.toHexString(id)
                    + " (" + entryName(v.getContext(), id) + ")");
        }
    }

    // ------------------------------------------------------- setVisibility

    /**
     * 直接吞掉「让广告控件显示」的那次 setVisibility（v1.7 恢复）。
     *
     * v1.3 就是靠这一招让「我的」页轮播消失的，v1.6 换成 OnPreDraw 监听后失效 ——
     * 最可能的解释：那个轮播不是 inflate 出来的子树里的控件，而是 App 用 addView
     * 动态挂上去的，`root.findViewById(0x7f090183)` 从头到尾返回 null，
     * 监听器根本没有机会挂到真正的控件上。
     *
     * 这里不依赖 Banner 类做 isInstance 判断：0x7f090183(banner_user_center) 和
     * 0x7f090177(banner_ad) 这两个 id 唯一对应那两个广告控件，按 id 匹配足够精确，
     * 不会误伤「考试区域」(0x7f09017b) 和首页「精选考试」轮播。
     *
     * 只在「要把它设为非 GONE」时拦截；GONE 本身放行，免得影响 App 自己的隐藏逻辑。
     */
    private static void hookSetVisibility() {
        try {
            XposedBridge.hookAllMethods(View.class, "setVisibility", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length != 1
                                || !(param.args[0] instanceof Integer)) {
                            return;
                        }
                        int vis = (Integer) param.args[0];
                        if (vis == View.GONE) {
                            return;
                        }
                        Object self = param.thisObject;
                        if (!(self instanceof View)) {
                            return;
                        }
                        int id = ((View) self).getId();
                        if (!isBlockedId(id)) {
                            return;
                        }
                        // 吞掉这次调用，控件维持原来的 GONE
                        param.setResult(null);
                        if (ID_LOGGED.add(id)) {
                            Log.i("拦截广告轮播显示: id=0x" + Integer.toHexString(id)
                                    + " (" + entryName(((View) self).getContext(), id) + ")");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            Log.i("setVisibility 拦截已装（按控件 id）");
        } catch (Throwable t) {
            Log.e("setVisibility 拦截安装失败", t);
        }
    }

    // ------------------------------------------------------------- onResume

    /**
     * Activity.onResume 之后：
     *   1. 重扫 decorView —— fragment 切换、View 重新 attach 后 ViewTreeObserver 会换新的，
     *      旧的 OnPreDraw 监听随之失效，所以每次 resume 都重新扫一遍并补挂；
     *   2. 首次 resume 启动 OkHttp 钩子的后台延时安装（那时加固壳必然已解密完毕）。
     */
    private static void hookResume(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.thisObject instanceof Activity)) {
                                    return;
                                }
                                Activity a = (Activity) param.thisObject;
                                startLateInstallOnce(a);
                                if (!Config.HIDE_BY_VIEW_ID) {
                                    return;
                                }
                                View root = a.getWindow() != null
                                        ? a.getWindow().getDecorView() : null;
                                if (root != null) {
                                    hideBlockedIds(root);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            Log.i("Activity.onResume 监听已就绪");
        } catch (Throwable t) {
            Log.e("onResume 监听安装失败", t);
        }
    }

    private static void startLateInstallOnce(Activity a) {
        if (lateInstallStarted) {
            return;
        }
        lateInstallStarted = true;
        ClassLoader cl = null;
        try {
            cl = a.getClassLoader();
        } catch (Throwable ignored) {
        }
        HttpHooks.scheduleLateInstall(cl);
    }

    // -------------------------------------------------------------- 工具

    private static boolean isBlockedId(int id) {
        for (int want : Config.BLOCK_VIEW_IDS) {
            if (want == id) {
                return true;
            }
        }
        return false;
    }

    private static void hide(View view) {
        try {
            // 只置 GONE，不改 LayoutParams：改高度会触发父布局重新测量，
            // 容易和宿主自己的测量打架（白屏/抖动）
            if (view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean looksLikeAd(String name) {
        String n = name.toLowerCase();
        for (String kw : Config.LAYOUT_PROBE_KEYWORDS) {
            if (n.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static String entryName(Context ctx, int id) {
        String cached = NAME_CACHE.get(id);
        if (cached != null) {
            return NONE.equals(cached) ? null : cached;
        }
        String name = null;
        try {
            Resources res = ctx.getResources();
            name = res.getResourceEntryName(id);
        } catch (Throwable ignored) {
        }
        NAME_CACHE.put(id, name == null ? NONE : name);
        return name;
    }
}
