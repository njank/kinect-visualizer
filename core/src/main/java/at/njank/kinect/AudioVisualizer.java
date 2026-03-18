package at.njank.kinect;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import edu.ufl.digitalworlds.j4k.Skeleton;

import java.nio.ByteBuffer;

import static at.njank.kinect.KinectManager.DEPTH_H;
import static at.njank.kinect.KinectManager.DEPTH_W;
import static at.njank.kinect.SkeletonConstants.*;

/**
 * Visualizer mode 6 - "Audio".
 *
 * <p>Renders the Kinect v2 colour stream as a UV-mapped 3-D point cloud
 * (identical to {@link ARVisualizer}) and layers system audio on top.
 * The scene looks identical to AR at silence; on loud beats the depth
 * surface physically protrudes toward the viewer and the camera colour
 * is tinted by spectral hue.
 *
 * <h3>Audio pipeline</h3>
 * <ol>
 *   <li>{@link WasapiLoopbackCapture} - WASAPI loopback captures whatever is
 *       playing on the default audio device; no Kinect microphone or Windows
 *       "Stereo Mix" setting is needed.  Requires {@code WasapiLoopback.dll}
 *       (x64) next to the application JAR or on the system {@code PATH}.</li>
 *   <li>{@link FFTAnalyzer} - converts the raw PCM ring-buffer snapshot into
 *       {@value #AUDIO_BANDS} smoothed, AGC-normalised frequency bands
 *       ({@code [0..1]}) once per render frame.</li>
 *   <li><b>Radial band mapping</b> - each depth pixel is assigned a band by
 *       its <em>aspect-ratio-corrected radial distance</em> from the depth
 *       image centre ({@code DEPTH_W/2, DEPTH_H/2}).  Distance 0 (centre)
 *       maps to band 0 (sub-bass); distance 1 (corner) maps to band
 *       {@value #AUDIO_BANDS}-1 (treble).  The result is concentric rings of
 *       frequency, like ripples spreading from the screen centre.  The lookup
 *       table is pre-computed once in {@link #create()} and costs O(1) per
 *       pixel per frame.</li>
 * </ol>
 *
 * <h3>Visual effects</h3>
 * <ul>
 *   <li><b>Z displacement</b> - each pixel is pushed toward the viewer by
 *       {@code bandValue^PUSH_CURVE x MAX_PUSH} metres.  Near pixels
 *       (small kinect Z) protrude more than far pixels (depth-scaled impact).
 *       At silence the cloud lies flat, identical to ARVisualizer.</li>
 *   <li><b>Spectral hue tint</b> - the camera texture colour is blended
 *       toward a frequency-ring hue (bass = warm orange-red, mids = green,
 *       treble = cool cyan-blue) proportionally to
 *       {@code bandValue x BAND_TINT_STRENGTH x depthScale}.
 *       Silent pixels remain true-colour.</li>
 * </ul>
 *
 * <p>Camera controls are handled by {@link OrbitCamera} (same defaults as
 * {@link ARVisualizer}): left-drag orbit, right-drag pan, scroll zoom, R reset.
 */
public class AudioVisualizer implements Visualizer {

    // -----------------------------------------------------------------------
    //  TUNING CONSTANTS - adjust these to change the audio-visual behaviour
    // -----------------------------------------------------------------------

    // -- Audio FFT -----------------------------------------------------------

    /** FFT window size in samples.  Must be a power of 2.
     *  2048 gives ~21 Hz per bin at 44100 Hz - good bass resolution. */
    private static final int FFT_SIZE    = 2048;

    /**
     * Number of frequency bands produced by {@link FFTAnalyzer} and mapped
     * onto the depth image as concentric radial rings.
     *
     * <pre>
     *  16 = coarse; wide rings, dramatic transitions between bands
     *  32 = balanced - recommended default
     *  48 = fine; smooth frequency sweep from centre to edge
     *  64 = very fine; subtle ring-to-ring differences
     * </pre>
     *
     * Increasing this beyond half the shorter image dimension (DEPTH_H/2 = 212)
     * provides no extra detail as multiple bands then share the same ring width.
     */
    private static final int AUDIO_BANDS = 32;

    // -- Z displacement ------------------------------------------------------

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
     *   Pop / EDM (heavily compressed):  2.0 - 4.0
     *   Rock / Hip-hop:                  3.0 - 6.0
     *   Classical / Jazz (wide dynamics): 1.0 - 2.0
     *   "Only peaks matter" look:        8.0 - 15.0
     *   Extreme spike-only aesthetics:   20.0 - 30.0
     */
    private static final float PUSH_CURVE  = 15.0f;

    // -- Depth-based impact scaling ------------------------------------------

    /**
     * Power exponent that scales all audio-driven effects by how close a pixel
     * is to the camera.
     *
     * <p>Near pixels (small Z) carry the most tactile audio energy and receive
     * the full effect.  Distant pixels are background surface and should react
     * subtly so the scene reads cleanly.
     *
     * <p>The per-pixel scale factor is:
     * <pre>
     *   t          = clamp((z - DEPTH_NEAR) / (DEPTH_FAR - DEPTH_NEAR), 0, 1)
     *   depthScale = (1 - t) ^ DEPTH_SCALE_CURVE
     * </pre>
     * {@code depthScale} is applied to Z push and colour tint weight, so near
     * pixels fully protrude and glow while far pixels barely move.
     *
     * <pre>
     *  0.5 = gentle falloff; background still reacts noticeably
     *  1.0 = linear - depth scale halves at mid-range
     *  2.0 = quadratic - recommended; near surface dominates, background quiet
     *  3.0 = steep; only the foreground (within ~1 m) shows a strong effect
     * </pre>
     */
    private static final float DEPTH_SCALE_CURVE = 2.0f;

    /** Nearest depth distance in metres (maps to depthScale = 1). */
    private static final float DEPTH_NEAR        = 0.5f;

    /** Farthest depth distance in metres (maps to depthScale = 0). */
    private static final float DEPTH_FAR         = 5.0f;

    // -- Colour tint ---------------------------------------------------------

    /**
     * Strength of the spectral hue tint blended into the camera colour.
     *
     * <p>Each frequency ring has an associated hue derived from its
     * {@code radialNorm} position:
     * <pre>
     *   radialNorm 0.0  (centre)  -> warm orange-red  bass
     *   radialNorm 0.5  (mid)     -> neutral green     mids
     *   radialNorm 1.0  (corner)  -> cool cyan-blue    treble
     * </pre>
     * The tint is mixed into the sampled camera colour by
     * {@code bandValue x BAND_TINT_STRENGTH x depthScale}.  At silence
     * ({@code band = 0}) the camera image is shown unmodified.
     *
     * <pre>
     *  0.0  = no spectral tint; pure camera colour at all times
     *  0.15 = subtle; faint warm/cool shift on active rings
     *  0.35 = recommended - clearly visible tint without drowning camera colour
     *  0.60 = strong; spectral colour competes with the camera image
     *  1.0  = spectral colour fully replaces camera colour at peak amplitude
     * </pre>
     */
    private static final float BAND_TINT_STRENGTH = 0.35f;

    // -----------------------------------------------------------------------
    //  Geometry
    // -----------------------------------------------------------------------

    private static final int POINT_COUNT = DEPTH_W * DEPTH_H;

    // Vertex layout: x y z  u v  tintR tintG tintB  tintWeight
    //   x y z        - world position (z includes audio push)
    //   u v          - camera texture coordinates; negative = invalid (discard)
    //   tintRGB      - spectral hue for this pixel's frequency ring
    //   tintWeight   - blend factor: band * BAND_TINT_STRENGTH * depthScale
    private static final int FLOATS_PER_VERTEX = 9;

    // -----------------------------------------------------------------------
    //  GPU resources
    // -----------------------------------------------------------------------

    private Pixmap        bgPixmap;
    private Texture       bgTexture;

    private Mesh          uvMesh;
    private float[]       vertices;
    private ShaderProgram uvShader;

    /** ShapeRenderer for 3-D skeleton bones (world-space lines) and billboard joints. */
    private ShapeRenderer sr;
    /** Ortho matrix kept in sync with window size for billboard joint circles. */
    private final Matrix4  screenOrtho = new Matrix4();
    /** Reusable joint world-space vectors — avoids per-frame allocation. */
    private final Vector3  tmpA        = new Vector3();
    private final Vector3  tmpB        = new Vector3();
    /** Screen-space radius of the joint billboard circles (pixels). */
    private static final float JOINT_RADIUS = 9f;

    // -----------------------------------------------------------------------
    //  Camera  (same defaults as ARVisualizer)
    // -----------------------------------------------------------------------

    private final OrbitCamera orbit =
        new OrbitCamera(0f, -10f, 2.0f, 0f, 0.3f, -2f);

    // -----------------------------------------------------------------------
    //  Audio
    // -----------------------------------------------------------------------

    private WasapiLoopbackCapture audio;
    private FFTAnalyzer           fft;

    // -----------------------------------------------------------------------
    //  Screen-centre radial band lookup tables (pre-computed in create)
    // -----------------------------------------------------------------------

    /**
     * Pre-computed frequency band index for every depth pixel, indexed by
     * pixel ordinal {@code row x DEPTH_W + col}.
     *
     * <p>Derived from each pixel's <em>aspect-ratio-corrected</em> radial
     * distance from the depth image centre.  See {@link #buildPixelBandMap()}.
     */
    private final int[]   pixelBand   = new int[POINT_COUNT];

    /**
     * Pre-computed {@code radialNorm} [0..1] for every pixel, parallel to
     * {@link #pixelBand}.  Cached so {@code fillVertices} can drive the
     * spectral hue tint without recomputing the square-root each frame.
     */
    private final float[] pixelRadial = new float[POINT_COUNT];

    // -----------------------------------------------------------------------
    //  Frame-dedup sentinels
    // -----------------------------------------------------------------------

    private byte[]  lastColorFrame;
    private float[] lastXYZ;
    private float[] lastUV;

    /** Whether to draw the skeleton overlay.  Toggled by the S key (default off). */
    private boolean skeletonEnabled = false;

    // -----------------------------------------------------------------------
    //  Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void create() {
        // Pre-compute radial centre -> band lookup so fillVertices() is O(1) per pixel
        buildPixelBandMap();

        bgPixmap  = new Pixmap(COLOR_W, COLOR_H, Pixmap.Format.RGBA8888);
        bgTexture = new Texture(bgPixmap);
        bgTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);

        vertices = new float[POINT_COUNT * FLOATS_PER_VERTEX];
        uvMesh   = new Mesh(
            Mesh.VertexDataType.VertexArray, false,
            POINT_COUNT, 0,
            new VertexAttribute(VertexAttributes.Usage.Position,           3, "a_position"),
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"),
            new VertexAttribute(VertexAttributes.Usage.Generic,            3, "a_tintColor"),
            new VertexAttribute(VertexAttributes.Usage.Generic,            1, "a_tintWeight"));

        buildShader();
        sr = new ShapeRenderer();
        screenOrtho.setToOrtho2D(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        orbit.init(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 0.01f, 50f);

        // Audio - start WASAPI loopback; gracefully continues without it
        audio = new WasapiLoopbackCapture();
        if (!audio.start()) {
            Gdx.app.log("AudioVisualizer",
                "WasapiLoopback not available - audio effects disabled. " +
                "Ensure WasapiLoopback.dll is on the PATH.");
        }
        fft = new FFTAnalyzer(FFT_SIZE, AUDIO_BANDS);
    }

    // -----------------------------------------------------------------------
    //  Visualizer contract
    // -----------------------------------------------------------------------

    @Override
    public void render(KinectManager kinect) {
        orbit.handleInput();

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        // Upload new colour frame to texture (BGRA -> RGBA swap on CPU)
        byte[] colorFrame = kinect.getColorFrame();
        if (colorFrame != null && colorFrame != lastColorFrame) {
            lastColorFrame = colorFrame;
            uploadBgra(colorFrame);
        }

        // Update FFT once per frame (lock-free read from WASAPI ring buffer)
        float[] bandValues = null;
        if (audio.isRunning()) {
            fft.analyze(audio.getSamples());
            bandValues = fft.getBandValues();
        }

        // Rebuild point cloud when depth, UV, or audio changes.
        // Always re-fill when audio is live (band values change every frame);
        // dedup depth/UV frames only when audio is silent or absent.
        float[] xyz = kinect.getDepthXYZ();
        float[] uv  = kinect.getDepthUV();
        if (xyz != null && uv != null && (xyz != lastXYZ || uv != lastUV || bandValues != null)) {
            lastXYZ = xyz;
            lastUV  = uv;
            fillVertices(xyz, uv, bandValues);
            uvMesh.setVertices(vertices);
        }

        if (lastXYZ != null && lastColorFrame != null) {
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            bgTexture.bind(0);
            uvShader.bind();
            uvShader.setUniformMatrix("u_projTrans", orbit.getCamera().combined);
            uvShader.setUniformi("u_texture", 0);
            uvMesh.render(uvShader, GL20.GL_POINTS, 0, POINT_COUNT);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        }

        // Skeleton drawn in the same audio-displaced 3-D world space as the point cloud
        if (skeletonEnabled) draw3DSkeleton(kinect.getSkeletons(), bandValues);
    }

    @Override
    public void resize(int w, int h) {
        orbit.resize(w, h);
        screenOrtho.setToOrtho2D(0, 0, w, h);
    }

    @Override
    public void dispose() {
        if (audio     != null) audio.stop();
        if (bgTexture != null) bgTexture.dispose();
        if (bgPixmap  != null) bgPixmap.dispose();
        if (uvMesh    != null) uvMesh.dispose();
        if (uvShader  != null) uvShader.dispose();
        if (sr        != null) sr.dispose();
    }

    @Override
    public void setSkeletonEnabled(boolean enabled) { skeletonEnabled = enabled; }

    @Override
    public boolean isSkeletonEnabled() { return skeletonEnabled; }

    @Override
    public InputProcessor getInputProcessor() { return orbit.getInputProcessor(); }

    @Override
    public void resetCamera() { orbit.reset(); }

    // -----------------------------------------------------------------------
    //  Radial band map initialisation
    // -----------------------------------------------------------------------

    /**
     * Pre-computes {@link #pixelBand} and {@link #pixelRadial} for every depth
     * pixel using an <em>aspect-ratio-corrected</em> radial distance from the
     * depth image centre {@code (DEPTH_W/2, DEPTH_H/2)}.
     *
     * <h4>Formula</h4>
     * <pre>
     *   cx      = DEPTH_W / 2.0          cy      = DEPTH_H / 2.0
     *   normX   = (col - cx) / cx        // [-1..1] along X, compensates aspect
     *   normY   = (row - cy) / cy        // [-1..1] along Y, compensates aspect
     *   norm    = min(1, sqrt(normX^2 + normY^2) / sqrt(2))
     *   band    = clamp(floor(norm x AUDIO_BANDS), 0, AUDIO_BANDS-1)
     * </pre>
     *
     * <p>Dividing each axis offset by its own half-dimension ({@code cx} or
     * {@code cy}) compensates for the non-square depth frame (512 x 424), so
     * iso-norm contours are true <em>circles on screen</em> rather than
     * ellipses stretched by the aspect ratio.  Dividing by {@code sqrt(2)}
     * maps the corner pixels (normX = normY = +-1) to {@code norm = 1.0},
     * giving the full band range a uniform spread from centre to corner.
     *
     * <ul>
     *   <li>Centre pixel -> {@code norm = 0.00} -> band 0 (sub-bass)</li>
     *   <li>Edge midpoints -> {@code norm ~= 0.71} -> mid-range bands</li>
     *   <li>Corner pixels -> {@code norm = 1.00} -> band AUDIO_BANDS-1 (treble)</li>
     * </ul>
     */
    private void buildPixelBandMap() {
        final float cx     = DEPTH_W / 2.0f;
        final float cy     = DEPTH_H / 2.0f;
        final float INV_R2 = 1.0f / (float) Math.sqrt(2.0); // 1 / sqrt(2)

        for (int row = 0; row < DEPTH_H; row++) {
            // Aspect-ratio-corrected Y component (range +-1 regardless of image height)
            float normY = (row - cy) / cy;
            for (int col = 0; col < DEPTH_W; col++) {
                // Aspect-ratio-corrected X component (range +-1 regardless of image width)
                float normX = (col - cx) / cx;
                // Circular radial distance in [0..1] - corners hit exactly 1.0
                float norm  = Math.min(1f,
                    (float) Math.sqrt(normX * normX + normY * normY) * INV_R2);
                int   idx   = row * DEPTH_W + col;
                pixelRadial[idx] = norm;
                pixelBand[idx]   = Math.min(AUDIO_BANDS - 1, (int)(norm * AUDIO_BANDS));
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Point-cloud geometry
    // -----------------------------------------------------------------------

    /**
     * Packs {@code (x, y, z', u, v, tintR, tintG, tintB, tintWeight)} into
     * {@link #vertices} for every depth pixel, where {@code z'} incorporates
     * the audio-driven displacement.
     *
     * <p>For each valid pixel ({@code z > 0} and valid UV) the following steps
     * run in order.  Every audio-driven quantity is multiplied by
     * {@code depthScale} so that near pixels receive the full effect and far
     * pixels barely react.
     *
     * <h4>1 - Band lookup (O(1) via pre-computed table)</h4>
     * <pre>
     *   band       = bandValues[pixelBand[i]]   // amplitude [0..1]
     *   radialNorm = pixelRadial[i]             // screen-radial position [0..1]
     * </pre>
     *
     * <h4>2 - Depth-based impact scale</h4>
     * <pre>
     *   t          = clamp((z - DEPTH_NEAR) / (DEPTH_FAR - DEPTH_NEAR), 0, 1)
     *   depthScale = (1 - t) ^ DEPTH_SCALE_CURVE
     * </pre>
     * Near pixels ({@code t~=0}, {@code depthScale~=1}) receive the full audio
     * effect.  Distant pixels ({@code t~=1}, {@code depthScale~=0}) barely move.
     *
     * <h4>3 - Z displacement</h4>
     * <pre>
     *   push = band ^ PUSH_CURVE x MAX_PUSH x depthScale
     *   z'   = -kinectZ + push     (negate to match libGDX convention, then push)
     * </pre>
     *
     * <h4>4 - Spectral hue tint (via shader mix)</h4>
     * The vertex carries a pre-computed spectral colour keyed to
     * {@code radialNorm}:
     * <pre>
     *   0.0  (centre)  -> warm orange-red  (1.00, 0.35, 0.00)  bass
     *   0.5  (mid)     -> neutral green    (0.10, 0.90, 0.10)  mids
     *   1.0  (corner)  -> cool cyan-blue   (0.00, 0.60, 1.00)  treble
     * </pre>
     * The tint weight {@code band x BAND_TINT_STRENGTH x depthScale} drives
     * {@code mix(camColor, tintColor, tintWeight)} in the fragment shader.
     * At silence {@code tintWeight = 0} and the output is pure camera colour.
     *
     * <p>Invalid pixels (UV out of range or {@code z <= 0}) have their UV set
     * to {@code -1} which the shader detects and discards.
     *
     * @param xyz        Kinect depth XYZ - {@code DEPTH_W x DEPTH_H x 3} floats.
     * @param uv         Kinect colour UVs - {@code DEPTH_W x DEPTH_H x 2} floats.
     * @param bandValues Normalised FFT magnitudes {@code [0..1]}, length
     *                   {@value #AUDIO_BANDS}, or {@code null} when audio is off.
     */
    private void fillVertices(float[] xyz, float[] uv, float[] bandValues) {
        int vi = 0;
        for (int i = 0; i < POINT_COUNT; i++) {
            int b3 = i * 3;
            int b2 = i * 2;

            float x = xyz[b3];
            float y = xyz[b3 + 1];
            float z = xyz[b3 + 2];

            float u = uv[b2];
            float v = uv[b2 + 1];
            boolean invalid = (u < 0f || v < 0f || z <= 0f);

            // -- 1: Band + radial norm via pre-computed screen-centre table --
            float radialNorm = pixelRadial[i];
            float band       = (bandValues != null && !invalid)
                               ? bandValues[pixelBand[i]] : 0f;

            // -- 2: Depth-based impact scale (near = full, far = silent) ----
            // t = 0 -> nearest surface (full effect), t = 1 -> farthest (no effect)
            float t = (z > 0f)
                ? MathUtils.clamp((z - DEPTH_NEAR) / (DEPTH_FAR - DEPTH_NEAR), 0f, 1f)
                : 0f;
            float depthScale = (z > 0f)
                ? (float) Math.pow(1f - t, DEPTH_SCALE_CURVE)
                : 0f;

            // -- 3: Z displacement - protrudes toward viewer, depth-scaled --
            float push = (float) Math.pow(band, PUSH_CURVE) * MAX_PUSH * depthScale;

            vertices[vi++] = x;
            vertices[vi++] = y;
            vertices[vi++] = -z + push; // negate Kinect Z -> positive world Z, then push out

            // UV: negative sentinel flags the shader to discard this point
            vertices[vi++] = invalid ? -1f : u;
            vertices[vi++] = invalid ? -1f : v;

            // -- 4: Spectral hue tint colour, keyed to radialNorm -----------
            // Hue palette:
            //   0.0 -> warm orange-red  (bass zone)
            //   0.5 -> neutral green    (mids zone)
            //   1.0 -> cool cyan-blue   (treble zone)
            float tr, tg, tb;
            if (radialNorm < 0.5f) {
                float s = radialNorm / 0.5f;           // 0->1 across inner half
                tr = 1.00f - s * 0.90f;                // orange-red -> near-zero
                tg = 0.35f + s * 0.55f;                // warm-green -> full green
                tb = 0.00f + s * 0.10f;                // zero -> faint blue
            } else {
                float s = (radialNorm - 0.5f) / 0.5f; // 0->1 across outer half
                tr = 0.10f - s * 0.10f;                // near-zero -> zero
                tg = 0.90f - s * 0.30f;                // full green -> teal
                tb = 0.10f + s * 0.90f;                // faint blue -> full cyan-blue
            }
            vertices[vi++] = tr;
            vertices[vi++] = tg;
            vertices[vi++] = tb;

            // Tint weight: 0 at silence/far pixels, up to BAND_TINT_STRENGTH at peak.
            // The fragment shader blends: mix(camColor, tintColor, tintWeight)
            vertices[vi++] = invalid ? 0f : (band * BAND_TINT_STRENGTH * depthScale);
        }
    }

    // -----------------------------------------------------------------------
    //  Shader
    // -----------------------------------------------------------------------

    /**
     * Builds the UV + tint shader.  Vertex stage passes UV coordinates and
     * tint parameters to the fragment stage.  Fragment stage:
     * <ol>
     *   <li>Discards points whose UV x-component is negative (invalid depth).</li>
     *   <li>Samples the camera texture at {@code v_uv}.</li>
     *   <li>Mixes the sampled colour toward {@code v_tintColor} by
     *       {@code v_tintWeight} using GLSL {@code mix()}.</li>
     * </ol>
     * At silence {@code v_tintWeight = 0} so the output is pure camera colour,
     * identical to {@link ARVisualizer}.
     */
    private void buildShader() {
        String vert =
            "attribute vec3  a_position;\n" +
            "attribute vec2  a_texCoord0;\n" +
            "attribute vec3  a_tintColor;\n" +
            "attribute float a_tintWeight;\n" +
            "uniform mat4 u_projTrans;\n" +
            "varying vec2  v_uv;\n" +
            "varying float v_valid;\n" +
            "varying vec3  v_tintColor;\n" +
            "varying float v_tintWeight;\n" +
            "void main() {\n" +
            "    v_uv         = a_texCoord0;\n" +
            "    v_valid      = (a_texCoord0.x < 0.0) ? 0.0 : 1.0;\n" +
            "    v_tintColor  = a_tintColor;\n" +
            "    v_tintWeight = a_tintWeight;\n" +
            "    gl_Position  = u_projTrans * vec4(a_position, 1.0);\n" +
            "    gl_PointSize = 2.5;\n" +
            "}\n";

        String frag =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec2  v_uv;\n" +
            "varying float v_valid;\n" +
            "varying vec3  v_tintColor;\n" +
            "varying float v_tintWeight;\n" +
            "uniform sampler2D u_texture;\n" +
            "void main() {\n" +
            "    if (v_valid < 0.5) discard;\n" +
            "    vec4 camColor = texture2D(u_texture, v_uv);\n" +
            // At silence tintWeight=0 -> pure camera colour (identical to AR mode)
            "    vec3 rgb = mix(camColor.rgb, v_tintColor, v_tintWeight);\n" +
            "    gl_FragColor = vec4(rgb, 1.0);\n" +
            "}\n";

        uvShader = new ShaderProgram(vert, frag);
        if (!uvShader.isCompiled())
            throw new RuntimeException("Audio UV shader:\n" + uvShader.getLog());
    }

    // -----------------------------------------------------------------------
    //  Colour-frame upload (BGRA -> RGBA)
    // -----------------------------------------------------------------------

    /**
     * Swaps the Kinect BGRA colour frame into RGBA order and uploads it to
     * {@link #bgTexture}.  Called at most once per new colour frame (deduped
     * by reference comparison in {@link #render}).
     */
    private void uploadBgra(byte[] bgra) {
        ByteBuffer buf = bgPixmap.getPixels();
        buf.rewind();
        for (int i = 0, n = bgra.length - 3; i < n; i += 4) {
            buf.put(bgra[i + 2]); // R <- B
            buf.put(bgra[i + 1]); // G
            buf.put(bgra[i    ]); // B <- R
            buf.put(bgra[i + 3]); // A
        }
        buf.rewind();
        bgTexture.draw(bgPixmap, 0, 0);
    }

    // -----------------------------------------------------------------------
    //  3-D skeleton overlay (audio-displaced)
    // -----------------------------------------------------------------------

    /**
     * Renders skeleton bones and joints in the same audio-displaced 3-D world
     * space as the point cloud, so the skeleton tracks the person exactly at
     * every camera angle.
     *
     * <p>Each joint's Z position is displaced by the <em>same push formula</em>
     * used in {@link #fillVertices}: the joint's depth pixel is looked up in
     * {@link #pixelBand}, the corresponding band value is read, and then:
     * <pre>
     *   depthScale = (1 - t) ^ DEPTH_SCALE_CURVE
     *   push       = band ^ PUSH_CURVE x MAX_PUSH x depthScale
     *   worldZ     = -kinectZ + push
     * </pre>
     * This keeps the skeleton rigidly attached to the point cloud surface —
     * joints protrude in perfect sync with the surrounding pixels.
     *
     * <p>Bones are drawn as 3-D lines (world-space projection).  Joints are
     * projected through the orbit camera into screen space and drawn as
     * billboard circles so they are always legible regardless of view angle.
     *
     * @param bandValues current FFT magnitudes [0..1], or {@code null} when
     *                   audio is unavailable (skeleton rendered without push)
     */
    private void draw3DSkeleton(Skeleton[] skeletons, float[] bandValues) {
        if (skeletons == null) return;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // ── Bones: 3-D lines in audio-displaced world space ──
        sr.setProjectionMatrix(orbit.getCamera().combined);
        sr.begin(ShapeRenderer.ShapeType.Line);
        for (int s = 0; s < skeletons.length; s++) {
            Skeleton sk = skeletons[s];
            if (sk == null) continue;
            Color col = SKELETON_COLORS[s % SKELETON_COLORS.length];
            sr.setColor(col.r, col.g, col.b, 0.9f);
            for (int[] bone : BONES) {
                if (!has3D(sk, bone[0]) || !has3D(sk, bone[1])) continue;
                toWorldAudio(sk, bone[0], tmpA, bandValues);
                toWorldAudio(sk, bone[1], tmpB, bandValues);
                sr.line(tmpA, tmpB);
            }
        }
        sr.end();

        // ── Joints: project to screen, draw billboard circles ──
        sr.setProjectionMatrix(screenOrtho);
        sr.begin(ShapeRenderer.ShapeType.Filled);
        for (int s = 0; s < skeletons.length; s++) {
            Skeleton sk = skeletons[s];
            if (sk == null) continue;
            Color col = SKELETON_COLORS[s % SKELETON_COLORS.length];
            for (int j = 0; j < JOINT_COUNT; j++) {
                if (!has3D(sk, j)) continue;
                toWorldAudio(sk, j, tmpA, bandValues);
                orbit.getCamera().project(tmpA);
                if (tmpA.z > 1f) continue; // behind camera or past far clip
                sr.setColor(col.r * 0.3f, col.g * 0.3f, col.b * 0.3f, 1f);
                sr.circle(tmpA.x, tmpA.y, JOINT_RADIUS + 3f);
                sr.setColor(col.r, col.g, col.b, 1f);
                sr.circle(tmpA.x, tmpA.y, JOINT_RADIUS);
            }
        }
        sr.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    // -----------------------------------------------------------------------
    //  Joint helpers
    // -----------------------------------------------------------------------

    /** Returns {@code true} if the joint has a non-zero 3-D position. */
    private static boolean has3D(Skeleton sk, int j) {
        double[] p = sk.get3DJoint(j);
        return p[0] != 0.0 || p[1] != 0.0 || p[2] != 0.0;
    }

    /**
     * Converts a joint to world space with the <em>same</em> audio Z
     * displacement applied to the depth-cloud pixels at that position.
     *
     * <p>Steps:
     * <ol>
     *   <li>Get metric joint position; negate Kinect Z (sensor convention).</li>
     *   <li>Find the depth-image pixel for this joint via
     *       {@code get2DJoint(j, DEPTH_W, DEPTH_H)}.</li>
     *   <li>Look up its pre-computed band index in {@link #pixelBand}.</li>
     *   <li>Apply the identical push formula from {@link #fillVertices}:
     *       <pre>
     *         t          = clamp((kinectZ - DEPTH_NEAR) / (DEPTH_FAR - DEPTH_NEAR), 0, 1)
     *         depthScale = (1 - t) ^ DEPTH_SCALE_CURVE
     *         push       = band ^ PUSH_CURVE x MAX_PUSH x depthScale
     *         worldZ     = -kinectZ + push
     *       </pre>
     *   </li>
     * </ol>
     *
     * @param bandValues current FFT magnitudes, or {@code null} for no push
     */
    private void toWorldAudio(Skeleton sk, int j, Vector3 out, float[] bandValues) {
        double[] p      = sk.get3DJoint(j);
        float    jx     = (float) p[0];
        float    jy     = (float) p[1];
        float    kinectZ = (float) p[2]; // Kinect: positive Z toward sensor

        float push = 0f;
        if (bandValues != null) {
            // Find the depth pixel for this joint and look up its band
            int[] dp = sk.get2DJoint(j, DEPTH_W, DEPTH_H);
            int   dx = dp[0];
            int   dy = dp[1];
            if (dx >= 0 && dx < DEPTH_W && dy >= 0 && dy < DEPTH_H) {
                float bandVal = bandValues[pixelBand[dy * DEPTH_W + dx]];
                // Depth-based impact scale — identical to fillVertices step 2
                float t = MathUtils.clamp(
                    (kinectZ - DEPTH_NEAR) / (DEPTH_FAR - DEPTH_NEAR), 0f, 1f);
                float depthScale = (float) Math.pow(1f - t, DEPTH_SCALE_CURVE);
                // Z protrusion — identical to fillVertices step 3
                push = (float) Math.pow(bandVal, PUSH_CURVE) * MAX_PUSH * depthScale;
            }
        }

        out.set(jx, jy, -kinectZ + push);
    }

}
