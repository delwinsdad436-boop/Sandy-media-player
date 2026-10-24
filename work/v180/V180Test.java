import com.talacore.talatv.BookChunker;
import com.talacore.talatv.BookSource;
import com.talacore.talatv.ReaderBook;
import com.talacore.talatv.SeriesBook;
import com.talacore.talatv.ReaderRoomActivity;
import com.talacore.talatv.CardState;
import com.talacore.talatv.DisplayCounts;
import com.talacore.talatv.FailureWords;
import com.talacore.talatv.GateQuestions;
import com.talacore.talatv.Hydra;
import com.talacore.talatv.HydraResolver;
import com.talacore.talatv.PlaybackContinuity;
import com.talacore.talatv.PlaybackRecord;
import com.talacore.talatv.LiveSegmentKeys;
import com.talacore.talatv.ActivePinger;
import com.talacore.talatv.TimeshiftMath;
import com.talacore.talatv.NextEpisode;
import com.talacore.talatv.StoryCast;
import com.talacore.talatv.StoryData;
import com.talacore.talatv.StoryPanels;
import com.talacore.talatv.BookLang;
import com.talacore.talatv.BookText;
import com.talacore.talatv.PdfText;
import com.talacore.talatv.EpisodeMatch;
import com.talacore.talatv.VerifyPriority;
import com.talacore.talatv.BookTranslator;
import com.talacore.talatv.ChannelNames;
import com.talacore.talatv.CookedSubs;
import com.talacore.talatv.CrashGuard;
import com.talacore.talatv.M3uParser;
import com.talacore.talatv.MtLoopGuard;
import com.talacore.talatv.ReadingSession;
import com.talacore.talatv.Cooker;
import com.talacore.talatv.EnginePack;
import com.talacore.talatv.HallucinationGuard;
import com.talacore.talatv.RecordingExport;
import com.talacore.talatv.ShareServer;
import com.talacore.talatv.TranslateGate;
import com.talacore.talatv.TvShare;
import com.talacore.talatv.UiStrings;
import com.talacore.talatv.Sanctuary;
import com.talacore.talatv.ChannelScan;
import com.talacore.talatv.CommonsClient;
import com.talacore.talatv.NfajClient;
import com.talacore.talatv.SourceReceipts;
import com.talacore.talatv.ScanEngine;
import com.talacore.talatv.UnitMerge;
import com.talacore.talatv.LibraryIndex;
import com.talacore.talatv.LocalFiles;
import com.talacore.talatv.CatalogIndex;
import com.talacore.talatv.Availability;
import com.talacore.talatv.TitleNames;
import com.talacore.talatv.TvMaze;
import com.talacore.talatv.IaClient;
import com.talacore.talatv.LangRegistry;
import com.talacore.talatv.GuideModel;
import com.talacore.talatv.XmltvParser;
import com.talacore.talatv.TimerModel;
import com.talacore.talatv.TimerFrame;
import com.talacore.talatv.LiveGroups;
import com.talacore.talatv.AutoPlay;
import com.talacore.talatv.EstimateMath;
import com.talacore.talatv.PipeMeter;
import com.talacore.talatv.GuideStore;
import com.talacore.talatv.LinkHealth;
import com.talacore.talatv.SourceFinder;
import com.talacore.talatv.ChannelNames;
import com.talacore.talatv.SubHarvest;
import com.talacore.talatv.DubGate;
import com.talacore.talatv.VoiceBook;
import com.talacore.talatv.DubShelf;
import com.talacore.talatv.DiarPack;
import com.talacore.talatv.DiarCore;
import com.talacore.talatv.DiarOnnx;
import com.talacore.talatv.VoiceLangs;
import com.talacore.talatv.VoiceResolve;
import com.talacore.talatv.SpeakQueue;
import com.talacore.talatv.QuickPhrases;
import com.talacore.talatv.ListenRecord;
import com.talacore.talatv.TranslatorCore;
import com.talacore.talatv.DoorCoverage;
import com.talacore.talatv.VoicePack;
import com.talacore.talatv.OnnxVoiceSynth;
import com.talacore.talatv.CreditActivity;
import com.talacore.talatv.WavPcm;
import com.talacore.talatv.MemoryFiles;
import com.talacore.talatv.LinkHealth;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * v0.10.1 harness: everything v0.10.0 pinned (below, with the one
 * version pin moved: the 0.10.1 crash stamp; the key count stays
 * 79) PLUS the Farsi suite:
 *   - UiStrings: the 13th table (fa) complete against the same 79
 *     keys, positional spot lookups, and the not-English sweep --
 *     every fa value differs from its en twin (the only Latin
 *     script inside fa strings is the proper nouns the other
 *     tables also keep: Sandy, Tala Ripper, Brain ▸ Programs).
 *   - The catalogue door: fa opens "Persian"; the bucket file is
 *     lang_persian.json; bucketing + shelves proven over the REAL
 *     captured Persian records (work/v101/fixtures/
 *     shows_persian.json -- every show TVmaze tags Persian,
 *     counted live by research_persian.py).
 *   - IaClient: Persian maps to fas (measured live: fas 74,338 /
 *     per 2,557 / fa 0 -- the Archive files Persian under its -T
 *     code), and the language clause rides the search URL.
 *   - LangPack: a complete fa pack written from the baked table
 *     round-trips through the fail-closed gate.
 *
 * v0.10.0 harness: everything v0.9.0 pinned (below, with the two
 * version pins moved: 79 string keys, the 0.10.0 crash stamp, two
 * new entry classes) PLUS the on-demand expansion suites:
 *   - CatalogIndex: language bucketing + country shelves with
 *     variant separation (Spain vs Mexico stay two shelves of
 *     Spanish), home-country-first order, wall sort, bucket/state
 *     JSON round-trips — and the same code run over a REAL captured
 *     TVmaze index page (page 200: 234 shows, 8+ languages).
 *   - TvMaze: country parsing (network, then webChannel, then
 *     honestly empty) + the AKA record parse on two REAL captured
 *     /akas answers (La Casa de Papel, Attack on Titan).
 *   - TitleNames: original-first set building, normalized dedupe,
 *     the cap, and the Archive fallback name (Money Heist).
 *   - Availability: marker-prefix keys, cleaned-title keys with and
 *     without the year, and the false-positive guards.
 *   - IaClient: the MARC language map, language-clause URLs,
 *     language parsing (string + array forms), and the stable
 *     language partition.
 *
 * v0.9.0 harness: everything v0.7.0 pinned (regression — gate
 * table carried forward with the v0.7.0 recording rows, pack
 * v2, guard/merge, Cooker FSM with a bound engine, E2E record
 * shape) PLUS the new work:
 *   - TvShare plans / URLs / ranges / the watching page
 *   - ShareServer ON THE WIRE lives in wire_check.py (a
 *     Python client against WireServerMain): this sandbox
 *     banners Java-process TCP connects with a policy notice,
 *     so the Java harness cannot hold the client end. The
 *     server itself is plain java.net and wire-proven there.
 *   - RecordingExport's pure core (playlist walking, master/
 *     variant, encryption refusal, span concatenation)
 *   - the cooked-record shape a recordings cook must produce
 *   - UiStrings completeness incl. the new share key
 * The QR encoder is verified separately (QrDump + ZXing).
 * v0.8.0 additions: the read-aloud core (BookText spine order on a
 * fixture EPUB, TXT charset honesty, PDF refusal; BookChunker
 * boundaries + bookmark math; BookLang gates; the ReadingSession
 * FSM; MtLoopGuard loop verdicts; BookTranslator block caps), the
 * mandated search pin (the real name-map pipeline + matchesQuery
 * matrix over a fixture directory — the check that would have
 * caught a dead search), and the cold-start pin (CrashGuard's
 * crash-file format + entry-class loading + the share stack no
 * longer referenced by the player).
 */
public class V180Test {
    static int checks = 0, failures = 0;

    static void check(String name, boolean cond) {
        checks++;
        if (!cond) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }

    static void checkEq(String name, Object want, Object got) {
        checks++;
        if (!String.valueOf(want).equals(String.valueOf(got))) {
            failures++;
            System.out.println("FAIL: " + name + " want=" + want + " got=" + got);
        }
    }

    // ------------------------------------------------------------ CI mode
    //
    // GitHub Actions runs this same harness with -Dsandy.ci=true.
    // The heavy binary packs (the engine pack, the diarization
    // pack, the 52 voice packs) and a few bulky fixtures
    // deliberately do NOT travel with the repo. In CI mode ONLY, a
    // group whose external fixture is absent prints a loud named
    // SKIP and returns — no crash, and absence is never recorded
    // as a failure — and main prints the tally as
    // "CI SKIPS: n group(s): names" next to the summary.
    // Without the property the harness is exactly as strict as it
    // has always been: a missing pack still crashes/fails, by
    // design. See ci/HARNESS-CI-AUDIT.md for the fixture census.
    static final boolean CI = Boolean.getBoolean("sandy.ci");
    static final List<String> CI_SKIPS = new ArrayList<>();

    /** CI only: if any of the given files is absent, record a
     *  named skip for the group and return true (caller returns).
     *  Always false when the sandy.ci property is absent. */
    static boolean ciSkipIfMissing(String group, File... files) {
        if (!CI) return false;
        StringBuilder missing = new StringBuilder();
        for (File f : files) {
            if (!f.exists()) {
                if (missing.length() > 0) missing.append(", ");
                missing.append(f.getPath());
            }
        }
        if (missing.length() == 0) return false;
        if (!CI_SKIPS.contains(group)) CI_SKIPS.add(group);
        System.out.println("SKIP " + group + ": missing " + missing);
        return true;
    }

    // ------------------------------------------------------------ v0.15.0 two doors

    static VoiceLangs.Row vrow(String tv, String pack, String iso) {
        VoiceLangs.Row r = new VoiceLangs.Row();
        r.tvName = tv;
        r.packCode = pack;
        r.iso1 = iso;
        r.kind = pack.isEmpty() ? "none" : "native";
        return r;
    }

    static java.util.Set<String> setOf(String... xs) {
        return new java.util.HashSet<>(Arrays.asList(xs));
    }

    /** SPEAK: the queue walks the voice ladder and refuses plain. */
    static void speakQueueSuite() {
        VoiceLangs.Row ja = vrow("Japanese", "jpn", "ja");
        VoiceLangs.Row fr = vrow("French", "fra", "fr");
        VoiceLangs.Row xx = vrow("Nowhere", "", "xx");
        checkEq("speak ladder pack wins",
                VoiceResolve.Voice.PACK,
                SpeakQueue.decide(ja, setOf("jpn"), setOf("ja")).voice);
        checkEq("speak ladder pack code carried", "jpn",
                SpeakQueue.decide(ja, setOf("jpn"), setOf()).packCode);
        checkEq("speak ladder system next",
                VoiceResolve.Voice.SYSTEM,
                SpeakQueue.decide(fr, setOf(), setOf("fr")).voice);
        checkEq("speak ladder none is a refusal",
                VoiceResolve.Voice.NONE,
                SpeakQueue.decide(xx, setOf(), setOf()).voice);
        checkEq("speak ladder no-pack no-system is none",
                VoiceResolve.Voice.NONE,
                SpeakQueue.decide(ja, setOf(), setOf()).voice);
        check("speak canSpeak false on none",
                !SpeakQueue.canSpeak(xx, setOf(), setOf()));
        check("speak canSpeak true on system",
                SpeakQueue.canSpeak(fr, setOf(), setOf("fr")));

        SpeakQueue q = new SpeakQueue();
        q.enqueue("   ", "en");
        check("speak blank never queued", q.isEmpty());
        q.enqueue("Hello there.", "en");
        checkEq("speak short is one item", 1, q.size());
        checkEq("speak peek is the words", "Hello there.", q.peek().text);
        checkEq("speak peek carries the language", "en", q.peek().iso1);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longText.append("This is sentence number ").append(i)
                    .append(" of a long message that must be cut. ");
        }
        q.enqueue(longText.toString(), "ja");
        check("speak long text cut into blocks", q.size() > 2);
        boolean allJa = true;
        for (SpeakQueue.Item it : q.pending()) {
            if (it.text.startsWith("This is sentence") && !"ja".equals(it.iso1)) {
                allJa = false;
            }
        }
        check("speak blocks keep their language", allJa);
        SpeakQueue.Item first = q.advance();
        checkEq("speak advance returns the head", "Hello there.", first.text);
        checkEq("speak receipt is the last spoken", "Hello there.",
                q.lastSpoken().text);
        check("speak repeat requeues the receipt", q.repeatLast());
        checkEq("speak repeat stands at the head", "Hello there.",
                q.peek().text);
        SpeakQueue fresh = new SpeakQueue();
        check("speak repeat with no history refuses", !fresh.repeatLast());
        q.clear();
        check("speak clear drains", q.isEmpty() && q.peek() == null);
    }

    /** Quick phrases: the file round-trips whole. */
    static void quickPhrasesSuite() {
        List<String> in = new ArrayList<>(Arrays.asList(
                "Yes", "I need help", "Tab\there", "Line one\nLine two",
                "Back\\slash"));
        String body = QuickPhrases.serialize(in);
        List<String> out = QuickPhrases.parse(body);
        checkEq("phrases round-trip whole", in, out);
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many = QuickPhrases.add(many, "phrase " + i);
        checkEq("phrases capped at the max", QuickPhrases.MAX, many.size());
        checkEq("phrases cap drops the oldest", "phrase 16", many.get(0));
        List<String> dd = QuickPhrases.add(new ArrayList<>(), "Yes");
        dd = QuickPhrases.add(dd, "Yes");
        checkEq("phrases dedupe", 1, dd.size());
        dd = QuickPhrases.remove(dd, "Yes");
        check("phrases remove works", dd.isEmpty());
        List<String> parsed = QuickPhrases.parse("# a comment\n\nYes\nYes\n");
        checkEq("phrases parse skips comments and dupes", 1, parsed.size());
    }

    /** The document road: BookText intake routes into the queue;
     *  scanned and unsupported are named, never faked. */
    static void docSpeakSuite() throws Exception {
        BookText.Result txt = BookText.extract(
                "Chapter one. The rain came down all morning.".getBytes("UTF-8"),
                "note.txt");
        check("doc txt extracts", txt.ok);
        SpeakQueue q = new SpeakQueue();
        q.enqueueDocument(txt.text, "en");
        check("doc text reaches the speak queue", q.size() >= 1);
        check("doc words are the file's words",
                q.peek().text.contains("rain came down"));

        BookText.Result scan = BookText.extract(
                "%PDF-1.4 scanned pages".getBytes("UTF-8"), "scan.pdf");
        check("doc scanned pdf refused", !scan.ok);
        checkEq("doc scanned refusal named", BookText.SCANNED_NO_TEXT,
                scan.failReason);
        checkEq("doc scanned wording is the plain line",
                "This book is scanned pictures — it has no text layer Sandy can read yet.",
                UiStrings.lookup("en", "book_scanned"));

        BookText.Result weird = BookText.extract(
                new byte[]{1, 2, 3, 4, 5}, "thing.xyz");
        check("doc unsupported refused", !weird.ok);
        checkEq("doc unsupported named", BookText.UNSUPPORTED_KIND,
                weird.failReason);
        checkEq("doc unsupported wording is the plain line",
                "Sandy can't read this kind of file yet.",
                UiStrings.lookup("en", "book_unsupported"));
    }

    /** LISTEN: states from errors, and the record's files together. */
    static void listenRecordSuite() {
        checkEq("ear no-match is unsure", ListenRecord.State.UNSURE,
                ListenRecord.stateForError(7));
        checkEq("ear speech timeout is unsure", ListenRecord.State.UNSURE,
                ListenRecord.stateForError(6));
        checkEq("ear language unsupported is no recognizer",
                ListenRecord.State.NO_RECOGNIZER, ListenRecord.stateForError(12));
        checkEq("ear language unavailable is no recognizer",
                ListenRecord.State.NO_RECOGNIZER, ListenRecord.stateForError(13));
        checkEq("ear network failure is no recognizer",
                ListenRecord.State.NO_RECOGNIZER, ListenRecord.stateForError(2));
        checkEq("ear permission failure is no recognizer",
                ListenRecord.State.NO_RECOGNIZER, ListenRecord.stateForError(9));

        ListenRecord.Record r = new ListenRecord.Record();
        r.id = "20261019-143022";
        r.iso1 = "ja";
        r.state = ListenRecord.State.HEARD;
        r.source = ListenRecord.Source.SANDY_WHISPER;
        r.text = "こんにちは、元気ですか";
        r.audioName = r.audioFileName();
        r.createdMs = 1760000000000L;
        checkEq("record transcript name pairs with the id",
                "listen_20261019-143022.txt", r.transcriptName());
        checkEq("record audio name pairs with the id",
                "listen_20261019-143022.wav", r.audioName);
        ListenRecord.Record back =
                ListenRecord.parseTranscript(ListenRecord.transcriptBody(r));
        checkEq("record round-trip id", r.id, back.id);
        checkEq("record round-trip language", "ja", back.iso1);
        checkEq("record round-trip state", r.state, back.state);
        checkEq("record round-trip source", r.source, back.source);
        checkEq("record round-trip words", r.text, back.text);
        checkEq("record round-trip audio", r.audioName, back.audioName);
        List<ListenRecord.Record> idx = ListenRecord.parseIndex(
                "# header\n" + ListenRecord.indexLine(r));
        checkEq("record index holds the record", 1, idx.size());
        checkEq("record index round-trip source",
                ListenRecord.Source.SANDY_WHISPER, idx.get(0).source);
        checkEq("record index round-trip when", 1760000000000L,
                idx.get(0).createdMs);

        byte[] wav = ListenRecord.toWav(new short[]{0, 1000, -1000, 32767},
                16000);
        checkEq("wav size is header plus samples", 44 + 8, wav.length);
        check("wav riff magic",
                wav[0] == 'R' && wav[1] == 'I' && wav[2] == 'F' && wav[3] == 'F');
        check("wav wave magic", wav[8] == 'W' && wav[9] == 'A');
        int rate = (wav[24] & 0xff) | ((wav[25] & 0xff) << 8)
                | ((wav[26] & 0xff) << 16) | ((wav[27] & 0xff) << 24);
        checkEq("wav sample rate field", 16000, rate);
        int dataSize = (wav[40] & 0xff) | ((wav[41] & 0xff) << 8)
                | ((wav[42] & 0xff) << 16) | ((wav[43] & 0xff) << 24);
        checkEq("wav data size field", 8, dataSize);
        int s1 = (short) ((wav[46] & 0xff) | ((wav[47] & 0xff) << 8));
        checkEq("wav sample bytes land in order", 1000, s1);
    }

    /** THE TRANSLATOR: routes, alternation, co-display data,
     *  refusal before capture, the transcript file. */
    static void translatorSuite() {
        java.util.Set<String> pairs = setOf("ja-en", "en-ja", "en-fr", "fr-en");
        checkEq("tr direct route", TranslatorCore.RouteKind.DIRECT,
                TranslatorCore.resolveRoute("ja", "en", pairs).kind);
        checkEq("tr direct pair named", "ja-en",
                TranslatorCore.resolveRoute("ja", "en", pairs).pair);
        TranslatorCore.Route pivot =
                TranslatorCore.resolveRoute("ja", "fr", pairs);
        checkEq("tr pivot route", TranslatorCore.RouteKind.PIVOT, pivot.kind);
        checkEq("tr pivot leg in", "ja-en", pivot.legIn);
        checkEq("tr pivot leg out", "en-fr", pivot.legOut);
        TranslatorCore.Route none =
                TranslatorCore.resolveRoute("ja", "de", pairs);
        checkEq("tr no path is none", TranslatorCore.RouteKind.NONE, none.kind);
        checkEq("tr refusal names the missing direction", "ja-de",
                none.missingPair);
        check("tr none route is not ok", !none.ok());
        checkEq("tr same language is identity",
                TranslatorCore.RouteKind.IDENTITY,
                TranslatorCore.resolveRoute("en", "en", pairs).kind);
        checkEq("tr empty side is none", TranslatorCore.RouteKind.NONE,
                TranslatorCore.resolveRoute("", "en", pairs).kind);

        check("tr text mode never speaks",
                !TranslatorCore.speaks(TranslatorCore.OutMode.TEXT));
        check("tr voice mode speaks",
                TranslatorCore.speaks(TranslatorCore.OutMode.VOICE));
        check("tr both mode speaks",
                TranslatorCore.speaks(TranslatorCore.OutMode.BOTH));
        check("tr text always shows (verification rule)",
                TranslatorCore.showsText(TranslatorCore.OutMode.TEXT)
                        && TranslatorCore.showsText(TranslatorCore.OutMode.VOICE)
                        && TranslatorCore.showsText(TranslatorCore.OutMode.BOTH));

        TranslatorCore.Conversation c = new TranslatorCore.Conversation();
        c.sideA = "en";
        c.sideB = "ja";
        checkEq("tr first turn is side A", 'A', c.nextSide());
        checkEq("tr A speaks its own language", "en", c.fromIso());
        checkEq("tr A renders into B's language", "ja", c.toIso());
        TranslatorCore.Route direct =
                TranslatorCore.resolveRoute("en", "ja", pairs);
        TranslatorCore.Turn t1 = c.addTurn("Hello, how are you?",
                "こんにちは、元気ですか", TranslatorCore.OutMode.BOTH, direct);
        check("tr turn recorded", t1 != null);
        checkEq("tr heard and rendered travel together (heard)",
                "Hello, how are you?", c.turns.get(0).heard);
        checkEq("tr heard and rendered travel together (rendered)",
                "こんにちは、元気ですか", c.turns.get(0).rendered);
        checkEq("tr floor passes to B", 'B', c.nextSide());
        checkEq("tr B renders back into A", "en", c.toIso());
        TranslatorCore.Route back =
                TranslatorCore.resolveRoute("ja", "en", pairs);
        c.addTurn("元気です、ありがとう", "I am well, thank you",
                TranslatorCore.OutMode.TEXT, back);
        checkEq("tr floor returns to A", 'A', c.nextSide());
        checkEq("tr two turns kept", 2, c.turns.size());
        // refusal BEFORE capture: a NONE route records nothing
        TranslatorCore.Turn refused = c.addTurn("Hallo", "...",
                TranslatorCore.OutMode.BOTH,
                TranslatorCore.resolveRoute("de", "en", pairs));
        check("tr NONE-route turn refused", refused == null);
        checkEq("tr refusal leaves the floor where it stood", 'A', c.nextSide());
        checkEq("tr refusal adds no turn", 2, c.turns.size());

        c.swapSides();
        checkEq("tr swap exchanges A", "ja", c.sideA);
        checkEq("tr swap exchanges B", "en", c.sideB);
        checkEq("tr past turns keep their directions", "en",
                c.turns.get(0).fromIso);

        String body = TranslatorCore.transcriptBody(c);
        TranslatorCore.Conversation c2 = TranslatorCore.parseTranscript(body);
        checkEq("tr transcript round-trip side A", "ja", c2.sideA);
        checkEq("tr transcript round-trip side B", "en", c2.sideB);
        checkEq("tr transcript round-trip turn count", 2, c2.turns.size());
        checkEq("tr transcript round-trip heard",
                "Hello, how are you?", c2.turns.get(0).heard);
        checkEq("tr transcript round-trip rendered",
                "こんにちは、元気ですか", c2.turns.get(0).rendered);
        checkEq("tr transcript round-trip direction from", "en",
                c2.turns.get(0).fromIso);
        checkEq("tr transcript round-trip direction to", "ja",
                c2.turns.get(0).toIso);
        checkEq("tr transcript round-trip side", 'B', c2.turns.get(1).side);
        checkEq("tr transcript round-trip mode",
                TranslatorCore.OutMode.TEXT, c2.turns.get(1).mode);
        checkEq("tr transcript round-trip route",
                TranslatorCore.RouteKind.DIRECT, c2.turns.get(0).route);

        TranslatorCore.Conversation cp = new TranslatorCore.Conversation();
        cp.sideA = "ja";
        cp.sideB = "fr";
        cp.addTurn("ありがとう", "Merci", "Thank you",
                TranslatorCore.OutMode.BOTH, pivot);
        TranslatorCore.Conversation cp2 =
                TranslatorCore.parseTranscript(TranslatorCore.transcriptBody(cp));
        checkEq("tr pivot intermediate kept in the file", "Thank you",
                cp2.turns.get(0).via);
    }

    /** Coverage is DERIVED from the audited machinery, not boasted. */
    static void doorCoverageSuite() {
        java.util.Set<String> pairs = setOf("ja-en", "en-ja");
        check("coverage whisper hears the pack pair source",
                DoorCoverage.whisperCovers("ja", pairs));
        check("coverage whisper does not hear the target",
                !DoorCoverage.whisperCovers("en", setOf("ja-en")));
        check("coverage whisper hears nothing with no packs",
                !DoorCoverage.whisperCovers("ja", setOf()));

        VoiceLangs.Row ja = vrow("Japanese", "jpn", "ja");
        DoorCoverage.LangCoverage full = DoorCoverage.forRow(
                ja, setOf("jpn"), setOf(), true, pairs);
        checkEq("coverage voice from the ladder", VoiceResolve.Voice.PACK,
                full.voice);
        check("coverage ear follows the recognizer's presence", full.earTry);
        check("coverage whisper follows the pairs", full.whisper);
        DoorCoverage.LangCoverage bare = DoorCoverage.forRow(
                ja, setOf(), setOf(), false, setOf());
        checkEq("coverage empty machinery means no voice",
                VoiceResolve.Voice.NONE, bare.voice);
        check("coverage no recognizer means no ear", !bare.earTry);
        check("coverage nothing installed means no coverage",
                !DoorCoverage.anyCoverage(bare));
        checkEq("coverage route delegates to the core",
                TranslatorCore.RouteKind.PIVOT,
                DoorCoverage.route("ja", "fr",
                        setOf("ja-en", "en-fr")).kind);
        checkEq("coverage route refuses like the core",
                TranslatorCore.RouteKind.NONE,
                DoorCoverage.route("ja", "de", pairs).kind);
    }

    /** Every new door string exists in all thirteen tables. */
    static void doorStringsSuite() {
        String[] keys = {
                "door_speak", "door_listen", "door_translator",
                "speak_hint", "speak_say", "speak_clear", "speak_repeat",
                "speak_stop", "speak_language", "speak_doc", "speak_phrases",
                "speak_phrase_add", "speak_phrase_remove", "speak_speaking",
                "speak_last", "speak_doc_failed", "speak_type_first",
                "listen_start", "listen_stop", "listen_state_listening",
                "listen_state_heard", "listen_state_unsure",
                "listen_state_none", "listen_record", "listen_saved",
                "listen_to_translator", "listen_unsure_note", "listen_no_mic",
                "listen_whisper_note", "listen_system_note",
                "translator_side_a", "translator_side_b", "translator_swap",
                "translator_speak_turn", "translator_type_turn",
                "translator_type_hint", "translator_working",
                "translator_heard", "translator_rendered",
                "translator_mode_voice", "translator_mode_text",
                "translator_mode_both", "translator_cant",
                "translator_pivot", "translator_save", "translator_saved",
                "translator_turn_a", "translator_turn_b", "translator_new",
                "translator_voice_missing", "translator_not_heard",
                "translator_use_words"};
        checkEq("door key count pinned", 52, keys.length);
        for (String k : keys) {
            int idx = UiStrings.keyIndex(k);
            boolean everywhere = idx >= 0 && UiStrings.TABLES.values().stream()
                    .allMatch(t -> t.length == UiStrings.KEYS.length
                            && t[idx] != null && !t[idx].isEmpty());
            check("door string in all 13 tables: " + k, everywhere);
        }
        checkEq("door speak en", "Speak For Me",
                UiStrings.lookup("en", "door_speak"));
        checkEq("door listen en", "Listen For Me",
                UiStrings.lookup("en", "door_listen"));
        check("door cant carries both slots (en)",
                UiStrings.lookup("en", "translator_cant").contains("%s → %s"));
        check("door cant carries both slots (fa)",
                UiStrings.lookup("fa", "translator_cant").contains("%s"));
        checkEq("door keys total", 420, UiStrings.KEYS.length);
        check("door tables still complete", UiStrings.tablesComplete());
    }

    public static void main(String[] args) throws Exception {
        String yourFiles = System.getProperty("user.home")
                + "/workspace/your_files";
        String v061 = System.getProperty("user.home")
                + "/workspace/tala-tv-2026-10-16/work/v061";
        String bench = System.getProperty("user.home")
                + "/workspace/tala-tv-2026-10-16/work/dub-lab/phone-bench";

        gateTable();
        packChecks(yourFiles, v061);
        guardRegression(bench);
        cookerFsm();
        e2eRecords(v061);
        tvShare();
        recordingExportPure();
        cookedRecordShape();
        uiStrings();
        bookText();
        intakeSuite();
        episodeMatchSuite();
        bookChunker();
        bookLang();
        readingSession();
        mtLoopGuard();
        translatorBlocks();
        searchPin();
        coldStart();
        uiStringsReader();
        localFiles();
        catalogIndex();
        titleNames();
        availability();
        iaLanguage();
        farsi();
        langRegistry();
        guideModel();
        xmltv();
        timerModel();
        memoryFiles();
        iaMarcExtended();
        uiStringsV110();
        linkHealthPatience();
        facetSuite();
        sortSuite();
        peopleSuite();
        guideMapSuite();
        subHarvestSuite();
        dubGateSuite();
        voiceBookSeasons();
        dubShelfSuite();
        diarPackSuite(yourFiles);
        diarE2ESuite(yourFiles);
        timerFrameSuite();
        liveGroupsSuite();
        autoPlaySuite();
        estimateSuite();
        pipeSuite();
        crashReaderSuite();
        voiceLangsSuite();
        voiceResolveSuite();
        voicePackSuite(yourFiles);
        voiceFrontendSuite();
        voiceoverSurfacesSuite();
        creditSuite();
        storyCastSuite();
        storyPanelsSuite();
        storyDataSuite();
        gateQuestionsSuite();
        nextEpisodeSuite();
        storyWiringSuite();
        hydraSuite();
        failureWordsSuite();
        cardStateSuite();
        displayCountsSuite();
        honestyWiringSuite();
        continuity141Suite();
        speakQueueSuite();
        quickPhrasesSuite();
        docSpeakSuite();
        listenRecordSuite();
        translatorSuite();
        doorCoverageSuite();
        doorStringsSuite();
        readerSuite();
        installerFaceSuite();
        sanctuarySuite();
        channelScanSuite();
        proveTierSuite();
        receiptsSuite();
        commonsSuite();
        nfajSuite();

        if (CI) {
            System.out.println("CI SKIPS: " + CI_SKIPS.size() + " group(s): "
                    + (CI_SKIPS.isEmpty() ? "(none)"
                            : String.join(", ", CI_SKIPS)));
        }
        System.out.println("V180Test: " + checks + " checks, "
                + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }


    // ------------------------------------------------------------ v0.16.0 the reader

    static final String IA_META_OPEN =
            "{\"metadata\":{\"title\":\"Aesop's Fables\",\"access-restricted-item\":false},"
            + "\"files\":["
            + "{\"name\":\"x.epub\",\"format\":\"EPUB\",\"private\":false},"
            + "{\"name\":\"x_djvu.txt\",\"format\":\"DjVuTXT\"},"
            + "{\"name\":\"x.pdf\",\"format\":\"Text PDF\"},"
            + "{\"name\":\"x_scandata.xml\",\"format\":\"Scandata\"},"
            + "{\"name\":\"x_jp2.zip\",\"format\":\"JP2 ZIP\"}]}";
    static final String IA_META_WALLED =
            "{\"metadata\":{\"title\":\"Pride and Prejudice\","
            + "\"access-restricted-item\":true},"
            + "\"files\":["
            + "{\"name\":\"x.epub\",\"format\":\"EPUB\",\"private\":true},"
            + "{\"name\":\"x_djvu.txt\",\"format\":\"DjVuTXT\",\"private\":true},"
            + "{\"name\":\"x.pdf\",\"format\":\"Text PDF\",\"private\":true},"
            + "{\"name\":\"x_scandata.xml\",\"format\":\"Scandata\",\"private\":true}]}";
    static final String IA_META_PRIVATE_ONLY =
            "{\"metadata\":{\"title\":\"Quiet Book\"},"
            + "\"files\":["
            + "{\"name\":\"x.epub\",\"format\":\"EPUB\",\"private\":true},"
            + "{\"name\":\"x_djvu.txt\",\"format\":\"DjVuTXT\",\"private\":true}]}";
    static final String IA_META_ENCRYPTED_ONLY =
            "{\"metadata\":{\"title\":\"Locked Book\"},"
            + "\"files\":["
            + "{\"name\":\"x.lcp.epub\",\"format\":\"EPUB\"},"
            + "{\"name\":\"x_encrypted.pdf\",\"format\":\"Text PDF\"}]}";
    static final String IA_META_AUDIO_ONLY =
            "{\"metadata\":{\"title\":\"Sounds\"},"
            + "\"files\":[{\"name\":\"x.mp3\",\"format\":\"VBR MP3\"}]}";

    static void readerSuite() throws Exception {
        // ---- search query construction (title / author / language)
        String tu = BookSource.iaSearchUrl("Pride and Prejudice", false, "", 20, 1);
        check("reader: IA title query searches titles in texts",
                tu.contains("title%3A%28Pride") && tu.contains("mediatype%3A%28texts%29"));
        String au = BookSource.iaSearchUrl("Jane Austen", true, "", 20, 1);
        check("reader: IA author query searches creator",
                au.contains("creator%3A%28Jane"));
        String lu = BookSource.iaSearchUrl("Faust", false, "ger", 20, 1);
        check("reader: IA language filter rides the query",
                lu.contains("language%3A%28ger%29"));
        checkEq("reader: Lucene operators stripped from words",
                "Pride Prejudice", BookSource.iaQueryWords("Pride +Prejudice:"));
        check("reader: Gutenberg language rides its URL",
                BookSource.gutSearchUrl("Faust", "fr").contains("languages=fr"));
        check("reader: fountain URL is the children's collection",
                BookSource.iaChildrenUrl(30, 1).contains("iacl"));

        // ---- IA search parse: names worn, states unresolved
        String iaJson = "{\"response\":{\"numFound\":2,\"docs\":["
                + "{\"identifier\":\"id1\",\"title\":\"Goody Two-Shoes\","
                + "\"creator\":\"Newbery, John\",\"year\":1765,\"language\":\"eng\","
                + "\"collection\":[\"iacl\",\"opensource\"]},"
                + "{\"identifier\":\"id2\",\"title\":\"Abroad\","
                + "\"creator\":[\"Crane, Thomas\",\"Houghton, Ellen\"],"
                + "\"language\":\"eng\",\"collection\":[\"iacl\"]}]}}";
        java.util.List<BookSource.Hit> iaHits = BookSource.parseIaSearch(iaJson);
        checkEq("reader: IA search parses both docs", 2, iaHits.size());
        checkEq("reader: IA creator string carried", "Newbery, John",
                iaHits.get(0).author);
        check("reader: IA creator array joined",
                iaHits.get(1).author.contains("Crane, Thomas"));
        check("reader: IA hits start UNKNOWN (no promise yet)",
                iaHits.get(0).state == BookSource.UNKNOWN
                        && !iaHits.get(0).readable());

        // ---- availability derivation: OPEN
        BookSource.Hit open = new BookSource.Hit();
        open.itemId = "x";
        BookSource.applyIaMetadata(open, IA_META_OPEN);
        check("reader: open item resolves OPEN", open.state == BookSource.OPEN);
        checkEq("reader: EPUB wins the sibling order", "epub", open.siblingKind);
        check("reader: sibling URL is the Archive download door",
                open.siblingUrl.equals("https://archive.org/download/x/x.epub"));
        check("reader: public scandata verifies page pictures", open.pageImages);
        check("reader: open item carries pd evidence", open.pd);
        check("reader: OPEN + sibling is readable", open.readable());

        // sibling preference falls through a locked EPUB to the text
        BookSource.Hit fall = new BookSource.Hit();
        fall.itemId = "x";
        BookSource.applyIaMetadata(fall,
                "{\"metadata\":{},\"files\":["
                + "{\"name\":\"x.epub\",\"format\":\"EPUB\",\"private\":true},"
                + "{\"name\":\"x_djvu.txt\",\"format\":\"DjVuTXT\"}]}");
        check("reader: locked EPUB falls to the public text",
                fall.state == BookSource.OPEN && "txt".equals(fall.siblingKind));

        // text-layer PDF is the last sibling resort
        BookSource.Hit pdf = new BookSource.Hit();
        pdf.itemId = "x";
        BookSource.applyIaMetadata(pdf,
                "{\"metadata\":{},\"files\":["
                + "{\"name\":\"x.pdf\",\"format\":\"Text PDF\"}]}");
        check("reader: text PDF serves when nothing better is public",
                pdf.state == BookSource.OPEN && "pdf".equals(pdf.siblingKind));

        // ---- availability derivation: the lending wall
        BookSource.Hit walled = new BookSource.Hit();
        walled.itemId = "x";
        walled.collections.add("inlibrary");
        BookSource.applyIaMetadata(walled, IA_META_WALLED);
        check("reader: restricted item is WALLED", walled.state == BookSource.WALLED);
        check("reader: WALLED yields NO readable source, ever",
                walled.siblingUrl.isEmpty() && !walled.readable()
                        && !BookSource.storyFiable(walled));
        check("reader: walled item shows no page pictures", !walled.pageImages);

        BookSource.Hit priv = new BookSource.Hit();
        priv.itemId = "x";
        BookSource.applyIaMetadata(priv, IA_META_PRIVATE_ONLY);
        check("reader: all-private files are the wall's shape without the flag",
                priv.state == BookSource.WALLED && !priv.readable());

        BookSource.Hit enc = new BookSource.Hit();
        enc.itemId = "x";
        BookSource.applyIaMetadata(enc, IA_META_ENCRYPTED_ONLY);
        check("reader: encrypted-only files are WALLED, never a sibling",
                enc.state == BookSource.WALLED && enc.siblingUrl.isEmpty());

        BookSource.Hit audio = new BookSource.Hit();
        audio.itemId = "x";
        BookSource.applyIaMetadata(audio, IA_META_AUDIO_ONLY);
        check("reader: no readable files stays UNKNOWN, unpromised",
                audio.state == BookSource.UNKNOWN && !audio.readable());

        // ---- page-image gating on verified structure
        checkEq("reader: scandata leaf count", 3, BookSource.scandataLeafCount(
                "<book><pageData><page leafNum=\"0\"/><page leafNum=\"1\"/>"
                + "<page leafNum=\"2\"/></pageData></book>"));
        checkEq("reader: no scandata, no count", 0,
                BookSource.scandataLeafCount(""));
        check("reader: page door URL is the standard BookReader door",
                BookSource.iaPageUrl("x", 5).equals(
                        "https://archive.org/download/x/page/n5.jpg"));
        check("reader: file door encodes spaces",
                BookSource.iaFileUrl("x", "my book.epub").contains("my%20book.epub"));

        // ---- Gutenberg: availability resolves at parse
        String gutJson = "{\"count\":2,\"results\":["
                + "{\"id\":1342,\"title\":\"Pride and Prejudice\","
                + "\"authors\":[{\"name\":\"Austen, Jane\"}],"
                + "\"languages\":[\"en\"],\"copyright\":false,"
                + "\"bookshelves\":[\"Love Stories\"],"
                + "\"formats\":{\"text/html\":\"https://www.gutenberg.org/files/1342/1342-h/1342-h.htm\","
                + "\"application/epub+zip\":\"https://www.gutenberg.org/cache/epub/1342/pg1342.epub\","
                + "\"text/plain; charset=utf-8\":\"https://www.gutenberg.org/cache/epub/1342/pg1342.txt\"}},"
                + "{\"id\":55,\"title\":\"The Wonderful Wizard of Oz\","
                + "\"authors\":[{\"name\":\"Baum, L. Frank\"}],"
                + "\"languages\":[\"en\"],\"copyright\":false,"
                + "\"bookshelves\":[\"Children's Literature\"],"
                + "\"formats\":{\"text/plain; charset=utf-8\":\"https://www.gutenberg.org/files/55/55.txt\"}}]}";
        java.util.List<BookSource.Hit> gut = BookSource.parseGutSearch(gutJson);
        checkEq("reader: Gutendex parses both books", 2, gut.size());
        check("reader: Gutenberg EPUB beats TXT beats HTML",
                "epub".equals(gut.get(0).siblingKind)
                        && gut.get(0).state == BookSource.OPEN);
        check("reader: Gutenberg wears its own name",
                "Project Gutenberg".equals(
                        BookSource.sourceName(gut.get(0).source)));
        check("reader: Gutenberg pd from the catalogue flag", gut.get(0).pd);
        check("reader: Gutenberg TXT-only book still opens as text",
                "txt".equals(gut.get(1).siblingKind) && gut.get(1).readable());

        String gutWalled = "{\"count\":1,\"results\":[{\"id\":9,\"title\":\"Held\","
                + "\"authors\":[],\"languages\":[\"en\"],\"copyright\":true,"
                + "\"bookshelves\":[\"Children's Literature\"],"
                + "\"formats\":{\"text/plain\":\"https://www.gutenberg.org/files/9/9.txt\"}}]}";
        BookSource.Hit held = BookSource.parseGutSearch(gutWalled).get(0);
        check("reader: a copyrighted Gutenberg book is not pd evidence",
                !held.pd && !BookSource.storyEligible(held));

        // ---- Open Library: the catalogue resolves through the Archive
        String olJson = "{\"numFound\":3,\"docs\":["
                + "{\"title\":\"Pride and Prejudice\",\"author_name\":[\"Jane Austen\"],"
                + "\"first_publish_year\":1813,\"language\":[\"eng\"],"
                + "\"ia\":[\"prideprejudice01aust\",\"prideprejudice00aust\"]},"
                + "{\"title\":\"Borrow Only\",\"author_name\":[\"Some One\"],"
                + "\"lending_edition_s\":\"OL1M\"},"
                + "{\"title\":\"Ghost\",\"author_name\":[\"No One\"]}]}";
        java.util.List<BookSource.Hit> ol = BookSource.parseOlSearch(olJson);
        checkEq("reader: OL parses its docs", 3, ol.size());
        check("reader: OL hit with Archive copies waits for the Archive leg",
                ol.get(0).state == BookSource.UNKNOWN
                        && "prideprejudice01aust".equals(ol.get(0).readVia)
                        && !ol.get(0).readable());
        check("reader: OL lending-only hit is WALLED on the spot",
                ol.get(1).state == BookSource.WALLED && !ol.get(1).readable());
        check("reader: OL ghost makes no promise",
                ol.get(2).state == BookSource.UNKNOWN && !ol.get(2).readable());
        // and the Archive leg settles an OL hit with the same rules
        BookSource.applyIaMetadata(ol.get(0), IA_META_WALLED);
        check("reader: OL hit through a walled edition stays WALLED",
                ol.get(0).state == BookSource.WALLED && !ol.get(0).readable());

        // ---- LibriVox: openly reachable audio
        String lvJson = "{\"books\":[{\"id\":\"253\",\"title\":\"Pride and Prejudice\","
                + "\"authors\":[{\"first_name\":\"Jane\",\"last_name\":\"Austen\"}],"
                + "\"language\":\"English\",\"copyright_year\":\"1813\","
                + "\"num_sections\":\"37\","
                + "\"url_zip_file\":\"https://archive.org/compress/pride_and_prejudice_librivox/x.zip\"}]}";
        java.util.List<BookSource.Hit> lv = BookSource.parseLvSearch(lvJson);
        check("reader: LibriVox hit opens on its own evidence",
                lv.size() == 1 && lv.get(0).state == BookSource.OPEN
                        && lv.get(0).audioSections == 37 && lv.get(0).pd);
        String lvSecs = "{\"books\":[{\"id\":\"253\",\"sections\":["
                + "{\"title\":\"Chapters 1-3\",\"listen_url\":\"https://archive.org/download/x/01.mp3\",\"playtime\":\"1132\"},"
                + "{\"title\":\"Chapters 4-5\",\"listen_url\":\"https://archive.org/download/x/02.mp3\",\"playtime\":\"865\"}]}]}";
        java.util.List<String[]> secs = BookSource.parseLvSections(lvSecs);
        checkEq("reader: LibriVox sections parse", 2, secs.size());
        checkEq("reader: LibriVox section door", "https://archive.org/download/x/01.mp3",
                secs.get(0)[1]);
        check("reader: LibriVox match needs title AND author",
                BookSource.lvMatches(lvJson, "Pride and Prejudice", "Jane Austen"));
        check("reader: LibriVox refuses a wrong author",
                !BookSource.lvMatches(lvJson, "Pride and Prejudice", "Charlotte Bronte"));
        check("reader: LibriVox refuses a wrong title",
                !BookSource.lvMatches(lvJson, "Emma", "Jane Austen"));

        // ---- the fountain gate: curation at the collection gate
        BookSource.Hit kid = new BookSource.Hit();
        kid.itemId = "wonderfulwizardo00baumiala";
        kid.collections.add("iacl");
        BookSource.applyIaMetadata(kid, IA_META_OPEN);
        check("reader: IACL + open + pd + sibling is a story", BookSource.storyEligible(kid));
        BookSource.Hit stray = new BookSource.Hit();
        stray.itemId = "x";
        stray.collections.add("opensource");
        BookSource.applyIaMetadata(stray, IA_META_OPEN);
        check("reader: an open book outside the children's gate is no fountain story",
                !BookSource.storyEligible(stray));
        check("reader: a Gutenberg children's-shelf pd book is a story",
                BookSource.storyEligible(gut.get(1)));
        checkEq("reader: fountain counts verified stories only", 2,
                BookSource.fountainVerified(java.util.Arrays.asList(
                        kid, stray, gut.get(1), walled)));
        check("reader: make-a-story opens on any readable book",
                BookSource.storyFiable(stray) && BookSource.storyFiable(gut.get(0)));
        check("reader: make-a-story refuses the walled and unresolved",
                !BookSource.storyFiable(walled) && !BookSource.storyFiable(audio));

        // ---- reading-room state: files round-trip on the hook
        java.io.File rroot = java.nio.file.Files.createTempDirectory("rbook").toFile();
        ReaderBook.Pos pos = new ReaderBook.Pos();
        pos.charOffset = 4321; pos.mode = ReaderBook.MODE_SCROLL; pos.leaf = 7;
        pos.updatedMs = 99L;
        ReaderBook.savePos(rroot, "ia_x", pos);
        ReaderBook.Pos posBack = ReaderBook.loadPos(rroot, "ia_x");
        check("reader: position round-trips (offset, mode, leaf)",
                posBack.charOffset == 4321
                        && ReaderBook.MODE_SCROLL.equals(posBack.mode)
                        && posBack.leaf == 7);
        java.util.List<ReaderBook.Mark> marks = ReaderBook.withMark(
                ReaderBook.withMark(new java.util.ArrayList<>(), 500, "Chapter 2"),
                100, "Start");
        ReaderBook.saveMarks(rroot, "ia_x", marks);
        java.util.List<ReaderBook.Mark> marksBack = ReaderBook.loadMarks(rroot, "ia_x");
        check("reader: bookmarks round-trip, sorted by place",
                marksBack.size() == 2 && marksBack.get(0).charOffset == 100
                        && "Chapter 2".equals(marksBack.get(1).label));
        marks = ReaderBook.withMark(marks, 500, "Chapter Two");
        checkEq("reader: re-marking a place replaces its label", "Chapter Two",
                marks.get(1).label);
        ReaderBook.Settings set = new ReaderBook.Settings();
        set.theme = ReaderBook.THEME_NIGHT; set.textSp = 34;
        set.mode = ReaderBook.MODE_SCROLL;
        ReaderBook.saveSettings(rroot, set);
        ReaderBook.Settings setBack = ReaderBook.loadSettings(rroot);
        check("reader: theme/type/mode settings persist",
                ReaderBook.THEME_NIGHT.equals(setBack.theme)
                        && setBack.textSp == 34
                        && ReaderBook.MODE_SCROLL.equals(setBack.mode));
        checkEq("reader: type size clamps to the large-print ceiling", 44,
                ReaderBook.settingsFromText("sp\t99\n").textSp);
        check("reader: a bad theme name is refused, not kept",
                ReaderBook.THEME_DAY.equals(
                        ReaderBook.settingsFromText("theme\tplaid\n").theme));
        check("reader: theme cycle day-night-contrast",
                ReaderBook.THEME_NIGHT.equals(ReaderBook.nextTheme("day"))
                        && ReaderBook.THEME_CONTRAST.equals(ReaderBook.nextTheme("night"))
                        && ReaderBook.THEME_DAY.equals(ReaderBook.nextTheme("contrast")));
        check("reader: bigger type fits fewer chars per page",
                ReaderBook.charsPerPage(34) < ReaderBook.charsPerPage(18));
        checkEq("reader: keys are source-qualified and file-safe",
                "gutenberg_pg1342", ReaderBook.keyFor("gutenberg", "pg1342"));

        // pagination never opens mid-word (on spaced text)
        StringBuilder tb = new StringBuilder();
        for (int i = 0; i < 400; i++) tb.append("word number ").append(i).append(' ');
        java.util.List<Integer> starts = ReaderBook.pageStarts(tb.toString(), 300);
        boolean boundaries = starts.get(0) == 0;
        for (int i = 1; i < starts.size(); i++) {
            if (starts.get(i) <= starts.get(i - 1)) boundaries = false;
            if (!Character.isWhitespace(tb.charAt(starts.get(i) - 1))) boundaries = false;
        }
        check("reader: pages start at word boundaries, in order", boundaries);
        checkEq("reader: pageAtOffset finds the containing page",
                starts.size() - 1,
                ReaderBook.pageAtOffset(starts, tb.length() - 1));

        // ---- read-aloud handoff: the chunk standing at the offset
        java.util.List<BookText.Chapter> chs = java.util.Arrays.asList(
                new BookText.Chapter("Chapter 1", 0),
                new BookText.Chapter("Chapter 2", 2000));
        StringBuilder bt = new StringBuilder();
        for (int i = 0; i < 500; i++) bt.append("Sentence one of the tale. ");
        int half = bt.length();
        for (int i = 0; i < 500; i++) bt.append("Another sentence follows here. ");
        java.util.List<BookText.Chapter> realChs = java.util.Arrays.asList(
                new BookText.Chapter("Chapter 1", 0),
                new BookText.Chapter("Chapter 2", half));
        java.util.List<BookChunker.Chunk> chunks =
                BookChunker.chunk(bt.toString(), realChs);
        int firstCh2 = -1;
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).startChar >= half) { firstCh2 = i; break; }
        }
        check("reader: chunks cover the chapter seam", firstCh2 > 0);
        int probeAt = chunks.get(firstCh2).startChar + 5;
        int atIdx = BookChunker.chunkIndexAt(chunks, probeAt);
        check("reader: handoff chunk stands in chapter 2",
                atIdx == firstCh2
                        && "Chapter 2".equals(chunks.get(atIdx).chapterTitle)
                        && chunks.get(atIdx).startChar <= probeAt
                        && chunks.get(atIdx).endChar > probeAt);
        int midIdx = BookChunker.chunkIndexAt(chunks, half - 40);
        check("reader: an offset maps to the chunk containing it",
                chunks.get(midIdx).startChar <= half - 40
                        && chunks.get(midIdx).endChar > half - 40);

        // ---- the dub's casting engine, witnessed by text (Addendum 4)
        StoryCast.Book cast = StoryCast.newBook("bk", "Tale", "en");
        StoryCast.enroll(cast, "Mary");
        StoryCast.enroll(cast, "Tom");
        java.util.List<StoryCast.Segment> tagged = StoryCast.attribute(cast,
                "\"I am here,\" said Mary. \"And I,\" said Tom.");
        boolean marySaid = false, tomSaid = false;
        for (StoryCast.Segment s : tagged) {
            if ("Mary".equals(s.speaker) && s.text.contains("I am here")) marySaid = true;
            if ("Tom".equals(s.speaker) && s.text.contains("And I")) tomSaid = true;
        }
        check("reader: tagged lines go to their tagged speakers", marySaid && tomSaid);
        java.util.List<StoryCast.Segment> before = StoryCast.attribute(cast,
                "Mary said, \"We go now.\"");
        check("reader: a before-tag names the speaker too",
                before.stream().anyMatch(s -> "Mary".equals(s.speaker)
                        && s.text.contains("We go now")));
        java.util.List<StoryCast.Segment> amb = StoryCast.attribute(cast,
                "The wind rose over the cold hill and the house stood dark. "
                + "\"Who is there?\" No one answered from the road.");
        check("reader: an unsettled line goes to the NARRATOR, never a guess",
                amb.stream().anyMatch(s -> StoryCast.NARRATOR.equals(s.speaker)
                        && s.text.contains("Who is there")));
        StoryCast.enroll(cast, "Fox");
        java.util.List<StoryCast.Segment> cont = StoryCast.attribute(cast,
                "\"I will eat you up,\" said the Fox. \"Now.\" \"Go.\"");
        check("reader: a quote hard against the last quote keeps the last settled speaker",
                cont.stream().anyMatch(s -> "Fox".equals(s.speaker)
                        && s.text.contains("Go.")));
        check("reader: the in-between untagged line is NOT guessed (narrator reads it)",
                cont.stream().anyMatch(s -> StoryCast.NARRATOR.equals(s.speaker)
                        && s.text.contains("Now.")));
        java.util.List<StoryCast.Segment> tagless = StoryCast.attribute(cast,
                "\"One.\" \"Two.\"");
        check("reader: a tagless exchange is never guessed between speakers",
                tagless.stream().allMatch(
                        s -> StoryCast.NARRATOR.equals(s.speaker)));
        // a new name in a tag enrolls into the book's cast
        StoryCast.Book fresh = StoryCast.newBook("bk2", "Tale", "en");
        StoryCast.attribute(fresh, "\"Come here,\" said Bruno.");
        check("reader: a tagged new name enrolls in the cast",
                fresh.byName("Bruno") != null);
        // cast persistence across chapters: first chapter's voice
        // is the last chapter's voice (the dub's acceptance test)
        StoryCast.Book novel = StoryCast.newBook("novel", "Novel", "en");
        StoryCast.collectCast(novel, java.util.Collections.singletonList(
                "\"We begin,\" said Mary. Mary laughed."));
        double maryPitch = novel.byName("Mary").pitch;
        String novelJson = StoryCast.toJson(novel);
        StoryCast.Book novelBack = StoryCast.fromJson(novelJson);
        java.util.List<StoryCast.Segment> lastCh = StoryCast.attribute(novelBack,
                "\"We end,\" said Mary.");
        check("reader: cast persists across chapters (same voice, ch. last as ch. first)",
                lastCh.stream().anyMatch(s -> "Mary".equals(s.speaker))
                        && novelBack.byName("Mary").pitch == maryPitch);

        // ---- series tier: series -> book -> narrator (Addendum 5)
        java.io.File sroot = java.nio.file.Files.createTempDirectory("series").toFile();
        String gkey0 = SeriesBook.group(sroot, "gutenberg_pg55", "Oz Books", "hand");
        StoryCast.Book series = SeriesBook.loadCast(sroot, gkey0, "Oz Books", "en");
        StoryCast.Entry sMary = StoryCast.enroll(series, "Mary");
        sMary.ttsVoice = "series-voice";
        StoryCast.Book vol3 = StoryCast.newBook("vol3", "Vol 3", "en");
        StoryCast.Entry bMary = StoryCast.enroll(vol3, "Mary");
        bMary.ttsVoice = "book-voice";
        StoryCast.enroll(vol3, "Tom");
        StoryCast.Book resolved = SeriesBook.resolve(series, vol3);
        checkEq("reader: series voice wins in volume 3", "series-voice",
                resolved.byName("Mary").ttsVoice);
        check("reader: book-only characters ride into the resolved cast",
                resolved.byName("Tom") != null);
        StoryCast.Book grown = SeriesBook.enrollInto(series, resolved);
        check("reader: new characters enroll into the series book",
                grown.byName("Tom") != null);
        checkEq("reader: enrollment never overwrites a series casting",
                "series-voice", grown.byName("Mary").ttsVoice);
        SeriesBook.Recast rec = SeriesBook.recast(grown, "Mary", "new-voice", 123L);
        check("reader: a recast is one series-level decision, recorded",
                rec != null && "series-voice".equals(rec.fromVoice)
                        && "new-voice".equals(rec.toVoice)
                        && "new-voice".equals(grown.byName("Mary").ttsVoice));
        java.util.List<SeriesBook.Recast> recBack =
                SeriesBook.recastsFromText(SeriesBook.recastsToText(
                        java.util.Collections.singletonList(rec)));
        check("reader: the recast ledger round-trips",
                recBack.size() == 1 && "Mary".equals(recBack.get(0).name)
                        && recBack.get(0).stampMs == 123L);
        String gkey = gkey0;
        checkEq("reader: hand-grouped series files round-trip", gkey,
                SeriesBook.seriesKeyFor(sroot, "gutenberg_pg55"));
        check("reader: the group index remembers it was by hand",
                SeriesBook.loadIndex(sroot).stream().anyMatch(
                        g -> g.key.equals(gkey) && "hand".equals(g.source)));
        SeriesBook.saveCast(sroot, grown);
        StoryCast.Book seriesBack = SeriesBook.loadCast(sroot, gkey, "Oz Books", "en");
        checkEq("reader: the series VoiceBook persists as a file", "new-voice",
                seriesBack.byName("Mary").ttsVoice);

        // ---- story rows + cartridges (fountain / made plumbing)
        StoryData.Row frow = new StoryData.Row();
        frow.id = "fc_wonderfulwizardo00baumiala";
        frow.title = "The Wonderful Wizard of Oz";
        frow.author = "Baum, L. Frank";
        frow.lang = "en"; frow.taleGroup = "";
        frow.iaId = "wonderfulwizardo00baumiala";
        frow.source = "ia"; frow.illustrated = true;
        StoryData.Row fback = StoryData.parseCurationLine(StoryData.rowToLine(frow));
        check("reader: fountain rows round-trip their TSV line",
                fback != null && fback.illustrated
                        && "wonderfulwizardo00baumiala".equals(fback.iaId));
        StoryData.Row grow2 = new StoryData.Row();
        grow2.id = "made_gutenberg_pg1342"; grow2.title = "Pride and Prejudice";
        grow2.lang = "en"; grow2.taleGroup = "";
        grow2.directUrl = "https://www.gutenberg.org/cache/epub/1342/pg1342.epub";
        grow2.source = "gutenberg";
        StoryData.Row gback = StoryData.parseCurationLine(StoryData.rowToLine(grow2));
        check("reader: direct-file rows round-trip (the Gutenberg road)",
                gback != null && gback.directUrl.endsWith("pg1342.epub")
                        && "gutenberg".equals(gback.source));

        // cartridge creation from a NON-children's book fixture
        StoryCast.Book ncast = StoryCast.newBook("made_x", "Pride and Prejudice", "en");
        StoryCast.enroll(ncast, "Elizabeth");
        String ntext = "\"You must allow me to tell you how ardently "
                + "I admire and love you,\" said Elizabeth. "
                + "She spoke at length and with great feeling about the "
                + "matter, more words and more words of hers to fill the "
                + "chapter well past any other voice in it, on and on. "
                + "The rain fell all the rest of the day over the fields.";
        int ch2At = ntext.indexOf("The rain fell");
        java.util.List<BookText.Chapter> nchs = java.util.Arrays.asList(
                new BookText.Chapter("Chapter 1", 0),
                new BookText.Chapter("Chapter 2", ch2At));
        StoryData.Cartridge cart = ReaderRoomActivity.buildCartridge(
                "made_x_1", "Pride and Prejudice", 42L, "made_x",
                "Pride and Prejudice", "Project Gutenberg",
                ncast, ntext, nchs);
        checkEq("reader: a made cartridge maps one panel per chapter",
                2, cart.panels.size());
        check("reader: chapter captions are the chapters' own names",
                "Chapter 1".equals(cart.panels.get(0).caption)
                        && "Chapter 2".equals(cart.panels.get(1).caption));
        checkEq("reader: a speaking chapter wears its speaker's card",
                StoryPanels.cardFor("made_x", "Elizabeth"), cart.panels.get(0).panel);
        checkEq("reader: an all-narration chapter wears the narrator's card",
                StoryPanels.NARRATOR_CARD, cart.panels.get(1).panel);
        check("reader: provenance rides the cartridge",
                "Pride and Prejudice".equals(cart.fromBook)
                        && "Project Gutenberg".equals(cart.fromSource)
                        && "made_x".equals(cart.bookId));
        StoryData.Cartridge cartBack = StoryData.cartridgeFromJson(
                StoryData.cartridgeToJson(cart));
        check("reader: cartridge JSON round-trips with provenance",
                "Project Gutenberg".equals(cartBack.fromSource)
                        && cartBack.panels.size() == 2);
        StoryData.Cartridge oldCart = StoryData.cartridgeFromJson(
                "{\"id\":\"c1\",\"title\":\"Old\",\"createdMs\":1,\"panels\":[]}");
        check("reader: pre-0.16 cartridges still parse (provenance empty, not fake)",
                oldCart.bookId.isEmpty() && oldCart.fromSource.isEmpty());

        // made-stories shelf derives from cartridges on the hook
        boolean madeShows = false;
        for (StoryData.Cartridge c : new StoryData.Cartridge[]{cart}) {
            if (!c.bookId.isEmpty()) madeShows = true;
        }
        check("reader: a book cartridge belongs on the made shelf", madeShows);
    }

    // ------------------------------------------------------------ gate

    static TranslateGate.Decision dec(boolean ex, boolean sl, boolean pack,
                                      boolean onnx, boolean bound, String why,
                                      boolean live, boolean rec, boolean settled,
                                      boolean local) {
        return TranslateGate.decide(ex, sl, pack, onnx, bound, why,
                live, rec, settled, local);
    }

    static void gateTable() {
        checkEq("gate existing", TranslateGate.Outcome.EXISTING,
                dec(true, true, false, false, false, "", true, false, false, false).outcome);
        checkEq("gate stream-lang", TranslateGate.Outcome.STREAM_HAS_LANG,
                dec(false, true, false, false, false, "", true, false, false, false).outcome);
        checkEq("gate needs-pack live", TranslateGate.Outcome.NEEDS_PACK,
                dec(false, false, false, false, false, "", true, false, false, false).outcome);
        checkEq("gate needs-pack file", TranslateGate.Outcome.NEEDS_PACK,
                dec(false, false, false, false, false, "", false, false, false, true).outcome);
        checkEq("gate pack-unbound", TranslateGate.Outcome.PACK_UNBOUND,
                dec(false, false, true, false, false, "", false, false, false, true).outcome);
        TranslateGate.Decision f = dec(false, false, true, true, false,
                "no onnx model: mt/encoder_model.onnx", false, false, false, true);
        checkEq("gate engine-failed", TranslateGate.Outcome.ENGINE_FAILED, f.outcome);
        check("gate engine-failed detail", f.detail.contains("no onnx model"));
        TranslateGate.Decision le = dec(false, false, true, true, true, "",
                true, false, false, false);
        checkEq("gate live-edge", TranslateGate.Outcome.LIVE_EDGE, le.outcome);
        check("gate live record door", le.recordDoor());
        check("gate live does not start cook", !le.startsCook());
        // v0.7.0: a recording still being made keeps an honest NEXT…
        checkEq("gate recording still going", TranslateGate.Outcome.RECORDING_NEXT,
                dec(false, false, true, true, true, "", false, true, false, false).outcome);
        // …and a settled recording COOKS (the span assembly feeds it)
        TranslateGate.Decision rc = dec(false, false, true, true, true, "",
                false, true, true, false);
        checkEq("gate recording settled cooks", TranslateGate.Outcome.COOK, rc.outcome);
        check("gate recording settled starts cook", rc.startsCook());
        check("gate recording settled no record door", !rc.recordDoor());
        // a settled recording with no pack still hears about the pack
        checkEq("gate recording settled, no pack", TranslateGate.Outcome.NEEDS_PACK,
                dec(false, false, false, false, false, "", false, true, true, false).outcome);
        checkEq("gate stream-cook", TranslateGate.Outcome.STREAM_COOK,
                dec(false, false, true, true, true, "", false, false, false, false).outcome);
        TranslateGate.Decision ck = dec(false, false, true, true, true, "",
                false, false, false, true);
        checkEq("gate cook", TranslateGate.Outcome.COOK, ck.outcome);
        check("gate cook starts cook", ck.startsCook());
        check("gate cook no record door", !ck.recordDoor());
    }

    // ------------------------------------------------------------ packs

    static void packChecks(String yourFiles, String v061) throws Exception {
        if (ciSkipIfMissing("pack",
                new File(yourFiles, "sandy-engine-ja-en-onnx.pack"),
                new File(v061, "out/packs/bad-sha.pack"),
                new File(v061, "out/packs/missing-file.pack"))) return;
        File tmp = new File(v061, "build/test-extract");
        deleteRec(tmp);
        EnginePack.Result r;
        try (FileInputStream in = new FileInputStream(
                new File(yourFiles, "sandy-engine-ja-en-onnx.pack"))) {
            r = EnginePack.validateAndExtract(in, tmp);
        }
        check("pack v2 validates", r.ok);
        checkEq("pack v2 version", 2, r.version);
        checkEq("pack v2 pair", "ja-en", r.pair);
        checkEq("pack v2 asr format", "onnx-int8", r.asrFormat);
        checkEq("pack v2 mt format", "onnx-int8", r.mtFormat);
        checkEq("pack v2 file count", 10, r.files.size());
        check("pack v2 extracted decoder",
                new File(tmp, "asr/decoder_model.onnx").isFile());

        EnginePack.Result bad1;
        try (FileInputStream in = new FileInputStream(
                new File(v061, "out/packs/bad-sha.pack"))) {
            bad1 = EnginePack.validateAndExtract(in, null);
        }
        check("pack bad-sha rejected", !bad1.ok && !bad1.errors.isEmpty());
        EnginePack.Result bad2;
        try (FileInputStream in = new FileInputStream(
                new File(v061, "out/packs/missing-file.pack"))) {
            bad2 = EnginePack.validateAndExtract(in, null);
        }
        check("pack missing-file rejected", !bad2.ok && !bad2.errors.isEmpty());

        File v1 = new File(yourFiles, "sandy-engine-ja-en.pack");
        if (v1.isFile()) {
            EnginePack.Result r1;
            try (FileInputStream in = new FileInputStream(v1)) {
                r1 = EnginePack.validateAndExtract(in, null);
            }
            check("pack v1 validates", r1.ok);
            checkEq("pack v1 version", 1, r1.version);
            check("pack v1 is not onnx", !r1.asrFormat.startsWith("onnx"));
        }
    }

    // ------------------------------------------------------------ guard

    static void guardRegression(String bench) throws Exception {
        if (ciSkipIfMissing("guard",
                new File(bench, "asr_base_vad0.json"))) return;
        String json = new String(Files.readAllBytes(
                new File(bench, "asr_base_vad0.json").toPath()), "UTF-8");
        org.json.JSONObject root = new org.json.JSONObject(json);
        org.json.JSONArray arr = root.getJSONArray("segments");
        List<HallucinationGuard.Segment> segs = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.getJSONObject(i);
            segs.add(new HallucinationGuard.Segment(
                    o.getDouble("start"), o.getDouble("end"),
                    o.getString("text"), o.getDouble("avg_logprob"),
                    o.getDouble("no_speech_prob")));
        }
        checkEq("bench segments", 60, segs.size());
        HallucinationGuard.Result gr = HallucinationGuard.apply(segs);
        checkEq("guard kept", 37, gr.kept);
        checkEq("guard dropped", 23, gr.dropped);
        List<UnitMerge.Unit> raw = UnitMerge.merge(segs, null);
        checkEq("merge raw units", 38, raw.size());
        List<UnitMerge.Unit> kept = UnitMerge.merge(gr.surviving(segs), gr);
        checkEq("merge kept units", 22, kept.size());
    }

    // ------------------------------------------------------------ cooker

    static void cookerFsm() {
        Cooker c = new Cooker(600);
        checkEq("fsm needs pack", Cooker.State.NEEDS_PACK,
                c.begin(false, Cooker.PackStatus.NOT_INSTALLED, () -> true));
        c = new Cooker(600);
        checkEq("fsm unbound engine", Cooker.State.ENGINE_UNAVAILABLE,
                c.begin(false, Cooker.PackStatus.INSTALLED, () -> false));
        c = new Cooker(600);
        checkEq("fsm bound engine cooks", Cooker.State.COOKING,
                c.begin(false, Cooker.PackStatus.INSTALLED, () -> true));
        c.onChunkCooked(240, 30);
        checkEq("fsm GO after full first chunk", Cooker.State.GO, c.state());
        check("fsm goLit", c.goLit());
        c.onPlayTranslated();
        checkEq("fsm riding", Cooker.State.RIDING, c.state());
        c.onTick(245);
        checkEq("fsm starved", Cooker.State.STARVED, c.state());
        c.onChunkCooked(240, 30);
        checkEq("fsm resume after lead rebuilds", Cooker.State.RIDING, c.state());
        c.onChunkCooked(120, 15);
        c.onTick(600);
        checkEq("fsm done", Cooker.State.DONE, c.state());
        c = new Cooker(600);
        checkEq("fsm existing", Cooker.State.READY_EXISTING,
                c.begin(true, Cooker.PackStatus.NOT_INSTALLED, () -> false));
    }

    // ------------------------------------------------------------ e2e

    static void e2eRecords(String v061) throws Exception {
        if (ciSkipIfMissing("e2e",
                new File(v061, "out/e2e_slice.tr-en.json"),
                new File(v061, "out/e2e_slice.tr-en.srt"),
                new File(v061, "out/e2e_toshishun.tr-en.json"),
                new File(v061, "out/e2e_toshishun.tr-en.srt"))) return;
        for (String name : new String[]{"e2e_slice", "e2e_toshishun"}) {
            File json = new File(v061, "out/" + name + ".tr-en.json");
            File srt = new File(v061, "out/" + name + ".tr-en.srt");
            check("e2e " + name + " json exists", json.isFile());
            check("e2e " + name + " srt exists", srt.isFile());
            if (!json.isFile()) continue;
            String text = new String(Files.readAllBytes(json.toPath()), "UTF-8");
            List<CookedSubs.Line> lines = CookedSubs.parseJson(text);
            check("e2e " + name + " has lines", lines.size() > 0);
            boolean originals = true;
            for (CookedSubs.Line l : lines) {
                if (l.orig == null || l.orig.isEmpty()) originals = false;
                if (l.text == null || l.text.isEmpty()) originals = false;
                if (l.endMs <= l.startMs) originals = false;
            }
            check("e2e " + name + " lines carry text + originals + sane times",
                    originals);
            System.out.println("e2e " + name + ": " + lines.size() + " lines");
        }
    }

    // ------------------------------------------------------------ tv share

    static void tvShare() {
        checkEq("plan file path", TvShare.Kind.FILE,
                TvShare.planFor("/storage/emulated/0/Movies/a.mp4").kind);
        checkEq("plan file uri", TvShare.Kind.FILE,
                TvShare.planFor("file:///sdcard/a.mp4").kind);
        checkEq("plan content", TvShare.Kind.FILE,
                TvShare.planFor("content://media/external/video/12").kind);
        checkEq("plan http", TvShare.Kind.REDIRECT,
                TvShare.planFor("http://example.test/x.m3u8").kind);
        checkEq("plan https", TvShare.Kind.REDIRECT,
                TvShare.planFor("https://example.test/x.mp4").kind);
        checkEq("plan exotic", TvShare.Kind.PAGE_ONLY,
                TvShare.planFor("rtsp://example.test/x").kind);
        checkEq("plan null", TvShare.Kind.PAGE_ONLY, TvShare.planFor(null).kind);

        checkEq("fling redirect is itself", "http://example.test/x.m3u8",
                TvShare.flingUrlFor(TvShare.planFor("http://example.test/x.m3u8"), null));
        checkEq("fling file needs server", null,
                TvShare.flingUrlFor(TvShare.planFor("/sdcard/a.mp4"), null));
        checkEq("fling file via server", "http://192.168.1.20:8123/media",
                TvShare.flingUrlFor(TvShare.planFor("/sdcard/a.mp4"),
                        "http://192.168.1.20:8123"));
        checkEq("fling page-only", null,
                TvShare.flingUrlFor(TvShare.planFor("rtsp://x"), "http://h"));

        checkEq("watch url", "http://192.168.1.20:8123/watch?k=ab12",
                TvShare.watchUrl("192.168.1.20", 8123, "ab12"));
        checkEq("media url", "http://192.168.1.20:8123/media?k=ab12",
                TvShare.mediaUrl("192.168.1.20", 8123, "ab12"));

        String tok = TvShare.newToken(new Random(42));
        checkEq("token length", 16, tok.length());
        check("token hex", tok.matches("[0-9a-f]{16}"));
        checkEq("token seeded deterministic", tok, TvShare.newToken(new Random(42)));

        checkEq("ipv4 from wifi int", "192.168.1.1",
                TvShare.formatIpv4(192 | (168 << 8) | (1 << 16) | (1 << 24)));
        checkEq("ipv4 from bytes", "10.0.0.7",
                TvShare.formatIpv4(new byte[]{10, 0, 0, 7}));
        checkEq("ipv4 bad bytes", null, TvShare.formatIpv4(new byte[]{1, 2}));
        check("loopback 127", TvShare.isLoopback("127.0.0.1"));
        check("loopback zero", TvShare.isLoopback("0.0.0.0"));
        check("not loopback", !TvShare.isLoopback("192.168.1.20"));

        checkEq("mime m3u8", "application/vnd.apple.mpegurl",
                TvShare.mimeFor("http://x/live.m3u8?token=1"));
        checkEq("mime mp4", "video/mp4", TvShare.mimeFor("a/b.MP4"));
        checkEq("mime ts", "video/mp2t", TvShare.mimeFor("rec.ts"));
        checkEq("mime unknown", "application/octet-stream",
                TvShare.mimeFor("a/b.weirdext"));

        rangeEq("range plain", TvShare.parseRange("bytes=0-99", 1000), 0, 99);
        rangeEq("range open end", TvShare.parseRange("bytes=100-", 1000), 100, 999);
        rangeEq("range suffix", TvShare.parseRange("bytes=-50", 1000), 950, 999);
        rangeEq("range clamped", TvShare.parseRange("bytes=900-5000", 1000), 900, 999);
        checkEq("range start past end", null, TvShare.parseRange("bytes=1500-1600", 1000));
        checkEq("range garbage", null, TvShare.parseRange("bananas", 1000));
        checkEq("range null header", null, TvShare.parseRange(null, 1000));
        checkEq("range multi refused", null, TvShare.parseRange("bytes=0-1,3-4", 1000));
        checkEq("range unknown length", null, TvShare.parseRange("bytes=0-1", -1));

        String page = TvShare.playerPage("A <b>Show</b>", "/media?k=zz", true);
        check("page escapes title", page.contains("A &lt;b&gt;Show&lt;/b&gt;"));
        check("page has video", page.contains("<video"));
        check("page points at media", page.contains("src=\"/media?k=zz\""));
        check("page explains wifi", page.contains("same Wi-Fi"));
        String dead = TvShare.playerPage("X", "/media?k=zz", false);
        check("page unplayable has no video", !dead.contains("<video"));
        check("page unplayable says so", dead.contains("isn't a kind the TV can play"));
    }

    static void rangeEq(String name, long[] got, long wantStart, long wantEnd) {
        checks++;
        if (got == null || got[0] != wantStart || got[1] != wantEnd) {
            failures++;
            System.out.println("FAIL: " + name + " got="
                    + (got == null ? "null" : got[0] + "-" + got[1]));
        }
    }

    // ------------------------------------------------ share server (wire)

    // ------------------------------------------------ recording export

    static void recordingExportPure() throws Exception {
        String media = "#EXTM3U\r\n#EXT-X-VERSION:3\r\n"
                + "#EXT-X-TARGETDURATION:10\r\n"
                + "#EXTINF:9.96,\r\nseg-1.ts\r\n"
                + "#EXTINF:10.0,\r\nsub/seg-2.ts\r\n"
                + "#EXTINF:4.5,\r\nhttps://cdn.example.test/seg-3.ts\r\n"
                + "#EXT-X-ENDLIST\r\n";
        List<String> segs = RecordingExport.playlistEntries(media);
        checkEq("hls segment count", 3, segs.size());
        checkEq("hls segment order 1", "seg-1.ts", segs.get(0));
        checkEq("hls segment order 2", "sub/seg-2.ts", segs.get(1));
        checkEq("hls media not master", false, RecordingExport.isMasterPlaylist(media));

        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n"
                + "variant-low.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\n"
                + "variant-high.m3u8\n";
        check("hls master detected", RecordingExport.isMasterPlaylist(master));
        checkEq("hls master variants", 2,
                RecordingExport.playlistEntries(master).size());

        checkEq("hls clear has no method", null,
                RecordingExport.encryptionMethod(media));
        checkEq("hls METHOD=NONE is clear", null,
                RecordingExport.encryptionMethod(
                        "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\nseg.ts\n"));
        checkEq("hls AES refused by name", "AES-128",
                RecordingExport.encryptionMethod(
                        "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\nseg.ts\n"));

        checkEq("resolve relative", "http://h.test/a/sub/seg-2.ts",
                RecordingExport.resolveUri("http://h.test/a/pl.m3u8", "sub/seg-2.ts"));
        checkEq("resolve sibling", "http://h.test/a/seg-1.ts",
                RecordingExport.resolveUri("http://h.test/a/pl.m3u8", "seg-1.ts"));
        checkEq("resolve absolute kept", "https://cdn.example.test/seg-3.ts",
                RecordingExport.resolveUri("http://h.test/a/pl.m3u8",
                        "https://cdn.example.test/seg-3.ts"));

        checkEq("export base sanitizes", "http___x.test_live.m3u8",
                RecordingExport.exportBaseFor("http://x.test/live.m3u8"));
        String longId = RecordingExport.exportBaseFor(
                "http://very.long.example.test/" + "a".repeat(80) + ".m3u8");
        check("export base capped", longId.length() <= 60);
        check("export base deterministic",
                longId.equals(RecordingExport.exportBaseFor(
                        "http://very.long.example.test/" + "a".repeat(80) + ".m3u8")));
        checkEq("extension hls", ".ts", RecordingExport.extensionFor(true, "http://x/y.m3u8"));
        checkEq("extension mp4 with query", ".mp4",
                RecordingExport.extensionFor(false, "http://x/y.mp4?tok=1"));
        checkEq("extension unknown", ".bin",
                RecordingExport.extensionFor(false, "http://x/stream"));

        // span concatenation: three pieces become one file, in order
        File dir = new File(System.getProperty("java.io.tmpdir"), "v070concat");
        dir.mkdirs();
        List<File> parts = new ArrayList<>();
        byte[] want = new byte[7000];
        int pos = 0;
        int[] sizes = {1000, 3000, 3000};
        for (int p = 0; p < 3; p++) {
            byte[] chunkBytes = new byte[sizes[p]];
            for (int i = 0; i < chunkBytes.length; i++) {
                chunkBytes[i] = (byte) (p * 7 + i % 13);
                want[pos++] = chunkBytes[i];
            }
            File f = new File(dir, "part" + p + ".ts");
            Files.write(f.toPath(), chunkBytes);
            parts.add(f);
        }
        File out = new File(dir, "joined.ts");
        long written = RecordingExport.concatFiles(parts, out);
        checkEq("concat byte count", 7000L, written);
        check("concat bytes in order",
                Arrays.equals(want, Files.readAllBytes(out.toPath())));
    }

    // --------------------------------------- cooked record shape (rec cook)

    static void cookedRecordShape() throws Exception {
        // What a recordings cook must produce: the same family
        // record a file cook produces — lines with translations
        // AND originals, writable as SRT + cartridge JSON, and
        // readable back losslessly.
        List<CookedSubs.Line> lines = new ArrayList<>();
        CookedSubs.Line a = new CookedSubs.Line();
        a.startMs = 0; a.endMs = 2400; a.text = "Hello, world."; a.orig = "こんにちは、世界。";
        CookedSubs.Line b = new CookedSubs.Line();
        b.startMs = 3000; b.endMs = 5100; b.text = "It begins."; b.orig = "始まる。";
        lines.add(a); lines.add(b);
        String json = CookedSubs.writeJson("en", lines);
        check("record json is the family cartridge",
                json.contains("\"sandy.trsubs\""));
        List<CookedSubs.Line> back = CookedSubs.parseJson(json);
        checkEq("record round trip count", 2, back.size());
        checkEq("record round trip text", "Hello, world.", back.get(0).text);
        checkEq("record round trip original", "こんにちは、世界。", back.get(0).orig);
        checkEq("record round trip times", 5100L, back.get(1).endMs);
        String srt = CookedSubs.writeSrt(lines);
        check("record srt carries translations",
                srt.contains("Hello, world.") && srt.contains("It begins."));
        check("record srt has timecodes", srt.contains("00:00:03,000"));
    }

    // ------------------------------------------------------------ strings

    static void uiStrings() {
        check("uistrings all tables complete", UiStrings.tablesComplete());
        checkEq("uistrings table count", UiStrings.LANGUAGES.length,
                UiStrings.TABLES.size());
        checkEq("uistrings share key en", "Share to TV",
                UiStrings.lookup("en", "player_share_tv"));
        check("uistrings share key present in every table",
                UiStrings.keyIndex("player_share_tv") >= 0
                        && UiStrings.TABLES.values().stream().allMatch(
                                t -> t.length == UiStrings.KEYS.length
                                        && t[UiStrings.keyIndex("player_share_tv")] != null
                                        && !t[UiStrings.keyIndex("player_share_tv")].isEmpty()));
        checkEq("uistrings unknown language falls back to en", "Share to TV",
                UiStrings.lookup("xx", "player_share_tv"));
        checkEq("uistrings unknown key returns key", "no_such_key",
                UiStrings.lookup("en", "no_such_key"));
    }

    // ------------------------------------------------------------ books: text

    static byte[] epubFixture() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(bos);
        java.util.function.BiConsumer<String, String> put = (name, content) -> {
            try {
                z.putNextEntry(new java.util.zip.ZipEntry(name));
                z.write(content.getBytes("UTF-8"));
                z.closeEntry();
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        // Zip order deliberately scrambled: ch2 first. The SPINE rules.
        put.accept("OEBPS/ch2.xhtml", "<html><head><title>Wrong Title</title></head>"
                + "<body><p>Bravo one.</p><p>Bravo two!</p></body></html>");
        put.accept("META-INF/container.xml", "<?xml version=\"1.0\"?>"
                + "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/>"
                + "</rootfiles></container>");
        put.accept("OEBPS/ch1.xhtml", "<html><head><title>First</title></head>"
                + "<body><p>Alpha one.</p><p>Alpha two?</p></body></html>");
        put.accept("OEBPS/content.opf", "<?xml version=\"1.0\"?>"
                + "<package><manifest>"
                + "<item id=\"c1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "<item id=\"c2\" href=\"ch2.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "<item id=\"c3\" href=\"ch3.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
                + "</manifest><spine toc=\"ncx\">"
                + "<itemref idref=\"c1\"/><itemref idref=\"c2\"/><itemref idref=\"c3\"/>"
                + "</spine></package>");
        put.accept("OEBPS/toc.ncx", "<?xml version=\"1.0\"?>"
                + "<ncx><navMap>"
                + "<navPoint><navLabel><text>Chapter One</text></navLabel>"
                + "<content src=\"ch1.xhtml\"/></navPoint>"
                + "<navPoint><navLabel><text>Chapter Two</text></navLabel>"
                + "<content src=\"ch2.xhtml\"/></navPoint>"
                + "</navMap></ncx>");
        put.accept("OEBPS/ch3.xhtml", "<html><head><title>Third Chapter</title></head>"
                + "<body><p>Charlie one.</p></body></html>");
        z.close();
        return bos.toByteArray();
    }

    static void bookText() throws Exception {
        BookText.Result r = BookText.extract(epubFixture(), "fixture.epub");
        check("book epub extracts", r.ok);
        checkEq("book epub kind", "epub", r.kind);
        int ia = r.text.indexOf("Alpha one");
        int ib = r.text.indexOf("Bravo one");
        int ic = r.text.indexOf("Charlie one");
        check("book epub spine order (not zip order)", ia >= 0 && ib > ia && ic > ib);
        checkEq("book epub chapter count", 3, r.chapters.size());
        checkEq("book epub ch1 title from NCX", "Chapter One", r.chapters.get(0).title);
        checkEq("book epub ch2 title from NCX (beats <title>)", "Chapter Two",
                r.chapters.get(1).title);
        checkEq("book epub ch3 title falls to <title>", "Third Chapter",
                r.chapters.get(2).title);
        checkEq("book epub ch2 offset is where Bravo starts", ib, r.chapters.get(1).charOffset);
        check("book epub tags stripped", !r.text.contains("<p>"));

        BookText.Result t = BookText.extract(
                "Hello world. Plain and simple.".getBytes("UTF-8"), "note.txt");
        check("book txt extracts", t.ok && t.text.contains("Plain and simple"));
        checkEq("book txt kind", "txt", t.kind);

        byte[] u16 = "BOM book.".getBytes("UTF-16LE");
        byte[] withBom = new byte[u16.length + 2];
        withBom[0] = (byte) 0xFF; withBom[1] = (byte) 0xFE;
        System.arraycopy(u16, 0, withBom, 2, u16.length);
        checkEq("book txt utf16le bom", "BOM book.", BookText.decodeText(withBom));

        byte[] cp = "café fermé".getBytes("windows-1252");
        check("book txt cp1252 fallback", BookText.decodeText(cp).contains("café fermé"));

        // A header-only "PDF" has no text layer at all: the honest
        // verdict is the scanned-image line, named — never an errand.
        BookText.Result pdf = BookText.extract(
                "%PDF-1.4 scanned pages".getBytes("UTF-8"), "scan.pdf");
        check("book pdf without text layer refused", !pdf.ok);
        checkEq("book pdf scanned verdict named", BookText.SCANNED_NO_TEXT,
                pdf.failReason);

        // A real text-layer PDF reads at home (Addendum 7).
        byte[] pageContent = "BT /F1 12 Tf (Hello from the page) Tj ET"
                .getBytes("ISO-8859-1");
        byte[] flate = deflate(pageContent);
        String pdfDoc = "%PDF-1.4\n1 0 obj<</Type/Page>>endobj\n"
                + "2 0 obj<</Length " + flate.length
                + "/Filter/FlateDecode>>stream\n"
                + new String(flate, "ISO-8859-1")
                + "\nendstream endobj";
        BookText.Result pdfOk = BookText.extract(
                pdfDoc.getBytes("ISO-8859-1"), "real.pdf");
        check("book pdf text layer reads", pdfOk.ok);
        check("book pdf text layer words",
                pdfOk.text.contains("Hello from the page"));
        check("book pdf page chapter",
                pdfOk.chapters.size() >= 1
                        && "Page 1".equals(pdfOk.chapters.get(0).title));

        // CID fonts speak through their ToUnicode CMap.
        String cmap = "begincmap\n2 beginbfchar\n<0001> <0041>\n<0002> <0042>\n"
                + "endbfchar\nendcmap";
        String cidContent = "BT /F1 12 Tf <00010002> Tj ET";
        String cidDoc = "%PDF-1.4\n1 0 obj<</Type/Page>>endobj\n"
                + "3 0 obj<</Length " + cmap.length() + ">>stream\n" + cmap
                + "\nendstream endobj\n"
                + "4 0 obj<</Type/Font/Subtype/Type0/ToUnicode 3 0 R>>endobj\n"
                + "5 0 obj<</Resources<</Font<</F1 4 0 R>>>>>>endobj\n"
                + "2 0 obj<</Length " + cidContent.length() + ">>stream\n"
                + cidContent + "\nendstream endobj";
        BookText.Result cid = BookText.extract(
                cidDoc.getBytes("ISO-8859-1"), "cid.pdf");
        check("book pdf cid cmap words", cid.ok && cid.text.contains("AB"));

        BookText.Result empty = BookText.extract(new byte[0], "x.txt");
        check("book empty refused with reason",
                !empty.ok && BookText.NO_TEXT_FOUND.equals(empty.failReason));

        String stripped = BookText.htmlToText(
                "<p>One</p broken <p>Two &amp; three</p>".getBytes("UTF-8"));
        check("book html fallback stripper", stripped.contains("One")
                && stripped.contains("Two & three"));
    }

    static byte[] deflate(byte[] raw) throws Exception {
        java.util.zip.Deflater d = new java.util.zip.Deflater();
        d.setInput(raw);
        d.finish();
        byte[] buf = new byte[raw.length + 64];
        int n = d.deflate(buf);
        d.end();
        return java.util.Arrays.copyOf(buf, n);
    }

    /** Addendum 8: ONE intake — file in, text out, for every class a
     *  person would hand her; anything else is named, not faked. */
    static void intakeSuite() throws Exception {
        checkEq("intake kind pdf", BookText.KIND_PDF,
                BookText.kindOf("%PDF-1.7".getBytes("UTF-8"), "x.bin"));
        checkEq("intake kind rtf by sniff", BookText.KIND_RTF,
                BookText.kindOf("{\\rtf1 hi}".getBytes("UTF-8"), "x.bin"));
        checkEq("intake kind html by sniff", BookText.KIND_HTML,
                BookText.kindOf("<html><body>hi</body>".getBytes("UTF-8"), "x.bin"));
        checkEq("intake kind txt fallback", BookText.KIND_TXT,
                BookText.kindOf("just some words".getBytes("UTF-8"), "x.bin"));

        // DOCX built on the spot: word/document.xml with two paragraphs.
        java.io.ByteArrayOutputStream dbos = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream dz = new java.util.zip.ZipOutputStream(dbos);
        dz.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
        dz.write(("<w:document xmlns:w=\"urn:x\"><w:body>"
                + "<w:p><w:r><w:t>First para</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>Second para</w:t></w:r></w:p>"
                + "</w:body></w:document>").getBytes("UTF-8"));
        dz.closeEntry();
        dz.close();
        BookText.Result docx = BookText.extract(dbos.toByteArray(), "letter.docx");
        checkEq("intake kind docx by insides", BookText.KIND_DOCX, docx.kind);
        check("intake docx reads", docx.ok
                && docx.text.contains("First para")
                && docx.text.contains("Second para"));

        // ODT: content.xml with text:p paragraphs.
        java.io.ByteArrayOutputStream obos = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream oz = new java.util.zip.ZipOutputStream(obos);
        oz.putNextEntry(new java.util.zip.ZipEntry("content.xml"));
        oz.write(("<office:document-content xmlns:text=\"urn:y\">"
                + "<text:p>Odt words here</text:p>"
                + "</office:document-content>").getBytes("UTF-8"));
        oz.closeEntry();
        oz.close();
        BookText.Result odt = BookText.extract(obos.toByteArray(), "note.odt");
        checkEq("intake kind odt by insides", BookText.KIND_ODT, odt.kind);
        check("intake odt reads", odt.ok && odt.text.contains("Odt words here"));

        // RTF: groups skipped, \par breaks, \'e9 is é.
        BookText.Result rtf = BookText.extract(
                ("{\\rtf1\\ansi{\\fonttbl\\f0 Arial;}Hello\\par caf\\'e9}")
                        .getBytes("windows-1252"), "old.rtf");
        checkEq("intake kind rtf", BookText.KIND_RTF, rtf.kind);
        check("intake rtf reads", rtf.ok && rtf.text.contains("Hello")
                && rtf.text.contains("café"));

        // HTML by name.
        BookText.Result html = BookText.extract(
                "<p>Web words</p>".getBytes("UTF-8"), "page.html");
        checkEq("intake kind html", BookText.KIND_HTML, html.kind);
        check("intake html reads", html.ok && html.text.contains("Web words"));

        // An OLE binary (.doc) is outside the classes: NAMED, not faked.
        byte[] ole = new byte[] { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                0, 0, 0, 0, 0, 0, 0, 0 };
        BookText.Result no = BookText.extract(ole, "old.doc");
        checkEq("intake kind ole unsupported", BookText.KIND_UNSUPPORTED, no.kind);
        check("intake unsupported named", !no.ok
                && BookText.UNSUPPORTED_KIND.equals(no.failReason));

        // PdfText counts pages by their /Type objects.
        PdfText.Extract px = PdfText.extract(
                "%PDF-1.4 scanned pages".getBytes("UTF-8"));
        checkEq("pdftext no streams no text", 0, px.textChars);
    }

    /** Addenda 9–11: the tolerant episode matcher, tiered truth,
     *  no silent guesses, verified-only playable counts, and the
     *  re-verification priority order. */
    static void episodeMatchSuite() throws Exception {
        String show = "Dragnet";
        // ---- parsing across the naming schemes
        EpisodeMatch.Parsed p1 = EpisodeMatch.parse(
                "Dragnet S01E01 The Big September Man.mp4", show);
        check("em parse SxxExx", p1.season == 1 && p1.episode == 1);
        EpisodeMatch.Parsed p2 = EpisodeMatch.parse(
                "dragnet.1x02.the.big.cast.mkv", show);
        check("em parse NxNN", p2.season == 1 && p2.episode == 2);
        EpisodeMatch.Parsed p3 = EpisodeMatch.parse(
                "Dragnet - Season 1 Episode 3 - The Big Crime.mp4", show);
        check("em parse words", p3.season == 1 && p3.episode == 3);
        EpisodeMatch.Parsed p4 = EpisodeMatch.parse("Dragnet 1951-12-21.mp4", show);
        checkEq("em parse airdate iso", "1951-12-21", p4.airdate);
        check("em airdate explains its day", p4.bareNumber < 0);
        EpisodeMatch.Parsed p5 = EpisodeMatch.parse("Dragnet 21.12.1951.mp4", show);
        checkEq("em parse airdate dmy", "1951-12-21", p5.airdate);
        EpisodeMatch.Parsed p6 = EpisodeMatch.parse("Dragnet 3.mp4", show);
        check("em parse bare number", p6.episode < 0 && p6.bareNumber == 3);
        EpisodeMatch.Parsed p0 = EpisodeMatch.parse("Dragnet S00E01 Special.mp4", show);
        check("em specials stay season 0", p0.season == 0 && p0.episode == 1);

        java.util.List<EpisodeMatch.EpisodeRef> eps = java.util.Arrays.asList(
                new EpisodeMatch.EpisodeRef(1, 1, "The Big September Man", "1951-12-14", 1560),
                new EpisodeMatch.EpisodeRef(1, 2, "The Big Cast", "1951-12-21", 1560),
                new EpisodeMatch.EpisodeRef(1, 3, "The Big Crime", "1951-12-28", 1500));

        // ---- scoring: conflict is a veto, not a shrug
        EpisodeMatch.Scored good = EpisodeMatch.score(p1, eps.get(0));
        check("em numbers+title strong", good.score >= 80 && !good.conflict);
        EpisodeMatch.Scored wrong = EpisodeMatch.score(
                EpisodeMatch.parse("Dragnet S01E02 The Big Crime.mp4", show), eps.get(2));
        check("em mislabel conflicts", wrong.conflict && wrong.score < 0);
        EpisodeMatch.Scored spec = EpisodeMatch.score(p0, eps.get(0));
        check("em specials not promoted to S1", spec.conflict);
        checkEq("em duration corroborates", 10,
                EpisodeMatch.durationDelta(1560, 1560));
        checkEq("em duration contradicts", -15,
                EpisodeMatch.durationDelta(300, 1560));
        checkEq("em duration unknown is silent", 0,
                EpisodeMatch.durationDelta(0, 1560));

        // ---- tiers: doubt is never rounded up
        checkEq("em tier resolved", EpisodeMatch.TIER_RESOLVED,
                EpisodeMatch.tierFor(80, 40, false));
        checkEq("em tier thin margin is mostly", EpisodeMatch.TIER_MOSTLY,
                EpisodeMatch.tierFor(80, 5, false));
        checkEq("em tier mostly", EpisodeMatch.TIER_MOSTLY,
                EpisodeMatch.tierFor(50, 40, false));
        checkEq("em tier close", EpisodeMatch.TIER_CLOSE,
                EpisodeMatch.tierFor(20, 99, false));
        checkEq("em tier conflict caps at close", EpisodeMatch.TIER_CLOSE,
                EpisodeMatch.tierFor(90, 90, true));
        checkEq("em tier unmatched", EpisodeMatch.TIER_UNMATCHED,
                EpisodeMatch.tierFor(0, 0, false));

        // ---- assignment: union dedupes by episode identity
        java.util.List<EpisodeMatch.Candidate> cands = java.util.Arrays.asList(
                new EpisodeMatch.Candidate("Dragnet S01E01 The Big September Man.mp4", 1560),
                new EpisodeMatch.Candidate("dragnet.1x02.the.big.cast.mkv", 0),
                new EpisodeMatch.Candidate("Dragnet - Season 1 Episode 3 - The Big Crime.mp4", 0),
                new EpisodeMatch.Candidate("Dragnet 1951-12-21.mp4", 0),
                new EpisodeMatch.Candidate("Dragnet S01E02 The Big Crime WRONG.mp4", 0),
                new EpisodeMatch.Candidate("Dragnet 3.mp4", 0),
                new EpisodeMatch.Candidate("Random Garbage Movie 1999.mp4", 0));
        EpisodeMatch.Assignment a = EpisodeMatch.matchAll(eps, cands, show);
        checkEq("em every episode bound once", 3, a.byEpisode.size());
        checkEq("em E1 takes the numbered file", 0,
                a.byEpisode.get(0).candidateIdx);
        checkEq("em E2 takes the true 1x02 over the mislabel", 1,
                a.byEpisode.get(1).candidateIdx);
        checkEq("em E1 resolved tier", EpisodeMatch.TIER_RESOLVED,
                a.byEpisode.get(0).tier);
        boolean penHonest = true;
        for (EpisodeMatch.Binding b : a.unbound) {
            if (b.tier == EpisodeMatch.TIER_RESOLVED
                    || b.tier == EpisodeMatch.TIER_MOSTLY) penHonest = false;
            if (b.reason == null || b.reason.isEmpty()) penHonest = false;
        }
        check("em pen holds only maybes, each with a reason",
                penHonest && a.unbound.size() == 4);
        EpisodeMatch.Binding garbage = null;
        for (EpisodeMatch.Binding b : a.unbound) {
            if (b.candidateIdx == 6) garbage = b;
        }
        check("em garbage unmatched with its reason", garbage != null
                && garbage.tier == EpisodeMatch.TIER_UNMATCHED
                && garbage.reason.contains("no episode evidence"));

        // ---- playable count: RESOLVED + verified-live only
        cands.get(0).verified = true;
        int[] pc = EpisodeMatch.playableCount(a, cands, 3);
        check("em playable counts only resolved+verified",
                pc[0] == 1 && pc[1] == 3);
        checkEq("em playable line words", "Playable: 1 of 3 episodes",
                String.format(UiStrings.lookup("en", "show_playable_count"), 1, 3));

        // ---- re-verification priority (Addendum 10(5))
        checkEq("vp kept first class", VerifyPriority.CAT_KEPT,
                VerifyPriority.category(true, true, true));
        checkEq("vp favorite class", VerifyPriority.CAT_FAVORITE,
                VerifyPriority.category(false, true, true));
        checkEq("vp recent class", VerifyPriority.CAT_RECENT,
                VerifyPriority.category(false, false, true));
        java.util.List<VerifyPriority.Entry> q = VerifyPriority.order(
                java.util.Arrays.asList(
                        new VerifyPriority.Entry("shelf-stale", "", VerifyPriority.CAT_SHELF, 100),
                        new VerifyPriority.Entry("fav-new", "", VerifyPriority.CAT_FAVORITE, 0),
                        new VerifyPriority.Entry("kept-stale", "", VerifyPriority.CAT_KEPT, 500),
                        new VerifyPriority.Entry("fav-stale", "", VerifyPriority.CAT_FAVORITE, 200),
                        new VerifyPriority.Entry("shelf-new", "", VerifyPriority.CAT_SHELF, 0)),
                0);
        checkEq("vp order 1 kept", "kept-stale", q.get(0).url);
        checkEq("vp order 2 stale favorite beats new favorite", "fav-stale", q.get(1).url);
        checkEq("vp order 3 new favorite", "fav-new", q.get(2).url);
        checkEq("vp order 4 shelf stale", "shelf-stale", q.get(3).url);
        checkEq("vp order 5 shelf new last", "shelf-new", q.get(4).url);
        checkEq("vp cap holds", 2, VerifyPriority.order(q, 2).size());

        // ---- wiring: surfaces consult the matcher and the order
        String classesDir = System.getProperty("sandy.classes");
        byte[] srcCls = Files.readAllBytes(new File(classesDir,
                "com/talacore/talatv/SourcesActivity.class").toPath());
        String srcB = new String(srcCls, "ISO-8859-1");
        check("em sources grade tiers", srcB.contains("gradeEpisodeSources")
                && srcB.contains("EpisodeMatch"));
        check("em sources consult the tier map for autoplay",
                srcB.contains("epTiers") && srcB.contains("appendTierLine"));
        byte[] fvCls = Files.readAllBytes(new File(classesDir,
                "com/talacore/talatv/FreshVerify.class").toPath());
        check("vp freshverify walks the priority order",
                new String(fvCls, "ISO-8859-1").contains("VerifyPriority"));
    }

    // ------------------------------------------------------------ books: chunks

    static void bookChunker() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("Sentence number ").append(i)
              .append(" is here to be spoken aloud. ");
        }
        String en = sb.toString().trim();
        List<BookChunker.Chunk> chunks = BookChunker.chunk(en, null);
        check("chunker produces chunks", chunks.size() >= 3);
        boolean allFit = true, allEndSentence = true;
        for (BookChunker.Chunk c : chunks) {
            if (c.text.length() > BookChunker.MAX) allFit = false;
            char last = c.text.charAt(c.text.length() - 1);
            if (last != '.' && last != '!' && last != '?') allEndSentence = false;
        }
        check("chunker respects MAX", allFit);
        check("chunker breaks on sentences", allEndSentence);

        // spaceless run: hard split at the ceiling, nothing lost
        char[] run = new char[2000];
        Arrays.fill(run, 'a');
        List<BookChunker.Chunk> hard = BookChunker.chunk(new String(run), null);
        int sum = 0;
        boolean hardFit = true;
        for (BookChunker.Chunk c : hard) {
            sum += c.text.length();
            if (c.text.length() > BookChunker.MAX) hardFit = false;
        }
        check("chunker hard-split fits", hardFit);
        checkEq("chunker hard-split loses nothing", 2000, sum);

        // Japanese terminators
        StringBuilder ja = new StringBuilder();
        for (int i = 0; i < 150; i++) ja.append("これは文です。");
        List<BookChunker.Chunk> jc = BookChunker.chunk(ja.toString(), null);
        check("chunker ja terminators", jc.size() >= 2
                && jc.get(0).text.endsWith("。"));

        // chapters ride along + bookmark math
        StringBuilder bp1 = new StringBuilder();
        for (int i = 0; i < 30; i++) bp1.append("First part sentence here. ");
        StringBuilder bp2 = new StringBuilder();
        for (int i = 0; i < 30; i++) bp2.append("Second part sentence here. ");
        String part1 = bp1.toString().trim();
        String part2 = bp2.toString().trim();
        String book = part1 + "\n\n" + part2;
        List<BookText.Chapter> chapters = Arrays.asList(
                new BookText.Chapter("Opening", 0),
                new BookText.Chapter("Middle", part1.length() + 2));
        List<BookChunker.Chunk> cc = BookChunker.chunk(book, chapters);
        checkEq("chunker chapter of first chunk", "Opening", cc.get(0).chapterTitle);
        checkEq("chunker chapterAt last", "Middle",
                BookChunker.chapterAt(cc, cc.size() - 1));
        // Chunks pack across paragraph seams by design (TARGET-driven),
        // so the owning chunk of an offset is the one whose startChar
        // brackets it — assert containment, not chapter alignment.
        int off = part1.length() + 3;
        int owner = BookChunker.chunkIndexAt(cc, off);
        boolean contained = cc.get(owner).startChar <= off
                && (owner + 1 >= cc.size() || cc.get(owner + 1).startChar > off);
        check("chunkIndexAt lands on the owning chunk", contained);
        checkEq("chunkIndexAt at a chunk start is that chunk",
                cc.size() - 1,
                BookChunker.chunkIndexAt(cc, cc.get(cc.size() - 1).startChar));
        checkEq("chunkIndexAt start", 0, BookChunker.chunkIndexAt(cc, 0));
        double p0 = BookChunker.progress(cc, 0, book.length());
        double p1 = BookChunker.progress(cc, cc.size() - 1, book.length());
        check("chunker progress sane", p0 == 0.0 && p1 > 0.0 && p1 <= 1.0);
    }

    static void bookLang() {
        checkEq("lang kana is ja", BookLang.JA,
                BookLang.detect("これはテストです。漢字もまじります。"));
        checkEq("lang han-only is zh", BookLang.ZH,
                BookLang.detect("这是中文文本，没有假名。"));
        checkEq("lang hangul is ko", BookLang.KO,
                BookLang.detect("이것은 한국어 문장입니다."));
        checkEq("lang latin is other", BookLang.OTHER,
                BookLang.detect("A plain English paragraph."));
        checkEq("lang empty is other", BookLang.OTHER, BookLang.detect(""));
    }

    // ------------------------------------------------------------ books: session FSM

    static void readingSession() {
        ReadingSession s = new ReadingSession();
        check("fsm starts idle", s.state() == ReadingSession.State.IDLE);
        check("fsm pause from idle refused", !s.pause());
        check("fsm start with no chunks refused", !s.start(0, 0));
        check("fsm start", s.start(10, 3));
        check("fsm speaking at start chunk",
                s.isSpeaking() && s.currentChunk() == 3);
        check("fsm resume while speaking refused", !s.resume());
        check("fsm pause", s.pause() && s.state() == ReadingSession.State.PAUSED);
        check("fsm place kept through pause", s.currentChunk() == 3);
        check("fsm resume", s.resume() && s.isSpeaking());
        check("fsm starve to waiting", s.starve()
                && s.state() == ReadingSession.State.WAITING);
        check("fsm pause while waiting", s.pause()
                && s.state() == ReadingSession.State.PAUSED);
        check("fsm resume back to speaking", s.resume() && s.isSpeaking());
        check("fsm starve again", s.starve());
        check("fsm feed from waiting", s.feed() && s.isSpeaking());
        check("fsm seek moves", s.seek(7) && s.currentChunk() == 7);
        check("fsm chunkStarted tracks furthest", s.chunkStarted(8)
                && s.furthestChunk() == 8 && s.currentChunk() == 8);
        check("fsm seek clamps to book", s.seek(99) && s.currentChunk() == 9);
        check("fsm finish", s.finish()
                && s.state() == ReadingSession.State.FINISHED);
        check("fsm restart after finish", s.start(4, 0) && s.isSpeaking());
        check("fsm sleep stop", s.stop()
                && s.state() == ReadingSession.State.STOPPED);
        check("fsm stop from idle refused", !new ReadingSession().stop());
    }

    // ------------------------------------------------------------ books: loop guard

    static void mtLoopGuard() {
        MtLoopGuard g = new MtLoopGuard();
        checkEq("guard normalize", "hello world",
                MtLoopGuard.normalize("Hello,  World!"));
        check("guard first rendering kept", g.feed("源文その一", "The same line.")
                == MtLoopGuard.Verdict.KEEP);
        check("guard second distinct source kept", g.feed("源文その二", "The same line.")
                == MtLoopGuard.Verdict.KEEP);
        check("guard third distinct source is a loop", g.feed("源文その三", "The same line.")
                == MtLoopGuard.Verdict.DROP_LOOP);
        checkEq("guard counts the drop", 1, g.dropped());
        check("guard fourth also dropped", g.feed("源文その四", "The same line.")
                == MtLoopGuard.Verdict.DROP_LOOP);
        checkEq("guard count grows", 2, g.dropped());
        check("guard same source repeated is a refrain, kept",
                g.feed("源文その一", "The same line.") == MtLoopGuard.Verdict.KEEP);
        check("guard different target kept", g.feed("源文その五", "A different line.")
                == MtLoopGuard.Verdict.KEEP);
        g.reset();
        checkEq("guard reset clears count", 0, g.dropped());
        check("guard reset re-keeps", g.feed("源文その三", "The same line.")
                == MtLoopGuard.Verdict.KEEP);
    }

    static void translatorBlocks() {
        List<String> short1 = BookTranslator.blocks("One short sentence.");
        checkEq("blocks short is one", 1, short1.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("A medium sentence to translate. ");
        boolean fit = true;
        List<String> many = BookTranslator.blocks(sb.toString());
        for (String b : many) if (b.length() > BookTranslator.BLOCK) fit = false;
        check("blocks all fit the cap", fit && many.size() >= 3);
        StringBuilder jab = new StringBuilder();
        for (int i = 0; i < 100; i++) jab.append("これは翻訳の文です。");
        List<String> jblocks = BookTranslator.blocks(jab.toString());
        check("blocks ja sentences stay whole", jblocks.size() >= 2
                && jblocks.get(0).endsWith("。"));
        char[] run = new char[1000];
        Arrays.fill(run, 'x');
        List<String> hard = BookTranslator.blocks(new String(run));
        boolean hfit = true;
        for (String b : hard) if (b.length() > BookTranslator.BLOCK) hfit = false;
        check("blocks hard-split a giant sentence", hfit && hard.size() >= 3);
    }

    // ------------------------------------------------------------ search pin (mandated)

    /**
     * The check that would have caught a dead search: the REAL
     * pipeline MainActivity runs — the name map (parseMapTsv ->
     * setMap -> applyTo) over a fixture directory, then the exact
     * filter MainActivity applies (query blank -> all; else
     * ChannelNames.matchesQuery on the lowercased trimmed query),
     * including finding a channel by the CANON name she sees when
     * its raw playlist name is junk.
     */
    static void searchPin() throws Exception {
        String mapTsv = "animax\tAnimax Asia\thttp://img/animax.png\tAnimation\n"
                + "newsx\tWorld News Now\thttp://img/news.png\tNews\n";
        ChannelNames.setMap(ChannelNames.parseMapTsv(mapTsv));

        M3uParser.Channel junk = new M3uParser.Channel();
        junk.name = "ANIMAX [720p] (backup)";
        junk.url = "http://streams.example/animax";
        junk.group = "Animation";
        junk.canonName = "Animax Asia";
        ChannelNames.applyTo(junk);
        M3uParser.Channel plain = new M3uParser.Channel();
        plain.name = "Cooking Corner";
        plain.url = "http://streams.example/cooking";
        plain.group = "Lifestyle";
        ChannelNames.applyTo(plain);
        List<M3uParser.Channel> dir = Arrays.asList(junk, plain);

        checkEq("searchpin display name is the canon name", "Animax Asia",
                junk.displayName());
        checkEq("searchpin canon name finds junk-named channel", 1,
                filterCount(dir, "  ANIMAX "));
        checkEq("searchpin raw name still finds it", 1, filterCount(dir, "720p"));
        checkEq("searchpin group finds it", 1, filterCount(dir, "lifestyle"));
        checkEq("searchpin other channel by name", 1, filterCount(dir, "cooking"));
        checkEq("searchpin no match is empty, not everything", 0,
                filterCount(dir, "zzz-no-such"));
        checkEq("searchpin blank query shows all", 2, filterCount(dir, ""));
    }

    static int filterCount(List<M3uParser.Channel> dir, String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase(
                java.util.Locale.US);
        int n = 0;
        for (M3uParser.Channel c : dir) {
            if (q.isEmpty() || ChannelNames.matchesQuery(c, q)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------ cold start (mandated)

    /**
     * The launcher's initialization order, as far as the JVM
     * harness can reach: the crash handler writes its evidence file
     * in the format the field will send back (version first), the
     * entry classes all load, and the player no longer references
     * share discovery at all (it starts inside ShareCast when the
     * share sheet asks — method-scoped proof is the wire model's
     * pre-ship check, run on the shipped dex).
     */
    static void coldStart() throws Exception {
        File dir = Files.createTempDirectory("crashguard").toFile();
        java.lang.reflect.Method w = CrashGuard.class.getDeclaredMethod(
                "writeCrash", File.class, String.class, Throwable.class);
        w.setAccessible(true);
        w.invoke(null, dir, "harness probe",
                new IllegalStateException("probe failure 080"));
        File[] logs = dir.listFiles((d, n) -> n.startsWith("crash-") && n.endsWith(".txt"));
        check("coldstart crash file written", logs != null && logs.length == 1);
        if (logs != null && logs.length == 1) {
            String body = new String(Files.readAllBytes(logs[0].toPath()), "UTF-8");
            String first = body.split("\n")[0];
            checkEq("coldstart version is the first line",
                    "Sandy 0.18.0 (versionCode 24)", first);
            check("coldstart stack is in the file",
                    body.contains("IllegalStateException")
                            && body.contains("probe failure 080"));
        }
        boolean loaded = true;
        for (String cls : new String[]{
                "com.talacore.talatv.SplashActivity",
                "com.talacore.talatv.HomeActivity",
                "com.talacore.talatv.MainActivity",
                "com.talacore.talatv.PlayerActivity",
                "com.talacore.talatv.RecordingsActivity",
                "com.talacore.talatv.BooksActivity",
                "com.talacore.talatv.ReaderActivity",
                "com.talacore.talatv.ReaderService",
                "com.talacore.talatv.FilesActivity",
                "com.talacore.talatv.WallListActivity",
                "com.talacore.talatv.CountryIndexActivity",
                "com.talacore.talatv.GuideActivity",
                "com.talacore.talatv.TimersActivity",
                "com.talacore.talatv.LanguageIndexActivity",
                "com.talacore.talatv.TimerRecordService",
                "com.talacore.talatv.TimerReceiver",
                "com.talacore.talatv.DubActivity",
                "com.talacore.talatv.VoiceCoverageActivity",
                "com.talacore.talatv.CreditActivity",
                "com.talacore.talatv.StoryActivity",
                "com.talacore.talatv.StoryShelfActivity",
                "com.talacore.talatv.StoryTellActivity",
                "com.talacore.talatv.StoryMakeActivity",
                "com.talacore.talatv.StoryPlayActivity",
                "com.talacore.talatv.StoryRecordActivity",
                "com.talacore.talatv.JournalsActivity",
                "com.talacore.talatv.GrownUpGateActivity",
                "com.talacore.talatv.SpeakActivity",
                "com.talacore.talatv.ListenActivity",
                "com.talacore.talatv.TranslatorActivity",
                "com.talacore.talatv.VoiceSpeaker",
                "com.talacore.talatv.EarCapture"}) {
            try {
                Class.forName(cls, false, V180Test.class.getClassLoader());
            } catch (Throwable t) {
                loaded = false;
                System.out.println("FAIL detail: cannot load " + cls + ": " + t);
            }
        }
        check("coldstart entry classes load", loaded);

        String classesDir = System.getProperty("sandy.classes");
        if (classesDir != null) {
            byte[] player = Files.readAllBytes(new File(classesDir,
                    "com/talacore/talatv/PlayerActivity.class").toPath());
            check("coldstart player never references share discovery",
                    !new String(player, "ISO-8859-1").contains("startDiscovery"));
            check("coldstart player carries the local set (v0.9.0)",
                    new String(player, "ISO-8859-1").contains("LocalFiles"));
            byte[] splash = Files.readAllBytes(new File(classesDir,
                    "com/talacore/talatv/SplashActivity.class").toPath());
            check("coldstart launcher installs the crash guard",
                    new String(splash, "ISO-8859-1").contains("CrashGuard"));
        }
    }

    static void uiStringsReader() {
        checkEq("uistrings keys grew to 420", 420, UiStrings.KEYS.length);
        check("uistrings reader keys complete in all tables",
                UiStrings.tablesComplete());
        for (String k : new String[]{"share_unavailable", "lib_books_btn",
                "books_title", "books_empty", "books_add_folder", "books_add_file",
                "books_rescan", "reader_pick_up", "reader_start_over",
                "reader_finished", "reader_play", "reader_pause", "reader_back",
                "reader_forward", "reader_speed", "reader_voice",
                "reader_read_in_english", "book_scanned", "book_unsupported",
                "lib_videos_btn", "files_title"}) {
            check("uistrings key present: " + k, UiStrings.keyIndex(k) >= 0);
        }
        check("uistrings key present: wall_available", UiStrings.keyIndex("wall_available") >= 0);
        check("uistrings key present: wall_catalog_only", UiStrings.keyIndex("wall_catalog_only") >= 0);
        checkEq("uistrings wall_available es", "En tus estantes",
                UiStrings.lookup("es", "wall_available"));
        checkEq("uistrings wall_catalog_only ja", "カタログのみ",
                UiStrings.lookup("ja", "wall_catalog_only"));
        checkEq("uistrings reader_play ja", "読んで",
                UiStrings.lookup("ja", "reader_play"));
        checkEq("uistrings book_scanned en",
                "This book is scanned pictures — it has no text layer Sandy can read yet.",
                UiStrings.lookup("en", "book_scanned"));
        checkEq("uistrings book_unsupported en",
                "Sandy can't read this kind of file yet.",
                UiStrings.lookup("en", "book_unsupported"));
        check("uistrings ripper deferral retired",
                UiStrings.keyIndex("reader_needs_text") < 0);
    }

    static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        f.delete();
    }

    // ------------------------------------------------------------ local files (v0.9.0)

    static LocalFiles.Row row(String uri, String title, LocalFiles.Origin o) {
        LocalFiles.Row r = new LocalFiles.Row();
        r.uri = uri; r.title = title; r.origin = o;
        return r;
    }

    static void localFiles() {
        // --- shelf merge: door order, dedupe, A->Z inside a door
        List<LocalFiles.Row> downloads = Arrays.asList(
                row("/dl/Zebra.mp4", "Zebra.mp4", LocalFiles.Origin.DOWNLOADS),
                row("/dl/Apple.mp4", "Apple.mp4", LocalFiles.Origin.DOWNLOADS));
        List<LocalFiles.Row> device = Arrays.asList(
                row("content://media/9", "Mango.mp4", LocalFiles.Origin.DEVICE),
                row("/dl/Apple.mp4", "Apple.mp4", LocalFiles.Origin.DEVICE),
                row("content://media/3", "banana.mkv", LocalFiles.Origin.DEVICE));
        List<LocalFiles.Row> folder = Arrays.asList(
                row("content://tree/1", "Cherry.mp4", LocalFiles.Origin.FOLDER),
                row("", "ghost.mp4", LocalFiles.Origin.FOLDER));
        List<LocalFiles.Row> shelf = LocalFiles.mergeShelves(downloads, device, folder);
        checkEq("local shelf size (dedupe + ghost dropped)", 5, shelf.size());
        checkEq("local shelf downloads first, A->Z", "Apple.mp4", shelf.get(0).title);
        checkEq("local shelf dedupe keeps the earlier door",
                LocalFiles.Origin.DOWNLOADS, shelf.get(0).origin);
        checkEq("local shelf device door second", "banana.mkv", shelf.get(2).title);
        checkEq("local shelf folder door last", "Cherry.mp4", shelf.get(4).title);
        checkEq("local shelf null doors tolerated", 0,
                LocalFiles.mergeShelves(null, null, null).size());

        // --- the library index feeds the same model: entry -> channel -> key
        LibraryIndex.Entry e = LibraryIndex.fromFile(
                "Firefly S01E01 Serenity.mkv", "content://media/42");
        M3uParser.Channel ch = LibraryIndex.toChannel(e);
        checkEq("local key is namespaced", "local:content://media/42",
                LocalFiles.keyFor(ch.url));
        checkEq("local key from row matches", LocalFiles.keyFor(ch.url),
                LocalFiles.keyFor(row("content://media/42", "x", LocalFiles.Origin.DEVICE)));
        check("local keys differ per file",
                !LocalFiles.keyFor("content://a").equals(LocalFiles.keyFor("content://b")));
        checkEq("local key of nothing", "local:", LocalFiles.keyFor((String) null));

        // --- resume policy
        check("resume: nothing saved", !LocalFiles.ResumePolicy.shouldOffer(0, -1));
        check("resume: at the floor", !LocalFiles.ResumePolicy.shouldOffer(10_000, -1));
        check("resume: past the floor", LocalFiles.ResumePolicy.shouldOffer(10_001, -1));
        check("resume: mid-file", LocalFiles.ResumePolicy.shouldOffer(50_000, 3_600_000));
        check("resume: inside end margin is not an offer",
                !LocalFiles.ResumePolicy.shouldOffer(3_590_000, 3_600_000));
        check("resume: finished at margin", LocalFiles.ResumePolicy.isFinished(3_590_000, 3_600_000));
        check("resume: not finished mid-file", !LocalFiles.ResumePolicy.isFinished(50_000, 3_600_000));
        check("resume: unknown duration, never finished",
                !LocalFiles.ResumePolicy.isFinished(50_000, -1));
        checkEq("resume: clamp past end", 3_000L, LocalFiles.ResumePolicy.clamp(5_000, 3_000));
        checkEq("resume: clamp negative", 0L, LocalFiles.ResumePolicy.clamp(-5, -1));
        checkEq("resume: clamp untouched", 100L, LocalFiles.ResumePolicy.clamp(100, -1));

        // --- opened-set arithmetic
        checkEq("set: next mid", 1, LocalFiles.nextIndex(0, 3));
        checkEq("set: next at end", -1, LocalFiles.nextIndex(2, 3));
        checkEq("set: next empty", -1, LocalFiles.nextIndex(0, 0));
        checkEq("set: prev mid", 0, LocalFiles.prevIndex(1, 3));
        checkEq("set: prev at start", -1, LocalFiles.prevIndex(0, 3));
        checkEq("set: prev out of range", -1, LocalFiles.prevIndex(3, 3));

        // --- speed cycle
        checkEq("speed: six stops", 6, LocalFiles.SPEEDS.length);
        check("speed: 1x -> 1.25x", LocalFiles.nextSpeed(1.0f) == 1.25f);
        check("speed: wraps at the top", LocalFiles.nextSpeed(2.0f) == 0.5f);
        check("speed: unknown snaps to nearest first", LocalFiles.nextSpeed(0.9f) == 1.25f);
        checkEq("speed: label whole", "1×", LocalFiles.speedLabel(1.0f));
        checkEq("speed: label half", "0.5×", LocalFiles.speedLabel(0.5f));
        checkEq("speed: label quarter", "1.25×", LocalFiles.speedLabel(1.25f));

        // --- clock + size
        checkEq("clock: zero", "0:00", LocalFiles.formatClock(0));
        checkEq("clock: negative clamps", "0:00", LocalFiles.formatClock(-5));
        checkEq("clock: minutes", "1:01", LocalFiles.formatClock(61_000));
        checkEq("clock: hours", "1:01:01", LocalFiles.formatClock(3_661_000));
        checkEq("size: unknown blank", "", LocalFiles.formatSize(-1));
        checkEq("size: bytes", "512 B", LocalFiles.formatSize(512));
        checkEq("size: KB", "2 KB", LocalFiles.formatSize(2048));
        checkEq("size: MB", "5.0 MB", LocalFiles.formatSize(5L * 1024 * 1024));
        checkEq("size: GB", "2.00 GB", LocalFiles.formatSize(2L * 1024 * 1024 * 1024));
    }

    // ------------------------------------------------- catalog index (v0.10.0)

    static CatalogIndex.Record rec(int id, String name, String lang,
                                   String country, double rating, String premiered) {
        CatalogIndex.Record r = new CatalogIndex.Record();
        r.id = id; r.name = name; r.language = lang; r.countryName = country;
        r.rating = rating; r.premiered = premiered;
        return r;
    }

    static void catalogIndex() throws Exception {
        // --- country parsing: network first, webChannel second, else empty
        String json = "["
                + "{\"id\":1,\"name\":\"Show A\",\"language\":\"Spanish\",\"rating\":{\"average\":8.0},"
                + "\"network\":{\"name\":\"Antena\",\"country\":{\"name\":\"Spain\",\"code\":\"ES\"}},\"genres\":[\"Drama\"]},"
                + "{\"id\":2,\"name\":\"Show B\",\"language\":\"Spanish\","
                + "\"webChannel\":{\"name\":\"Netflix\",\"country\":{\"name\":\"Mexico\",\"code\":\"MX\"}}},"
                + "{\"id\":3,\"name\":\"Show C\",\"language\":\"Japanese\","
                + "\"webChannel\":{\"name\":\"WebOnly\"}},"
                + "{\"id\":4,\"name\":\"Show D\",\"language\":\"Portuguese\","
                + "\"network\":{\"name\":\"Globo\",\"country\":{\"name\":\"Brazil\",\"code\":\"BR\"}}},"
                + "{\"id\":5,\"name\":\"Show E\",\"language\":\"Portuguese\","
                + "\"network\":{\"name\":\"RTP\",\"country\":{\"name\":\"Portugal\",\"code\":\"PT\"}}},"
                + "{\"id\":6,\"name\":\"Show F\"}"
                + "]";
        List<TvMaze.Show> shows = TvMaze.parseShows(json);
        checkEq("maze: parsed 6", 6, shows.size());
        checkEq("maze: network country", "Spain", shows.get(0).countryName);
        checkEq("maze: webchannel country", "Mexico", shows.get(1).countryName);
        checkEq("maze: web-only country honestly empty", "", shows.get(2).countryName);
        checkEq("maze: country code", "BR", shows.get(3).countryCode);

        Map<Integer, CatalogIndex.Record> gathered = new java.util.LinkedHashMap<>();
        CatalogIndex.mergeInto(gathered, shows);
        checkEq("index: merged 6", 6, gathered.size());
        CatalogIndex.mergeInto(gathered, shows); // the same page walked twice
        checkEq("index: merge dedupes by id", 6, gathered.size());
        List<CatalogIndex.Record> all = new ArrayList<>(gathered.values());

        // --- bucketing by the show's own language
        checkEq("index: spanish bucket", 2, CatalogIndex.bucketOf(all, "Spanish").size());
        checkEq("index: portuguese bucket", 2, CatalogIndex.bucketOf(all, "Portuguese").size());
        checkEq("index: unlisted-language bucket", 1, CatalogIndex.bucketOf(all, "").size());
        checkEq("index: absent language is empty, not padded",
                0, CatalogIndex.bucketOf(all, "Korean").size());

        // --- shelves: variants stay separate; home first; web last
        java.util.LinkedHashMap<String, List<CatalogIndex.Record>> es =
                CatalogIndex.shelves(CatalogIndex.bucketOf(all, "Spanish"), "United States");
        checkEq("shelves: Spain and Mexico are two shelves", 2, es.size());
        check("shelves: A-Z without a home match",
                new ArrayList<>(es.keySet()).equals(Arrays.asList("Mexico", "Spain")));
        java.util.LinkedHashMap<String, List<CatalogIndex.Record>> esHome =
                CatalogIndex.shelves(CatalogIndex.bucketOf(all, "Spanish"), "Spain");
        checkEq("shelves: home country first", "Spain",
                esHome.keySet().iterator().next());
        java.util.LinkedHashMap<String, List<CatalogIndex.Record>> ja =
                CatalogIndex.shelves(CatalogIndex.bucketOf(all, "Japanese"), "");
        checkEq("shelves: web-only show sits on the web shelf",
                CatalogIndex.COUNTRY_WEB, ja.keySet().iterator().next());
        java.util.LinkedHashMap<String, List<CatalogIndex.Record>> pt =
                CatalogIndex.shelves(CatalogIndex.bucketOf(all, "Portuguese"), "");
        check("shelves: Brazil and Portugal are two shelves",
                pt.keySet().contains("Brazil") && pt.keySet().contains("Portugal"));

        // --- wall sort: rating desc, unrated last, newest breaks ties
        List<CatalogIndex.Record> sort = new ArrayList<>(Arrays.asList(
                rec(10, "Low", "English", "United States", 5.0, "2020-01-01"),
                rec(11, "Unrated", "English", "United States", -1, "2024-01-01"),
                rec(12, "NewHigh", "English", "United States", 9.0, "2023-01-01"),
                rec(13, "OldHigh", "English", "United States", 9.0, "2001-01-01")));
        CatalogIndex.sortShows(sort);
        check("sort: rated desc, newest tie-break, unrated last",
                sort.get(0).id == 12 && sort.get(1).id == 13
                        && sort.get(2).id == 10 && sort.get(3).id == 11);

        // --- cache round-trips: bucket + state
        List<CatalogIndex.Record> bucket = CatalogIndex.bucketOf(all, "Spanish");
        List<CatalogIndex.Record> back =
                CatalogIndex.bucketFromJson(CatalogIndex.bucketToJson(bucket));
        checkEq("cache: bucket round-trip size", bucket.size(), back.size());
        check("cache: bucket round-trip fields",
                back.get(0).name.equals(bucket.get(0).name)
                        && back.get(0).countryCode.equals(bucket.get(0).countryCode)
                        && back.get(0).rating == bucket.get(0).rating);
        CatalogIndex.State st = new CatalogIndex.State();
        st.pagesWalked = 37;
        CatalogIndex.recount(st, all);
        CatalogIndex.State stBack = CatalogIndex.State.fromJson(st.toJson().toString());
        checkEq("cache: state pages survive", 37, stBack.pagesWalked);
        checkEq("cache: state counts survive", 2,
                stBack.counts.getOrDefault("Spanish", 0).intValue());
        checkEq("cache: state total", 6, stBack.totalGathered());
        checkEq("cache: bucket file for Spanish", "lang_spanish.json",
                CatalogIndex.bucketFileName("Spanish"));
        checkEq("cache: bucket file for no language", "lang_none.json",
                CatalogIndex.bucketFileName(""));
        checkEq("door: es opens Spanish", "Spanish",
                CatalogIndex.languageNameForCode("es"));
        checkEq("door: ko opens Korean", "Korean",
                CatalogIndex.languageNameForCode("ko"));

        // --- the real index: a captured TVmaze page (page 200)
        String fixDir = System.getProperty("user.home")
                + "/workspace/tala-tv-2026-10-16/work/v100/fixtures";
        if (ciSkipIfMissing("catalogIndex",
                new File(fixDir, "shows_page200.json"))) return;
        String pageJson = new String(Files.readAllBytes(
                new File(fixDir, "shows_page200.json").toPath()), "UTF-8");
        List<TvMaze.Show> page = TvMaze.parseShows(pageJson);
        check("real page: 230+ shows parse", page.size() >= 230);
        Map<Integer, CatalogIndex.Record> realMap = new java.util.LinkedHashMap<>();
        CatalogIndex.mergeInto(realMap, page);
        List<CatalogIndex.Record> realAll = new ArrayList<>(realMap.values());
        java.util.Set<String> langs = new java.util.HashSet<>();
        for (CatalogIndex.Record r : realAll) if (!r.language.isEmpty()) langs.add(r.language);
        check("real page: at least 5 languages ride one page", langs.size() >= 5);
        check("real page: Russian depth is real (>=25 of the measured 42)",
                CatalogIndex.bucketOf(realAll, "Russian").size() >= 25);
        check("real page: Japanese present", CatalogIndex.bucketOf(realAll, "Japanese").size() >= 5);
        int shelfSum = 0;
        for (List<CatalogIndex.Record> members
                : CatalogIndex.shelves(realAll, "").values()) {
            shelfSum += members.size();
        }
        checkEq("real page: shelves account for every show", realAll.size(), shelfSum);
    }

    // ------------------------------------------------- title names (v0.10.0)

    static void titleNames() throws Exception {
        String fixDir = System.getProperty("user.home")
                + "/workspace/tala-tv-2026-10-16/work/v100/fixtures";
        if (ciSkipIfMissing("titleNames",
                new File(fixDir, "akas_27436.json"),
                new File(fixDir, "akas_919.json"))) return;
        String akaJson = new String(Files.readAllBytes(
                new File(fixDir, "akas_27436.json").toPath()), "UTF-8");
        List<TvMaze.Aka> akas = TvMaze.parseAkas(akaJson);
        check("akas: real record parses (5+)", akas.size() >= 5);
        checkEq("akas: first is the country-less primary", "Money Heist", akas.get(0).name);
        checkEq("akas: primary carries no country", "", akas.get(0).countryName);

        List<TitleNames.Name> set = TitleNames.buildSet("La Casa de Papel", akas);
        checkEq("names: original first", "La Casa de Papel", set.get(0).name);
        boolean hasHeist = false;
        for (TitleNames.Name n : set) if (n.name.equals("Money Heist")) hasHeist = true;
        check("names: Money Heist is in the set", hasHeist);
        checkEq("names: Archive falls back to Money Heist", "Money Heist",
                TitleNames.archiveFallbackName(set));

        String titanJson = new String(Files.readAllBytes(
                new File(fixDir, "akas_919.json").toPath()), "UTF-8");
        List<TitleNames.Name> titan = TitleNames.buildSet(
                "Attack on Titan", TvMaze.parseAkas(titanJson));
        boolean hasOriginal = false;
        for (TitleNames.Name n : titan) if (n.name.equals("進撃の巨人")) hasOriginal = true;
        check("names: the Japanese original rides the set", hasOriginal);

        // dedupe: an AKA that only differs by punctuation is the same name
        List<TvMaze.Aka> dup = new ArrayList<>();
        TvMaze.Aka d1 = new TvMaze.Aka(); d1.name = "Attack on Titan!";
        dup.add(d1);
        checkEq("names: punctuation twin dropped", 1,
                TitleNames.buildSet("Attack on Titan", dup).size());
        // and the cap holds
        List<TvMaze.Aka> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            TvMaze.Aka a = new TvMaze.Aka(); a.name = "Alt Name " + i; many.add(a);
        }
        checkEq("names: the set is capped", TitleNames.MAX_NAMES,
                TitleNames.buildSet("Original", many).size());
        checkEq("names: no alternates, no fallback", null,
                TitleNames.archiveFallbackName(
                        TitleNames.buildSet("Solo", new ArrayList<>())));
    }

    // ------------------------------------------------- availability (v0.10.0)

    static void availability() {
        Availability.Shelf shelf = new Availability.Shelf(Availability.buildKeySet(
                Arrays.asList(
                        "Bonanza S01E01 - A Rose for Bronco.mp4",
                        "Night of the Living Dead (1968) [1080p].mkv",
                        "Office Space (1999).mkv",
                        "Dragnet - Episode 7.mp4",
                        "Some Show S00E01 - Special.mp4",
                        "Money Heist S01E01.mkv")));
        check("avail: episode file carries its show", shelf.carries("Bonanza"));
        check("avail: movie answers without its year",
                shelf.carries("Night of the Living Dead"));
        check("avail: office space is not The Office", !shelf.carries("The Office"));
        check("avail: episode-word marker gives the show", shelf.carries("Dragnet"));
        check("avail: specials marker gives the show", shelf.carries("Some Show"));
        check("avail: an unloaded show is not carried", !shelf.carries("Firefly"));
        check("avail: AKA name carries the original's show",
                shelf.carriesAny(Arrays.asList("La Casa de Papel", "Money Heist")));
        check("avail: original alone does not invent the match",
                !shelf.carries("La Casa de Papel"));
        check("avail: candidate keys from a bare episode file",
                Availability.candidateKeys("Bonanza S01E01 - x.mp4").contains("bonanza"));
        check("avail: empty shelf carries nothing",
                !new Availability.Shelf(Availability.buildKeySet(
                        new ArrayList<>())).carries("Bonanza"));
    }

    // ------------------------------------------------- IA language (v0.10.0)

    static IaClient.Item iaItem(String id, String lang) {
        IaClient.Item it = new IaClient.Item();
        it.identifier = id; it.title = id; it.language = lang;
        return it;
    }

    static void iaLanguage() throws Exception {
        String[][] map = {
                {"English", "eng"}, {"Spanish", "spa"}, {"French", "fre"},
                {"German", "ger"}, {"Portuguese", "por"}, {"Italian", "ita"},
                {"Russian", "rus"}, {"Arabic", "ara"}, {"Hindi", "hin"},
                {"Chinese", "chi"}, {"Japanese", "jpn"}, {"Korean", "kor"}};
        for (String[] pair : map) {
            checkEq("ia code: " + pair[0], pair[1], IaClient.iaLanguageCode(pair[0]));
        }
        checkEq("ia code: unmapped language is honest empty", "",
                IaClient.iaLanguageCode("Luxembourgish"));
        check("ia url: episode language clause",
                java.net.URLDecoder.decode(
                        IaClient.episodeLangSearchUrl("Show", "Ep", "spa"), "UTF-8")
                        .contains("language:(spa)"));
        check("ia url: show language clause",
                java.net.URLDecoder.decode(
                        IaClient.showLangSearchUrl("Show", "jpn"), "UTF-8")
                        .contains("language:(jpn)"));

        String docs = "{\"response\":{\"docs\":["
                + "{\"identifier\":\"a\",\"title\":\"A\",\"language\":[\"spa\"]},"
                + "{\"identifier\":\"b\",\"title\":\"B\",\"language\":\"kor\"},"
                + "{\"identifier\":\"c\",\"title\":\"C\"}]}}";
        List<IaClient.Item> items = IaClient.parseSearch(docs);
        checkEq("ia parse: array language", "spa", items.get(0).language);
        checkEq("ia parse: string language", "kor", items.get(1).language);
        checkEq("ia parse: untagged stays empty", "", items.get(2).language);

        List<IaClient.Item> ranked = new ArrayList<>(Arrays.asList(
                iaItem("eng1", "eng"), iaItem("spa1", "spa"),
                iaItem("none", ""), iaItem("spa2", "spa")));
        List<IaClient.Item> parted = IaClient.preferLanguage(ranked, "spa");
        check("ia partition: tagged float first, order kept",
                parted.get(0).identifier.equals("spa1")
                        && parted.get(1).identifier.equals("spa2")
                        && parted.get(2).identifier.equals("eng1")
                        && parted.get(3).identifier.equals("none"));
        checkEq("ia partition: no code, no change", 4,
                IaClient.preferLanguage(ranked, "").size());
    }

    // ------------------------------------------------- farsi (v0.10.1)

    static void farsi() throws Exception {
        // --- the 13th citizen: table, picker, door
        checkEq("fa: 13 baked languages", 13, UiStrings.LANGUAGES.length);
        checkEq("fa: 13 baked tables", 13, UiStrings.TABLES.size());
        check("fa: all tables still complete", UiStrings.tablesComplete());
        String faName = "";
        for (String[] l : UiStrings.LANGUAGES) {
            if (l[0].equals("fa")) faName = l[1];
        }
        checkEq("fa: picker name in its own script", "فارسی", faName);
        checkEq("fa: facet is TVmaze's name", "Persian",
                UiStrings.facetFor("fa"));
        checkEq("fa: door opens Persian", "Persian",
                CatalogIndex.languageNameForCode("fa"));
        checkEq("fa: bucket file", "lang_persian.json",
                CatalogIndex.bucketFileName("Persian"));

        // --- positional spot lookups (the table is positional --
        // these pin the alignment, not just the presence)
        checkEq("fa lookup: home_catalog", "کاتالوگ",
                UiStrings.lookup("fa", "home_catalog"));
        checkEq("fa lookup: lang_choose_title", "زبان خود را انتخاب کنید",
                UiStrings.lookup("fa", "lang_choose_title"));
        checkEq("fa lookup: player_share_tv", "اشتراک‌گذاری با تلویزیون",
                UiStrings.lookup("fa", "player_share_tv"));
        checkEq("fa lookup: reader_play", "برایم بخوان",
                UiStrings.lookup("fa", "reader_play"));
        checkEq("fa lookup: wall_available", "در قفسه‌های شما",
                UiStrings.lookup("fa", "wall_available"));
        checkEq("fa lookup: wall_catalog_only", "فقط در کاتالوگ",
                UiStrings.lookup("fa", "wall_catalog_only"));

        // --- not English-in-disguise: every fa value differs from
        // its en twin, and the text is really RTL-script
        String[] faT = UiStrings.TABLES.get("fa");
        String[] enT = UiStrings.TABLES.get("en");
        boolean anySame = false, anyBroken = false, anyNoRtl = false;
        for (int i = 0; i < UiStrings.KEYS.length; i++) {
            if (faT[i].equals(enT[i])) {
                anySame = true;
                System.out.println("FAIL detail: fa==en at " + UiStrings.KEYS[i]);
            }
            if (faT[i].indexOf('�') >= 0) anyBroken = true;
            boolean rtl = false;
            for (int j = 0; j < faT[i].length(); j++) {
                char c = faT[i].charAt(j);
                if (c >= 0x590 && c <= 0x8FF) { rtl = true; break; }
            }
            if (!rtl) {
                anyNoRtl = true;
                System.out.println("FAIL detail: no RTL char at " + UiStrings.KEYS[i]);
            }
        }
        check("fa: no value is its English twin", !anySame);
        check("fa: no replacement-char damage", !anyBroken);
        check("fa: every value carries RTL script", !anyNoRtl);

        // --- the wall, over the REAL captured Persian records
        String fixDir = System.getProperty("user.home")
                + "/workspace/tala-tv-2026-10-16/work/v101/fixtures";
        if (ciSkipIfMissing("farsi",
                new File(fixDir, "shows_persian.json"),
                new File(System.getProperty("user.home")
                        + "/workspace/tala-tv-2026-10-16"
                        + "/work/v101/research_persian.json"))) return;
        String faJson = new String(Files.readAllBytes(
                new File(fixDir, "shows_persian.json").toPath()), "UTF-8");
        List<TvMaze.Show> faShows = TvMaze.parseShows(faJson);
        check("fa real: captured records parse (15+)", faShows.size() >= 15);
        boolean allFa = true;
        for (TvMaze.Show s : faShows) {
            if (!"Persian".equals(s.language)) allFa = false;
        }
        check("fa real: every captured record is tagged Persian", allFa);
        String research = new String(Files.readAllBytes(new File(
                System.getProperty("user.home") + "/workspace/tala-tv-2026-10-16"
                        + "/work/v101/research_persian.json").toPath()), "UTF-8");
        int measured = new org.json.JSONObject(research).getInt("persian_total");
        checkEq("fa real: fixture is the measured count", measured, faShows.size());

        Map<Integer, CatalogIndex.Record> faMap = new java.util.LinkedHashMap<>();
        CatalogIndex.mergeInto(faMap, faShows);
        List<CatalogIndex.Record> faAll = new ArrayList<>(faMap.values());
        checkEq("fa wall: the whole Persian bucket gathers",
                faShows.size(), CatalogIndex.bucketOf(faAll, "Persian").size());
        java.util.LinkedHashMap<String, List<CatalogIndex.Record>> faShelves =
                CatalogIndex.shelves(CatalogIndex.bucketOf(faAll, "Persian"),
                        "Iran, Islamic Republic of");
        checkEq("fa wall: Iran is the home shelf, first",
                "Iran, Islamic Republic of",
                faShelves.keySet().iterator().next());
        int faSum = 0;
        for (List<CatalogIndex.Record> members : faShelves.values()) {
            faSum += members.size();
        }
        checkEq("fa wall: shelves account for every Persian show",
                faAll.size(), faSum);
        check("fa wall: the Iran shelf holds the bulk (>=15)",
                faShelves.getOrDefault("Iran, Islamic Republic of",
                        new ArrayList<>()).size() >= 15);

        // --- the Archive leg: fas, measured (fas 74,338 / per 2,557 / fa 0)
        checkEq("ia code: Persian is fas", "fas",
                IaClient.iaLanguageCode("Persian"));
        check("ia url: Persian language clause",
                java.net.URLDecoder.decode(
                        IaClient.showLangSearchUrl("Show", "fas"), "UTF-8")
                        .contains("language:(fas)"));

        // --- a holder's fa pack rides the same fail-closed gate
        java.util.Map<String, String> faStrings = new java.util.HashMap<>();
        for (int i = 0; i < UiStrings.KEYS.length; i++) {
            faStrings.put(UiStrings.KEYS[i], faT[i]);
        }
        com.talacore.talatv.LangPack.Result pr = com.talacore.talatv.LangPack.parse(
                com.talacore.talatv.LangPack.write("fa", "فارسی", faStrings));
        check("fa pack: complete pack validates", pr.ok);
        checkEq("fa pack: code rides through", "fa", pr.code);
        checkEq("fa pack: a string rides through", "کاتالوگ",
                pr.strings.get("home_catalog"));
    }
    // -------------------------------------------- v0.11.0: registry

    static void langRegistry() throws Exception {
        String home = System.getProperty("user.home");
        String seed = new String(Files.readAllBytes(new File(home
                + "/workspace/tala-tv-2026-10-16/app/assets/lang_registry.tsv")
                .toPath()), "UTF-8");
        java.util.Map<String, Long> counts = LangRegistry.parseSeed(seed);
        checkEq("registry: seed carries the walk's 84 buckets "
                + "(83 named languages + the none bucket)", 84, counts.size());
        checkEq("registry: English is the measured head", 45122L,
                counts.get("English"));
        checkEq("registry: Persian measured thin, printed thin", 22L,
                counts.get("Persian"));
        checkEq("registry: no-language bucket measured", 2813L,
                counts.get(LangRegistry.NO_LANGUAGE));

        java.util.Map<String, Integer> gathered = new java.util.HashMap<>();
        gathered.put("Dutch", 5);
        gathered.put("Persian", 22);
        gathered.put("Klingon", 7); // not in the seed: a walk discovery
        gathered.put(LangRegistry.NO_LANGUAGE, 3);
        java.util.List<LangRegistry.Entry> reg =
                LangRegistry.build(counts, gathered);
        checkEq("registry: union of seed + gathered + spoken", 85, reg.size());
        LangRegistry.Entry first = reg.get(0);
        checkEq("registry: spoken languages stand first (picker order)",
                "English", first.tvName);
        checkEq("registry: spoken bridge code", "en", first.spokenCode);
        LangRegistry.Entry dutch = LangRegistry.find(reg, "Dutch");
        check("registry: Dutch present though Sandy doesn't speak it",
                dutch != null && !dutch.spoken());
        checkEq("registry: Dutch index count rides", 2181L, dutch.indexCount);
        checkEq("registry: Dutch gathered count rides", 5L, dutch.gatheredCount);
        checkEq("registry: Dutch shows its own name", "Nederlands",
                dutch.displayName());
        LangRegistry.Entry persian = LangRegistry.find(reg, "Persian");
        checkEq("registry: Persian spoken (fa)", "fa", persian.spokenCode);
        checkEq("registry: Persian own script", "فارسی", persian.displayName());
        LangRegistry.Entry klingon = LangRegistry.find(reg, "Klingon");
        check("registry: a language the walk discovers joins with no "
                + "build change", klingon != null && klingon.indexCount == 0
                && klingon.gatheredCount == 7);
        checkEq("registry: no-language bucket rides last",
                LangRegistry.NO_LANGUAGE, reg.get(reg.size() - 1).tvName);
        check("registry: every spoken language has an entry",
                LangRegistry.find(reg, "Arabic") != null
                        && LangRegistry.find(reg, "Japanese") != null);
        check("registry: native name fallback is the TVmaze name",
                LangRegistry.nativeNameFor("Klingon").isEmpty());
    }

    // --------------------------------------------- v0.11.0: guide

    static void guideModel() {
        // Gap honesty: rows 10:00-11:00 and 12:00-13:00 (epoch base).
        long h = 3600_000L;
        GuideModel.GuideData d = new GuideModel.GuideData();
        d.add(new GuideModel.Programme("Chan.us", 10 * h, 11 * h, "A", "", ""));
        d.add(new GuideModel.Programme("Chan.us", 12 * h, 13 * h, "B", "", ""));
        d.finish();
        checkEq("guide: now inside A", "A", d.now("Chan.us", 10 * h + 1).title);
        check("guide: a gap is a gap — no programme invented",
                d.now("Chan.us", 11 * h + 1800_000L) == null);
        checkEq("guide: next across the gap", "B",
                d.next("Chan.us", 11 * h + 1800_000L).title);
        check("guide: unknown channel has no guide",
                !d.hasGuide("Nope.us") && d.now("Nope.us", 10 * h) == null);

        // Timezone truth: a programme filed from a +0300 source,
        // 22:00-23:00 their time = 19:00-20:00 UTC. A viewer at UTC-5
        // sees it on their day at 14:00-15:00 local. Epochs only here;
        // the day window is the viewer's, built with their zone.
        long startUtc = GuideModel.parseXmltvTime("20261018220000 +0300");
        checkEq("guide: +0300 converts to the same epoch as 19:00 UTC",
                GuideModel.parseXmltvTime("20261018190000 +0000"), startUtc);
        GuideModel.GuideData g = new GuideModel.GuideData();
        g.add(new GuideModel.Programme("X", startUtc, startUtc + h, "Show", "", ""));
        g.finish();
        java.util.TimeZone minus5 = java.util.TimeZone.getTimeZone("GMT-05:00");
        java.util.Calendar cal = java.util.Calendar.getInstance(minus5);
        cal.setTimeInMillis(startUtc);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long dayStart = cal.getTimeInMillis();
        checkEq("guide: source-evening show lands on the viewer's day",
                1, g.day("X", dayStart, 24 * h).size());
        checkEq("guide: ...and not on the viewer's next day",
                0, g.day("X", dayStart + 24 * h, 24 * h).size());
        checkEq("guide: rendered in the viewer's zone it reads 2:00 PM",
                "2:00 PM", GuideModel.formatTime(startUtc, minus5));
        checkEq("guide: rendered in the source zone it reads 10:00 PM",
                "10:00 PM", GuideModel.formatTime(startUtc,
                        java.util.TimeZone.getTimeZone("GMT+03:00")));

        // DST: America/Chicago springs forward 2026-03-08 (23h day).
        java.util.TimeZone chi = java.util.TimeZone.getTimeZone("America/Chicago");
        java.util.Calendar c2 = java.util.Calendar.getInstance(chi);
        c2.set(2026, 2, 8, 0, 0, 0);
        c2.set(java.util.Calendar.MILLISECOND, 0);
        long dstStart = c2.getTimeInMillis();
        c2.add(java.util.Calendar.DAY_OF_YEAR, 1);
        long dstLen = c2.getTimeInMillis() - dstStart;
        checkEq("guide: spring-forward day is 23 hours", 23 * h, dstLen);
        GuideModel.GuideData gd = new GuideModel.GuideData();
        gd.add(new GuideModel.Programme("Y", dstStart + 90 * 60_000L,
                dstStart + 150 * 60_000L, "BeforeJump", "", ""));
        gd.add(new GuideModel.Programme("Y", dstStart + 150 * 60_000L,
                dstStart + 210 * 60_000L, "AfterJump", "", ""));
        gd.finish();
        checkEq("guide: both sides of the jump are on the day",
                2, gd.day("Y", dstStart, dstLen).size());

        // Prune: the old row goes, the window claims shrink honestly.
        gd.prune(dstStart + 200 * 60_000L);
        checkEq("guide: prune drops ended rows", 1, gd.rowsFor("Y").size());
        check("guide: bad timestamps are dropped, never guessed",
                GuideModel.parseXmltvTime("not a time") == -1
                        && GuideModel.parseXmltvTime("20261340000000 +0000") == -1
                        && GuideModel.parseXmltvTime(null) == -1);
    }

    static void xmltv() throws Exception {
        String home = System.getProperty("user.home");
        File fix = new File(home + "/workspace/tala-tv-2026-10-16/work/v110"
                + "/fixtures/guide_slice.xml");
        if (ciSkipIfMissing("xmltv", fix,
                new File(home + "/workspace/tala-tv-2026-10-16/work/v110"
                        + "/fixtures/guide_bridge.txt"))) return;
        java.util.Map<String, String> bridge = new java.util.HashMap<>();
        for (String line : new String(Files.readAllBytes(new File(home
                + "/workspace/tala-tv-2026-10-16/work/v110/fixtures/"
                + "guide_bridge.txt").toPath()), "UTF-8").split("\n")) {
            String[] c = line.trim().split("\t");
            if (c.length == 2) bridge.put(c[0], c[1]);
        }
        checkEq("xmltv: bridge fixture carries two channels", 2, bridge.size());
        GuideModel.GuideData d;
        try (java.io.InputStream in = new java.io.FileInputStream(fix)) {
            d = XmltvParser.parse(in, bridge);
        }
        checkEq("xmltv: both mapped channels land", 2, d.channelCount());
        String plutoId = bridge.values().toArray(new String[0])[0];
        int total = 0;
        for (java.util.List<GuideModel.Programme> rows : d.byChannel.values()) {
            total += rows.size();
        }
        checkEq("xmltv: the unmapped channel's programme is dropped",
                20, total);
        String iptvId = null;
        for (String k : d.byChannel.keySet()) if (!k.equals(plutoId)) iptvId = k;
        GuideModel.Programme first = d.rowsFor(iptvId).get(0);
        checkEq("xmltv: +0300 programme filed at its true epoch",
                GuideModel.parseXmltvTime("20261012001500 +0300"), first.startMs);
        check("xmltv: titles ride through",
                !d.rowsFor(plutoId).get(0).title.isEmpty());
    }

    // -------------------------------------------- v0.11.0: timers

    static TimerModel.Timer timer(long startMin, long stopMin, String id) {
        TimerModel.Timer t = new TimerModel.Timer();
        t.id = id;
        t.channelName = "Chan";
        t.channelUrl = "http://example.com/chan.m3u8";
        t.title = "Show " + id;
        t.startMs = startMin * 60_000L;
        t.stopMs = stopMin * 60_000L;
        t.createdAtMs = 1;
        return t;
    }

    static void timerModel() {
        TimerModel.Timer t = timer(120, 180, "t1");
        checkEq("timer: fire = start - 60s pad", 119 * 60_000L, t.fireMs());
        checkEq("timer: until = stop + 120s pad", 182 * 60_000L, t.untilMs());

        // Round-trip with hostile text (tabs, newlines, unicode).
        t.title = "A\tB\nC — «Шоу»";
        t.detail = "some\tdetail";
        TimerModel.Timer back = TimerModel.fromRow(TimerModel.toRow(t));
        check("timer: row round-trips", back != null);
        checkEq("timer: hostile title survives the file", t.title, back.title);
        checkEq("timer: hostile detail survives", t.detail, back.detail);
        java.util.List<TimerModel.Timer> parsed =
                TimerModel.parse(TimerModel.serialize(
                        java.util.Arrays.asList(t, timer(300, 360, "t2"))));
        checkEq("timer: file round-trips whole", 2, parsed.size());
        check("timer: malformed rows are skipped, never fatal",
                TimerModel.fromRow("garbage") == null
                        && TimerModel.parse("junk\nmore junk").isEmpty());

        // Conflict: padded windows decide; merely touching is fine.
        TimerModel.Timer a = timer(120, 180, "a");       // fires 119–182
        check("timer: overlap conflicts",
                TimerModel.conflict(java.util.Arrays.asList(a),
                        timer(170, 200, "b")) != null);
        check("timer: padded reach conflicts too",
                TimerModel.conflict(java.util.Arrays.asList(a),
                        timer(182, 240, "b")) != null);
        check("timer: back-to-back does not conflict",
                TimerModel.conflict(java.util.Arrays.asList(a),
                        timer(184, 240, "b")) == null);
        TimerModel.Timer dead = timer(120, 180, "dead");
        dead.state = TimerModel.State.RECORDED;
        check("timer: finished timers never conflict",
                TimerModel.conflict(java.util.Arrays.asList(dead),
                        timer(130, 140, "b")) == null);

        // Reconcile matrix (minutes since epoch; pads 1m/2m).
        java.util.List<TimerModel.Decision> dec;
        dec = TimerModel.reconcile(java.util.Arrays.asList(timer(120, 180, "f")),
                100 * 60_000L);
        checkEq("timer: future re-arms", TimerModel.Action.REARM, dec.get(0).action);
        dec = TimerModel.reconcile(java.util.Arrays.asList(timer(120, 180, "p")),
                119 * 60_000L + 30_000L);
        checkEq("timer: woken inside the pad fires on time",
                TimerModel.Action.FIRE_NOW, dec.get(0).action);
        dec = TimerModel.reconcile(java.util.Arrays.asList(timer(120, 180, "l")),
                150 * 60_000L);
        checkEq("timer: mid-show with tape left fires late",
                TimerModel.Action.FIRE_LATE, dec.get(0).action);
        dec = TimerModel.reconcile(java.util.Arrays.asList(timer(120, 180, "m")),
                178 * 60_000L);
        checkEq("timer: under 5 minutes left is missed, not joined",
                TimerModel.Action.MARK_MISSED_OFF, dec.get(0).action);
        dec = TimerModel.reconcile(java.util.Arrays.asList(timer(120, 180, "o")),
                300 * 60_000L);
        checkEq("timer: long past is missed — phone was off",
                TimerModel.Action.MARK_MISSED_OFF, dec.get(0).action);
        TimerModel.Timer rec = timer(120, 180, "r");
        rec.state = TimerModel.State.RECORDING;
        dec = TimerModel.reconcile(java.util.Arrays.asList(rec), 300 * 60_000L);
        checkEq("timer: a recording found dead past its window is "
                + "closed as interrupted", TimerModel.Action.MARK_INTERRUPTED,
                dec.get(0).action);
        dec = TimerModel.reconcile(java.util.Arrays.asList(rec), 150 * 60_000L);
        checkEq("timer: a recording inside its window is left alone",
                TimerModel.Action.NONE, dec.get(0).action);

        // End states.
        checkEq("timer: clean run lands RECORDED", TimerModel.State.RECORDED,
                TimerModel.endState(true, false, false, false));
        checkEq("timer: late join lands PARTIAL_LATE",
                TimerModel.State.PARTIAL_LATE,
                TimerModel.endState(true, true, false, false));
        checkEq("timer: space stop lands PARTIAL_EARLY",
                TimerModel.State.PARTIAL_EARLY,
                TimerModel.endState(true, false, true, false));
        checkEq("timer: refused start lands FAILED_REFUSED",
                TimerModel.State.FAILED_REFUSED,
                TimerModel.endState(false, false, false, true));
        checkEq("timer: no start, no refusal lands MISSED_WAKE",
                TimerModel.State.MISSED_WAKE,
                TimerModel.endState(false, false, false, false));

        // Storage math: 5 Mbps * 1h = 2.25 GB; pre-flight needs x1.2.
        checkEq("timer: an hour at 5 Mbps is 2.25 GB", 2_250_000_000L,
                TimerModel.bytesForDuration(5.0, 3600_000L));
        TimerModel.Timer hour = timer(0, 60, "h");
        long need = TimerModel.neededBytes(5.0, hour);
        check("timer: pre-flight wants estimate x1.2 with pads counted",
                need > 2_250_000_000L);
        check("timer: space verdict at the line",
                TimerModel.spaceOk(need, 5.0, hour)
                        && !TimerModel.spaceOk(need - 1, 5.0, hour));
        checkEq("timer: size text", "2.3 GB", TimerModel.sizeText(2_250_000_000L));
        check("timer: manual timers need no guide — a timer is complete "
                + "with channel + times alone",
                TimerModel.fromRow(TimerModel.toRow(timer(600, 660, "man")))
                        .tvgId.isEmpty());
    }

    // --------------------------------------- v0.11.0: memory files

    static void memoryFiles() {
        java.util.List<MemoryFiles.KeptPlace> rows = new java.util.ArrayList<>();
        rows.add(new MemoryFiles.KeptPlace("#seeded", "pos", 0, 1, ""));
        rows.add(new MemoryFiles.KeptPlace("pos", "title:heat", 83_000, 2, ""));
        rows.add(new MemoryFiles.KeptPlace("live", "live", 5_000, 3,
                "{\"url\":\"http://x/y.m3u8\",\"name\":\"A\\tB\"}"));
        rows.add(new MemoryFiles.KeptPlace("book", "eb:1", 7, 4,
                "{\"char\":120,\"finished\":false}"));
        java.util.List<MemoryFiles.KeptPlace> back =
                MemoryFiles.keptPlacesFromText(MemoryFiles.keptPlacesToText(rows));
        checkEq("memory: kept places round-trip whole", 4, back.size());
        checkEq("memory: position rides", 83_000L,
                MemoryFiles.findKept(back, "pos", "title:heat").position);
        check("memory: live extra JSON rides untouched",
                MemoryFiles.findKept(back, "live", "live").extra.contains("y.m3u8"));
        java.util.List<MemoryFiles.KeptPlace> up = MemoryFiles.upsertKept(back,
                new MemoryFiles.KeptPlace("pos", "title:heat", 0, 9, ""));
        check("memory: a zeroed position removes the row (house rule)",
                MemoryFiles.findKept(up, "pos", "title:heat") == null);
        checkEq("memory: upsert replaces, never duplicates", 4,
                MemoryFiles.upsertKept(back, new MemoryFiles.KeptPlace(
                        "pos", "title:heat", 1, 9, "")).size());

        java.util.List<MemoryFiles.RecordingMeta> recs = new java.util.ArrayList<>();
        MemoryFiles.RecordingMeta r = new MemoryFiles.RecordingMeta();
        r.id = "http://x/y.m3u8";
        r.title = "The\tShow";
        r.channel = "Chan";
        r.airedStartMs = 100;
        r.airedStopMs = 200;
        r.origin = "timer";
        r.state = "PARTIAL_LATE";
        recs.add(r);
        java.util.List<MemoryFiles.RecordingMeta> rback =
                MemoryFiles.recordingsFromText(MemoryFiles.recordingsToText(recs));
        checkEq("memory: recordings round-trip whole", 1, rback.size());
        checkEq("memory: recording title with a tab survives",
                "The\tShow", rback.get(0).title);
        checkEq("memory: recording end state rides", "PARTIAL_LATE",
                rback.get(0).state);
        checkEq("memory: remove drops exactly the row", 0,
                MemoryFiles.removeRecording(rback, r.id).size());
    }

    // ------------------------------------- v0.11.0: IA + strings

    static void iaMarcExtended() {
        String[][] pins = {
                {"Dutch", "dut"}, {"Swedish", "swe"}, {"Ukrainian", "ukr"},
                {"Thai", "tha"}, {"Greek", "gre"}, {"Bengali", "ben"},
                {"Tamil", "tam"}, {"Scottish Gaelic", "gla"},
                {"Latin", "lat"}, {"Pashto", "pus"}, {"Hebrew", "heb"},
                {"Polish", "pol"}, {"Norwegian", "nor"}, {"Finnish", "fin"},
                {"Czech", "cze"}, {"Turkish", "tur"}, {"Hungarian", "hun"},
                {"Indonesian", "ind"}, {"Vietnamese", "vie"},
                {"Romanian", "rum"}, {"Serbian", "srp"}, {"Urdu", "urd"},
        };
        for (String[] p : pins) {
            checkEq("ia marc (measured): " + p[0], p[1],
                    IaClient.iaLanguageCode(p[0]));
        }
        checkEq("ia marc: unmeasured languages stay silent (no leg)",
                "", IaClient.iaLanguageCode("Klingon"));
        checkEq("ia marc: Persian regression — still fas", "fas",
                IaClient.iaLanguageCode("Persian"));
        checkEq("ia marc: French regression — still fre", "fre",
                IaClient.iaLanguageCode("French"));
    }

    static void uiStringsV110() {
        for (String k : new String[]{"guide_btn", "guide_title",
                "guide_no_guide", "guide_no_info", "guide_now", "guide_next",
                "guide_record_this", "guide_updated", "guide_covers",
                "timers_btn", "timers_title", "timers_empty", "timer_set_btn",
                "timer_manual_intro", "timer_approx", "timer_cancel",
                "state_recorded", "state_partial", "state_failed",
                "state_missed", "home_more_languages", "lang_index_title",
                "lang_index_note"}) {
            check("uistrings v110 key present: " + k, UiStrings.keyIndex(k) >= 0);
        }
        checkEq("uistrings guide_title fa", "راهنمای تلویزیون",
                UiStrings.lookup("fa", "guide_title"));
        checkEq("uistrings timers_title es", "Temporizadores de grabación",
                UiStrings.lookup("es", "timers_title"));
        checkEq("uistrings guide_no_guide ru",
                "Нет программы для этого канала",
                UiStrings.lookup("ru", "guide_no_guide"));
        checkEq("uistrings home_more_languages ja", "その他の言語",
                UiStrings.lookup("ja", "home_more_languages"));
        checkEq("uistrings state_missed fa", "از دست رفت",
                UiStrings.lookup("fa", "state_missed"));
    }

    // ------------------------- v0.11.0 field defect: slow != dead
    // James, live use, 2026-10-18 ~16:51 CDT: SG-1 episode 3 — the
    // first tap said the source was dead ("didn't answer in time"),
    // he backed out, tapped again, and the same link played. The
    // old probe ran ONE attempt and filed every failure as DEAD.
    // These pins hold the fix: classification, patience, the cap.

    static final class Script implements LinkHealth.Transport {
        final java.util.Map<String, java.util.Deque<Object>> queues =
                new java.util.HashMap<>();
        final java.util.Map<String, Integer> calls = new java.util.HashMap<>();
        Object fallback;

        Script on(String url, Object... outcomes) {
            java.util.Deque<Object> q = new java.util.ArrayDeque<>();
            for (Object o : outcomes) q.add(o);
            queues.put(url, q);
            return this;
        }

        @Override
        public LinkHealth.Response get(String url, String ua, int timeoutMs)
                throws java.io.IOException {
            calls.merge(url, 1, Integer::sum);
            java.util.Deque<Object> q = queues.get(url);
            Object o = q == null || q.isEmpty() ? fallback : q.poll();
            if (o instanceof java.io.IOException) throw (java.io.IOException) o;
            if (o instanceof LinkHealth.Response) return (LinkHealth.Response) o;
            throw new java.io.IOException("unscripted: " + url);
        }
    }

    static void linkHealthPatience() {
        // --- classification: terminal vs retryable (the split)
        checkEq("health cls: 200 ok", LinkHealth.CLS_OK, LinkHealth.classifyHttp(200));
        checkEq("health cls: 404 terminal", LinkHealth.CLS_TERMINAL, LinkHealth.classifyHttp(404));
        checkEq("health cls: 410 gone is terminal", LinkHealth.CLS_TERMINAL, LinkHealth.classifyHttp(410));
        checkEq("health cls: 403 refused is terminal", LinkHealth.CLS_TERMINAL, LinkHealth.classifyHttp(403));
        checkEq("health cls: 408 retryable", LinkHealth.CLS_RETRYABLE, LinkHealth.classifyHttp(408));
        checkEq("health cls: 429 retryable", LinkHealth.CLS_RETRYABLE, LinkHealth.classifyHttp(429));
        checkEq("health cls: 500 retryable", LinkHealth.CLS_RETRYABLE, LinkHealth.classifyHttp(500));
        checkEq("health cls: 503 retryable", LinkHealth.CLS_RETRYABLE, LinkHealth.classifyHttp(503));
        checkEq("health cls: connect timeout retryable", LinkHealth.CLS_RETRYABLE,
                LinkHealth.classifyIo(new java.net.SocketTimeoutException("t")));
        checkEq("health cls: DNS failure terminal", LinkHealth.CLS_TERMINAL,
                LinkHealth.classifyIo(new java.net.UnknownHostException("h")));
        checkEq("health cls: connection refused terminal", LinkHealth.CLS_TERMINAL,
                LinkHealth.classifyIo(new java.net.ConnectException("refused")));
        checkEq("health cls: connection reset retryable", LinkHealth.CLS_RETRYABLE,
                LinkHealth.classifyIo(new java.net.SocketException("Connection reset")));
        checkEq("health cls: handshake stall retryable", LinkHealth.CLS_RETRYABLE,
                LinkHealth.classifyIo(new javax.net.ssl.SSLException("handshake")));

        // --- cap math: patient but bounded, both directions pinned
        check("health cap: first retry fits easily", LinkHealth.mayRetry(1, 6000, 6000));
        check("health cap: retry allowed at exactly the cap",
                LinkHealth.mayRetry(1, 23000, 6000));
        check("health cap: one ms past the cap is refused",
                !LinkHealth.mayRetry(1, 23001, 6000));
        check("health cap: second retry uses the longer backoff",
                LinkHealth.mayRetry(2, 20000, 6000)
                        && !LinkHealth.mayRetry(2, 25000, 6000));
        check("health cap: attempts are hard-capped at 3",
                !LinkHealth.mayRetry(3, 0, 6000));

        LinkHealth.setSleeper(ms -> { });
        try {
            // --- slow, slow, then alive: the SG-1 shape. Verdict: plays.
            String base = "http://slow.example.com/master.m3u8";
            Script s = new Script()
                    .on(base,
                            new java.net.SocketTimeoutException("t1"),
                            new java.net.SocketTimeoutException("t2"),
                            new LinkHealth.Response(200, null,
                                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nvar.m3u8\n"))
                    .on("http://slow.example.com/var.m3u8",
                            new LinkHealth.Response(200, null,
                                    "#EXTM3U\n#EXTINF:6,\nseg1.ts\n"))
                    .on("http://slow.example.com/seg1.ts",
                            new LinkHealth.Response(200, null, "TSBYTES"));
            LinkHealth.setTransport(s);
            final int[] slowNotes = {0};
            LinkHealth.Result r = LinkHealth.probe(base, "", 6000,
                    (next, max) -> slowNotes[0]++);
            checkEq("health: slow-then-alive plays", LinkHealth.TIER_PLAYS, r.tier);
            checkEq("health: it took 3 attempts", 3, r.attempts);
            checkEq("health: the source was asked 3 times", 3,
                    s.calls.getOrDefault(base, 0).intValue());
            checkEq("health: the user was told it was slow, twice", 2, slowNotes[0]);
            check("health: a playing link is not flagged slow", !r.slow);

            // --- terminal: 404 comes back on the FIRST attempt, final.
            String gone = "http://gone.example.com/x.m3u8";
            Script s2 = new Script().on(gone, new LinkHealth.Response(404, null, ""));
            LinkHealth.setTransport(s2);
            LinkHealth.Result r2 = LinkHealth.probe(gone, "", 6000);
            checkEq("health: 404 is dead", LinkHealth.TIER_DEAD, r2.tier);
            check("health: 404 is not slow-flagged", !r2.slow);
            checkEq("health: 404 gets exactly one attempt", 1, r2.attempts);
            checkEq("health: 404 asks exactly once", 1,
                    s2.calls.getOrDefault(gone, 0).intValue());

            // --- slow forever: retries exhaust, verdict says what
            // happened and is flagged slow — never filed as death.
            String never = "http://never.example.com/y.m3u8";
            Script s3 = new Script();
            s3.fallback = new java.net.SocketTimeoutException("always");
            LinkHealth.setTransport(s3);
            LinkHealth.Result r3 = LinkHealth.probe(never, "", 6000);
            checkEq("health: exhausted retries still end the wait",
                    LinkHealth.TIER_DEAD, r3.tier);
            check("health: exhausted retries are slow, not dead", r3.slow);
            checkEq("health: exhausted after the capped 3 attempts", 3, r3.attempts);
            checkEq("health: the cap held — 3 asks, no hang", 3,
                    s3.calls.getOrDefault(never, 0).intValue());
            check("health: the detail says what happened",
                    r3.detail.contains("didn't answer in time"));

            // --- an overloaded server (503) is slow, not dead.
            String busy = "http://busy.example.com/z.m3u8";
            Script s4 = new Script();
            s4.fallback = new LinkHealth.Response(503, null, "");
            LinkHealth.setTransport(s4);
            LinkHealth.Result r4 = LinkHealth.probe(busy, "", 6000);
            check("health: persistent 503 is slow-flagged", r4.slow);
            checkEq("health: persistent 503 also caps at 3 attempts", 3, r4.attempts);
            check("health: the 503 detail names the code",
                    r4.detail.contains("503"));

            // --- refused is terminal: one attempt, dead, no slow flag.
            String refused = "http://refused.example.com/w.m3u8";
            Script s5 = new Script();
            s5.fallback = new java.net.ConnectException("Connection refused");
            LinkHealth.setTransport(s5);
            LinkHealth.Result r5 = LinkHealth.probe(refused, "", 6000);
            checkEq("health: refused is dead", LinkHealth.TIER_DEAD, r5.tier);
            check("health: refused is not slow", !r5.slow);
            checkEq("health: refused gets one attempt", 1, r5.attempts);

            // --- probeOnce stays single-attempt (batch/skip-walk path).
            Script s6 = new Script();
            s6.fallback = new java.net.SocketTimeoutException("always");
            LinkHealth.setTransport(s6);
            LinkHealth.Result r6 = LinkHealth.probeOnce(never, "", 6000);
            check("health: probeOnce on a slow link flags slow", r6.slow);
            checkEq("health: probeOnce asks exactly once", 1,
                    s6.calls.getOrDefault(never, 0).intValue());
        } finally {
            LinkHealth.setTransport(null);
            LinkHealth.setSleeper(null);
        }
    }


    // ------------------------------------------- v0.11.1: facets

    static CatalogIndex.Record rec(int id, String name, String premiered,
                                   double rating) {
        CatalogIndex.Record r = new CatalogIndex.Record();
        r.id = id;
        r.name = name;
        r.premiered = premiered;
        r.rating = rating;
        return r;
    }

    static void facetSuite() throws Exception {
        String home = System.getProperty("user.home");
        if (ciSkipIfMissing("facets", new File(home
                + "/workspace/tala-tv-2026-10-16/work/v100/fixtures/"
                + "shows_page200.json"))) return;
        String page = new String(Files.readAllBytes(new File(home
                + "/workspace/tala-tv-2026-10-16/work/v100/fixtures/"
                + "shows_page200.json").toPath()), "UTF-8");
        java.util.List<TvMaze.Show> shows = TvMaze.parseShows(page);
        checkEq("facets: the real page carries 234 shows", 234, shows.size());
        java.util.List<CatalogIndex.Record> pool = new ArrayList<>();
        for (TvMaze.Show s : shows) pool.add(CatalogIndex.fromShow(s));

        // the type, parsed off the real wire (it was dropped before
        // 0.11.1): the fixture's own distribution, counted live
        java.util.Map<String, Integer> wireTypes = new java.util.HashMap<>();
        for (CatalogIndex.Record r : pool) {
            wireTypes.merge(r.type, 1, Integer::sum);
        }
        checkEq("facets: type Scripted", 135, wireTypes.get("Scripted"));
        checkEq("facets: type Reality", 33, wireTypes.get("Reality"));
        checkEq("facets: type Documentary", 30, wireTypes.get("Documentary"));
        checkEq("facets: type Animation", 9, wireTypes.get("Animation"));
        checkEq("facets: type News", 1, wireTypes.get("News"));
        check("facets: every fixture show carries a type",
                !wireTypes.containsKey(""));

        // derived facet counts agree with the wire
        java.util.LinkedHashMap<String, Integer> tc =
                CatalogIndex.facetCounts(pool, "type");
        int tsum = 0;
        for (int v : tc.values()) tsum += v;
        checkEq("facets: type counts sum to the pool", 234, tsum);
        checkEq("facets: type counts Scripted", 135, tc.get("Scripted"));
        check("facets: no not-listed entry when all are listed",
                !tc.containsKey(CatalogIndex.NOT_LISTED));
        java.util.LinkedHashMap<String, Integer> sc =
                CatalogIndex.facetCounts(pool, "status");
        checkEq("facets: status Ended", 198, sc.get("Ended"));
        checkEq("facets: status Running", 20, sc.get("Running"));
        checkEq("facets: status To Be Determined", 15,
                sc.get("To Be Determined"));
        java.util.LinkedHashMap<String, Integer> gc =
                CatalogIndex.facetCounts(pool, "genre");
        int dramaManual = 0;
        for (CatalogIndex.Record r : pool) {
            if (r.genres.contains("Drama")) dramaManual++;
        }
        checkEq("facets: genre counts agree with a manual count",
                dramaManual, gc.get("Drama"));

        // single + combined cuts
        CatalogIndex.FacetSet f = new CatalogIndex.FacetSet();
        f.type = "Scripted";
        checkEq("facets: type cut", 135,
                CatalogIndex.applyFacets(pool, f).size());
        f.genre = "Drama";
        int bothManual = 0;
        for (CatalogIndex.Record r : pool) {
            if ("Scripted".equals(r.type) && r.genres.contains("Drama")) {
                bothManual++;
            }
        }
        checkEq("facets: type x genre cut", bothManual,
                CatalogIndex.applyFacets(pool, f).size());
        check("facets: combined cut is the tighter one",
                bothManual <= 135 && bothManual <= dramaManual);

        // decades partition the dated pool, years ride inside them
        int withYear = 0;
        for (CatalogIndex.Record r : pool) {
            if (CatalogIndex.yearOf(r) >= 0) withYear++;
        }
        int viaDecades = 0;
        for (int d : CatalogIndex.decadesIn(pool)) {
            CatalogIndex.FacetSet df = new CatalogIndex.FacetSet();
            df.yearLo = d;
            df.yearHi = d + 9;
            viaDecades += CatalogIndex.applyFacets(pool, df).size();
            check("facets: decade is a decade start (" + d + ")", d % 10 == 0);
        }
        checkEq("facets: decades partition the dated pool",
                withYear, viaDecades);
        int someYear = CatalogIndex.yearOf(pool.get(0));
        for (CatalogIndex.Record r : pool) {
            if (CatalogIndex.yearOf(r) >= 0) {
                someYear = CatalogIndex.yearOf(r);
                break;
            }
        }
        CatalogIndex.FacetSet yf = new CatalogIndex.FacetSet();
        yf.yearLo = someYear;
        yf.yearHi = someYear;
        int yearManual = 0;
        for (CatalogIndex.Record r : pool) {
            if (CatalogIndex.yearOf(r) == someYear) yearManual++;
        }
        checkEq("facets: a single-year cut", yearManual,
                CatalogIndex.applyFacets(pool, yf).size());
        check("facets: yearsIn holds the picked year",
                CatalogIndex.yearsIn(pool,
                        CatalogIndex.decadeOf(someYear)).contains(someYear));

        // "not listed" is a real value, never a guess: it matches
        // exactly the records that list none
        java.util.List<CatalogIndex.Record> pool2 = new ArrayList<>(pool);
        CatalogIndex.Record bare = rec(999999, "Typeless", "", -1);
        pool2.add(bare);
        CatalogIndex.FacetSet nf = new CatalogIndex.FacetSet();
        nf.type = CatalogIndex.NOT_LISTED;
        checkEq("facets: type not-listed cut", 1,
                CatalogIndex.applyFacets(pool2, nf).size());
        checkEq("facets: type not-listed counted", 1,
                CatalogIndex.facetCounts(pool2, "type")
                        .get(CatalogIndex.NOT_LISTED));
        CatalogIndex.FacetSet ynf = new CatalogIndex.FacetSet();
        ynf.yearNotListed = true;
        int noYearManual = 0;
        for (CatalogIndex.Record r : pool2) {
            if (CatalogIndex.yearOf(r) < 0) noYearManual++;
        }
        checkEq("facets: year not-listed cut", noYearManual,
                CatalogIndex.applyFacets(pool2, ynf).size());
        check("facets: hasNoYear sees the bare record",
                CatalogIndex.hasNoYear(pool2));

        // state format: 2 rides the file; a legacy state reads 1
        CatalogIndex.State st2 = new CatalogIndex.State();
        st2.format = CatalogIndex.State.FORMAT_TYPE;
        st2.pagesWalked = 12;
        CatalogIndex.State back =
                CatalogIndex.State.fromJson(st2.toJson().toString());
        checkEq("facets: state format survives the round trip",
                CatalogIndex.State.FORMAT_TYPE, back.format);
        CatalogIndex.State legacy = CatalogIndex.State.fromJson(
                "{\"pagesWalked\":7,\"complete\":true}");
        checkEq("facets: a legacy state reads format 1", 1, legacy.format);
        checkEq("facets: legacy pages still read", 7, legacy.pagesWalked);

        // the record round trip carries the type; a pre-0.11.1
        // bucket (no type key) reads "not listed", honestly empty
        CatalogIndex.Record typed = rec(42, "Typed Show", "2001-01-01", 8.0);
        typed.type = "Animation";
        String bj = CatalogIndex.bucketToJson(
                java.util.Collections.singletonList(typed));
        checkEq("facets: type survives the bucket round trip", "Animation",
                CatalogIndex.bucketFromJson(bj).get(0).type);
        CatalogIndex.Record old = CatalogIndex.Record.fromJson(
                new org.json.JSONObject("{\"id\":7,\"name\":\"Old\"}"));
        checkEq("facets: a pre-0.11.1 record reads type not-listed",
                "", old.type);
    }

    // ------------------------------------------- v0.11.1: sorts

    static String idOrder(java.util.List<CatalogIndex.Record> l) {
        StringBuilder sb = new StringBuilder();
        for (CatalogIndex.Record r : l) {
            if (sb.length() > 0) sb.append(",");
            sb.append(r.id);
        }
        return sb.toString();
    }

    static void sortSuite() throws Exception {
        java.util.List<CatalogIndex.Record> base = new ArrayList<>();
        base.add(rec(3, "beta", "2001-05-01", 7.0));
        base.add(rec(1, "Alpha", "1999-01-01", 9.0));
        base.add(rec(2, "alpha", "", -1));
        base.add(rec(4, "24 Hours", "2020-01-01", 8.0));
        base.add(rec(5, "Zulu", "1980-01-01", 6.0));

        java.util.List<CatalogIndex.Record> az = new ArrayList<>(base);
        CatalogIndex.sortBy(az, CatalogIndex.SortMode.AZ);
        checkEq("sorts: A-Z (digits first, name ties by id)",
                "4,1,2,3,5", idOrder(az));
        java.util.List<CatalogIndex.Record> za = new ArrayList<>(base);
        CatalogIndex.sortBy(za, CatalogIndex.SortMode.ZA);
        checkEq("sorts: Z-A", "5,3,1,2,4", idOrder(za));
        java.util.List<CatalogIndex.Record> nw = new ArrayList<>(base);
        CatalogIndex.sortBy(nw, CatalogIndex.SortMode.NEWEST);
        checkEq("sorts: newest first, unknown year last",
                "4,3,1,5,2", idOrder(nw));
        java.util.List<CatalogIndex.Record> od = new ArrayList<>(base);
        CatalogIndex.sortBy(od, CatalogIndex.SortMode.OLDEST);
        checkEq("sorts: oldest first, unknown year last",
                "5,1,3,4,2", idOrder(od));
        java.util.List<CatalogIndex.Record> best = new ArrayList<>(base);
        CatalogIndex.sortBy(best, CatalogIndex.SortMode.BEST);
        checkEq("sorts: best = the wall order (rating first)",
                "1,4,3,5,2", idOrder(best));

        checkEq("letters: a plain title", 'T',
                CatalogIndex.letterOf("The Wire"));
        checkEq("letters: lowercase files under its letter", 'B',
                CatalogIndex.letterOf("breaking Bad"));
        checkEq("letters: digits file under #", '#',
                CatalogIndex.letterOf("24 Hours"));
        checkEq("letters: empty files under #", '#',
                CatalogIndex.letterOf(""));
        checkEq("letters: the jump index on the A-Z list",
                "3", String.valueOf(
                        CatalogIndex.firstIndexForLetter(az, 'B')));
        checkEq("letters: # sits at the head of an A-Z list",
                "0", String.valueOf(
                        CatalogIndex.firstIndexForLetter(az, '#')));
        checkEq("letters: an absent letter has no landing",
                "-1", String.valueOf(
                        CatalogIndex.firstIndexForLetter(az, 'Q')));
        checkEq("letters: the letters a list holds",
                "[#, A, B, Z]", CatalogIndex.lettersIn(az).toString());

        // the real page, A-Z: monotone, and the jump lands right
        String home = System.getProperty("user.home");
        if (ciSkipIfMissing("sorts", new File(home
                + "/workspace/tala-tv-2026-10-16/work/v100/fixtures/"
                + "shows_page200.json"))) return;
        String page = new String(Files.readAllBytes(new File(home
                + "/workspace/tala-tv-2026-10-16/work/v100/fixtures/"
                + "shows_page200.json").toPath()), "UTF-8");
        java.util.List<CatalogIndex.Record> real = new ArrayList<>();
        for (TvMaze.Show s : TvMaze.parseShows(page)) {
            real.add(CatalogIndex.fromShow(s));
        }
        CatalogIndex.sortBy(real, CatalogIndex.SortMode.AZ);
        boolean monotone = true;
        for (int i = 1; i < real.size(); i++) {
            if (real.get(i - 1).name.compareToIgnoreCase(real.get(i).name) > 0) {
                monotone = false;
            }
        }
        check("sorts: the real page sorts A-Z monotone", monotone);
        int bIdx = CatalogIndex.firstIndexForLetter(real, 'B');
        check("sorts: the jump lands on a B title",
                bIdx > 0 && CatalogIndex.letterOf(real.get(bIdx).name) == 'B'
                        && CatalogIndex.letterOf(real.get(bIdx - 1).name) != 'B');
    }

    // ------------------------------------- v0.11.1: actor search

    static void peopleSuite() throws Exception {
        String home = System.getProperty("user.home");
        String fix = home + "/workspace/tala-tv-2026-10-16/work/v111/fixtures/";
        if (ciSkipIfMissing("people",
                new File(fix, "people_search_cranston.json"),
                new File(fix, "castcredits_14245.json"))) return;
        String pj = new String(Files.readAllBytes(
                new File(fix, "people_search_cranston.json").toPath()), "UTF-8");
        java.util.List<TvMaze.Person> people = TvMaze.parsePeople(pj);
        checkEq("people: the live search shape parses", 2, people.size());
        checkEq("people: the first hit is the man", "Bryan Cranston",
                people.get(0).name);
        checkEq("people: his TVmaze id", 14245, people.get(0).id);

        String cj = new String(Files.readAllBytes(
                new File(fix, "castcredits_14245.json").toPath()), "UTF-8");
        java.util.List<TvMaze.Show> credits = TvMaze.parseCastCredits(cj);
        checkEq("people: his cast credits parse", 11, credits.size());
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        boolean hasBreakingBad = false, hasMalcolm = false;
        String bbType = "", bbRecordType = "";
        for (TvMaze.Show s : credits) {
            ids.add(s.id);
            if (s.id == 169) {
                hasBreakingBad = true;
                bbType = s.type;
                bbRecordType = CatalogIndex.fromShow(s).type;
            }
            if (s.id == 568) hasMalcolm = true;
        }
        checkEq("people: credits dedupe by show", credits.size(), ids.size());
        check("people: Breaking Bad is among them", hasBreakingBad);
        check("people: Malcolm in the Middle is among them", hasMalcolm);
        checkEq("people: a credit's show carries its type",
                "Scripted", bbType);
        checkEq("people: the record keeps the type",
                "Scripted", bbRecordType);
        checkEq("people: cast-credits url shape",
                "https://api.tvmaze.com/people/14245/castcredits?embed=show",
                TvMaze.castCreditsUrl(14245));
        check("people: the search url encodes the query",
                TvMaze.peopleSearchUrl("Bryan Cranston")
                        .contains("q=Bryan+Cranston"));
    }

    // ------------------------------------- v0.11.1: the guide map

    static void guideMapSuite() throws Exception {
        String home = System.getProperty("user.home");
        String text = new String(Files.readAllBytes(new File(home
                + "/workspace/tala-tv-2026-10-16/app/assets/guide_map.tsv")
                .toPath()), "UTF-8");
        java.util.List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\n")) {
            String s2 = line.trim();
            if (s2.isEmpty() || s2.startsWith("#")) continue;
            String[] c = s2.split("\t", -1);
            if (c.length >= 5) rows.add(c);
        }
        checkEq("map: the regenerated map carries 1,345 rows",
                1345, rows.size());
        int verified = 0, pattern = 0;
        java.util.Set<String> files = new java.util.HashSet<>();
        boolean flashRow = false, evidenceClean = true;
        java.util.Map<String, String[]> byId = new java.util.HashMap<>();
        for (String[] r : rows) {
            if ("verified".equals(r[4])) verified++;
            else if ("pattern".equals(r[4])) pattern++;
            else evidenceClean = false;
            if (r[2].contains("Flash")) flashRow = true;
            files.add(r[2]);
            byId.put(r[0], r);
        }
        checkEq("map: verified rows stand", 1228, verified);
        checkEq("map: pattern rows after remediation", 117, pattern);
        check("map: no row points at the dead Flash file", !flashRow);
        check("map: every evidence value is verified or pattern",
                evidenceClean);
        for (String gone : new String[]{"AusbizTV.au", "CNA.sg", "CNN.us",
                "ETNow.in", "SkyNewsWeather.uk", "TRTWorld.tr",
                "TimesNow.in", "i24NEWSEnglishWorld.il", "tickerNews.au"}) {
            check("map: no living bridge, no row (" + gone + ")",
                    !byId.containsKey(gone));
        }
        for (String saved : new String[]{"FoxSportsNews.au", "MSNOW.us",
                "SkyNewsExtra.au"}) {
            String[] r = byId.get(saved);
            check("map: re-preferred onto the living Foxtel file ("
                    + saved + ")", r != null
                    && "https://i.mjh.nz/Foxtel/epg.xml".equals(r[2])
                    && "pattern".equals(r[4]));
        }
        // liveness: the generation record proves every file in the
        // shipped map answered HEAD 200, and Flash answered 404
        if (ciSkipIfMissing("guideMap", new File(home
                + "/workspace/tala-tv-2026-10-16/work/v111/data/"
                + "guide_map_measured_111.json"))) return;
        org.json.JSONObject measured = new org.json.JSONObject(
                new String(Files.readAllBytes(new File(home
                        + "/workspace/tala-tv-2026-10-16/work/v111/data/"
                        + "guide_map_measured_111.json").toPath()), "UTF-8"));
        org.json.JSONObject urlStatus = measured.getJSONObject("url_status");
        boolean allAlive = true;
        for (String u : files) {
            if (!urlStatus.has(u) || urlStatus.getInt(u) != 200) {
                allAlive = false;
            }
        }
        check("map: every shipped file answered HEAD 200 at generation",
                allAlive);
        checkEq("map: the Flash file's recorded answer is 404", 404,
                urlStatus.getInt("https://i.mjh.nz/Flash/epg.xml"));
        checkEq("map: measured coverage agrees with the map", 1345,
                measured.getInt("channels_covered"));
    }
    // ------------------------------------------- v0.12.0: the dub

    /**
     * Subtitle harvest: all three parsers on fixtures, the language
     * a file name declares, and the cleaning law (markup out, words
     * kept). The ASS fixture carries speaker names; the VTT one
     * carries a voice span; the SRT one carries italics + a 2-line
     * cue.
     */
    static void subHarvestSuite() {
        String srt = "1\n00:00:01,000 --> 00:00:04,500\n<i>We leave</i> at dawn.\n\n"
                + "2\n00:00:05,000 --> 00:00:08,250\nNobody must know\nour route.\n\n"
                + "3\n00:00:09,000 --> 00:00:10,000\n   \n";
        SubHarvest.Track t = SubHarvest.parse("ep.en.srt", srt);
        checkEq("harvest srt format", "srt", t.format);
        checkEq("harvest srt drops the empty cue", 2, t.lines.size());
        checkEq("harvest srt strips tags, keeps words",
                "We leave at dawn.", t.lines.get(0).text);
        checkEq("harvest srt joins the two-line cue",
                "Nobody must know our route.", t.lines.get(1).text);
        checkEq("harvest srt timing", 4500L, t.lines.get(0).endMs);

        String vtt = "WEBVTT\n\n00:01.500 --> 00:04.000 align:start position:0%\n"
                + "<v Taro>Stay behind me.</v>\n\n"
                + "00:05.000 --> 00:07.500\n<c.highlight>Run!</c>\n";
        SubHarvest.Track v = SubHarvest.parse("ep.vtt", vtt);
        checkEq("harvest vtt format", "vtt", v.format);
        checkEq("harvest vtt lines", 2, v.lines.size());
        checkEq("harvest vtt voice span names the speaker", "Taro",
                v.lines.get(0).speaker);
        checkEq("harvest vtt named count", 1, v.namedLines);
        checkEq("harvest vtt mm:ss timing", 1500L, v.lines.get(0).startMs);
        checkEq("harvest vtt strips class tags", "Run!", v.lines.get(1).text);

        String ass = "[Script Info]\nTitle: fixture\n\n[Events]\n"
                + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
                + "Dialogue: 0,0:00:02.00,0:00:05.50,Default,Alice,0,0,0,,{\\pos(320,240)}The gate is\\Nunguarded.\n"
                + "Dialogue: 0,0:00:06.00,0:00:09.25,Default,,0,0,0,,Then we run, all of us.\n"
                + "Comment: 0,0:00:10.00,0:00:11.00,Default,Bob,0,0,0,,not dialogue\n";
        SubHarvest.Track a = SubHarvest.parse("ep.ass", ass);
        checkEq("harvest ass format", "ass", a.format);
        checkEq("harvest ass skips comments", 2, a.lines.size());
        checkEq("harvest ass Name field is the speaker", "Alice",
                a.lines.get(0).speaker);
        checkEq("harvest ass centisecond timing", 5500L, a.lines.get(0).endMs);
        checkEq("harvest ass override+break cleaned",
                "The gate is unguarded.", a.lines.get(0).text);
        checkEq("harvest ass comma inside text kept",
                "Then we run, all of us.", a.lines.get(1).text);

        checkEq("lang: plain 2-letter", "ja",
                SubHarvest.langFromName("Show.ja.srt", "Show"));
        checkEq("lang: 3-letter bibliographic", "en",
                SubHarvest.langFromName("Show.eng.srt", "Show"));
        checkEq("lang: forced suffix still speaks", "en",
                SubHarvest.langFromName("Show.en.forced.srt", "Show"));
        checkEq("lang: bare sidecar is silent", "",
                SubHarvest.langFromName("Show.srt", "Show"));
        checkEq("lang: our cooked track is not a source", "",
                SubHarvest.langFromName("Show.tr-en.srt", "Show"));
        checkEq("lang: full word", "ko",
                SubHarvest.langFromName("Show.korean.ass", "Show"));
        checkEq("lang: region tag folds", "en",
                SubHarvest.langFromName("Show.en-US.srt", "Show"));
        checkEq("lang: normalize zho", "zh", SubHarvest.normalizeLang("zho"));
        checkEq("lang: normalize unknown 3-letter stays unknown", "",
                SubHarvest.normalizeLang("xyz"));
        check("harvest names: candidates lead with the bare sidecar",
                SubHarvest.candidateNames("Show.mkv").get(0).equals("Show.srt"));
    }

    /** The DubGate decision table — every plan outcome reachable
     *  and named, routing one hop only. */
    static void dubGateSuite() {
        java.util.Set<String> pairs = new java.util.HashSet<>(
                java.util.Collections.singletonList("ja-en"));
        checkEq("gate route passthrough", DubGate.Route.PASSTHROUGH,
                DubGate.route("en", "en", pairs));
        checkEq("gate route direct", DubGate.Route.DIRECT,
                DubGate.route("ja", "en", pairs));
        checkEq("gate route none (no pivot)", DubGate.Route.NONE,
                DubGate.route("ja", "es", pairs));

        checkEq("gate: subs + pack = cast", DubGate.Plan.SUB_CAST,
                DubGate.decide("ja", false, "en", pairs, true, "ja-en").plan);
        checkEq("gate: subs named, no pack = named cast", DubGate.Plan.SUB_NAMED,
                DubGate.decide("ja", true, "en", pairs, false, "ja-en").plan);
        checkEq("gate: subs plain, no pack = solo", DubGate.Plan.SUB_SOLO,
                DubGate.decide("ja", false, "en", pairs, false, "ja-en").plan);
        checkEq("gate: subs already in the hearing language cast too",
                DubGate.Plan.SUB_CAST,
                DubGate.decide("en", false, "en", pairs, true, null).plan);
        checkEq("gate: unnamed subs ask, never guess",
                DubGate.Plan.SUB_LANG_UNKNOWN,
                DubGate.decide("", false, "en", pairs, true, "ja-en").plan);
        DubGate.Decision mt = DubGate.decide("ja", false, "es", pairs, true, "ja-en");
        checkEq("gate: unroutable subs need the pair pack",
                DubGate.Plan.NEEDS_MT, mt.plan);
        checkEq("gate: the missing pair is named", "ja-es", mt.detail);
        checkEq("gate: no subs, no engine = needs engine",
                DubGate.Plan.NEEDS_ENGINE,
                DubGate.decide(null, false, "en", pairs, true, null).plan);
        checkEq("gate: no subs + engine + pack = ASR cast",
                DubGate.Plan.ASR_CAST,
                DubGate.decide(null, false, "en", pairs, true, "ja-en").plan);
        checkEq("gate: no subs + engine, no pack = ASR solo",
                DubGate.Plan.ASR_SOLO,
                DubGate.decide(null, false, "en", pairs, false, "ja-en").plan);
        checkEq("gate: ASR road to another hearing language needs its pack",
                DubGate.Plan.NEEDS_MT,
                DubGate.decide(null, false, "es", pairs, true, "ja-en").plan);
        check("gate: cast plans cook", DubGate.decide("ja", false, "en",
                pairs, true, "ja-en").cooks());
        check("gate: needs-mt does not cook", !mt.cooks());
    }

    /**
     * VoiceBook, James's persistence law: a voice cast once answers
     * through the whole series; profiles scope per show AND per
     * season; a season's fork never answers for another season;
     * near never auto-assigns; per-book calibration rides the JSON.
     */
    static void voiceBookSeasons() throws Exception {
        float[] a = norm(new float[]{1f, 0.1f, 0f, 0f});
        float[] a2 = norm(new float[]{0.95f, 0.2f, 0.05f, 0f});
        float[] b = norm(new float[]{0f, 1f, 0.1f, 0f});
        VoiceBook.Book book = new VoiceBook.Book();
        book.showKey = "show:fixture";
        VoiceBook.CastEntry ea = VoiceBook.enroll(book, "spk-a", a);
        checkEq("vb: enroll lands series-wide", 0, ea.season);
        checkEq("vb: the series voice answers in season 3",
                VoiceBook.MatchKind.MATCH,
                VoiceBook.match(book, a2, 3).kind);
        // a season-2 fork (a recast, near the original but distinct)
        float[] aFork = norm(new float[]{0.8f, 0.45f, 0f, 0f});
        VoiceBook.CastEntry ef = VoiceBook.enroll(book, "spk-a-s2", aFork, 2);
        checkEq("vb: fork kept its print", "spk-a-s2", ef.speakerKey);
        VoiceBook.Match s1 = VoiceBook.match(book, a, 1);
        checkEq("vb: season 1 still answers with the series voice",
                "spk-a", s1.entry == null ? "" : s1.entry.speakerKey);
        VoiceBook.Match s2 = VoiceBook.match(book, aFork, 2);
        checkEq("vb: season 2 pool includes its own fork",
                VoiceBook.MatchKind.MATCH, s2.kind);
        checkEq("vb: an unrelated voice is NEW, never near-swapped",
                VoiceBook.MatchKind.NEW, VoiceBook.match(book, b, 1).kind);
        // per-book calibration: a book calibrated for the diarizer's
        // cosine scale matches prints the classic scale would call new
        VoiceBook.Book diar = new VoiceBook.Book();
        diar.showKey = "show:diar";
        diar.matchMin = 0.55;
        diar.nearMin = 0.42;
        float[] p = norm(new float[]{1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f});
        float[] q = norm(new float[]{0.62f, 0.55f, 0.4f, 0.3f, 0.2f, 0.1f, 0.05f, 0f});
        VoiceBook.enroll(diar, "spk-1", p);
        double cos = VoiceBook.cosine(p, q);
        check("vb: fixture pair sits between the two scales ("
                + String.format("%.3f", cos) + ")",
                cos >= 0.55 && cos < VoiceBook.MATCH_MIN);
        checkEq("vb: diar-calibrated book MATCHes it",
                VoiceBook.MatchKind.MATCH, VoiceBook.match(diar, q, 1).kind);
        VoiceBook.Book classic = new VoiceBook.Book();
        VoiceBook.enroll(classic, "spk-1", p);
        check("vb: the classic scale does NOT match it",
                VoiceBook.match(classic, q, 1).kind != VoiceBook.MatchKind.MATCH);
        // JSON round trip: season + calibration + print all survive
        String json = VoiceBook.toJson(book);
        VoiceBook.Book back = VoiceBook.fromJson(json);
        checkEq("vb: round trip keeps the fork's season", 2,
                back.cast.get(1).season);
        String json2 = VoiceBook.toJson(diar);
        VoiceBook.Book back2 = VoiceBook.fromJson(json2);
        checkEq("vb: round trip keeps calibration", "0.55",
                String.valueOf(back2.matchMin));
        checkEq("vb: round trip keeps the print's dimension", 8,
                back2.cast.get(0).voiceprint.length);
        // the cooked record carries the fit disclosure
        CookedSubs.Line fl = new CookedSubs.Line();
        fl.startMs = 0; fl.endMs = 2000; fl.text = "x"; fl.fit = 1;
        java.util.List<CookedSubs.Line> one =
                java.util.Collections.singletonList(fl);
        String cj = CookedSubs.writeJson("en", one);
        java.util.List<CookedSubs.Line> parsed = CookedSubs.parseJson(cj);
        checkEq("vb: fit disclosure survives the record", 1, parsed.get(0).fit);
        CookedSubs.Line plain = new CookedSubs.Line();
        plain.startMs = 0; plain.endMs = 1000; plain.text = "y";
        String cj2 = CookedSubs.writeJson("en",
                java.util.Collections.singletonList(plain));
        check("vb: natural lines write no fit key (back-compatible)",
                !cj2.contains("\"fit\""));
        checkEq("vb: absent fit reads as natural", 0,
                CookedSubs.parseJson(cj2).get(0).fit);
    }

    static float[] norm(float[] v) {
        double n = 0;
        for (float f : v) n += f * (double) f;
        n = Math.sqrt(n);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / n);
        return out;
    }

    /** The dubbed-episodes shelf index round-trips through its TSV. */
    static void dubShelfSuite() {
        DubShelf.Entry e = new DubShelf.Entry();
        e.id = "show:fixture|Ep 1|en";
        e.cookedAtSec = 1790000000L;
        e.showKey = "show:fixture";
        e.showTitle = "Fixture Show";
        e.season = 1; e.episode = 2; e.targetLang = "en";
        e.videoUrl = "/sdcard/Download/Fixture Show S01E02.mkv";
        e.dubPath = "/data/dubbed/x.dub-en.wav";
        e.srtPath = "/data/dubbed/x.tr-en.srt";
        e.jsonPath = "/data/dubbed/x.tr-en.json";
        e.lineCount = 42; e.castCount = 3; e.nearCount = 1;
        String tsv = DubShelf.toTsv(java.util.Collections.singletonList(e));
        java.util.List<DubShelf.Entry> back = DubShelf.parse(tsv);
        checkEq("shelf: one row back", 1, back.size());
        checkEq("shelf: display name speaks season/episode/lang",
                "Fixture Show S01E02 → EN", back.get(0).displayName());
        checkEq("shelf: numbers survive", 42, back.get(0).lineCount);
        checkEq("shelf: near count survives", 1, back.get(0).nearCount);
        checkEq("shelf: tabs in a title are sanitized flat", 1,
                DubShelf.parse(DubShelf.toTsv(java.util.Collections
                        .singletonList(back.get(0)))).size());
        checkEq("shelf: empty text is an empty shelf", 0,
                DubShelf.parse("").size());
    }

    /** The REAL shipped voice pack validates, calibrates, and
     *  refuses tampering — DiarPack's fail-closed law. */
    static void diarPackSuite(String yourFiles) throws Exception {
        File pack = new File(yourFiles, "sandy-diarize.pack");
        if (ciSkipIfMissing("diarpack", pack)) return;
        check("diarpack: the shipped pack exists", pack.isFile());
        if (!pack.isFile()) return;
        DiarPack.Result r;
        try (java.io.FileInputStream in = new java.io.FileInputStream(pack)) {
            r = DiarPack.validateAndExtract(in, null);
        }
        check("diarpack: the shipped pack validates: " + r.errors, r.ok);
        checkEq("diarpack: segmentation model named",
                "pyannote-segmentation-3.0", r.segKind);
        checkEq("diarpack: speaker model named", "eres2net-3dspeaker", r.embKind);
        checkEq("diarpack: cluster threshold", "0.5",
                String.valueOf(r.clusterThreshold));
        checkEq("diarpack: voicebook match calibration", "0.55",
                String.valueOf(r.vbMatch));
        checkEq("diarpack: voicebook near calibration", "0.42",
                String.valueOf(r.vbNear));
        check("diarpack: both models listed",
                r.files.containsKey("seg/model.onnx")
                        && r.files.containsKey("emb/model.onnx"));
        // tamper: one flipped byte deep in the archive must refuse
        byte[] bytes = Files.readAllBytes(pack.toPath());
        bytes[bytes.length / 2] ^= 0x40;
        DiarPack.Result bad = DiarPack.validateAndExtract(
                new java.io.ByteArrayInputStream(bytes), null);
        check("diarpack: a tampered pack is refused", !bad.ok);
        DiarPack.Result junk = DiarPack.validateAndExtract(
                new java.io.ByteArrayInputStream("not a pack".getBytes("UTF-8")),
                null);
        check("diarpack: junk is refused", !junk.ok);
    }

    /**
     * The diarization organ, end to end through the REAL shipped
     * pack: the pack extracts, both models bind, and over the
     * three-speaker fixture (ground truth in work/v120/diar) the
     * pipeline must find three voices, assign the subtitle-window
     * lines to them correctly, enroll them in a VoiceBook, and
     * RECOGNIZE them in a second pass — James's persistence law
     * exercised, not asserted. The single-narrator Toshishun slice
     * is the negative control: one voice must stay one voice.
     */
    static void diarE2ESuite(String yourFiles) throws Exception {
        String home = System.getProperty("user.home");
        File diarDir = new File(home
                + "/workspace/tala-tv-2026-10-16/work/v120/diar");
        File pack = new File(yourFiles, "sandy-diarize.pack");
        File fixture = new File(diarDir, "fixture.wav");
        if (ciSkipIfMissing("e2e diar", pack, fixture,
                new File(diarDir, "fixture_truth.json"),
                new File(diarDir, "ref_embeddings.bin"),
                new File(diarDir, "toshi60.wav"))) return;
        check("e2e diar: fixture present", fixture.isFile() && pack.isFile());
        if (!fixture.isFile() || !pack.isFile()) return;

        File extract = Files.createTempDirectory("diarpack").toFile();
        DiarPack.Result pr;
        try (java.io.FileInputStream in = new java.io.FileInputStream(pack)) {
            pr = DiarPack.validateAndExtract(in, extract);
        }
        check("e2e diar: pack extracts for binding", pr.ok);

        // ground truth turns
        String truthText = new String(Files.readAllBytes(
                new File(diarDir, "fixture_truth.json").toPath()), "UTF-8");
        org.json.JSONArray turns = new org.json.JSONObject(truthText)
                .getJSONArray("turns");
        int n = turns.length();

        // fbank fidelity: the shipped bridge vs the reference extractor
        byte[] raw = Files.readAllBytes(
                new File(diarDir, "ref_embeddings.bin").toPath());
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[][] ref = new float[n][512];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < 512; j++) ref[i][j] = bb.getFloat();
        }
        WavPcm wav = new WavPcm(fixture);
        double minCos = 2;
        try (DiarOnnx diar = DiarOnnx.open(extract)) {
            check("e2e diar: both models bind from the shipped pack",
                    diar.isBound());
            for (int i = 0; i < n; i++) {
                org.json.JSONObject t = turns.getJSONObject(i);
                float[] audio = wav.read(t.getDouble("start"),
                        t.getDouble("end") - t.getDouble("start"));
                float[] emb = diar.embed(audio);
                double c = VoiceBook.cosine(emb, ref[i]);
                if (c < minCos) minCos = c;
            }
            check("e2e diar: fbank bridge reproduces the reference "
                    + "extractor (min cos " + String.format("%.3f", minCos) + ")",
                    minCos >= 0.97);

            // the subtitle road: lines are the truth turns' windows;
            // the audio only witnesses WHO speaks each
            float[][][] logits = diar.segment(wav, null);
            boolean[][][] act = DiarCore.decode(logits);
            java.util.List<DiarCore.Observation> obs = DiarCore.events(DiarCore.observations(act));
            check("e2e diar: speech events gathered (" + obs.size() + ")",
                    obs.size() >= 12);
            for (DiarCore.Observation ob : obs) {
                float[] audio = DiarCore.observationAudio(ob,
                        (s, d) -> wav.read(s, d));
                if (audio.length >= DiarCore.SAMPLE_RATE / 4) {
                    ob.embedding = diar.embed(audio);
                }
            }
            java.util.List<DiarCore.Cluster> clusters =
                    DiarCore.cluster(obs, 0.50);
            checkEq("e2e diar: three voices found for three speakers",
                    3, clusters.size());

            // per-turn assignment accuracy (best cluster<->speaker map)
            int[] lineCluster = new int[n];
            String[] lineSpk = new String[n];
            for (int i = 0; i < n; i++) {
                org.json.JSONObject t = turns.getJSONObject(i);
                lineSpk[i] = t.getString("speaker");
                lineCluster[i] = DiarCore.assignLine(clusters,
                        t.getDouble("start"), t.getDouble("end"));
            }
            int bestCorrect = 0;
            String[] abc = {"A", "B", "C"};
            int[][] perm = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2},
                    {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
            for (int[] p : perm) {
                int correct = 0;
                for (int i = 0; i < n; i++) {
                    if (lineCluster[i] >= 0 && lineCluster[i] < 3
                            && abc[p[lineCluster[i]]].equals(lineSpk[i])) correct++;
                }
                bestCorrect = Math.max(bestCorrect, correct);
            }
            check("e2e diar: lines assigned to the right voice ("
                    + bestCorrect + "/" + n + ")", bestCorrect >= n - 2);

            // the VoiceBook law across episodes: episode one's
            // casting must MATCH episode two's voices outright
            VoiceBook.Book book = new VoiceBook.Book();
            book.showKey = "show:fixture";
            book.matchMin = 0.55;
            book.nearMin = 0.42;
            java.util.Map<Integer, VoiceBook.CastEntry> byCluster =
                    new java.util.LinkedHashMap<>();
            for (int ci = 0; ci < clusters.size(); ci++) {
                byCluster.put(ci, VoiceBook.enroll(book, "dub-spk-" + (ci + 1),
                        clusters.get(ci).centroid, 0));
            }
            int matched = 0;
            for (int ci = 0; ci < clusters.size(); ci++) {
                VoiceBook.Match m = VoiceBook.match(book,
                        clusters.get(ci).centroid, 1);
                if (m.kind == VoiceBook.MatchKind.MATCH
                        && m.entry == byCluster.get(ci)) matched++;
            }
            checkEq("e2e diar: episode two's voices match episode one's book",
                    clusters.size(), matched);

            // negative control: one narrator must stay one voice
            File toshi = new File(diarDir, "toshi60.wav");
            check("e2e diar: narrator control present", toshi.isFile());
            if (toshi.isFile()) {
                WavPcm tw = new WavPcm(toshi);
                float[][][] tl = diar.segment(tw, null);
                java.util.List<DiarCore.Observation> tobs =
                        DiarCore.events(DiarCore.observations(DiarCore.decode(tl)));
                for (DiarCore.Observation ob : tobs) {
                    float[] audio = DiarCore.observationAudio(ob,
                            (s, d) -> tw.read(s, d));
                    if (audio.length >= DiarCore.SAMPLE_RATE / 4) {
                        ob.embedding = diar.embed(audio);
                    }
                }
                java.util.List<DiarCore.Cluster> tc =
                        DiarCore.cluster(tobs, 0.50);
                checkEq("e2e diar: one narrator stays one cluster",
                        1, tc.size());
            }
        }
    }
    // ------------------------------------------------- v0.12.1 suites

    static void timerFrameSuite() {
        java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
        // 2026-10-20 is a Tuesday; day start in UTC.
        java.util.Calendar day = java.util.Calendar.getInstance(utc);
        day.set(2026, 9, 20, 0, 0, 0);
        day.set(java.util.Calendar.MILLISECOND, 0);
        long dayStart = day.getTimeInMillis();

        long[] f = TimerFrame.compose(dayStart, 20, 0, 21, 30, utc);
        checkEq("timerframe: same-day duration",
                90, TimerFrame.durationMinutes(f[0], f[1]));
        check("timerframe: same-day does not span midnight",
                !TimerFrame.spansMidnight(f[0], f[1], utc));
        checkEq("timerframe: summary",
                "Tue, Oct 20 · 8:00 PM – 9:30 PM",
                TimerFrame.summary(f[0], f[1], utc));

        // End at/before start rolls to the next day (late movie).
        long[] g = TimerFrame.compose(dayStart, 23, 0, 1, 0, utc);
        checkEq("timerframe: overnight duration",
                120, TimerFrame.durationMinutes(g[0], g[1]));
        check("timerframe: overnight spans midnight",
                TimerFrame.spansMidnight(g[0], g[1], utc));
        checkEq("timerframe: overnight summary shows both days",
                "Tue, Oct 20 · 11:00 PM – Wed, Oct 21 · 1:00 AM",
                TimerFrame.summary(g[0], g[1], utc));

        // Equal start/end = a full day, never zero.
        long[] h = TimerFrame.compose(dayStart, 20, 0, 20, 0, utc);
        checkEq("timerframe: equal times roll a full day",
                1440, TimerFrame.durationMinutes(h[0], h[1]));

        checkEq("timerframe: duration helper",
                f[0] + 45 * 60_000L, TimerFrame.stopFromDuration(f[0], 45));
    }

    static void liveGroupsSuite() throws Exception {
        String home = System.getProperty("user.home");
        // The REAL shipped bridge map.
        String text = new String(Files.readAllBytes(new File(home
                + "/workspace/tala-tv-2026-10-16/app/assets/guide_map.tsv")
                .toPath()), "UTF-8");
        java.util.List<GuideStore.MapRow> rows = new ArrayList<>();
        for (String line : text.split("\n")) {
            String s2 = line.trim();
            if (s2.isEmpty() || s2.startsWith("#")) continue;
            String[] c = s2.split("\t", -1);
            if (c.length >= 5) {
                GuideStore.MapRow r = new GuideStore.MapRow();
                r.tvgId = c[0];
                r.fileUrl = c[2];
                r.evidence = c[4];
                rows.add(r);
            }
        }
        checkEq("livegroups: real map rows", 1345, rows.size());

        // The O(1) index the guide screen now uses.
        java.util.Map<String, GuideStore.MapRow> idx =
                GuideStore.indexById(rows);
        checkEq("livegroups: index covers every row", 1345, idx.size());
        check("livegroups: index finds a known id",
                idx.get("PlutoTV.us") != null
                        || idx.values().iterator().next() != null);

        // Service names come off the file trees — real ones.
        checkEq("livegroups: pluto file names its service",
                "Pluto TV", LiveGroups.serviceNameForFile(
                        "https://i.mjh.nz/PlutoTV/all.xml"));
        checkEq("livegroups: samsung file names its service",
                "Samsung TV Plus", LiveGroups.serviceNameForFile(
                        "https://i.mjh.nz/SamsungTVPlus/all.xml"));
        checkEq("livegroups: generic iptvx file is not a service",
                "", LiveGroups.serviceNameForFile(
                        "https://epg.iptvx.one/all.xml"));
        checkEq("livegroups: country file is not a service",
                "", LiveGroups.serviceNameForFile(
                        "https://i.mjh.nz/au.xml"));

        java.util.Map<String, String> byBase =
                LiveGroups.serviceByBaseId(rows);
        int plutoBridged = 0;
        for (String v : byBase.values()) if ("Pluto TV".equals(v)) plutoBridged++;
        check("livegroups: the bridge really carries Pluto channels ("
                + plutoBridged + ")", plutoBridged >= 400);

        // The REAL built-in channel cache (12,883 channels).
        if (ciSkipIfMissing("liveGroups", new File(home
                + "/workspace/tala-tv-2026-10-16/work/v020/index.country.m3u"))) return;
        String m3u = new String(Files.readAllBytes(new File(home
                + "/workspace/tala-tv-2026-10-16/work/v020/index.country.m3u")
                .toPath()), "UTF-8");
        java.util.List<M3uParser.Channel> chans = M3uParser.parse(
                new java.io.ByteArrayInputStream(m3u.getBytes("UTF-8")),
                "index.country");
        check("livegroups: real channel cache parsed (" + chans.size() + ")",
                chans.size() > 12000);

        java.util.Map<String, java.util.List<M3uParser.Channel>> bySvc =
                LiveGroups.groupByService(chans, byBase);
        check("livegroups: Pluto TV tile exists with real channels",
                bySvc.containsKey("Pluto TV")
                        && bySvc.get("Pluto TV").size() >= 400);
        check("livegroups: Plex tile exists",
                bySvc.containsKey("Plex")
                        && bySvc.get("Plex").size() >= 200);
        int svcTotal = 0;
        for (java.util.List<M3uParser.Channel> v : bySvc.values()) {
            svcTotal += v.size();
        }
        check("livegroups: service tiles cover a real share (" + svcTotal + ")",
                svcTotal >= 800);

        if (ciSkipIfMissing("liveGroups", new File(home
                + "/workspace/tala-tv-2026-10-16/app/assets/channels_map.tsv"))) return;
        String mapText = new String(Files.readAllBytes(new File(home
                + "/workspace/tala-tv-2026-10-16/app/assets/channels_map.tsv")
                .toPath()), "UTF-8");
        ChannelNames.setMap(ChannelNames.parseMapTsv(mapText));
        for (M3uParser.Channel c : chans) ChannelNames.applyTo(c);
        java.util.Map<String, java.util.List<M3uParser.Channel>> bySec =
                LiveGroups.groupBySection(chans);
        check("livegroups: News section tile is real",
                bySec.containsKey("News") && bySec.get("News").size() >= 500);
        check("livegroups: Movies section tile is real",
                bySec.containsKey("Movies")
                        && bySec.get("Movies").size() >= 300);
        check("livegroups: Music section tile is real",
                bySec.containsKey("Music")
                        && bySec.get("Music").size() >= 400);

        // section derivation: first canonical category only.
        M3uParser.Channel two = new M3uParser.Channel();
        two.canonDef = "News · Politics";
        checkEq("livegroups: section = first canonical category",
                "News", LiveGroups.sectionsOf(two).get(0));
        M3uParser.Channel und = new M3uParser.Channel();
        und.canonDef = "";
        und.group = "Undefined";
        check("livegroups: Undefined yields no section",
                LiveGroups.sectionsOf(und).isEmpty());
    }

    static SourceFinder.Source src(String url, int tier) {
        SourceFinder.Source s = new SourceFinder.Source();
        s.url = url;
        s.qualityTier = tier;
        s.qualityLabel = PipeMeter.tierLabel(tier);
        return s;
    }

    static void autoPlaySuite() {
        java.util.Map<String, Integer> health = new java.util.HashMap<>();
        java.util.List<SourceFinder.Source> srcs = new ArrayList<>();
        srcs.add(src("http://x/uhd", 6));
        srcs.add(src("http://x/hd", 5));
        srcs.add(src("http://x/sd", 2));
        srcs.add(src("http://x/dead", 5));
        health.put("http://x/uhd", LinkHealth.TIER_PLAYS);
        health.put("http://x/hd", LinkHealth.TIER_PLAYS);
        health.put("http://x/sd", LinkHealth.TIER_PLAYS);
        health.put("http://x/dead", LinkHealth.TIER_DEAD);
        java.util.List<AutoPlay.Cand> cands =
                AutoPlay.candidates(srcs, health, "English", "eng");

        // A proven-dead source is never the pick, whatever its tier.
        AutoPlay.Pick p = AutoPlay.pick(cands, AutoPlay.Posture.ULTRA,
                AutoPlay.HEALTH_UNKNOWN);
        check("autoplay: ultra picks the best living source",
                p != null && "http://x/uhd".equals(p.source.url) && !p.offer);

        // LOW: the lowest adequate working source wins (fast start);
        // the dead 1080p stays dead.
        p = AutoPlay.pick(cands, AutoPlay.Posture.LOW,
                AutoPlay.HEALTH_UNKNOWN);
        check("autoplay: low-first picks the lowest working source",
                p != null && "http://x/sd".equals(p.source.url) && !p.offer);

        // BALANCED under a measured 480p pipe cap: the cap rules.
        java.util.Map<String, Integer> h2 = new java.util.HashMap<>(health);
        java.util.List<AutoPlay.Cand> c2 =
                AutoPlay.candidates(srcs, h2, "English", "eng");
        p = AutoPlay.pick(c2, AutoPlay.Posture.BALANCED, 2);
        check("autoplay: balanced respects the measured pipe cap",
                p != null && "http://x/sd".equals(p.source.url) && !p.offer);

        // Below the floor: nothing at/above 480p alive -> the pick
        // is an OFFER, never a silent serve.
        java.util.List<SourceFinder.Source> poor = new ArrayList<>();
        poor.add(src("http://x/cam", -1));
        poor.add(src("http://x/low", 1));
        java.util.Map<String, Integer> h3 = new java.util.HashMap<>();
        h3.put("http://x/cam", LinkHealth.TIER_PLAYS);
        h3.put("http://x/low", LinkHealth.TIER_PLAYS);
        p = AutoPlay.pick(AutoPlay.candidates(poor, h3, "English", "eng"),
                AutoPlay.Posture.BALANCED, AutoPlay.HEALTH_UNKNOWN);
        check("autoplay: below-floor best is offered, not served",
                p != null && p.offer && "http://x/low".equals(p.source.url));

        // All dead -> no pick at all (the list is the honest answer).
        java.util.Map<String, Integer> h4 = new java.util.HashMap<>();
        h4.put("http://x/cam", LinkHealth.TIER_DEAD);
        h4.put("http://x/low", LinkHealth.TIER_DEAD);
        p = AutoPlay.pick(AutoPlay.candidates(poor, h4, "English", "eng"),
                AutoPlay.Posture.BALANCED, AutoPlay.HEALTH_UNKNOWN);
        check("autoplay: all dead yields no pick",
                p == null || p.source == null);

        // Language first: a matched source restricts the pool even
        // at a lower tier; an unmatched pool plays unmatched.
        SourceFinder.Source en = src("http://x/en-sd", 2);
        en.langFacet = "English";
        SourceFinder.Source es = src("http://x/es-uhd", 6);
        es.langFacet = "Spanish";
        java.util.List<SourceFinder.Source> mix = new ArrayList<>();
        mix.add(en);
        mix.add(es);
        java.util.Map<String, Integer> h5 = new java.util.HashMap<>();
        h5.put("http://x/en-sd", LinkHealth.TIER_PLAYS);
        h5.put("http://x/es-uhd", LinkHealth.TIER_PLAYS);
        p = AutoPlay.pick(AutoPlay.candidates(mix, h5, "English", "eng"),
                AutoPlay.Posture.ULTRA, AutoPlay.HEALTH_UNKNOWN);
        check("autoplay: the chosen language wins over raw quality",
                p != null && "http://x/en-sd".equals(p.source.url)
                        && p.langMatched);
        p = AutoPlay.pick(AutoPlay.candidates(mix, h5, "French", "fra"),
                AutoPlay.Posture.ULTRA, AutoPlay.HEALTH_UNKNOWN);
        check("autoplay: no language evidence -> best overall plays",
                p != null && "http://x/es-uhd".equals(p.source.url)
                        && !p.langMatched);

        // Archive MARC evidence counts as a match too.
        SourceFinder.Source ia = src("http://x/ia", 3);
        ia.langMarc = "eng";
        java.util.List<SourceFinder.Source> mix2 = new ArrayList<>();
        mix2.add(ia);
        mix2.add(es);
        p = AutoPlay.pick(AutoPlay.candidates(mix2, h5, "English", "eng"),
                AutoPlay.Posture.ULTRA, AutoPlay.HEALTH_UNKNOWN);
        check("autoplay: archive MARC language matches",
                p != null && "http://x/ia".equals(p.source.url));

        // Posture parsing: user-facing values, safe default.
        check("autoplay: posture parse",
                AutoPlay.parsePosture("low") == AutoPlay.Posture.LOW
                        && AutoPlay.parsePosture("ultra")
                                == AutoPlay.Posture.ULTRA
                        && AutoPlay.parsePosture("junk")
                                == AutoPlay.Posture.BALANCED
                        && AutoPlay.parsePosture(null)
                                == AutoPlay.Posture.BALANCED);
    }

    static void estimateSuite() {
        // No measured basis -> NO number (the law's first half).
        checkEq("estimate: no rate, no number",
                -1, EstimateMath.estimateMs(1_000_000, 0));
        checkEq("estimate: negative rate, no number",
                -1, EstimateMath.estimateMs(1_000_000, -5));
        // 1 MB at 1 MB/s = 1000 ms raw; padded once by 1/6 -> 1166.
        checkEq("estimate: measured basis, one modest pad",
                1166, EstimateMath.estimateMs(1_000_000, 1_000_000.0));
        checkEq("estimate: pad is exactly one sixth",
                700, EstimateMath.pad(600));
        checkEq("estimate: pad of nothing is nothing",
                0, EstimateMath.pad(0));
        check("estimate: format names minutes",
                EstimateMath.formatDuration(30 * 60_000L).contains("30 min"));
        checkEq("estimate: no basis formats as no claim",
                "", EstimateMath.formatDuration(-1));
    }

    static void pipeSuite() {
        checkEq("pipe: unmeasured has no cap", -1, PipeMeter.capTierFor(-1));
        checkEq("pipe: unmeasured class is unknown", 0, PipeMeter.pipeClass(0));
        checkEq("pipe: 1 Mbps caps at SD", 1, PipeMeter.capTierFor(1_000_000));
        checkEq("pipe: 3 Mbps caps at 480p", 2, PipeMeter.capTierFor(3_000_000));
        checkEq("pipe: 5 Mbps caps at 720p", 4, PipeMeter.capTierFor(5_000_000));
        checkEq("pipe: 12 Mbps caps at 1080p", 5, PipeMeter.capTierFor(12_000_000));
        checkEq("pipe: 30 Mbps caps at 2160p", 6, PipeMeter.capTierFor(30_000_000));
        checkEq("pipe: slow class", 1, PipeMeter.pipeClass(1_500_000));
        checkEq("pipe: fast class", 3, PipeMeter.pipeClass(20_000_000));
        checkEq("pipe: tier labels", "480p", PipeMeter.tierLabel(2));
        checkEq("pipe: unknown tier is named unknown",
                "unknown quality", PipeMeter.tierLabel(0));
    }

    static void crashReaderSuite() throws Exception {
        // The reader contract the Settings view stands on: newest
        // first, readable text, "" when nothing is filed. Exercised
        // against the pure file helpers via a temp dir stand-in is
        // not possible (Context-bound), so pin the stamp + the
        // share-intent text path through CrashGuard's statics.
        check("crash: stamp is 0.18.0/24",
                CrashGuard.VERSION_STAMP.contains("0.18.0")
                        && CrashGuard.VERSION_STAMP.contains("24"));
    }

    // ------------------------------------------------- v0.13.0 suites

    static List<VoiceLangs.Row> shippedVoiceRows() throws Exception {
        File asset = new File(System.getProperty("user.home")
                + "/workspace/tala-tv-2026-10-16/app/assets/voice_langs.tsv");
        List<VoiceLangs.Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(asset.toPath(),
                java.nio.charset.StandardCharsets.UTF_8)) {
            VoiceLangs.Row r = VoiceLangs.parseLine(line);
            if (r != null) rows.add(r);
        }
        return rows;
    }

    /** The measured table, pinned: the shipped asset must carry
     *  exactly the rows the measurement established (work/v130/
     *  coverage_table.tsv) — 52 pack languages, Korean the one
     *  uroman model, 30 with no MMS model at all. */
    static void voiceLangsSuite() throws Exception {
        List<VoiceLangs.Row> rows = shippedVoiceRows();
        checkEq("voice: 83 named catalogue languages", 83, rows.size());
        int nat = 0, uro = 0, non = 0;
        java.util.Set<String> codes = new java.util.TreeSet<>();
        for (VoiceLangs.Row r : rows) {
            if ("native".equals(r.kind)) { nat++; codes.add(r.packCode); }
            else if ("uroman".equals(r.kind)) uro++;
            else if ("none".equals(r.kind)) non++;
            check("voice: row has an iso1: " + r.tvName, !r.iso1.isEmpty());
            if ("native".equals(r.kind)) {
                check("voice: native row carries its pack code: " + r.tvName,
                        !r.packCode.isEmpty());
            } else {
                check("voice: non-native row has NO pack code: " + r.tvName,
                        r.packCode.isEmpty());
            }
        }
        checkEq("voice: 52 native (pack) languages", 52, nat);
        checkEq("voice: 1 uroman language", 1, uro);
        checkEq("voice: 30 with no MMS model", 30, non);
        checkEq("voice: the measured pack code set",
                "ara ben bul cat che cym deu div ell eng eus fas fij fin fra guj "
                        + "heb hin hun ind isl jav kan kaz khm kir lao lat lav mal "
                        + "mar mlg mon mya nld pan pol por ron rus spa sqi swe swh "
                        + "tam tel tgl tha tur ukr vie yor",
                String.join(" ", codes));
        VoiceLangs.Row kor = VoiceLangs.byTvName(rows, "Korean");
        check("voice: Korean is the uroman row, unpackable",
                kor != null && "uroman".equals(kor.kind) && kor.packCode.isEmpty());
        VoiceLangs.Row jpn = VoiceLangs.byTvName(rows, "Japanese");
        check("voice: Japanese has no MMS model (measured)",
                jpn != null && "none".equals(jpn.kind));
        checkEq("voice: Russian pack code", "rus",
                VoiceLangs.byTvName(rows, "Russian").packCode);
        checkEq("voice: Arabic pack code", "ara",
                VoiceLangs.byTvName(rows, "Arabic").packCode);
        checkEq("voice: Persian pack code", "fas",
                VoiceLangs.byTvName(rows, "Persian").packCode);
        checkEq("voice: Latvian pack code", "lav",
                VoiceLangs.byTvName(rows, "Latvian").packCode);
        checkEq("voice: Mongolian pack code", "mon",
                VoiceLangs.byTvName(rows, "Mongolian").packCode);
        checkEq("voice: Albanian pack code", "sqi",
                VoiceLangs.byTvName(rows, "Albanian").packCode);
        checkEq("voice: byIso1 finds Thai", "Thai",
                VoiceLangs.byIso1(rows, "th").tvName);
        check("voice: parseLine drops comments",
                VoiceLangs.parseLine("# a comment") == null);
        check("voice: parseLine drops the none-listed bucket",
                VoiceLangs.parseLine("(none listed)\t\t\t-") == null);
    }

    /** Resolution order, driven off the REAL shipped rows:
     *  pack > system > none, and the uroman language can never
     *  resolve to a pack even if a same-named code were installed. */
    static void voiceResolveSuite() throws Exception {
        List<VoiceLangs.Row> rows = shippedVoiceRows();
        VoiceLangs.Row rus = VoiceLangs.byTvName(rows, "Russian");
        VoiceLangs.Row kor = VoiceLangs.byTvName(rows, "Korean");
        VoiceLangs.Row jpn = VoiceLangs.byTvName(rows, "Japanese");
        java.util.Set<String> rusIn = new java.util.HashSet<>(
                java.util.Collections.singletonList("rus"));
        java.util.Set<String> sysRu = new java.util.HashSet<>(
                java.util.Collections.singletonList("ru"));
        java.util.Set<String> none = new java.util.HashSet<>();
        checkEq("resolve: installed pack speaks first", VoiceResolve.Voice.PACK,
                VoiceResolve.resolve(rus, rusIn, sysRu).voice);
        checkEq("resolve: pack code carried", "rus",
                VoiceResolve.resolve(rus, rusIn, sysRu).packCode);
        checkEq("resolve: system voice second", VoiceResolve.Voice.SYSTEM,
                VoiceResolve.resolve(rus, none, sysRu).voice);
        checkEq("resolve: neither means NONE, said plainly",
                VoiceResolve.Voice.NONE,
                VoiceResolve.resolve(rus, none, none).voice);
        java.util.Set<String> korIn = new java.util.HashSet<>(
                java.util.Collections.singletonList("kor"));
        checkEq("resolve: uroman never resolves to a pack",
                VoiceResolve.Voice.NONE,
                VoiceResolve.resolve(kor, korIn, none).voice);
        java.util.Set<String> sysKo = new java.util.HashSet<>(
                java.util.Collections.singletonList("ko"));
        checkEq("resolve: uroman falls to the system voice",
                VoiceResolve.Voice.SYSTEM,
                VoiceResolve.resolve(kor, korIn, sysKo).voice);
        java.util.Set<String> sysJa = new java.util.HashSet<>(
                java.util.Collections.singletonList("ja"));
        checkEq("resolve: no-model language rides the system voice",
                VoiceResolve.Voice.SYSTEM,
                VoiceResolve.resolve(jpn, none, sysJa).voice);
        checkEq("resolve: unknown language is NONE",
                VoiceResolve.Voice.NONE,
                VoiceResolve.resolve(null, rusIn, sysRu).voice);
    }

    /** Every shipped voice pack validates under the pack law,
     *  names the language its file name claims, and carries its
     *  license; a tampered pack and a wrong-kind pack refuse. */
    static void voicePackSuite(String yourFiles) throws Exception {
        File dir = new File(yourFiles);
        File[] packs = dir.listFiles((d, n) -> n.startsWith("sandy-voice-")
                && n.endsWith(".pack"));
        if (CI && (packs == null || packs.length == 0)) {
            if (!CI_SKIPS.contains("voicepack")) CI_SKIPS.add("voicepack");
            System.out.println("SKIP voicepack: no sandy-voice-*.pack on the"
                    + " shelf at " + dir.getPath()
                    + " (binary packs do not travel to CI)");
            return;
        }
        check("voicepack: packs are on the shelf", packs != null);
        if (packs == null) return;
        checkEq("voicepack: one pack per native language", 52, packs.length);
        java.util.Arrays.sort(packs);
        for (File p : packs) {
            String code = p.getName()
                    .substring("sandy-voice-".length(), p.getName().length() - 5);
            VoicePack.Result r;
            try (InputStream in = new FileInputStream(p)) {
                r = VoicePack.validateAndExtract(in, null);
            }
            check("voicepack validates: " + code, r.ok);
            if (!r.ok) {
                System.out.println("  detail " + code + ": " + r.errors);
                continue;
            }
            checkEq("voicepack lang matches its file: " + code, code, r.lang);
            checkEq("voicepack engine: " + code, "mms-tts", r.engine);
            checkEq("voicepack license rides: " + code, "CC-BY-NC-4.0", r.license);
            checkEq("voicepack sample rate: " + code, 16000, r.sampleRate);
        }
        // tamper: one flipped byte inside the model must refuse
        File eng = new File(dir, "sandy-voice-eng.pack");
        if (eng.isFile()) {
            byte[] bytes = Files.readAllBytes(eng.toPath());
            bytes[bytes.length / 2] ^= 0x5A;
            File tmp = File.createTempFile("tampered-voice", ".pack");
            Files.write(tmp.toPath(), bytes);
            VoicePack.Result bad;
            try (InputStream in = new FileInputStream(tmp)) {
                bad = VoicePack.validateAndExtract(in, null);
            }
            check("voicepack: a tampered pack is refused", !bad.ok);
            tmp.delete();
        }
        // the diarization pack is not a voice pack
        File diar = new File(dir, "sandy-diarize.pack");
        if (diar.isFile()) {
            VoicePack.Result wrong;
            try (InputStream in = new FileInputStream(diar)) {
                wrong = VoicePack.validateAndExtract(in, null);
            }
            check("voicepack: the diar pack is refused at this door", !wrong.ok);
        }
    }

    /** The character front end, pinned against the reference
     *  tokenizations computed from the shipped token tables by
     *  the desktop bench (blank-interspersed ids, unknown
     *  characters skipped, sentences split at . ! ? :). */
    static void voiceFrontendSuite() throws Exception {
        String fix = System.getProperty("user.home")
                + "/workspace/tala-tv-2026-10-16/work/v130/fixtures";
        if (ciSkipIfMissing("voiceFrontend",
                new File(fix, "tokens-eng.txt"),
                new File(fix, "tokens-rus.txt"))) return;
        java.util.Map<String, Integer> eng =
                OnnxVoiceSynth.loadTokens(new File(fix, "tokens-eng.txt"));
        List<long[]> hello = OnnxVoiceSynth.tokenize(eng, "Hello world.");
        checkEq("frontend: one sentence", 1, hello.size());
        check("frontend: eng Hello world ids",
                java.util.Arrays.equals(hello.get(0), new long[]{
                        0, 6, 0, 7, 0, 21, 0, 21, 0, 22, 0, 19, 0, 9, 0, 22, 0,
                        25, 0, 21, 0, 5, 0}));
        List<long[]> sandy = OnnxVoiceSynth.tokenize(eng, "Sandy speaks.");
        check("frontend: eng Sandy speaks ids",
                java.util.Arrays.equals(sandy.get(0), new long[]{
                        0, 8, 0, 26, 0, 29, 0, 5, 0, 3, 0, 19, 0, 8, 0, 13, 0,
                        7, 0, 26, 0, 0, 0, 8, 0}));
        List<long[]> skip = OnnxVoiceSynth.tokenize(eng, "a€b");
        check("frontend: unknown characters are skipped",
                skip.size() == 1 && java.util.Arrays.equals(skip.get(0),
                        new long[]{0, 26, 0, 24, 0}));
        checkEq("frontend: two sentences split", 2,
                OnnxVoiceSynth.tokenize(eng, "Hello world. Sandy speaks.").size());
        java.util.Map<String, Integer> rus =
                OnnxVoiceSynth.loadTokens(new File(fix, "tokens-rus.txt"));
        List<long[]> privet = OnnxVoiceSynth.tokenize(rus, "Привет, мир.");
        check("frontend: rus ids (comma unknown, skipped)",
                privet.size() == 1 && java.util.Arrays.equals(privet.get(0),
                        new long[]{0, 22, 0, 26, 0, 33, 0, 41, 0, 37, 0, 40, 0,
                                1, 0, 43, 0, 33, 0, 26, 0}));
        List<long[]> raz = OnnxVoiceSynth.tokenize(rus, "Раз два три.");
        check("frontend: rus Raz dva tri ids",
                raz.size() == 1 && java.util.Arrays.equals(raz.get(0),
                        new long[]{0, 26, 0, 8, 0, 19, 0, 1, 0, 2, 0, 41, 0,
                                8, 0, 1, 0, 40, 0, 26, 0, 33, 0}));
        // pitch + trim, the cast mechanics
        float[] tone = new float[1600];
        java.util.Arrays.fill(tone, 0.5f);
        checkEq("frontend: pitch 2x halves the buffer", 800,
                OnnxVoiceSynth.pitchShift(tone, 2.0).length);
        checkEq("frontend: pitch 1x keeps the buffer", 1600,
                OnnxVoiceSynth.pitchShift(tone, 1.0).length);
        checkEq("frontend: all-silence trims to nothing", 0,
                OnnxVoiceSynth.trimSilence(new float[1000]).length);
        checkEq("frontend: full tone survives the trim", 1600,
                OnnxVoiceSynth.trimSilence(tone).length);
    }

    private static String classBytes(String classesDir, String cls)
            throws Exception {
        return new String(Files.readAllBytes(new File(classesDir,
                "com/talacore/talatv/" + cls + ".class").toPath()), "ISO-8859-1");
    }

    /** The voiceover doors stand on their surfaces, in the class
     *  bytes themselves (the standing surface-pin pattern). */
    static void voiceoverSurfacesSuite() throws Exception {
        String classesDir = System.getProperty("sandy.classes");
        String files = classBytes(classesDir, "FilesActivity");
        check("surface: My Videos carries the voiceover door",
                files.contains("dub_make_voiceover") && files.contains("pickUrl"));
        String recs = classBytes(classesDir, "RecordingsActivity");
        check("surface: Recordings carries the voiceover door",
                recs.contains("dub_make_voiceover") && recs.contains("DubActivity"));
        String show = classBytes(classesDir, "ShowActivity");
        check("surface: the episode page offers the voiceover mode",
                show.contains("episodeDub"));
        String sources = classBytes(classesDir, "SourcesActivity");
        check("surface: source picking can hold for a voiceover",
                sources.contains("episodeDub") && sources.contains("holdForDub"));
        String dub = classBytes(classesDir, "DubActivity");
        check("surface: the kitchen takes a preselected pick",
                dub.contains("pickUrl") && dub.contains("pickRecording"));
        check("surface: the kitchen discloses a still-saving copy",
                dub.contains("dub_still_saving"));
        check("surface: the kitchen discloses an unvoiced language",
                dub.contains("dub_voice_none"));
        check("surface: the kitchen renders with the pack voice",
                dub.contains("renderPack"));
        String renderer = classBytes(classesDir, "TtsDubRenderer");
        check("surface: the renderer has the pack leg",
                renderer.contains("renderPack") && renderer.contains("OnnxVoiceSynth"));
        String settings = classBytes(classesDir, "SettingsActivity");
        check("surface: Settings doors to coverage + credit stand",
                settings.contains("VoiceCoverageActivity")
                        && settings.contains("CreditActivity")
                        && settings.contains("VoicePackStore"));
        String reader = classBytes(classesDir, "ReaderService");
        check("surface: read-aloud can ride a voice pack",
                reader.contains("OnnxVoiceSynth")
                        && reader.contains("reader_voice_pack_fail"));
        // the new strings ride all 13 tables
        for (String k : new String[]{"settings_voice_packs", "voice_packs_none",
                "voice_packs_some", "settings_voice_import", "voice_coverage_title",
                "voice_state_pack", "voice_state_system", "voice_state_none",
                "dub_voice_pack", "dub_voice_system", "dub_voice_none",
                "dub_make_voiceover", "dub_voiceover_pick_source", "dub_still_saving",
                "reader_voice_pack_fail", "sources_title", "sources_intro",
                "sources_brain", "sources_archive", "sources_iptvorg",
                "sources_tvmaze", "sources_mjh", "sources_iptvx",
                "sources_responsibility"}) {
            check("uistrings key present: " + k, UiStrings.keyIndex(k) >= 0);
        }
        checkEq("uistrings dub_make_voiceover en", "Make a voiceover…",
                UiStrings.lookup("en", "dub_make_voiceover"));
        check("uistrings responsibility line is not an English twin in fa",
                !UiStrings.lookup("fa", "sources_responsibility")
                        .equals(UiStrings.lookup("en", "sources_responsibility")));
    }

    /** The credit page's doors, verbatim — verified live the day
     *  they were built; a dead credit link is worse than none,
     *  so the shipped list is pinned exactly. */
    static void creditSuite() {
        checkEq("credit: nine doors", 9, CreditActivity.LINKS.length);
        checkEq("credit: the Brain's public edition",
                "https://drive.google.com/drive/folders/1xocvSY7tb8tky1D6oogkApVkh9d-ULmZ",
                CreditActivity.LINKS[0]);
        checkEq("credit: the Internet Archive", "https://archive.org",
                CreditActivity.LINKS[1]);
        checkEq("credit: iptv-org", "https://iptv-org.github.io/",
                CreditActivity.LINKS[2]);
        checkEq("credit: TVmaze", "https://www.tvmaze.com",
                CreditActivity.LINKS[3]);
        checkEq("credit: the guide files (mjh)", "https://i.mjh.nz",
                CreditActivity.LINKS[4]);
        checkEq("credit: more guide files (iptvx)", "https://epg.iptvx.one",
                CreditActivity.LINKS[5]);
        for (String k : CreditActivity.NAMES) {
            check("credit: name key present: " + k, UiStrings.keyIndex(k) >= 0);
        }
    }

    // ------------------------------------------------- v0.14.0 suites

    /** The story room's casting: the book's cast is stable —
     *  the witch sounds like the witch in every chapter — and
     *  the telling's speakers come from the text's own tags. */
    static void storyCastSuite() throws Exception {
        StoryCast.Book b = StoryCast.newBook("test-book", "T", "en");
        StoryCast.Entry n = b.narrator();
        check("cast: a fresh book has its narrator", n != null);
        check("cast: narrator rate is the calm 0.92",
                n != null && Math.abs(n.rate - 0.92) < 0.001);
        StoryCast.Entry fox = StoryCast.enroll(b, "Fox");
        StoryCast.Entry crow = StoryCast.enroll(b, "Crow");
        check("cast: two characters get different voices",
                fox.pitch != crow.pitch || fox.rate != crow.rate);
        StoryCast.Book b2 = StoryCast.newBook("test-book", "T", "en");
        StoryCast.Entry fox2 = StoryCast.enroll(b2, "Fox");
        StoryCast.Entry crow2 = StoryCast.enroll(b2, "Crow");
        check("cast: same order, same voices (deterministic)",
                fox.pitch == fox2.pitch && fox.rate == fox2.rate
                        && crow.pitch == crow2.pitch && crow.rate == crow2.rate);
        check("cast: re-enroll returns the same entry",
                StoryCast.enroll(b, "Fox") == fox);
        check("cast: lookup is by name, any case",
                b.byName("fox") == fox);

        // family enrollment casts the person as themselves
        StoryCast.Entry g = StoryCast.enrollFamily(b, "Grandma");
        check("cast: family entry is flagged family", g.family);
        g.recordings.put(3, "c3.m4a");
        String json = StoryCast.toJson(b);
        StoryCast.Book back = StoryCast.fromJson(json);
        check("cast: JSON round-trip keeps the cast",
                back != null && back.cast.size() == b.cast.size());
        check("cast: recordings survive the round-trip",
                back != null && back.byName("Grandma") != null
                        && "c3.m4a".equals(
                                back.byName("Grandma").recordings.get(3)));
        check("cast: pitch survives the round-trip",
                back != null && back.byName("Fox") != null
                        && back.byName("Fox").pitch == fox.pitch);

        // dialogue attribution from the text's own tags
        String text = "\"I will eat you up,\" said the Fox, and he smiled. "
                + "The Crow only laughed. "
                + "\"You don’t fool me,\" cried the Crow. "
                + "\"Not today.\"";
        java.util.List<StoryCast.Segment> segs = StoryCast.attribute(b, text);
        check("cast: segments found", segs.size() >= 3);
        boolean foxSaid = false, crowSaid = false, narratorTold = false;
        for (StoryCast.Segment s : segs) {
            if (s.speaker.equals("Fox") && s.text.contains("eat you")) foxSaid = true;
            if (s.speaker.equals("Crow") && s.text.contains("fool me")) crowSaid = true;
            if (s.speaker.equals(StoryCast.NARRATOR)
                    && s.text.contains("only laughed")) narratorTold = true;
        }
        check("cast: 'said the Fox' attributes the line to the Fox", foxSaid);
        check("cast: 'cried the Crow' attributes the line to the Crow", crowSaid);
        check("cast: plain telling stays with the narrator", narratorTold);
        // whole-book collection enrolls speakers in reading order
        StoryCast.Book b3 = StoryCast.newBook("t3", "T", "en");
        StoryCast.collectCast(b3, java.util.Collections.singletonList(text));
        check("cast: collectCast finds Fox and Crow",
                b3.byName("Fox") != null && b3.byName("Crow") != null);
    }

    /** The storyboard panels: a fixed drawn set, scenes matched
     *  from the passage's own words, the same character drawn the
     *  same way every time. */
    static void storyPanelsSuite() throws Exception {
        checkEq("panels: night scene", "night",
                StoryPanels.sceneFor("Night fell and the moon came up over the fields."));
        checkEq("panels: forest scene", "forest",
                StoryPanels.sceneFor("They walked deep into the forest."));
        checkEq("panels: sea scene", "sea",
                StoryPanels.sceneFor("The little boat crossed the sea."));
        checkEq("panels: no scene matched", "",
                StoryPanels.sceneFor("Then something happened."));
        checkEq("panels: a fox is drawn as the fox card", "fox",
                StoryPanels.cardFor("book1", "The Fox"));
        checkEq("panels: a rabbit is the rabbit card", "rabbit",
                StoryPanels.cardFor("book1", "Peter Rabbit"));
        String c1 = StoryPanels.cardFor("book1", "Maren");
        String c2 = StoryPanels.cardFor("book1", "Maren");
        checkEq("panels: same name, same card (consistency)", c1, c2);
        StoryPanels.Choice ch = StoryPanels.choose(
                "", "At night the Fox crept home.",
                java.util.Collections.singletonList("fox"));
        checkEq("panels: choice takes the passage's scene", "night", ch.sceneId);
        check("panels: choice carries the speaker's card",
                ch.charIds.contains("fox"));

        // every panel id has its drawn file in the app tree
        String appRes = System.getProperty("user.home")
                + "/workspace/tala-tv-2026-10-16/app/res/drawable/";
        for (StoryPanels.Panel p : StoryPanels.all()) {
            check("panels: drawable exists for " + p.id,
                    new java.io.File(appRes, "story_" + p.id + ".xml").isFile());
        }
        checkEq("panels: the set is 14 scenes + 19 characters", 33,
                StoryPanels.all().size());
    }

    /** The curated shelf, as SHIPPED: the file itself is read
     *  and pinned — curated going in, and the pairs the
     *  bilingual hearing stands on must be there. */
    static void storyDataSuite() throws Exception {
        String tsv = new String(java.nio.file.Files.readAllBytes(
                new java.io.File(System.getProperty("user.home")
                        + "/workspace/tala-tv-2026-10-16/app/assets/story_curation.tsv")
                        .toPath()), "UTF-8");
        java.util.List<StoryData.Row> rows = StoryData.parseCuration(tsv);
        check("curation: at least a dozen tales on the shelf", rows.size() >= 12);
        boolean allCast = true;
        for (StoryData.Row r : rows) {
            if (r.cast.isEmpty()) allCast = false;
        }
        check("curation: every row names its cast", allCast);
        for (String gname : new String[]{"aesop", "grimm", "andersen"}) {
            java.util.Set<String> langs = new java.util.HashSet<>();
            for (StoryData.Row r : rows) {
                if (r.taleGroup.equals(gname)) langs.add(r.lang);
            }
            check("curation: " + gname + " stands in more than one tongue",
                    langs.size() >= 2);
        }
        StoryData.Row en = StoryData.rowById(rows, "aesop-en");
        check("curation: aesop-en is on the shelf", en != null);
        StoryData.Row pair = StoryData.bilingualPair(rows, en, "es");
        check("curation: aesop-en pairs with the Spanish telling",
                pair != null && "es".equals(pair.lang));
        java.util.List<StoryData.Row> opts = StoryData.pairOptions(rows, en);
        check("curation: pair options exist for aesop-en", opts.size() >= 2);

        // cartridge + journal + voice-index round-trips
        StoryData.Cartridge c = new StoryData.Cartridge();
        c.id = "story_1";
        c.title = "My Ending";
        c.createdMs = 123456789L;
        StoryData.CartridgePanel p1 = new StoryData.CartridgePanel();
        p1.panel = "fox";
        p1.caption = "The fox ran home.";
        p1.audio = "p0.m4a";
        c.panels.add(p1);
        StoryData.Cartridge cBack = StoryData.cartridgeFromJson(
                StoryData.cartridgeToJson(c));
        check("cartridge: round-trip keeps the panels",
                cBack != null && cBack.panels.size() == 1
                        && "fox".equals(cBack.panels.get(0).panel)
                        && "The fox ran home.".equals(cBack.panels.get(0).caption)
                        && "p0.m4a".equals(cBack.panels.get(0).audio)
                        && "My Ending".equals(cBack.title));

        java.util.List<StoryData.Journal> js = new java.util.ArrayList<>();
        StoryData.Journal j = new StoryData.Journal();
        j.id = "j1";
        j.name = "Grandma";
        j.dateMs = 42L;
        j.file = "j1.m4a";
        js.add(j);
        java.util.List<StoryData.Journal> jBack =
                StoryData.parseJournals(StoryData.journalsToTsv(js));
        check("journals: round-trip keeps the letter",
                jBack.size() == 1 && "Grandma".equals(jBack.get(0).name)
                        && "j1.m4a".equals(jBack.get(0).file));

        java.util.Map<Integer, String[]> vi = new java.util.TreeMap<>();
        vi.put(2, new String[]{"Dad", "c2.m4a"});
        java.util.Map<Integer, String[]> viBack =
                StoryData.parseVoiceIndex(StoryData.voiceIndexToTsv(vi));
        check("voice index: round-trip keeps the reading",
                viBack.containsKey(2) && "Dad".equals(viBack.get(2)[0])
                        && "c2.m4a".equals(viBack.get(2)[1]));

        checkEq("safeId makes a file-safe name", "my-story-2",
                StoryData.safeId("My Story!", 2));
    }

    /** The grown-up gate's questions: always answerable, always
     *  with exactly one right answer — a plain check, not a
     *  riddle a tired parent can fail. */
    static void gateQuestionsSuite() {
        for (int seed = 0; seed < 100; seed++) {
            GateQuestions.Question q = GateQuestions.generate(
                    new java.util.Random(seed * 7919L + 13));
            check("gate: options are distinct (seed " + seed + ")",
                    new java.util.HashSet<>(q.options).size() == q.options.size());
            check("gate: marked answer is the right one (seed " + seed + ")",
                    GateQuestions.isCorrect(q, q.correctIndex));
            if (q.kind == GateQuestions.KIND_BIGGEST) {
                int max = Integer.MIN_VALUE;
                for (int v : q.options) max = Math.max(max, v);
                check("gate: biggest is right (seed " + seed + ")",
                        q.options.get(q.correctIndex) == max);
            } else {
                check("gate: sum is right (seed " + seed + ")",
                        q.options.get(q.correctIndex) == q.a + q.b);
            }
        }
    }

    private static TvMaze.Episode ep(String name, int season, int number) {
        TvMaze.Episode e = new TvMaze.Episode();
        e.name = name;
        e.season = season;
        e.number = number;
        return e;
    }

    /** One tap to the next episode: the air-order rule — specials
     *  on their own shelf, season rollover, and the honest end. */
    static void nextEpisodeSuite() {
        java.util.List<TvMaze.Episode> eps = new java.util.ArrayList<>();
        eps.add(ep("One", 1, 1));
        eps.add(ep("Two", 1, 2));
        eps.add(ep("Special A", 0, 1));
        eps.add(ep("Special B", 0, 2));
        eps.add(ep("Three", 2, 1));
        TvMaze.Episode n1 = NextEpisode.next(eps, 1, 1);
        check("next: S1E1 → S1E2", n1 != null && n1.season == 1 && n1.number == 2);
        TvMaze.Episode n2 = NextEpisode.next(eps, 1, 2);
        check("next: S1E2 rolls to S2E1 (specials skipped)",
                n2 != null && n2.season == 2 && n2.number == 1);
        check("next: the last episode has no next",
                NextEpisode.next(eps, 2, 1) == null);
        TvMaze.Episode n4 = NextEpisode.next(eps, 0, 1);
        check("next: a special's next is the next special",
                n4 != null && n4.season == 0 && n4.number == 2);
        TvMaze.Episode n5 = NextEpisode.next(eps, 1, 9);
        check("next: unknown current → first after it in air order",
                n5 != null && n5.season == 2 && n5.number == 1);
        checkEq("next: label shape", "S02E03 · Name",
                NextEpisode.label(ep("Name", 2, 3)));
    }

    /** The story room is WIRED, not drawn: the door stands on the
     *  home surface, the episode context rides into the player,
     *  the auto-advance choice lives in Settings, and the
     *  telling refuses to read voiceless. Bytecode pins in the
     *  house style. */
    static void storyWiringSuite() throws Exception {
        String classesDir = System.getProperty("sandy.classes");
        String home = classBytes(classesDir, "HomeActivity");
        check("wiring: the Story door stands on Home",
                home != null && home.contains("StoryActivity"));
        String sources = classBytes(classesDir, "SourcesActivity");
        check("wiring: episode launches carry epShowId",
                sources != null && sources.contains("epShowId"));
        String player = classBytes(classesDir, "PlayerActivity");
        check("wiring: the player resolves the next episode",
                player != null && player.contains("epShowId")
                        && player.contains("NextEpisodeResolver"));
        // the end-of-playback branch lives in the player's
        // listener (an inner class) — read the whole family
        StringBuilder family = new StringBuilder(player == null ? "" : player);
        java.io.File dir = new java.io.File(classesDir, "com/talacore/talatv");
        java.io.File[] kin = dir.listFiles((d, nm) ->
                nm.startsWith("PlayerActivity$") && nm.endsWith(".class"));
        if (kin != null) {
            for (java.io.File f : kin) {
                family.append(new String(java.nio.file.Files.readAllBytes(
                        f.toPath()), "ISO-8859-1"));
            }
        }
        check("wiring: the player honors the auto-advance choice",
                family.toString().contains("getAutoAdvanceNext"));
        String settings = classBytes(classesDir, "SettingsActivity");
        check("wiring: Settings holds the auto-advance toggle",
                settings != null && settings.contains("settings_auto_advance"));
        String tell = classBytes(classesDir, "StoryTellActivity");
        check("wiring: the telling names a voiceless book plainly",
                tell != null && tell.contains("story_no_voice"));
        String story = classBytes(classesDir, "StoryActivity");
        check("wiring: the room's exit passes the gate",
                story != null && story.contains("GrownUpGateActivity"));
    }

    // --------------------------------- the riders (2026-10-19)

    /** HYDRA, the Connection Law: the ladder's order, the
     *  alternate manners, the learned door (persisted + re-walk),
     *  the shared budget, and the exhaustion report. */
    static void hydraSuite() {
        // ladder shape per address class
        java.util.List<Hydra.Head> plain = Hydra.heads("https://example.com/a.m3u8");
        checkEq("hydra: a plain https address has direct+scheme heads", 2, plain.size());
        checkEq("hydra: direct is the first head", Hydra.M_DIRECT, plain.get(0).manner);
        checkEq("hydra: scheme head swaps to http", "http://example.com/a.m3u8",
                plain.get(1).url);
        java.util.List<Hydra.Head> ia = Hydra.heads(
                "https://ia801409.us.archive.org/download/item42/movie.mp4");
        checkEq("hydra: an Archive file has three heads", 3, ia.size());
        checkEq("hydra: the canonical door is the Archive's own",
                "https://archive.org/download/item42/movie.mp4", ia.get(1).url);
        checkEq("hydra: canonical head manner", Hydra.M_IA_CANONICAL, ia.get(1).manner);
        check("hydra: canonical of the canonical door is itself (no head)",
                Hydra.iaCanonical("https://archive.org/download/item42/movie.mp4") == null);
        check("hydra: a non-Archive address has no canonical head",
                Hydra.iaCanonical("https://example.com/download/x.mp4") == null);
        check("hydra: a local file has no ladder",
                Hydra.heads("/storage/movie.mp4").size() <= 1);

        // learned doors: the file round-trips, and the learned
        // door leads the next ladder for that host
        java.util.Map<String, Integer> doors = new java.util.LinkedHashMap<>();
        doors.put("ia801409.us.archive.org", Hydra.M_IA_CANONICAL);
        doors.put("example.com", Hydra.M_SCHEME);
        java.util.Map<String, Integer> back = Hydra.parseDoors(Hydra.doorsToTsv(doors));
        checkEq("hydra: learned doors round-trip", doors, back);
        java.util.List<Hydra.Head> ordered = Hydra.orderFor(
                "https://ia801409.us.archive.org/download/item42/movie.mp4", back);
        checkEq("hydra: the learned door leads its ladder",
                Hydra.M_IA_CANONICAL, ordered.get(0).manner);
        checkEq("hydra: the rest of the ladder follows in law order",
                Hydra.M_DIRECT, ordered.get(1).manner);
        checkEq("hydra: an unlearned host keeps law order",
                Hydra.M_DIRECT, Hydra.orderFor("https://other.org/x", back).get(0).manner);

        // the budget is shared and hard-capped
        check("hydra: budget holds early", Hydra.mayAdvance(0, 5_000, 4_000));
        check("hydra: budget refuses past the cap", !Hydra.mayAdvance(0, 27_000, 4_000));
        checkEq("hydra: the cap is the house cap", LinkHealth.TOTAL_CAP_MS,
                Hydra.TOTAL_BUDGET_MS);

        // the exhaustion report: a terminal word outranks silence
        java.util.List<Hydra.Tried> tried = new java.util.ArrayList<>();
        tried.add(new Hydra.Tried(Hydra.M_DIRECT, 0, FailureWords.TIMEOUT));
        tried.add(new Hydra.Tried(Hydra.M_IA_CANONICAL, 404, FailureWords.GONE));
        checkEq("hydra: the terminal answer is the reported one",
                404, Hydra.mostInformative(tried).httpCode);
        java.util.List<Hydra.Tried> silent = new java.util.ArrayList<>();
        silent.add(new Hydra.Tried(Hydra.M_DIRECT, 0, FailureWords.TIMEOUT));
        checkEq("hydra: pure silence reports the last silence",
                FailureWords.TIMEOUT, Hydra.mostInformative(silent).kind);
    }

    /** Failure words: a bare "HTTP error" is banned — every
     *  outcome names itself in plain words. */
    static void failureWordsSuite() {
        checkEq("words: 404 is gone", FailureWords.GONE, FailureWords.kindForHttp(404));
        checkEq("words: 410 is gone", FailureWords.GONE, FailureWords.kindForHttp(410));
        checkEq("words: 403 is refused", FailureWords.REFUSED, FailureWords.kindForHttp(403));
        checkEq("words: 503 is the server's trouble", FailureWords.SERVER,
                FailureWords.kindForHttp(503));
        checkEq("words: 408 is a timeout", FailureWords.TIMEOUT, FailureWords.kindForHttp(408));
        checkEq("words: silence is no-answer", FailureWords.NO_ANSWER,
                FailureWords.kindForHttp(0));
        checkEq("words: cleartext names itself", FailureWords.CLEARTEXT,
                FailureWords.kindForDetail("Cleartext HTTP traffic not permitted"));
        checkEq("words: a timeout detail names itself", FailureWords.TIMEOUT,
                FailureWords.kindForDetail("SocketTimeoutException: timeout"));
        for (String kind : new String[]{FailureWords.REFUSED, FailureWords.GONE,
                FailureWords.SERVER, FailureWords.TIMEOUT, FailureWords.ADDRESS,
                FailureWords.CLEARTEXT, FailureWords.NO_ANSWER}) {
            check("words: key exists in the tables: " + kind,
                    UiStrings.keyIndex(FailureWords.keyFor(kind)) >= 0);
            check("words: the English sentence is plain: " + kind,
                    !FailureWords.englishFor(kind, 404).contains("HTTP error"));
        }
        check("words: the sentence carries the status",
                FailureWords.englishFor(FailureWords.GONE, 404).contains("404"));
    }

    /** The Display Honesty Law at the card: state derives from
     *  shelf truth, never from catalogue presence alone. */
    static void cardStateSuite() {
        checkEq("card: on the shelves = playable",
                CardState.PLAYABLE, CardState.forShow(true, false));
        checkEq("card: catalogue only, settled = catalog-only",
                CardState.CATALOG_ONLY, CardState.forShow(false, false));
        checkEq("card: catalogue only, still resolving = checking",
                CardState.CHECKING, CardState.forShow(false, true));
        check("card: only playable may promise play",
                CardState.playPromise(CardState.PLAYABLE)
                        && !CardState.playPromise(CardState.CATALOG_ONLY)
                        && !CardState.playPromise(CardState.CHECKING));
        check("card: a non-playable card is dimmed",
                CardState.alphaFor(CardState.CATALOG_ONLY) < 1.0f
                        && CardState.alphaFor(CardState.PLAYABLE) == 1.0f);
        checkEq("card: catalog-only label key", "wall_catalog_only",
                CardState.labelKey(CardState.CATALOG_ONLY));
        checkEq("card: checking label key", "card_checking",
                CardState.labelKey(CardState.CHECKING));
    }

    /** The QC clause: a printed count IS the verified set —
     *  displayed count == verified-live links, by construction. */
    static void displayCountsSuite() {
        java.util.List<String> urls = java.util.Arrays.asList(
                "http://a/1", "http://a/2", "http://a/3", "http://a/4");
        java.util.Map<String, Integer> tiers = new java.util.HashMap<>();
        tiers.put("http://a/1", LinkHealth.TIER_PLAYS);
        tiers.put("http://a/2", LinkHealth.TIER_CONNECTS);
        tiers.put("http://a/3", LinkHealth.TIER_DEAD);
        // a/4 has no verdict at all: unproven, uncounted
        checkEq("counts: only verified-live links are counted", 2,
                DisplayCounts.verifiedCount(urls, tiers));
        checkEq("counts: a dead link is not verified", false,
                DisplayCounts.isVerifiedLive(LinkHealth.TIER_DEAD));
        checkEq("counts: an unchecked link is not verified", false,
                DisplayCounts.isVerifiedLive(null));
        checkEq("counts: zero verified prints no number", "",
                DisplayCounts.countText(0));
        checkEq("counts: a verified count prints itself", "2",
                DisplayCounts.countText(2));
    }

    /** The riders are WIRED, in the house's bytecode-pin style:
     *  Net walks Hydra's ladder, the prove paths route through
     *  HydraResolver, the cards consult CardState, the tiles
     *  count through DisplayCounts, the player's next tile is
     *  gated on the resolved source. */
    static void honestyWiringSuite() throws Exception {
        String classesDir = System.getProperty("sandy.classes");
        String net = classBytes(classesDir, "Net");
        check("wiring: Net walks the Hydra ladder",
                net != null && net.contains("Hydra") && net.contains("orderFor"));
        check("wiring: Net's exhaustion carries failure words",
                net != null && net.contains("FailureWords"));
        String resolver = classBytes(classesDir, "HydraResolver");
        check("wiring: HydraResolver exists and probes",
                resolver != null && resolver.contains("LinkHealth"));
        String sources = classBytes(classesDir, "SourcesActivity");
        check("wiring: the source door proves through Hydra",
                sources != null && sources.contains("HydraResolver"));
        String next = classBytes(classesDir, "NextEpisodeResolver");
        check("wiring: the next-episode resolver proves through Hydra",
                next != null && next.contains("HydraResolver"));
        String player = classBytes(classesDir, "PlayerActivity");
        check("wiring: the player walks Hydra on failure",
                player != null && player.contains("HydraResolver"));
        check("wiring: the player speaks failure words",
                player != null && player.contains("FailureWords"));
        String home = classBytes(classesDir, "HomeActivity");
        check("wiring: home cards consult CardState",
                home != null && home.contains("CardState"));
        // the wall's rows live in its adapter (an inner class):
        // read the whole family, as with the player's listener
        StringBuilder wallFam = new StringBuilder();
        java.io.File wdir = new java.io.File(classesDir, "com/talacore/talatv");
        java.io.File[] wkin = wdir.listFiles((d, nm) ->
                nm.startsWith("WallListActivity") && nm.endsWith(".class"));
        if (wkin != null) {
            for (java.io.File f : wkin) {
                wallFam.append(new String(java.nio.file.Files.readAllBytes(
                        f.toPath()), "ISO-8859-1"));
            }
        }
        check("wiring: wall rows consult CardState",
                wallFam.toString().contains("CardState"));
        String main = classBytes(classesDir, "MainActivity");
        check("wiring: live tiles count through DisplayCounts",
                main != null && main.contains("DisplayCounts"));
        String shelf = classBytes(classesDir, "StoryShelfActivity");
        check("wiring: the story shelf verifies before it tiles",
                shelf != null && shelf.contains("StoryFetch")
                        && shelf.contains("bookHeld"));
        String splash = classBytes(classesDir, "SplashActivity");
        check("wiring: boot primes Hydra's learned doors",
                splash != null && splash.contains("HydraStore"));
    }

    // ------------------------------------------------- v0.14.1 suites

    /**
     * THE FIFTEEN-SECOND WINDOW (v0.14.1): the continuity law,
     * the segment-key law, the pinger's pure judgment, the
     * runway numbers, the record's shape, and the wiring — the
     * rule pinned the way James's addenda state it: a playing
     * channel reconnects before it can ever advance; advance
     * requires ladder-exhausted AND runway-spent; a stuck window
     * is words, never a verdict; a loop of her own cached bytes
     * can never be served as live again.
     */
    static void continuity141Suite() throws Exception {
        // ---- the law: reconnect before any advance
        checkEq("continuity: drop during play, ladder walking → RECONNECT",
                PlaybackContinuity.RECONNECT,
                PlaybackContinuity.decide(
                        PlaybackContinuity.SEGMENT_REFUSED, false, 0));
        checkEq("continuity: ladder spent but runway not → still RECONNECT",
                PlaybackContinuity.RECONNECT,
                PlaybackContinuity.decide(
                        PlaybackContinuity.SEGMENT_REFUSED, true,
                        PlaybackContinuity.RUNWAY_MS - 1));
        checkEq("continuity: segments refused + ladder + runway → ADVANCE",
                PlaybackContinuity.ADVANCE,
                PlaybackContinuity.decide(
                        PlaybackContinuity.SEGMENT_REFUSED, true,
                        PlaybackContinuity.RUNWAY_MS));
        checkEq("continuity: source ended + ladder + runway → ADVANCE",
                PlaybackContinuity.ADVANCE,
                PlaybackContinuity.decide(
                        PlaybackContinuity.PLAYLIST_GONE, true,
                        PlaybackContinuity.RUNWAY_MS + 5000));
        checkEq("continuity: silence + ladder + runway → ADVANCE",
                PlaybackContinuity.ADVANCE,
                PlaybackContinuity.decide(
                        PlaybackContinuity.NO_ANSWER, true,
                        PlaybackContinuity.RUNWAY_MS));
        checkEq("continuity: stuck window NEVER advances while it answers",
                PlaybackContinuity.HOLD_STALL,
                PlaybackContinuity.decide(
                        PlaybackContinuity.PLAYLIST_STUCK, true,
                        PlaybackContinuity.RUNWAY_MS * 10));
        checkEq("continuity: stuck window holds before the ladder too",
                PlaybackContinuity.HOLD_STALL,
                PlaybackContinuity.decide(
                        PlaybackContinuity.PLAYLIST_STUCK, false, 0));
        boolean noStrikes = true;
        for (int d : new int[]{PlaybackContinuity.RECONNECT,
                PlaybackContinuity.HOLD_STALL, PlaybackContinuity.ADVANCE}) {
            if (PlaybackContinuity.addsStrike(d)) noStrikes = false;
        }
        check("continuity: maintenance and verdicts carry NO strike", noStrikes);
        // no attempt cap while trying: cycle after cycle inside
        // the runway is still RECONNECT — no counter anywhere
        boolean uncapped = true;
        for (long t = 0; t < PlaybackContinuity.RUNWAY_MS; t += 3000) {
            if (PlaybackContinuity.decide(
                    PlaybackContinuity.NO_ANSWER, false, t)
                    != PlaybackContinuity.RECONNECT) uncapped = false;
        }
        check("continuity: reconnect attempts uncapped inside the runway",
                uncapped);
        checkEq("continuity: the runway IS one Hydra budget",
                Hydra.TOTAL_BUDGET_MS, PlaybackContinuity.RUNWAY_MS);
        checkEq("continuity: stall trigger is 20 s",
                20_000L, PlaybackContinuity.STALL_TRIGGER_MS);

        // ---- classification from walk evidence
        checkEq("continuity: connects tier = segments refused",
                PlaybackContinuity.SEGMENT_REFUSED,
                PlaybackContinuity.classifyWalk(LinkHealth.TIER_CONNECTS, 200));
        checkEq("continuity: playlist 404 = source ended",
                PlaybackContinuity.PLAYLIST_GONE,
                PlaybackContinuity.classifyWalk(LinkHealth.TIER_DEAD, 404));
        checkEq("continuity: playlist 410 = source ended",
                PlaybackContinuity.PLAYLIST_GONE,
                PlaybackContinuity.classifyWalk(LinkHealth.TIER_DEAD, 410));
        checkEq("continuity: playlist 403 = source ended",
                PlaybackContinuity.PLAYLIST_GONE,
                PlaybackContinuity.classifyWalk(LinkHealth.TIER_DEAD, 403));
        checkEq("continuity: silence = no answer",
                PlaybackContinuity.NO_ANSWER,
                PlaybackContinuity.classifyWalk(LinkHealth.TIER_DEAD, 0));
        checkEq("continuity: a 500 is trouble, not an ending",
                PlaybackContinuity.NO_ANSWER,
                PlaybackContinuity.classifyWalk(LinkHealth.TIER_DEAD, 500));
        checkEq("continuity: a stuck exception is its own family at once",
                PlaybackContinuity.PLAYLIST_STUCK,
                PlaybackContinuity.classifyError(true));
        checkEq("continuity: other errors wait for the walk's evidence",
                PlaybackContinuity.NO_ANSWER,
                PlaybackContinuity.classifyError(false));

        // ---- the segment-key law (the loop, closed)
        LiveSegmentKeys keys = new LiveSegmentKeys(4242L);
        String seg = "http://cdn.example/live/seg-991.ts";
        String k0 = keys.keyFor(seg);
        String k1 = keys.keyFor(seg);
        check("segkeys: a segment address re-keyed is a DIFFERENT key",
                !k0.equals(k1));
        checkEq("segkeys: generation counts fetches", 2,
                keys.generationOf(seg));
        checkEq("segkeys: generation key is reconstructible", k0,
                keys.keyForGeneration(seg, 0));
        String other = "http://cdn.example/live/seg-992.ts";
        checkEq("segkeys: an unfetched address has no generations", 0,
                keys.generationOf(other));
        keys.keyFor(other);
        checkEq("segkeys: generations are per-address", 1,
                keys.generationOf(other));
        checkEq("segkeys: the first address keeps its own count", 2,
                keys.generationOf(seg));
        LiveSegmentKeys tune2 = new LiveSegmentKeys(777L);
        check("segkeys: a new tune's salt changes the key",
                !tune2.keyFor(seg).equals(k0));
        String pl = "http://cdn.example/live/index.m3u8";
        check("segkeys: playlists stay one-time keys",
                !keys.keyFor(pl).equals(keys.keyFor(pl)));

        // ---- the pinger's pure judgment
        checkEq("pinger: media sequence parses", 421L,
                ActivePinger.mediaSequenceOf(
                        "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:421\n#EXTINF:5,\na.ts\n"));
        checkEq("pinger: missing media sequence is -1", -1L,
                ActivePinger.mediaSequenceOf("#EXTM3U\n#EXTINF:5,\na.ts\n"));
        checkEq("pinger: target duration parses to ms", 6000L,
                ActivePinger.targetDurationMsOf("#EXT-X-TARGETDURATION:6\n"));
        checkEq("pinger: stuck span floors at 15 s", 15_000L,
                ActivePinger.stuckSpanMs(4000));
        checkEq("pinger: stuck span is 3.5 x target", 21_000L,
                ActivePinger.stuckSpanMs(6000));
        checkEq("pinger: stuck span with no target is the floor", 15_000L,
                ActivePinger.stuckSpanMs(-1));
        ActivePinger.Sample a = new ActivePinger.Sample();
        a.mediaSequence = 10; a.segmentUrl = "http://h/s1.ts"; a.segmentCrc = 99;
        ActivePinger.Sample b = new ActivePinger.Sample();
        b.mediaSequence = 10; b.segmentUrl = "http://h/s1.ts"; b.segmentCrc = 99;
        check("pinger: same sequence + segment + bytes = same window",
                ActivePinger.sameWindow(a, b));
        b.mediaSequence = 11;
        check("pinger: advanced sequence = new window",
                !ActivePinger.sameWindow(a, b));
        b.mediaSequence = 10; b.segmentCrc = 100;
        check("pinger: same address, new bytes = recycled, not the same window",
                !ActivePinger.sameWindow(a, b));
        b.segmentCrc = 99; b.segmentUrl = "http://h/s2.ts";
        check("pinger: new segment address = new window",
                !ActivePinger.sameWindow(a, b));
        check("pinger: cadence is 10 s, knock patience 4 s",
                ActivePinger.PING_INTERVAL_MS == 10_000
                        && ActivePinger.PING_TIMEOUT_MS == 4_000);
        check("pinger: terminal set is 401/403/404/410",
                ActivePinger.isTerminalHttp(404) && ActivePinger.isTerminalHttp(410)
                        && ActivePinger.isTerminalHttp(401)
                        && ActivePinger.isTerminalHttp(403)
                        && !ActivePinger.isTerminalHttp(200)
                        && !ActivePinger.isTerminalHttp(500)
                        && !ActivePinger.isTerminalHttp(0));

        // ---- the runway numbers
        checkEq("runway: min buffer defends one Hydra walk",
                30_000, TimeshiftMath.RUNWAY_MIN_BUFFER_MS);
        checkEq("runway: min buffer equals the law's runway",
                (int) PlaybackContinuity.RUNWAY_MS,
                TimeshiftMath.RUNWAY_MIN_BUFFER_MS);
        checkEq("runway: rebuffer resume is 10 s in hand",
                10_000, TimeshiftMath.REBUFFER_RESUME_MS);
        checkEq("runway: ahead cap unchanged at 10 min",
                600_000, TimeshiftMath.MAX_AHEAD_MS);

        // ---- the record's shape (Addendum 1: separable from the record alone)
        PlaybackRecord.Facts f = new PlaybackRecord.Facts();
        f.channel = "Field News";
        f.playlistUrl = "http://src.example/live/pl.m3u8";
        f.playlistResult = "playlist http=200 seq=88";
        f.segmentHost = "cdn.example";
        f.segmentUrl = "http://cdn.example/live/seg-88.ts";
        f.segmentResult = "HTTP 403";
        f.positionMs = 15_000;
        f.bufferedMs = 2_000;
        f.headsTried = "DIRECT http=403/refused";
        f.family = "segments-refused";
        f.decision = "ADVANCE";
        String line = PlaybackRecord.formatEntry(1760000000000L,
                PlaybackRecord.EV_VERDICT, f);
        check("record: line carries the playlist + its reload result",
                line.contains("playlist=http://src.example/live/pl.m3u8")
                        && line.contains("playlist http=200"));
        check("record: line carries segment host + HTTP result",
                line.contains("segmentHost=cdn.example")
                        && line.contains("segmentResult=HTTP 403"));
        check("record: line carries position + runway at failure",
                line.contains("positionMs=15000")
                        && line.contains("bufferedMs=2000"));
        check("record: line carries heads tried + family + decision",
                line.contains("DIRECT http=403/refused")
                        && line.contains("family=segments-refused")
                        && line.contains("decision=ADVANCE"));
        java.util.List<Hydra.Tried> tried = new java.util.ArrayList<>();
        tried.add(new Hydra.Tried(Hydra.M_DIRECT, 403, "refused"));
        tried.add(new Hydra.Tried(Hydra.M_SCHEME, 0, "noanswer"));
        String ht = PlaybackRecord.headsTriedText(tried);
        check("record: heads text names manner + code + kind",
                ht.contains("DIRECT http=403/refused")
                        && ht.contains("SCHEME http=0/noanswer"));
        checkEq("record: manner names", "IA_CANONICAL",
                PlaybackRecord.mannerName(Hydra.M_IA_CANONICAL));
        File dir = Files.createTempDirectory("playrecord").toFile();
        PlaybackRecord.writeTo(dir, 1760000000000L,
                PlaybackRecord.EV_ERROR, f);
        File[] recs = dir.listFiles((d, n) -> n.startsWith("play-"));
        check("record: a day file is written", recs != null && recs.length == 1);
        if (recs != null && recs.length == 1) {
            String body = new String(Files.readAllBytes(recs[0].toPath()), "UTF-8");
            check("record: file lines carry the version stamp",
                    body.contains(CrashGuard.VERSION_STAMP));
            check("record: file carries the event + facts",
                    body.contains(PlaybackRecord.EV_ERROR)
                            && body.contains("cdn.example"));
        }
        HydraResolver.Outcome oc = new HydraResolver.Outcome();
        check("record: a fresh outcome carries empty evidence, tier DEAD",
                oc.tried != null && oc.tried.isEmpty()
                        && oc.bestTier == LinkHealth.TIER_DEAD);

        // ---- the new words stand in all 13 tables
        for (String key : new String[]{"player_reconnecting",
                "player_stream_stalled", "player_stream_ended",
                "playback_record", "playback_record_none"}) {
            check("strings: " + key + " present in every table",
                    UiStrings.keyIndex(key) >= 0
                            && UiStrings.TABLES.values().stream().allMatch(
                                    t -> t[UiStrings.keyIndex(key)] != null
                                            && !t[UiStrings.keyIndex(key)].isEmpty()));
        }
        checkEq("strings: the ended words, in English",
                "The source ended this stream.",
                UiStrings.lookup("en", "player_stream_ended"));

        // ---- wiring: the law stands in the shipped classes
        String classesDir = System.getProperty("sandy.classes");
        StringBuilder playerFam = new StringBuilder();
        java.io.File pdir = new java.io.File(classesDir, "com/talacore/talatv");
        java.io.File[] pkin = pdir.listFiles((d, nm) ->
                nm.startsWith("PlayerActivity") && nm.endsWith(".class"));
        if (pkin != null) {
            for (java.io.File kf : pkin) {
                playerFam.append(new String(java.nio.file.Files.readAllBytes(
                        kf.toPath()), "ISO-8859-1"));
            }
        }
        String playerAll = playerFam.toString();
        check("wiring: the player keeps an ActivePinger",
                playerAll.contains("ActivePinger"));
        check("wiring: the player answers to PlaybackContinuity",
                playerAll.contains("PlaybackContinuity"));
        check("wiring: the player writes the PlaybackRecord",
                playerAll.contains("PlaybackRecord"));
        check("wiring: maintenance walks exhaustively (segment tier)",
                playerAll.contains("proveExhaustive"));
        check("wiring: the playing channel's words are in the player",
                playerAll.contains("player_reconnecting")
                        && playerAll.contains("player_stream_stalled")
                        && playerAll.contains("player_stream_ended"));
        String cache = classBytes(classesDir, "TimeshiftCache");
        check("wiring: the timeshift cache keys by LiveSegmentKeys",
                cache != null && cache.contains("LiveSegmentKeys"));
        String settings = classBytes(classesDir, "SettingsActivity");
        check("wiring: Settings stands the playback record in the open",
                settings != null && settings.contains("PlaybackRecord")
                        && settings.contains("playback_record"));
    }

    // --------------------------------------- v0.16.1 the installer's face

    static void installerFaceSuite() throws Exception {
        // The patch's whole substance, pinned at the source of truth:
        // installers read the APPLICATION icon before install and
        // many cannot draw an adaptive icon there, so the application
        // wears a plain PNG and the adaptive icon lives on the
        // launcher activity. Diagnosis by Claude, relayed by James.
        String root = System.getProperty("user.home")
                + "/workspace/tala-tv-2026-10-16";
        String manifest = new String(Files.readAllBytes(
                new File(root + "/app/AndroidManifest.xml").toPath()), "UTF-8");
        check("installer: manifest carries 0.18.0/24",
                manifest.contains("android:versionName=\"0.18.0\"")
                        && manifest.contains("android:versionCode=\"24\""));
        int appAt = manifest.indexOf("<application");
        String appTag = manifest.substring(appAt, manifest.indexOf('>', appAt));
        check("installer: the application wears the plain PNG",
                appTag.contains("android:icon=\"@drawable/ic_installer\"")
                        && appTag.contains(
                                "android:roundIcon=\"@drawable/ic_installer\""));
        check("installer: no adaptive mipmap left on the application",
                !appTag.contains("@mipmap/ic_launcher"));
        int splashAt = manifest.indexOf(".SplashActivity");
        String splashTag = manifest.substring(splashAt,
                manifest.indexOf('>', splashAt));
        check("installer: the launcher activity keeps the adaptive icon",
                splashTag.contains("android:icon=\"@mipmap/ic_launcher\"")
                        && splashTag.contains(
                                "android:roundIcon=\"@mipmap/ic_launcher_round\""));
        byte[] png = Files.readAllBytes(new File(
                root + "/app/res/drawable/ic_installer.png").toPath());
        boolean magic = png.length > 24
                && (png[0] & 0xFF) == 0x89 && png[1] == 'P'
                && png[2] == 'N' && png[3] == 'G';
        check("installer: ic_installer is a real PNG", magic);
        int w = magic ? ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16)
                | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF) : -1;
        int h = magic ? ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16)
                | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF) : -1;
        checkEq("installer: ic_installer width is 512", 512, w);
        checkEq("installer: ic_installer height is 512", 512, h);
    }

    // --------------------------------- v0.17.0 the sanctuary layout

    /** One app source file's text — construction audits read the
     *  shipped sources like the installer suite reads the manifest. */
    private static String srcText(String name) throws Exception {
        String root = System.getProperty("user.home")
                + "/workspace/tala-tv-2026-10-16";
        return new String(Files.readAllBytes(new File(
                root + "/app/src/com/talacore/talatv/" + name + ".java")
                .toPath()), "UTF-8");
    }

    private static void collectSources(File dir, StringBuilder sb)
            throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectSources(f, sb);
            else if (f.getName().endsWith(".java")) {
                sb.append(new String(Files.readAllBytes(f.toPath()),
                        "UTF-8")).append('\n');
            }
        }
    }

    static void sanctuarySuite() throws Exception {
        String classesDir = System.getProperty("sandy.classes");
        // The palette, pinned exactly — gold and teal are the
        // only light in the room, and these are their values.
        checkEq("sanctuary: ground top", 0xFF0C1417, Sanctuary.GROUND_TOP);
        checkEq("sanctuary: ground", 0xFF07090C, Sanctuary.GROUND);
        checkEq("sanctuary: ground deep", 0xFF04060A, Sanctuary.GROUND_DEEP);
        checkEq("sanctuary: panel hi", 0xFF1B2530, Sanctuary.PANEL_HI);
        checkEq("sanctuary: panel", 0xFF121A21, Sanctuary.PANEL);
        checkEq("sanctuary: gold", 0xFFF0B84C, Sanctuary.GOLD);
        checkEq("sanctuary: gold text", 0xFFF6CD7E, Sanctuary.GOLD_TEXT);
        checkEq("sanctuary: teal", 0xFF55C9B8, Sanctuary.TEAL);
        checkEq("sanctuary: ink", 0xFFF5F0E6, Sanctuary.INK);
        checkEq("sanctuary: body", 0xFFDCD4C3, Sanctuary.BODY);
        checkEq("sanctuary: muted", 0xFFA79E8E, Sanctuary.MUTED);
        checkEq("sanctuary: overlay", 0xB3121A21, Sanctuary.OVERLAY);
        checkEq("sanctuary: rail card is 116dp wide",
                116, Sanctuary.RAIL_CARD_W_DP);
        checkEq("sanctuary: rail poster height is 152dp",
                152, Sanctuary.RAIL_IMG_H_DP);
        checkEq("sanctuary: section gap is 20dp",
                20, Sanctuary.SECTION_GAP_DP);

        // The register stands on every main surface (audit by
        // construction: the compiled surface references Sanctuary).
        for (String surface : new String[]{
                "HomeActivity", "MainActivity", "ShowActivity",
                "GuideActivity", "LibraryActivity", "VodActivity",
                "WallListActivity", "StoryShelfActivity", "CreditActivity",
                "ReaderRoomActivity", "PlayerActivity", "SplashActivity",
                "SettingsActivity"}) {
            String bytes = classBytes(classesDir, surface);
            check("sanctuary: " + surface + " wears the register",
                    bytes != null && bytes.contains("Sanctuary"));
        }

        // Home's rails are windowed PosterRails now.
        String home = srcText("HomeActivity");
        check("sanctuary: home rails are PosterRails",
                home.contains("new PosterRail(this)")
                        && home.contains("PosterRail.Tiles")
                        && home.contains("bindRailCard"));
        check("sanctuary: the hero stands on the home wall",
                home.contains("addHero") && home.contains("glassPanel"));

        // FLUID, pinned by construction: the rail recycles a
        // bounded pool, binds on window change, and never decodes.
        String rail = srcText("PosterRail");
        check("fluid: the rail keeps a recycle pool",
                rail.contains("ArrayDeque") && rail.contains("pool"));
        check("fluid: the rail windows on scroll",
                rail.contains("onScrollChanged")
                        && rail.contains("updateWindow")
                        && rail.contains("bindTile"));
        check("fluid: the rail never decodes an image",
                !rail.contains("BitmapFactory"));
        check("fluid: rails keep LTR window math in every language",
                rail.contains("LAYOUT_DIRECTION_LTR"));

        // Image loading: cached in memory, decoded off the UI
        // thread on a fixed pool — the load call hands off to the
        // executor BEFORE any decode call in the file.
        String cache = srcText("ImageCache");
        check("fluid: image cache is an LruCache on a fixed pool",
                cache.contains("LruCache")
                        && cache.contains("newFixedThreadPool"));
        check("fluid: decode happens inside the executor's task",
                cache.indexOf("EXEC.execute") >= 0
                        && cache.indexOf("BitmapFactory.decodeFile")
                                > cache.indexOf("EXEC.execute"));

        // The list rows that were already recycled stay recycled.
        check("fluid: channel rows recycle convertView",
                srcText("MainActivity").contains("convertView instanceof"));
        check("fluid: episode rows recycle convertView",
                srcText("ShowActivity").contains("convertView instanceof"));

        // The manifest: hardware acceleration on, installer face
        // untouched (the installer suite pins the icon wiring).
        String root = System.getProperty("user.home")
                + "/workspace/tala-tv-2026-10-16";
        String manifest = new String(Files.readAllBytes(
                new File(root + "/app/AndroidManifest.xml").toPath()), "UTF-8");
        int appAt = manifest.indexOf("<application");
        String appTag = manifest.substring(appAt, manifest.indexOf('>', appAt));
        check("sanctuary: hardware acceleration is on",
                appTag.contains("android:hardwareAccelerated=\"true\""));

        // The subtraction equation: a design element on the
        // credit page, table-driven in all 13 languages.
        checkEq("sanctuary: the equation, in English",
                "Sandy = Media \u2212 (Friction + Cost + Surveillance)",
                UiStrings.lookup("en", "credit_equation"));
        boolean eqAll = true;
        for (String[] lang : UiStrings.LANGUAGES) {
            String v = UiStrings.lookup(lang[0], "credit_equation");
            if (v == null || !v.contains("Sandy =")) eqAll = false;
        }
        check("sanctuary: the equation stands in all 13 languages", eqAll);
        check("sanctuary: the credit page shows the equation",
                srcText("CreditActivity").contains("credit_equation"));

        // The honesty fences: none of the panels' capability
        // claims may enter the app — audited over every source.
        StringBuilder all = new StringBuilder();
        collectSources(new File(root
                + "/app/src/com/talacore/talatv"), all);
        String blob = all.toString();
        check("fence: no 84-languages claim anywhere",
                !blob.contains("84 languages"));
        check("fence: no zero-network claim anywhere",
                !blob.contains("zero network"));
        check("fence: no mesh/LoRa claim anywhere",
                !blob.contains("LoRa"));
        check("fence: no real-time dubbing claim anywhere",
                !blob.contains("real-time dubbing"));
    }

    // ------------------------------------- v0.17.0 the channel scan

    static void channelScanSuite() throws Exception {
        String classesDir = System.getProperty("sandy.classes");
        // SG-1 + v0.18.0, the truth table: the proof tier is
        // the PLAYER'S tier — segment-proven is LIVE; playlist-
        // proven is LISTED (presented as listed, never live);
        // an answering door outranks a later dead head (it
        // demotes to LISTED, it does not convict); terminal
        // with nothing answered is DEAD; slow is NEVER dead.
        checkEq("scan: segment-proven is live", ChannelScan.V_LIVE,
                ChannelScan.classify(LinkHealth.TIER_PLAYS,
                        LinkHealth.TIER_PLAYS, false));
        checkEq("scan: playlist-proven is listed, not live",
                ChannelScan.V_LISTED,
                ChannelScan.classify(LinkHealth.TIER_CONNECTS,
                        LinkHealth.TIER_CONNECTS, false));
        checkEq("scan: an early proof outranks a later dead head"
                        + " \u2014 listed, not convicted",
                ChannelScan.V_LISTED,
                ChannelScan.classify(LinkHealth.TIER_CONNECTS,
                        LinkHealth.TIER_DEAD, false));
        checkEq("scan: terminal is dead on the spot", ChannelScan.V_DEAD,
                ChannelScan.classify(LinkHealth.TIER_DEAD,
                        LinkHealth.TIER_DEAD, false));
        checkEq("scan: slow is never dead", ChannelScan.V_UNKNOWN,
                ChannelScan.classify(LinkHealth.TIER_DEAD,
                        LinkHealth.TIER_DEAD, true));
        checkEq("scan: nothing proven, nothing terminal = unknown",
                ChannelScan.V_UNKNOWN,
                ChannelScan.classify(LinkHealth.TIER_DEAD,
                        LinkHealth.TIER_CONNECTS, true));

        // The persisted record: round-trip, torn lines skipped,
        // the summary riding the first line.
        Map<String, ChannelScan.Rec> recs =
                new java.util.LinkedHashMap<>();
        recs.put("http://a.example/live.m3u8", new ChannelScan.Rec(
                "http://a.example/live.m3u8", ChannelScan.KIND_LIVE,
                ChannelScan.V_LIVE, 111L, "plays"));
        recs.put("http://b.example/movie.mp4", new ChannelScan.Rec(
                "http://b.example/movie.mp4", ChannelScan.KIND_VOD,
                ChannelScan.V_DEAD, 222L, "HTTP 404 — gone"));
        recs.put("http://c.example/slow.m3u8", new ChannelScan.Rec(
                "http://c.example/slow.m3u8", ChannelScan.KIND_LIVE,
                ChannelScan.V_UNKNOWN, 333L, "didn't answer in time"));
        String tsv = ChannelScan.toTsv(recs,
                new ChannelScan.Summary(999L, 3, 1, 1));
        ChannelScan.Parsed parsed = ChannelScan.parse(tsv);
        checkEq("scan: record round-trips every link", 3, parsed.recs.size());
        checkEq("scan: the dead stay dead in the file",
                ChannelScan.V_DEAD, ChannelScan.verdictOf(parsed.recs,
                        "http://b.example/movie.mp4"));
        checkEq("scan: the plain reason round-trips",
                "HTTP 404 — gone",
                parsed.recs.get("http://b.example/movie.mp4").how);
        checkEq("scan: summary last-scan round-trips",
                999L, parsed.summary.lastScanMs);
        checkEq("scan: summary tested round-trips", 3, parsed.summary.tested);
        checkEq("scan: summary live round-trips", 1, parsed.summary.live);
        checkEq("scan: summary removed round-trips",
                1, parsed.summary.removed);
        checkEq("scan: a torn line is skipped, never guessed",
                3, ChannelScan.parse(tsv + "torn\tline\n").recs.size());
        check("scan: an empty file parses empty",
                ChannelScan.parse("").recs.isEmpty()
                        && ChannelScan.parse(null).summary.lastScanMs == 0);

        // Presentation: dead links are OFF the shelf; live,
        // unknown and untested links present.
        List<String> shelf = ChannelScan.presentable(
                java.util.Arrays.asList(
                        "http://a.example/live.m3u8",
                        "http://b.example/movie.mp4",
                        "http://c.example/slow.m3u8",
                        "http://d.example/never-tested.m3u8"),
                parsed.recs);
        checkEq("scan: only the dead leave the shelf", 3, shelf.size());
        check("scan: the dead link is gone, not badged",
                !shelf.contains("http://b.example/movie.mp4"));
        check("scan: the slow link keeps its place",
                shelf.contains("http://c.example/slow.m3u8"));
        check("scan: the untested link keeps its place",
                shelf.contains("http://d.example/never-tested.m3u8"));
        checkEq("scan: an unheard-of link is untested",
                ChannelScan.V_UNTESTED,
                ChannelScan.verdictOf(parsed.recs, "http://z.example/x"));

        // Printed counts are verified counts: they come from the
        // record itself, by the same math the surface prints.
        int[] counts = ChannelScan.counts(parsed.recs.values());
        checkEq("scan: counts — live", 1, counts[0]);
        checkEq("scan: counts — dead", 1, counts[1]);
        checkEq("scan: counts — unknown", 1, counts[2]);
        ChannelScan.Summary sum = ChannelScan.summarize(parsed.recs, 42L, 7);
        checkEq("scan: summarize counts live from the record", 1, sum.live);
        checkEq("scan: summarize counts removed from the record",
                1, sum.removed);
        checkEq("scan: summarize keeps the run's tested count", 7, sum.tested);

        // Cadence: fresh verdicts are not re-walked by an auto
        // pass; a manual scan re-tests everything.
        long now = 10_000_000_000L;
        ChannelScan.Rec fresh = new ChannelScan.Rec("http://a.example/x",
                ChannelScan.KIND_LIVE, ChannelScan.V_LIVE, now - 1000L, "");
        ChannelScan.Rec stale = new ChannelScan.Rec("http://a.example/x",
                ChannelScan.KIND_LIVE, ChannelScan.V_LIVE,
                now - ChannelScan.CADENCE_MS - 1L, "");
        check("scan: a fresh verdict is not due on auto",
                !ChannelScan.isDue(fresh, now, false));
        check("scan: a manual scan re-tests even the fresh",
                ChannelScan.isDue(fresh, now, true));
        check("scan: a stale verdict is due",
                ChannelScan.isDue(stale, now, false));
        check("scan: an untested link is always due",
                ChannelScan.isDue(null, now, false));

        // Bounds: the sweep's concurrency and per-link budget are
        // the house's own numbers, not new appetites.
        checkEq("scan: bounded concurrency", 4, ChannelScan.CONCURRENCY);
        checkEq("scan: budget is Hydra's shared cap",
                LinkHealth.TOTAL_CAP_MS, ChannelScan.BUDGET_MS);
        checkEq("scan: the cap is ~30 s", 30000L, ChannelScan.BUDGET_MS);

        // The engine, by construction: Hydra does the walking, on
        // a bounded pool, verdicts land in the record the moment
        // they are decided, and the record is the memory-hook file.
        String engine = srcText("ScanEngine");
        check("scan: the engine walks through Hydra",
                engine.contains("HydraResolver.prove"));
        check("scan: the engine pool is the pinned bound",
                engine.contains("newFixedThreadPool")
                        && engine.contains("ChannelScan.CONCURRENCY"));
        check("scan: verdicts classify through the SG-1 table",
                engine.contains("ChannelScan.classify"));
        check("scan: verdicts persist as they land",
                engine.contains("ScanStore.record"));
        check("scan: the record is scan_links.tsv on the memory hook",
                srcText("ScanStore").contains("scan_links.tsv"));
        String engineBytes = classBytes(classesDir, "ScanEngine");
        check("scan: the compiled engine carries Hydra + the record",
                engineBytes != null && engineBytes.contains("HydraResolver")
                        && engineBytes.contains("ScanStore"));

        // Display consequence is wired where the shelves are built.
        check("scan: Live TV drops the scan-dead",
                srcText("MainActivity").contains("ChannelScan.V_DEAD"));
        check("scan: the VOD shelf drops the scan-dead",
                srcText("VodActivity").contains("ChannelScan.V_DEAD"));
        check("scan: dead entries lend no name to a wall tile",
                srcText("CatalogStore").contains("ChannelScan.V_DEAD"));

        // The human surface: a Scan action and honest progress,
        // in every language the house speaks.
        String[] scanKeys = {"scan_action", "scan_progress",
                "scan_summary", "scan_none"};
        boolean scanAll = true;
        for (String[] lang : UiStrings.LANGUAGES) {
            for (String key : scanKeys) {
                String v = UiStrings.lookup(lang[0], key);
                if (v == null || v.isEmpty() || v.equals(key)) scanAll = false;
            }
        }
        check("scan: the surface speaks in all 13 languages", scanAll);
        check("scan: progress carries both counters",
                UiStrings.lookup("en", "scan_progress")
                        .contains("%1$d of %2$d"));
        check("scan: the summary carries time + three counts",
                UiStrings.lookup("en", "scan_summary").contains("%1$s")
                        && UiStrings.lookup("en", "scan_summary")
                                .contains("%4$d"));
        check("scan: Live TV carries the Scan action",
                srcText("MainActivity").contains("scan_action"));
        check("scan: Home carries the Scan action",
                srcText("HomeActivity").contains("scan_action"));
    }

    // ------------------------------------- v0.18.0 pillar 1: the proof tier

    static LinkHealth.Result resultAt(int tier, boolean slow) {
        LinkHealth.Result r = new LinkHealth.Result();
        r.tier = tier;
        r.slow = slow;
        return r;
    }

    static void proveTierSuite() throws Exception {
        // The player's tier is the only tier that presents as
        // working: a segment proof outranks even a dead tail.
        checkEq("tier: segment proof outranks a dead tail",
                ChannelScan.V_LIVE,
                ChannelScan.classify(LinkHealth.TIER_PLAYS,
                        LinkHealth.TIER_DEAD, false));
        check("tier: only LIVE presents as working",
                ChannelScan.presentedAsWorking(ChannelScan.V_LIVE)
                        && !ChannelScan.presentedAsWorking(ChannelScan.V_LISTED)
                        && !ChannelScan.presentedAsWorking(ChannelScan.V_DEAD)
                        && !ChannelScan.presentedAsWorking(ChannelScan.V_UNKNOWN)
                        && !ChannelScan.presentedAsWorking(ChannelScan.V_UNTESTED));

        // THE LIVE-CHANNEL FIXTURE (James's field report): the
        // playlist answers, the first segment refuses. Under
        // the old law this link filed LIVE; under the player's
        // law it files LISTED and never presents as working.
        int fixture = ChannelScan.classify(LinkHealth.TIER_CONNECTS,
                LinkHealth.TIER_CONNECTS, false);
        checkEq("tier: playlist answers, segment refuses = listed",
                ChannelScan.V_LISTED, fixture);
        check("tier: the fixture never presents as working",
                !ChannelScan.presentedAsWorking(fixture));

        // The verdict/result agreement gate: a walk's result is
        // filed only when it agrees with the verdict at tier —
        // a mid-walk result never contradicts the best proof.
        check("tier: LIVE agrees with a plays result",
                ScanEngine.agreesAtTier(ChannelScan.V_LIVE,
                        resultAt(LinkHealth.TIER_PLAYS, false)));
        check("tier: LIVE does not agree with a connects result",
                !ScanEngine.agreesAtTier(ChannelScan.V_LIVE,
                        resultAt(LinkHealth.TIER_CONNECTS, false)));
        check("tier: LISTED agrees with a connects result",
                ScanEngine.agreesAtTier(ChannelScan.V_LISTED,
                        resultAt(LinkHealth.TIER_CONNECTS, false)));
        check("tier: LISTED does not agree with a plays result",
                !ScanEngine.agreesAtTier(ChannelScan.V_LISTED,
                        resultAt(LinkHealth.TIER_PLAYS, false)));
        check("tier: DEAD agrees only with a terminal result",
                ScanEngine.agreesAtTier(ChannelScan.V_DEAD,
                        resultAt(LinkHealth.TIER_DEAD, false))
                        && !ScanEngine.agreesAtTier(ChannelScan.V_DEAD,
                                resultAt(LinkHealth.TIER_DEAD, true)));
        check("tier: UNKNOWN files nothing, whatever the result",
                !ScanEngine.agreesAtTier(ChannelScan.V_UNKNOWN,
                        resultAt(LinkHealth.TIER_PLAYS, false))
                        && !ScanEngine.agreesAtTier(ChannelScan.V_LIVE, null));

        // A listed verdict persists, prints, and round-trips as
        // listed — counted apart from live, never folded in.
        Map<String, ChannelScan.Rec> recs =
                new java.util.LinkedHashMap<>();
        recs.put("http://a.example/live.m3u8", new ChannelScan.Rec(
                "http://a.example/live.m3u8", ChannelScan.KIND_LIVE,
                ChannelScan.V_LISTED, 111L,
                FailureWords.englishFor(FailureWords.REFUSED, 403)));
        String tsv = ChannelScan.toTsv(recs,
                new ChannelScan.Summary(999L, 5, 2, 1, 2));
        ChannelScan.Parsed parsed = ChannelScan.parse(tsv);
        checkEq("tier: the listed verdict round-trips",
                ChannelScan.V_LISTED, ChannelScan.verdictOf(parsed.recs,
                        "http://a.example/live.m3u8"));
        checkEq("tier: the summary's listed count round-trips",
                2, parsed.summary.listed);
        int[] counts = ChannelScan.counts(parsed.recs.values());
        checkEq("tier: counts keep listed apart from live", 1, counts[3]);
        checkEq("tier: counts do not fold listed into live", 0, counts[0]);
        ChannelScan.Summary sum = ChannelScan.summarize(parsed.recs, 42L, 1);
        checkEq("tier: summarize counts listed from the record", 1, sum.listed);
        checkEq("tier: summarize keeps listed out of live", 0, sum.live);
        // The refusing door is named in plain words in the record.
        checkEq("tier: the plain-words refusal round-trips",
                FailureWords.englishFor(FailureWords.REFUSED, 403),
                parsed.recs.get("http://a.example/live.m3u8").how);

        // A pre-0.18.0 record (no listed field) still parses:
        // listed reads as zero, nothing is guessed.
        String oldTsv = "#\t999\t5\t2\t1\n"
                + "http://a.example/live.m3u8\tlive\t0\t111\tplays\n";
        ChannelScan.Parsed oldParsed = ChannelScan.parse(oldTsv);
        checkEq("tier: an old record's listed reads zero",
                0, oldParsed.summary.listed);
        checkEq("tier: an old record's live still reads", 2,
                oldParsed.summary.live);

        // The scan walks the PLAYER'S ladder, by construction:
        // the exhaustive prove, in the engine and in the
        // episode pool alike; listed names lend to no tile.
        check("tier: the engine walks the player's own ladder",
                srcText("ScanEngine").contains("proveExhaustive"));
        check("tier: the episode pool walks the same ladder",
                srcText("NextEpisodeResolver").contains("proveExhaustive"));
        check("tier: listed entries lend no name to a wall tile",
                srcText("CatalogStore").contains("ChannelScan.V_LISTED"));

        // Plain words, never a bare error: the failure table.
        check("words: gone names itself in plain words",
                FailureWords.englishFor(FailureWords.GONE, 404)
                        .contains("isn't there"));
        check("words: refused names the refusing door",
                FailureWords.englishFor(FailureWords.REFUSED, 403)
                        .contains("refused"));
        check("words: slow is worded as slow, never as death",
                FailureWords.englishFor(FailureWords.TIMEOUT, 0)
                        .contains("slow"));
        // Search-out-why (pillar 2): the door memory is the
        // house's hydra_doors.tsv, fed by the resolver's wins.
        check("words: the door memory is hydra_doors.tsv",
                srcText("HydraStore").contains("hydra_doors.tsv"));
        check("words: scan failures print the listed part too",
                srcText("MainActivity").contains("scan_listed_part")
                        && srcText("HomeActivity").contains("scan_listed_part")
                        && srcText("VodActivity").contains("scan_listed_part"));
    }

    // ------------------------------------- v0.18.0 pillar 3: the receipts

    static void receiptsSuite() throws Exception {
        List<SourceReceipts.Rec> seed = SourceReceipts.seed();
        checkEq("receipt: the seed carries eight sources", 8, seed.size());
        Map<String, SourceReceipts.Rec> byId =
                SourceReceipts.parse(SourceReceipts.seedTsv());
        SourceReceipts.Rec ia = byId.get("internet_archive");
        SourceReceipts.Rec commons = byId.get("wikimedia_commons");
        SourceReceipts.Rec nfaj = byId.get("nfaj_janim");
        SourceReceipts.Rec gutenberg = byId.get("project_gutenberg");
        SourceReceipts.Rec openlib = byId.get("open_library");
        SourceReceipts.Rec librivox = byId.get("librivox");
        SourceReceipts.Rec tvmaze = byId.get("tvmaze");
        SourceReceipts.Rec iptv = byId.get("iptvorg");
        check("receipt: every bound source is in the seed",
                ia != null && commons != null && nfaj != null
                        && gutenberg != null && openlib != null
                        && librivox != null && tvmaze != null && iptv != null);

        // The gate: stream + bytes tiers, or no playable claim.
        check("receipt: the archive backs playable",
                SourceReceipts.backsPlayable(ia));
        check("receipt: Commons backs playable",
                SourceReceipts.backsPlayable(commons));
        check("receipt: NFAJ backs playable",
                SourceReceipts.backsPlayable(nfaj));
        check("receipt: LibriVox backs playable",
                SourceReceipts.backsPlayable(librivox));
        check("receipt: a metadata-only receipt backs nothing playable",
                !SourceReceipts.backsPlayable(tvmaze)
                        && !SourceReceipts.backsPlayable(iptv));
        check("receipt: a text source backs no playable claim",
                !SourceReceipts.backsPlayable(gutenberg)
                        && !SourceReceipts.backsPlayable(openlib));
        check("receipt: reading is backed by catalogue + bytes",
                SourceReceipts.backsReadable(gutenberg)
                        && !SourceReceipts.backsReadable(openlib));

        // The eyes tier, as ordered: Pearl saw both pages herself.
        checkEq("receipt: Commons carries Pearl's eyes",
                "seen by Pearl, 2026-10-20", commons.eyes);
        checkEq("receipt: NFAJ carries Pearl's eyes",
                "seen by Pearl, 2026-10-20", nfaj.eyes);
        checkEq("receipt: the census round is recorded",
                "Round 2", commons.round);
        checkEq("receipt: the proof date is recorded",
                "2026-10-20", commons.provedDate);
        checkEq("receipt: the census round is recorded for NFAJ",
                "Round 2", nfaj.round);
        checkEq("receipt: tiers read in plain words",
                "catalogue, page, stream, bytes",
                SourceReceipts.tiersWords(commons));
        checkEq("receipt: a catalogue-only tier list reads so",
                "catalogue", SourceReceipts.tiersWords(tvmaze));

        // The seed on disk is the seed in code — one record.
        String root = System.getProperty("user.home")
                + "/workspace/tala-tv-2026-10-16";
        String asset = new String(Files.readAllBytes(new File(
                root + "/app/assets/source_receipts_seed.tsv").toPath()),
                "UTF-8");
        checkEq("receipt: the shipped seed asset is the code seed",
                SourceReceipts.seedTsv(), asset);
        Map<String, SourceReceipts.Rec> round = SourceReceipts.parse(asset);
        checkEq("receipt: the seed parses back whole", 8, round.size());
        checkEq("receipt: the TSV round-trips exactly",
                asset, SourceReceipts.toTsv(round));

        // World-changed: only a terminal door answer moves a
        // receipt; slowness never does; a moved receipt stays.
        check("receipt: terminal kinds are gone/refused/address",
                SourceReceipts.isTerminalKind(FailureWords.GONE)
                        && SourceReceipts.isTerminalKind(FailureWords.REFUSED)
                        && SourceReceipts.isTerminalKind(FailureWords.ADDRESS)
                        && !SourceReceipts.isTerminalKind(FailureWords.TIMEOUT)
                        && !SourceReceipts.isTerminalKind(FailureWords.SERVER)
                        && !SourceReceipts.isTerminalKind(FailureWords.NO_ANSWER)
                        && !SourceReceipts.isTerminalKind(FailureWords.CLEARTEXT));
        SourceReceipts.Rec slow = SourceReceipts.noteDoorResult(
                ia, FailureWords.TIMEOUT, "2026-10-21", "didn't answer in time");
        check("receipt: a slow door never marks the world changed",
                slow == ia && SourceReceipts.STATE_BOUND.equals(ia.state));
        SourceReceipts.Rec moved = SourceReceipts.noteDoorResult(
                ia, FailureWords.GONE, "2026-10-21",
                "That file isn't there anymore.");
        check("receipt: a terminal door marks world-changed",
                moved != ia
                        && SourceReceipts.STATE_WORLD_CHANGED.equals(moved.state)
                        && "2026-10-21".equals(moved.changedDate)
                        && "That file isn't there anymore."
                                .equals(moved.changedHow));
        check("receipt: the bind proof itself is never edited",
                moved.stream && moved.bytes
                        && "Round 1".equals(moved.round));
        check("receipt: a world-changed receipt backs nothing",
                !SourceReceipts.backsPlayable(moved));
        check("receipt: world-changed is terminal — it stays moved",
                SourceReceipts.noteDoorResult(moved, FailureWords.REFUSED,
                        "2026-10-22", "refused") == moved
                        && "2026-10-21".equals(moved.changedDate));

        // The wiring: the record lives on the memory hook, and
        // the films room stands behind the gate.
        check("receipt: the record is source_receipts.tsv",
                srcText("ReceiptStore").contains("source_receipts.tsv"));
        check("receipt: the films room consults the gate",
                srcText("ArchiveFilmsActivity")
                        .contains("ReceiptStore.backsPlayable"));
    }

    // ------------------------------------- v0.18.0 pillar 4a: Commons

    static void commonsSuite() throws Exception {
        String json = "{\"query\":{\"pages\":{"
                + "\"101\":{\"index\":1,\"title\":\"File:Night of the Living Dead (1968).webm\","
                + "\"imageinfo\":[{\"url\":\"https://upload.wikimedia.org/wikipedia/commons/f/f0/Night_of_the_Living_Dead_(1968).webm?utm_source=fixture\","
                + "\"size\":123456789,\"mime\":\"video/webm\",\"width\":1920,\"height\":1080,"
                + "\"thumburl\":\"https://upload.wikimedia.org/thumb/101.jpg\","
                + "\"extmetadata\":{\"LicenseShortName\":{\"value\":\"Public domain\"}}}],"
                + "\"videoinfo\":[{\"derivatives\":["
                + "{\"src\":\"https://upload.wikimedia.org/trans/480p.vp9.webm\",\"type\":\"video/webm; codecs=\\\"vp9, opus\\\"\",\"width\":854,\"height\":480},"
                + "{\"src\":\"https://upload.wikimedia.org/trans/360p.mp4\",\"type\":\"video/mp4; codecs=\\\"avc1.4d401f, mp4a.40.2\\\"\",\"width\":640,\"height\":360},"
                + "{\"src\":\"https://upload.wikimedia.org/trans/1080p.av1.webm\",\"type\":\"video/webm; codecs=\\\"av01.0.05M.08, opus\\\"\",\"width\":1920,\"height\":1080}"
                + "]}]},"
                + "\"102\":{\"index\":2,\"title\":\"File:A Trip to the Moon (1902).webm\","
                + "\"imageinfo\":[{\"url\":\"https://upload.wikimedia.org/wikipedia/commons/a/a0/Trip.webm\","
                + "\"size\":98765,\"mime\":\"video/webm\",\"width\":640,\"height\":480,"
                + "\"thumburl\":\"https://upload.wikimedia.org/thumb/102.jpg\","
                + "\"extmetadata\":{\"LicenseShortName\":{\"value\":\"CC BY-SA 4.0\"}}}],"
                + "\"videoinfo\":[{\"derivatives\":[]}]}"
                + "}}}";
        List<CommonsClient.Item> items = CommonsClient.parseItems(json);
        checkEq("commons: the fixture parses both films", 2, items.size());
        CommonsClient.Item dead = items.get(0);
        checkEq("commons: the display title drops the File: prefix",
                "Night of the Living Dead (1968)", dead.title);
        checkEq("commons: tracking parameters are stripped",
                "https://upload.wikimedia.org/wikipedia/commons/f/f0/Night_of_the_Living_Dead_(1968).webm",
                dead.originalUrl);
        checkEq("commons: the rights label rides the item",
                "Public domain", dead.license);
        checkEq("commons: each file keeps its own rights label",
                "CC BY-SA 4.0", items.get(1).license);
        checkEq("commons: the derivatives ride along", 3, dead.renditions.size());
        check("commons: an empty answer parses empty",
                CommonsClient.parseItems("{}").isEmpty()
                        && CommonsClient.parseItems(null).isEmpty());
        checkEq("commons: the file name is the API's own shape",
                "Night_of_the_Living_Dead_(1968).webm",
                CommonsClient.fileNameOf(dead.pageTitle));

        // WebM acceptance: VP9 and AV1 are first-class citizens,
        // and the ladder walks MPEG-4 first (house rule), WebM
        // next, the original last — never the other way.
        List<CommonsClient.Rendition> ladder =
                CommonsClient.renditionLadder(dead);
        checkEq("commons: the ladder carries every door + the original",
                4, ladder.size());
        checkEq("commons: MPEG-4 leads the ladder",
                "MPEG-4", ladder.get(0).codec);
        checkEq("commons: the WebM VP9 door is accepted",
                "VP9", ladder.get(1).codec);
        checkEq("commons: the WebM AV1 door is accepted",
                "AV1", ladder.get(2).codec);
        check("commons: the original closes the ladder",
                ladder.get(3).url.equals(dead.originalUrl));
        checkEq("commons: codec reading — VP9 WebM",
                "VP9", CommonsClient.codecOf("video/webm; codecs=\"vp9, opus\""));
        checkEq("commons: container reading — WebM",
                "WebM", CommonsClient.containerOf("video/webm"));
        checkEq("commons: container reading — MP4",
                "MP4", CommonsClient.containerOf(
                        "video/mp4; codecs=\"avc1.4d401f\""));

        // Captions: the TimedText index parses, foreign files
        // are excluded, and the pick prefers the viewer, then
        // English, then the first offered.
        String capsJson = "{\"query\":{\"allpages\":["
                + "{\"title\":\"TimedText:Film_one.webm.en.srt\"},"
                + "{\"title\":\"TimedText:Film_one.webm.de.srt\"},"
                + "{\"title\":\"TimedText:Film_one.webm.fr.srt\"},"
                + "{\"title\":\"TimedText:Other_film.webm.en.srt\"}"
                + "]}}";
        List<CommonsClient.Caption> caps =
                CommonsClient.parseCaptions(capsJson, "Film_one.webm");
        checkEq("commons: captions parse for this file only", 3, caps.size());
        checkEq("commons: the viewer's language is picked",
                "de", CommonsClient.pickCaption(caps, "de").lang);
        checkEq("commons: English is the fallback",
                "en", CommonsClient.pickCaption(caps, "es").lang);
        check("commons: the raw caption door is the API's",
                CommonsClient.captionRawUrl("TimedText:Film_one.webm.en.srt")
                        .contains("action=raw"));
        check("commons: the search door asks for video files",
                CommonsClient.searchUrl("moon").contains("generator=search")
                        && CommonsClient.searchUrl("moon").contains("filetype"));

        // The room, by construction: Commons behind the receipt
        // gate, proved through the rendition ladder by Hydra.
        String room = srcText("ArchiveFilmsActivity");
        check("commons: the room proves through the rendition ladder",
                room.contains("CommonsClient.renditionLadder")
                        && room.contains("proveExhaustive"));
        check("commons: the rights label is printed on the tile",
                room.contains("it.license"));
    }

    // -------------------------------------- v0.18.0 pillar 4b: NFAJ

    static void nfajSuite() throws Exception {
        String html = "<ul>"
                + "<li class=\"work-result\">"
                + "<a href=\"/en/works/view/5141\">"
                + "<img src=\"/img/works/5141.jpg\" alt=\"\">"
                + "<h2 class=\"work-result-heading\">The Quack Infantry Troop</h2>"
                + "<p class=\"work-result-title-en\">あひる陸戰隊</p>"
                + "<div class=\"work-result-summary\">1940 / Japan / silent cartoon</div>"
                + "</a></li>"
                + "<li class=\"work-result\">"
                + "<a href=\"/en/works/view/5001\">"
                + "<img src=\"/img/works/5001.jpg\" alt=\"\">"
                + "<h2 class=\"work-result-heading\">The Village Festival</h2>"
                + "<p class=\"work-result-title-en\">村祭り</p>"
                + "<div class=\"work-result-summary\">1933 / Japan</div>"
                + "</a></li></ul>";
        List<NfajClient.Work> works = NfajClient.parseList(html);
        checkEq("nfaj: the catalogue fixture parses both works", 2, works.size());
        NfajClient.Work quack = works.get(0);
        checkEq("nfaj: the work id rides the view door", 5141, quack.id);
        checkEq("nfaj: the view door is the archive's own page",
                "https://animation.filmarchives.jp/en/works/view/5141",
                quack.viewUrl);
        checkEq("nfaj: the English title parses",
                "The Quack Infantry Troop", quack.titleEn);
        checkEq("nfaj: the Japanese title parses", "あひる陸戰隊", quack.titleJa);
        checkEq("nfaj: the year parses", "1940", quack.year);
        checkEq("nfaj: the second work keeps its own year",
                "1933", works.get(1).year);
        check("nfaj: an empty page parses empty",
                NfajClient.parseList("<html></html>").isEmpty()
                        && NfajClient.parseList(null).isEmpty());

        // The censused chain, pinned door by door: catalogue →
        // play page → the NII stream host, auto first, then
        // the fixed renditions as alternate dialects.
        checkEq("nfaj: the catalogue door is the archive's own",
                "https://animation.filmarchives.jp/en/works",
                NfajClient.listUrl(1));
        check("nfaj: page two rides the catalogue's own query",
                NfajClient.listUrl(2).endsWith("?page=2"));
        checkEq("nfaj: the master door is the censused NII door",
                "https://h10.cs.nii.ac.jp/stream/nfc/5141_en/hls/auto/master.m3u8",
                NfajClient.masterUrl(5141));
        List<String> doors = NfajClient.streamDoors(5141);
        checkEq("nfaj: five stream doors, auto first", 5, doors.size());
        check("nfaj: the auto door leads, renditions follow",
                doors.get(0).equals(NfajClient.masterUrl(5141))
                        && doors.get(1).contains("/480/")
                        && doors.get(4).contains("/144/"));
        checkEq("nfaj: entity text cleans to plain words",
                "a & b's", NfajClient.cleanText("a &amp; b&#39;s"));
        checkEq("nfaj: tags clean out of titles",
                "x y", NfajClient.cleanText("<b>x</b> y"));

        // The room, by construction: NFAJ proved door by door
        // through Hydra, the archive's no-copy wish printed.
        String room = srcText("ArchiveFilmsActivity");
        check("nfaj: the room proves through the stream doors",
                room.contains("NfajClient.streamDoors"));
        check("nfaj: the archive's wish is printed in the room",
                room.contains("films_nfaj_note"));
        check("nfaj: the room is registered in the manifest",
                new String(Files.readAllBytes(new File(
                        System.getProperty("user.home")
                                + "/workspace/tala-tv-2026-10-16"
                                + "/app/AndroidManifest.xml").toPath()),
                        "UTF-8").contains(".ArchiveFilmsActivity"));
        check("nfaj: Home carries the films door",
                srcText("HomeActivity").contains("ArchiveFilmsActivity")
                        && srcText("HomeActivity").contains("door_films"));
        boolean filmsAll = true;
        for (String[] lang : UiStrings.LANGUAGES) {
            for (String key : new String[]{"door_films", "films_featured",
                    "films_commons", "films_nfaj", "films_nfaj_note",
                    "films_search_hint", "films_none", "films_loading",
                    "films_unverified", "scan_listed_part"}) {
                String v = UiStrings.lookup(lang[0], key);
                if (v == null || v.isEmpty() || v.equals(key)) filmsAll = false;
            }
        }
        check("nfaj: the films room speaks in all 13 languages", filmsAll);
        check("nfaj: the listed part carries its count",
                UiStrings.lookup("en", "scan_listed_part").contains("%1$d"));
    }

}
