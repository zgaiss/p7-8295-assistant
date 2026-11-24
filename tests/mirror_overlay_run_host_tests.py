#!/usr/bin/env python3
"""Compile the real overlay/mailbox/fanout against deterministic Android fakes.

No Android SDK, camera, vehicle, network, or production-source extraction. Fakes
model API calls and failure paths, not Android compositor/HAL behavior.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import textwrap

ROOT = Path(__file__).resolve().parents[1]
FAKES = {
    "android/Manifest.java": """
        package android;
        public final class Manifest {
            public static final class permission { public static final String CAMERA = "android.permission.CAMERA"; }
        }
    """,
    "android/content/pm/PackageManager.java": """
        package android.content.pm;
        public final class PackageManager { public static final int PERMISSION_GRANTED = 0, PERMISSION_DENIED = -1; }
    """,
    "android/util/DisplayMetrics.java": """
        package android.util;
        public class DisplayMetrics { public int widthPixels = 1920, heightPixels = 1080; public float density = 1; }
    """,
    "android/util/Log.java": """
        package android.util;
        public final class Log { public static int w(String tag, String message) { return 0; } }
    """,
    "android/content/res/Resources.java": """
        package android.content.res;
        import android.util.DisplayMetrics;
        public class Resources {
            public final DisplayMetrics metrics = new DisplayMetrics();
            public DisplayMetrics getDisplayMetrics() { return metrics; }
        }
    """,
    "android/content/Context.java": """
        package android.content;
        import android.content.res.Resources;
        import android.view.WindowManager;
        public class Context {
            public static final String WINDOW_SERVICE = "window";
            public final Resources resources = new Resources();
            public WindowManager windows = new WindowManager(resources.metrics);
            public boolean cameraGranted = true, overlayGranted = true;
            public boolean permissionThrows, settingsThrows;
            public Context getApplicationContext() { return this; }
            public Object getSystemService(String name) { return WINDOW_SERVICE.equals(name) ? windows : null; }
            public int checkSelfPermission(String name) {
                if (permissionThrows) throw new SecurityException("permission service unavailable");
                return cameraGranted ? 0 : -1;
            }
            public Resources getResources() { return resources; }
        }
    """,
    "android/provider/Settings.java": """
        package android.provider;
        import android.content.Context;
        public final class Settings {
            public static boolean canDrawOverlays(Context context) {
                if (context.settingsThrows) throw new SecurityException("settings unavailable");
                return context.overlayGranted;
            }
        }
    """,
    "android/os/Looper.java": """
        package android.os;
        public final class Looper {
            private static final Looper MAIN = new Looper();
            public static Looper getMainLooper() { return MAIN; }
        }
    """,
    "android/os/SystemClock.java": """
        package android.os;
        public final class SystemClock {
            public static long now = 1000;
            public static long elapsedRealtime() { return now; }
        }
    """,
    "android/os/Handler.java": """
        package android.os;
        import java.util.ArrayList;
        import java.util.List;
        public final class Handler {
            private static final class Task {
                Handler owner; Runnable work; long at, order;
                Task(Handler h, Runnable r, long t, long seq) { owner=h; work=r; at=t; order=seq; }
            }
            private static final List<Task> queue = new ArrayList<Task>();
            private static long sequence;
            public Handler(Looper looper) { }
            public boolean post(Runnable r) { return postDelayed(r, 0); }
            public boolean postDelayed(Runnable r, long ms) {
                queue.add(new Task(this, r, SystemClock.now + Math.max(0, ms), sequence++)); return true;
            }
            public void removeCallbacks(Runnable r) { queue.removeIf(t -> t.owner == this && t.work == r); }
            public static int pending() { return queue.size(); }
            public static void reset() { queue.clear(); sequence=0; SystemClock.now=1000; }
            public static void advanceTo(long now) {
                if (now < SystemClock.now) throw new AssertionError("test clock cannot go backwards");
                SystemClock.now=now; runReady();
            }
            public static void runReady() {
                int runs=0;
                for (;;) {
                    Task next=null;
                    for (Task t:queue) {
                        if (t.at <= SystemClock.now && (next == null || t.at < next.at
                                || (t.at == next.at && t.order < next.order))) next=t;
                    }
                    if (next == null) return;
                    if (++runs > 10000) throw new AssertionError("unbounded handler repost loop");
                    queue.remove(next); next.work.run();
                }
            }
        }
    """,
    "android/graphics/Bitmap.java": """
        package android.graphics;
        import java.nio.Buffer;
        import java.nio.ByteBuffer;
        public final class Bitmap {
            public enum Config { RGB_565 }
            public static boolean createThrows, copyThrows;
            public static long copyClockAdvance;
            public final int width, height;
            public final Config config;
            public final byte[] pixels;
            private Bitmap(int w, int h, Config c) { width=w; height=h; config=c; pixels=new byte[w*h*2]; }
            public static Bitmap createBitmap(int w, int h, Config c) {
                if (createThrows) throw new IllegalStateException("bitmap create failure");
                return new Bitmap(w,h,c);
            }
            public void copyPixelsFromBuffer(Buffer buffer) {
                if (copyThrows) throw new IllegalStateException("bitmap copy failure");
                ((ByteBuffer)buffer).get(pixels);
                android.os.SystemClock.now += copyClockAdvance;
            }
        }
    """,
    "android/graphics/Rect.java": """
        package android.graphics;
        public class Rect {
            public int left, top, right, bottom;
            public void set(int l,int t,int r,int b) { left=l; top=t; right=r; bottom=b; }
        }
    """,
    "android/graphics/Paint.java": """
        package android.graphics;
        public class Paint {
            public static final int ANTI_ALIAS_FLAG=1, FILTER_BITMAP_FLAG=2;
            public Paint(int flags) { }
            public void setColor(int color) { }
            public void setTextSize(float size) { }
        }
    """,
    "android/graphics/Color.java": """
        package android.graphics;
        public final class Color { public static final int WHITE = -1; }
    """,
    "android/graphics/PixelFormat.java": """
        package android.graphics;
        public final class PixelFormat { public static final int TRANSLUCENT = -3; }
    """,
    "android/graphics/Canvas.java": """
        package android.graphics;
        public final class Canvas {
            public Bitmap bitmap;
            public final Rect destination = new Rect();
            public String text;
            public int bitmapDraws;
            public void drawBitmap(Bitmap b, Rect source, Rect target, Paint paint) {
                bitmap=b; bitmapDraws++; destination.set(target.left,target.top,target.right,target.bottom);
            }
            public void drawRect(float l,float t,float r,float b,Paint p) { }
            public void drawText(String value,float x,float y,Paint p) { text=value; }
        }
    """,
    "android/view/Gravity.java": """
        package android.view;
        public final class Gravity { public static final int LEFT=3, RIGHT=5, CENTER_VERTICAL=16; }
    """,
    "android/view/View.java": """
        package android.view;
        import android.content.Context;
        import android.content.res.Resources;
        import android.graphics.Canvas;
        public class View {
            public static final int VISIBLE=0, GONE=8;
            private final Context context;
            public int visibility=VISIBLE, width, height, invalidations;
            public View(Context context) { this.context=context; }
            public Resources getResources() { return context.getResources(); }
            public int getWidth() { return width; }
            public int getHeight() { return height; }
            public void setVisibility(int value) { visibility=value; }
            public void invalidate() { invalidations++; }
            protected void onDraw(Canvas canvas) { }
            public void draw(Canvas canvas) { onDraw(canvas); }
        }
    """,
    "android/view/Display.java": """
        package android.view;
        import android.util.DisplayMetrics;
        public class Display {
            private final DisplayMetrics metrics;
            Display(DisplayMetrics metrics) { this.metrics=metrics; }
            public void getRealMetrics(DisplayMetrics out) {
                out.widthPixels=metrics.widthPixels; out.heightPixels=metrics.heightPixels; out.density=metrics.density;
            }
        }
    """,
    "android/view/WindowManager.java": """
        package android.view;
        import android.util.DisplayMetrics;
        import java.util.HashSet;
        import java.util.Set;
        public class WindowManager {
            public static class LayoutParams {
                public static final int TYPE_APPLICATION_OVERLAY=2038, FLAG_NOT_FOCUSABLE=8,
                    FLAG_NOT_TOUCHABLE=16, FLAG_LAYOUT_IN_SCREEN=256;
                public int width,height,type,flags,format,gravity,x,y;
                public float alpha=1;
                public CharSequence title;
                public LayoutParams(int w,int h,int t,int f,int fmt) { width=w; height=h; type=t; flags=f; format=fmt; }
                public void setTitle(CharSequence value) { title=value; }
            }
            public final Set<View> installed = new HashSet<View>();
            public View lastView;
            public LayoutParams lastLayout;
            public int adds,updates,removes;
            public boolean addThrows,partialAddThrows,updateThrows,removeThrows,alreadyRemoved;
            private final Display display;
            public WindowManager(DisplayMetrics metrics) { display=new Display(metrics); }
            public Display getDefaultDisplay() { return display; }
            private void remember(View v,LayoutParams p) { lastView=v; lastLayout=p; v.width=p.width; v.height=p.height; }
            public void addView(View v,LayoutParams p) {
                adds++;
                if (installed.contains(v)) throw new AssertionError("duplicate addView");
                if (addThrows) throw new SecurityException("add denied");
                remember(v,p); installed.add(v);
                if (partialAddThrows) throw new IllegalStateException("vendor partially added window");
            }
            public void updateViewLayout(View v,LayoutParams p) {
                updates++;
                if (updateThrows) throw new IllegalStateException("update failed");
                if (!installed.contains(v)) throw new IllegalArgumentException("not attached");
                remember(v,p);
            }
            public void removeViewImmediate(View v) {
                removes++;
                if (alreadyRemoved) { installed.remove(v); throw new IllegalArgumentException("already removed"); }
                if (removeThrows) throw new IllegalStateException("remove failed");
                if (!installed.remove(v)) throw new IllegalArgumentException("not attached");
            }
        }
    """,
    "com/xiaopeng/clusterassist/MirrorPush.java": """
        package com.xiaopeng.clusterassist;
        // Only dimensions are used by MirrorOverlay; no network sender is substituted in fanout tests.
        final class MirrorPush { static final int W=640, H=360; }
    """,
}


def java_tool(name):
    home = os.environ.get("JAVA_HOME")
    candidate = Path(home) / "bin" / name if home else None
    if candidate is not None and candidate.is_file():
        return str(candidate)
    resolved = shutil.which(name)
    if not resolved:
        raise SystemExit("Missing JDK tool: " + name)
    return resolved


def main():
    # This context owns the newly created absolute temporary directory and its cleanup.
    with tempfile.TemporaryDirectory(prefix="p7-overlay-host-") as directory:
        output = Path(directory)
        sources = []
        for name, body in FAKES.items():
            target = output / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(textwrap.dedent(body), encoding="utf-8")
            sources.append(str(target))
        production = ROOT / "carpad/src/com/xiaopeng/clusterassist"
        sources.extend(str(production / name) for name in (
            "MirrorFrameSink.java", "MirrorFrameFanout.java", "MirrorPreviewFrames.java", "MirrorOverlay.java"))
        sources.append(str(ROOT / "tests/MirrorOverlayTest.java"))
        subprocess.run([
            java_tool("javac"), "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-Xlint:-options", "-d", str(output), *sources,
        ], check=True)
        subprocess.run([
            java_tool("java"), "-ea", "-cp", str(output), "com.xiaopeng.clusterassist.MirrorOverlayTest",
        ], check=True)


if __name__ == "__main__":
    main()
