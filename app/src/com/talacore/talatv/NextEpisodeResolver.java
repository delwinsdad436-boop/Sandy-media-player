package com.talacore.talatv;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pre-resolving the next episode (v0.14.0) — while the current
 * episode plays, quietly find the NEXT one and get its best
 * working source ready, so one tap on "Next episode" PLAYS
 * instead of starting a hunt. The machinery is the source
 * picker's own: the show's names (its own name first, then the
 * alternates TVmaze lists), the loaded playlists, the phone's
 * own library, the Internet Archive's episode shelves; the pick
 * is AutoPlay's under the viewer's quality posture; the proof
 * is LinkHealth's patient probe — terminal answers are dead on
 * the spot, slow answers are retried (3 attempts, ~30 s cap)
 * and a source that merely didn't answer in time is NOT marked
 * dead and still counts as resolved (the player is the ground
 * truth, as on the source picker's own road).
 *
 * Blocking network work: call resolve() from a worker thread.
 * Callbacks arrive on that same thread.
 */
public final class NextEpisodeResolver {

    public interface Callback {
        /** The next episode exists (source may still be looking). */
        void onNextFound(TvMaze.Episode next);
        /** A working source for it stands ready. */
        void onResolved(TvMaze.Episode next, SourceFinder.Source source);
        /** No next episode, or none could be sourced. */
        void onNoNext(TvMaze.Episode next);
    }

    private NextEpisodeResolver() {}

    public static void resolve(Context ctx, int showId, String showName,
                               String showLanguage, int season, int number,
                               Callback cb) {
        TvMaze.Episode next = null;
        try {
            List<TvMaze.Episode> episodes = TvMaze.parseEpisodes(
                    Net.getString(TvMaze.episodesUrl(showId)));
            next = NextEpisode.next(episodes, season, number);
        } catch (Exception ignored) {}
        if (next == null) {
            cb.onNoNext(null);
            return;
        }
        cb.onNextFound(next);

        // gather candidates for the next episode
        List<SourceFinder.Source> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> names = new ArrayList<>();
        names.add(showName == null ? "" : showName);
        try {
            List<TvMaze.Aka> akas = TvMaze.parseAkas(
                    Net.getString(TvMaze.akasUrl(showId)));
            names = TitleNames.namesOf(TitleNames.buildSet(showName, akas));
        } catch (Exception ignored) {}
        try {
            List<M3uParser.Channel> channels = ChannelStore.loadCache(ctx);
            if (channels != null) {
                for (String nm : names) {
                    for (SourceFinder.Source s : SourceFinder.findEpisode(
                            channels, nm, next.season, next.number, next.name)) {
                        if (seen.add(s.url)) found.add(s);
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            List<M3uParser.Channel> lib = LibraryStore.loadChannels(ctx);
            for (String nm : names) {
                for (SourceFinder.Source s : SourceFinder.findEpisode(
                        lib, nm, next.season, next.number, next.name)) {
                    if (seen.add(s.url)) found.add(s);
                }
            }
        } catch (Exception ignored) {}
        try {
            List<IaClient.Item> pool = new ArrayList<>();
            Set<String> seenIds = new HashSet<>();
            if (next.name != null && !next.name.trim().isEmpty()) {
                for (IaClient.Item it : IaClient.parseSearch(Net.getString(
                        IaClient.episodeSearchUrl(showName, next.name)))) {
                    if (seenIds.add(it.identifier)) pool.add(it);
                }
            }
            List<IaClient.Item> ranked = IaClient.rankItems(
                    pool, showName, next.name, next.season, next.number, 0);
            if (ranked.isEmpty() || ranked.get(0).score < 50) {
                for (IaClient.Item it : IaClient.parseSearch(Net.getString(
                        IaClient.showScopedSearchUrl(showName)))) {
                    if (seenIds.add(it.identifier)) pool.add(it);
                }
                ranked = IaClient.rankItems(
                        pool, showName, next.name, next.season, next.number, 0);
            }
            int opened = 0;
            for (IaClient.Item item : ranked) {
                if (opened >= 3 || found.size() >= 25) break;
                opened++;
                try {
                    List<IaClient.FileEntry> files = IaClient.parseMetadata(
                            Net.getString(IaClient.metadataUrl(item.identifier)),
                            item.identifier);
                    boolean matched = IaClient.itemMatchesEpisode(
                            item, next.name, next.season, next.number);
                    IaClient.FileEntry firstSub = null;
                    for (IaClient.FileEntry f : files) {
                        if (f.subtitle && firstSub == null) firstSub = f;
                    }
                    for (IaClient.FileEntry v : IaClient.filterEpisodeFiles(
                            files, next.season, next.number, next.name, matched)) {
                        SourceFinder.Source s = SourceFinder.fromArchiveFile(
                                item, v, firstSub);
                        if (seen.add(s.url)) found.add(s);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        if (found.isEmpty()) {
            cb.onNoNext(next);
            return;
        }

        // posture pick, then prove it with the patient probe;
        // a proven-dead pick falls to the next candidate.
        AutoPlay.Posture posture =
                AutoPlay.parsePosture(ChannelStore.getQualityPosture(ctx));
        String facet = UiStrings.facetFor(ChannelStore.getUiLanguage(ctx));
        String marc = IaClient.iaLanguageCode(facet);
        int cap = PipeMeter.capTierFor(PipeMeter.measuredBps(ctx));
        List<SourceFinder.Source> pool = new ArrayList<>(found);
        for (int attempt = 0; attempt < 3 && !pool.isEmpty(); attempt++) {
            List<AutoPlay.Cand> cands = new ArrayList<>();
            for (SourceFinder.Source s : pool) {
                HealthStore.Health h = HealthStore.get(ctx, s.url);
                java.util.Map<String, Integer> health = new java.util.HashMap<>();
                health.put(s.url, h == null ? AutoPlay.HEALTH_UNKNOWN : h.tier);
                cands.add(AutoPlay.candidates(
                        java.util.Collections.singletonList(s), health,
                        facet, marc).get(0));
            }
            AutoPlay.Pick pick = AutoPlay.pick(cands, posture, cap);
            if (pick == null || pick.source == null) break;
            SourceFinder.Source chosen = null;
            for (SourceFinder.Source s : pool) {
                if (s.url.equals(pick.source.url)) { chosen = s; break; }
            }
            if (chosen == null) break;
            if (!LinkHealth.isRemote(chosen.url)) {
                cb.onResolved(next, chosen);
                return;
            }
            // Hydra proves the source (v0.14.0): the published
            // manner failing advances the ladder to another door
            // instead of ending the resolution — and when a
            // different door answers, THAT door is what plays.
            // v0.18.0: the next-episode TILE is a presentation
            // promise, so the proof is the player's own ladder
            // walked exhaustively, and only a segment-tier proof
            // (or a slow answer — SG-1, it may well play) earns
            // the tile. A connects-only candidate is listed-grade:
            // it leaves the pool and the next candidate is tried,
            // exactly like a dead one but with no death spoken.
            HydraResolver.Outcome o = HydraResolver.proveExhaustive(
                    ctx, chosen.url, chosen.userAgent, null);
            if (o.result != null
                    && (o.result.tier == LinkHealth.TIER_PLAYS
                            || o.result.slow)) {
                if (!o.url.equals(chosen.url)) {
                    SourceFinder.Source via = new SourceFinder.Source();
                    via.kind = chosen.kind;
                    via.label = chosen.label;
                    via.detail = chosen.detail;
                    via.langFacet = chosen.langFacet;
                    via.langMarc = chosen.langMarc;
                    via.url = o.url;
                    via.userAgent = chosen.userAgent;
                    via.qualityTier = chosen.qualityTier;
                    via.subUrl = chosen.subUrl;
                    via.subMime = chosen.subMime;
                    via.subLang = chosen.subLang;
                    chosen = via;
                }
                cb.onResolved(next, chosen);
                return;
            }
            // proven dead: mark it (HealthStore.record did) and
            // take it out of the pool — the next candidate's turn.
            pool.remove(chosen);
        }
        cb.onNoNext(next);
    }
}
