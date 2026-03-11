package at.njank.kinect;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.ScreenUtils;
import edu.ufl.digitalworlds.j4k.Skeleton;

import static at.njank.kinect.SkeletonConstants.*;

/**
 * Visualizer mode 2 – "2D Skeleton".
 *
 * <p>Renders every tracked skeleton as coloured joints and bones projected
 * onto the depth-sensor image plane.  Joint positions come from J4K's
 * {@code get2DJoint()}, which maps metric 3-D positions to normalised
 * [0, 1] × [0, 1] screen coordinates.
 *
 * <p>This class doubles as a lightweight overlay helper: other visualizers
 * (Camera, AR, Depth) call {@link #renderOverlay} to composite a flat
 * skeleton on top of their own 3-D scene without allocating extra resources.
 */
public class SkeletonVisualizer2D implements Visualizer {

    private static final float JOINT_RADIUS   = 8f;
    private static final float BONE_THICKNESS = 4f;

    private final ShapeRenderer sr = new ShapeRenderer();

    // -----------------------------------------------------------------------
    // Visualizer
    // -----------------------------------------------------------------------

    @Override
    public void render(KinectManager kinect) {
        ScreenUtils.clear(0.08f, 0.08f, 0.12f, 1f);

        Skeleton[] skeletons = kinect.getSkeletons();
        if (skeletons == null) return;

        float w = Gdx.graphics.getWidth();
        float h = Gdx.graphics.getHeight();

        sr.begin(ShapeRenderer.ShapeType.Filled);

        for (int s = 0; s < skeletons.length; s++) {
            Skeleton sk = skeletons[s];
            if (sk == null) continue;

            Color color = SKELETON_COLORS[s % SKELETON_COLORS.length];

            // Bones first so joints are drawn on top
            sr.setColor(color);
            for (int[] bone : BONES) {
                if (!visible(sk, bone[0], (int) w, (int) h)) continue;
                if (!visible(sk, bone[1], (int) w, (int) h)) continue;
                int[] a = sk.get2DJoint(bone[0], (int) w, (int) h);
                int[] b = sk.get2DJoint(bone[1], (int) w, (int) h);
                sr.rectLine(a[0], h - a[1], b[0], h - b[1], BONE_THICKNESS);
            }

            // Joints – dark halo + bright fill
            for (int j = 0; j < JOINT_COUNT; j++) {
                if (!visible(sk, j, (int) w, (int) h)) continue;
                int[] p = sk.get2DJoint(j, (int) w, (int) h);
                float x = p[0], y = h - p[1];
                sr.setColor(color.r * 0.3f, color.g * 0.3f, color.b * 0.3f, 1f);
                sr.circle(x, y, JOINT_RADIUS + 3f);
                sr.setColor(color);
                sr.circle(x, y, JOINT_RADIUS);
            }
        }

        sr.end();
    }

    @Override
    public void resize(int w, int h) {
        sr.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
    }

    @Override
    public void dispose() {
        sr.dispose();
    }

    @Override
    public InputProcessor getInputProcessor() { return null; }

    // -----------------------------------------------------------------------
    // Overlay helper (used by CameraVisualizer, ARVisualizer, DepthVisualizer)
    // -----------------------------------------------------------------------

    /**
     * Draws the skeleton overlay into the currently active frame.
     * Does not clear the screen – intended to be called after the caller's
     * own scene has been rendered.
     *
     * @param skeletons array from {@link at.njank.kinect.KinectManager#getSkeletons()} (may be null)
     * @param w         current viewport width  in pixels
     * @param h         current viewport height in pixels
     */
    public void renderOverlay(Skeleton[] skeletons, float w, float h) {
        if (skeletons == null) return;
        sr.begin(ShapeRenderer.ShapeType.Filled);
        for (int s = 0; s < skeletons.length; s++) {
            Skeleton sk = skeletons[s];
            if (sk == null) continue;
            Color color = SKELETON_COLORS[s % SKELETON_COLORS.length];
            sr.setColor(color);
            for (int[] bone : BONES) {
                if (!visible(sk, bone[0], (int) w, (int) h)) continue;
                if (!visible(sk, bone[1], (int) w, (int) h)) continue;
                int[] a = sk.get2DJoint(bone[0], (int) w, (int) h);
                int[] b = sk.get2DJoint(bone[1], (int) w, (int) h);
                sr.rectLine(a[0], h - a[1], b[0], h - b[1], 3f);
            }
            for (int j = 0; j < JOINT_COUNT; j++) {
                if (!visible(sk, j, (int) w, (int) h)) continue;
                int[] p = sk.get2DJoint(j, (int) w, (int) h);
                float x = p[0], y = h - p[1];
                sr.setColor(color.r * 0.3f, color.g * 0.3f, color.b * 0.3f, 1f);
                sr.circle(x, y, 7f);
                sr.setColor(color);
                sr.circle(x, y, 5f);
            }
        }
        sr.end();
    }

    /** Updates the ShapeRenderer projection matrix to match a new viewport size. */
    public void setProjection(float w, float h) {
        sr.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
    }

    // -----------------------------------------------------------------------

    /** Returns true if the joint projects within the visible viewport. */
    private static boolean visible(Skeleton sk, int joint, int w, int h) {
        int[] p = sk.get2DJoint(joint, w, h);
        return p[0] > 0 && p[0] < w && p[1] > 0 && p[1] < h;
    }
}
