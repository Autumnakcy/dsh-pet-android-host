package com.dshmobile.pethost;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * 一次性截屏：MediaProjection + VirtualDisplay + ImageReader，抓一帧存成 PNG。
 *
 * 为什么 App 要自己截屏：手机侧的调试工具受白名单限制（微信/系统界面都截不了），
 * 而「悬浮窗是不是被系统压了 alpha」这种事只有看真实屏幕像素才能定死。
 * 存到 /sdcard/dsh/pet-shot.png，容器里能直接读。
 */
public final class Shot {

    private static final String TAG = "PetHostShot";
    public static final String OUT = "/sdcard/dsh/pet-shot.png";

    private Shot() {
    }

    public static void request(Activity a, int reqCode) {
        if (Build.VERSION.SDK_INT < 21) return;
        MediaProjectionManager mpm = (MediaProjectionManager) a.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) return;
        a.startActivityForResult(mpm.createScreenCaptureIntent(), reqCode);
    }

    public interface Callback {
        void done(boolean ok, String detail);
    }

    public static void capture(final Activity a, final int resultCode, final Intent data, final Callback cb) {
        final MediaProjectionManager mpm =
                (MediaProjectionManager) a.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            cb.done(false, "no MediaProjectionManager");
            return;
        }
        final DisplayMetrics dm = a.getResources().getDisplayMetrics();
        final int w = dm.widthPixels;
        final int h = dm.heightPixels;
        final int dpi = dm.densityDpi;
        new Thread(() -> {
            MediaProjection mp = null;
            VirtualDisplay vd = null;
            ImageReader reader = null;
            try {
                mp = mpm.getMediaProjection(resultCode, data);
                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
                vd = mp.createVirtualDisplay("pet-shot", w, h, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null,
                        new Handler(Looper.getMainLooper()));
                Image img = null;
                for (int i = 0; i < 60 && img == null; i++) {
                    Thread.sleep(120);
                    img = reader.acquireLatestImage();
                }
                if (img == null) {
                    cb.done(false, "no frame");
                    return;
                }
                Bitmap bmp = toBitmap(img, w, h);
                img.close();
                File f = new File(OUT);
                File dir = f.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                FileOutputStream os = new FileOutputStream(f);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                os.close();
                cb.done(true, OUT + " " + bmp.getWidth() + "x" + bmp.getHeight());
            } catch (Throwable t) {
                Log.w(TAG, "capture failed", t);
                cb.done(false, String.valueOf(t));
            } finally {
                try {
                    if (vd != null) vd.release();
                } catch (Throwable ignore) {
                }
                try {
                    if (reader != null) reader.close();
                } catch (Throwable ignore) {
                }
                try {
                    if (mp != null) mp.stop();
                } catch (Throwable ignore) {
                }
            }
        }, "pet-shot").start();
    }

    private static Bitmap toBitmap(Image img, int w, int h) {
        Image.Plane[] planes = img.getPlanes();
        ByteBuffer buf = planes[0].getBuffer();
        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (pixelStride == 4 && rowStride == w * 4) {
            bmp.copyPixelsFromBuffer(buf);
            return bmp;
        }
        int[] row = new int[w];
        int offset = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = offset + x * pixelStride;
                int r = buf.get(p) & 0xFF;
                int g = buf.get(p + 1) & 0xFF;
                int b = buf.get(p + 2) & 0xFF;
                row[x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1);
            offset += rowStride;
        }
        return bmp;
    }
}
