package com.headless.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONObject;

/** App-process client for the shell service. Handles permission and rebinding. */
final class Shizuku {
    static final String READY = "ready";
    static final String MISSING = "Shizuku not installed";
    static final String NO_PERMISSION = "Grant Shizuku";
    static final String STARTING = "starting";

    private static final int PERMISSION_CODE = 7701;
    private static Shizuku instance;

    private final Context context;
    private final rikka.shizuku.Shizuku.UserServiceArgs args;
    private volatile IBinder binder;
    private volatile String state = STARTING;

    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            state = READY;
        }
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            state = STARTING;
        }
    };

    private Shizuku(Context context) {
        this.context = context.getApplicationContext();
        this.args = new rikka.shizuku.Shizuku.UserServiceArgs(
                new ComponentName(context, ShellService.class))
                .processNameSuffix("shell")
                .tag("headless")
                .version(1)
                .daemon(false)
                .debuggable(false);
        try {
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(this::refresh);
            rikka.shizuku.Shizuku.addBinderDeadListener(() -> {
                binder = null;
                state = STARTING;
            });
            rikka.shizuku.Shizuku.addRequestPermissionResultListener((code, result) -> {
                if (result == PackageManager.PERMISSION_GRANTED) bind();
                refresh();
            });
        } catch (Throwable e) {
            Log.w("HeadlessAndroid", "shizuku listeners: " + e);
        }
    }

    static Shizuku get(Context context) {
        if (instance == null) {
            synchronized (Shizuku.class) {
                if (instance == null) instance = new Shizuku(context);
            }
        }
        return instance;
    }

    String state() { return state; }

    boolean ready() { return READY.equals(state); }

    void refresh() {
        try {
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                binder = null;
                state = MISSING;
                return;
            }
            if (rikka.shizuku.Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                state = NO_PERMISSION;
                return;
            }
            bind();
        } catch (Throwable e) {
            state = MISSING;
        }
    }

    void bind() {
        try {
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                state = MISSING;
                return;
            }
            if (rikka.shizuku.Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                state = NO_PERMISSION;
                return;
            }
            if (binder != null && binder.pingBinder()) {
                state = READY;
                return;
            }
            binder = null;
            state = STARTING;
            rikka.shizuku.Shizuku.bindUserService(args, connection);
        } catch (Throwable e) {
            Log.w("HeadlessAndroid", "bind: " + e);
        }
    }

    void requestPermission() {
        try {
            rikka.shizuku.Shizuku.requestPermission(PERMISSION_CODE);
        } catch (Throwable ignored) {
        }
    }

    JSONObject exec(JSONObject command) {
        IBinder local = binder;
        if (local == null || !local.pingBinder()) {
            refresh();
            local = binder;
        }
        if (local == null) return Util.fail(state);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeString(command.toString());
            local.transact(1, data, reply, 0);
            String raw = reply.readString();
            return raw == null || raw.isEmpty() ? Util.fail("empty shell reply")
                    : new JSONObject(raw);
        } catch (RemoteException e) {
            binder = null;
            state = STARTING;
            return Util.fail("shell binder lost");
        } catch (Throwable e) {
            return Util.fail(Util.message(e));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
