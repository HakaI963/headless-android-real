package com.headless.android;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private static final String FILE = "headless";
    private static SharedPreferences prefs;

    private static SharedPreferences get(Context context) {
        if (prefs == null) {
            synchronized (Prefs.class) {
                if (prefs == null) {
                    prefs = context.getApplicationContext()
                            .getSharedPreferences(FILE, Context.MODE_PRIVATE);
                }
            }
        }
        return prefs;
    }

    static int port(Context context) {
        int value = get(context).getInt("port", 8765);
        return value < 1024 || value > 65535 ? 8765 : value;
    }

    static void port(Context context, int value) {
        get(context).edit().putInt("port", value < 1024 || value > 65535 ? 8765 : value).apply();
    }

    static String token(Context context) {
        String value = get(context).getString("token", null);
        if (value == null || value.trim().isEmpty()) {
            value = Util.hex();
            get(context).edit().putString("token", value).apply();
        }
        return value.trim();
    }

    static void newToken(Context context) {
        get(context).edit().putString("token", Util.hex()).apply();
    }

    static void migrate(Context context) {
        SharedPreferences prefs = get(context);
        if (!prefs.getBoolean("layout2", false)) {
            prefs.edit().putInt("columns", 1).putBoolean("layout2", true).apply();
        }
    }

    static int columns(Context context) {
        int value = get(context).getInt("columns", 1);
        return value < 1 || value > 3 ? 1 : value;
    }

    static void columns(Context context, int value) {
        get(context).edit().putInt("columns", Math.max(1, Math.min(3, value))).apply();
    }
}
