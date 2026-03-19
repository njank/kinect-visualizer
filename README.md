# Kinect Visualizer

A real-time Kinect v2 visualizer built with **libGDX** (LWJGL3 desktop backend) and the **J4K (Java for Kinect)** SDK.  
Five interactive modes let you explore colour, skeleton, and depth data from a Kinect v2 sensor.

---

## Modes

| Key | Mode | Description |
|-----|------|-------------|
| `1` | **Camera** | Full-resolution colour feed (1920 x 1080) from the Kinect colour camera. Optional skeleton overlay aligned to the colour image via the depth-to-colour UV map. |
| `2` | **AR** | UV-mapped 3-D point cloud: every depth pixel (512 x 424) is placed at its metric world position and coloured with the matching colour-image texel. Supports screen capture and audio overlays (see below). |
| `3` | **Depth** | Depth point cloud colour-graded by distance: red (near ~0.5 m) to green to blue (far ~5 m). |

---

## AR Mode Overlays

Press **O** to cycle the screen-capture overlay applied on top of the point cloud.

| Overlay | Description |
|---------|-------------|
| `NONE` | Pure AR - camera colour only, no overlay. |
| `LINEAR_DODGE` | Linear Dodge (Add): desktop frame added to the point cloud. Black screen areas cause no change; bright areas push toward white. |
| `SUBTRACT` | Subtract: desktop frame subtracted from the point cloud. Black screen areas cause no change; bright areas darken the result. |

When a screen overlay is active, press **P** to toggle between:

- **2-D flat** - screen texture drawn as a fullscreen quad over the viewport.
- **3-D projective** - screen texture projected onto the 3-D point-cloud mesh. Each depth point samples the screen pixel it projects to, so the overlay conforms to the 3-D geometry as you orbit around it.

---

## AR Mode Toggles

| Key | Toggle | Default | Notes |
|-----|--------|---------|-------|
| `A` | Audio Z-push | OFF | System audio drives Z displacement of the depth cloud. Each pixel's push magnitude is determined by the FFT band value for its radial ring position. Near pixels protrude more than far pixels. No colour change - camera colours are preserved. |
| `S` | Skeleton overlay | OFF | Draws joints and bones in 3-D world space using the same orbit camera, so the skeleton tracks the point cloud at every zoom, pan, and angle. |
| `O` | Screen overlay | NONE | Cycles through NONE, LINEAR_DODGE, SUBTRACT. |
| `P` | Projective screen | OFF | Only visible when a screen overlay is active. |

---

## Global Keys

| Key | Action |
|-----|--------|
| `1` / `2` / `3` | Switch mode |
| `H` | Toggle HUD (tab bar + hints + FPS) |
| `S` | Toggle skeleton overlay on the active mode |
| `R` | Reset orbit camera to default position |
| `Escape` | Quit |

**AR mode only:**

| Key | Action |
|-----|--------|
| `O` | Cycle screen overlay (NONE - LINEAR_DODGE - SUBTRACT) |
| `A` | Toggle audio Z-push displacement |
| `P` | Toggle 3-D projective screen rendering |
| `M` | Cycle to the next monitor for screen capture |

**3-D mode camera controls (AR and Depth):**

| Input | Action |
|-------|--------|
| Left-drag | Orbit (yaw / pitch) |
| Right-drag | Pan |
| Scroll wheel | Zoom |
| `R` | Reset camera to default |

---

## Requirements

| Requirement                   | Details |
|-------------------------------|---------|
| Kinect v2 sensor              | Connected via USB 3.0 |
| Kinect for Windows Runtime v2 | [Microsoft Download](https://www.microsoft.com/en-us/download/details.aspx?id=44559) |
| Kinect for Windows SDK v2     | [Microsoft Download](https://www.microsoft.com/en-us/download/details.aspx?id=44561) |
| J4K library (`ufdw.jar`)      | Place in `libs/` at project root. Download from [J4K project page](https://abarmpou.github.io/ufdw/j4k/). |
| `WasapiLoopback.dll` (x64)    | Required for audio Z-push. Place in `assets/`. Missing DLL degrades gracefully - audio toggle has no effect. |
| `ScreenDuplicator.dll` (x64)  | Required for screen capture ov[erlays. Place in `assets/`. Missing DLL degrades gracefully - overlay is skipped. |

---

]()## Project Structure

```
kinect/
+-- core/
|   +-- src/main/java/
|       +-- at/njank/kinect/
|       |   +-- Main.java                   Application entry, tab bar HUD, mode switching
|       |   +-- Visualizer.java             Common interface for all visualizer modes
|       |   +-- KinectManager.java          J4K wrapper; bridges Kinect threads to GL thread
|       |   +-- OrbitCamera.java            Shared orbit/pan/zoom camera (state preserved on switch)
|       |   +-- SkeletonConstants.java      Bone topology, joint count, per-body colours
|       |   +-- CameraVisualizer.java       Mode 1 - colour feed + UV-aligned skeleton
|       |   +-- ARVisualizer.java           Mode 2 - UV-mapped 3-D point cloud, overlays, audio push
|       |   +-- DepthVisualizer.java        Mode 3 - depth-coloured point cloud
|       |   +-- SkeletonVisualizer2D.java   Flat skeleton helper (used by Camera mode)
|       +-- at/njank/capture/audio/
|       |   +-- WasapiLoopbackCapture.java  JNA wrapper for WasapiLoopback.dll
|       |   +-- FFTAnalyzer.java            Per-band AGC + tanh compression FFT analyser
|       +-- at/njank/capture/screen/
|           +-- ScreenCapture.java          Shared DXGI screen capture manager
|           +-- ScreenDuplicator.java       JNI wrapper for screen_duplicator.dll
|                                           (package must stay com.example.capture - see note below)
+-- lwjgl3/                                 Desktop launcher and packaging
+-- libs/                                   Local JARs (ufdw.jar - not committed)
+-- assets/                                 Runtime working directory
|       WasapiLoopback.dll                  Place here for audio push
|       screen_duplicator.dll               Place here for screen capture
+-- README.md
```

---

## Architecture Notes

### Visualizer interface

All three modes implement `Visualizer`:

```java
interface Visualizer {
    default void create() {}           // allocate GPU resources once
    void render(KinectManager kinect); // draw one complete frame
    void resize(int w, int h);         // handle window resize
    void dispose();                    // release GPU resources
    InputProcessor getInputProcessor();
    default void resetCamera() {}
    default void setSkeletonEnabled(boolean e) {}
    default boolean isSkeletonEnabled() { return false; }
}
```

### KinectManager

Wraps J4K and exposes four volatile data streams read safely by the GL thread:

| Accessor | Content |
|----------|---------|
| `getSkeletons()` | Up to 6 tracked `Skeleton` objects |
| `getColorFrame()` | BGRA bytes at 1920 x 1080 |
| `getDepthXYZ()` | Per-pixel 3-D positions in metres (512 x 424 x 3 floats) |
| `getDepthUV()` | Per-pixel colour-image UVs (512 x 424 x 2 floats) |

### OrbitCamera

Shared by AR and Depth modes. State (`yaw`, `pitch`, `zoom`, `panX`, `panY`) is saved in `Main` before each mode switch and restored on return via `getState()` / `setState()`. Mouse position is reseeded via `seedMouse()` on switch to prevent the first frame delta from jumping the camera.

### ScreenCapture

Holds one `ScreenDuplicator` and one GPU texture. Updated each frame via non-blocking `captureFrame()` + `glTexSubImage2D`. The shared instance is created once in `Main` and injected into `ARVisualizer`. Monitor cycling is handled by `nextMonitor()` which disposes the old duplicator and opens the next index, falling back to index 0 if the target monitor does not exist.

### AR Audio Z-push

When audio push is enabled (`A` key), `WasapiLoopbackCapture` captures system audio via WASAPI loopback. `FFTAnalyzer` produces 32 AGC-normalised frequency bands per frame. Each depth pixel is mapped to a band by its aspect-ratio-corrected radial distance from the depth image centre (centre = lowest band, corners = highest). The push formula is:

```
t          = clamp((z - 0.5) / 4.5, 0, 1)
depthScale = (1 - t) ^ 2          // near pixels protrude most
push       = band ^ 15 * 0.35m * depthScale
worldZ     = -kinectZ + push
```

The high exponent (`^15`) means only loud beats produce visible protrusion; quiet signals leave the geometry flat.

---

## Troubleshooting

**"Could not open Kinect v2"**
- Ensure Kinect for Windows Runtime v2 is installed.
- Confirm the sensor is connected to a USB 3.0 port.
- Only one application can own the sensor at a time.

**Screen capture overlay not visible**
- Confirm `ScreenDuplicator.dll` is in `assets/`.
- Check the log for `[ScreenDuplicator] screen_duplicator.dll not found`.

**Audio push not working**
- Confirm `WasapiLoopback.dll` is in `assets/`.
- Press `A` in AR mode to enable (indicator in HUD: `A:[audio ON]`).
- Check the log for `WasapiLoopback not available`.

**Skeleton misaligned in Camera mode**
The skeleton uses the depth-to-colour UV map (`getDepthUV()`) to align joints to the colour image. Ensure the Kinect is streaming with `UV` flag enabled.
