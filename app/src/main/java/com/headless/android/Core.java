package com.headless.android;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns displays, launches, input and the MCP endpoint. Java only, no lateinit. */
final class Core {
    static final int MAX_SLOTS = 9;
    private static Core instance;

    static final class Slot {
        final int slot;
        final String pkg;
        final String label;
        final String component;
        volatile DisplaySession session;
        volatile int displayId = -1;
        volatile String state = "starting";
        volatile String error = "";
        volatile int width;
        volatile int height;

        Slot(int slot, String pkg, String label, String component, int width, int height) {
            this.slot = slot;
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.width = width;
            this.height = height;
        }
    }

    static final class AppInfo {
        final String label;
        final String pkg;
        final String component;
        AppInfo(String label, String pkg, String component) {
            this.label = label;
            this.pkg = pkg;
            this.component = component;
        }
    }

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Slot> slots = new ArrayList<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final PowerManager.WakeLock wakeLock;
    private volatile List<AppInfo> apps = Collections.emptyList();

    private Core(Context context) {
        this.context = context.getApplicationContext();
        Prefs.migrate(this.context);
        PowerManager.WakeLock lock = null;
        try {
            PowerManager manager = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
            if (manager != null) {
                lock = manager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "HeadlessAndroid:displays");
                lock.setReferenceCounted(false);
            }
        } catch (Throwable ignored) {
        }
        wakeLock = lock;
        Shizuku.get(this.context).refresh();
        worker.execute(this::loadApps);
        try {
            Mcp.start(this, Prefs.port(this.context), Prefs.token(this.context));
        } catch (Throwable e) {
            Log.w("HeadlessAndroid", "MCP start failed: " + e);
        }
    }

    static synchronized Core init(Context context) {
        if (instance == null) instance = new Core(context);
        return instance;
    }

    static Core get() { return instance; }

    Context context() { return context; }

    void addListener(Runnable listener) { listeners.add(listener); }
    void removeListener(Runnable listener) { listeners.remove(listener); }

    private void notifyListeners() {
        main.post(() -> {
            for (Runnable listener : listeners) {
                try { listener.run(); } catch (Throwable ignored) {}
            }
        });
    }

    private void loadApps() {
        List<AppInfo> found = new ArrayList<>();
        try {
            PackageManager pm = context.getPackageManager();
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                Intent intent = pm.getLaunchIntentForPackage(info.packageName);
                if (intent == null || intent.getComponent() == null) continue;
                String label;
                try { label = String.valueOf(pm.getApplicationLabel(info)); }
                catch (Throwable e) { label = info.packageName; }
                found.add(new AppInfo(label, info.packageName,
                        intent.getComponent().flattenToShortString()));
            }
            found.sort(Comparator.comparing(a -> a.label.toLowerCase(java.util.Locale.US)));
        } catch (Throwable e) {
            Log.w("HeadlessAndroid", "app list: " + e);
        }
        apps = found;
        notifyListeners();
    }

    List<AppInfo> apps() { return apps; }

    List<Slot> slots() {
        synchronized (slots) { return new ArrayList<>(slots); }
    }

    Slot slot(int number) {
        synchronized (slots) {
            for (Slot slot : slots) if (slot.slot == number) return slot;
        }
        return null;
    }

    Slot slotFor(String pkg) {
        synchronized (slots) {
            for (Slot slot : slots) if (slot.pkg.equals(pkg)) return slot;
        }
        return null;
    }

    private int freeSlot() {
        synchronized (slots) {
            for (int i = 1; i <= MAX_SLOTS; i++) {
                boolean used = false;
                for (Slot slot : slots) if (slot.slot == i) used = true;
                if (!used) return i;
            }
        }
        return -1;
    }

    /** Creates a real virtual display with no app on it — visible proof the system works. */
    Slot createDisplay() {
        int number = freeSlot();
        if (number < 1) return null;
        int[] screen = screenSize();
        Slot slot = new Slot(number, "virtual", "Virtual display", "", screen[0], screen[1]);
        synchronized (slots) { slots.add(slot); }
        acquireWake();
        notifyListeners();
        worker.execute(() -> {
            try {
                DisplaySession session = DisplaySession.create(context, slot.width, slot.height,
                        context.getResources().getDisplayMetrics().densityDpi);
                slot.session = session;
                slot.displayId = session.displayId;
                slot.state = "live";
            } catch (Throwable e) {
                slot.state = "error";
                slot.error = Util.message(e);
            }
            notifyListeners();
        });
        return slot;
    }

    Slot launch(String pkg) {
        AppInfo entry = null;
        for (AppInfo info : apps()) if (info.pkg.equals(pkg)) entry = info;
        if (entry == null) return null;
        Slot existing = slotFor(pkg);
        if (existing != null) { restart(existing.slot); return existing; }
        int number = freeSlot();
        if (number < 1) return null;
        int[] screen = screenSize();
        Slot slot = new Slot(number, pkg, entry.label, entry.component, screen[0], screen[1]);
        synchronized (slots) { slots.add(slot); }
        acquireWake();
        notifyListeners();
        final Slot target = slot;
        worker.execute(() -> startSlot(target));
        return slot;
    }

    private void startSlot(Slot slot) {
        try {
            DisplaySession session = DisplaySession.create(context, slot.width, slot.height,
                    slot.width * 2 / 3);
            slot.session = session;
            slot.displayId = session.displayId;
            JSONObject reply = shell(new JSONObject()
                    .put("cmd", "start").put("component", slot.component)
                    .put("display", slot.displayId));
            if (!reply.optBoolean("ok")) {
                Intent intent = context.getPackageManager().getLaunchIntentForPackage(slot.pkg);
                if (intent != null) {
                    ActivityOptions options = ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(slot.displayId);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent, options.toBundle());
                } else {
                    throw new IllegalStateException(reply.optString("error", "launch failed"));
                }
            }
            slot.state = "live";
            // The app on the virtual display must not steal the controller UI.
            main.post(() -> {
                try {
                    Intent bring = new Intent(context, MainActivity.class);
                    bring.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    context.startActivity(bring);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable e) {
            slot.state = "error";
            slot.error = Util.message(e);
            Log.w("HeadlessAndroid", "slot " + slot.slot + ": " + slot.error);
        }
        notifyListeners();
    }

    private int[] screenSize() {
        int columns = Prefs.columns(context);
        if (columns == 1) return new int[]{540, 720};
        if (columns == 2) return new int[]{420, 560};
        return new int[]{360, 480};
    }

    void close(int number) {
        Slot slot = slot(number);
        if (slot == null) return;
        synchronized (slots) { slots.remove(slot); }
        worker.execute(() -> {
            try {
                shell(new JSONObject().put("cmd", "stop").put("package", slot.pkg));
            } catch (Throwable ignored) {}
            if (slot.session != null) slot.session.release();
        });
        releaseWake();
        notifyListeners();
    }

    void closeAll() {
        for (Slot slot : slots()) close(slot.slot);
    }

    void restart(int number) {
        Slot slot = slot(number);
        if (slot == null) return;
        String pkg = slot.pkg;
        close(number);
        launch(pkg);
    }

    private void acquireWake() {
        try { if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(); }
        catch (Throwable ignored) {}
    }

    private void releaseWake() {
        try { if (wakeLock != null && wakeLock.isHeld() && slots.isEmpty()) wakeLock.release(); }
        catch (Throwable ignored) {}
    }

    JSONObject shellInfo() {
        try {
            return shell(new JSONObject().put("cmd", "info"));
        } catch (Throwable e) {
            return Util.fail(Util.message(e));
        }
    }

    private JSONObject shell(JSONObject command) {
        if (!Shizuku.get(context).ready()) Shizuku.get(context).refresh();
        return Shizuku.get(context).exec(command);
    }

    JSONObject input(String cmd, JSONObject args) throws Exception {
        Slot slot = slot(args.optInt("slot", 0));
        if (slot == null || slot.displayId < 0) throw new IllegalArgumentException("slot not running");
        JSONObject command = new JSONObject();
        command.put("cmd", cmd);
        command.put("display", slot.displayId);
        java.util.Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"slot".equals(key)) command.put(key, args.get(key));
        }
        return shell(command);
    }

    JSONObject ui(String op, JSONObject args) throws Exception {
        Slot slot = slot(args.optInt("slot", 0));
        if (slot == null || slot.displayId < 0) throw new IllegalArgumentException("slot not running");
        JSONObject command = new JSONObject();
        java.util.Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"slot".equals(key)) command.put(key, args.get(key));
        }
        command.put("cmd", "ui").put("op", op).put("display", slot.displayId);
        return shell(command);
    }

    Bitmap frame(int number) {
        Slot slot = slot(number);
        return slot == null || slot.session == null ? null : slot.session.capture();
    }

    JSONObject status() {
        JSONObject out = new JSONObject();
        try {
            JSONArray array = new JSONArray();
            for (Slot slot : slots()) {
                JSONObject item = new JSONObject();
                item.put("slot", slot.slot);
                item.put("package", slot.pkg);
                item.put("state", slot.state);
                item.put("display_id", slot.displayId);
                item.put("frames", slot.session == null ? 0 : slot.session.frames());
                if (!slot.error.isEmpty()) item.put("error", slot.error);
                array.put(item);
            }
            out.put("slots", array);
            out.put("used", slots().size());
            out.put("max", MAX_SLOTS);
            out.put("columns", Prefs.columns(context));
            out.put("shell", Shizuku.get(context).state());
            out.put("endpoint", Mcp.endpoint());
        } catch (Throwable e) {
            return Util.fail(Util.message(e));
        }
        return out;
    }
}
