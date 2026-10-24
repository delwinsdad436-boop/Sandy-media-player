package com.talacore.talatv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tolerant M3U/M3U8 playlist parser for live-TV channel lists.
 *
 * Handles the real-world mess found in public playlists:
 *  - #EXTINF:-1 tvg-id=".." tvg-logo=".." group-title=".." http-user-agent="..",Name
 *  - #EXTGRP:Group Name        (applies to the next channel)
 *  - #EXTVLCOPT:...            (player options; http-user-agent is captured, rest skipped)
 *  - #KODIPROP:...             (Kodi/DRM props; skipped - we cannot play DRM anyway)
 *  - bare # lines of any other kind are skipped
 * The parser never throws on a malformed entry: a bad entry is dropped and
 * parsing continues, because public playlists always contain some rot.
 */
public final class M3uParser {

    public static final class Channel {
        public String name = "";
        public String url = "";
        public String group = "";
        public String logo = "";
        public String tvgId = "";
        public String userAgent = "";
        public String source = "";   // playlist name this channel came from
        public String language = ""; // facet, joined from index.language.m3u (built-in only)
        public String country = "";  // facet, joined from index.country.m3u (built-in only)
        public String canonDef = "";  // v0.6.0: what the channel IS (database categories)
        public String canonName = ""; // v0.5.0: real station name from the iptv-org
                                      // channels database (ChannelNames join)
        public String canonLogo = ""; // v0.5.0: its logo, same source

        /**
         * The name a person should see: the database's canonical name
         * when the join found one, else the playlist name with the
         * feed junk stripped. The raw name stays in {@link #name} —
         * quality/source tokens in it are still load-bearing for
         * ranking and identity keys.
         */
        public String displayName() {
            if (canonName != null && !canonName.isEmpty()) return canonName;
            return ChannelNames.cleanName(name);
        }

        /** The logo a person should see: the database's, else the playlist's. */
        public String logoUrl() {
            if (canonLogo != null && !canonLogo.isEmpty()) return canonLogo;
            return logo == null ? "" : logo;
        }
    }

    private static final Pattern ATTR =
            Pattern.compile("([A-Za-z0-9_\\-]+)=\"([^\"]*)\"");

    public static List<Channel> parse(InputStream in, String sourceName) throws IOException {
        List<Channel> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        Channel pending = null;
        String pendingGroup = "";
        String vlcUserAgent = "";
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                pending = new Channel();
                pending.source = sourceName;
                // Format: #EXTINF:<duration> <attrs>,<name>  (attrs optional)
                int colon = line.indexOf(':');
                String rest = colon >= 0 ? line.substring(colon + 1).trim() : line;
                // skip the duration token (ends at first space or comma)
                int i = 0;
                while (i < rest.length()
                        && !Character.isWhitespace(rest.charAt(i))
                        && rest.charAt(i) != ',') i++;
                String afterDur = rest.substring(i);
                Matcher m = ATTR.matcher(afterDur);
                int lastAttrEnd = -1;
                while (m.find()) {
                    String k = m.group(1).toLowerCase();
                    String v = m.group(2);
                    switch (k) {
                        case "tvg-id": pending.tvgId = v; break;
                        case "tvg-logo": pending.logo = v; break;
                        case "group-title": pending.group = v; break;
                        case "http-user-agent": pending.userAgent = v; break;
                        default: break;
                    }
                    lastAttrEnd = m.end();
                }
                // The name starts after the first comma at/after the end of
                // the last attribute (attribute values may contain commas;
                // channel names may too - everything past the separator is
                // the name, verbatim).
                int nameStart;
                if (lastAttrEnd >= 0) {
                    int c = afterDur.indexOf(',', lastAttrEnd);
                    nameStart = c >= 0 ? c + 1 : afterDur.length();
                } else {
                    int c = afterDur.indexOf(',');
                    nameStart = c >= 0 ? c + 1 : 0;
                }
                pending.name = afterDur.substring(nameStart).trim();
                if (!pendingGroup.isEmpty() && pending.group.isEmpty()) {
                    pending.group = pendingGroup;
                }
                if (!vlcUserAgent.isEmpty() && pending.userAgent.isEmpty()) {
                    pending.userAgent = vlcUserAgent;
                }
                vlcUserAgent = "";
            } else if (line.startsWith("#EXTGRP:")) {
                pendingGroup = line.substring("#EXTGRP:".length()).trim();
            } else if (line.startsWith("#EXTVLCOPT:")) {
                String opt = line.substring("#EXTVLCOPT:".length()).trim();
                if (opt.toLowerCase().startsWith("http-user-agent=")) {
                    vlcUserAgent = opt.substring("http-user-agent=".length()).trim();
                }
                // all other VLC options are ignored
            } else if (line.startsWith("#")) {
                // #EXTM3U, #KODIPROP, #EXT-X-*, anything else: skip
                continue;
            } else {
                // a URL line
                if (pending != null) {
                    // EXTVLCOPT lines sit between EXTINF and the URL, so a
                    // pending user-agent lands here, not at EXTINF time
                    if (pending.userAgent.isEmpty() && !vlcUserAgent.isEmpty()) {
                        pending.userAgent = vlcUserAgent;
                    }
                    vlcUserAgent = "";
                    pending.url = line;
                    if (pending.name.isEmpty()) {
                        pending.name = !pending.tvgId.isEmpty() ? pending.tvgId : line;
                    }
                    if (pending.group.isEmpty()) pending.group = "Uncategorized";
                    out.add(pending);
                    pending = null;
                }
                // a URL with no preceding EXTINF is not a channel; drop it
            }
        }
        return out;
    }
}
