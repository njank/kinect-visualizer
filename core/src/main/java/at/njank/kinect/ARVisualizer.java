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
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import edu.ufl.digitalworlds.j4k.Skeleton;

import java.nio.ByteBuffer;

import static at.njank.kinect.KinectManager.DEPTH_H;
import static at.njank.kinect.KinectManager.DEPTH_W;
import static at.njank.kinect.SkeletonConstants.*;

/**
 * Visualizer mode 4 – "AR" (Augmented Reality).
 *
 * <p>Renders the Kinect v2 colour stream as a UV-mapped 3-D point cloud:
 * every depth pixel is placed at its metric (x, y, z) world position and
 * coloured with the corresponding colour-image texel.
 *
 * <h3>Skeleton alignment</h3>
 * The skeleton is rendered <em>in 3-D world space</em> using the same orbit
 * camera as the point cloud.  Bones are drawn as 3-D lines; joints are
 * projected through the camera matrix and drawn as billboard circles in
 * screen space.  This keeps the skeleton locked to the cloud at every zoom,
 * pan, and orbit angle.
 *
 * <p>Camera controls are handled by {@link OrbitCamera}.
 */
public class ARVisualizer implements Visualizer {

    private static final int POINT_COUNT       = DEPTH_W * DEPTH_H;
    private static final int FLOATS_PER_VERTEX = 5; // x y z  u v

    // Joint and bone visual sizes
    private static final float JOINT_RADIUS    = 9f;   // screen-space pixels
    private static final float BONE_WIDTH      = 0.012f; // world-space metres

    // -----------------------------------------------------------------------
    // GPU resources
    // -----------------------------------------------------------------------

    private Pixmap  bgPixmap;
    private Texture bgTexture;

    private Mesh          uvMesh;
    private float[]       vertices;
    private ShaderProgram uvShader;

    private ShapeRenderer sr;
    /** Ortho matrix kept in sync with window size for billboard joint circles. */
    private final Matrix4 screenOrtho = new Matrix4();

    // Reusable vectors — avoids per-frame allocation
    private final Vector3 tmpA = new Vector3();
    private final Vector3 tmpB = new Vector3();

    // -----------------------------------------------------------------------
    // Camera
    // -----------------------------------------------------------------------

    private final OrbitCamera orbit =
        new OrbitCamera(0f, -10f, 2.0f, 0f, 0.3f, -2f);

    // -----------------------------------------------------------------------
    // Frame-dedup sentinels
    // -----------------------------------------------------------------------

    private byte[]  lastColorFrame;
    private float[] lastXYZ;
    private float[] lastUV;

    /** Whether to draw the skeleton overlay.  Toggled by the S key (default off). */
    private boolean skeletonEnabled = false;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void create() {
        int w = Gdx.graphics.getWidth();
        int h = Gdx.graphics.getHeight();

        bgPixmap  = new Pixmap(COLOR_W, COLOR_H, Pixmap.Format.RGBA8888);
        bgTexture = new Texture(bgPixmap);
        bgTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);

        vertices = new float[POINT_COUNT * FLOATS_PER_VERTEX];
        uvMesh   = new Mesh(
            Mesh.VertexDataType.VertexArray, false,
            POINT_COUNT, 0,
            new VertexAttribute(VertexAttributes.Usage.Position,           3, "a_position"),
            new VertexAttribute(VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0"));

        buildUvShader();

        sr = new ShapeRenderer();
        screenOrtho.setToOrtho2D(0, 0, w, h);

        orbit.init(w, h, 0.01f, 50f);
    }

    // -----------------------------------------------------------------------
    // Visualizer
    // -----------------------------------------------------------------------

    @Override
    public void render(KinectManager kinect) {
        orbit.handleInput();

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);

        byte[] colorFrame = kinect.getColorFrame();
        if (colorFrame != null && colorFrame != lastColorFrame) {
            lastColorFrame = colorFrame;
            uploadBgra(colorFrame);
        }

        float[] xyz = kinect.getDepthXYZ();
        float[] uv  = kinect.getDepthUV();
        if (xyz != null && uv != null && (xyz != lastXYZ || uv != lastUV)) {
            lastXYZ = xyz;
            lastUV  = uv;
            fillUvVertices(xyz, uv);
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

        // Skeleton drawn in 3-D world space so it tracks the point cloud
        if (skeletonEnabled) draw3DSkeleton(kinect.getSkeletons());
    }

    @Override
    public void resize(int w, int h) {
        orbit.resize(w, h);
        screenOrtho.setToOrtho2D(0, 0, w, h);
    }

    @Override
    public void dispose() {
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

        // ── Bones: 3-D lines in world space ──
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

        // ── Joints: project to screen, draw billboard circles ──
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

    /** Returns {@code true} if the joint has a non-zero 3-D position. */
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
    // Point-cloud geometry
    // -----------------------------------------------------------------------

    private void fillUvVertices(float[] xyz, float[] uv) {
        int vi = 0;
        for (int i = 0; i < POINT_COUNT; i++) {
            int b3 = i * 3;
            int b2 = i * 2;
            vertices[vi++] =  xyz[b3];
            vertices[vi++] =  xyz[b3 + 1];
            vertices[vi++] = -xyz[b3 + 2]; // negate Z

            boolean invalid = uv[b2] < 0f || uv[b2 + 1] < 0f;
            vertices[vi++] = invalid ? -1f : uv[b2];
            vertices[vi++] = invalid ? -1f : uv[b2 + 1];
        }
    }

    // -----------------------------------------------------------------------
    // Shader
    // -----------------------------------------------------------------------

    private void buildUvShader() {
        String vert =
            "attribute vec3 a_position;\n" +
            "attribute vec2 a_texCoord0;\n" +
            "uniform mat4 u_projTrans;\n" +
            "varying vec2  v_uv;\n" +
            "varying float v_valid;\n" +
            "void main() {\n" +
            "    v_uv    = a_texCoord0;\n" +
            "    v_valid = (a_texCoord0.x < 0.0) ? 0.0 : 1.0;\n" +
            "    gl_Position  = u_projTrans * vec4(a_position, 1.0);\n" +
            "    gl_PointSize = 2.5;\n" +
            "}\n";

        String frag =
            "#ifdef GL_ES\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec2  v_uv;\n" +
            "varying float v_valid;\n" +
            "uniform sampler2D u_texture;\n" +
            "void main() {\n" +
            "    if (v_valid < 0.5) discard;\n" +
            "    gl_FragColor = texture2D(u_texture, v_uv);\n" +
            "}\n";

        uvShader = new ShaderProgram(vert, frag);
        if (!uvShader.isCompiled())
            throw new RuntimeException("AR UV shader:\n" + uvShader.getLog());
    }

    // -----------------------------------------------------------------------
    // Colour-frame upload (BGRA → RGBA)
    // -----------------------------------------------------------------------

    private void uploadBgra(byte[] bgra) {
        ByteBuffer buf = bgPixmap.getPixels();
        buf.rewind();
        for (int i = 0, n = bgra.length - 3; i < n; i += 4) {
            buf.put(bgra[i + 2]); // R ← B
            buf.put(bgra[i + 1]); // G
            buf.put(bgra[i    ]); // B ← R
            buf.put(bgra[i + 3]); // A
        }
        buf.rewind();
        bgTexture.draw(bgPixmap, 0, 0);
    }
}
