package at.njank.kinect;

/**
 * Lightweight FFT analyser with per-band AGC and soft compression.
 *
 * <p>Used by {@link AudioVisualizer} to convert raw PCM samples from
 * {@link WasapiLoopbackCapture} into a normalised per-band magnitude array
 * that is mapped onto the Kinect depth point-cloud.
 *
 * <hr>
 * <h3>Why bars max out on modern music</h3>
 * Modern tracks are mastered at near-0 dBFS (the "loudness war").  A fixed
 * dB floor/range normalisation like {@code (db + 80) / 80} maps most music
 * energy to values well above 1.0, so every bar clips solid.
 *
 * <h3>Solution: per-band AGC + soft compression</h3>
 * <ol>
 *   <li><b>Per-band AGC</b> — each band independently tracks its own recent
 *       peak and normalises against it.  The bars use the <em>full</em> height
 *       range relative to that song's actual dynamics, not a fixed dB ceiling.
 *       Quiet passages open up; loud passages don't clip.
 *       {@link #AGC_PEAK_DECAY} controls how fast the peak releases.</li>
 *   <li><b>Soft-knee compression</b> — instead of a hard [0,1] clamp, a
 *       tanh-based curve gently rolls off values above ~0.7, so peaks feel
 *       "rounded" rather than brick-walled.
 *       {@link #COMPRESSION} controls how aggressively it squashes.</li>
 * </ol>
 *
 * <hr>
 * <h3>Tuning constants</h3>
 * <pre>
 *  ATTACK           [0..1]  Bar rise speed. 0.92 = snappy. 0.60 = sluggish.
 *
 *  DECAY            [0..1]  Bar fall speed. 0.10 = slow linger. 0.35 = tight.
 *
 *  DB_FLOOR         (dB)    Hard silence floor. -70 is standard.
 *                           Raise to -50 to cut more background noise.
 *
 *  AGC_PEAK_DECAY   [0..1]  How fast the per-band peak ceiling falls back down.
 *                           0.998 = very slow (adapts over ~5 s) — recommended.
 *                           0.990 = faster, adapts in ~1 s.
 *                           0.970 = quick, chases every loud hit.
 *
 *  AGC_MIN_DB       (dB)    Minimum peak level the AGC assumes even in silence.
 *                           Prevents division-by-zero and stops the AGC from
 *                           wildly amplifying background noise between songs.
 *                           -20 dB is a good noise gate threshold.
 *
 *  COMPRESSION      [>0]    Soft-knee curve steepness fed into tanh.
 *                           1.5 = gentle S-curve, peaks slightly rounded.
 *                           2.5 = moderate — bars rarely hit full height.
 *                           4.0 = strong — only the loudest transients peak.
 *
 *  PERCEPTUAL_BOOST         Extra dB per band index (treble tilt compensation).
 *                           0.0 = raw FFT. 0.4 = gentle. 0.8 = strong lift.
 *                           With AGC active, keep this modest (0.3–0.5) since
 *                           AGC already self-balances each band.
 * </pre>
 */
public class FFTAnalyzer {

    // -----------------------------------------------------------------------
    // Tuning
    // -----------------------------------------------------------------------

    /** Bar rise speed [0..1].  Higher = snappier attack. */
    private static final float ATTACK           = 0.92f;

    /** Bar fall speed [0..1].  Lower = longer linger / afterglow. */
    private static final float DECAY            = 0.10f;

    /** Hard silence floor in dB.  Signals below this map to 0. */
    private static final float DB_FLOOR         = -70f;

    /**
     * Per-band AGC peak release rate [0..1] per frame.
     * The tracked peak multiplies by this value each frame, slowly sliding
     * down until it meets the actual signal again.
     *
     * <pre>
     *  0.999 = very slow (~10 s to halve) — smooth, song-level adaptation
     *  0.997 = recommended (~3–4 s)
     *  0.990 = fast (~1 s), chases loud hits quickly
     * </pre>
     */
    private static final float AGC_PEAK_DECAY   = 0.997f;

    /**
     * Minimum assumed peak level for AGC normalisation (in dB above
     * {@link #DB_FLOOR}).  Prevents the AGC from amplifying silence into
     * noise between tracks.  Raise this if background hiss becomes visible
     * when music stops.
     *
     * <pre>
     *  15f = aggressive noise gate (bars stay low during near-silence)
     *  25f = moderate — recommended
     *  40f = loose; bars animate even on quiet background noise
     * </pre>
     */
    private static final float AGC_MIN_DB       = 25f;

    /**
     * Soft-knee compression via tanh curve.  Applied after AGC normalisation.
     * Prevents bands from sitting at maximum on every loud beat.
     *
     * <pre>
     *  1.5 = very gentle rounding of peaks
     *  2.5 = moderate — recommended for modern mastered music
     *  4.0 = strong squash; only transient hits reach full amplitude
     * </pre>
     */
    private static final float COMPRESSION      = 2.5f;

    /**
     * Perceptual tilt: extra dB per band index (applied before AGC).
     * With AGC active this is less critical, but still helps high bands
     * visually match the energy of bass on very trebly material.
     *
     * <pre>
     *  0.0 = flat  |  0.3 = gentle (recommended with AGC)  |  0.6 = strong
     * </pre>
     */
    private static final float PERCEPTUAL_BOOST = 0.3f;

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private final int     fftSize;
    private final int     bands;
    private final float[] window;
    private final float[] re;
    private final float[] im;
    private final float[] magnitudes;
    private final float[] bandValues;
    private final float[] smoothed;
    /** Per-band running peak used by the AGC (in dB above {@link #DB_FLOOR}). */
    private final float[] agcPeak;

    /**
     * Constructs an analyser.
     *
     * @param fftSize  FFT window size — must be a power of 2 (e.g. 2048).
     *                 Larger values give better bass frequency resolution.
     * @param bands    Number of output frequency bands (e.g. 48).
     *                 Must satisfy {@code bands ≤ fftSize / 2}.
     */
    public FFTAnalyzer(int fftSize, int bands) {
        if (Integer.bitCount(fftSize) != 1)
            throw new IllegalArgumentException("fftSize must be a power of 2");
        this.fftSize    = fftSize;
        this.bands      = bands;
        this.window     = new float[fftSize];
        this.re         = new float[fftSize];
        this.im         = new float[fftSize];
        this.magnitudes = new float[fftSize / 2];
        this.bandValues = new float[bands];
        this.smoothed   = new float[bands];
        this.agcPeak    = new float[bands];

        // Initialise AGC peaks to the minimum so bars don't jump on startup
        for (int b = 0; b < bands; b++) agcPeak[b] = AGC_MIN_DB;

        // Pre-compute Hann window coefficients
        for (int i = 0; i < fftSize; i++)
            window[i] = 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (fftSize - 1)));
    }

    /**
     * Analyses {@code samples} and updates the smoothed band values returned
     * by {@link #getBandValues()}.
     *
     * <p>Should be called once per render frame with the most recent mono PCM
     * snapshot from {@link WasapiLoopbackCapture#getSamples()}.
     *
     * @param samples  Mono PCM samples in the range {@code [-1, 1]}.
     *                 At least {@code fftSize} elements are used; excess ignored.
     */
    public void analyze(float[] samples) {
        int len = Math.min(samples.length, fftSize);

        for (int i = 0; i < len; i++) { re[i] = samples[i] * window[i]; im[i] = 0f; }
        for (int i = len; i < fftSize; i++) { re[i] = 0; im[i] = 0; }

        fft(re, im, fftSize);

        for (int i = 0; i < fftSize / 2; i++)
            magnitudes[i] = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]);

        // Log-scale binning: bin 0 ≈ 20 Hz, last bin ≈ Nyquist
        int   nyquistBins = fftSize / 2;
        float logMin      = (float) Math.log10(1);
        float logMax      = (float) Math.log10(nyquistBins);

        for (int b = 0; b < bands; b++) {
            float startLog = logMin + (logMax - logMin) *  b      / bands;
            float endLog   = logMin + (logMax - logMin) * (b + 1) / bands;
            int   startBin = (int) Math.pow(10, startLog);
            int   endBin   = Math.min((int) Math.pow(10, endLog), nyquistBins - 1);

            float sum = 0; int count = 0;
            for (int k = startBin; k <= endBin; k++) { sum += magnitudes[k]; count++; }
            float raw = count > 0 ? sum / count : 0f;

            // Convert to dB with perceptual tilt toward high bands
            float db = (float)(20.0 * Math.log10(raw + 1e-6f));
            db += PERCEPTUAL_BOOST * b;

            // dB headroom above the hard floor (linear scale)
            float dbAboveFloor = Math.max(0f, db - DB_FLOOR);

            // ── Per-band AGC ──────────────────────────────────────────────
            // Track the running peak; decay it slowly each frame so it adapts
            // to the long-term loudness of whatever is playing.
            agcPeak[b] = Math.max(
                Math.max(agcPeak[b] * AGC_PEAK_DECAY, AGC_MIN_DB),
                dbAboveFloor);

            // Normalise against the running peak → [0, 1] where 1 = recent loudest
            float norm = dbAboveFloor / agcPeak[b];

            // ── Soft-knee compression via tanh ────────────────────────────
            // tanh(x * k) / tanh(k) maps [0,1]→[0,1] with a soft ceiling so
            // only the loudest transients actually reach 1.0.
            float tanhK = (float) Math.tanh(COMPRESSION);
            norm = (float)(Math.tanh(norm * COMPRESSION) / tanhK);

            // ── Smooth: fast attack, slow decay ──────────────────────────
            if (norm > smoothed[b])
                smoothed[b] += (norm - smoothed[b]) * ATTACK;
            else
                smoothed[b] += (norm - smoothed[b]) * DECAY;

            bandValues[b] = Math.max(0f, Math.min(1f, smoothed[b]));
        }
    }

    /**
     * Returns the current smoothed band magnitudes in the range {@code [0, 1]}.
     * The array has length {@code bands} as passed to the constructor.
     *
     * <p>The returned array is the analyser's internal buffer — do not modify
     * it and do not cache it across calls to {@link #analyze}.
     */
    public float[] getBandValues() { return bandValues; }

    /** @return The number of output frequency bands. */
    public int getBands() { return bands; }

    // -----------------------------------------------------------------------
    // Cooley-Tukey radix-2 DIT FFT (in-place, iterative)
    // -----------------------------------------------------------------------

    private static void fft(float[] re, float[] im, int n) {
        // Bit-reversal permutation
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        // Butterfly stages
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            float  wRe = (float) Math.cos(ang);
            float  wIm = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float curRe = 1f, curIm = 0f;
                for (int k = 0; k < len / 2; k++) {
                    float uRe = re[i + k],          uIm = im[i + k];
                    float vRe = re[i + k + len/2] * curRe - im[i + k + len/2] * curIm;
                    float vIm = re[i + k + len/2] * curIm + im[i + k + len/2] * curRe;
                    re[i + k]         = uRe + vRe;  im[i + k]         = uIm + vIm;
                    re[i + k + len/2] = uRe - vRe;  im[i + k + len/2] = uIm - vIm;
                    float nr = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nr;
                }
            }
        }
    }
}
