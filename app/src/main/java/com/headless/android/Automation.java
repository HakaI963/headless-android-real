package com.headless.android;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.util.SparseArray;
import android.view.InputEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Android's real UiAutomation, hosted in the Shizuku shell process. */
final class Automation {
    private final UiAutomation automation;
    private final Method connect;
    private final Method disconnect;
    private final Method connectionId;
    private final Method setDisplayId;
    private String state = "disconnected";
    private String error = "";

    Automation(Looper looper) throws Exception {
        exemptHiddenApis();
        Class<?> connectionClass = Class.forName("android.app.UiAutomationConnection");
        Constructor<?> connectionConstructor = connectionClass.getDeclaredConstructor();
        connectionConstructor.setAccessible(true);
        Object connection = connectionConstructor.newInstance();
        Class<?> iface = Class.forName("android.app.IUiAutomationConnection");
        Constructor<?> constructor = UiAutomation.class.getDeclaredConstructor(Looper.class, iface);
        constructor.setAccessible(true);
        automation = (UiAutomation) constructor.newInstance(looper, connection);
        connect = UiAutomation.class.getDeclaredMethod("connect", int.class);
        connect.setAccessible(true);
        disconnect = UiAutomation.class.getDeclaredMethod("disconnect");
        disconnect.setAccessible(true);
        connectionId = UiAutomation.class.getDeclaredMethod("getConnectionId");
        connectionId.setAccessible(true);
        setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
    }

    private static void exemptHiddenApis() {
        try {
            Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
            Method get = runtime.getDeclaredMethod("getRuntime");
            get.setAccessible(true);
            Object instance = get.invoke(null);
            Method exempt = runtime.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            exempt.setAccessible(true);
            exempt.invoke(instance, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
        }
    }

    private void ensure() throws Exception {
        if ("connected".equals(state)) return;
        try {
            connect.invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = automation.getServiceInfo();
            if (info == null) throw new IllegalStateException("no service info");
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
            state = "connected";
            error = "";
        } catch (Throwable e) {
            state = "error";
            error = Util.message(e);
            throw new IllegalStateException("UiAutomation: " + error, e);
        }
    }

    boolean inject(int display, InputEvent event) {
        try {
            ensure();
            setDisplayId.invoke(event, display);
            return automation.injectInputEvent(event, true);
        } catch (Throwable e) {
            return false;
        }
    }

    JSONObject status() {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("available", "connected".equals(state));
            out.put("state", state);
            out.put("error", error);
            out.put("connection_id", "connected".equals(state)
                    ? connectionId.invoke(automation) : -1);
        } catch (Throwable e) {
            return Util.fail(Util.message(e));
        }
        return out;
    }

    JSONObject execute(JSONObject in) throws Exception {
        String op = in.optString("op", "status");
        if ("status".equals(op)) return status();
        ensure();
        if ("tree".equals(op)) return snapshot(in, false);
        if ("find".equals(op)) return snapshot(in, true);
        if ("click".equals(op)) return action(in, AccessibilityNodeInfo.ACTION_CLICK, "click", null);
        if ("long_click".equals(op))
            return action(in, AccessibilityNodeInfo.ACTION_LONG_CLICK, "long_click", null);
        if ("set_text".equals(op)) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    in.optString("value", ""));
            return action(in, AccessibilityNodeInfo.ACTION_SET_TEXT, "set_text", args);
        }
        if ("scroll_forward".equals(op))
            return action(in, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "scroll_forward", null);
        return Util.fail("unknown op " + op);
    }

    private interface Visitor {
        void visit(Snapshot snapshot) throws Exception;
    }

    private static final class Snapshot {
        final JSONObject result = new JSONObject();
        final JSONArray matches = new JSONArray();
        final List<Node> nodes = new ArrayList<>();
        int display;
    }

    private static final class Node {
        AccessibilityNodeInfo info;
        String id;
        JSONObject json;
    }

    private JSONObject snapshot(JSONObject in, boolean onlyMatches) throws Exception {
        final JSONObject[] answer = new JSONObject[1];
        walk(in, snapshot -> {
            if (!onlyMatches) {
                snapshot.result.put("matches", snapshot.matches);
                snapshot.result.put("count", snapshot.matches.length());
            }
            answer[0] = snapshot.result;
        });
        return answer[0];
    }

    private JSONObject action(JSONObject in, int action, String name, Bundle args) throws Exception {
        final JSONObject[] answer = new JSONObject[1];
        walk(in, snapshot -> {
            Node target = firstMatch(snapshot);
            if (target == null) throw new IllegalArgumentException("No matching node on this display");
            boolean ok = target.info.performAction(action, args);
            JSONObject out = new JSONObject();
            out.put("ok", ok);
            out.put("action", name);
            out.put("node", target.json);
            if (!ok) out.put("error", "The app did not accept this action");
            answer[0] = out;
        });
        return answer[0];
    }

    private void walk(JSONObject in, Visitor visitor) throws Exception {
        int display = in.optInt("display", -1);
        if (display < 0) throw new IllegalArgumentException("display required");
        SparseArray<List<AccessibilityWindowInfo>> all = automation.getWindowsOnAllDisplays();
        List<AccessibilityWindowInfo> windows = all == null ? null : all.get(display);
        if (windows == null || windows.isEmpty()) {
            throw new IllegalArgumentException("Display " + display + " has no windows yet");
        }
        Snapshot snapshot = new Snapshot();
        snapshot.display = display;
        int limit = Math.max(20, Math.min(2000, in.optInt("max_nodes", 500)));
        snapshot.result.put("ok", true);
        snapshot.result.put("display_id", display);
        snapshot.result.put("max_nodes", limit);
        JSONArray nodes = new JSONArray();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    flatten(root, snapshot, nodes, 0, limit, 1, in);
                } finally {
                    root.recycle();
                }
            }
        } finally {
            for (Node node : snapshot.nodes) {
                try { node.info.recycle(); } catch (Throwable ignored) {}
            }
        }
        snapshot.result.put("nodes", nodes);
        snapshot.result.put("total_returned", nodes.length());
        snapshot.result.put("truncated", nodes.length() >= limit);
        visitor.visit(snapshot);
    }

    private void flatten(AccessibilityNodeInfo source, Snapshot snapshot, JSONArray output,
                         int index, int limit, int depth, JSONObject query) throws Exception {
        if (index >= limit || depth > 48) return;
        if (!source.isVisibleToUser() && !query.optBoolean("include_invisible", false)
                && source.getChildCount() == 0) return;
        Node node = new Node();
        node.info = AccessibilityNodeInfo.obtain(source);
        node.id = "n" + snapshot.display + "-" + index;
        node.json = describe(node.info, node.id, source);
        output.put(node.json);
        snapshot.nodes.add(node);
        if (matches(query, source)) snapshot.matches.put(node.json);
        for (int i = 0; i < source.getChildCount() && output.length() < limit; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = source.getChild(i);
                if (child != null) flatten(child, snapshot, output, output.length(), limit,
                        depth + 1, query);
            } catch (Throwable ignored) {
            } finally {
                if (child != null) {
                    try { child.recycle(); } catch (Throwable ignored2) {}
                }
            }
        }
    }

    private JSONObject describe(AccessibilityNodeInfo node, String id,
                                 AccessibilityNodeInfo source) throws Exception {
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("text", value(source.getText()));
        out.put("content_description", value(source.getContentDescription()));
        String viewId = source.getViewIdResourceName();
        if (viewId != null) out.put("resource_id", viewId);
        out.put("class", value(source.getClassName()));
        out.put("package", value(source.getPackageName()));
        out.put("clickable", source.isClickable());
        out.put("editable", source.isEditable());
        out.put("scrollable", source.isScrollable());
        out.put("checked", source.isChecked());
        out.put("focused", source.isFocused());
        out.put("enabled", source.isEnabled());
        Rect bounds = new Rect();
        source.getBoundsInScreen(bounds);
        out.put("center", new JSONArray().put(bounds.centerX()).put(bounds.centerY()));
        out.put("bounds", new JSONArray().put(bounds.left).put(bounds.top)
                .put(bounds.right).put(bounds.bottom));
        return out;
    }

    private Node firstMatch(Snapshot snapshot) {
        if (snapshot.matches.length() == 0) return null;
        try {
            String id = snapshot.matches.getJSONObject(0).optString("id", "");
            for (Node node : snapshot.nodes) if (node.id.equals(id)) return node;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean matches(JSONObject query, AccessibilityNodeInfo node) {
        String text = query.optString("text", "").trim();
        String desc = query.optString("content_description", "").trim();
        String id = query.optString("resource_id", "").trim();
        String cls = query.optString("class_name", "").trim();
        String pkg = query.optString("package", "").trim();
        boolean any = false;
        if (!text.isEmpty()) {
            if (!contains(value(node.getText()), text) && !contains(value(node.getContentDescription()), text)) return false;
            any = true;
        }
        if (!desc.isEmpty()) { if (!contains(value(node.getContentDescription()), desc)) return false; any = true; }
        if (!id.isEmpty()) {
            String actual = node.getViewIdResourceName();
            if (actual == null || !actual.contains(id)) return false;
            any = true;
        }
        if (!cls.isEmpty()) { if (!contains(value(node.getClassName()), cls)) return false; any = true; }
        if (!pkg.isEmpty()) { if (!value(node.getPackageName()).equals(pkg)) return false; any = true; }
        if (query.optBoolean("clickable", false) && !node.isClickable()) return false;
        if (query.optBoolean("editable", false) && !node.isEditable()) return false;
        if (query.optBoolean("scrollable", false) && !node.isScrollable()) return false;
        return any || query.optBoolean("clickable", false) || query.optBoolean("editable", false)
                || query.optBoolean("scrollable", false);
    }

    private static boolean contains(String value, String search) {
        return value != null && value.toLowerCase(java.util.Locale.US)
                .contains(search.toLowerCase(java.util.Locale.US));
    }

    private static String value(CharSequence value) {
        if (value == null) return "";
        String text = value.toString();
        return text.length() > 1024 ? text.substring(0, 1024) : text;
    }
}
