package at.njank.kinect;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

/**
 * Reusable orbit/pan/zoom camera shared by {@link at.njank.kinect.SkeletonVisualizer3D},
 * {@link at.njank.kinect.DepthVisualizer}, and {@link at.njank.kinect.ARVisualizer}.
 *
 * <p>Controls (polling-based, called once per frame from the visualizer's
 * {@code render()} method via {@link #handleInput()}):
 * <ul>
 *   <li>Left-drag  – orbit (yaw / pitch)</li>
 *   <li>Right-drag – pan</li>
 *   <li>Scroll     – zoom</li>
 *   <li>R          – reset to constructor defaults</li>
 * </ul>
 *
 * <p>Callers should:
 * <ol>
 *   <li>Construct with desired defaults and look-at target Z.</li>
 *   <li>Call {@link #init(int, int)} once on the GL thread (inside
 *       the visualizer's {@code create()} method).</li>
 *   <li>Call {@link #handleInput()} at the top of each {@code render()} frame.</li>
 *   <li>Pass {@link #getInputProcessor()} to the {@code InputMultiplexer}
 *       via {@link at.njank.kinect.Visualizer#getInputProcessor()}.</li>
 *   <li>Call {@link #resize(int, int)} from the visualizer's {@code resize()}.</li>
 * </ol>
 */
public class OrbitCamera {

    // Orbit/pan/zoom sensitivities
    private static final float ORBIT_SPEED  = 0.45f;
    private static final float PAN_SPEED    = 0.002f;
    private static final float SCROLL_SPEED = 0.2f;

    // -----------------------------------------------------------------------
    // Defaults (stored so resetCamera() can restore them)
    // -----------------------------------------------------------------------

    private final float defYaw, defPitch, defZoom, defPanX, defPanY;

    /** World-space Z coordinate that the camera always looks toward. */
    private final float lookAtZ;

    // -----------------------------------------------------------------------
    // Mutable state
    // -----------------------------------------------------------------------

    private float yaw, pitch, zoom, panX, panY;

    /** Previous-frame mouse position used to compute per-frame deltas. */
    private int pmx, pmy;

    // -----------------------------------------------------------------------
    // libGDX objects (created in init())
    // -----------------------------------------------------------------------

    private PerspectiveCamera camera;
    private InputProcessor    inputProcessor;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * @param defYaw    default yaw in degrees (0 = front-facing for negated-Z scenes)
     * @param defPitch  default pitch in degrees (negative = looking slightly down)
     * @param defZoom   default distance from the look-at point in metres
     * @param defPanX   default horizontal pan offset
     * @param defPanY   default vertical pan offset (use ~chest height for skeletons)
     * @param lookAtZ   world-space Z the camera always points toward
     */
    public OrbitCamera(float defYaw, float defPitch, float defZoom,
                       float defPanX, float defPanY, float lookAtZ) {
        this.defYaw   = defYaw;
        this.defPitch = defPitch;
        this.defZoom  = defZoom;
        this.defPanX  = defPanX;
        this.defPanY  = defPanY;
        this.lookAtZ  = lookAtZ;

        // Apply defaults
        resetState();
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Allocates the {@link com.badlogic.gdx.graphics.PerspectiveCamera} and wires up the scroll
     * {@link com.badlogic.gdx.InputProcessor}.  Must be called on the GL thread.
     *
     * @param near near clip distance
     * @param far  far  clip distance
     */
    public void init(int viewportW, int viewportH, float near, float far) {
        camera = new PerspectiveCamera(60f, viewportW, viewportH);
        camera.near = near;
        camera.far  = far;
        updateCamera();

        // Seed previous mouse so first frame delta is zero.
        pmx = Gdx.input.getX();
        pmy = Gdx.input.getY();

        // Only scroll needs an event listener; orbit/pan/reset are polled.
        inputProcessor = new InputAdapter() {
            @Override
            public boolean scrolled(float ax, float ay) {
                zoom = MathUtils.clamp(zoom + ay * SCROLL_SPEED, 0.3f, 20f);
                updateCamera();
                return true;
            }
        };
    }

    public void resize(int w, int h) {
        camera.viewportWidth  = w;
        camera.viewportHeight = h;
        camera.update();
    }

    // -----------------------------------------------------------------------
    // Per-frame update (call from visualizer render())
    // -----------------------------------------------------------------------

    /**
     * Polls the mouse every frame to update orbit, pan, and zoom.
     * Must be called at the top of the visualizer's {@code render()} method.
     */
    public void handleInput() {
        int mx = Gdx.input.getX();
        int my = Gdx.input.getY();
        int dx = mx - pmx;
        int dy = my - pmy;
        pmx = mx;
        pmy = my;

        if (Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
            yaw   += dx * ORBIT_SPEED;
            pitch  = MathUtils.clamp(pitch + dy * ORBIT_SPEED, -89f, 89f);
        }
        if (Gdx.input.isButtonPressed(Input.Buttons.RIGHT)) {
            panX -= dx * PAN_SPEED * zoom;
            panY += dy * PAN_SPEED * zoom;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.R)) {
            reset();
            return; // updateCamera() already called inside reset()
        }
        updateCamera();
    }

    /** Resets to the defaults supplied at construction time. */
    public void reset() {
        resetState();
        updateCamera();
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public PerspectiveCamera getCamera()          { return camera;         }
    public InputProcessor    getInputProcessor()   { return inputProcessor; }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void resetState() {
        yaw  = defYaw;
        pitch = defPitch;
        zoom  = defZoom;
        panX  = defPanX;
        panY  = defPanY;
    }

    private void updateCamera() {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        camera.position.set(
            panX + (float)(zoom * Math.cos(pr) * Math.sin(yr)),
            panY + (float)(zoom * Math.sin(pr)),
                   (float)(zoom * Math.cos(pr) * Math.cos(yr)));
        camera.lookAt(panX, panY, lookAtZ);
        camera.up.set(Vector3.Y);
        camera.update();
    }
}
