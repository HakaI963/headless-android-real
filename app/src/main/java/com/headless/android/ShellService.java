package com.headless.android;

import android.os.Binder;
import android.os.HandlerThread;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/** The privileged half: starts apps on displays and hosts real UiAutomation. */
public class ShellService extends Binder {
    private static final int TX_EXEC = 1;

    private volatile Automation automation;
    private volatile HandlerThread automationThread;

    public ShellService() { super(); }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code != TX_EXEC) return super.onTransact(code, data, reply, flags);
        try {
            String command = data.readString();
            reply.writeString(exec(command == null ? "{}" : command));
        } catch (Throwable e) {
            reply.writeString(Util.fail(Util.message(e)).toString());
        }
        return true;
    }

    private Automation automation() throws Exception {
        Automation local = automation;
        if (local == null) {
            synchronized (this) {
                if (automation == null) {
                    HandlerThread thread = new HandlerThread("HeadlessAndroid-UiAutomation");
                    thread.setDaemon(true);
                    thread.start();
                    automationThread = thread;
                    automation = new Automation(thread.getLooper());
                }
                local = automation;
            }
        }
        return local;
    }

    private String exec(String raw) {
        JSONObject out = new JSONObject();
        try {
            JSONObject in = new JSONObject(raw);
            String cmd = in.optString("cmd", "");
            switch (cmd) {
                case "info": {
                    out.put("ok", true);
                    out.put("uid", android.os.Process.myUid());
                    out.put("shizuku", rikka.shizuku.Shizuku.getVersion());
                    try {
                        out.put("automation", automation().status());
                    } catch (Throwable e) {
                        out.put("automation", Util.fail(Util.message(e)));
                    }
                    break;
                }
                case "start": {
                    String component = in.optString("component", "");
                    int display = in.optInt("display", -1);
                    if (component.isEmpty() || display < 0) {
                        return Util.fail("bad start command").toString();
                    }
                    Result result = run("am start --user 0 --display " + display
                            + " -f 0x18000000 -W -n '" + component.replace("'", "'\\''") + "'");
                    out.put("ok", result.code == 0);
                    out.put("output", result.output);
                    break;
                }
                case "stop": {
                    String pkg = in.optString("package", "");
                    Result result = run("am force-stop --user 0 '"
                            + pkg.replace("'", "'\\''") + "'");
                    out.put("ok", result.code == 0);
                    break;
                }
                case "ui":
                    return automation().execute(in).toString();
                case "tap":
                case "long":
                case "swipe":
                case "key":
                case "text":
                    return input(in).toString();
                default:
                    return Util.fail("unknown command " + cmd).toString();
            }
        } catch (Throwable e) {
            return Util.fail(Util.message(e)).toString();
        }
        return out.toString();
    }

    private JSONObject input(JSONObject in) throws Exception {
        Automation auto = automation();
        int display = in.optInt("display", -1);
        String cmd = in.optString("cmd", "");
        if (cmd.equals("tap")) {
            long now = android.os.SystemClock.uptimeMillis();
            float x = (float) in.optDouble("x", 0);
            float y = (float) in.optDouble("y", 0);
            boolean ok = auto.inject(display, motion(now, now, MotionEvent.ACTION_DOWN, x, y))
                    & auto.inject(display, motion(now, now + 60, MotionEvent.ACTION_UP, x, y));
            return new JSONObject().put("ok", ok);
        }
        if (cmd.equals("swipe")) {
            long now = android.os.SystemClock.uptimeMillis();
            int duration = Math.max(100, in.optInt("duration", 300));
            float x1 = (float) in.optDouble("x1", 0), y1 = (float) in.optDouble("y1", 0);
            float x2 = (float) in.optDouble("x2", 0), y2 = (float) in.optDouble("y2", 0);
            boolean ok = auto.inject(display, motion(now, now, MotionEvent.ACTION_DOWN, x1, y1));
            for (int i = 1; i <= 8; i++) {
                float p = i / 8f;
                ok = auto.inject(display, motion(now, now + (long) (duration * p),
                        MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * p, y1 + (y2 - y1) * p)) && ok;
            }
            return new JSONObject().put("ok", ok);
        }
        if (cmd.equals("key")) {
            long now = android.os.SystemClock.uptimeMillis();
            int code = in.optInt("code", -1);
            boolean ok = auto.inject(display, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
                    & auto.inject(display, new KeyEvent(now, now + 20, KeyEvent.ACTION_UP, code, 0));
            return new JSONObject().put("ok", ok);
        }
        if (cmd.equals("text")) {
            KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                    .getEvents(in.optString("text", "").toCharArray());
            boolean ok = events != null && events.length > 0;
            if (ok) for (KeyEvent event : events) ok = auto.inject(display, event) && ok;
            return new JSONObject().put("ok", ok);
        }
        return Util.fail("unknown input command");
    }

    private static MotionEvent motion(long down, long event, int action, float x, float y) {
        MotionEvent result = MotionEvent.obtain(down, event, action,
                Math.round(x), Math.round(y), 1f, 1f, 0, 1f, 1f, 0, 0);
        result.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return result;
    }

    private static final class Result {
        final int code;
        final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }

    private static Result run(String line) {
        try {
            Process process = new ProcessBuilder("/system/bin/sh", "-c", line)
                    .redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            String row;
            while ((row = reader.readLine()) != null) out.append(row).append('\n');
            boolean done = process.waitFor(25, TimeUnit.SECONDS);
            if (!done) process.destroyForcibly();
            return new Result(done ? process.exitValue() : -1, out.toString().trim());
        } catch (Throwable e) {
            return new Result(-1, Util.message(e));
        }
    }
}
