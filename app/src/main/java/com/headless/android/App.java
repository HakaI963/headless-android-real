package com.headless.android;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Process split and crash safety. No engine work runs in the shell process. */
public class App extends Application {
    private static final String CRASH_DIR = "Download/HeadlessAndroid/crashes";

    @Override
    public void onCreate() {
        super.onCreate();
        int uid = android.os.Process.myUid();
        if (uid == 2000 || uid == 0 || uid == 1000) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            writeCrash(thread, error);
            Log.e("HeadlessAndroid", "uncaught", error);
        });
        try {
            Core.init(this);
            HeadlessService.start(this);
        } catch (Throwable e) {
            writeCrash(Thread.currentThread(), e);
        }
    }

    private void writeCrash(Thread thread, Throwable error) {
        try {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            File dir = new File(android.os.Environment.getExternalStorageDirectory(), CRASH_DIR);
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) return;
            File file = new File(dir, "crash-" + System.currentTimeMillis() + ".txt");
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), "UTF-8")) {
                writer.write("Headless Android crash\nThread: " + thread.getName() + "\n\n");
                writer.write(trace.toString());
            }
        } catch (Throwable ignored) {
        }
    }
}
