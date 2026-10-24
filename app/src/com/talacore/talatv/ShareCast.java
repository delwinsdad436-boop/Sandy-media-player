package com.talacore.talatv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;

import androidx.mediarouter.media.MediaControlIntent;
import androidx.mediarouter.media.MediaItemStatus;
import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;
import androidx.mediarouter.media.MediaSessionStatus;
import androidx.mediarouter.media.RemotePlaybackClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;

/**
 * Screen-share to a TV (v0.7.0) — the Android half of TvShare.
 *
 * Owns the three routes' machinery for the player:
 *  - the page-share server (ShareServer) and its token/URL,
 *  - the remote-playback fling (MediaRouter routes +
 *    RemotePlaybackClient — the protocol Android calls remote
 *    playback; TVs/receivers that speak it appear as routes),
 *  - the Miracast hand-off to the system's own Cast settings.
 *
 * Honesty rules, same as everywhere in Sandy: a route that
 * isn't there produces words, not a dead button; the fling
 * reports the receiver's own errors back as speech; the page
 * server says its address or says why it has none (no Wi-Fi).
 *
 * The MediaRouter library's AppCompat chooser dialog is NOT
 * used — Sandy lists routes itself, in its own sheet, so the
 * appcompat closure stays out of the build on purpose.
 */
public final class ShareCast {

    public interface Events {
        void onShareState(String spoken);
    }

    private static ShareCast instance;

    public static synchronized ShareCast get(Context ctx) {
        if (instance == null) instance = new ShareCast(ctx);
        return instance;
    }

    private final Context app;
    private MediaRouter router;
    private boolean discovering = false;

    private ShareServer server;
    private String token = "";
    private int serverPort = -1;
    private String serverPlanUrl = null;

    private RemotePlaybackClient client;
    private MediaRouter.RouteInfo activeRoute;
    private long pendingSeekMs = -1;
    private String activeItemId;

    private ShareCast(Context ctx) {
        app = ctx.getApplicationContext();
    }

    // ------------------------------------------------ discovery

    public void startDiscovery() {
        if (discovering) return;
        try {
            router = MediaRouter.getInstance(app);
            MediaRouteSelector selector = new MediaRouteSelector.Builder()
                    .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                    .build();
            router.addCallback(selector, new MediaRouter.Callback() {},
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
            discovering = true;
        } catch (Throwable t) {
            // Discovery must never take the player down with it —
            // an Error from the router stack stays a share problem.
            router = null;
        }
    }

    /** TVs/receivers that can take a flung stream right now. */
    public List<MediaRouter.RouteInfo> remoteRoutes() {
        List<MediaRouter.RouteInfo> out = new ArrayList<>();
        if (router == null) startDiscovery();
        if (router == null) return out;
        try {
            for (MediaRouter.RouteInfo r : router.getRoutes()) {
                if (r.isEnabled() && !r.isDefault()
                        && r.getPlaybackType()
                                == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE
                        && r.supportsControlCategory(
                                MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)) {
                    out.add(r);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    // ------------------------------------------------ page server

    public boolean pageLive() { return server != null && server.isRunning(); }

    public String watchUrl() {
        String ip = localIpv4();
        if (ip == null || serverPort < 0) return null;
        return TvShare.watchUrl(ip, serverPort, token);
    }

    public String mediaUrl() {
        String ip = localIpv4();
        if (ip == null || serverPort < 0) return null;
        return TvShare.mediaUrl(ip, serverPort, token);
    }

    /**
     * Start (or reuse) the page server for this plan. A fresh
     * token is minted whenever the shared item changes, so an
     * old link dies when the viewer moves on — household hygiene.
     */
    public void ensurePageServer(final TvShare.Plan plan, final String title)
            throws IOException {
        if (pageLive() && plan.url != null && plan.url.equals(serverPlanUrl)) {
            return;
        }
        stopPageServer();
        token = TvShare.newToken(new Random());
        ShareServer.MediaSource media = mediaSourceFor(plan);
        server = new ShareServer(token, media, new ShareServer.PageSource() {
            @Override public String title() { return title; }
            @Override public boolean playable() {
                return plan.kind != TvShare.Kind.PAGE_ONLY;
            }
        });
        serverPort = server.start();
        serverPlanUrl = plan.url;
    }

    public void stopPageServer() {
        if (server != null) server.stop();
        server = null;
        serverPort = -1;
        serverPlanUrl = null;
    }

    private ShareServer.MediaSource mediaSourceFor(final TvShare.Plan plan) {
        if (plan.kind == TvShare.Kind.REDIRECT) {
            return new ShareServer.MediaSource() {
                @Override public long length() { return -1; }
                @Override public String contentType() {
                    return TvShare.mimeFor(plan.url);
                }
                @Override public InputStream open(long start) throws IOException {
                    throw new IOException("redirect plan has no local bytes");
                }
                @Override public String redirectTo() { return plan.url; }
            };
        }
        if (plan.kind == TvShare.Kind.FILE
                && (plan.url.startsWith("file://") || plan.url.startsWith("/"))) {
            final File file = plan.url.startsWith("file://")
                    ? new File(Uri.parse(plan.url).getPath())
                    : new File(plan.url);
            return new ShareServer.MediaSource() {
                @Override public long length() {
                    return file.isFile() ? file.length() : -1;
                }
                @Override public String contentType() {
                    return TvShare.mimeFor(file.getName());
                }
                @Override public InputStream open(long start) throws IOException {
                    FileInputStream in = new FileInputStream(file);
                    long left = start;
                    while (left > 0) {
                        long skipped = in.skip(left);
                        if (skipped <= 0) break;
                        left -= skipped;
                    }
                    return in;
                }
                @Override public String redirectTo() { return null; }
            };
        }
        // content:// (or anything else streamable through the resolver)
        final Uri uri = Uri.parse(plan.url);
        final ContentResolver cr = app.getContentResolver();
        return new ShareServer.MediaSource() {
            @Override public long length() {
                try (android.content.res.AssetFileDescriptor afd =
                             cr.openAssetFileDescriptor(uri, "r")) {
                    return afd == null ? -1 : afd.getLength();
                } catch (Throwable e) {
                    return -1;
                }
            }
            @Override public String contentType() {
                String t = null;
                try { t = cr.getType(uri); } catch (Throwable ignored) {}
                return t == null ? TvShare.mimeFor(plan.url) : t;
            }
            @Override public InputStream open(long start) throws IOException {
                InputStream in = cr.openInputStream(uri);
                if (in == null) throw new IOException("the file wouldn't open");
                long left = start;
                while (left > 0) {
                    long skipped = in.skip(left);
                    if (skipped <= 0) break;
                    left -= skipped;
                }
                return in;
            }
            @Override public String redirectTo() { return null; }
        };
    }

    // ------------------------------------------------ fling

    public boolean castActive() { return client != null && activeRoute != null; }

    public String activeRouteName() {
        return activeRoute == null ? "" : activeRoute.getName();
    }

    public void fling(MediaRouter.RouteInfo route, String url, String mime,
                      String title, long positionMs, final Events events) {
        stopCast();
        try {
            if (router != null) router.selectRoute(route);
            client = new RemotePlaybackClient(app, route);
            if (!client.isRemotePlaybackSupported()) {
                client.release();
                client = null;
                events.onShareState(route.getName()
                        + " answered, but it doesn't take sent video.");
                return;
            }
            activeRoute = route;
            pendingSeekMs = positionMs > 5000 ? positionMs : -1;
            final RemotePlaybackClient c = client;
            c.setStatusCallback(new RemotePlaybackClient.StatusCallback() {
                @Override
                public void onItemStatusChanged(Bundle data, String sessionId,
                        MediaSessionStatus sessionStatus, String itemId,
                        MediaItemStatus itemStatus) {
                    if (pendingSeekMs > 0 && itemStatus != null
                            && itemStatus.getPlaybackState()
                                    == MediaItemStatus.PLAYBACK_STATE_PLAYING) {
                        long seek = pendingSeekMs;
                        pendingSeekMs = -1;
                        c.seek(itemId, seek, null,
                                new RemotePlaybackClient.ItemActionCallback() {});
                    }
                }
                @Override
                public void onSessionStatusChanged(Bundle data, String sessionId,
                                                   MediaSessionStatus sessionStatus) {}
            });
            c.play(Uri.parse(url), mime, null, 0, null,
                    new RemotePlaybackClient.ItemActionCallback() {
                        @Override
                        public void onResult(Bundle data, String sessionId,
                                MediaSessionStatus sessionStatus, String itemId,
                                MediaItemStatus itemStatus) {
                            activeItemId = itemId;
                            events.onShareState("Playing on "
                                    + route.getName()
                                    + " now — this phone is the remote.");
                        }
                        @Override
                        public void onError(String error, int code,
                                            Bundle extras) {
                            stopCast();
                            events.onShareState(route.getName()
                                    + " couldn't take it: "
                                    + (error == null ? "the receiver refused"
                                       : error));
                        }
                    });
        } catch (Throwable e) {
            stopCast();
            events.onShareState("Sending to " + route.getName()
                    + " failed: " + (e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    public void stopCast() {
        if (client != null) {
            try {
                client.stop(null, new RemotePlaybackClient.SessionActionCallback() {});
            } catch (Throwable ignored) {}
            try { client.release(); } catch (Throwable ignored) {}
            client = null;
        }
        activeRoute = null;
        activeItemId = null;
        pendingSeekMs = -1;
    }

    public void stopAll() {
        stopCast();
        stopPageServer();
    }

    public boolean anythingLive() { return castActive() || pageLive(); }

    // ------------------------------------------------ network facts

    /**
     * This phone's Wi-Fi address, or null when it has none (not
     * on Wi-Fi — the page share honestly can't work then).
     * Interface scan first (no permission needed), WifiManager
     * as the fallback.
     */
    public String localIpv4() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            String wifiHit = null;
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        String name = ni.getName().toLowerCase();
                        if (name.startsWith("wlan")) return ip;
                        if (wifiHit == null) wifiHit = ip;
                    }
                }
            }
            if (wifiHit != null) return wifiHit;
        } catch (Throwable ignored) {}
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                int v = wm.getConnectionInfo().getIpAddress();
                if (v != 0) return TvShare.formatIpv4(v);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ------------------------------------------------ miracast door

    /**
     * Walk the viewer to the phone's own screen-mirroring
     * settings (the app cannot mirror by itself — Miracast is
     * the system's). Returns false when neither settings door
     * exists on this phone, so the caller can say so in words.
     */
    public static boolean openCastSettings(Activity act) {
        String[] actions = {
                "android.settings.CAST_SETTINGS",
                "android.settings.WIFI_DISPLAY_SETTINGS",
        };
        for (String a : actions) {
            try {
                Intent i = new Intent(a);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
                return true;
            } catch (ActivityNotFoundException ignored) {}
        }
        return false;
    }
}
