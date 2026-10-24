package com.talacore.talatv;

/**
 * A small QR encoder (v0.7.0) — just enough of ISO/IEC 18004 to
 * put the watching-page address on screen as a scannable code:
 * byte mode, error-correction level M, versions 1–10, all eight
 * masks tried and scored by the spec's own penalty rules.
 *
 * It exists because the page-share address is long to type on a
 * TV remote; a TV/phone camera that can scan it saves the
 * typing. The encoder is pure (no Android), and it does not
 * ship on faith: the build harness renders its matrices and a
 * real decoder (ZXing) must read them back byte-for-byte
 * before the code is trusted — see work/v070. If a payload is
 * too long for version 10-M, encode() refuses (returns null)
 * and the caller falls back to the big printed address.
 */
public final class QrCode {

    private QrCode() {}

    // EC level M block table, versions 1..10:
    // {total codewords, ec per block, g1 blocks, g1 data cw,
    //  g2 blocks, g2 data cw}
    private static final int[][] ECC_M = {
        {26, 10, 1, 16, 0, 0},
        {44, 16, 1, 28, 0, 0},
        {70, 26, 1, 44, 0, 0},
        {100, 18, 2, 32, 0, 0},
        {134, 24, 2, 43, 0, 0},
        {172, 16, 4, 27, 0, 0},
        {196, 18, 4, 31, 0, 0},
        {242, 22, 2, 38, 2, 39},
        {292, 22, 3, 36, 2, 37},
        {346, 26, 4, 43, 1, 44},
    };

    private static final int[][] ALIGN = {
        {},
        {6, 18},
        {6, 22},
        {6, 26},
        {6, 30},
        {6, 34},
        {6, 22, 38},
        {6, 24, 42},
        {6, 26, 46},
        {6, 28, 50},
    };

    /**
     * Encode text (UTF-8 bytes) into a module matrix
     * ([row][col], true = dark), WITHOUT quiet zone. Returns
     * null when the payload fits no version ≤ 10 at level M.
     */
    public static boolean[][] encode(String text) {
        byte[] data;
        try {
            data = text.getBytes("UTF-8");
        } catch (Exception e) {
            return null;
        }
        for (int version = 1; version <= 10; version++) {
            int[] t = ECC_M[version - 1];
            int dataCw = t[2] * t[3] + t[4] * t[5];
            int lenBits = version == 10 ? 16 : 8;
            int needed = 4 + lenBits + data.length * 8;
            if (needed > dataCw * 8) continue;
            byte[] codewords = buildCodewords(data, version, dataCw, lenBits);
            byte[] interleaved = interleaveWithEcc(codewords, version);
            return drawMatrix(interleaved, version);
        }
        return null;
    }

    private static byte[] buildCodewords(byte[] data, int version,
                                         int dataCw, int lenBits) {
        StringBuilder bits = new StringBuilder();
        bits.append("0100"); // byte mode
        String len = Integer.toBinaryString(data.length);
        for (int i = len.length(); i < lenBits; i++) bits.append('0');
        bits.append(len);
        for (byte b : data) {
            String s = Integer.toBinaryString(b & 0xff);
            for (int i = s.length(); i < 8; i++) bits.append('0');
            bits.append(s);
        }
        int cap = dataCw * 8;
        for (int i = 0; i < 4 && bits.length() < cap; i++) bits.append('0');
        while (bits.length() % 8 != 0) bits.append('0');
        byte[] out = new byte[dataCw];
        int n = bits.length() / 8;
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        }
        byte[] pads = {(byte) 0xEC, (byte) 0x11};
        for (int i = n, p = 0; i < dataCw; i++, p++) out[i] = pads[p % 2];
        return out;
    }

    // ------------------------------------------------ reed-solomon

    private static final int[] GF_EXP = new int[512];
    private static final int[] GF_LOG = new int[256];
    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            GF_EXP[i] = x;
            GF_LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255];
    }

    private static int gfMul(int a, int b) {
        return (a == 0 || b == 0) ? 0 : GF_EXP[GF_LOG[a] + GF_LOG[b]];
    }

    /** Generator polynomial of the given degree (coefficients). */
    private static int[] rsGenerator(int degree) {
        int[] poly = {1};
        for (int i = 0; i < degree; i++) {
            int[] next = new int[poly.length + 1];
            for (int j = 0; j < poly.length; j++) {
                next[j] ^= gfMul(poly[j], GF_EXP[i]);
                next[j + 1] ^= poly[j];
            }
            poly = next;
        }
        return poly;
    }

    /** The error-correction codewords for one data block. */
    private static byte[] rsRemainder(byte[] data, int ecLen) {
        // gen is ASCENDING (gen[k] = coefficient of x^k, gen[ecLen]
        // the leading 1); the shift register walks DESCENDING
        // degrees, so position i takes gen[ecLen - 1 - i].
        int[] gen = rsGenerator(ecLen);
        int[] res = new int[ecLen];
        for (byte b : data) {
            int factor = (b & 0xff) ^ res[0];
            System.arraycopy(res, 1, res, 0, ecLen - 1);
            res[ecLen - 1] = 0;
            for (int i = 0; i < ecLen; i++) {
                res[i] ^= gfMul(gen[ecLen - 1 - i], factor);
            }
        }
        byte[] out = new byte[ecLen];
        for (int i = 0; i < ecLen; i++) out[i] = (byte) res[i];
        return out;
    }

    private static byte[] interleaveWithEcc(byte[] data, int version) {
        int[] t = ECC_M[version - 1];
        int total = t[0], ecLen = t[1];
        byte[][] blocks = new byte[t[2] + t[4]][];
        byte[][] eccs = new byte[blocks.length][];
        int pos = 0;
        for (int i = 0; i < blocks.length; i++) {
            int n = i < t[2] ? t[3] : t[5];
            blocks[i] = new byte[n];
            System.arraycopy(data, pos, blocks[i], 0, n);
            pos += n;
            eccs[i] = rsRemainder(blocks[i], ecLen);
        }
        byte[] out = new byte[total];
        int maxData = Math.max(t[3], t[5]);
        int o = 0;
        for (int i = 0; i < maxData; i++) {
            for (byte[] blk : blocks) if (i < blk.length) out[o++] = blk[i];
        }
        for (int i = 0; i < ecLen; i++) {
            for (byte[] ecc : eccs) out[o++] = ecc[i];
        }
        return out;
    }

    // ------------------------------------------------ matrix

    private static boolean[][] drawMatrix(byte[] codewords, int version) {
        int size = version * 4 + 17;
        boolean[][] dark = new boolean[size][size];
        boolean[][] fn = new boolean[size][size]; // function modules

        // timing first (alignment/finders overwrite where they sit)
        for (int i = 0; i < size; i++) {
            setFn(dark, fn, 6, i, i % 2 == 0);
            setFn(dark, fn, i, 6, i % 2 == 0);
        }
        drawFinder(dark, fn, 3, 3);
        drawFinder(dark, fn, size - 4, 3);
        drawFinder(dark, fn, 3, size - 4);
        int[] centers = ALIGN[version - 1];
        if (centers.length >= 2) {
            int first = centers[0], last = centers[centers.length - 1];
            for (int r : centers) {
                for (int c : centers) {
                    // skip only the three finder corners — centers
                    // on the timing lines are still drawn (they
                    // overwrite the timing where they sit)
                    if ((r == first && c == first)
                            || (r == first && c == last)
                            || (r == last && c == first)) continue;
                    drawAlignment(dark, fn, r, c);
                }
            }
        }
        // reserve format areas + the dark module
        drawFormatPlaceholders(dark, fn, size);
        if (version >= 7) drawVersionPlaceholders(dark, fn, size);
        dark[size - 8][8] = true;
        fn[size - 8][8] = true;

        // place data bits, zigzag from the bottom right
        int bits = codewords.length * 8;
        int idx = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int col = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int row = upward ? size - 1 - vert : vert;
                    if (fn[row][col]) continue;
                    boolean bit = false;
                    if (idx < bits) {
                        bit = ((codewords[idx >>> 3] >>> (7 - (idx & 7))) & 1) != 0;
                        idx++;
                    }
                    dark[row][col] = bit;
                }
            }
        }

        // try all eight masks on the data area; keep the best score
        boolean[][] best = null;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] m = new boolean[size][size];
            for (int r = 0; r < size; r++) {
                for (int c = 0; c < size; c++) {
                    boolean v = dark[r][c];
                    if (!fn[r][c] && maskBit(mask, r, c)) v = !v;
                    m[r][c] = v;
                }
            }
            drawFormatBits(m, size, mask);
            if (version >= 7) drawVersionBits(m, size, version);
            int pen = penalty(m, size);
            if (pen < bestPenalty) {
                bestPenalty = pen;
                best = m;
            }
        }
        return best;
    }

    private static void setFn(boolean[][] dark, boolean[][] fn,
                              int r, int c, boolean v) {
        dark[r][c] = v;
        fn[r][c] = true;
    }

    private static void drawFinder(boolean[][] dark, boolean[][] fn,
                                   int cx, int cy) {
        for (int dr = -4; dr <= 4; dr++) {
            for (int dc = -4; dc <= 4; dc++) {
                int r = cy + dr, c = cx + dc;
                if (r < 0 || r >= dark.length || c < 0 || c >= dark.length) continue;
                int dist = Math.max(Math.abs(dr), Math.abs(dc));
                setFn(dark, fn, r, c, dist != 2 && dist != 4);
            }
        }
    }

    private static void drawAlignment(boolean[][] dark, boolean[][] fn,
                                      int cr, int cc) {
        for (int dr = -2; dr <= 2; dr++) {
            for (int dc = -2; dc <= 2; dc++) {
                setFn(dark, fn, cr + dr, cc + dc,
                        Math.max(Math.abs(dr), Math.abs(dc)) != 1);
            }
        }
    }

    private static void drawFormatPlaceholders(boolean[][] dark,
                                               boolean[][] fn, int size) {
        for (int i = 0; i <= 8; i++) {
            if (i != 6) { fn[8][i] = true; fn[i][8] = true; }
        }
        for (int i = 0; i < 8; i++) {
            fn[8][size - 1 - i] = true;
            fn[size - 1 - i][8] = true;
        }
    }

    private static void drawVersionPlaceholders(boolean[][] dark,
                                                boolean[][] fn, int size) {
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 3; c++) {
                fn[r][size - 11 + c] = true;
                fn[size - 11 + c][r] = true;
            }
        }
    }

    private static boolean maskBit(int mask, int r, int c) {
        switch (mask) {
            case 0: return (r + c) % 2 == 0;
            case 1: return r % 2 == 0;
            case 2: return c % 3 == 0;
            case 3: return (r + c) % 3 == 0;
            case 4: return (r / 2 + c / 3) % 2 == 0;
            case 5: return (r * c) % 2 + (r * c) % 3 == 0;
            case 6: return ((r * c) % 2 + (r * c) % 3) % 2 == 0;
            case 7: return ((r + c) % 2 + (r * c) % 3) % 2 == 0;
            default: return false;
        }
    }

    /** Format information for EC level M (indicator bits 00). */
    private static void drawFormatBits(boolean[][] m, int size, int mask) {
        int data = mask; // ecBits(M)=0b00 -> data = 0b00 << 3 | mask
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ (((rem >>> 9) & 1) != 0 ? 0x537 : 0);
        }
        int bits = ((data << 10) | rem) ^ 0x5412;
        // first copy (around the top-left finder): bits 0..8 run
        // up column 8 (rows 0..5, 7, 8), bits 9..14 run left
        // along row 8 (cols 5..0)
        for (int i = 0; i <= 5; i++) m[i][8] = bit(bits, i);
        m[7][8] = bit(bits, 6);
        m[8][8] = bit(bits, 7);
        m[8][7] = bit(bits, 8);
        for (int i = 9; i < 15; i++) m[8][14 - i] = bit(bits, i);
        // second copy (split along the other finders)
        for (int i = 0; i < 8; i++) m[8][size - 1 - i] = bit(bits, i);
        for (int i = 8; i < 15; i++) m[size - 15 + i][8] = bit(bits, i);
        m[size - 8][8] = true; // the dark module
    }

    private static boolean bit(int v, int i) { return ((v >>> i) & 1) != 0; }

    private static void drawVersionBits(boolean[][] m, int size, int version) {
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ (((rem >>> 11) & 1) != 0 ? 0x1F25 : 0);
        }
        int bits = (version << 12) | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = bit(bits, i);
            m[i / 3][size - 11 + i % 3] = b;
            m[size - 11 + i % 3][i / 3] = b;
        }
    }

    // ------------------------------------------------ penalties

    private static int penalty(boolean[][] m, int size) {
        int pen = 0;
        // N1: long runs in rows and columns
        for (int r = 0; r < size; r++) {
            pen += runPenalty(m[r][0], m, size, true, r);
        }
        for (int c = 0; c < size; c++) {
            pen += runPenalty(m[0][c], m, size, false, c);
        }
        // N2: same-color 2x2 blocks
        for (int r = 0; r < size - 1; r++) {
            for (int c = 0; c < size - 1; c++) {
                if (m[r][c] == m[r][c + 1] && m[r][c] == m[r + 1][c]
                        && m[r][c] == m[r + 1][c + 1]) pen += 3;
            }
        }
        // N3: finder-like patterns with a four-module light guard
        for (int r = 0; r < size; r++) {
            for (int c = 0; c + 11 <= size; c++) {
                if (patternAt(m, r, c, true)) pen += 40;
            }
        }
        for (int c = 0; c < size; c++) {
            for (int r = 0; r + 11 <= size; r++) {
                if (patternAt(m, r, c, false)) pen += 40;
            }
        }
        // N4: overall dark ratio
        int darkCount = 0;
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) if (m[r][c]) darkCount++;
        }
        double pct = darkCount * 100.0 / (size * size);
        int lo = (int) Math.floor(pct / 5) * 5;
        int hi = lo + 5;
        pen += Math.min(Math.abs(lo - 50), Math.abs(hi - 50)) / 5 * 10;
        return pen;
    }

    private static int runPenalty(boolean first, boolean[][] m, int size,
                                  boolean rowWise, int fixed) {
        int pen = 0;
        boolean cur = first;
        int run = 1;
        for (int i = 1; i < size; i++) {
            boolean v = rowWise ? m[fixed][i] : m[i][fixed];
            if (v == cur) {
                run++;
            } else {
                if (run >= 5) pen += 3 + (run - 5);
                cur = v;
                run = 1;
            }
        }
        if (run >= 5) pen += 3 + (run - 5);
        return pen;
    }

    private static boolean patternAt(boolean[][] m, int r, int c,
                                     boolean rowWise) {
        // 1011101 preceded or followed by four light modules
        int[] pat = {1, 0, 1, 1, 1, 0, 1};
        boolean core = true;
        for (int i = 0; i < 7; i++) {
            boolean v = rowWise ? m[r][c + i] : m[r + i][c];
            if (v != (pat[i] == 1)) { core = false; break; }
        }
        if (!core) return false;
        boolean guardBefore = true, guardAfter = true;
        boolean hasBefore = false, hasAfter = false;
        for (int i = 1; i <= 4; i++) {
            int before = (rowWise ? c : r) - i;
            if (before < 0) break;
            hasBefore = true;
            boolean v = rowWise ? m[r][before] : m[before][c];
            if (v) guardBefore = false;
        }
        int size = m.length;
        for (int i = 7; i < 11; i++) {
            int after = (rowWise ? c : r) + i;
            if (after >= size) break;
            hasAfter = true;
            boolean v = rowWise ? m[r][after] : m[after][c];
            if (v) guardAfter = false;
        }
        return (hasBefore && guardBefore) || (hasAfter && guardAfter);
    }
}
