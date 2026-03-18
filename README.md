# Kinect Visualizer

A real-time Kinect v2 visualizer built with **libGDX** (LWJGL3 desktop backend) and the **J4K (Java for Kinect)** SDK.  
Five interactive modes let you explore colour, skeleton, and depth data from a Kinect v2 sensor.

---

## Modes

| Key | Mode | Description |
|-----|------|-------------|
| `1` | **Camera** | Full-resolution colour feed (1920 × 1080) with a 2-D skeleton overlay. |
| `2` | **2D Skeleton** | Flat skeleton projection on a dark background. Joint positions come from J4K's normalised [0, 1] depth-space coordinates. |
| `3` | **3D Skeleton** | Metric 3-D skeleton rendered with lit sphere joints and bone lines. Includes a ground-plane reference grid. |
| `4` | **AR** | Augmented Reality – every depth pixel placed at its real-world (x, y, z) position and coloured by the matching colour-image texel, producing a navigable 3-D scene reconstruction. |
| `5` | **Depth** | Depth point cloud colour-graded by distance: red (near, ~0.5 m) → green → blue (far, ~5 m). |
| `6` | **Audio** | Depth cloud with Z displacement and colour brightness intensified in real time by the system audio spectrum. Each pixel's radial position maps to a frequency band (same log-curve as the standalone AudioVisualizer). Bass energy at the edges, treble at the centre. Requires `WasapiLoopback.dll`. |

### 3-D mode controls (modes 3, 4, 5, 6)

| Input | Action |
|-------|--------|
| Left-drag | Orbit (yaw / pitch) |
| Right-drag | Pan |
| Scroll wheel | Zoom |
| `R` | Reset camera to default position |
| `Esc` | Quit |

---

## Requirements

| Requirement | Version |
|-------------|---------|
| Kinect v2 sensor | — |
| Kinect for Windows Runtime v2 | [Microsoft Download](https://www.microsoft.com/en-us/download/details.aspx?id=44559) |
| Kinect for Windows SDK v2 | [Microsoft Download](https://www.microsoft.com/en-us/download/details.aspx?id=44561) |

> **Windows only.** The Kinect v2 SDK and its runtime are Windows-exclusive.

---

## Project structure

```
kinect/
├── core/                          # Platform-agnostic application code
│   └── src/main/java/at/njank/kinect/
│       ├── Main.java              # App entry point, tab-bar HUD, mode switching
│       ├── KinectManager.java     # J4K wrapper – bridges Kinect threads to libGDX render thread
│       ├── OrbitCamera.java       # Shared orbit/pan/zoom camera for 3-D modes
│       ├── Visualizer.java        # Common interface for all visualizer modes
│       ├── CameraVisualizer.java  # Mode 1 – colour feed + skeleton overlay
│       ├── SkeletonVisualizer2D.java  # Mode 2 – flat 2-D skeleton
│       ├── SkeletonVisualizer3D.java  # Mode 3 – metric 3-D skeleton
│       ├── ARVisualizer.java      # Mode 4 – UV-mapped 3-D point cloud (AR)
│       ├── DepthVisualizer.java   # Mode 5 – depth-coloured point cloud
│       ├── AudioVisualizer.java   # Mode 6 – depth cloud + audio-reactive Z/colour
│       ├── WasapiLoopbackCapture.java  # JNA wrapper for WasapiLoopback.dll
│       ├── FFTAnalyzer.java       # Per-band AGC + tanh compression FFT analyser
│       └── SkeletonConstants.java # Bone topology, joint count, per-body colours
├── lwjgl3/                        # Desktop (LWJGL3) launcher and packaging
│   └── src/main/java/.../Lwjgl3Launcher.java
├── libs/                          # Local JARs (not committed – see setup below)
│   └── ufdw.jar
└── README.md
```

---

## Architecture notes

### Visualizer interface

All four modes implement `Visualizer`:

```java
public interface Visualizer {
    default void create() {}          // allocate GPU resources (called once)
    void render(KinectManager kinect); // draw one frame
    void resize(int w, int h);         // handle window resize
    void dispose();                    // release GPU resources
    InputProcessor getInputProcessor();// optional per-mode input (null = none)
    default void resetCamera() {}      // reset orbit camera (no-op for 2-D modes)
}
```

### KinectManager

`KinectManager` wraps the J4K `J4KSDK` and exposes four volatile data streams that the render thread reads safely without locking:

| Accessor | Content |
|----------|---------|
| `getSkeletons()` | Array of up to 6 tracked `Skeleton` objects |
| `getColorFrame()` | Raw BGRA bytes at 1920 × 1080 |
| `getDepthXYZ()` | Per-pixel 3-D positions in metres (`float[512×424×3]`) |
| `getDepthUV()` | Per-pixel colour-image UVs (`float[512×424×2]`) |

### AudioVisualizer

`AudioVisualizer` builds on top of the AR point-cloud pipeline.  Each depth pixel
carries an extra `intensity` vertex attribute — the smoothed FFT band value [0, 1]
for its radial position in the depth image.

The GLSL shader applies it in two places:
- **Vertex stage** — `z *= (1 + intensity × Z_INTENSIFY)` stretches the cloud
  outward on loud beats.
- **Fragment stage** — `colour.rgb *= (1 + intensity × COLOR_BOOST)` brightens
  pixels whose frequency band is currently loud.

Band mapping uses the same logarithmic radial curve as the standalone
`AudioVisualizerGame`: outer pixels → low-frequency bands (bass at the edges);
inner pixels → high-frequency bands (treble at the centre).

### OrbitCamera

`OrbitCamera` is a shared helper used by `SkeletonVisualizer3D`, `ARVisualizer`, and `DepthVisualizer`.  
It polls `Gdx.input` **every frame** (rather than using event callbacks) to ensure smooth orbit even at high mouse speeds.  Constructor parameters set the default position, which is also restored on `reset()`.

---

## Troubleshooting

**"Could not open Kinect v2"**
- Confirm the Kinect for Windows Runtime v2 is installed.
- Make sure the sensor is plugged into a USB 3.0 port.

**Skeleton not visible in Camera mode**  
The skeleton overlay uses depth-space joint projections.  The person must be within the Kinect's tracking range (~0.5 m – 4.5 m) and facing the sensor.

---

## References

A. Barmpoutis. 'Tensor Body: Real-time Reconstruction of the Human Body and Avatar Synthesis from RGB-D', IEEE Transactions on Cybernetics, Special issue on Computer Vision for RGB-D Sensors: Kinect and Its Applications, October 2013, Vol. 43(5), Pages: 1347-1356.
