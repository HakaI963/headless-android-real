package com.headless.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import android.view.Display;

import java.lang.reflect.Field;

/** One virtual display and the surface that captures what it renders. */
final class DisplaySession {
    private final ImageReader reader;
    private final VirtualDisplay display;
    final int displayId;
    final int width;
    final int height;
    private Bitmap staging;
    private int stride;
    private volatile Bitmap latest;
    private volatile long frames;

    private DisplaySession(ImageReader reader, VirtualDisplay display, int displayId,
                           int width, int height) {
        this.reader = reader;
        this.display = display;
        this.displayId = displayId;
        this.width = width;
        this.height = height;
    }

    static DisplaySession create(Context context, int width, int height, int dpi) {
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) throw new IllegalStateException("DisplayManager unavailable");
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        int base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        int[] extra = new int[]{
                flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH")
                        | flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS")
                        | flag("VIRTUAL_DISPLAY_FLAG_TRUSTED"),
                flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH"),
                0};
        Throwable last = null;
        for (int value : extra) {
            VirtualDisplay created = null;
            try {
                created = manager.createVirtualDisplay("HeadlessAndroid", width, height,
                        dpi, reader.getSurface(), base | value);
                Display target = created == null ? null : created.getDisplay();
                if (target != null) {
                    return new DisplaySession(reader, created, target.getDisplayId(), width, height);
                }
            } catch (Throwable e) {
                last = e;
            }
            if (created != null) created.release();
        }
        reader.close();
        throw new IllegalStateException("createVirtualDisplay failed", last);
    }

    private static int flag(String name) {
        try {
            Field field = DisplayManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Some ROMs create public virtual displays in the OFF state; reattach to wake. */
    void ensureOn() {
        try {
            Display display = this.display.getDisplay();
            if (display != null && display.getState() == Display.STATE_ON) return;
            this.display.setSurface(null);
            SystemClock.sleep(120);
            this.display.setSurface(reader.getSurface());
            SystemClock.sleep(300);
        } catch (Throwable ignored) {
        }
    }

    Bitmap capture() {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return latest;
            Image.Plane plane = image.getPlanes()[0];
            int pixelStride = Math.max(1, plane.getPixelStride());
            int rowStride = plane.getRowStride() / pixelStride;
            if (staging == null || stride != rowStride) {
                if (staging != null) staging.recycle();
                stride = Math.max(width, rowStride);
                staging = Bitmap.createBitmap(stride, height, Bitmap.Config.ARGB_8888);
            }
            staging.copyPixelsFromBuffer(plane.getBuffer());
            Bitmap frame = stride == width
                    ? Bitmap.createBitmap(staging)
                    : Bitmap.createBitmap(staging, 0, 0, width, height);
            latest = frame;
            frames++;
            return frame;
        } catch (Throwable e) {
            return latest;
        } finally {
            if (image != null) {
                try { image.close(); } catch (Throwable ignored) {}
            }
        }
    }

    long frames() { return frames; }

    void release() {
        try { display.release(); } catch (Throwable ignored) {}
        try { reader.close(); } catch (Throwable ignored) {}
        try { if (staging != null) staging.recycle(); } catch (Throwable ignored) {}
    }
}
