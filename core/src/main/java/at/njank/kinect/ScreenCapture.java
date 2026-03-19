package at.njank.kinect;

import com.example.capture.ScreenDuplicator;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

/**
 * Shared screen-capture manager used by {@link ScreenVisualizer} and
 * {@link ARVisualizer}.
 *
 * <p>Owns one {@link ScreenDuplicator} and one GPU {@link Texture} that are
 * kept alive across both visualizers so only a single DXGI duplication session
 * and a single GPU texture allocation exist at any time.
 *
 * <h3>Monitor switching</h3>
 * Call {@link #nextMonitor()} to cycle through monitors 0, 1, 2, ... When the
 * requested index has no physical output, {@link ScreenDuplicator} construction
 * throws and the index wraps back to 0.
 *
 * <h3>Graceful degradation</h3>
 * If {@code ScreenDuplicator.dll} is absent, all methods are no-ops and
 * {@link #getTexture()} returns {@code null}. Callers must null-check before
 * using the texture.
 *
 * <h3>Usage</h3>
 * Create once in {@link Main#create()}.  Call {@link #update()} once per frame
 * from any visualizer that needs the texture (redundant calls within the same
 * frame are cheap because {@link ScreenDuplicator#captureFrame()} is
 * non-blocking).
 */
public class ScreenCapture {

    // -----------------------------------------------------------------------
    // Capture resolution
    // Resolution / performance guide:
    //   1920x1080 - pixel-perfect, highest upload cost
    //    960x540  - standard quality preview, 4x cheaper than 1080p
    //    640x360  - lightweight monitoring
    // -----------------------------------------------------------------------

    /** Width of the captured/uploaded texture. Lower = faster GPU upload. */
    public static final int CAP_W = 960;

    /** Height of the captured/uploaded texture. */
    public static final int CAP_H = 540;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private ScreenDuplicator duplicator;
    private Texture          captureTexture;
    private int              monitorIndex = 0;

    /** True once {@link #init()} has succeeded at least once. */
    private boolean ready = false;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Initialises the DXGI duplicator for the current {@link #monitorIndex}
     * and allocates the GPU texture.
     *
     * <p>Must be called on the GL thread (inside a visualizer's
     * {@code create()} or from {@link Main#create()}).
     * Safe to call multiple times - disposes the old duplicator first.
     */
    public void init() {
        dispose();
        if (!ScreenDuplicator.isDllAvailable()) {
            Gdx.app.log("ScreenCapture",
                "ScreenDuplicator.dll not available - screen capture disabled.");
            return;
        }
        try {
            duplicator     = new ScreenDuplicator(monitorIndex, CAP_W, CAP_H);
            captureTexture = new Texture(CAP_W, CAP_H, Pixmap.Format.RGBA8888);
            captureTexture.setFilter(
                Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            ready = true;
            Gdx.app.log("ScreenCapture",
                "Opened monitor " + monitorIndex + " at " + CAP_W + "x" + CAP_H);
        } catch (RuntimeException e) {
            Gdx.app.error("ScreenCapture", "Failed to open monitor " + monitorIndex
                + ": " + e.getMessage());
            ready = false;
        }
    }

    /**
     * Cycles to the next monitor index and reinitialises.
     * If the next index has no physical output, wraps back to 0.
     */
    public void nextMonitor() {
        int next = monitorIndex + 1;
        monitorIndex = next;
        init();
        // If the higher-index monitor does not exist, fall back to primary
        if (!ready) {
            monitorIndex = 0;
            init();
        }
    }

    /**
     * Captures a new desktop frame (non-blocking) and uploads it to the GPU
     * texture via {@code glTexSubImage2D} when a new frame is available.
     *
     * <p>Call once per render frame from the active visualizer. Redundant
     * calls within the same frame are cheap: DXGI returns {@code false}
     * immediately when the desktop has not changed.
     */
    public void update() {
        if (!ready) return;
        if (duplicator.captureFrame()) {
            // Upload fresh pixels without reallocating the texture object
            captureTexture.bind();
            Gdx.gl.glTexSubImage2D(
                GL20.GL_TEXTURE_2D,
                0,               // mip level 0
                0, 0,            // x / y offset
                CAP_W, CAP_H,
                GL20.GL_RGBA,
                GL20.GL_UNSIGNED_BYTE,
                duplicator.getPixelBuffer());
        }
    }

    /**
     * Returns the GPU texture containing the last captured frame, or
     * {@code null} if the DLL is unavailable or init failed.
     */
    public Texture getTexture() { return captureTexture; }

    /** Returns the current monitor index (0 = primary). */
    public int getMonitorIndex() { return monitorIndex; }

    /** Returns true if the duplicator is ready and the texture is valid. */
    public boolean isReady() { return ready; }

    /** Releases all GPU and native resources. Safe to call multiple times. */
    public void dispose() {
        ready = false;
        if (duplicator     != null) { duplicator.dispose();     duplicator     = null; }
        if (captureTexture != null) { captureTexture.dispose(); captureTexture = null; }
    }
}
