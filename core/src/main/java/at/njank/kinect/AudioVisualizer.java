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
 * {@link DepthVisualizer}) and layers system audio on top: each depth pixel's
 * Z coordinate is pushed <em>toward the viewer</em> by an amount proportional
 * to the magnitude of the audio frequency band that corresponds to that
 * pixel's horizontal column position.  The result is a living, breathing
 * depth surface that physically protrudes in sync with the music.
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
 *   <li>Band → pixel mapping — each depth pixel at column {@code c}
 *       ({@code 0 .. DEPTH_W-1}) is assigned the band whose index equals
 *       {@code round(c × AUDIO_BANDS / DEPTH_W)}.  Low bands (bass) map to
 *       the left half of the image; high bands (treble) to the right half,
 *       mirroring a traditional spectrum analyser layout.</li>
 * </ol>
 *
 * <h3>Visual effect</h3>
 * <ul>
 *   <li><b>Z displacement</b> — a pixel's world-Z is increased by
 *       {@code bandValue × MAX_PUSH} metres, making the surface protrude
 *       toward the viewer.  Silent columns lie flat; loud columns spike
 *       outward like spines of sound.</li>
 *   <li><b>Colour modulation</b> — the base red→green→blue depth-distance
 *       gradient is brightened by {@code 1 + bandValue × COLOR_BOOST}, so
 *       active audio regions glow more intensely than silent ones.</li>
 * </ul>
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
     * onto the depth image columns.
     *
     * <pre>
     *  16 = coarse; wide column stripes, dramatic transitions between bands
     *  32 = balanced — recommended default
     *  48 = fine; smooth frequency sweep across the image width
     *  64 = very fine; subtle column-to-column differences
     * </pre>
     *
     * Increasing this beyond DEPTH_W / 4 (128) provides no extra detail
     * because multiple bands then map to the same pixel columns.
     */
    private static final int   AUDIO_BANDS = 32;

    // ── Z displacement ──────────────────────────────────────────────────────

    /**
     * Maximum depth-axis protrusion in metres when a frequency band reaches
     * full amplitude (band value = 1.0).
     *
     * <p>The displacement is added <em>toward the viewer</em> (world +Z
     * direction after the standard Kinect Z-negation), so higher values
     * cause louder columns to lunge out of the depth surface.
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
     * Power exponent applied to the normalised band value before it is
     * multiplied by {@link #MAX_PUSH}.
     *
     * <p>A value of 1.0 gives linear displacement (moderate bass = moderate
     * push).  Higher values concentrate the displacement in loud peaks,
     * creating a sharper, more percussive-feeling protrusion.
     *
     * <pre>
     *  1.0 = linear — every dB increment moves the surface equally
     *  1.5 = mild curve — recommended; quiet stays quiet, loud pops
     *  2.0 = quadratic — only the loudest beats produce tall spikes
     *  3.0 = cubic — near-silence floor, very dramatic peak spikes
     * </pre>
     */
    private static final float PUSH_CURVE  = 1.5f;

    // ── Colour modulation ───────────────────────────────────────────────────

    /**
     * Maximum additive brightness multiplier at full band amplitude.
     *
     * <p>The base depth colour (red→green→blue by distance) is multiplied by
     * {@code (1 + bandValue × COLOR_BOOST)}, so active audio columns glow
     * brighter than their silent neighbours.
     *
     * <pre>
     *  0.0 = no colour change; purely geometric displacement
     *  0.5 = subtle glow — recommended as a complement to MAX_PUSH
     *  1.0 = doubles brightness at full amplitude
     *  2.0 = very bright; good contrast in dark rooms
     * </pre>
     */
    private static final float COLOR_BOOST = 0.8f;

    /**
     * How much the colour hue shifts toward white (desaturates) in response
     * to audio.  Set to 0 for pure brightness-only modulation.
     *
     * <pre>
     *  0.0 = no desaturation (recommended — preserves depth colour coding)
     *  0.3 = slight whitening of loud columns
     *  0.7 = strong wash-out; loud areas nearly white
     * </pre>
     */
    private static final float WHITE_BLEND = 0.15f;

    // ── Depth colour ramp (inherited from DepthVisualizer) ──────────────────

    /**
     * Nearest depth distance (metres) that maps to pure red in the
     * red→green→blue distance gradient.
     */
    private static final float DEPTH_NEAR  = 0.5f;

    /**
     * Farthest depth distance (metres) that maps to pure blue.
     * Pixels beyond this distance are clamped to blue.
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

    /**
     * Pre-computed band index for every depth pixel column.
     * {@code columnBand[c]} = index into {@link FFTAnalyzer#getBandValues()}
     * for all pixels in column {@code c}.
     */
    private final int[] columnBand = new int[DEPTH_W];

    // ═══════════════════════════════════════════════════════════════════════
    //  Frame-dedup sentinel
    // ═══════════════════════════════════════════════════════════════════════

    private float[] lastXYZ;

    // ═══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void create() {
        // Pre-compute column → band lookup so fillVertices() stays branchless
        buildColumnBandMap();

        // GPU mesh
        vertices  = new float[POINT_COUNT * FLOATS_PER_VERTEX];
        pointMesh = new Mesh(
            Mesh.VertexDataType.VertexArray, false,
            POINT_COUNT, 0,
            new VertexAttribute(VertexAttributes.Usage.Position,      3, "a_position"),
            new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, "a_color"));

        buildShader();

        skeletonOverlay = new SkeletonVisualizer2D();
        orbit.init(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 0.01f, 50f);

        // Audio — start WASAPI loopback; gracefully continues without it
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

        // Update FFT analysis once per frame (non-blocking read from ring buffer)
        float[] bandValues = null;
        if (audio.isRunning()) {
            fft.analyze(audio.getSamples());
            bandValues = fft.getBandValues();
        }

        float[] xyz = kinect.getDepthXYZ();
        if (xyz != null && (xyz != lastXYZ || bandValues != null)) {
            // Always re-fill when audio is live (band values change every frame),
            // but also dedup depth frames when audio is silent/absent.
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
    //  Band-map initialisation
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Pre-computes the frequency band index for every depth pixel column
     * using a linear (left-to-right) layout:
     *
     * <ul>
     *   <li>Column 0 (leftmost) → band 0 (lowest frequencies, sub-bass)</li>
     *   <li>Column DEPTH_W-1 (rightmost) → band {@value #AUDIO_BANDS}-1
     *       (highest frequencies, treble)</li>
     * </ul>
     *
     * This mirrors a standard horizontal spectrum-analyser display, so the
     * viewer can read frequency left-to-right while simultaneously watching
     * the depth cloud react.
     */
    private void buildColumnBandMap() {
        for (int c = 0; c < DEPTH_W; c++) {
            columnBand[c] = Math.min(AUDIO_BANDS - 1,
                (int)((float) c / DEPTH_W * AUDIO_BANDS));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Point-cloud geometry
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Packs {@code (x, y, z', r, g, b, a)} into {@link #vertices} for every
     * depth pixel, where {@code z'} incorporates the audio displacement.
     *
     * <p><b>Z displacement formula</b>:
     * <pre>
     *   push  = bandValue ^ PUSH_CURVE × MAX_PUSH
     *   z'    = −kinectZ + push
     * </pre>
     * {@code kinectZ} is negated to match the standard libGDX right-handed
     * camera convention used by all 3-D modes.  Adding {@code push} then
     * moves the pixel toward the viewer (positive world-Z direction).
     *
     * <p><b>Colour formula</b>:
     * <pre>
     *   brightness multiplier = 1 + bandValue × COLOR_BOOST
     *   final colour = lerp(base depth colour × brightness, WHITE, WHITE_BLEND × bandValue)
     * </pre>
     *
     * <p>Pixels with {@code kinectZ ≤ 0} (no valid depth reading) are
     * assigned {@code alpha = 0} and discarded by the shader.
     *
     * @param xyz        Kinect depth XYZ array — {@code DEPTH_W × DEPTH_H × 3} floats.
     * @param bandValues Normalised FFT band magnitudes {@code [0..1]}, length
     *                   {@value #AUDIO_BANDS}, or {@code null} when audio is unavailable.
     */
    private void fillVertices(float[] xyz, float[] bandValues) {
        int vi = 0;
        for (int i = 0; i < POINT_COUNT; i++) {
            int b = i * 3;

            // Pixel column drives band selection
            int   col  = i % DEPTH_W;
            float band = (bandValues != null) ? bandValues[columnBand[col]] : 0f;

            // Compute Z displacement: bandValue is curved then scaled
            float push = (float) Math.pow(band, PUSH_CURVE) * MAX_PUSH;

            float x = xyz[b];
            float y = xyz[b + 1];
            float z = xyz[b + 2];

            // World X and Y unchanged; negate Z and add audio protrusion
            vertices[vi++] = x;
            vertices[vi++] = y;
            vertices[vi++] = -z + push;   // negative Kinect Z → positive world Z, then push out

            if (z <= 0f) {
                // No valid depth — invisible vertex
                vertices[vi++] = 0f;
                vertices[vi++] = 0f;
                vertices[vi++] = 0f;
                vertices[vi++] = 0f; // alpha = 0 → discarded by shader
            } else {
                // Base depth colour: red (near) → green → blue (far)
                float t = MathUtils.clamp((z - DEPTH_NEAR) / (DEPTH_FAR - DEPTH_NEAR), 0f, 1f);
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

                // Brightness boost proportional to audio energy
                float brightness = 1f + band * COLOR_BOOST;
                r  = Math.min(1f, r  * brightness);
                g  = Math.min(1f, g  * brightness);
                bl = Math.min(1f, bl * brightness);

                // Optional white-blend: desaturate loud columns slightly
                float wb = band * WHITE_BLEND;
                r  = r  + (1f - r)  * wb;
                g  = g  + (1f - g)  * wb;
                bl = bl + (1f - bl) * wb;

                vertices[vi++] = r;
                vertices[vi++] = g;
                vertices[vi++] = bl;
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
