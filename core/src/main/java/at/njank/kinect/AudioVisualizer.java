package at.njank.kinect;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import edu.ufl.digitalworlds.j4k.Skeleton;

import static at.njank.kinect.KinectManager.DEPTH_H;
import static at.njank.kinect.KinectManager.DEPTH_W;

/**
 * Visualizer mode 6 – "Audio".
 *
 * <p>Renders the Kinect v2 depth point-cloud (identical base geometry to
 * {@link DepthVisualizer}) and layers system audio on top.  The audio effect
 * radiates outward in concentric rings from the <em>centre of the depth image</em>:
 * the innermost pixels react to the lowest frequencies (bass/sub-bass); the
 * outermost pixels (edges and corners) react to the highest frequencies (treble).
 *
 * <h3>Audio pipeline</h3>
 * <ol>
 *   <li>{@link WasapiLoopbackCapture} — WASAPI loopback captures whatever is
 *       playing on the default audio device; no Kinect microphone or Windows
 *       "Stereo Mix" setting is needed.  Requires {@code WasapiLoopback.dll}
 *       (x64) next to the application JAR or on the system {@code PATH}.</li>
 *   <li>{@link FFTAnalyzer} — converts the raw PCM ring-buffer snapshot into
 *       {@value #AUDIO_BANDS} smoothed, AGC-normalised frequency bands
 *       ({@code [0..1]}) once per render frame.</li>
 *   <li><b>Screen-centre radial band mapping</b> — each depth pixel is
 *       assigned a frequency band by its normalised radial distance from the
 *       depth image centre {@code (DEPTH_W/2, DEPTH_H/2)}.  The mapping is
 *       aspect-ratio corrected so the rings are circular on screen (not
 *       elliptical).  Band 0 = centre = bass; band {@value #AUDIO_BANDS}-1 =
 *       edge/corners = treble.  The lookup table is pre-computed once in
 *       {@link #create()} and costs O(1) per pixel per frame.</li>
 * </ol>
 *
 * <h3>Visual effects (applied in order per pixel)</h3>
 * <ol>
 *   <li><b>Z displacement</b> — each pixel is pushed toward the viewer by
 *       {@code bandValue^PUSH_CURVE × MAX_PUSH × depthScale} metres.</li>
 *   <li><b>Depth-based impact scaling</b> — near pixels (red) receive the
 *       full effect; far pixels (blue) are barely displaced and tinted,
 *       keeping the background calm.  Controlled by {@link #DEPTH_SCALE_CURVE}.</li>
 *   <li><b>Brightness boost</b> — active frequency rings glow brighter.</li>
 *   <li><b>Spectral hue tint</b> — each ring is tinted by its frequency
 *       colour: bass rings glow warm orange-red, treble rings cool cyan-blue,
 *       blended by the band's current amplitude and {@code depthScale}.</li>
 *   <li><b>White blend</b> — subtle desaturation on the loudest near peaks.</li>
 * </ol>
 *
 * <p>Camera controls are handled by {@link OrbitCamera} (same defaults as
 * {@link DepthVisualizer}): left-drag orbit · right-drag pan · scroll zoom ·
 * R reset.
 */
public class AudioVisualizer implements Visualizer {

    // ═══════════════════════════════════════════════════════════════════════
    //  TUNING CONSTANTS — adjust these to change the audio-visual behaviour
    // ═══════════════════════════════════════════════════════════════════════

    // ── Audio FFT ───────────────────────────────────────────────────────────

    /** FFT window size in samples.  Must be a power of 2.
     *  2048 gives ~21 Hz per bin at 44100 Hz — good bass resolution. */
    private static final int   FFT_SIZE    = 2048;

    /**
     * Number of frequency bands produced by {@link FFTAnalyzer} and mapped
     * onto the depth image as concentric screen-centred rings.
     *
     * <pre>
     *  16 = coarse; wide rings, dramatic transitions between bands
     *  32 = balanced — recommended default
     *  48 = fine; smooth frequency sweep from centre to edge
     *  64 = very fine; subtle ring-to-ring differences
     * </pre>
     *
     * Increasing this beyond half the shorter image dimension (DEPTH_H/2 = 212)
     * provides no extra detail as multiple bands then share the same ring width.
     */
    private static final int   AUDIO_BANDS = 32;

    // ── Z displacement ──────────────────────────────────────────────────────

    /**
     * Maximum depth-axis protrusion in metres when a frequency band reaches
     * full amplitude (band value = 1.0) on a near (red) pixel.
     *
     * <p>The displacement is added <em>toward the viewer</em> (world +Z
     * direction after the standard Kinect Z-negation).  Distant pixels receive
     * proportionally less push (see {@link #DEPTH_SCALE_CURVE}).
     *
     * <pre>
     *  0.3 = subtle shimmer; barely noticeable protrusion
     *  0.8 = visible wave; recommended for casual viewing
     *  1.5 = dramatic spikes; striking on close subjects
     *  3.0 = extreme extrusion; good for abstract/artistic modes
     * </pre>
     */
    private static final float MAX_PUSH    = 1.2f;

    /**
     * Power exponent applied to the normalised band value [0..1] before it is
     * scaled to bar height.  Makes the mapping non-linear so that high-dB
     * signals occupy a much larger portion of the bar than low-dB signals.
     *
     * Formula:  displayHeight = bandValue ^ HEIGHT_CURVE  ×  MAX_HEIGHT
     *
     * What this means visually: a band at value V reaches (V^n × 100) % of
     * MAX_HEIGHT.  The table below shows how much bar height each signal
     * level gets at different curve values:
     *
     *  curve │  V=0.3   V=0.5   V=0.7   V=0.9   V=1.0  │ character
     *  ──────┼────────────────────────────────────────────┼──────────────────────────
     *   1.0  │   30 %    50 %    70 %    90 %   100 %   │ linear — flat response
     *   2.0  │    9 %    25 %    49 %    81 %   100 %   │ quadratic — quiet shrinks
     *   3.0  │    3 %    13 %    34 %    73 %   100 %   │ cubic — only loud is tall
     *   4.0  │    1 %     6 %    24 %    66 %   100 %   │ very punchy transients
     *   5.0  │   <1 %     3 %    17 %    59 %   100 %   │ almost gate-like silence
     *   7.0  │   <1 %     1 %     8 %    48 %   100 %   │ bars mostly flat, peaks spike
     *  10.0  │   <1 %    <1 %     3 %    35 %   100 %   │ extreme — only peaks visible
     *  15.0  │   <1 %    <1 %    <1 %    21 %   100 %   │ near-silence floor, peak drama
     *  20.0  │   <1 %    <1 %    <1 %    12 %   100 %   │ black silence, spikes only
     *  30.0  │   <1 %    <1 %    <1 %     4 %   100 %   │ total silence except clipping
     *
     *  0.5   │   55 %    71 %    84 %    95 %   100 %   │ expands quiet (classical/ambient)
     *
     * Recommended ranges by genre:
     *   Pop / EDM (heavily compressed):  2.0 – 4.0
     *   Rock / Hip-hop:                  3.0 – 6.0
     *   Classical / Jazz (wide dynamics): 1.0 – 2.0
     *   "Only peaks matter" look:        8.0 – 15.0
     *   Extreme spike-only aesthetics:   20.0 – 30.0
     */
    private static final float PUSH_CURVE  = 15.0f;

    // ── Depth-based impact scaling ───────────────────────────────────────────

    /**
     * Power exponent that scales all audio-driven effects by how close a pixel
     * is to the camera.
     *
     * <p>Near pixels (red, small Z) are within reach of the viewer and carry
     * the loudest, most tactile audio energy.  Distant pixels (blue, large Z)
     * are background surface and should react subtly so the scene reads cleanly.
     *
     * <p>The per-pixel scale factor is:
     * <pre>
     *   t          = clamp((z − DEPTH_NEAR) / (DEPTH_FAR − DEPTH_NEAR), 0, 1)
     *   depthScale = (1 − t) ^ DEPTH_SCALE_CURVE
     * </pre>
     * where {@code t = 0} is the nearest measurable surface (pure red) and
     * {@code t = 1} is the far clip distance (pure blue).
     * {@code depthScale} multiplies Z push, brightness boost, spectral tint,
     * and white-blend — so near pixels fully protrude and glow while far pixels
     * barely move.
     *
     * <pre>
     *  0.5 = gentle falloff; background still reacts noticeably
     *  1.0 = linear — depth scale halves at mid-range
     *  2.0 = quadratic — recommended; near surface dominates, background quiet
     *  3.0 = steep; only the foreground (within ~1 m) shows a strong effect
     * </pre>
     */
    private static final float DEPTH_SCALE_CURVE = 2.0f;

    // ── Colour modulation ───────────────────────────────────────────────────

    /**
     * Brightness multiplier applied on top of the base depth colour at full
     * band amplitude on a near pixel.
     *
     * <p>The base depth colour (red→green→blue by distance) is multiplied by
     * {@code (1 + bandValue × COLOR_BOOST × depthScale)}.
     *
     * <pre>
     *  0.0 = no brightness change; purely geometric displacement
     *  0.5 = subtle glow
     *  0.8 = recommended — clearly visible ring brightening
     *  1.5 = strong glow; near pixels nearly white at peak amplitude
     *  2.0 = very bright; good contrast in dark rooms
     * </pre>
     */
    private static final float COLOR_BOOST = 0.8f;

    /**
     * Strength of the spectral hue tint blended on top of the base depth colour.
     *
     * <p>Each frequency ring has an associated spectral colour derived from its
     * {@code radialNorm} position (0 = centre = bass, 1 = edge = treble):
     * <pre>
     *   radialNorm 0.0  → warm orange-red  (1.00, 0.35, 0.00)  bass
     *   radialNorm 0.5  → neutral green    (0.10, 0.90, 0.10)  mids
     *   radialNorm 1.0  → cool cyan-blue   (0.00, 0.60, 1.00)  treble
     * </pre>
     * This tint is mixed into the pixel colour by
     * {@code bandValue × BAND_TINT_STRENGTH × depthScale}, so a pixel
     * simultaneously shows its depth distance (base gradient) <em>and</em>
     * the frequency energy currently acting on it.
     *
     * <pre>
     *  0.0  = no spectral tint; pure depth gradient
     *  0.15 = subtle; faint warm/cool shift on active rings
     *  0.35 = recommended — clearly visible tint without drowning depth colour
     *  0.60 = strong; spectral colour competes with the depth gradient
     *  1.0  = spectral colour fully replaces depth colour at peak amplitude
     * </pre>
     */
    private static final float BAND_TINT_STRENGTH = 0.35f;

    /**
     * Additive desaturation (shift toward white) applied to the loudest near
     * pixels.  Set to {@code 0} for pure brightness-only modulation.
     *
     * <pre>
     *  0.0 = no desaturation (preserves depth colour coding)
     *  0.1 = very subtle whitening on peaks — recommended
     *  0.3 = noticeable wash-out on loud, near regions
     * </pre>
     */
    private static final float WHITE_BLEND = 0.10f;

    // ── Depth colour ramp (same as DepthVisualizer) ──────────────────────────

    /**
     * Nearest depth distance in metres that maps to pure red.
     * Pixels closer than this are clamped to red.
     */
    private static final float DEPTH_NEAR  = 0.5f;

    /**
     * Farthest depth distance in metres that maps to pure blue.
     * Pixels farther than this are clamped to blue.
     */
    private static final float DEPTH_FAR   = 5.0f;

    // ── Point-cloud geometry ─────────────────────────────────────────────────

    private static final int POINT_COUNT       = DEPTH_W * DEPTH_H;
    private static final int FLOATS_PER_VERTEX = 7; // x y z  r g b a

    // ═══════════════════════════════════════════════════════════════════════
    //  GPU resources
    // ═══════════════════════════════════════════════════════════════════════

    private Mesh          pointMesh;
    private float[]       vertices;
    private ShaderProgram cloudShader;

    /** 2-D skeleton overlay, shared with the other 3-D modes. */
    private SkeletonVisualizer2D skeletonOverlay;

    // ═══════════════════════════════════════════════════════════════════════
    //  Camera  (same defaults as DepthVisualizer / ARVisualizer)
    // ═══════════════════════════════════════════════════════════════════════

    private final OrbitCamera orbit =
        new OrbitCamera(0f, -10f, 2.0f, 0f, 0.3f, -2f);

    // ═══════════════════════════════════════════════════════════════════════
    //  Audio
    // ═══════════════════════════════════════════════════════════════════════

    private WasapiLoopbackCapture audio;
    private FFTAnalyzer           fft;

    // ═══════════════════════════════════════════════════════════════════════
    //  Screen-centre radial band lookup tables (pre-computed in create)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Pre-computed frequency band index for every depth pixel, indexed by
     * pixel ordinal {@code row × DEPTH_W + col}.
     *
     * <p>Derived from each pixel's <em>aspect-ratio-corrected</em> radial
     * distance from the depth image centre.  See {@link #buildPixelBandMap()}.
     */
    private final int[]   pixelBand   = new int[POINT_COUNT];

    /**
     * Pre-computed {@code radialNorm} [0..1] for every pixel, parallel to
     * {@link #pixelBand}.  Cached so {@code fillVertices} can use it for the
     * spectral hue tint without recomputing the square-root each frame.
     */
    private final float[] pixelRadial = new float[POINT_COUNT];

    // ═══════════════════════════════════════════════════════════════════════
    //  Frame-dedup sentinel
    // ═══════════════════════════════════════════════════════════════════════

    private float[] lastXYZ;

    // ═══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void create() {
        buildPixelBandMap();

        vertices  = new float[POINT_COUNT * FLOATS_PER_VERTEX];
        pointMesh = new Mesh(
            Mesh.VertexDataType.VertexArray, false,
            POINT_COUNT, 0,
            new VertexAttribute(VertexAttributes.Usage.Position,      3, "a_position"),
            new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, "a_color"));

        buildShader();

        skeletonOverlay = new SkeletonVisualizer2D();
        orbit.init(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 0.01f, 50f);

        // Audio — start WASAPI loopback; runs silently (no crash) if DLL missing
        audio = new WasapiLoopbackCapture();
        if (!audio.start()) {
            Gdx.app.log("AudioVisualizer",
                "WasapiLoopback not available — audio displacement disabled. " +
                "Ensure WasapiLoopback.dll is on the PATH.");
        }
        fft = new FFTAnalyzer(FFT_SIZE, AUDIO_BANDS);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Visualizer contract
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void render(KinectManager kinect) {
        orbit.handleInput();

        Gdx.gl.glClearColor(0.02f, 0.02f, 0.05f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        // Update FFT once per frame (lock-free read from WASAPI ring buffer)
        float[] bandValues = null;
        if (audio.isRunning()) {
            fft.analyze(audio.getSamples());
            bandValues = fft.getBandValues();
        }

        float[] xyz = kinect.getDepthXYZ();
        if (xyz != null && (xyz != lastXYZ || bandValues != null)) {
            // Re-fill every frame when audio is live (band values change continuously).
            // Dedup depth frames only when audio is absent/silent.
            if (xyz != lastXYZ) lastXYZ = xyz;
            fillVertices(xyz, bandValues);
            pointMesh.setVertices(vertices);
        }

        if (lastXYZ != null) {
            cloudShader.bind();
            cloudShader.setUniformMatrix("u_projTrans", orbit.getCamera().combined);
            pointMesh.render(cloudShader, GL20.GL_POINTS, 0, POINT_COUNT);
        }

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);

        // 2-D skeleton overlay (same as DepthVisualizer)
        Skeleton[] skeletons = kinect.getSkeletons();
        if (skeletons != null) {
            float sw = Gdx.graphics.getWidth();
            float sh = Gdx.graphics.getHeight();
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            skeletonOverlay.setProjection(sw, sh);
            skeletonOverlay.renderOverlay(skeletons, sw, sh);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    @Override
    public void resize(int w, int h) {
        orbit.resize(w, h);
        if (skeletonOverlay != null) skeletonOverlay.resize(w, h);
    }

    @Override
    public void dispose() {
        if (audio           != null) audio.stop();
        if (pointMesh       != null) pointMesh.dispose();
        if (cloudShader     != null) cloudShader.dispose();
        if (skeletonOverlay != null) skeletonOverlay.dispose();
    }

    @Override
    public InputProcessor getInputProcessor() { return orbit.getInputProcessor(); }

    @Override
    public void resetCamera() { orbit.reset(); }

    // ═══════════════════════════════════════════════════════════════════════
    //  Screen-centre radial band map
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Pre-computes {@link #pixelBand} and {@link #pixelRadial} for every depth
     * pixel using an <em>aspect-ratio-corrected</em> radial distance from the
     * depth image centre {@code (DEPTH_W/2, DEPTH_H/2)}.
     *
     * <h4>Formula</h4>
     * <pre>
     *   cx      = DEPTH_W / 2.0          cy      = DEPTH_H / 2.0
     *   normX   = (col − cx) / cx        // [-1..1] along X, compensates aspect
     *   normY   = (row − cy) / cy        // [-1..1] along Y, compensates aspect
     *   norm    = min(1, sqrt(normX² + normY²) / sqrt(2))
     *   band    = clamp(floor(norm × AUDIO_BANDS), 0, AUDIO_BANDS-1)
     * </pre>
     *
     * <p>Dividing each axis offset by its own half-dimension ({@code cx} or
     * {@code cy}) compensates for the non-square depth frame (512 × 424), so
     * iso-norm contours are true <em>circles on screen</em> rather than ellipses.
     * Dividing by {@code sqrt(2)} then maps the corner pixels (normX=normY=±1)
     * to {@code norm = 1.0}, giving the full band range [0..AUDIO_BANDS-1]
     * a uniform spread from centre to corner.
     *
     * <ul>
     *   <li>Centre pixel → {@code norm = 0.00} → band 0 (sub-bass)</li>
     *   <li>Edge midpoints → {@code norm ≈ 0.71} → mid-range bands</li>
     *   <li>Corner pixels → {@code norm = 1.00} → band AUDIO_BANDS-1 (treble)</li>
     * </ul>
     */
    private void buildPixelBandMap() {
        final float cx     = DEPTH_W / 2.0f;
        final float cy     = DEPTH_H / 2.0f;
        final float INV_R2 = 1.0f / (float) Math.sqrt(2.0); // 1 / sqrt(2)

        for (int row = 0; row < DEPTH_H; row++) {
            // Aspect-ratio-corrected Y component (range ±1 regardless of image height)
            float normY = (row - cy) / cy;
            for (int col = 0; col < DEPTH_W; col++) {
                // Aspect-ratio-corrected X component (range ±1 regardless of image width)
                float normX = (col - cx) / cx;
                // Circular radial distance in [0..1] — corners hit exactly 1.0
                float norm  = Math.min(1f,
                    (float) Math.sqrt(normX * normX + normY * normY) * INV_R2);
                int   idx   = row * DEPTH_W + col;
                pixelRadial[idx] = norm;
                pixelBand[idx]   = Math.min(AUDIO_BANDS - 1, (int)(norm * AUDIO_BANDS));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Point-cloud geometry
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Packs {@code (x, y, z', r, g, b, a)} into {@link #vertices} for every
     * depth pixel, where {@code z'} incorporates audio-driven displacement.
     *
     * <p>For each valid pixel ({@code z > 0}) four effects are applied in order.
     * Every audio-driven quantity is multiplied by {@code depthScale} so that
     * near (red) pixels receive the full effect and far (blue) pixels barely react.
     *
     * <h4>1 — Band lookup (O(1) via pre-computed table)</h4>
     * <pre>
     *   band       = bandValues[pixelBand[i]]   // amplitude [0..1]
     *   radialNorm = pixelRadial[i]             // screen-radial position [0..1]
     * </pre>
     *
     * <h4>2 — Depth-based impact scale</h4>
     * <pre>
     *   t          = clamp((z − DEPTH_NEAR) / (DEPTH_FAR − DEPTH_NEAR), 0, 1)
     *   depthScale = (1 − t) ^ DEPTH_SCALE_CURVE
     * </pre>
     * Near pixels (red, {@code t≈0}, {@code depthScale≈1}) receive the full
     * audio effect.  Distant pixels (blue, {@code t≈1}, {@code depthScale≈0})
     * barely react, keeping the background calm.
     *
     * <h4>3 — Z displacement</h4>
     * <pre>
     *   push = band ^ PUSH_CURVE × MAX_PUSH × depthScale
     *   z'   = −kinectZ + push
     * </pre>
     *
     * <h4>4 — Colour (four composited layers, all audio layers depth-scaled)</h4>
     * <ol>
     *   <li><b>Depth gradient</b> — red (near) → green → blue (far).</li>
     *   <li><b>Brightness boost</b> — {@code × (1 + band × COLOR_BOOST × depthScale)}.</li>
     *   <li><b>Spectral hue tint</b> — hue from {@code radialNorm}:
     *       {@code 0.0} = warm orange-red (bass), {@code 0.5} = green (mids),
     *       {@code 1.0} = cool cyan-blue (treble).
     *       Blended by {@code band × BAND_TINT_STRENGTH × depthScale}.</li>
     *   <li><b>White blend</b> — subtle desaturation by
     *       {@code band × WHITE_BLEND × depthScale}.</li>
     * </ol>
     *
     * @param xyz        Kinect depth XYZ — {@code DEPTH_W × DEPTH_H × 3} floats.
     * @param bandValues Normalised FFT magnitudes {@code [0..1]}, length
     *                   {@value #AUDIO_BANDS}, or {@code null} when audio is off.
     */
    private void fillVertices(float[] xyz, float[] bandValues) {
        int vi = 0;
        for (int i = 0; i < POINT_COUNT; i++) {
            int b = i * 3;

            float x = xyz[b];
            float y = xyz[b + 1];
            float z = xyz[b + 2];

            // ── 1: Band + radial norm via pre-computed screen-centre table ─
            float radialNorm = pixelRadial[i];
            float band       = (bandValues != null) ? bandValues[pixelBand[i]] : 0f;

            // ── 2: Depth-based impact scale ────────────────────────────────
            // t = 0 → pure red (nearest), t = 1 → pure blue (farthest)
            float t = (z > 0f)
                ? MathUtils.clamp((z - DEPTH_NEAR) / (DEPTH_FAR - DEPTH_NEAR), 0f, 1f)
                : 0f;
            // depthScale ≈ 1 for near pixels (red), ≈ 0 for distant pixels (blue).
            // All audio effects below are multiplied by this value.
            float depthScale = (z > 0f)
                ? (float) Math.pow(1f - t, DEPTH_SCALE_CURVE)
                : 0f;

            // ── 3: Z displacement — protrudes toward viewer, depth-scaled ──
            float push = (float) Math.pow(band, PUSH_CURVE) * MAX_PUSH * depthScale;

            vertices[vi++] = x;
            vertices[vi++] = y;
            vertices[vi++] = -z + push; // negate Kinect Z → positive world Z, then push

            if (z <= 0f) {
                // No valid depth — invisible; discarded by shader alpha test
                vertices[vi++] = 0f;
                vertices[vi++] = 0f;
                vertices[vi++] = 0f;
                vertices[vi++] = 0f;
            } else {
                // ── Layer 1: depth distance gradient (red → green → blue) ──
                float r, g, bl;
                if (t < 0.5f) {
                    r  = 1f - t * 2f;
                    g  = t * 2f;
                    bl = 0f;
                } else {
                    r  = 0f;
                    g  = 1f - (t - 0.5f) * 2f;
                    bl = (t - 0.5f) * 2f;
                }

                // ── Layer 2: brightness boost (depth-scaled) ───────────────
                float brightness = 1f + band * COLOR_BOOST * depthScale;
                r  = Math.min(1f, r  * brightness);
                g  = Math.min(1f, g  * brightness);
                bl = Math.min(1f, bl * brightness);

                // ── Layer 3: spectral hue tint by frequency ring ───────────
                // Hue palette keyed to radialNorm (screen-radial position):
                //   0.0  → bass zone    warm orange-red  (1.00, 0.35, 0.00)
                //   0.5  → mid zone     neutral green    (0.10, 0.90, 0.10)
                //   1.0  → treble zone  cool cyan-blue   (0.00, 0.60, 1.00)
                // Blend weight = amplitude × strength × depthScale, so only
                // loud, near pixels show strong spectral colour; silent or
                // distant pixels retain the plain depth gradient.
                float tr, tg, tb;
                if (radialNorm < 0.5f) {
                    float s = radialNorm / 0.5f;           // 0→1 across inner half
                    tr = 1.00f - s * 0.90f;                // orange-red → near-zero
                    tg = 0.35f + s * 0.55f;                // warm-green → full green
                    tb = 0.00f + s * 0.10f;                // zero → faint blue
                } else {
                    float s = (radialNorm - 0.5f) / 0.5f; // 0→1 across outer half
                    tr = 0.10f - s * 0.10f;                // near-zero → zero
                    tg = 0.90f - s * 0.30f;                // full green → teal
                    tb = 0.10f + s * 0.90f;                // faint blue → full cyan-blue
                }
                float tintWeight = band * BAND_TINT_STRENGTH * depthScale;
                r  = r  + (tr - r)  * tintWeight;
                g  = g  + (tg - g)  * tintWeight;
                bl = bl + (tb - bl) * tintWeight;

                // ── Layer 4: white blend — subtle desaturation on loud peaks ─
                float wb = band * WHITE_BLEND * depthScale;
                r  = r  + (1f - r)  * wb;
                g  = g  + (1f - g)  * wb;
                bl = bl + (1f - bl) * wb;

                vertices[vi++] = Math.min(1f, r);
                vertices[vi++] = Math.min(1f, g);
                vertices[vi++] = Math.min(1f, bl);
                vertices[vi++] = 1f;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Shader (identical to DepthVisualizer — discards zero-alpha vertices)
    // ═══════════════════════════════════════════════════════════════════════

    private void buildShader() {
        String vert =
            "attribute vec3 a_position;\n" +
            "attribute vec4 a_color;\n" +
            "uniform mat4 u_projTrans;\n" +
            "varying vec4 v_color;\n" +
            "void main() {\n" +
            "    v_color     = a_color;\n" +
            "    gl_Position = u_projTrans * vec4(a_position, 1.0);\n" +
            "    gl_PointSize = 2.0;\n" +
            "}\n";

        String frag =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec4 v_color;\n" +
            "void main() {\n" +
            "    if (v_color.a < 0.01) discard;\n" +
            "    gl_FragColor = v_color;\n" +
            "}\n";

        cloudShader = new ShaderProgram(vert, frag);
        if (!cloudShader.isCompiled())
            throw new RuntimeException("Audio cloud shader:\n" + cloudShader.getLog());
    }
}
