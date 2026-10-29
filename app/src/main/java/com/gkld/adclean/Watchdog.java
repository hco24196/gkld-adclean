package com.gkld.adclean;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 启动看门狗（v1.6 新增）。
 *
 * 目的：万一 v1.6 还有某种边角情况的崩溃，不要让用户卡在「崩 → 找我 → 等新版」的循环里，
 * 让模块自己发现这件事并自动降级。
 *
 * 规则很简单：
 *   进程一进来先标记 `boot=1`；
 *   撑过 5 秒（Activity 都起来了）改标 `boot=0`；
 *   下次启动时如果读到的还是 `boot=1`，说明上次没走到 5 秒就没了 → 本次只装网络层，
 *   不装视图层的钩子（inflate / onResume），保证至少能用。
 *
 * 状态文件写在 App 自己的 files 目录里 —— 模块代码就跑在 App 进程内，同一个 uid，
 * 直接 File IO 即可，不需要 Context（handleLoadPackage 阶段还拿不到 Context）。
 * 全部 try-catch 包住：读写失败就当作「没有崩溃过」，绝不因为看门狗自己出问题影响启动。
 */
public final class Watchdog {

    private static final String STATE_DIR =
            "/data/data/" + Config.TARGET_PACKAGE + "/files";

    private static final String STATE_FILE = "adclean.state";

    private static final String BOOTING = "boot=1";

    private static final String HEALTHY = "boot=0";

    /** 撑过这么久就算「这次启动正常」 */
    public static final long HEALTHY_AFTER_MS = 5000L;

    private Watchdog() {
    }

    /** 上次启动是不是异常中断（没撑到 5 秒） */
    public static boolean crashedLastTime() {
        String s = read();
        return s != null && s.contains(BOOTING);
    }

    /** 进程刚起来时调用，标记「本次启动进行中」 */
    public static void markBooting() {
        write(BOOTING);
    }

    /** 启动稳定后调用，清除「进行中」标记 */
    public static void markHealthy() {
        write(HEALTHY);
    }

    private static String read() {
        FileInputStream in = null;
        try {
            File f = new File(STATE_DIR, STATE_FILE);
            if (!f.exists()) {
                return null;
            }
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
        }
    }

    private static void write(String s) {
        FileOutputStream out = null;
        try {
            File dir = new File(STATE_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            out = new FileOutputStream(new File(dir, STATE_FILE));
            out.write(s.getBytes("UTF-8"));
        } catch (Throwable ignored) {
            // 写不进去不影响功能，只是降级机制失效
        } finally {
            close(out);
        }
    }

    private static void close(java.io.Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
