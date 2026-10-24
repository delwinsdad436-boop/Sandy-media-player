package com.talacore.talatv;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decode a media file's audio track to PCM for the cooker
 * (v0.6.1). MediaExtractor + MediaCodec decode to a temp WAV in
 * the cache dir (native rate/channels preserved), and WavPcm then
 * serves the cooker's 16 kHz mono reads from it — the same
 * PcmSource the desktop JVM proof uses, so the engine sees no
 * difference between a bench WAV and a decoded film.
 *
 * COMPILE-VERIFIED ONLY in the build environment: no Android
 * device or emulator exists there, so this class has never run.
 * It is written to the standard MediaCodec dequeue pattern and
 * fails loudly (exception up to the Translate gate, which speaks
 * it as the FAILED state) rather than returning partial audio
 * silently.
 */
public final class AudioPcmDecoder {

    private AudioPcmDecoder() {}

    /**
     * Decode the first audio track of {@code url} (file path or
     * http(s) URL MediaExtractor accepts) into {@code tmpDir} and
     * return a PcmSource over the decoded WAV.
     */
    public static PcmSource decode(Context ctx, String url, File tmpDir)
            throws Exception {
        MediaExtractor ex = new MediaExtractor();
        if (url.startsWith("content://")) {
            ex.setDataSource(ctx, android.net.Uri.parse(url), null);
        } else if (url.startsWith("file://")) {
            ex.setDataSource(android.net.Uri.parse(url).getPath());
        } else {
            ex.setDataSource(url);
        }
        int track = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                track = i;
                fmt = f;
                break;
            }
        }
        if (track < 0 || fmt == null) {
            ex.release();
            throw new IllegalStateException("no audio track in " + url);
        }
        ex.selectTrack(track);
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(fmt, null, null, 0);
        codec.start();
        File out = new File(tmpDir, "cook-" + Math.abs(url.hashCode()) + ".wav");
        long pcmBytes = 0;
        MediaFormat lastOutFormat = fmt;
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(new byte[44]); // header placeholder, rewritten at end
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaFormat outFmt = fmt;
            boolean inputDone = false, outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(inIdx);
                        int sz = ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz,
                                    ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFmt = codec.getOutputFormat();
                    lastOutFormat = outFmt;
                } else if (outIdx >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (info.size > 0 && ob != null) {
                        // decoder output may be PCM16 or (API 29+
                        // float) — normalize to PCM16 for the WAV
                        MediaFormat of = codec.getOutputFormat();
                        int enc = of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                ? of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                : android.media.AudioFormat.ENCODING_PCM_16BIT;
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        if (enc == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                            byte[] tmp = new byte[info.size / 2];
                            ByteBuffer src = ob.duplicate().order(ByteOrder.nativeOrder());
                            ByteBuffer dst = ByteBuffer.wrap(tmp)
                                    .order(ByteOrder.LITTLE_ENDIAN);
                            int n = info.size / 4;
                            for (int i = 0; i < n; i++) {
                                float v = src.getFloat(i * 4);
                                if (v > 1f) v = 1f;
                                if (v < -1f) v = -1f;
                                dst.putShort(i * 2, (short) (v * 32767));
                            }
                            fos.write(tmp);
                            pcmBytes += tmp.length;
                        } else {
                            byte[] tmp = new byte[info.size];
                            ob.get(tmp);
                            fos.write(tmp);
                            pcmBytes += tmp.length;
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
        } finally {
            try { codec.stop(); } catch (Exception ignored) {}
            codec.release();
            ex.release();
        }
        // rewrite the WAV header with the decoder's actual output
        // format (rate/channels can change at INFO_OUTPUT_FORMAT_CHANGED)
        MediaFormat f2 = lastOutFormat;
        int rate = f2.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? f2.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int chans = f2.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? f2.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        writeHeader(out, rate, chans, pcmBytes);
        return new WavPcm(out);
    }

    private static void writeHeader(File out, int rate, int chans, long pcmBytes)
            throws Exception {
        byte[] h = new byte[44];
        ByteBuffer bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(0, (byte) 'R'); bb.put(1, (byte) 'I');
        bb.put(2, (byte) 'F'); bb.put(3, (byte) 'F');
        bb.putInt(4, (int) (36 + pcmBytes));
        bb.put(8, (byte) 'W'); bb.put(9, (byte) 'A');
        bb.put(10, (byte) 'V'); bb.put(11, (byte) 'E');
        bb.put(12, (byte) 'f'); bb.put(13, (byte) 'm');
        bb.put(14, (byte) 't'); bb.put(15, (byte) ' ');
        bb.putInt(16, 16);
        bb.putShort(20, (short) 1);
        bb.putShort(22, (short) chans);
        bb.putInt(24, rate);
        bb.putInt(28, rate * chans * 2);
        bb.putShort(32, (short) (chans * 2));
        bb.putShort(34, (short) 16);
        bb.put(36, (byte) 'd'); bb.put(37, (byte) 'a');
        bb.put(38, (byte) 't'); bb.put(39, (byte) 'a');
        bb.putInt(40, (int) pcmBytes);
        try (java.io.RandomAccessFile raf =
                     new java.io.RandomAccessFile(out, "rw")) {
            raf.seek(0);
            raf.write(h);
        }
    }
}
