package at.njank.kinect;

import com.badlogic.gdx.graphics.Color;
import edu.ufl.digitalworlds.j4k.Skeleton;

/**
 * Shared constants used by every visualizer:
 *  - bone topology (pairs of Kinect v2 joint indices)
 *  - one colour per skeleton slot (Kinect v2 tracks up to 6 bodies)
 */
public final class SkeletonConstants {

    private SkeletonConstants() {}

    /** Total number of joints tracked by the Kinect v2 SDK. */
    public static final int JOINT_COUNT = 25;

    /** Kinect v2 colour-image resolution. */
    public static final int COLOR_W = 1920;
    public static final int COLOR_H = 1080;

    /**
     * Each row is a bone: {jointA, jointB}.
     * Joint indices come from {@link Skeleton}'s public constants.
     */
    public static final int[][] BONES = {
        // Spine / torso
        {Skeleton.SPINE_BASE,     Skeleton.SPINE_MID},
        {Skeleton.SPINE_MID,      Skeleton.SPINE_SHOULDER},
        {Skeleton.SPINE_SHOULDER, Skeleton.NECK},
        {Skeleton.NECK,           Skeleton.HEAD},
        // Left arm
        {Skeleton.SPINE_SHOULDER, Skeleton.SHOULDER_LEFT},
        {Skeleton.SHOULDER_LEFT,  Skeleton.ELBOW_LEFT},
        {Skeleton.ELBOW_LEFT,     Skeleton.WRIST_LEFT},
        {Skeleton.WRIST_LEFT,     Skeleton.HAND_LEFT},
        {Skeleton.HAND_LEFT,      Skeleton.HAND_TIP_LEFT},
        {Skeleton.WRIST_LEFT,     Skeleton.THUMB_LEFT},
        // Right arm
        {Skeleton.SPINE_SHOULDER, Skeleton.SHOULDER_RIGHT},
        {Skeleton.SHOULDER_RIGHT, Skeleton.ELBOW_RIGHT},
        {Skeleton.ELBOW_RIGHT,    Skeleton.WRIST_RIGHT},
        {Skeleton.WRIST_RIGHT,    Skeleton.HAND_RIGHT},
        {Skeleton.HAND_RIGHT,     Skeleton.HAND_TIP_RIGHT},
        {Skeleton.WRIST_RIGHT,    Skeleton.THUMB_RIGHT},
        // Left leg
        {Skeleton.SPINE_BASE,     Skeleton.HIP_LEFT},
        {Skeleton.HIP_LEFT,       Skeleton.KNEE_LEFT},
        {Skeleton.KNEE_LEFT,      Skeleton.ANKLE_LEFT},
        {Skeleton.ANKLE_LEFT,     Skeleton.FOOT_LEFT},
        // Right leg
        {Skeleton.SPINE_BASE,     Skeleton.HIP_RIGHT},
        {Skeleton.HIP_RIGHT,      Skeleton.KNEE_RIGHT},
        {Skeleton.KNEE_RIGHT,     Skeleton.ANKLE_RIGHT},
        {Skeleton.ANKLE_RIGHT,    Skeleton.FOOT_RIGHT},
    };

    /** Distinct colour per skeleton slot (index = body slot 0-5). */
    public static final Color[] SKELETON_COLORS = {
        new Color(0.20f, 0.80f, 1.00f, 1f),  // cyan-blue
        new Color(1.00f, 0.55f, 0.10f, 1f),  // orange
        new Color(0.35f, 1.00f, 0.35f, 1f),  // green
        new Color(1.00f, 0.20f, 0.60f, 1f),  // pink-red
        new Color(0.90f, 0.90f, 0.20f, 1f),  // yellow
        new Color(0.70f, 0.35f, 1.00f, 1f),  // purple
    };
}
