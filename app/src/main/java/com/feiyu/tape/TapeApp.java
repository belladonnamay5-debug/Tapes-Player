package com.feiyu.tape;

import android.app.Application;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 整个进程共用一台"机器"。
 * Activity 关掉、前台服务还挂着的时候，带子不能断——所以引擎不能跟着 Activity 走。
 *
 * 另外负责一件事：老平板没有 logcat，崩溃现场只能自己记下来。
 * 记两份：应用私有目录一份（下次启动自己读），外置卡一份（USB 插上电脑能直接拷走）。
 * 再数一下"连续崩了几次"：连着崩两次以上，下次就用安全模式起，先把错误摆到屏幕上。
 */
public class TapeApp extends Application {

    private static TapeEngine shared;
    private static Context appCtx;

    /** 上一次异常退出的现场，下次开机时读出来给用户看 */
    public static final String CRASH_FILE = "last_crash.txt";

    private static final String PREFS = "tape_prefs";
    private static final String KEY_STREAK = "crash_streak";
    private static final String BOOT_MARK = "booting.marker";
    private static final String KEY_MINIMAL = "minimal_mode";

    public static synchronized TapeEngine engine(Context c) {
        try {
            if (shared == null) shared = new TapeEngine(c.getApplicationContext());
        } catch (Throwable t) {
            report(t);
        }
        return shared;
    }

    @Override
    public void onCreate() {
        // 这里出错会让整个进程起不来，所以整段包住：宁可少做事，也不能崩
        try {
            super.onCreate();
            appCtx = this;
            final Thread.UncaughtExceptionHandler sys = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        reportFatal(e);
                    } catch (Throwable ignored) {
                    }
                    if (sys != null) sys.uncaughtException(t, e);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 连续崩溃次数：>= 2 就该怀疑这台平板跑不动这台机器了 */
    public static int crashStreak(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_STREAK, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 界面真的画出来了（说明这一趟没崩），把连击清零 */
    public static void markHealthy(Context c) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_STREAK, 0).commit();
            File m = new File(c.getFilesDir(), BOOT_MARK);
            if (m.exists()) m.delete();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 开机哨兵：进程被系统/驱动直接干掉时，Java 的异常处理根本不会跑，
     * 所以只能在下一次启动时检查"上次有没有活着画出来"。
     * 调用时机：Activity.onCreate 一开始（先查后记）。
     */
    public static void bootStart(Context c) {
        try {
            File m = new File(c.getFilesDir(), BOOT_MARK);
            FileOutputStream out = new FileOutputStream(m, false);
            try {
                out.write(("t=" + System.currentTimeMillis() + " "
                        + android.os.Build.MODEL + " sdk=" + android.os.Build.VERSION.SDK_INT)
                        .getBytes("UTF-8"));
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** true = 上一次没画完就没的（很可能是原生崩溃，比如 GPU 驱动） */
    public static boolean bootCrashedLastTime(Context c) {
        try {
            File m = new File(c.getFilesDir(), BOOT_MARK);
            if (m.exists()) {
                m.delete();
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 给"上次原生死掉"记一笔，触发安全模式 */
    public static void noteFatal(Context c) {
        bumpStreak(c);
    }

    /** 这一趟活着（哪怕只是安全模式的页面），别再算成崩溃 */
    public static void markAlive(Context c) {
        try {
            File m = new File(c.getFilesDir(), BOOT_MARK);
            if (m.exists()) m.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 极简模式：不画台面，只留能按的键。台面在这台机器上画不出来时的保底方案 */
    public static boolean minimalMode(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MINIMAL, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setMinimalMode(Context c, boolean on) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MINIMAL, on).commit();
        } catch (Throwable ignored) {
        }
    }

    private static void bumpStreak(Context c) {
        try {
            int n = crashStreak(c);
            if (n < 100) n++;
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_STREAK, n).commit();
        } catch (Throwable ignored) {
        }
    }

    /** 记录一次异常。任何地方都能调，自己不会抛。注意：这一条不增加"连续崩溃"计数 */
    public static void report(Throwable e) {
        if (e == null) return;
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            pw.flush();
            String body = "t=" + System.currentTimeMillis() + "\n"
                    + "device=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + " sdk=" + android.os.Build.VERSION.SDK_INT
                    + " hw=" + android.os.Build.HARDWARE + "\n"
                    + sw.toString();
            writeFile(body);
        } catch (Throwable ignored) {
        }
    }

    /** 记一条纯文字：不算异常，但下次启动一定要知道的事（比如图层尺寸对不上） */
    public static void reportText(String msg) {
        if (msg == null) return;
        try {
            String body = "t=" + System.currentTimeMillis() + "\n"
                    + "device=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + " sdk=" + android.os.Build.VERSION.SDK_INT
                    + " hw=" + android.os.Build.HARDWARE + "\n"
                    + msg + "\n";
            writeFile(body);
        } catch (Throwable ignored) {
        }
    }

    /** 致命的那一种：进程真的被带走了。写现场 + 连击 +1 */
    public static void reportFatal(Throwable e) {
        report(e);
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            if (e != null) e.printStackTrace(pw);
            pw.flush();
            String body = "t=" + System.currentTimeMillis() + "\n"
                    + "device=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + " sdk=" + android.os.Build.VERSION.SDK_INT
                    + " hw=" + android.os.Build.HARDWARE + "\n"
                    + sw.toString();
            writeFile(body);
            if (appCtx != null) bumpStreak(appCtx);
        } catch (Throwable ignored) {
        }
    }

    private static void writeFile(String body) throws Exception {
        byte[] bytes = body.getBytes("UTF-8");
        write(new File(appCtx.getFilesDir(), CRASH_FILE), bytes);
        File ext = externalDir(appCtx);
        if (ext != null) write(new File(ext, CRASH_FILE), bytes);
    }

    /** 外置卡上的现场文件（Android/data/包名/files），拷出来最方便 */
    public static File externalDir(Context c) {
        try {
            File f = c.getExternalFilesDir(null);
            if (f != null) {
                if (!f.exists()) f.mkdirs();
                return f;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void write(File f, byte[] bytes) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            out.write(bytes);
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 上次的现场：先找外置卡，再找私有目录。读完删掉，免得每次开机都报 */
    public static String takeCrash(Context c) {
        File[] cands = new File[2];
        try {
            cands[0] = new File(c.getFilesDir(), CRASH_FILE);
        } catch (Throwable ignored) {
        }
        File ext = externalDir(c);
        cands[1] = ext == null ? null : new File(ext, CRASH_FILE);
        for (int i = 0; i < cands.length; i++) {
            File f = cands[i];
            if (f == null || !f.exists() || f.length() <= 0) continue;
            try {
                byte[] buf = new byte[(int) Math.min(f.length(), 4000)];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                int n;
                try {
                    n = in.read(buf);
                } finally {
                    in.close();
                }
                f.delete();
                return new String(buf, 0, Math.max(0, n), "UTF-8");
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
