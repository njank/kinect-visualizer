package at.njank.kinect;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g3d.*;
import com.badlogic.gdx.graphics.g3d.attributes.*;
import com.badlogic.gdx.graphics.g3d.environment.*;
import com.badlogic.gdx.graphics.g3d.utils.*;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import edu.ufl.digitalworlds.j4k.Skeleton;

import static at.njank.kinect.SkeletonConstants.*;

/**
 * Visualizer mode 3 – "3D Skeleton".
 *
 * <p>Renders skeletons using the Kinect v2 metric 3-D joint positions.
 * Joints are drawn as lit spheres via libGDX's {@code ModelBatch}; bones are
 * drawn as lines via {@code ShapeRenderer}.  A ground-plane grid gives spatial
 * reference.
 *
 * <p>Coordinate convention: Kinect +Z points toward the sensor (away from the
 * person), so Z is negated when copying joint positions into world space.
 * This places the skeleton at positive Z in front of the default camera.
 *
 * <p>Camera controls are handled by {@link at.njank.kinect.OrbitCamera}.
 */
public class SkeletonVisualizer3D implements Visualizer {

    /** Radius of each joint sphere in metres. */
    private static final float JOINT_RADIUS = 0.04f;

    // -----------------------------------------------------------------------
    // Scene resources
    // -----------------------------------------------------------------------

    private ModelBatch    modelBatch;
    private Environment   environment;
    /** One pre-built sphere model per skeleton colour slot (0-5). */
    private final Model[] sphereModels = new Model[SKELETON_COLORS.length];
    private ShapeRenderer sr;

    /** Reused each frame to avoid per-frame allocation. */
    private final Array<ModelInstance> instances = new Array<>();
    private final Vector3 tmp1 = new Vector3();
    private final Vector3 tmp2 = new Vector3();

    // -----------------------------------------------------------------------
    // Camera
    // -----------------------------------------------------------------------

    // yaw=180  → camera sits on the −Z side; skeleton (placed at +Z after Z-negate) faces us
    // pitch=-10 → slight downward tilt
    // zoom=3.5  → comfortable full-body distance
    // panY=1.0  → look-target at ~chest height
    // lookAtZ=2 → world-space Z where the skeleton appears
    private final OrbitCamera orbit =
        new OrbitCamera(180f, -10f, 3.5f, 0f, 1.0f, 2f);

    // -----------------------------------------------------------------------
    // Visualizer
    // -----------------------------------------------------------------------

    @Override
    public void create() {
        modelBatch  = new ModelBatch();
        environment = new Environment();
        environment.set(new ColorAttribute(
            ColorAttribute.AmbientLight, 0.5f, 0.5f, 0.55f, 1f));
        environment.add(new DirectionalLight()
            .set(1f, 0.97f, 0.90f, -0.4f, -1f, -0.4f));

        // Build one coloured sphere model per body slot up-front to avoid
        // per-frame model creation.
        ModelBuilder mb   = new ModelBuilder();
        int          attr = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;
        for (int i = 0; i < SKELETON_COLORS.length; i++) {
            sphereModels[i] = mb.createSphere(
                JOINT_RADIUS * 2, JOINT_RADIUS * 2, JOINT_RADIUS * 2, 12, 12,
                new Material(ColorAttribute.createDiffuse(SKELETON_COLORS[i])), attr);
        }

        sr = new ShapeRenderer();
        orbit.init(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 0.05f, 100f);
    }

    @Override
    public void render(KinectManager kinect) {
        orbit.handleInput();

        ScreenUtils.clear(0.05f, 0.05f, 0.09f, 1f);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        orbit.getCamera().update();
        Skeleton[] skeletons = kinect.getSkeletons();

        // --- Bone lines ---
        sr.setProjectionMatrix(orbit.getCamera().combined);
        sr.begin(ShapeRenderer.ShapeType.Line);
        if (skeletons != null) {
            for (int s = 0; s < skeletons.length; s++) {
                Skeleton sk = skeletons[s];
                if (sk == null) continue;
                sr.setColor(SKELETON_COLORS[s % SKELETON_COLORS.length]);
                for (int[] bone : BONES) {
                    if (!has3D(sk, bone[0]) || !has3D(sk, bone[1])) continue;
                    toWorld(sk, bone[0], tmp1);
                    toWorld(sk, bone[1], tmp2);
                    sr.line(tmp1, tmp2);
                }
            }
        }
        sr.end();

        // --- Joint spheres ---
        instances.clear();
        if (skeletons != null) {
            for (int s = 0; s < skeletons.length; s++) {
                Skeleton sk = skeletons[s];
                if (sk == null) continue;
                Model model = sphereModels[s % sphereModels.length];
                for (int j = 0; j < JOINT_COUNT; j++) {
                    if (!has3D(sk, j)) continue;
                    toWorld(sk, j, tmp1);
                    ModelInstance inst = new ModelInstance(model);
                    inst.transform.setToTranslation(tmp1);
                    instances.add(inst);
                }
            }
        }
        modelBatch.begin(orbit.getCamera());
        modelBatch.render(instances, environment);
        modelBatch.end();

        drawGrid();
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
    }

    @Override
    public void resize(int w, int h) {
        orbit.resize(w, h);
    }

    @Override
    public void dispose() {
        if (modelBatch != null) modelBatch.dispose();
        for (Model m : sphereModels) if (m != null) m.dispose();
        if (sr != null) sr.dispose();
    }

    @Override
    public InputProcessor getInputProcessor() { return orbit.getInputProcessor(); }

    @Override
    public void resetCamera() { orbit.reset(); }

    // -----------------------------------------------------------------------
    // Joint helpers
    // -----------------------------------------------------------------------

    /** Returns true if the joint has a non-zero 3-D position. */
    private static boolean has3D(Skeleton sk, int j) {
        double[] p = sk.get3DJoint(j);
        return p[0] != 0.0 || p[1] != 0.0 || p[2] != 0.0;
    }

    /**
     * Converts a Kinect joint position to world space.
     * Kinect Z is negated so the skeleton appears in front of the camera.
     */
    private static void toWorld(Skeleton sk, int j, Vector3 out) {
        double[] p = sk.get3DJoint(j);
        out.set((float) p[0], (float) p[1], -(float) p[2]);
    }

    /** Draws a flat reference grid on the y = 0 plane. */
    private void drawGrid() {
        final int   HALF = 4;
        final float STEP = 0.5f;
        sr.setProjectionMatrix(orbit.getCamera().combined);
        sr.begin(ShapeRenderer.ShapeType.Line);
        sr.setColor(0.22f, 0.22f, 0.28f, 1f);
        for (int i = -HALF; i <= HALF; i++) {
            float f = i * STEP;
            sr.line(-HALF * STEP, 0f, f,  HALF * STEP, 0f, f);
            sr.line(f, 0f, -HALF * STEP,  f, 0f,  HALF * STEP);
        }
        sr.end();
    }
}
