package at.njank.kinect;

import com.badlogic.gdx.InputProcessor;

/**
 * Common contract for all visualizer modes.
 *
 * Each implementation is fully responsible for clearing the screen and
 * drawing a complete frame in {@link #render}.  GPU resources should be
 * allocated in {@link #create} and released in {@link #dispose}.
 */
public interface Visualizer {

    /**
     * Allocates GPU / native resources.
     * Called once on the GL thread immediately after construction.
     * Default implementation is a no-op for lightweight visualizers
     * that initialise everything in their constructor.
     */
    default void create() {}

    /** Called once per frame from {@link at.njank.kinect.Main#render()}. */
    void render(KinectManager kinect);

    /** Called whenever the window is resized. */
    void resize(int width, int height);

    /** Releases all GPU / native resources owned by this visualizer. */
    void dispose();

    /**
     * Returns an optional {@link com.badlogic.gdx.InputProcessor} for visualizer-specific
     * controls (e.g. mouse orbit in 3-D modes).
     * Return {@code null} if the visualizer needs no extra input.
     */
    InputProcessor getInputProcessor();

    /**
     * Resets the camera / view to its default position.
     * Only meaningful for 3-D visualizers; default is a no-op.
     */
    default void resetCamera() {}
}
