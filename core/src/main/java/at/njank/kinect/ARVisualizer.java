package at.njank.kinect;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import edu.ufl.digitalworlds.j4k.Skeleton;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static at.njank.kinect.KinectManager.DEPTH_H;
import static at.njank.kinect.KinectManager.DEPTH_W;
import static at.njank.kinect.SkeletonConstants.*;

/**
 * Visualizer mode 2 - "AR" (Augmented Reality).
 *
 * <p>Renders the Kinect v2 colour stream as a UV-mapped 3-D point cloud:
 * every depth pixel is placed at its metric (x, y, z) world position and
 * coloured with the corresponding colour-image texel.
 *
 * <h3>Overlay modes (press O to cycle)</h3>
 * <ul>
 *   <li><b>NONE</b> - pure AR: camera texture on point cloud, no extra overlay.</li>
 *   <li><b>AUDIO</b> - spectral tint from system audio layered over the camera
 *       colour (uses the same FFT pipeline as {@link AudioVisualizer} but without
 *       Z displacement so the cloud geometry stays stable).</li>
 *   <li><b>SCREEN_ADD</b> - Linear Dodge (Add): screen adds luminance to the AR
 *       cloud (GL_FUNC_ADD, src x alpha + dst).</li>
 *   <li><b>SCREEN_SUBTRACT</b> - Subtract: screen subtracts luminance from the
 *       cloud (GL_FUNC_REVERSE_SUBTRACT, dst - src x alpha).</li>
 * </ul>
 *
 * <h3>Screen projection toggle (P key)</h3>
 * When {@code screenProjected} is enabled and a SCREEN_ADD or SCREEN_SUBTRACT
 * overlay is active, the screen texture is applied via a <em>second render pass
 * on the same 3-D point-cloud mesh</em> using projective texture mapping:
 * <pre>
 *   screenUV.x = clipPos.x / clipPos.w * 0.5 + 0.5
 *   screenUV.y = fixedClip.y / fixedClip.w * 0.5 + 0.5
 * </pre>
 * Each depth point samples the screen at the screen-space pixel it projects to,
 * so the screen content conforms exactly to the 3-D geometry rather than sitting
 * as a flat 2-D quad in front of it.  The SCREEN_ADD / SCREEN_SUBTRACT blend
 * equations still apply, so the camera colours beneath are never fully replaced.
 * When {@code screenProjected} is false (default) the existing 2-D quad path is used.
 *
 * <h3>Vertex layout</h3>
 * The mesh always uses a 9-float-per-vertex layout:
 * {@code x y z  u v  tintR tintG tintB  tintWeight}.
 * In NONE and SCREEN modes {@code tintWeight = 0} so the fragment shader outputs
 * pure camera colour.  In AUDIO mode {@code tintWeight > 0} drives a
 * {@code mix(camColor, tintColor, tintWeight)} in the fragment shader.
 *
 * <h3>Skeleton alignment</h3>
 * The skeleton is rendered in 3-D world space using the same orbit camera so
 * it tracks the point cloud at every zoom, pan, and orbit angle.
 *
 * <p>Camera controls are handled by {@link OrbitCamera}.
 */
public class ARVisualizer implements Visualizer {

    // -----------------------------------------------------------------------
    // Overlay mode
    // -----------------------------------------------------------------------

    /**
     * Active overlay composited on top of the AR point cloud.
     * Cycled with the O key.
     */
    public enum Overlay {
        /** Pure AR - no overlay, camera colours only. */
        NONE,
        /**
         * Spectral tint from system audio (same pipeline as AudioVisualizer
         * but without Z displacement so the cloud geometry is stable).
         */
        AUDIO,
        /**
         * Screen overlay - Linear Dodge (Add) blend: src x alpha + dst.
         * Bright areas of the captured screen add luminance to the AR cloud
         * without replacing camera pixel colours.
         * GL equation: GL_FUNC_ADD, srcFactor=SRC_ALPHA, dstFactor=ONE.
         */
        SCREEN_ADD,
        /**
         * Screen overlay - Subtract blend: dst - src x alpha.
         * The captured screen subtracts luminance from the AR cloud, darkening
         * regions that are bright in the captured frame.
         * GL equation: GL_FUNC_REVERSE_SUBTRACT, srcFactor=SRC_ALPHA, dstFactor=ONE.
         */
        SCREEN_SUBTRACT
    }

    private Overlay overlay = Overlay.NONE;

    // -----------------------------------------------------------------------
    // Audio overlay constants (identical to AudioVisualizer)
    // -----------------------------------------------------------------------

    /** FFT window size in samples - must be a power of 2. */
    private static final int   FFT_SIZE          = 2048;
    /** Number of frequency bands for the radial audio mapping. */
    private static final int   AUDIO_BANDS       = 32;
    /** Depth nearest to the camera (full audio effect). */
    private static final float DEPTH_NEAR        = 0.5f;
    /** Depth farthest from the camera (zero audio effect). */
    private static final float DEPTH_FAR         = 5.0f;
    /** Falloff exponent: (1-t)^DEPTH_SCALE_CURVE gives near-surface dominance. */
    private static final float DEPTH_SCALE_CURVE = 2.0f;
    /** Maximum tint blend weight at peak amplitude. 0=no tint, 1=full tint colour. */
    private static final float BAND_TINT_STRENGTH = 0.35f;

    // -----------------------------------------------------------------------
    // Geometry
    // -----------------------------------------------------------------------

    private static final int POINT_COUNT = DEPTH_W * DEPTH_H;

    // Vertex layout: x y z  u v  tintR tintG tintB  tintWeight
    // tintWeight=0 -> pure camera colour; tintWeight>0 -> mix with tint colour
    private static final int FLOATS_PER_VERTEX = 9;

    // Joint visual sizes
    private static final float JOINT_RADIUS = 9f;  // screen-space pixels

    // Alpha applied to the screen overlay in both SCREEN_ADD and SCREEN_SUBTRACT modes.
    // Higher values = stronger screen effect; lower = subtler blend with camera colours.
    private static final float SCREEN_OVERLAY_ALPHA = 0.55f;

    // -----------------------------------------------------------------------
    // GPU resources - point cloud
    // -----------------------------------------------------------------------

    private Pixmap  bgPixmap;
    private Texture bgTexture;

    private Mesh          uvMesh;
    private float[]       vertices;
    private ShaderProgram uvShader;

    // -----------------------------------------------------------------------
    // GPU resources - screen overlay
    // -----------------------------------------------------------------------

    /** Shared screen capture - provided by Main, not owned here. */
    private final ScreenCapture screenCapture;

    /**
     * Fullscreen quad mesh used for the 2-D flat screen overlay path.
     * Two triangles covering NDC [-1,1], with per-vertex UV accounting
     * for DXGI's top-down pixel order (V=0 at top, V=1 at bottom).
     * Using a raw mesh instead of SpriteBatch ensures the blend equation
     * set by the caller is never overwritten by SpriteBatch internals.
     */
    private Mesh screenQuad;
    /** Identity-like ortho matrix mapping NDC directly - stays constant. */
    private final Matrix4 ndcIdentity = new Matrix4();

    /**
     * Shader for the 2-D fullscreen quad screen overlay path.
     * Passes NDC (x, y) vertices directly as clip-space position and samples
     * the screen texture at the per-vertex UVs.
     * Kept separate from screenProjShader so the projective shader can declare
     * its own attribute layout without conflicting with the quad's layout.
     */
    private ShaderProgram screenQuadShader;

    /**
     * Shader for the 3-D projective screen pass.
     * Renders the same point-cloud mesh a second time, sampling the screen
     * texture at projective UVs derived from each point's clip-space position.
     * Only allocated and used when the SCREEN overlay is active.
     */
    private ShaderProgram screenProjShader;

    /**
     * The orbit camera's combined matrix captured once at the default
     * (front-facing) position.  Used as the fixed projector for
     * {@link #screenProjShader}: UV coordinates are always computed from this
     * viewpoint regardless of where the user has orbited the camera.
     * This makes the screen image appear "painted" onto the 3-D surface from
     * a fixed direction - rotating the orbit camera reveals the projection.
     */
    private final Matrix4 fixedProjMatrix = new Matrix4();

    // -----------------------------------------------------------------------
    // GPU resources - skeleton
    // -----------------------------------------------------------------------

    private ShapeRenderer sr;
    /** Ortho matrix for billboard joint circles - updated in resize(). */
    private final Matrix4 screenOrtho = new Matrix4();
    /** Reusable joint vectors - avoids per-frame allocation. */
    private final Vector3 tmpA = new Vector3();
    private final Vector3 tmpB = new Vector3();

    // -----------------------------------------------------------------------
    // Camera
    // -----------------------------------------------------------------------

    // yaw=0 -> camera on +Z side, front-facing
    // pitch=-10 -> gentle downward tilt
    // zoom=2 -> close enough to fill the frame
    // panY=0.3 -> look-target at roughly chest height
    // lookAtZ=-2 -> scene centred at z~-2 in world space
    private final OrbitCamera orbit =
        new OrbitCamera(0f, -10f, 2.0f, 0f, 0.3f, -2f);

    // -----------------------------------------------------------------------
    // Audio pipeline (used by Overlay.AUDIO)
    // -----------------------------------------------------------------------

    private WasapiLoopbackCapture audioCapture;
    private FFTAnalyzer           fft;

    /**
     * Pre-computed frequency band index for every depth pixel.
     * Keyed to radial distance from the depth image centre so concentric
     * rings of pixels react to the same frequency band.
     * Filled once in create(); O(1) lookup per pixel per frame.
     */
    private final int[]   pixelBand   = new int[POINT_COUNT];

    /**
     * Pre-computed normalised radial distance [0..1] for every depth pixel,
     * parallel to pixelBand[].  Used to derive the spectral hue tint colour.
     */
    private final float[] pixelRadial = new float[POINT_COUNT];

    /**
     * Current FFT band values [0..1] - updated once per frame when audio overlay
     * is active.  Pre-allocated to avoid GC at 60 Hz; zeroed when audio is off.
     */
    private final float[] bandValues  = new float[AUDIO_BANDS];

    // -----------------------------------------------------------------------
    // Frame-dedup sentinels
    // -----------------------------------------------------------------------

    private byte[]  lastColorFrame;
    private float[] lastXYZ;
    private float[] lastUV;

    // -----------------------------------------------------------------------
    // Other state
    // -----------------------------------------------------------------------

    /** Whether to draw the skeleton overlay.  Toggled by the S key (default off). */
    private boolean skeletonEnabled = false;

    /**
     * When true and a SCREEN overlay is active, the screen texture is projected
     * onto the 3-D point cloud instead of being drawn as a 2-D fullscreen quad.
     * Toggled by the P key (default off).
     */
    private boolean screenProjected = false;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * @param screenCapture shared {@link ScreenCapture} (may be null if screen
     *                      capture is unavailable; SCREEN overlay will no-op)
     */
    public ARVisualizer(ScreenCapture screenCapture) {
        this.screenCapture = screenCapture;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void create() {
        int w = Gdx.graphics.getWidth();
        int h = Gdx.graphics.getHeight();

        // Camera colour texture (BGRA -> RGBA uploaded each frame)
        bgPixmap  = new Pixmap(COLOR_W, COLOR_H, Pixmap.Format.RGBA8888);
        bgTexture = new Texture(bgPixmap);
        bgTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);

        // Point-cloud mesh: 9 floats per vertex for all overlay modes
        vertices = new float[POINT_COUNT * FLOATS_PER_VERTEX];
        uvMesh   = new Mesh(
            Mesh.VertexDataType.VertexArray, false,
            POINT_COUNT, 0,
            new VertexAttribute(VertexAttributes.Usage.Position,           3, "a_position"),
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"),
            new VertexAttribute(VertexAttributes.Usage.Generic,            3, "a_tintColor"),
            new VertexAttribute(VertexAttributes.Usage.Generic,            1, "a_tintWeight"));

        buildUvShader();
        buildScreenQuadShader();
        buildScreenProjShader();

        sr = new ShapeRenderer();
        screenOrtho.setToOrtho2D(0, 0, w, h);

        // Fullscreen quad: two triangles in NDC space [-1,1].
        // UV V=0 at top, V=1 at bottom - DXGI row-0 (screen top) is first
        // in the buffer, so V=0 must map to the top of the image.
        // Vertex layout: x, y (NDC), u, v
        screenQuad = new Mesh(true, 4, 6,
            new VertexAttribute(VertexAttributes.Usage.Position,           2, "a_position"),
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"));
        screenQuad.setVertices(new float[]{
            // x      y     u     v
            -1f,  -1f,  0f,   1f,   // bottom-left  -> V=1 (image bottom)
             1f,  -1f,  1f,   1f,   // bottom-right -> V=1
             1f,   1f,  1f,   0f,   // top-right    -> V=0 (image top)
            -1f,   1f,  0f,   0f    // top-left     -> V=0
        });
        screenQuad.setIndices(new short[]{ 0, 1, 2, 2, 3, 0 });
        // ndcIdentity passes x,y straight through as clip coords (w=1, z=0)
        ndcIdentity.idt();

        orbit.init(w, h, 0.01f, 50f);
        // Capture the default-position camera matrix as the fixed projector.
        // This is done once here; it never changes so rotating the orbit camera
        // does not affect where the screen image is projected on the geometry.
        fixedProjMatrix.set(orbit.getCamera().combined);

        // Pre-compute radial band map for audio overlay
        buildPixelBandMap();

        // Attempt to start audio capture for AUDIO overlay
        audioCapture = new WasapiLoopbackCapture();
        if (!audioCapture.start()) {
            Gdx.app.log("ARVisualizer",
                "WasapiLoopback.dll not available - audio overlay disabled.");
        }
        fft = new FFTAnalyzer(FFT_SIZE, AUDIO_BANDS);
    }

    // -----------------------------------------------------------------------
    // Visualizer contract
    // -----------------------------------------------------------------------

    @Override
    public void render(KinectManager kinect) {
        orbit.handleInput();

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        // Upload camera colour frame (BGRA -> RGBA) when it changes
        byte[] colorFrame = kinect.getColorFrame();
        if (colorFrame != null && colorFrame != lastColorFrame) {
            lastColorFrame = colorFrame;
            uploadBgra(colorFrame);
        }

        // ---- Audio band values (used in AUDIO overlay, zeroed otherwise) ----
        if (overlay == Overlay.AUDIO && audioCapture != null && audioCapture.isRunning()) {
            fft.analyze(audioCapture.getSamples());
            System.arraycopy(fft.getBandValues(), 0, bandValues, 0, AUDIO_BANDS);
        } else {
            Arrays.fill(bandValues, 0f);
        }

        // ---- Rebuild point-cloud vertices ----
        // Always rebuild when audio is live (band values change every frame).
        // Dedup depth/UV frames when audio is inactive.
        float[] xyz = kinect.getDepthXYZ();
        float[] uv  = kinect.getDepthUV();
        boolean newDepth = xyz != null && uv != null
                        && (xyz != lastXYZ || uv != lastUV);
        boolean audioActive = (overlay == Overlay.AUDIO);
        if (newDepth || audioActive) {
            if (newDepth) { lastXYZ = xyz; lastUV = uv; }
            if (lastXYZ != null && lastUV != null) {
                fillVertices(lastXYZ, lastUV, (overlay == Overlay.AUDIO) ? bandValues : null);
                uvMesh.setVertices(vertices);
            }
        }

        // ---- Render point cloud ----
        if (lastXYZ != null && lastColorFrame != null) {
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            bgTexture.bind(0);
            uvShader.bind();
            uvShader.setUniformMatrix("u_projTrans", orbit.getCamera().combined);
            uvShader.setUniformi("u_texture", 0);
            uvMesh.render(uvShader, GL20.GL_POINTS, 0, POINT_COUNT);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        }

        // ---- Screen overlay (SCREEN_ADD or SCREEN_SUBTRACT) ----
        //
        // Two rendering paths are available, selected by screenProjected:
        //
        // screenProjected=false  (default) - 2-D fullscreen quad
        //   The screen texture is drawn as a flat quad over the entire viewport.
        //   Vertical flip (y+h, w, -h) corrects DXGI's top-down pixel order.
        //
        // screenProjected=true  - 3-D projective pass on the point-cloud mesh
        //   The same mesh is rendered a second time with screenProjShader, which
        //   computes UV from each point's clip-space position:
        //     screenUV = clipPos.xy/w * 0.5 + 0.5
        //   This makes the screen content conform to the 3-D geometry: points that
        //   project to the same screen pixel share the same screen sample.
        //
        // Both paths respect the SCREEN_ADD / SCREEN_SUBTRACT blend equations so
        // the underlying camera colours are never fully replaced.
        boolean isScreenOverlay = (overlay == Overlay.SCREEN_ADD
                               || overlay == Overlay.SCREEN_SUBTRACT);
        if (isScreenOverlay && screenCapture != null) {
            screenCapture.update();
            Texture screenTex = screenCapture.getTexture();
            if (screenTex != null) {
                // Set up the shared blend state for both paths
                Gdx.gl.glEnable(GL20.GL_BLEND);
                if (overlay == Overlay.SCREEN_ADD) {
                    // Linear Dodge (Add): result = dst + src * alpha
                    Gdx.gl.glBlendEquation(GL20.GL_FUNC_ADD);
                    Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
                } else {
                    // Subtract: result = dst - src * alpha
                    Gdx.gl.glBlendEquation(GL20.GL_FUNC_REVERSE_SUBTRACT);
                    Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
                }

                if (screenProjected && lastXYZ != null) {
                    // ---- 3-D projective path ----
                    // Render the point-cloud mesh again with the screen texture
                    // projected from the camera's viewpoint.  Each point samples
                    // the screen at the pixel it projects to in clip space.
                    //
                    // GL_LEQUAL is required here because the first pass (camera
                    // colours) already wrote depth values for every valid point.
                    // The default GL_LESS would reject all fragments (z == z is
                    // not strictly less-than), so nothing would be drawn.
                    // GL_LEQUAL passes fragments whose depth equals the stored
                    // value, letting this second pass composite on top of the
                    // exact same surface without any geometry duplication.
                    Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
                    Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
                    screenTex.bind(0);
                    screenProjShader.bind();
                    // u_projTrans    - live orbit matrix: moves gl_Position through 3-D space
                    // u_fixedProjTrans - frozen front-facing matrix: keeps UV fixed in world
                    screenProjShader.setUniformMatrix("u_projTrans",      orbit.getCamera().combined);
                    screenProjShader.setUniformMatrix("u_fixedProjTrans", fixedProjMatrix);
                    screenProjShader.setUniformi("u_screenTex", 0);
                    screenProjShader.setUniformf("u_alpha", SCREEN_OVERLAY_ALPHA);
                    uvMesh.render(screenProjShader, GL20.GL_POINTS, 0, POINT_COUNT);
                    // Restore the default depth function before returning to
                    // normal rendering (skeleton, HUD).
                    Gdx.gl.glDepthFunc(GL20.GL_LESS);
                    Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
                } else {
                    // ---- 2-D flat quad path ----
                    // Renders a fullscreen NDC quad with screenQuadShader.
                    // No SpriteBatch: the blend equation set above stays intact
                    // because SpriteBatch.begin() would overwrite glBlendFunc.
                    Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
                    screenTex.bind(0);
                    screenQuadShader.bind();
                    screenQuadShader.setUniformi("u_screenTex", 0);
                    screenQuadShader.setUniformf("u_alpha", SCREEN_OVERLAY_ALPHA);
                    screenQuad.render(screenQuadShader, GL20.GL_TRIANGLES);
                }

                // Restore default blend state
                Gdx.gl.glBlendEquation(GL20.GL_FUNC_ADD);
                Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                Gdx.gl.glDisable(GL20.GL_BLEND);
            }
        }

        // ---- Skeleton ----
        if (skeletonEnabled) draw3DSkeleton(kinect.getSkeletons());
    }

    @Override
    public void resize(int w, int h) {
        orbit.resize(w, h);
        screenOrtho.setToOrtho2D(0, 0, w, h);
    }

    @Override
    public void dispose() {
        if (audioCapture != null) audioCapture.stop();
        if (bgTexture    != null) bgTexture.dispose();
        if (bgPixmap     != null) bgPixmap.dispose();
        if (uvMesh       != null) uvMesh.dispose();
        if (uvShader     != null) uvShader.dispose();
        if (sr                != null) sr.dispose();
        if (screenQuad        != null) screenQuad.dispose();
        if (screenQuadShader  != null) screenQuadShader.dispose();
        if (screenProjShader  != null) screenProjShader.dispose();
        // screenCapture is owned by Main - do not dispose here
    }

    @Override
    public InputProcessor getInputProcessor() { return orbit.getInputProcessor(); }

    @Override
    public void resetCamera() { orbit.reset(); }

    @Override
    public void setSkeletonEnabled(boolean enabled) { skeletonEnabled = enabled; }

    @Override
    public boolean isSkeletonEnabled() { return skeletonEnabled; }

    // -----------------------------------------------------------------------
    // Overlay cycling (called by Main on O key press)
    // -----------------------------------------------------------------------

    /** Cycles the overlay: NONE -> AUDIO -> SCREEN_ADD -> SCREEN_SUBTRACT -> NONE. */
    public void nextOverlay() {
        Overlay[] vals = Overlay.values();
        overlay = vals[(overlay.ordinal() + 1) % vals.length];
    }

    /** Returns the currently active overlay mode. */
    public Overlay getOverlay() { return overlay; }

    /**
     * Enables or disables 3-D projective screen rendering.
     * When true and a SCREEN overlay is active, the screen texture is projected
     * onto the point-cloud mesh instead of being drawn as a 2-D quad.
     * Toggled by the P key.
     */
    public void setScreenProjected(boolean projected) { screenProjected = projected; }

    /** Returns whether 3-D projective screen rendering is enabled. */
    public boolean isScreenProjected() { return screenProjected; }

    // -----------------------------------------------------------------------
    // 3-D skeleton overlay
    // -----------------------------------------------------------------------

    /**
     * Renders skeleton joints and bones in 3-D world space using the same
     * orbit camera as the point cloud, so the skeleton tracks the cloud at
     * every zoom, pan, and orbit angle.
     *
     * <p>Bones are drawn as 3-D lines.  Joints are projected through the
     * camera matrix and drawn as billboard circles in screen space.
     */
    private void draw3DSkeleton(Skeleton[] skeletons) {
        if (skeletons == null) return;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Bones: 3-D lines in world space
        sr.setProjectionMatrix(orbit.getCamera().combined);
        sr.begin(ShapeRenderer.ShapeType.Line);
        for (int s = 0; s < skeletons.length; s++) {
            Skeleton sk = skeletons[s];
            if (sk == null) continue;
            Color col = SKELETON_COLORS[s % SKELETON_COLORS.length];
            sr.setColor(col.r, col.g, col.b, 0.9f);
            for (int[] bone : BONES) {
                if (!has3D(sk, bone[0]) || !has3D(sk, bone[1])) continue;
                toWorld(sk, bone[0], tmpA);
                toWorld(sk, bone[1], tmpB);
                sr.line(tmpA, tmpB);
            }
        }
        sr.end();

        // Joints: projected to screen space, drawn as billboard circles
        sr.setProjectionMatrix(screenOrtho);
        sr.begin(ShapeRenderer.ShapeType.Filled);
        for (int s = 0; s < skeletons.length; s++) {
            Skeleton sk = skeletons[s];
            if (sk == null) continue;
            Color col = SKELETON_COLORS[s % SKELETON_COLORS.length];
            for (int j = 0; j < JOINT_COUNT; j++) {
                if (!has3D(sk, j)) continue;
                toWorld(sk, j, tmpA);
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
    // Joint helpers
    // -----------------------------------------------------------------------

    /** Returns true if the joint has a non-zero 3-D position. */
    private static boolean has3D(Skeleton sk, int j) {
        double[] p = sk.get3DJoint(j);
        return p[0] != 0.0 || p[1] != 0.0 || p[2] != 0.0;
    }

    /**
     * Copies a joint into world space, negating Z so the scene appears in
     * front of the camera (Kinect +Z points toward the sensor).
     */
    private static void toWorld(Skeleton sk, int j, Vector3 out) {
        double[] p = sk.get3DJoint(j);
        out.set((float) p[0], (float) p[1], -(float) p[2]);
    }

    // -----------------------------------------------------------------------
    // Audio overlay - radial band map initialisation
    // -----------------------------------------------------------------------

    /**
     * Pre-computes pixelBand[] and pixelRadial[] for every depth pixel using
     * an aspect-ratio-corrected radial distance from the depth image centre.
     *
     * <p>Formula (same as AudioVisualizer):
     * <pre>
     *   cx    = DEPTH_W / 2.0   cy    = DEPTH_H / 2.0
     *   normX = (col - cx) / cx          // [-1..1]
     *   normY = (row - cy) / cy          // [-1..1]
     *   norm  = min(1, sqrt(normX^2 + normY^2) / sqrt(2))
     *   band  = clamp(floor(norm x AUDIO_BANDS), 0, AUDIO_BANDS-1)
     * </pre>
     *
     * <ul>
     *   <li>Centre pixel -> norm=0 -> band 0 (sub-bass)</li>
     *   <li>Corner pixels -> norm=1 -> band AUDIO_BANDS-1 (treble)</li>
     * </ul>
     */
    private void buildPixelBandMap() {
        final float cx     = DEPTH_W / 2.0f;
        final float cy     = DEPTH_H / 2.0f;
        final float INV_R2 = 1.0f / (float) Math.sqrt(2.0);

        for (int row = 0; row < DEPTH_H; row++) {
            float normY = (row - cy) / cy;
            for (int col = 0; col < DEPTH_W; col++) {
                float normX = (col - cx) / cx;
                float norm  = Math.min(1f,
                    (float) Math.sqrt(normX * normX + normY * normY) * INV_R2);
                int idx = row * DEPTH_W + col;
                pixelRadial[idx] = norm;
                pixelBand[idx]   = Math.min(AUDIO_BANDS - 1, (int)(norm * AUDIO_BANDS));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Point-cloud geometry
    // -----------------------------------------------------------------------

    /**
     * Packs 9 floats per depth pixel into vertices[]:
     * {@code x  y  z  u  v  tintR  tintG  tintB  tintWeight}.
     *
     * <p>When {@code bandValues} is null (NONE or SCREEN overlays), tintWeight
     * is 0 for all pixels and the fragment shader outputs pure camera colour.
     *
     * <p>When {@code bandValues} is non-null (AUDIO overlay), each pixel
     * receives a spectral hue tint weighted by:
     * {@code band x BAND_TINT_STRENGTH x depthScale}, where depthScale gives
     * near pixels the strongest tint and far pixels none.
     *
     * <p>Invalid pixels (UV out of range or z <= 0) receive sentinel UV (-1)
     * which the shader detects and discards.
     *
     * @param xyz        Kinect depth XYZ - DEPTH_W x DEPTH_H x 3 floats
     * @param uv         Kinect colour UVs - DEPTH_W x DEPTH_H x 2 floats
     * @param bandValues FFT magnitudes [0..1] length AUDIO_BANDS, or null
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

            vertices[vi++] = x;
            vertices[vi++] = y;
            vertices[vi++] = -z; // negate Kinect Z -> positive world Z

            // UV: negative sentinel tells the shader to discard this point
            vertices[vi++] = invalid ? -1f : u;
            vertices[vi++] = invalid ? -1f : v;

            // Spectral hue tint (AUDIO overlay) or zero (NONE / SCREEN)
            float tr = 0f, tg = 0f, tb = 0f, tintWeight = 0f;
            if (bandValues != null && !invalid) {
                // Band value and radial position for this pixel
                float band       = bandValues[pixelBand[i]];
                float radialNorm = pixelRadial[i];

                // Depth-based impact scale: near pixels get full tint, far get none
                float t          = MathUtils.clamp(
                    (z - DEPTH_NEAR) / (DEPTH_FAR - DEPTH_NEAR), 0f, 1f);
                float depthScale = (float) Math.pow(1f - t, DEPTH_SCALE_CURVE);

                // Spectral hue palette (centre = warm bass, edge = cool treble)
                //   radialNorm 0.0 (centre) -> warm orange-red (1.00, 0.35, 0.00)
                //   radialNorm 0.5 (mid)    -> neutral green   (0.10, 0.90, 0.10)
                //   radialNorm 1.0 (corner) -> cool cyan-blue  (0.00, 0.60, 1.00)
                if (radialNorm < 0.5f) {
                    float s = radialNorm / 0.5f;
                    tr = 1.00f - s * 0.90f;
                    tg = 0.35f + s * 0.55f;
                    tb = 0.00f + s * 0.10f;
                } else {
                    float s = (radialNorm - 0.5f) / 0.5f;
                    tr = 0.10f - s * 0.10f;
                    tg = 0.90f - s * 0.30f;
                    tb = 0.10f + s * 0.90f;
                }
                // Tint weight: 0 at silence/far, up to BAND_TINT_STRENGTH at peak
                tintWeight = band * BAND_TINT_STRENGTH * depthScale;
            }

            vertices[vi++] = tr;
            vertices[vi++] = tg;
            vertices[vi++] = tb;
            vertices[vi++] = tintWeight;
        }
    }

    // -----------------------------------------------------------------------
    // Shader
    // -----------------------------------------------------------------------

    /**
     * Builds the UV + tint shader used for all three overlay modes.
     *
     * <p>Vertex stage passes UV coordinates and tint parameters to the
     * fragment stage.
     *
     * <p>Fragment stage:
     * <ol>
     *   <li>Discards points whose UV x-component is negative (invalid depth).</li>
     *   <li>Samples the Kinect camera texture at v_uv.</li>
     *   <li>Mixes the sampled colour toward v_tintColor by v_tintWeight using
     *       GLSL mix().  When tintWeight=0 (NONE and SCREEN overlays), the
     *       output is pure camera colour.</li>
     * </ol>
     */
    private void buildUvShader() {
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
            // tintWeight=0 -> pure camera colour (identical to original AR mode)
            "    vec3 rgb = mix(camColor.rgb, v_tintColor, v_tintWeight);\n" +
            "    gl_FragColor = vec4(rgb, 1.0);\n" +
            "}\n";

        uvShader = new ShaderProgram(vert, frag);
        if (!uvShader.isCompiled())
            throw new RuntimeException("AR UV shader:\n" + uvShader.getLog());
    }

    // -----------------------------------------------------------------------
    // Fullscreen quad shader (2-D flat screen overlay path)
    // -----------------------------------------------------------------------

    /**
     * Builds the simple shader used by the 2-D flat screen overlay path.
     *
     * <p>Vertex stage: (x, y) are already in NDC space [-1, 1], so they are
     * passed through as clip-space position directly (w=1).  UV coordinates
     * are read from the mesh verbatim (V=0 at image top, V=1 at bottom).
     *
     * <p>Fragment stage: samples u_screenTex at v_uv and outputs with u_alpha
     * so the caller's blend equation (GL_FUNC_ADD or GL_FUNC_REVERSE_SUBTRACT)
     * composites it correctly over the camera colours in the framebuffer.
     */
    private void buildScreenQuadShader() {
        String vert =
            "attribute vec2 a_position;\n" +
            "attribute vec2 a_texCoord0;\n" +
            "varying vec2 v_uv;\n" +
            "void main() {\n" +
            // NDC coords passed straight through; w=1 so no perspective divide
            "    gl_Position = vec4(a_position, 0.0, 1.0);\n" +
            "    v_uv = a_texCoord0;\n" +
            "}\n";

        String frag =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec2 v_uv;\n" +
            "uniform sampler2D u_screenTex;\n" +
            "uniform float     u_alpha;\n" +
            "void main() {\n" +
            "    vec4 col = texture2D(u_screenTex, v_uv);\n" +
            "    gl_FragColor = vec4(col.rgb, u_alpha);\n" +
            "}\n";

        screenQuadShader = new ShaderProgram(vert, frag);
        if (!screenQuadShader.isCompiled())
            throw new RuntimeException("Screen quad shader:\n" + screenQuadShader.getLog());
    }

    // -----------------------------------------------------------------------
    // Projective screen shader
    // -----------------------------------------------------------------------

    /**
     * Builds the shader used for 3-D projective screen rendering.
     *
     * <p>This shader renders the same point-cloud mesh a second time (after the
     * base camera-colour pass) to apply the screen texture in 3-D space.
     *
     * <p>Vertex stage: each point's clip-space position is used to compute the
     * screen-texture UV:
     * <pre>
     *   clipPos    = u_projTrans * vec4(a_position, 1.0)
     *   screenUV.x = clipPos.x / clipPos.w * 0.5 + 0.5
     *   screenUV.y = fixedClip.y / fixedClip.w * 0.5 + 0.5
     * </pre>
     * Dividing by {@code clipPos.w} (perspective divide) ensures near and far
     * points sample the correct pixel - without it the sampling drifts with depth.
     *
     * <p>Fragment stage: samples the screen texture at {@code v_screenUV} and
     * outputs the colour with {@code u_alpha} so the blend equation applied by
     * the caller (GL_FUNC_ADD or GL_FUNC_REVERSE_SUBTRACT) can composite it
     * over the camera colours already in the framebuffer.
     *
     * <p>Invalid depth pixels (sentinel UV.x < 0) are discarded so only pixels
     * with valid Kinect depth data receive the screen overlay.
     */
    private void buildScreenProjShader() {
        String vert =
            "attribute vec3  a_position;\n" +
            "attribute vec2  a_texCoord0;\n" +
            // The shader uses two separate transform matrices:
            //   u_projTrans      - live orbit camera: determines gl_Position
            //   u_fixedProjTrans - frozen front-facing camera: determines screenUV
            // Splitting them is the key to the effect: the point moves through 3-D
            // space as the user orbits, but its UV coordinate is always derived from
            // where it sits relative to the fixed front-facing projection, so the
            // screen image stays 'painted onto the surface' rather than tracking
            // the viewer's eye.
            // Remaining vertex attributes (tintColor, tintWeight) are present in
            // the VBO but unused here; the driver skips them via stride.
            "uniform mat4  u_projTrans;\n" +
            "uniform mat4  u_fixedProjTrans;\n" +
            "varying vec2  v_screenUV;\n" +
            "varying float v_valid;\n" +
            "void main() {\n" +
            // Discard invalid depth pixels (sentinel: UV.x < 0)
            "    v_valid = (a_texCoord0.x < 0.0) ? 0.0 : 1.0;\n" +
            // Live projection: where this point appears in the current view
            "    gl_Position  = u_projTrans * vec4(a_position, 1.0);\n" +
            "    gl_PointSize = 2.5;\n" +
            // Fixed projection: where this point appeared in the front-facing view.
            // Perspective divide maps clip-space to NDC [-1,1], then remap to [0,1].
            // No extra Y flip: the screenQuad mesh already encodes V=0 at the
            // top and V=1 at the bottom (matching DXGI's top-down row order).
            // This projective shader samples the same texture directly, so
            // NDC Y=+1 (viewport top) -> V=1 (image bottom) would be wrong;
            // leave as-is and let the fixed projector handle alignment.
            "    vec4 fixedClip = u_fixedProjTrans * vec4(a_position, 1.0);\n" +
            "    float ndcX     = fixedClip.x / fixedClip.w;\n" +
            "    float ndcY     = fixedClip.y / fixedClip.w;\n" +
            "    v_screenUV.x   = ndcX * 0.5 + 0.5;\n" +
            "    v_screenUV.y   = ndcY * 0.5 + 0.5;\n" +
            "}\n";

        String frag =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec2  v_screenUV;\n" +
            "varying float v_valid;\n" +
            "uniform sampler2D u_screenTex;\n" +
            "uniform float     u_alpha;\n" +
            "void main() {\n" +
            "    if (v_valid < 0.5) discard;\n" +
            "    vec4 col = texture2D(u_screenTex, v_screenUV);\n" +
            // Output screen colour with controlled alpha so the blend equation
            // (set by the caller) composites it correctly over camera colours.
            "    gl_FragColor = vec4(col.rgb, u_alpha);\n" +
            "}\n";

        screenProjShader = new ShaderProgram(vert, frag);
        if (!screenProjShader.isCompiled())
            throw new RuntimeException("Screen projection shader:\n" + screenProjShader.getLog());
    }

    // -----------------------------------------------------------------------
    // Colour-frame upload (BGRA -> RGBA)
    // -----------------------------------------------------------------------

    /**
     * Swaps Kinect BGRA bytes into RGBA order and uploads to bgTexture.
     * Called at most once per new colour frame (deduped by reference comparison).
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
}
