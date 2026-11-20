package com.headless.android;

import org.json.JSONException;
import org.json.JSONObject;

final class Util {
    private Util() {}

    static int dp(android.content.Context c, float value) {
        return (int) (value * c.getResources().getDisplayMetrics().density);
    }

    static String hex() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(32);
        for (byte b : bytes) out.append(String.format(java.util.Locale.US, "%02x", b));
        return out.toString();
    }

    static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') out.append("\\\"");
            else if (c == '\\') out.append("\\\\");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else out.append(c);
        }
        return out.toString();
    }

    static JSONObject fail(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static String message(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return cause.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
