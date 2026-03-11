package at.njank.kinect;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import edu.ufl.digitalworlds.j4k.Skeleton;

import java.nio.ByteBuffer;

import static at.njank.kinect.KinectManager.DEPTH_H;
import static at.njank.kinect.KinectManager.DEPTH_W;
import static at.njank.kinect.SkeletonConstants.*;

/**
 * Visualizer mode 4 – "AR" (Augmented Reality).
 *
 * <p>Renders the Kinect v2 colour stream as a UV-mapped 3-D point cloud:
 * every depth pixel is placed at its metric (x, y, z) position in world
 * space and coloured with the corresponding colour-image texel, producing a
 * navigable 3-D reconstruction of the scene.
 *
 * <p>Camera controls are handled by {@link at.njank.kinect.OrbitCamera}.
 */
public class ARVisualizer implements Visualizer {

    private static final int POINT_COUNT      = DEPTH_W * DEPTH_H;
    private static final int FLOATS_PER_VERTEX = 5; // x y z  u v

    // -----------------------------------------------------------------------
    // GPU resources
    // -----------------------------------------------------------------------

    private Pixmap  bgPixmap;
    private Texture bgTexture;

    private Mesh          uvMesh;
    private float[]       vertices;
    private ShaderProgram uvShader;

    private SkeletonVisualizer2D skeletonOverlay;

    // -----------------------------------------------------------------------
    // Camera
    // -----------------------------------------------------------------------

    // yaw=0    → camera on +Z side, front-facing
    // pitch=-10 → gentle downward tilt
    // zoom=2   → close enough to fill the frame
    // panY=0.3 → look-target at roughly chest height
    // lookAtZ=-2 → scene centred at z≈−2 in world space
    private final OrbitCamera orbit =
        new OrbitCamera(0f, -10f, 2.0f, 0f, 0.3f, -2f);

    // -----------------------------------------------------------------------
    // Frame-dedup sentinels
    // -----------------------------------------------------------------------

    private byte[]  lastColorFrame;
    private float[] lastXYZ;
    private float[] lastUV;

    // -----------------------------------------------------------------------
    // Lazy init
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
        skeletonOverlay = new SkeletonVisualizer2D();
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
        if (bgTexture       != null) bgTexture.dispose();
        if (bgPixmap        != null) bgPixmap.dispose();
        if (uvMesh          != null) uvMesh.dispose();
        if (uvShader        != null) uvShader.dispose();
        if (skeletonOverlay != null) skeletonOverlay.dispose();
    }

    @Override
    public InputProcessor getInputProcessor() { return orbit.getInputProcessor(); }

    /** Called by Main when the user presses R in AR mode. */
    @Override
    public void resetCamera() { orbit.reset(); }

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
    // Colour-frame upload
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
