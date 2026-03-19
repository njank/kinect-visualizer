package capture.audio;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;

/**
 * JNA wrapper around WasapiLoopback.dll.
 *
 * <p>Captures system audio via WASAPI loopback (what-you-hear) so that
 * the calling class can react to any audio playing on the default
 * playback device - no Kinect microphone required, and no Windows
 * "Stereo Mix" setting needs to be enabled.
 *
 * <h3>Requirements</h3>
 * <ul>
 *   <li>{@code WasapiLoopback.dll} (x64) must be placed next to the
 *       application JAR or somewhere on the system {@code PATH}.</li>
 *   <li>JNA dependency in {@code build.gradle}:
 *       {@code implementation 'net.java.dev.jna:jna:5.14.0'}</li>
 * </ul>
 *
 * <h3>Thread model</h3>
 * A daemon background thread calls {@code wasapi_read()} in a tight loop and
 * writes interleaved float samples into a lock-free ring buffer.
 * The render thread calls {@link #getSamples()} which snapshots the ring
 * buffer into a stable mono array without blocking.
 */
public class WasapiLoopbackCapture {

    // -----------------------------------------------------------------------
    // JNA interface - mirrors WasapiCapture.h exactly
    // -----------------------------------------------------------------------

    interface WasapiLib extends Library {
        WasapiLib INSTANCE = Native.load("WasapiLoopback", WasapiLib.class);

        int    wasapi_open();
        int    wasapi_read(float[] buffer, int maxFrames,
                           IntByReference framesRead,
                           IntByReference sampleRate,
                           IntByReference channels);
        void   wasapi_close();
        String wasapi_last_error();
    }

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /**
     * Size of the ring buffer in stereo frames.
     * Must be large enough to hold at least one full FFT window of audio
     * ({@link FFTAnalyzer} default: 2048 frames).  4096 gives comfortable
     * headroom for jitter in the capture loop.
     */
    public static final int BUFFER_FRAMES = 4096;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private boolean   open       = false;
    private int       sampleRate = 44100;
    private int       channels   = 2;

    /** Ring buffer: interleaved float samples written by the background thread. */
    private final float[] ring     = new float[BUFFER_FRAMES * 2];
    private volatile int  writePos = 0;

    /** Pre-allocated mono snapshot returned by {@link #getSamples()}. */
    private final float[] snapshot = new float[BUFFER_FRAMES];

    private Thread captureThread;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Opens WASAPI loopback on the default playback device and starts
     * a background capture thread.
     *
     * @return {@code true} on success; {@code false} if the DLL is missing
     *         or the device cannot be opened (bars will stay silent).
     */
    public boolean start() {
        int hr = WasapiLib.INSTANCE.wasapi_open();
        if (hr != 0) {
            System.err.println("[WasapiCapture] wasapi_open() failed: "
                + WasapiLib.INSTANCE.wasapi_last_error());
            return false;
        }
        open = true;
        System.out.println("[WasapiCapture] WASAPI loopback opened.");

        captureThread = new Thread(this::captureLoop, "WasapiCapture");
        captureThread.setDaemon(true);
        captureThread.start();
        return true;
    }

    /** Stops the capture thread and closes the WASAPI device. */
    public void stop() {
        open = false;
        WasapiLib.INSTANCE.wasapi_close();
        System.out.println("[WasapiCapture] Closed.");
    }

    /** @return {@code true} while the capture thread is running. */
    public boolean isRunning() { return open; }

    /** @return The sample rate reported by WASAPI (typically 44100 or 48000 Hz). */
    public int getSampleRate() { return sampleRate; }

    // -----------------------------------------------------------------------
    // Sample access - called from the render thread
    // -----------------------------------------------------------------------

    /**
     * Returns a mono snapshot of the most recent {@value #BUFFER_FRAMES} frames,
     * averaged from all channels, normalised to {@code [-1, 1]}.
     *
     * <p>The returned array is reused on every call; callers must not hold
     * references across frames.
     */
    public float[] getSamples() {
        int wp = writePos;
        for (int i = 0; i < BUFFER_FRAMES; i++) {
            int   ri = ((wp + i) % BUFFER_FRAMES) * 2;
            float l  = ring[ri];
            float r  = ring[ri + 1];
            snapshot[i] = (l + r) * 0.5f;
        }
        return snapshot;
    }

    // -----------------------------------------------------------------------
    // Background capture loop
    // -----------------------------------------------------------------------

    private void captureLoop() {
        float[]        buf        = new float[BUFFER_FRAMES * 2];
        IntByReference framesRead = new IntByReference(0);
        IntByReference sr         = new IntByReference(44100);
        IntByReference ch         = new IntByReference(2);

        while (open) {
            int hr = WasapiLib.INSTANCE.wasapi_read(buf, BUFFER_FRAMES,
                                                    framesRead, sr, ch);
            if (hr != 0) {
                System.err.println("[WasapiCapture] read error: "
                    + WasapiLib.INSTANCE.wasapi_last_error());
                // Brief pause before retrying to avoid a spin on error
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                continue;
            }

            sampleRate = sr.getValue();
            channels   = ch.getValue();

            int frames = framesRead.getValue();
            if (frames == 0) {
                // No audio currently playing - yield briefly
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                continue;
            }

            // Write to ring buffer (interleaved stereo; mono devices duplicated)
            for (int f = 0; f < frames; f++) {
                int   srcOff = f * ch.getValue();
                float l      = buf[srcOff];
                float r      = ch.getValue() > 1 ? buf[srcOff + 1] : l;
                int   wi     = (writePos % BUFFER_FRAMES) * 2;
                ring[wi]     = l;
                ring[wi + 1] = r;
                writePos++;
            }
        }
    }
}
