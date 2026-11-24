#!/usr/bin/env python3
"""Execute real MirrorSource/MirrorAvmSession with deterministic Android camera fakes.

No Android runtime or source-text assertions: callbacks and owner tasks execute on
an explicitly advanced fake Handler queue. These tests do not prove HAL/optical output.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCES = {
    "android/content/Context.java": '''package android.content;
public class Context {
 public static final String CAMERA_SERVICE="camera";
 private final Object manager;
 public Context(Object m){manager=m;}
 public Context getApplicationContext(){return this;}
 public Object getSystemService(String name){return manager;}
}''',
    "android/graphics/ImageFormat.java": '''package android.graphics;
public class ImageFormat { public static final int YUV_420_888=35; }''',
    "android/os/SystemClock.java": '''package android.os;
public class SystemClock { public static long now=1000; public static long elapsedRealtime(){return now;} }''',
    "android/os/Looper.java": '''package android.os; public class Looper {}''',
    "android/os/HandlerThread.java": '''package android.os;
public class HandlerThread { public HandlerThread(String name){} public void start(){}
 public Looper getLooper(){return new Looper();} }''',
    "android/os/Handler.java": '''package android.os;
import java.util.PriorityQueue;
public class Handler {
 private static long sequence;
 private static class Task implements Comparable<Task> {
  long when, seq; Runnable body;
  Task(long t,Runnable r){when=t;body=r;seq=sequence++;}
  public int compareTo(Task b){int c=Long.compare(when,b.when);return c!=0?c:Long.compare(seq,b.seq);}
 }
 private static final PriorityQueue<Task> queue=new PriorityQueue<Task>();
 public Handler(Looper looper){}
 public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long delay){queue.add(new Task(SystemClock.now+delay,r));return true;}
 public static void ready(){advance(0);}
 public static void advance(long delta){
  long end=SystemClock.now+delta; int count=0;
  while(!queue.isEmpty()&&queue.peek().when<=end){
   if(++count>10000)throw new AssertionError("queue did not settle");
   Task t=queue.remove();SystemClock.now=t.when;t.body.run();
  }
  SystemClock.now=end;
 }
 public static int pending(){return queue.size();}
}''',
    "android/util/Log.java": '''package android.util;
public class Log { public static int i(String tag,String msg){return 0;}
 public static int w(String tag,String msg){return 0;} }''',
    "android/util/Size.java": '''package android.util;
public class Size { private final int w,h; public Size(int w,int h){this.w=w;this.h=h;}
 public int getWidth(){return w;} public int getHeight(){return h;} }''',
    "android/util/Range.java": '''package android.util;
public class Range<T> { public final T lower, upper; public Range(T l, T u){lower=l;upper=u;} }''',
    "android/view/Surface.java": '''package android.view; public class Surface {}''',
    "android/hardware/camera2/CameraAccessException.java": '''package android.hardware.camera2;
public class CameraAccessException extends Exception {}''',
    "android/hardware/camera2/params/StreamConfigurationMap.java": '''package android.hardware.camera2.params;
import android.util.Size;
public class StreamConfigurationMap { public Size[] getOutputSizes(int format){return new Size[]{new Size(1280,720)};} }''',
    "android/hardware/camera2/CameraCharacteristics.java": '''package android.hardware.camera2;
import android.hardware.camera2.params.StreamConfigurationMap;
public class CameraCharacteristics {
 public static class Key<T> {}
 public static final Key<StreamConfigurationMap> SCALER_STREAM_CONFIGURATION_MAP=new Key<StreamConfigurationMap>();
 @SuppressWarnings("unchecked") public <T> T get(Key<T> key){return (T)new StreamConfigurationMap();}
}''',
    "android/hardware/camera2/CaptureRequest.java": '''package android.hardware.camera2;
import android.view.Surface;
public class CaptureRequest {
 public static final Object CONTROL_AE_TARGET_FPS_RANGE = new Object();
 public static class Builder {
  public void addTarget(Surface s){} public void set(Object k,Object v){}
  public CaptureRequest build(){return new CaptureRequest();} } }''',
    "android/hardware/camera2/CameraCaptureSession.java": '''package android.hardware.camera2;
import android.os.Handler;
public class CameraCaptureSession {
 public int repeats, closes;
 public void close(){closes++;}
 public void setRepeatingRequest(CaptureRequest r,Object cb,Handler h){repeats++;}
 public abstract static class StateCallback {
  public abstract void onConfigured(CameraCaptureSession s);
  public abstract void onConfigureFailed(CameraCaptureSession s);
 }
}''',
    "android/hardware/camera2/CameraDevice.java": '''package android.hardware.camera2;
import java.util.List; import android.view.Surface; import android.os.Handler;
public class CameraDevice {
 public static final int TEMPLATE_PREVIEW=1;
 public int closes, sessionCreates;
 public CameraCaptureSession session;
 private CameraCaptureSession.StateCallback sessionCallback; private Handler sessionHandler;
 public void close(){closes++;} // Intentionally does NOT deliver onClosed: test controls the barrier.
 public CaptureRequest.Builder createCaptureRequest(int template){return new CaptureRequest.Builder();}
 public void createCaptureSession(List<Surface> s,CameraCaptureSession.StateCallback cb,Handler h){
  sessionCreates++;session=new CameraCaptureSession();sessionCallback=cb;sessionHandler=h;
 }
 public void configured(){sessionHandler.post(new Runnable(){public void run(){sessionCallback.onConfigured(session);}});}
 public abstract static class StateCallback {
  public abstract void onOpened(CameraDevice d); public abstract void onDisconnected(CameraDevice d);
  public abstract void onError(CameraDevice d,int code); public void onClosed(CameraDevice d){}
 }
}''',
    "android/hardware/camera2/CameraManager.java": '''package android.hardware.camera2;
import java.util.ArrayList; import java.util.List; import android.os.Handler;
public class CameraManager {
 public int opens, mode=3; public boolean opened, ignoreWrites, ignorePreOpen=true;
 public final List<Integer> writes=new ArrayList<Integer>();
 public final List<Open> requests=new ArrayList<Open>();
 public Runnable onRead, onCharacteristics;
 public int readMode(){int value=mode;Runnable hook=onRead;onRead=null;if(hook!=null)hook.run();return value;}
 public String[] getCameraIdList(){return new String[]{"0","1"};}
 public CameraCharacteristics getCameraCharacteristics(String id){
  Runnable hook=onCharacteristics;onCharacteristics=null;if(hook!=null)hook.run();return new CameraCharacteristics();}
 public void openCamera(String id,CameraDevice.StateCallback cb,Handler h){
  opens++;requests.add(new Open(cb,h));
 }
 public class Open {
  public final CameraDevice device=new CameraDevice();
  final CameraDevice.StateCallback cb; final Handler handler;
  Open(CameraDevice.StateCallback c,Handler h){cb=c;handler=h;}
  public void opened(){handler.post(new Runnable(){public void run(){opened=true;cb.onOpened(device);}});}
  public void closed(){handler.post(new Runnable(){public void run(){opened=false;cb.onClosed(device);}});}
  public void disconnected(){handler.post(new Runnable(){public void run(){cb.onDisconnected(device);}});}
 }
}''',
    "android/media/Image.java": '''package android.media;
import java.nio.ByteBuffer;
public class Image {
 public int getWidth(){return 1280;} public int getHeight(){return 720;}
 public Plane[] getPlanes(){return new Plane[]{new Plane(),new Plane(),new Plane()};}
 public void close(){}
 public static class Plane { public ByteBuffer getBuffer(){return ByteBuffer.allocate(1280*720);}
  public int getRowStride(){return 1280;} public int getPixelStride(){return 1;} }
}''',
    "android/media/ImageReader.java": '''package android.media;
import android.os.Handler; import android.view.Surface;
public class ImageReader {
 public static int created,closed,reads; public static ImageReader latest;
 private OnImageAvailableListener listener; private Handler handler;
 public static ImageReader newInstance(int w,int h,int f,int max){created++;latest=new ImageReader();return latest;}
 public Surface getSurface(){return new Surface();}
 public void close(){closed++;}
 public void setOnImageAvailableListener(OnImageAvailableListener l,Handler h){listener=l;handler=h;}
 public Image acquireLatestImage(){reads++;return new Image();}
 public void frame(){handler.post(new Runnable(){public void run(){listener.onImageAvailable(ImageReader.this);}});}
 public interface OnImageAvailableListener { void onImageAvailable(ImageReader reader); }
}''',
    "com/xiaopeng/clusterassist/AvmMode.java": '''package com.xiaopeng.clusterassist;
import android.hardware.camera2.CameraManager;
final class AvmMode {
 static int current(CameraManager m){return m.readMode();}
 static boolean set(CameraManager m,int mode){m.writes.add(mode);
  if(!m.ignoreWrites&&(!m.ignorePreOpen||m.opened))m.mode=mode;
  return true;
 }
 static String lastError(){return "fake AVM mode";}
}''',
    "com/xiaopeng/clusterassist/MirrorPush.java": '''package com.xiaopeng.clusterassist;
public class MirrorPush implements MirrorFrameSink { public static final int W=640,H=360; public int submits;
 public void submit(byte[] b,int side){submits++;} }''',
    "com/xiaopeng/clusterassist/Prefs.java": '''package com.xiaopeng.clusterassist;
public class Prefs { public boolean quadCropEnable=true; public float quadZoomL=2,quadZoomR=2;
 public float quadPanXL,quadPanXR,quadPanYL,quadPanYR; }''',
}


def tool(name):
    home = os.environ.get("JAVA_HOME")
    candidate = Path(home) / "bin" / name if home else None
    if candidate is not None and candidate.is_file():
        return str(candidate)
    found = shutil.which(name)
    if not found:
        raise SystemExit("Missing JDK tool: " + name)
    return found


def main():
    with tempfile.TemporaryDirectory(prefix="p7-mirror-camera-host-") as temporary:
        temp = Path(temporary)
        fake_files = []
        for relative, content in SOURCES.items():
            path = temp / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            fake_files.append(str(path))
        output = temp / "classes"
        output.mkdir()
        real = ROOT / "carpad/src/com/xiaopeng/clusterassist"
        subprocess.run([
            tool("javac"), "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-Xlint:-options", "-d", str(output),
            *fake_files, str(real / "MirrorSource.java"), str(real / "MirrorAvmSession.java"),
            str(real / "MirrorFrameSink.java"),
            str(ROOT / "tests/mirror-camera/MirrorCameraLifecycleTest.java"),
        ], check=True)
        subprocess.run([
            tool("java"), "-ea", "-cp", str(output),
            "com.xiaopeng.clusterassist.MirrorCameraLifecycleTest",
        ], check=True)


if __name__ == "__main__":
    main()
