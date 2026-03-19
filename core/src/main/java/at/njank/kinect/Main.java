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
 * <p>Three visualizer modes, selectable via the tab bar or number keys 1-3:
 * <pre>
 *   1 - Camera   colour feed with optional skeleton overlay
 *   2 - AR       UV-mapped 3-D point cloud + overlay toggle (O key)
 *   3 - Depth    depth point cloud coloured by distance (red to blue)
 * </pre>
 *
 * <p>Global keys:
 * <ul>
 *   <li>H - toggle the entire HUD (tab bar + hints)</li>
 *   <li>S - toggle skeleton overlay on the active mode</li>
 *   <li>O - cycle AR overlay: NONE -> AUDIO -> LINEAR_DODGE -> SUBTRACT</li>
 *   <li>P - toggle 3-D projective screen rendering (AR + screen overlay only)</li>
 *   <li>M - cycle to the next monitor (AR screen overlay)</li>
 *   <li>R - reset orbit camera (AR / Depth modes)</li>
 *   <li>Escape - quit</li>
 * </ul>
 */
public class Main extends ApplicationAdapter implements InputProcessor {

    // -----------------------------------------------------------------------
    // Modes
    // -----------------------------------------------------------------------

    private enum Mode { CAMERA, AR, DEPTH }

    private static final String[] TAB_LABELS = {
        "1  Camera", "2  AR", "3  Depth"
    };

    // -----------------------------------------------------------------------
    // Core state
    // -----------------------------------------------------------------------

    private KinectManager kinect;

    private final Visualizer[] visualizers = new Visualizer[Mode.values().length];
    private Visualizer activeVis;
    private Mode       currentMode = Mode.CAMERA;

    /** Shared DXGI screen capture passed to ARVisualizer. */
    private ScreenCapture screenCapture;

    // -----------------------------------------------------------------------
    // HUD state
    // -----------------------------------------------------------------------

    /** When false the entire HUD (tab bar + hints + FPS) is hidden. Toggle: H. */
    private boolean hudVisible = true;

    /** Height of the tab bar strip in pixels. */
    private static final float TAB_H            = 36f;
    /** Horizontal padding inside each tab at base font scale. */
    private static final float TAB_PAD          = 20f;
    /** Base font scale used when all tabs fit. */
    private static final float FONT_BASE_SCALE  = 1.1f;
    /** Vertical padding above/below hint/FPS text inside the background pill. */
    private static final float HINT_PAD_V       = 4f;
    /** Horizontal padding left/right of hint/FPS text inside the background pill. */
    private static final float HINT_PAD_H       = 8f;

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
        fpsAccum += Gdx.graphics.getDeltaTime();
        fpsFrames++;
        if (fpsFrames >= 30) {
            fpsDisplay = Math.round(fpsFrames / fpsAccum);
            fpsAccum   = 0f;
            fpsFrames  = 0;
        }

        activeVis.render(kinect);
        if (hudVisible) drawHud();
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

        if (visualizers[idx] == null) {
            visualizers[idx] = buildVisualizer(mode);
            visualizers[idx].create();
        }

        activeVis = visualizers[idx];
        activeVis.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        inputMux.clear();
        inputMux.addProcessor(this);
        InputProcessor vp = activeVis.getInputProcessor();
        if (vp != null) inputMux.addProcessor(vp);
    }

    private Visualizer buildVisualizer(Mode mode) {
        return switch (mode) {
            case CAMERA -> new CameraVisualizer();
            case AR     -> new ARVisualizer(screenCapture);
            case DEPTH  -> new DepthVisualizer();
        };
    }

    // -----------------------------------------------------------------------
    // HUD rendering
    // -----------------------------------------------------------------------

    private void drawHud() {
        final float sw = Gdx.graphics.getWidth();
        final float sh = Gdx.graphics.getHeight();

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // ---- Tab bar ----
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

        final float startX = (sw - totalW) / 2f;
        final float tabY   = sh - TAB_H;

        // Tab strip background
        hudShape.begin(ShapeRenderer.ShapeType.Filled);
        hudShape.setColor(0f, 0f, 0f, 0.65f);
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

        hudFont.getData().setScale(FONT_BASE_SCALE);

        // ---- Hint text (bottom-left) with dark background ----
        String hint = buildHint();
        if (!hint.isEmpty()) {
            layout.setText(hudFont, hint);
            float hintW = layout.width;
            float hintH = layout.height;
            float bgX   = 8f;
            float bgY   = 8f; // bottom of background rect

            // Dark pill behind the hint text
            hudShape.begin(ShapeRenderer.ShapeType.Filled);
            hudShape.setColor(0f, 0f, 0f, 0.60f);
            hudShape.rect(bgX, bgY,
                hintW + HINT_PAD_H * 2,
                hintH + HINT_PAD_V * 2);
            hudShape.end();

            hudBatch.begin();
            hudFont.setColor(0.85f, 0.85f, 0.88f, 1f);
            hudFont.draw(hudBatch, hint,
                bgX + HINT_PAD_H,
                bgY + HINT_PAD_V + hintH); // BitmapFont draw y is the baseline/top
            hudBatch.end();
        }

        // ---- FPS counter (bottom-right) with dark background ----
        String fpsStr = fpsDisplay + " FPS";
        layout.setText(hudFont, fpsStr);
        float fpsW  = layout.width;
        float fpsH  = layout.height;
        float fBgX  = sw - fpsW - HINT_PAD_H * 2 - 8f;
        float fBgY  = 8f;

        hudShape.begin(ShapeRenderer.ShapeType.Filled);
        hudShape.setColor(0f, 0f, 0f, 0.60f);
        hudShape.rect(fBgX, fBgY,
            fpsW + HINT_PAD_H * 2,
            fpsH + HINT_PAD_V * 2);
        hudShape.end();

        Color fpsCol = fpsDisplay >= 55 ? new Color(0.3f, 1f,   0.3f,  1f)
                     : fpsDisplay >= 30 ? new Color(1f,   0.85f, 0.2f, 1f)
                     :                    new Color(1f,   0.3f,  0.3f, 1f);

        hudBatch.begin();
        hudFont.setColor(fpsCol);
        hudFont.draw(hudBatch, fpsStr,
            fBgX + HINT_PAD_H,
            fBgY + HINT_PAD_V + fpsH);

        // Tab labels (drawn last so they sit on top of their backgrounds)
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

        hudBatch.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /** Builds the context-sensitive hint string for the current mode. */
    private String buildHint() {
        boolean skelOn   = activeVis.isSkeletonEnabled();
        String  skelHint = "  S:[skel " + (skelOn ? "ON" : "OFF") + "]";

        switch (currentMode) {
            case AR: {
                ARVisualizer ar = (ARVisualizer) visualizers[Mode.AR.ordinal()];
                String overlayName = ar != null ? ar.getOverlay().name() : "NONE";
                boolean proj = ar != null && ar.isScreenProjected();
                boolean isScreen = ar != null
                    && (ar.getOverlay() == ARVisualizer.Overlay.LINEAR_DODGE
                     || ar.getOverlay() == ARVisualizer.Overlay.SUBTRACT);
                String projHint = isScreen
                    ? "  P:[proj " + (proj ? "ON" : "OFF") + "]" : "";
                return "Drag:orbit  R:reset  O:[" + overlayName + "]  M:monitor"
                       + projHint + skelHint;
            }
            case DEPTH:
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

            case Input.Keys.H:
                hudVisible = !hudVisible;
                return true;

            case Input.Keys.S:
                activeVis.setSkeletonEnabled(!activeVis.isSkeletonEnabled());
                return true;

            case Input.Keys.O:
                if (currentMode == Mode.AR) {
                    ARVisualizer ar = (ARVisualizer) visualizers[Mode.AR.ordinal()];
                    if (ar != null) ar.nextOverlay();
                }
                return true;

            case Input.Keys.P:
                if (currentMode == Mode.AR) {
                    ARVisualizer ar = (ARVisualizer) visualizers[Mode.AR.ordinal()];
                    if (ar != null) ar.setScreenProjected(!ar.isScreenProjected());
                }
                return true;

            case Input.Keys.M:
                screenCapture.nextMonitor();
                return true;

            case Input.Keys.R:
                activeVis.resetCamera();
                return true;

            case Input.Keys.ESCAPE:
                Gdx.app.exit();
                return true;
        }
        return false;
    }

    /** Handles tab-bar clicks. */
    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (!hudVisible) return false;

        final float sw   = Gdx.graphics.getWidth();
        final float sh   = Gdx.graphics.getHeight();
        final float gdxY = sh - screenY;

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
        hudFont.getData().setScale(FONT_BASE_SCALE);

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

    @Override public boolean keyUp(int k)                                { return false; }
    @Override public boolean keyTyped(char c)                            { return false; }
    @Override public boolean touchUp(int x, int y, int p, int b)        { return false; }
    @Override public boolean touchDragged(int x, int y, int p)          { return false; }
    @Override public boolean mouseMoved(int x, int y)                    { return false; }
    @Override public boolean scrolled(float ax, float ay)                { return false; }
    @Override public boolean touchCancelled(int x, int y, int p, int b) { return false; }
}
