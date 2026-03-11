package at.njank.kinect;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.ScreenUtils;
import edu.ufl.digitalworlds.j4k.Skeleton;

import java.nio.ByteBuffer;

import static at.njank.kinect.SkeletonConstants.*;

/**
 * Visualizer mode 2 – "Camera".
 *
 * Streams the Kinect v2 colour image (1920 × 1080, BGRA) into a GPU texture
 * and composites a 2-D skeleton overlay on top.
 *
 * <p>The BGRA → RGBA conversion is done on the CPU into the Pixmap's native
 * buffer each frame a new colour frame is available.</p>
 */
public class CameraVisualizer implements Visualizer {

    private final SpriteBatch          batch       = new SpriteBatch();
    private final SkeletonVisualizer2D overlay    = new SkeletonVisualizer2D();
    private final BitmapFont           font        = new BitmapFont();

    // GPU texture backed by a persistent Pixmap so we can do sub-image updates.
    private Pixmap  colorPixmap;
    private Texture colorTexture;
    private boolean textureReady = false;

    // Track the last byte[] reference so we only re-upload on new frames.
    private byte[] lastFrame = null;

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
            // No colour data yet – show a placeholder message.
            batch.begin();
            font.draw(batch, "Waiting for Kinect colour stream...", 20, screenH / 2f);
            batch.end();
        } else {
            // Lazy-create the texture on the first real frame.
            if (!textureReady) {
                colorPixmap  = new Pixmap(COLOR_W, COLOR_H, Pixmap.Format.RGBA8888);
                colorTexture = new Texture(colorPixmap);
                colorTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
                textureReady = true;
            }

            // Only re-upload when we have a new frame (reference change).
            if (frame != lastFrame) {
                lastFrame = frame;
                convertBgraToRgba(frame, colorPixmap.getPixels(), COLOR_W * COLOR_H);
                colorTexture.draw(colorPixmap, 0, 0);
            }

            // Draw the colour texture, fitting it to the screen.
            // flipY=true corrects Kinect's top-left origin to libGDX's bottom-left.
            batch.begin();
            batch.draw(colorTexture,
                0, 0, screenW, screenH,
                0, 0, COLOR_W, COLOR_H,
                false, false);
            batch.end();
        }

        // Skeleton overlay drawn on top.
        overlay.setProjection(screenW, screenH);
        overlay.renderOverlay(kinect.getSkeletons(), screenW, screenH);
    }

    @Override
    public void resize(int w, int h) {
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        overlay.setProjection(w, h);
    }

    @Override
    public void dispose() {
        batch.dispose();
        overlay.dispose();
        font.dispose();
        if (textureReady) {
            colorTexture.dispose();
            colorPixmap.dispose();
        }
    }

    @Override
    public InputProcessor getInputProcessor() { return null; }

    // -----------------------------------------------------------------------
    // BGRA → RGBA conversion (in-place into the Pixmap's native ByteBuffer)
    // -----------------------------------------------------------------------

    /**
     * Converts {@code pixelCount} pixels from Kinect BGRA format into the
     * RGBA format expected by the libGDX Pixmap / OpenGL texture.
     */
    private static void convertBgraToRgba(byte[] bgra, ByteBuffer rgba, int pixelCount) {
        rgba.clear();
        for (int i = 0, n = pixelCount * 4; i < n; i += 4) {
            rgba.put(bgra[i + 2]); // R  ← B slot
            rgba.put(bgra[i + 1]); // G
            rgba.put(bgra[i    ]); // B  ← R slot
            rgba.put(bgra[i + 3]); // A
        }
        rgba.rewind();
    }
}
