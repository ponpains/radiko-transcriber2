package com.example.radikotranscriber;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Stateful time-stretcher for 1.5x/2x captured speech.
 * The previous hand-written overlap-add restarted every ~0.5 s and created audible seams. Sonic
 * keeps its analysis state across chunks, slows speech toward 1x while preserving pitch, and lets
 * the existing spool absorb the extra recognition time.
 */
@UnstableApi
public final class HighSpeedSonicProcessor {
    private static final int SAMPLE_RATE = 16000;
    private final SonicAudioProcessor sonic;
    private boolean finished;

    public HighSpeedSonicProcessor(float capturedSpeed) throws AudioProcessor.UnhandledAudioFormatException {
        float factor = capturedSpeed >= 1.9f ? 2.0f : capturedSpeed >= 1.4f ? 1.5f : 1.0f;
        sonic = new SonicAudioProcessor();
        sonic.setSpeed(1.0f / factor);
        sonic.setPitch(1.0f);
        sonic.setOutputSampleRateHz(SAMPLE_RATE);
        sonic.configure(new AudioProcessor.AudioFormat(SAMPLE_RATE, 1, C.ENCODING_PCM_16BIT));
        sonic.flush();
    }

    public synchronized byte[] process(short[] input, int n) {
        if (finished || input == null || n <= 0) return new byte[0];
        ByteBuffer in = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < n; i++) in.putShort(input[i]);
        in.flip();

        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(n * 2, 4096));
        int guard = 0;
        while (in.hasRemaining() && guard++ < 64) {
            int before = in.position();
            sonic.queueInput(in);
            drain(out);
            if (in.position() == before) {
                // Sonic normally consumes the full input. Avoid a busy loop if a future version
                // temporarily back-pressures; the next call will still contain continuous audio in
                // the spool, so returning currently available output is safer than hanging capture.
                break;
            }
        }
        drain(out);
        return out.toByteArray();
    }

    public synchronized byte[] finish() {
        if (finished) return new byte[0];
        finished = true;
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        sonic.queueEndOfStream();
        int guard = 0;
        while (!sonic.isEnded() && guard++ < 256) {
            int before = out.size();
            drain(out);
            if (out.size() == before && !sonic.isEnded()) Thread.yield();
        }
        drain(out);
        return out.toByteArray();
    }

    public synchronized void reset() {
        try { sonic.reset(); } catch (Exception ignored) {}
        finished = true;
    }

    private void drain(ByteArrayOutputStream out) {
        int guard = 0;
        while (guard++ < 64) {
            ByteBuffer b = sonic.getOutput();
            if (b == null || !b.hasRemaining()) break;
            byte[] bytes = new byte[b.remaining()];
            b.get(bytes);
            out.write(bytes, 0, bytes.length);
        }
    }
}
