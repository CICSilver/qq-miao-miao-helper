package com.xianyunb.qqmiaohelper;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 写到文件里的诊断日志，用来回答一个 adb 才能回答的问题：
 * 「无障碍服务是崩了、被系统停用了、还是整个进程被杀了？」
 *
 * 这三种情况在手机上看起来一模一样（开关自己关掉了），但日志里长得完全不同：
 *
 *   崩溃       出现「!! 未捕获异常」加一段堆栈
 *   系统停用   出现「服务解绑」或「服务销毁」，前面没有异常
 *   进程被杀   最后一行停在别处，什么收尾记录都没有
 *
 * 所以关键不只在于记了什么，还在于【最后一行停在哪】。
 *
 * 日志走文件而不是 logcat，是因为要让用户在手机上直接看到 —— 让人为了
 * 一个自用小工具去装 adb 不现实。
 */
public final class MeowLog {

    private static final String TAG = "QQMiao";
    private static final String FILE = "miao-diag.log";
    /** 超过就从头截掉一半。诊断只关心最近发生了什么。 */
    private static final long MAX_BYTES = 64 * 1024;

    private static File file;

    private MeowLog() {
    }

    public static synchronized void init(Context ctx) {
        if (file == null) {
            file = new File(ctx.getApplicationContext().getFilesDir(), FILE);
        }
    }

    public static File fileOf(Context ctx) {
        init(ctx);
        return file;
    }

    /** 记一行。任何情况下都不能因为记日志本身把调用方搞崩。 */
    public static synchronized void w(String line) {
        if (file == null) {
            Log.d(TAG, line);
            return;
        }
        try {
            trimIfHuge();
            FileWriter fw = new FileWriter(file, true);
            try {
                fw.write(stamp() + "  " + line + "\n");
            } finally {
                fw.close();
            }
        } catch (Throwable ignored) {
            // 诊断日志写不进去就算了，不能反过来影响功能
        }
        Log.d(TAG, line);
    }

    public static void w(String line, Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        w(line + "\n" + sw);
    }

    public static synchronized String read(Context ctx) {
        init(ctx);
        try {
            if (!file.exists()) {
                return "";
            }
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                byte[] buf = new byte[(int) raf.length()];
                raf.readFully(buf);
                return new String(buf, "UTF-8");
            } finally {
                raf.close();
            }
        } catch (Throwable t) {
            return "读取日志失败：" + t;
        }
    }

    public static synchronized void clear(Context ctx) {
        init(ctx);
        try {
            if (file.exists() && !file.delete()) {
                new FileWriter(file, false).close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    /** 文件太大就砍掉前半截，保留最近的部分 */
    private static void trimIfHuge() throws Exception {
        if (!file.exists() || file.length() <= MAX_BYTES) {
            return;
        }
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            long keepFrom = raf.length() - MAX_BYTES / 2;
            raf.seek(keepFrom);
            byte[] tail = new byte[(int) (raf.length() - keepFrom)];
            raf.readFully(tail);
            raf.setLength(0);
            raf.seek(0);
            raf.write("...（更早的记录已截断）\n".getBytes("UTF-8"));
            raf.write(tail);
        } finally {
            raf.close();
        }
    }
}
