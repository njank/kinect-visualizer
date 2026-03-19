package at.njank.kinect;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Application entry point and top-level orchestrator.
 *
 * <p>Five visualizer modes, selectable via the tab bar or number keys 1-5:
 * <pre>
 *   1 - Camera   colour feed with optional 2-D skeleton overlay
 *   2 - AR       UV-mapped 3-D point cloud + overlay toggle (O key)
 *   3 - Depth    depth point cloud coloured by distance (red to blue)
 *   4 - Audio    depth cloud with Z and colour intensified by system audio
 * </pre>
 *
 * <p>Global keys:
 * <ul>
 *   <li>S - toggle skeleton overlay on the active mode</li>
 *   <li>O - cycle AR overlay: NONE -> AUDIO -> SCREEN_ADD -> SCREEN_SUBTRACT (AR mode only)</li>
 *   <li>P - toggle 3-D projective screen rendering (AR mode + SCREEN overlay only)</li>
 *   <li>M - cycle to the next monitor (works from any mode when AR screen overlay is active)</li>
 *   <li>R - reset orbit camera (AR / Depth / Audio modes)</li>
 *   <li>Escape - quit</li>
 * </ul>
 *
 * <p>A single {@link ScreenCapture} instance is created at startup and shared
 * between visualizer modes so only one DXGI
 * duplication session exists at a time.
 *
 * <p>Frame rate is capped to 60 FPS in {@code Lwjgl3Launcher}.
 */
public class Main extends ApplicationAdapter implements InputProcessor {

    // -----------------------------------------------------------------------
    // Modes
    // -----------------------------------------------------------------------

    /** Ordered list of available visualizer modes (index == tab position). */
    private enum Mode { CAMERA, AR, DEPTH, AUDIO }

    /** Tab labels - must stay in the same order as {@link Mode}. */
    private static final String[] TAB_LABELS = {
        "1  Camera", "2  AR", "3  Depth", "4  Audio"
    };

    // -----------------------------------------------------------------------
    // Core state
    // -----------------------------------------------------------------------

    private KinectManager kinect;

    /**
     * All visualizer instances indexed by {@link Mode#ordinal()}.
     * Slots are null until the corresponding mode is activated for the first time.
     */
    private final Visualizer[] visualizers = new Visualizer[Mode.values().length];

    /** Convenience reference to {@code visualizers[currentMode.ordinal()]}. */
    private Visualizer activeVis;

    private Mode currentMode = Mode.CAMERA;

    /**
     * Shared screen capture manager.  Created once at startup and passed to
     * {@link ARVisualizer}.
     * Owned by Main; disposed in {@link #dispose()}.
     */
    private ScreenCapture screenCapture;

    // -----------------------------------------------------------------------
    // HUD resources
    // -----------------------------------------------------------------------

    /** Height of the tab bar strip at the top of the window, in pixels. */
    private static final float TAB_H   = 36f;
    /** Horizontal padding on each side of a tab label, at base font scale. */
    private static final float TAB_PAD = 20f;
    /** Font scale used when all tabs fit comfortably in the window. */
    private static final float FONT_BASE_SCALE = 1.1f;

    private SpriteBatch      hudBatch;
    private ShapeRenderer    hudShape;
    private BitmapFont       hudFont;
    private GlyphLayout      layout;
    private InputMultiplexer inputMux;

    // FPS counter - smoothed over 30 frames
    private float fpsAccum   = 0f;
    private int   fpsFrames  = 0;
    private int   fpsDisplay = 0;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void create() {
        kinect = new KinectManager();
        kinect.start();

        // Create the shared screen capture and attempt to open monitor 0.
        // Passed to ARVisualizer for use with the SCREEN_ADD/SCREEN_SUBTRACT overlays.
        screenCapture = new ScreenCapture();
        screenCapture.init();

        hudBatch = new SpriteBatch();
        hudShape = new ShapeRenderer();
        hudFont  = new BitmapFont();
        hudFont.getData().setScale(FONT_BASE_SCALE);
        layout   = new GlyphLayout();

        inputMux = new InputMultiplexer();
        Gdx.input.setInputProcessor(inputMux);

        activateMode(Mode.CAMERA);
    }

    @Override
    public void render() {
        // Smooth FPS over 30-frame windows
        fpsAccum += Gdx.graphics.getDeltaTime();
        fpsFrames++;
        if (fpsFrames >= 30) {
            fpsDisplay = Math.round(fpsFrames / fpsAccum);
            fpsAccum   = 0f;
            fpsFrames  = 0;
        }

        activeVis.render(kinect);
        drawHud();
    }

    @Override
    public void resize(int w, int h) {
        activeVis.resize(w, h);
        hudBatch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        hudShape.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
    }

    @Override
    public void dispose() {
        kinect.stop();
        for (Visualizer v : visualizers) {
            if (v != null) v.dispose();
        }
        screenCapture.dispose();
        hudBatch.dispose();
        hudShape.dispose();
        hudFont.dispose();
    }

    // -----------------------------------------------------------------------
    // Mode switching
    // -----------------------------------------------------------------------

    private void activateMode(Mode mode) {
        currentMode = mode;
        int idx     = mode.ordinal();

        // Lazy-init: create the visualizer on first use
        if (visualizers[idx] == null) {
            visualizers[idx] = buildVisualizer(mode);
            visualizers[idx].create();
        }

        activeVis = visualizers[idx];
        activeVis.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        // Rebuild the input chain: global keys always have priority
        inputMux.clear();
        inputMux.addProcessor(this);
        InputProcessor vp = activeVis.getInputProcessor();
        if (vp != null) inputMux.addProcessor(vp);
    }

    /**
     * Constructs (but does not initialise) the visualizer for a given mode.
     * The shared ScreenCapture is injected into the visualizers that need it.
     */
    private Visualizer buildVisualizer(Mode mode) {
        return switch (mode) {
            case CAMERA -> new CameraVisualizer();
            case AR     -> new ARVisualizer(screenCapture);
            case DEPTH  -> new DepthVisualizer();
            case AUDIO  -> new AudioVisualizer();
        };
    }

    // -----------------------------------------------------------------------
    // HUD rendering
    // -----------------------------------------------------------------------

    private void drawHud() {
        final float sw = Gdx.graphics.getWidth();
        final float sh = Gdx.graphics.getHeight();

        // Compute a scale so all tabs always fit the window width
        hudFont.getData().setScale(FONT_BASE_SCALE);
        float[] tabW   = new float[TAB_LABELS.length];
        float   totalW = 0;
        for (int i = 0; i < TAB_LABELS.length; i++) {
            layout.setText(hudFont, TAB_LABELS[i]);
            tabW[i] = layout.width + TAB_PAD * 2;
            totalW  += tabW[i];
        }
        // Scale down uniformly to fit within 96% of the window if needed
        float tabScale = Math.min(1f, (sw * 0.96f) / totalW);
        if (tabScale < 1f) {
            hudFont.getData().setScale(FONT_BASE_SCALE * tabScale);
            totalW = 0;
            for (int i = 0; i < TAB_LABELS.length; i++) {
                layout.setText(hudFont, TAB_LABELS[i]);
                tabW[i] = layout.width + TAB_PAD * tabScale * 2;
                totalW  += tabW[i];
            }
        }

        final float startX = (sw - totalW) / 2f;
        final float tabY   = sh - TAB_H;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Tab strip background
        hudShape.begin(ShapeRenderer.ShapeType.Filled);
        hudShape.setColor(0f, 0f, 0f, 0.58f);
        hudShape.rect(0, tabY - 2, sw, TAB_H + 2);
        hudShape.end();

        // Individual tab backgrounds
        hudShape.begin(ShapeRenderer.ShapeType.Filled);
        float x = startX;
        for (int i = 0; i < TAB_LABELS.length; i++) {
            if (i == currentMode.ordinal()) {
                Color ac = SkeletonConstants.SKELETON_COLORS[i];
                hudShape.setColor(ac.r * 0.25f, ac.g * 0.25f, ac.b * 0.25f, 0.92f);
            } else {
                hudShape.setColor(0.10f, 0.10f, 0.14f, 0.82f);
            }
            hudShape.rect(x, tabY, tabW[i] - 2, TAB_H);
            x += tabW[i];
        }
        hudShape.end();

        // Active tab top accent bar
        hudShape.begin(ShapeRenderer.ShapeType.Filled);
        x = startX;
        for (int i = 0; i < TAB_LABELS.length; i++) {
            if (i == currentMode.ordinal()) {
                hudShape.setColor(SkeletonConstants.SKELETON_COLORS[i]);
                hudShape.rect(x, sh - 3, tabW[i] - 2, 3);
            }
            x += tabW[i];
        }
        hudShape.end();

        // Tab labels
        hudBatch.begin();
        x = startX;
        for (int i = 0; i < TAB_LABELS.length; i++) {
            layout.setText(hudFont, TAB_LABELS[i]);
            hudFont.setColor(i == currentMode.ordinal()
                ? SkeletonConstants.SKELETON_COLORS[i]
                : new Color(0.58f, 0.58f, 0.63f, 1f));
            hudFont.draw(hudBatch, TAB_LABELS[i],
                x + TAB_PAD * tabScale,
                tabY + (TAB_H + layout.height) / 2f);
            x += tabW[i];
        }

        // Restore base scale for hint / fps lines
        hudFont.getData().setScale(FONT_BASE_SCALE);
        hudFont.setColor(0.40f, 0.40f, 0.45f, 1f);

        // Build the bottom-left hint line for the current mode
        String hint = buildHint();
        if (!hint.isEmpty()) {
            hudFont.draw(hudBatch, hint, 10, 18);
        }

        // FPS counter (bottom-right), colour-coded green/yellow/red
        String fpsStr = fpsDisplay + " FPS";
        layout.setText(hudFont, fpsStr);
        Color fpsCol = fpsDisplay >= 55 ? new Color(0.3f, 1f,   0.3f,  1f)
                     : fpsDisplay >= 30 ? new Color(1f,   0.85f, 0.2f, 1f)
                     :                    new Color(1f,   0.3f,  0.3f, 1f);
        hudFont.setColor(fpsCol);
        hudFont.draw(hudBatch, fpsStr, sw - layout.width - 10, 18);

        hudBatch.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /**
     * Builds the context-sensitive hint string shown at the bottom-left of
     * the window.  Returns an empty string when no hint is applicable.
     */
    private String buildHint() {
        boolean skelOn = activeVis.isSkeletonEnabled();
        String  skelHint = "  S:[skel " + (skelOn ? "ON" : "OFF") + "]";

        switch (currentMode) {
            case AR: {
                ARVisualizer ar = (ARVisualizer) visualizers[Mode.AR.ordinal()];
                String overlayName = ar != null ? ar.getOverlay().name() : "NONE";
                boolean proj = ar != null && ar.isScreenProjected();
                String projHint = ar != null && (ar.getOverlay() == ARVisualizer.Overlay.SCREEN_ADD
                                             || ar.getOverlay() == ARVisualizer.Overlay.SCREEN_SUBTRACT)
                               ? "  P:[proj " + (proj ? "ON" : "OFF") + "]" : "";
                return "Drag:orbit  R:reset  O:[" + overlayName + "]  M:monitor" + projHint + skelHint;
            }
            case DEPTH:
            case AUDIO:
                return "Drag:orbit  R:reset" + skelHint;
            case CAMERA:
                return skelHint.trim();

            default:
                return "";
        }
    }

    // -----------------------------------------------------------------------
    // InputProcessor
    // -----------------------------------------------------------------------

    @Override
    public boolean keyDown(int keycode) {
        switch (keycode) {
            case Input.Keys.NUM_1: activateMode(Mode.CAMERA); return true;
            case Input.Keys.NUM_2: activateMode(Mode.AR);     return true;
            case Input.Keys.NUM_3: activateMode(Mode.DEPTH);  return true;
            case Input.Keys.NUM_4: activateMode(Mode.AUDIO);  return true;

            case Input.Keys.S:
                // Toggle skeleton overlay on the active visualizer
                activeVis.setSkeletonEnabled(!activeVis.isSkeletonEnabled());
                return true;

            case Input.Keys.O:
                // Cycle AR overlay (only meaningful in AR mode)
                if (currentMode == Mode.AR) {
                    ARVisualizer ar = (ARVisualizer) visualizers[Mode.AR.ordinal()];
                    if (ar != null) ar.nextOverlay();
                }
                return true;

            case Input.Keys.P:
                // Toggle 3-D projective screen rendering (AR + SCREEN overlay)
                if (currentMode == Mode.AR) {
                    ARVisualizer ar = (ARVisualizer) visualizers[Mode.AR.ordinal()];
                    if (ar != null) ar.setScreenProjected(!ar.isScreenProjected());
                }
                return true;

            case Input.Keys.M:
                // Cycle to the next monitor (only meaningful in Screen mode,
                // also affects the SCREEN_ADD/SCREEN_SUBTRACT overlay in AR mode
                screenCapture.nextMonitor();
                return true;

            case Input.Keys.R:
                // Delegate camera reset (no-op for Camera and Screen modes)
                activeVis.resetCamera();
                return true;

            case Input.Keys.ESCAPE:
                Gdx.app.exit();
                return true;
        }
        return false;
    }

    /** Handles tab-bar clicks. Converts screen-space Y before hit-testing. */
    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        final float sw   = Gdx.graphics.getWidth();
        final float sh   = Gdx.graphics.getHeight();
        final float gdxY = sh - screenY; // libGDX Y is bottom-up

        if (gdxY < sh - TAB_H) return false;

        hudFont.getData().setScale(FONT_BASE_SCALE);
        float[] tabW   = new float[TAB_LABELS.length];
        float   totalW = 0;
        for (int i = 0; i < TAB_LABELS.length; i++) {
            layout.setText(hudFont, TAB_LABELS[i]);
            tabW[i] = layout.width + TAB_PAD * 2;
            totalW  += tabW[i];
        }
        float tabScale = Math.min(1f, (sw * 0.96f) / totalW);
        if (tabScale < 1f) {
            hudFont.getData().setScale(FONT_BASE_SCALE * tabScale);
            totalW = 0;
            for (int i = 0; i < TAB_LABELS.length; i++) {
                layout.setText(hudFont, TAB_LABELS[i]);
                tabW[i] = layout.width + TAB_PAD * tabScale * 2;
                totalW  += tabW[i];
            }
        }
        hudFont.getData().setScale(FONT_BASE_SCALE); // restore

        float x = (sw - totalW) / 2f;
        for (int i = 0; i < TAB_LABELS.length; i++) {
            if (screenX >= x && screenX < x + tabW[i]) {
                activateMode(Mode.values()[i]);
                return true;
            }
            x += tabW[i];
        }
        return false;
    }

    // Unused InputProcessor methods
    @Override public boolean keyUp(int k)                                { return false; }
    @Override public boolean keyTyped(char c)                            { return false; }
    @Override public boolean touchUp(int x, int y, int p, int b)        { return false; }
    @Override public boolean touchDragged(int x, int y, int p)          { return false; }
    @Override public boolean mouseMoved(int x, int y)                    { return false; }
    @Override public boolean scrolled(float ax, float ay)                { return false; }
    @Override public boolean touchCancelled(int x, int y, int p, int b) { return false; }
}
