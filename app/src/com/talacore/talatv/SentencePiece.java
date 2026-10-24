package com.talacore.talatv;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SentencePiece, the opus-mt tokenization, in pure Java (v0.6.1).
 *
 * The Helsinki opus-mt models segment with a SentencePiece UNIGRAM
 * model: the best-scoring segmentation wins (Viterbi over piece
 * log-probabilities). This class reproduces that segmentation from
 * two flat tables exported from the model's own files at pack-build
 * time (never re-derived, never hand-copied):
 *
 *   sp_pieces.tsv — one line per source piece: piece \t score
 *                   (from source.spm, the segmenter's own table)
 *   vocab.tsv     — one line per model token id: piece
 *                   (the shared vocabulary order the MT model was
 *                   trained with; id = line number)
 *
 * Encoding: whitespace becomes ▁ and a ▁ is prefixed (SentencePiece's
 * surface convention), then Viterbi picks the highest-scoring piece
 * sequence; a character no piece covers becomes <unk> with the
 * unigram unknown penalty (min piece score − 10). Decoding maps ids
 * back to pieces, joins them, and turns ▁ back into spaces; the
 * special tokens </s>, <s>, <pad> render as nothing.
 *
 * The JVM harness pins this class against the real sentencepiece
 * library's encodings of every bench unit — the port is only
 * trusted because that comparison passes.
 */
public final class SentencePiece {

    private final Map<String, Double> pieceScore = new HashMap<>();
    private final Map<String, Integer> pieceId = new HashMap<>();
    private final List<String> idPiece = new ArrayList<>();
    private final double minScore;
    private int maxPieceLen = 0;

    public static final int UNK_ID = 1;
    public static final int EOS_ID = 0;   // </s> in the shared vocab
    public static final int PAD_ID = 60715;

    public SentencePiece(File spPiecesTsv, File vocabTsv) throws Exception {
        double min = Double.POSITIVE_INFINITY;
        for (String line : readLines(spPiecesTsv)) {
            int tab = line.lastIndexOf('\t');
            if (tab < 0) continue;
            String piece = line.substring(0, tab);
            double score = Double.parseDouble(line.substring(tab + 1));
            pieceScore.put(piece, score);
            if (score < min) min = score;
            if (piece.length() > maxPieceLen) maxPieceLen = piece.length();
        }
        minScore = min;
        int id = 0;
        for (String line : readLines(vocabTsv)) {
            idPiece.add(line);
            pieceId.put(line, id);
            id++;
        }
    }

    private static List<String> readLines(File f) throws Exception {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        }
        return out;
    }

    /** Segment + map to model ids (WITHOUT the trailing </s> — the
     *  MT engine adds it, per the recipe). */
    public int[] encode(String text) {
        String norm = "▁" + text.replace(" ", "▁");
        int n = norm.length();
        // Viterbi over characters (code points would be more exact;
        // pieces are matched on UTF-16 substrings, which is exact
        // for the BMP characters Japanese text uses)
        double[] best = new double[n + 1];
        int[] back = new int[n + 1];
        int[] backLen = new int[n + 1];
        for (int i = 1; i <= n; i++) best[i] = Double.NEGATIVE_INFINITY;
        best[0] = 0;
        for (int i = 0; i < n; i++) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue;
            int maxL = Math.min(maxPieceLen, n - i);
            for (int len = 1; len <= maxL; len++) {
                Double sc = pieceScore.get(norm.substring(i, i + len));
                if (sc == null) continue;
                double v = best[i] + sc;
                if (v > best[i + len]) {
                    best[i + len] = v;
                    back[i + len] = i;
                    backLen[i + len] = len;
                }
            }
            // an unknown single character always competes (the
            // unigram lattice carries it with the penalty score)
            double v = best[i] + (minScore - 10.0);
            if (v > best[i + 1]) {
                best[i + 1] = v;
                back[i + 1] = i;
                backLen[i + 1] = -1; // marks <unk>
            }
        }
        // walk back
        List<Integer> ids = new ArrayList<>();
        int i = n;
        List<int[]> path = new ArrayList<>();
        while (i > 0) {
            int len = backLen[i];
            path.add(new int[]{back[i], len});
            i = back[i];
        }
        for (int k = path.size() - 1; k >= 0; k--) {
            int start = path.get(k)[0];
            int len = path.get(k)[1];
            if (len < 0) {
                ids.add(UNK_ID);
            } else {
                String piece = norm.substring(start, start + len);
                Integer id = pieceId.get(piece);
                ids.add(id != null ? id : UNK_ID);
            }
        }
        int[] out = new int[ids.size()];
        for (int k = 0; k < out.length; k++) out[k] = ids.get(k);
        return out;
    }

    /** Model ids -> text: join pieces, ▁ back to space, specials dropped. */
    public String decode(int[] ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            if (id < 0 || id >= idPiece.size()) continue;
            String p = idPiece.get(id);
            if (p.equals("</s>") || p.equals("<s>") || p.equals("<pad>")) continue;
            if (p.equals("<unk>")) continue;
            sb.append(p);
        }
        return sb.toString().replace("▁", " ").trim();
    }

    public int vocabSize() { return idPiece.size(); }
}
