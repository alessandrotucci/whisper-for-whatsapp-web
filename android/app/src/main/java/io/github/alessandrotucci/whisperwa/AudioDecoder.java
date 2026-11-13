package io.github.alessandrotucci.whisperwa;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Decodifica un file audio (i vocali di WhatsApp sono Ogg Opus) con MediaExtractor e
 * MediaCodec, e lo converte nel formato di whisper: float mono a 16 kHz.
 */
public final class AudioDecoder {

    public static final int RATE = 16000;
    private static final long TIMEOUT_US = 10_000;

    private AudioDecoder() {
    }

    public static float[] decode(Context context, Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("no audio track");
            extractor.selectTrack(track);

            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            FloatSink sink = new FloatSink();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            while (!outputDone) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int n = extractor.readSampleData(buf, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, n, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (out >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(out);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset).limit(info.offset + info.size);
                        sink.addPcm16(buf.slice().order(ByteOrder.nativeOrder()).asShortBuffer(), channels);
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
            return resample(sink.toArray(), rate, RATE);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (IllegalStateException ignored) {
                    // il codec non è mai partito
                }
                codec.release();
            }
            extractor.release();
        }
    }

    /**
     * Ricampionamento con un filtro sinc a finestra di Hann; in sottocampionamento la frequenza
     * di taglio scende sotto la nuova frequenza di Nyquist, così non entra aliasing.
     */
    static float[] resample(float[] in, int from, int to) {
        if (from == to) return in;
        double ratio = (double) from / to;
        int outLen = (int) Math.floor(in.length / ratio);
        float[] out = new float[outLen];
        double cutoff = Math.min(1.0, 1.0 / ratio) * 0.95;
        int half = 16;
        double scale = Math.max(1.0, ratio);
        int taps = (int) Math.ceil(half * scale);
        double[] kernel = new double[2 * taps];
        double kernelFrac = Double.NaN;
        for (int i = 0; i < outLen; i++) {
            double center = i * ratio;
            int base = (int) Math.floor(center);
            double frac = center - base;
            // Il nucleo dipende solo dalla parte frazionaria del centro: con rapporti interi
            // (48 kHz -> 16 kHz) si calcola una volta sola.
            if (frac != kernelFrac) {
                for (int t = 0; t < kernel.length; t++) {
                    double d = (t - taps + 1) - frac;
                    double x = d * cutoff;
                    double sinc = x == 0 ? 1.0 : Math.sin(Math.PI * x) / (Math.PI * x);
                    double w = 0.5 + 0.5 * Math.cos(Math.PI * d / (taps + 1));
                    kernel[t] = sinc * w;
                }
                kernelFrac = frac;
            }
            int first = base - taps + 1;
            double acc = 0;
            double norm = 0;
            for (int t = 0; t < kernel.length; t++) {
                int j = first + t;
                if (j < 0 || j >= in.length) continue;
                acc += in[j] * kernel[t];
                norm += kernel[t];
            }
            out[i] = (float) (norm != 0 ? acc / norm : 0);
        }
        return out;
    }

    /** Accumula campioni mono in float, mediando i canali. */
    private static final class FloatSink {
        private float[] data = new float[RATE * 60];
        private int size;

        void addPcm16(ShortBuffer pcm, int channels) {
            int frames = pcm.remaining() / Math.max(1, channels);
            ensure(size + frames);
            for (int f = 0; f < frames; f++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) sum += pcm.get();
                data[size++] = sum / (32768f * channels);
            }
        }

        private void ensure(int n) {
            if (n <= data.length) return;
            float[] bigger = new float[Math.max(n, data.length * 2)];
            System.arraycopy(data, 0, bigger, 0, size);
            data = bigger;
        }

        float[] toArray() {
            float[] out = new float[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }
}
