package at.njank.kinect;

import com.badlogic.gdx.Gdx;
import edu.ufl.digitalworlds.j4k.J4KSDK;
import edu.ufl.digitalworlds.j4k.Skeleton;

/**
 * Owns the {@link J4KSDK} instance and bridges the Kinect background threads
 * to the libGDX render thread via volatile references.
 */
public class KinectManager {

    /** Kinect v2 depth resolution. */
    public static final int DEPTH_W = 512;
    public static final int DEPTH_H = 424;

    // Written by J4K threads, read by render thread.
    private volatile Skeleton[] latestSkeletons  = null;
    private volatile byte[]     latestColorFrame = null;
    private volatile float[]    latestDepthXYZ   = null;
    private volatile float[]    latestDepthUV    = null;

    private final J4KSDK sdk;
    private boolean running = false;

    public KinectManager() {
        sdk = new J4KSDK() {

            @Override
            public void onSkeletonFrameEvent(boolean[] skeletonTracked,
                                             float[]   positions,
                                             float[]   orientations,
                                             byte[]    jointStates) {
                int count = getMaxNumberOfSkeletons();
                Skeleton[] frame = new Skeleton[count];
                for (int i = 0; i < count; i++) {
                    if (skeletonTracked[i]) {
                        frame[i] = Skeleton.getSkeleton(
                            i, skeletonTracked, positions, orientations,
                            jointStates, getDeviceType());
                    }
                }
                latestSkeletons = frame;
            }

            @Override
            public void onColorFrameEvent(byte[] colorData) {
                byte[] copy = new byte[colorData.length];
                System.arraycopy(colorData, 0, copy, 0, colorData.length);
                latestColorFrame = copy;
            }

            @Override
            public void onDepthFrameEvent(short[] depthData, byte[] playerIndex,
                                          float[] xyz, float[] uv) {
                if (xyz != null) {
                    float[] xyzCopy = new float[xyz.length];
                    System.arraycopy(xyz, 0, xyzCopy, 0, xyz.length);
                    latestDepthXYZ = xyzCopy;
                }
                if (uv != null) {
                    float[] uvCopy = new float[uv.length];
                    System.arraycopy(uv, 0, uvCopy, 0, uv.length);
                    latestDepthUV = uvCopy;
                }
            }
        };
    }

    public void start() {
        running = sdk.start(J4KSDK.COLOR | J4KSDK.DEPTH | J4KSDK.SKELETON
            | J4KSDK.XYZ | J4KSDK.UV);
        if (!running) {
            Gdx.app.error("KinectManager",
                "Could not open Kinect v2. " +
                "Ensure the Kinect for Windows Runtime v2 is installed.");
        } else {
            Gdx.app.log("KinectManager", "Kinect v2 opened - streaming COLOR + DEPTH + SKELETON.");
        }
    }

    public void stop() {
        sdk.stop();
        running = false;
    }

    public Skeleton[] getSkeletons()  { return latestSkeletons;  }
    public byte[]     getColorFrame() { return latestColorFrame; }
    public float[]    getDepthXYZ()   { return latestDepthXYZ;   }
    public float[]    getDepthUV()    { return latestDepthUV;     }
    public boolean    isRunning()     { return running;           }
}
