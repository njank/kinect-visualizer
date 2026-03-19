package at.njank.kinect;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import edu.ufl.digitalworlds.j4k.Skeleton;

import static at.njank.kinect.KinectManager.DEPTH_H;
import static at.njank.kinect.KinectManager.DEPTH_W;
import static at.njank.kinect.SkeletonConstants.*;

/**
 * Visualizer mode 5 - "Depth".
 *
 * <p>Renders every Kinect v2 depth pixel as a coloured 3-D point, graded
 * red (near) -> green -> blue (far) by metric Z distance.
 *
 * <h3>Skeleton alignment</h3>
 * The skeleton is rendered <em>in 3-D world space</em> using the same orbit
 * camera as the point cloud, so bones and joints track the cloud at every
 * zoom, pan, and orbit angle.  Bones are drawn as 3-D lines; joints are
 * projected through the camera matrix and drawn as billboard circles.
 *
 * <p>Camera controls are handled by {@link OrbitCamera}.
 */
public class DepthVisualizer implements Visualizer {

    private static final int POINT_COUNT       = DEPTH_W * DEPTH_H;
    private static final int FLOATS_PER_VERTEX = 7; // x y z  r g b a

    // Joint visual size
    private static final float JOINT_RADIUS = 9f; // screen-space pixels

    // -----------------------------------------------------------------------
    // GPU resources
    // -----------------------------------------------------------------------

    private Mesh          pointMesh;
    private float[]       vertices;
    private ShaderProgram cloudShader;

    private ShapeRenderer sr;
    /** Ortho matrix kept in sync with window size for billboard joint circles. */
    private final Matrix4 screenOrtho = new Matrix4();

    // Reusable vectors - avoids per-frame allocation
    private final Vector3 tmpA = new Vector3();
    private final Vector3 tmpB = new Vector3();

    // -----------------------------------------------------------------------
    // Camera - same defaults as AR, both use negated-Z coordinate space
    // -----------------------------------------------------------------------

    private final OrbitCamera orbit =
        new OrbitCamera(0f, -10f, 2.0f, 0f, 0.3f, -2f);

    // -----------------------------------------------------------------------
    // Frame-dedup sentinel
    // -----------------------------------------------------------------------

    private float[] lastXYZ;

    /** Whether to draw the skeleton overlay.  Toggled by the S key (default off). */
    private boolean skeletonEnabled = false;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void create() {
        int w = Gdx.graphics.getWidth();
        int h = Gdx.graphics.getHeight();

        vertices  = new float[POINT_COUNT * FLOATS_PER_VERTEX];
        pointMesh = new Mesh(
            Mesh.VertexDataType.VertexArray, false,
            POINT_COUNT, 0,
            new VertexAttribute(VertexAttributes.Usage.Position,      3, "a_position"),
            new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, "a_color"));

        buildShader();

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

        Gdx.gl.glClearColor(0.02f, 0.02f, 0.05f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        float[] xyz = kinect.getDepthXYZ();
        if (xyz != null && xyz != lastXYZ) {
            lastXYZ = xyz;
            fillVertices(xyz);
            pointMesh.setVertices(vertices);
        }

        if (lastXYZ != null) {
            cloudShader.bind();
            cloudShader.setUniformMatrix("u_projTrans", orbit.getCamera().combined);
            pointMesh.render(cloudShader, GL20.GL_POINTS, 0, POINT_COUNT);
        }

        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);

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
        if (pointMesh   != null) pointMesh.dispose();
        if (cloudShader != null) cloudShader.dispose();
        if (sr          != null) sr.dispose();
    }

    @Override
    public void setSkeletonEnabled(boolean enabled) { skeletonEnabled = enabled; }

    @Override
    public boolean isSkeletonEnabled() { return skeletonEnabled; }

    @Override
    public InputProcessor getInputProcessor() { return orbit.getInputProcessor(); }

    /** Returns the orbit camera so callers can save/restore its state. */
    public OrbitCamera getOrbit() { return orbit; }

    @Override
    public void resetCamera() { orbit.reset(); }

    // -----------------------------------------------------------------------
    // 3-D skeleton overlay
    // -----------------------------------------------------------------------

    /**
     * Renders skeleton joints and bones in 3-D world space using the same
     * orbit camera as the point cloud.
     *
     * <p>Bones are drawn as 3-D lines.  Joints are projected through the
     * camera matrix and drawn as billboard circles in screen space.
     */
    private void draw3DSkeleton(Skeleton[] skeletons) {
        if (skeletons == null) return;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // -- Bones: 3-D lines in world space --
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

        // Joints: project to screen, draw billboard circles
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

    /**
     * Packs (x, y, -z, r, g, b, a) per pixel.
     * Colour is graded red -> green -> blue over 0.5 m - 5.0 m.
     * Pixels with z <= 0 get alpha = 0 and are discarded by the shader.
     */
    private void fillVertices(float[] xyz) {
        int vi = 0;
        for (int i = 0; i < POINT_COUNT; i++) {
            int b = i * 3;
            vertices[vi++] =  xyz[b];
            vertices[vi++] =  xyz[b + 1];
            vertices[vi++] = -xyz[b + 2]; // negate Z

            float z = xyz[b + 2];
            if (z <= 0f) {
                vertices[vi++] = 0f;
                vertices[vi++] = 0f;
                vertices[vi++] = 0f;
                vertices[vi++] = 0f; // alpha=0 -> discard
            } else {
                float t = MathUtils.clamp((z - 0.5f) / 4.5f, 0f, 1f);
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
                vertices[vi++] = r;
                vertices[vi++] = g;
                vertices[vi++] = bl;
                vertices[vi++] = 1f;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Shader
    // -----------------------------------------------------------------------

    private void buildShader() {
        String vert =
            "attribute vec3 a_position;\n" +
            "attribute vec4 a_color;\n" +
            "uniform mat4 u_projTrans;\n" +
            "varying vec4 v_color;\n" +
            "void main() {\n" +
            "    v_color = a_color;\n" +
            "    gl_Position  = u_projTrans * vec4(a_position, 1.0);\n" +
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
            throw new RuntimeException("Depth cloud shader:\n" + cloudShader.getLog());
    }
}
