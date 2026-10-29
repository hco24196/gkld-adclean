package com.gkld.adclean.hook;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import com.gkld.adclean.Config;
import com.gkld.adclean.Log;

/**
 * 自营信息流广告卡片（com.maidu.gkld.adapter.ad.AdCenterViewHolder）。
 *
 * 这类广告不走广告 SDK：数据由 api.gongkaoleida.com 下发（AdV2Bean / AdV2ListBean），
 * 用普通 View 渲染，域名拦截和 SDK 拦截都碰不到，只能从列表绑定这一层抹掉。
 *
 * v1.2 两个改动：
 *   1. **不再 hook 全局 RecyclerView.Adapter.bindViewHolder**。那是所有列表项每次复用都要过的
 *      钩子，里面还带反射查字段，首页/列表滑动时开销非常明显；而且真机日志里每次绑定都
 *      抛 `NoSuchFieldError: AdCenterViewHolder#itemView`，等于反复制造异常。现在只 hook
 *      这个 ViewHolder 自己的构造，零全局开销。
 *   2. itemView 改成**递归遍历字段**去找（父类里的 itemView 也能拿到），
 *      找不到就静默放弃并只记一次日志，不再每次抛异常。
 */
public final class FeedHooks {

    private static boolean fieldWarned;

    private FeedHooks() {
    }

    public static void hookViewHolder(Class<?> holderCls, ClassLoader cl) {
        if (!Config.HIDE_FEED_AD_ITEM) {
            return;
        }
        final String target = holderCls.getName();
        try {
            XposedBridge.hookAllConstructors(holderCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    hide(param.thisObject, "构造");
                }
            });
            Log.i("已接管信息流广告 ViewHolder: " + target);
        } catch (Throwable t) {
            Log.e("ViewHolder 钩子失败", t);
        }
    }

    private static void hide(Object holder, String from) {
        try {
            View v = findItemView(holder.getClass(), holder);
            if (v == null) {
                if (!fieldWarned) {
                    fieldWarned = true;
                    Log.d("AdCenterViewHolder 里找不到 itemView 字段，跳过（布局层仍会隐藏该卡片）");
                }
                return;
            }
            if (v.getVisibility() == View.GONE) {
                return;
            }
            v.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                lp.height = 0;
                v.setLayoutParams(lp);
            }
            Log.d("隐藏信息流广告卡片(" + from + ") " + holder.getClass().getSimpleName());
        } catch (Throwable t) {
            if (!fieldWarned) {
                fieldWarned = true;
                Log.d("隐藏卡片失败（后续静默）: " + t);
            }
        }
    }

    /** 沿继承链找名字含 itemView、或类型是 View 的字段 */
    private static View findItemView(Class<?> cls, Object holder) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                String n = f.getName();
                if (!n.equals("itemView") && !n.endsWith("ItemView")) {
                    continue;
                }
                if (!View.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object o = f.get(holder);
                    if (o instanceof View) {
                        return (View) o;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }
}
