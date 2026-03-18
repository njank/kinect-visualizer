package at.njank.kinect;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.ScreenUtils;
import edu.ufl.digitalworlds.j4k.Skeleton;

import java.nio.ByteBuffer;

import static at.njank.kinect.KinectManager.DEPTH_H;
import static at.njank.kinect.KinectManager.DEPTH_W;
import static at.njank.kinect.SkeletonConstants.*;

/**
 * Visualizer mode 1 - "Camera".
 *
 * <p>Streams the Kinect v2 colour image (1920 × 1080, BGRA) into a GPU
 * texture and composites a 2-D skeleton overlay on top.
 *
 * <h3>Coordinate alignment</h3>
 * The Kinect v2 has two separate cameras: a depth sensor (512 × 424) and a
 * colour camera (1920 × 1080). They have different fields of view, focal
 * lengths, and are physically offset from one another. Using
 * {@code Skeleton.get2DJoint()} — which projects joints through the
 * <em>depth</em> camera's intrinsics — produces systematic misalignment on
 * the colour image.
 *
 * <p>The fix uses the depth→colour UV map provided by J4K (same data that
 * powers the AR point cloud). For each joint:
 * <ol>
 *   <li>Find its depth-image pixel: {@code get2DJoint(j, DEPTH_W, DEPTH_H)}
 *   <li>Look up the colour-image UV at that depth pixel from the UV map
 *   <li>Convert UV → screen position inside the letterboxed image rect:
 *       {@code sx = drawX + u × drawW},
 *       {@code sy = drawY + (1 − v) × drawH}
 *       (the {@code (1 − v)} flip accounts for libGDX's bottom-up Y axis)
 * </ol>
 *
 * <h3>Aspect-correct display</h3>
 * The colour image is drawn letterboxed (or pillar-boxed) to preserve its
 * 16 : 9 aspect ratio at any window size. The same draw rectangle is used
 * for the UV → screen conversion so the skeleton stays aligned when the
 * window is resized.
 */
public class CameraVisualizer implements Visualizer {

    private static final float JOINT_RADIUS   = 8f;
    private static final float BONE_THICKNESS = 4f;

    private final SpriteBatch  batch  = new SpriteBatch();
    private final ShapeRenderer sr    = new ShapeRenderer();
    private final BitmapFont    font  = new BitmapFont();

    // GPU texture backed by a persistent Pixmap for sub-image updates.
    private Pixmap  colorPixmap;
    private Texture colorTexture;
    private boolean textureReady = false;

    // Frame-dedup sentinel — only re-upload when the reference changes.
    private byte[] lastFrame;

    /** Whether to draw the skeleton overlay.  Toggled by the S key (default off). */
    private boolean skeletonEnabled = false;

    // -----------------------------------------------------------------------
    // Visualizer
    // -----------------------------------------------------------------------

    @Override
    public void render(KinectManager kinect) {
        ScreenUtils.clear(0f, 0f, 0f, 1f);

        float screenW = Gdx.graphics.getWidth();
        float screenH = Gdx.graphics.getHeight();

        byte[] frame = kinect.getColorFrame();

        if (frame == null) {
            batch.begin();
            font.draw(batch, "Waiting for Kinect colour stream...", 20, screenH / 2f);
            batch.end();
            return;
        }

        // Lazy-create texture on the first real frame.
        if (!textureReady) {
            colorPixmap  = new Pixmap(COLOR_W, COLOR_H, Pixmap.Format.RGBA8888);
            colorTexture = new Texture(colorPixmap);
            colorTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            textureReady = true;
        }

        // Re-upload only when a new frame arrives.
        if (frame != lastFrame) {
            lastFrame = frame;
            convertBgraToRgba(frame, colorPixmap.getPixels(), COLOR_W * COLOR_H);
            colorTexture.draw(colorPixmap, 0, 0);
        }

        // --- Letterbox rect — preserves 16:9 regardless of window shape ---
        float imageAspect  = (float) COLOR_W / COLOR_H; // ≈ 1.778
        float screenAspect = screenW / screenH;
        float drawW, drawH, drawX, drawY;
        if (screenAspect > imageAspect) {
            // Screen wider than 16:9 → pillarbox
            drawH = screenH;
            drawW = screenH * imageAspect;
            drawX = (screenW - drawW) * 0.5f;
            drawY = 0;
        } else {
            // Screen taller than 16:9 → letterbox
            drawW = screenW;
            drawH = screenW / imageAspect;
            drawX = 0;
            drawY = (screenH - drawH) * 0.5f;
        }

        // Draw colour image into the letterbox rect.
        batch.begin();
        batch.draw(colorTexture,
            drawX, drawY, drawW, drawH,
            0, 0, COLOR_W, COLOR_H,
            false, false);
        batch.end();

        // --- Skeleton overlay — UV-mapped to colour-camera space ---
        Skeleton[] skeletons = kinect.getSkeletons();
        float[]    depthUV   = kinect.getDepthUV();
        if (skeletonEnabled && skeletons != null && depthUV != null) {
            drawColorSpaceSkeleton(skeletons, depthUV,
                                   drawX, drawY, drawW, drawH);
        }
    }

    @Override
    public void resize(int w, int h) {
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        sr.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
    }

    @Override
    public void dispose() {
        batch.dispose();
        sr.dispose();
        font.dispose();
        if (textureReady) {
            colorTexture.dispose();
            colorPixmap.dispose();
        }
    }

    @Override
    public void setSkeletonEnabled(boolean enabled) { skeletonEnabled = enabled; }

    @Override
    public boolean isSkeletonEnabled() { return skeletonEnabled; }

    @Override
    public InputProcessor getInputProcessor() { return null; }

    // -----------------------------------------------------------------------
    // Colour-space skeleton overlay
    // -----------------------------------------------------------------------

    /**
     * Draws skeleton joints and bones aligned to the colour camera image.
     *
     * <p>Each joint is first located in depth-image pixel space via
     * {@code get2DJoint(j, DEPTH_W, DEPTH_H)}.  The depth→colour UV map is
     * then sampled at that pixel to obtain a normalised colour-image
     * coordinate, which is finally transformed into the letterbox draw rect.
     *
     * @param skeletons tracked skeletons from {@link KinectManager#getSkeletons()}
     * @param depthUV   depth→colour UV map from {@link KinectManager#getDepthUV()};
     *                  interleaved pairs {@code [u0, v0, u1, v1, ...]},
     *                  one entry per depth pixel (DEPTH_W × DEPTH_H)
     * @param drawX     left edge of the letterboxed image on screen
     * @param drawY     bottom edge of the letterboxed image on screen (libGDX Y-up)
     * @param drawW     pixel width  of the letterboxed image
     * @param drawH     pixel height of the letterboxed image
     */
    private void drawColorSpaceSkeleton(Skeleton[] skeletons, float[] depthUV,
                                        float drawX, float drawY,
                                        float drawW, float drawH) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        sr.begin(ShapeRenderer.ShapeType.Filled);

        for (int s = 0; s < skeletons.length; s++) {
            Skeleton sk = skeletons[s];
            if (sk == null) continue;

            Color color = SKELETON_COLORS[s % SKELETON_COLORS.length];

            // --- Bones ---
            sr.setColor(color);
            for (int[] bone : BONES) {
                float[] a = colorScreenPos(sk, bone[0], depthUV, drawX, drawY, drawW, drawH);
                float[] b = colorScreenPos(sk, bone[1], depthUV, drawX, drawY, drawW, drawH);
                if (a == null || b == null) continue;
                sr.rectLine(a[0], a[1], b[0], b[1], BONE_THICKNESS);
            }

            // --- Joints: dark halo + bright fill ---
            for (int j = 0; j < JOINT_COUNT; j++) {
                float[] p = colorScreenPos(sk, j, depthUV, drawX, drawY, drawW, drawH);
                if (p == null) continue;
                sr.setColor(color.r * 0.3f, color.g * 0.3f, color.b * 0.3f, 1f);
                sr.circle(p[0], p[1], JOINT_RADIUS + 3f);
                sr.setColor(color);
                sr.circle(p[0], p[1], JOINT_RADIUS);
            }
        }

        sr.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /**
     * Maps a skeleton joint to a 2-element {@code [screenX, screenY]} array
     * within the letterboxed image rect using the depth→colour UV map.
     *
     * <p>Returns {@code null} if the joint has no valid depth pixel or if its
     * colour-image UV lies outside [0, 1] (joint outside the colour FOV).
     */
    private static float[] colorScreenPos(Skeleton sk, int joint,
                                          float[] depthUV,
                                          float drawX, float drawY,
                                          float drawW, float drawH) {
        // Step 1: project joint into depth-image pixel space.
        int[] dp  = sk.get2DJoint(joint, DEPTH_W, DEPTH_H);
        int   dx  = dp[0];
        int   dy  = dp[1];

        // Discard joints that fall outside the depth image bounds.
        if (dx < 0 || dx >= DEPTH_W || dy < 0 || dy >= DEPTH_H) return null;

        // Step 2: look up the colour-image UV at this depth pixel.
        int   uvIdx = (dy * DEPTH_W + dx) * 2;
        float u     = depthUV[uvIdx];
        float v     = depthUV[uvIdx + 1];

        // Discard invalid mappings (J4K uses negative UV as sentinel).
        if (u < 0f || u > 1f || v < 0f || v > 1f) return null;

        // Step 3: convert UV to screen position inside the draw rect.
        // U goes left→right: sx = drawX + u × drawW
        // V goes top→bottom in image space; libGDX Y is bottom-up:
        //   sy = drawY + (1 − v) × drawH
        float sx = drawX + u * drawW;
        float sy = drawY + (1f - v) * drawH;

        return new float[]{ sx, sy };
    }

    // -----------------------------------------------------------------------
    // BGRA → RGBA conversion (in-place into the Pixmap's native ByteBuffer)
    // -----------------------------------------------------------------------

    /**
     * Converts {@code pixelCount} pixels from Kinect BGRA into the RGBA
     * format expected by the libGDX Pixmap / OpenGL texture.
     */
    private static void convertBgraToRgba(byte[] bgra, ByteBuffer rgba, int pixelCount) {
        rgba.clear();
        for (int i = 0, n = pixelCount * 4; i < n; i += 4) {
            rgba.put(bgra[i + 2]); // R ← B slot
            rgba.put(bgra[i + 1]); // G
            rgba.put(bgra[i    ]); // B ← R slot
            rgba.put(bgra[i + 3]); // A
        }
        rgba.rewind();
    }
}
