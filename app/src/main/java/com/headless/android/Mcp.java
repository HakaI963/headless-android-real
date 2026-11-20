package com.headless.android;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Built-in MCP endpoint. The app is the server; no external controller exists. */
final class Mcp {
    private static final String VERSION = "2025-06-18";
    private static volatile Core core;
    private static volatile String token = "";
    private static volatile ServerSocket server;
    private static volatile ExecutorService pool;
    private static volatile int port = -1;

    private Mcp() {}

    static synchronized void start(Core core, int preferredPort, String token) {
        stop();
        Mcp.core = core;
        Mcp.token = token == null ? "" : token.trim();
        try {
            ServerSocket socket = null;
            int bound = -1;
            for (int p = preferredPort; p < preferredPort + 4; p++) {
                try {
                    socket = new ServerSocket();
                    socket.setReuseAddress(true);
                    socket.bind(new InetSocketAddress("0.0.0.0", p), 16);
                    bound = p;
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (socket == null) throw new IllegalStateException("no free port near " + preferredPort);
            server = socket;
            port = bound;
            pool = Executors.newFixedThreadPool(8);
            Thread acceptor = new Thread(Mcp::accept, "HeadlessAndroid-MCP");
            acceptor.setDaemon(true);
            acceptor.start();
            Log.i("HeadlessAndroid", "MCP listening on " + bound);
        } catch (Throwable e) {
            Log.w("HeadlessAndroid", "MCP failed: " + e);
        }
    }

    static synchronized void stop() {
        try { if (server != null) server.close(); } catch (Throwable ignored) {}
        try { if (pool != null) pool.shutdownNow(); } catch (Throwable ignored) {}
        server = null;
        pool = null;
        port = -1;
    }

    static boolean running() { return server != null && !server.isClosed(); }

    static int port() { return port; }

    static String endpoint() {
        if (!running()) return null;
        return "http://" + localAddress() + ":" + port;
    }

    private static String localAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.getAddress().length == 4) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "127.0.0.1";
    }

    private static void accept() {
        ServerSocket socket = server;
        if (socket == null) return;
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                client.setSoTimeout(30000);
                final ExecutorService executor = pool;
                if (executor == null) { client.close(); break; }
                executor.execute(() -> handle(client));
            } catch (Throwable e) {
                if (socket.isClosed()) break;
            }
        }
    }

    private static void handle(Socket socket) {
        try (Socket client = socket;
             InputStream in = client.getInputStream();
             OutputStream out = client.getOutputStream()) {
            String head = readHead(in);
            if (head.isEmpty()) return;
            String[] lines = head.split("\r?\n");
            String[] request = lines[0].split(" ");
            String method = request.length > 0 ? request[0] : "GET";
            String path = request.length > 1 ? request[1] : "/";
            int length = 0;
            String auth = "";
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.toLowerCase(java.util.Locale.US).startsWith("content-length:")) {
                    try { length = Integer.parseInt(line.substring(15).trim()); }
                    catch (Throwable ignored) {}
                }
                if (line.toLowerCase(java.util.Locale.US).startsWith("x-droid-token:")) {
                    auth = line.substring(14).trim();
                }
            }
            byte[] body = new byte[0];
            if (length > 0 && length < 8 * 1024 * 1024) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while (buffer.size() < length && (read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                body = buffer.toByteArray();
            }
            if (path.contains("?")) path = path.substring(0, path.indexOf('?'));

            if ("GET".equals(method) && "/health".equals(path)) {
                JSONObject health = new JSONObject();
                health.put("ok", true).put("apps", core == null ? 0 : core.slots().size());
                health.put("shell", core == null ? "offline" : Shizuku.get(core.context()).state());
                respond(out, 200, "application/json", health.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/shot/")) {
                int slot = number(path.substring(6).replace(".jpg", ""), 0);
                Bitmap frame = core == null ? null : core.frame(slot);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (frame == null || !frame.compress(Bitmap.CompressFormat.JPEG, 88, bytes)) {
                    respond(out, 404, "text/plain", "no frame".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                respond(out, 200, "image/jpeg", bytes.toByteArray());
                return;
            }
            if ("POST".equals(method) && "/mcp".equals(path)) {
                String raw = new String(body, StandardCharsets.UTF_8);
                respond(out, 200, "application/json",
                        rpc(raw, auth).getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            Log.w("HeadlessAndroid", "http: " + e);
        }
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int current;
        while ((current = in.read()) != -1) {
            out.write(current);
            if (out.size() > 16384) break;
            byte[] bytes = out.toByteArray();
            if (bytes.length >= 4
                    && bytes[bytes.length - 4] == '\r' && bytes[bytes.length - 3] == '\n'
                    && bytes[bytes.length - 2] == '\r' && bytes[bytes.length - 1] == '\n') break;
        }
        return out.toString("US-ASCII");
    }

    private static void respond(OutputStream out, int status, String type, byte[] body)
            throws Exception {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(" ").append(status == 200 ? "OK" : "Error")
                .append("\r\nContent-Type: ").append(type)
                .append("\r\nContent-Length: ").append(body.length)
                .append("\r\nAccess-Control-Allow-Origin: *")
                .append("\r\nAccess-Control-Allow-Headers: Content-Type, X-Droid-Token")
                .append("\r\nConnection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String rpc(String raw, String auth) {
        Object id = JSONObject.NULL;
        try {
            JSONObject message = new JSONObject(raw);
            if (!"2.0".equals(message.optString("jsonrpc", ""))) {
                return error(id, -32600, "expected jsonrpc 2.0");
            }
            id = message.has("id") && !message.isNull("id") ? message.opt("id") : JSONObject.NULL;
            boolean notification = !message.has("id") || message.isNull("id");
            String method = message.optString("method", "");
            JSONObject params = message.optJSONObject("params");
            if (params == null) params = new JSONObject();
            if (token.isEmpty() || !token.equals(auth)) {
                return error(id, -32001, "unauthorized: send X-Droid-Token");
            }
            if (notification) return "";
            JSONObject result;
            switch (method) {
                case "initialize": {
                    JSONObject info = new JSONObject();
                    info.put("name", "Headless Android").put("version", "1.0.0");
                    result = new JSONObject();
                    result.put("protocolVersion", VERSION);
                    result.put("serverInfo", info);
                    result.put("capabilities", new JSONObject().put("tools", new JSONObject()));
                    result.put("instructions", "Use launch_app, ui_tree, ui_find and ui_click to drive real apps on private displays.");
                    break;
                }
                case "ping":
                    result = new JSONObject();
                    break;
                case "tools/list":
                    result = new JSONObject().put("tools", tools());
                    break;
                case "tools/call":
                    result = tool(params);
                    break;
                default:
                    return error(id, -32601, "unknown method " + method);
            }
            JSONObject reply = new JSONObject();
            reply.put("jsonrpc", "2.0").put("id", id).put("result", result);
            return reply.toString();
        } catch (Throwable e) {
            return error(id, -32700, Util.message(e));
        }
    }

    private static String error(Object id, int code, String message) {
        try {
            JSONObject err = new JSONObject().put("code", code).put("message", message);
            return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", err).toString();
        } catch (Throwable e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603}}";
        }
    }

    private static JSONArray tools() throws Exception {
        String[][] names = {
                {"grid_status", "List running displays"},
                {"list_apps", "List installed launchable apps"},
                {"create_display", "Create an empty virtual display"},
                {"launch_app", "Launch an app on a virtual display"},
                {"close_app", "Close one display"},
                {"tap", "Tap a display at normalized x,y"},
                {"key", "Send a key to a display"},
                {"screenshot", "Capture a display as a data URI"},
                {"ui_status", "Check the shell UiAutomation connection"},
                {"ui_tree", "Read the accessibility tree of a display"},
                {"ui_find", "Find nodes by text or resource id"},
                {"ui_click", "Click a matching accessibility node"},
                {"ui_set_text", "Set text on a matching editable node"},
        };
        JSONArray array = new JSONArray();
        for (String[] item : names) {
            JSONObject tool = new JSONObject();
            tool.put("name", item[0]).put("description", item[1]);
            tool.put("inputSchema", new JSONObject().put("type", "object")
                    .put("properties", new JSONObject()).put("required", new JSONArray()));
            array.put(tool);
        }
        return array;
    }

    private static JSONObject tool(JSONObject params) throws Exception {
        String name = params.optString("name", "");
        JSONObject args = params.optJSONObject("arguments");
        if (args == null) args = new JSONObject();
        try {
            String text;
            switch (name) {
                case "grid_status":
                    text = core == null ? "{}" : core.status().toString(2);
                    break;
                case "list_apps": {
                    JSONArray array = new JSONArray();
                    if (core != null) {
                        for (Core.AppInfo info : core.apps()) {
                            array.put(new JSONObject().put("label", info.label)
                                    .put("package", info.pkg));
                        }
                    }
                    text = array.toString();
                    break;
                }
                case "create_display": {
                    if (core == null) throw new IllegalStateException("engine not ready");
                    Core.Slot slot = core.createDisplay();
                    if (slot == null) throw new IllegalArgumentException("all displays are in use");
                    text = new JSONObject().put("slot", slot.slot).toString();
                    break;
                }
                case "launch_app": {
                    String pkg = args.optString("package", "");
                    if (pkg.isEmpty() || core == null) throw new IllegalArgumentException("package required");
                    Core.Slot slot = core.launch(pkg);
                    if (slot == null) throw new IllegalArgumentException("cannot launch " + pkg);
                    text = new JSONObject().put("slot", slot.slot).put("display_id", slot.displayId)
                            .toString();
                    break;
                }
                case "close_app":
                    if (core == null) throw new IllegalStateException("engine not ready");
                    core.close(args.optInt("slot", 0));
                    text = "{\"closed\":true}";
                    break;
                case "tap": {
                    JSONObject reply = core.input("tap", args);
                    text = reply.toString();
                    break;
                }
                case "key": {
                    JSONObject reply = core.input("key", args);
                    text = reply.toString();
                    break;
                }
                case "screenshot": {
                    Bitmap frame = core == null ? null : core.frame(args.optInt("slot", 0));
                    if (frame == null) throw new IllegalArgumentException("no frame");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    frame.compress(Bitmap.CompressFormat.JPEG, 88, bytes);
                    text = new JSONObject().put("data",
                            "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(),
                                    Base64.NO_WRAP)).toString();
                    break;
                }
                case "ui_status": {
                    if (core == null) throw new IllegalStateException("engine not ready");
                    text = core.shellInfo().toString(2);
                    break;
                }
                case "ui_tree":
                    text = core.ui("tree", args).toString(2);
                    break;
                case "ui_find":
                    text = core.ui("find", args).toString(2);
                    break;
                case "ui_click":
                    text = core.ui("click", args).toString(2);
                    break;
                case "ui_set_text":
                    text = core.ui("set_text", args).toString(2);
                    break;
                default:
                    throw new IllegalArgumentException("unknown tool " + name);
            }
            JSONObject content = new JSONObject().put("type", "text").put("text", text);
            return new JSONObject().put("content", new JSONArray().put(content)).put("isError", false);
        } catch (Throwable e) {
            JSONObject content = new JSONObject().put("type", "text")
                    .put("text", Util.message(e));
            return new JSONObject().put("content", new JSONArray().put(content)).put("isError", true);
        }
    }

    private static int number(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); }
        catch (Throwable e) { return fallback; }
    }
}
