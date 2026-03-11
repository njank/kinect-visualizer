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
 * Visualizer mode 5 – "Depth".
 *
 * <p>Renders every Kinect v2 depth pixel as a coloured 3-D point, graded
 * red (near) → green → blue (far) by metric Z distance.
 *
 * <p>Camera controls are handled by {@link at.njank.kinect.OrbitCamera}.
 */
public class DepthVisualizer implements Visualizer {

    private static final int POINT_COUNT      = DEPTH_W * DEPTH_H;
    private static final int FLOATS_PER_VERTEX = 7; // x y z  r g b a

    // -----------------------------------------------------------------------
    // GPU resources
    // -----------------------------------------------------------------------

    private Mesh          pointMesh;
    private float[]       vertices;
    private ShaderProgram cloudShader;

    private SkeletonVisualizer2D skeletonOverlay;

    // -----------------------------------------------------------------------
    // Camera
    // -----------------------------------------------------------------------

    // Same defaults as AR – both share the same negated-Z coordinate space.
    private final OrbitCamera orbit =
        new OrbitCamera(0f, -10f, 2.0f, 0f, 0.3f, -2f);

    // -----------------------------------------------------------------------
    // Frame-dedup sentinel
    // -----------------------------------------------------------------------

    private float[] lastXYZ;

    // -----------------------------------------------------------------------
    // Lazy init
    // -----------------------------------------------------------------------

    @Override
    public void create() {
        vertices  = new float[POINT_COUNT * FLOATS_PER_VERTEX];
        pointMesh = new Mesh(
            Mesh.VertexDataType.VertexArray, false,
            POINT_COUNT, 0,
            new VertexAttribute(VertexAttributes.Usage.Position,      3, "a_position"),
            new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, "a_color"));

        buildShader();
        skeletonOverlay = new SkeletonVisualizer2D();
        orbit.init(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 0.01f, 50f);
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
        if (pointMesh       != null) pointMesh.dispose();
        if (cloudShader     != null) cloudShader.dispose();
        if (skeletonOverlay != null) skeletonOverlay.dispose();
    }

    @Override
    public InputProcessor getInputProcessor() { return orbit.getInputProcessor(); }

    /** Called by Main when the user presses R in Depth mode. */
    @Override
    public void resetCamera() { orbit.reset(); }

    // -----------------------------------------------------------------------
    // Point-cloud geometry
    // -----------------------------------------------------------------------

    /**
     * Packs (x, y, −z, r, g, b, a) per pixel.
     * Colour is graded red→green→blue over 0.5 m – 5.0 m.
     * Pixels with z ≤ 0 (no valid depth) get alpha = 0 and are discarded by the shader.
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
                vertices[vi++] = 0f; // alpha=0 → discard
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
