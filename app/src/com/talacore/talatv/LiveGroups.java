package com.talacore.talatv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Live TV subdivision (v0.12.1, James's field order): channels
 * grouped by WHAT THEY ARE, on two axes, both derived from the
 * data Sandy already carries — never from invented buckets.
 *
 * SERVICE — the service a channel belongs to (Pluto TV, Plex,
 * Roku…). Evidence, in order: (a) the channel's guide file lives
 * in a service tree on the guide host (i.mjh.nz/PlutoTV/… — the
 * guide map's file URL names the service); (b) the channel's own
 * logo is served from the service's host (images.pluto.tv serves
 * Pluto's logos). A channel with neither is simply not under a
 * service tile — the flat list and the section axis still hold it.
 *
 * SECTION — what kind of thing it is (News, Movies, Sports, Kids,
 * Music…): the iptv-org database's own categories for the channel
 * (ChannelNames canonDef, e.g. "Movies · Series"), falling back to
 * the playlist group when the database lists none — the same
 * honesty rule as ChannelNames.definitionFor.
 *
 * Pure logic (the guide bridge arrives as a plain map) so the JVM
 * harness pins the grouping against the real shipped assets.
 */
public final class LiveGroups {

    private LiveGroups() {}

    // ------------------------------------------------------- service

    /** Service display name for a guide file URL, "" when the file
     *  is a country/generic tree rather than a service's. */
    public static String serviceNameForFile(String fileUrl) {
        if (fileUrl == null) return "";
        String seg = firstPathSegment(fileUrl);
        switch (seg) {
            case "PlutoTV": return "Pluto TV";
            case "Plex": return "Plex";
            case "SamsungTVPlus": return "Samsung TV Plus";
            case "Roku": return "Roku";
            case "SkyGo": return "Sky Go";
            case "Foxtel": return "Foxtel";
            case "MeTV": return "MeTV";
            default: return "";
        }
    }

    /** base tvg-id → service name, from guide map rows (pure). */
    public static Map<String, String> serviceByBaseId(
            List<GuideStore.MapRow> rows) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rows == null) return out;
        for (GuideStore.MapRow r : rows) {
            String svc = serviceNameForFile(r.fileUrl);
            if (!svc.isEmpty() && r.tvgId != null && !r.tvgId.isEmpty()) {
                out.putIfAbsent(r.tvgId, svc);
            }
        }
        return out;
    }

    /** The service a channel belongs to, or "" — evidence only. */
    public static String serviceOf(M3uParser.Channel c,
                                   Map<String, String> serviceByBaseId) {
        if (c == null) return "";
        if (serviceByBaseId != null) {
            String svc = serviceByBaseId.get(GuideStore.baseId(c.tvgId));
            if (svc != null && !svc.isEmpty()) return svc;
        }
        String host = hostOf(c.logoUrl());
        if (host.endsWith("pluto.tv")) return "Pluto TV";
        return "";
    }

    // ------------------------------------------------------- section

    /**
     * The sections a channel sits in: its database categories
     * (canonDef split on "·", at most two — the definition rule),
     * else its playlist group as a single section when the group
     * says something real. Empty when neither knows — the channel
     * then lives only in the flat list, honestly unsectioned.
     */
    public static List<String> sectionsOf(M3uParser.Channel c) {
        List<String> out = new ArrayList<>();
        if (c == null) return out;
        String def = c.canonDef == null ? "" : c.canonDef.trim();
        if (!def.isEmpty()) {
            for (String part : def.split("·")) {
                String s = part.trim();
                if (!s.isEmpty() && !"Undefined".equalsIgnoreCase(s)
                        && !out.contains(s)) out.add(s);
                if (out.size() >= 2) break;
            }
            return out;
        }
        String g = c.group == null ? "" : c.group.trim();
        if (!g.isEmpty() && !"Undefined".equalsIgnoreCase(g)) out.add(g);
        return out;
    }

    // ------------------------------------------------------ grouping

    /** service name → its channels (count desc, then A–Z). */
    public static Map<String, List<M3uParser.Channel>> groupByService(
            List<M3uParser.Channel> channels,
            Map<String, String> serviceByBaseId) {
        Map<String, List<M3uParser.Channel>> map = new LinkedHashMap<>();
        if (channels != null) {
            for (M3uParser.Channel c : channels) {
                String svc = serviceOf(c, serviceByBaseId);
                if (svc.isEmpty()) continue;
                map.computeIfAbsent(svc, k -> new ArrayList<>()).add(c);
            }
        }
        return sortedByCount(map);
    }

    /** section name → its channels (count desc, then A–Z). A channel
     *  with two categories stands in both of its sections. */
    public static Map<String, List<M3uParser.Channel>> groupBySection(
            List<M3uParser.Channel> channels) {
        Map<String, List<M3uParser.Channel>> map = new LinkedHashMap<>();
        if (channels != null) {
            for (M3uParser.Channel c : channels) {
                for (String s : sectionsOf(c)) {
                    map.computeIfAbsent(s, k -> new ArrayList<>()).add(c);
                }
            }
        }
        return sortedByCount(map);
    }

    private static Map<String, List<M3uParser.Channel>> sortedByCount(
            Map<String, List<M3uParser.Channel>> map) {
        List<Map.Entry<String, List<M3uParser.Channel>>> e =
                new ArrayList<>(map.entrySet());
        e.sort((a, b) -> {
            int d = b.getValue().size() - a.getValue().size();
            return d != 0 ? d : a.getKey().compareToIgnoreCase(b.getKey());
        });
        Map<String, List<M3uParser.Channel>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<M3uParser.Channel>> en : e) {
            out.put(en.getKey(), en.getValue());
        }
        return out;
    }

    // -------------------------------------------------------- helpers

    /** Host of a URL (lowercase, no port), "" when unparseable. */
    public static String hostOf(String url) {
        if (url == null) return "";
        String s = url.trim().toLowerCase(Locale.US);
        int p = s.indexOf("://");
        if (p >= 0) s = s.substring(p + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s;
    }

    private static String firstPathSegment(String url) {
        if (url == null) return "";
        String s = url.trim();
        int p = s.indexOf("://");
        if (p >= 0) s = s.substring(p + 3);
        int slash = s.indexOf('/');
        if (slash < 0) return "";
        s = s.substring(slash + 1);
        int next = s.indexOf('/');
        return next < 0 ? s : s.substring(0, next);
    }
}
