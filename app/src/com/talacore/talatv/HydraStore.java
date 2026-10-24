package com.talacore.talatv;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Hydra's learned doors, kept as a FILE on the memory hook
 * (files/memory/hydra_doors.tsv) — file in, file out, like
 * everything she keeps. One line per host: the manner (Hydra
 * head) that answered there last. Primed into Net at boot, and
 * Net's successes flow back here to be written down, so the
 * next visit to a host starts at the door that worked.
 */
public final class HydraStore {

    private HydraStore() {}

    private static File doorsFile(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "memory");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "hydra_doors.tsv");
    }

    public static Map<String, Integer> load(Context ctx) {
        try {
            File f = doorsFile(ctx);
            if (!f.isFile()) return new HashMap<>();
            return Hydra.parseDoors(new String(
                    Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /**
     * Forget a host's learned door in the FILE (v0.14.1): the
     * Active Pinger proved the remembered door terminally dead;
     * the preference must not survive into the next session.
     */
    public static void forget(Context ctx, final String host) {
        if (ctx == null || host == null) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            synchronized (HydraStore.class) {
                try {
                    Map<String, Integer> doors = load(app);
                    if (doors.remove(host) != null) {
                        Files.write(doorsFile(app).toPath(),
                                Hydra.doorsToTsv(doors)
                                        .getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Exception ignored) {}
            }
        }, "hydra-door-forget").start();
    }

    /** Prime Net's ladder memory + persist every new learning. */
    public static void prime(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Net.primeDoors(load(app), (host, manner) -> {
            // write-through on a thread of its own; the file is
            // small and the writes are rare (a door learned)
            new Thread(() -> {
                synchronized (HydraStore.class) {
                    try {
                        Map<String, Integer> doors = load(app);
                        doors.put(host, manner);
                        Files.write(doorsFile(app).toPath(),
                                Hydra.doorsToTsv(doors)
                                        .getBytes(StandardCharsets.UTF_8));
                    } catch (Exception ignored) {}
                }
            }, "hydra-door-write").start();
        });
    }
}
