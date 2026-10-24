package com.talacore.talatv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny hand-rolled poster cache (no new dependencies): memory LRU +
 * on-disk copy in the app cache dir + background fetch. An ImageView is
 * only ever handed the bitmap for the URL it is currently tagged with,
 * so a fast-scrolling grid can't paint the wrong poster.
 */
public final class ImageCache {

    private static final LruCache<String, Bitmap> MEM =
            new LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(3);

    private ImageCache() {}

    public static void load(Context ctx, String url, ImageView view) {
        if (url == null || url.isEmpty()) {
            view.setTag(null);
            view.setImageBitmap(null);
            return;
        }
        view.setTag(url);
        Bitmap hit = MEM.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageBitmap(null);
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            Bitmap bmp = null;
            File f = diskFile(app, url);
            if (f.exists()) {
                bmp = decode(f.getAbsolutePath());
            }
            if (bmp == null) {
                try {
                    byte[] bytes = Net.getBytes(url);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(bytes);
                    }
                    bmp = decode(f.getAbsolutePath());
                } catch (Exception ignored) {
                    // a missing poster is cosmetic; the title text carries on
                }
            }
            if (bmp != null) {
                MEM.put(url, bmp);
                final Bitmap done = bmp;
                view.post(() -> {
                    if (url.equals(view.getTag())) view.setImageBitmap(done);
                });
            }
        });
    }

    private static Bitmap decode(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 400) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, opts);
    }

    private static File diskFile(Context ctx, String url) {
        File dir = new File(ctx.getCacheDir(), "posters");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, md5(url) + ".img");
    }

    private static String md5(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] digest = d.digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : digest) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
