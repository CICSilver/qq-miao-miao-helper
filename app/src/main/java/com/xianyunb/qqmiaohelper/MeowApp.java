package com.xianyunb.qqmiaohelper;

import android.app.Application;

/**
 * 只做一件事：在进程刚起来的时候装上未捕获异常处理器。
 *
 * 无障碍服务和主界面跑在同一个进程里，所以在这里装一次，整个进程的
 * 所有线程都覆盖到了 —— 包括无障碍框架自己回调过来的那条线程。
 *
 * 装完之后原来的处理器照样调用，崩溃该怎么报还怎么报，我们只是在崩溃
 * 落地之前先把堆栈落到文件里，让用户不接电脑也能把原因发出来。
 */
public class MeowApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        MeowLog.init(this);

        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    MeowLog.w("!! 未捕获异常，线程 " + t.getName(), e);
                } catch (Throwable ignored) {
                    // 崩溃处理器里再崩就真没救了
                }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });

        MeowLog.w("进程启动");
    }
}
