package capture.screen;

import java.nio.ByteBuffer;

/**
 * JNI wrapper around the DXGI Desktop Duplication API.
 *
 * <h3>What it does</h3>
 * <ol>
 *   <li>Opens a zero-copy GPU handle to the desktop of the chosen monitor.</li>
 *   <li>On each {@link #captureFrame()} call: acquires the latest OS-composed
 *       frame, runs a D3D11 bilinear-scale pass on the GPU to the requested
 *       destination size, reads back the smaller RGBA buffer via a staging
 *       texture, and writes it into a pre-allocated {@link ByteBuffer}.</li>
 * </ol>
 *
 * <h3>Performance characteristics</h3>
 * <ul>
 *   <li>Cutting {@code dstW}/{@code dstH} in half reduces PCI-E readback and
 *       the subsequent {@code glTexSubImage2D} upload by 4x.</li>
 *   <li>{@link #captureFrame()} is non-blocking: it returns {@code false}
 *       immediately when no new frame has been composed by the OS since the
 *       last call, so the render loop can keep its cadence.</li>
 *   <li>The {@link ByteBuffer} is allocated once (direct/off-heap) and reused
 *       every frame, so there is no GC pressure.</li>
 * </ul>
 *
 * <h3>Requirements</h3>
 * <ul>
 *   <li>Windows 8+ (DXGI 1.2). Works on Windows 10/11.</li>
 *   <li>{@code ScreenDuplicator.dll} must be on {@code java.library.path}
 *       (simplest: place it next to the JAR or in the {@code assets/} folder).</li>
 *   <li>Must not be run from a UAC-elevated process when capturing a
 *       non-elevated desktop.</li>
 * </ul>
 *
 * <h3>Package note</h3>
 * This class intentionally remains in {@code com.example.capture} to match
 * the JNI symbol names compiled into {@code ScreenDuplicator.dll}:
 * {@code Java_com_example_capture_ScreenDuplicator_nativeInit}, etc.
 * Moving it to another package would break the native linkage.
 *
 * <h3>DLL availability</h3>
 * The static initializer catches {@link UnsatisfiedLinkError} so the class
 * loads cleanly even when the DLL is absent.
 * Check {@link #isDllAvailable()} before constructing an instance.
 *
 * <h3>Thread safety</h3>
 * All calls ({@link #captureFrame()}, {@link #dispose()}) must come from the
 * same thread that constructed this object - the libGDX GL/render thread.
 */
public final class ScreenDuplicator {

    // -----------------------------------------------------------------------
    // DLL availability guard
    // -----------------------------------------------------------------------

    /** True when ScreenDuplicator.dll was found and loaded successfully. */
    private static final boolean DLL_AVAILABLE;

    static {
        boolean ok;
        try {
            System.loadLibrary("ScreenDuplicator");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[ScreenDuplicator] ScreenDuplicator.dll not found - "
                + "screen capture will be unavailable. "
                + "Place the DLL in assets/ or on java.library.path.");
            ok = false;
        }
        DLL_AVAILABLE = ok;
    }

    /** Returns true if the native DLL loaded successfully. */
    public static boolean isDllAvailable() { return DLL_AVAILABLE; }

    // -----------------------------------------------------------------------
    // Native methods - names must match symbols in ScreenDuplicator.dll
    // -----------------------------------------------------------------------

    /**
     * Initialises DXGI Desktop Duplication and the D3D11 scaling pipeline.
     * @return opaque context pointer, or 0 on failure
     */
    private static native long nativeInit(int monitorIndex, int dstW, int dstH);

    /**
     * Acquires the latest desktop frame (non-blocking), GPU-scales it to
     * dstW x dstH, and writes RGBA bytes into buf.
     * @return true if a new frame was written; false if the desktop has not
     *         changed since the last call
     */
    private static native boolean nativeCaptureFrame(long handle, ByteBuffer buf);

    /** Releases all native COM resources and frees the context pointer. */
    private static native void nativeDestroy(long handle);

    // -----------------------------------------------------------------------
    // Instance state
    // -----------------------------------------------------------------------

    private final long       handle;
    private final ByteBuffer pixelBuffer; // direct (off-heap) - JNI writes into this

    /** Destination width passed to the constructor. */
    public final int width;

    /** Destination height passed to the constructor. */
    public final int height;

    // -----------------------------------------------------------------------
    // Construction / disposal
    // -----------------------------------------------------------------------

    /**
     * Creates a {@code ScreenDuplicator} targeting the given monitor.
     *
     * @param monitorIndex 0-based monitor index (0 = primary, 1 = secondary)
     * @param dstW         destination width - lower than source for better performance
     * @param dstH         destination height - same advice
     * @throws RuntimeException if the DLL is missing or native init fails
     */
    public ScreenDuplicator(int monitorIndex, int dstW, int dstH) {
        if (!DLL_AVAILABLE)
            throw new RuntimeException(
                "ScreenDuplicator.dll not available - cannot create ScreenDuplicator");
        this.width  = dstW;
        this.height = dstH;
        this.handle = nativeInit(monitorIndex, dstW, dstH);
        if (handle == 0L)
            throw new RuntimeException(
                "DXGI Desktop Duplication init failed for monitor " + monitorIndex
                + " at " + dstW + "x" + dstH);
        // One-time allocation: 4 bytes (RGBA) per pixel
        this.pixelBuffer = ByteBuffer.allocateDirect(dstW * dstH * 4);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Attempts to capture the latest desktop frame (non-blocking).
     *
     * <p>When the OS has not composed a new frame since the last call,
     * returns {@code false} immediately and {@link #getPixelBuffer()} retains
     * the previous contents - ideal for skipping redundant GPU uploads.
     *
     * @return true if {@link #getPixelBuffer()} contains fresh RGBA data
     */
    public boolean captureFrame() {
        pixelBuffer.clear();
        return nativeCaptureFrame(handle, pixelBuffer);
    }

    /**
     * Returns the direct {@link ByteBuffer} populated by the last successful
     * {@link #captureFrame()} call.
     *
     * <p>Layout: row-major, top-to-bottom (already GL-compatible), 4 bytes per
     * pixel in RGBA order.
     */
    public ByteBuffer getPixelBuffer() { return pixelBuffer; }

    /**
     * Releases all native D3D11/DXGI resources.
     * Must be called from the same thread that constructed this object.
     * Safe to call multiple times.
     */
    public void dispose() { nativeDestroy(handle); }
}
