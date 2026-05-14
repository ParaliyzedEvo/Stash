# Loudness Normalization — Design Spec

**Date:** 2026-05-14
**Status:** Brainstormed, awaiting plan
**Author:** rawnaldclark + Claude (brainstorming session)

## Problem

User feedback (e.g. *prince420vegeta63*: "any fix for the irregular audio levels between each song?") confirms that Stash's playback experience requires the listener to ride the volume knob between tracks. A quiet acoustic recording at −24 LUFS and a maximally-mastered EDM single at −5 LUFS — both common in a single user's library — differ by ~20 dB of perceived loudness, which is the difference between "inaudible" and "blow out the speaker."

Every major streaming service (Spotify, Apple Music, Tidal, YouTube Music, Amazon Music) has solved this with loudness normalization. Stash currently does nothing: the audio pipeline passes samples straight from decoder to speaker with no per-track gain adjustment.

The previous EQ rebuild (`2026-04-30-equalizer-redesign-design.md`) reserved a slot in the DSP chain for `LoudnessGain` and `SoftClipLimiter`, but the actual processors were never built. This spec finishes that deferred work.

## Goals

1. **Per-track gain so all tracks play at a perceived −14 LUFS** (industry standard, matches Spotify/Tidal/YT/Amazon defaults). Listener never reaches for the volume knob between tracks.
2. **Measurement happens transparently** — at the download path for new tracks, in a background backfill worker for the pre-existing library. User never waits.
3. **No clipping**, even when normalization gain stacks on top of user-applied EQ and Bass Boost.
4. **Default-on**, silent UX. Match what a user expects coming from a streaming app: it just works.
5. **Structurally compatible with the existing EQ controller pattern** (one writer, one state, one chain, snapshot-per-buffer). Reuses Auxio's reference shape for `ReplayGainAudioProcessor` adapted to Stash conventions.

## Non-Goals

- Three-mode selector (Quiet / Normal / Loud). Single −14 LUFS target in v1; setting hook left in place for v2.
- True-peak limiter with 4× oversampling. Sample-peak only — research-justified for phone CPU budget.
- Album gain stored as a separate column. Derived on-the-fly from track LUFS within album.
- Writing ReplayGain tags back to source files (file-system-modifying). Stash DB only.
- Per-playlist normalization profiles.
- Opus R128 +5 dB offset handling — Stash sources (yt-dlp MP3/M4A, Qobuz FLAC, Kennyy FLAC) don't deliver Opus.
- Opportunistic measurement during playback (parallel decode of next track). Backfill worker only.
- Spectrum visualizer or live loudness meter UI. Diagnostic-only stat ("library measured 1842/2274") is the entire UX.
- LRA (loudness range) measurement / storage.

## Architecture

Three subsystems, wired into existing code paths:

```
┌─────────────────────────────────────────────────────────┐
│  Download path (per-track, runs once)                   │
│  ─────────────────────────────────────                  │
│  yt-dlp / Qobuz / Kennyy fetch                          │
│         │                                               │
│         ▼                                               │
│  TrackFinalizer ──► LoudnessMeasurer.measure(file)      │
│                          │                              │
│                          │  spawn ffmpeg ebur128        │
│                          │  parse stderr summary        │
│                          ▼                              │
│                     track.lufs / track.true_peak ──► DB │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  Backfill (idle-time, plugged-in, batched)              │
│  ─────────────────────────────────────────              │
│  WorkManager periodic worker (6h)                       │
│   constraints: charging + deviceIdle + batteryNotLow    │
│         │                                               │
│         ▼                                               │
│  LoudnessBackfillWorker → tracks where lufs IS NULL     │
│   (batch of 20, ~10 min cap per run)                    │
│         │                                               │
│         ▼ same LoudnessMeasurer                         │
│    track.lufs ──► DB                                    │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  Playback path (per-buffer, hot)                        │
│  ─────────────────────────────                          │
│  ExoPlayer ◄─ StashRenderersFactory                     │
│                       │                                 │
│  audio chain:  Preamp → Eq → BassShelf →                │
│                LoudnessGain → SoftClipLimiter           │
│                       ▲              ▲                  │
│                       │              │                  │
│                LoudnessController    (reads volume      │
│  Player.Listener.onMediaItemTransition                  │
│      ↓                                                  │
│  loudnessController.setCurrentTrackGain(                │
│      computeGain(track.lufs, target=−14))               │
└─────────────────────────────────────────────────────────┘
```

### Invariants (parallel to the EQ rebuild's invariants)

- **One writer.** Only `LoudnessController` mutates `LoudnessState`. UI emits events; processors only read snapshots.
- **One state.** `LoudnessState` is the truth. Processors hold no flags or coefficients between buffers except their own local DSP state (ramp position, limiter envelope, ring buffer).
- **One chain.** Built once at ExoPlayer creation by `StashRenderersFactory`; never reconstructed. Toggle is a flag flip, not a topology change.
- **Default-on.** Missing persisted key → `enabled = true`. (Opposite of the EQ default — justified because normalization is a "just works" feature 95% of users want on, and it's silent when no tracks have been measured yet.)
- **Restore-before-render.** `LoudnessController.init { runBlocking { LoudnessStore.read() } }` runs before any AudioProcessor sees its first buffer. Hilt enforces ordering.

### Files added (Kotlin)

DSP layer (in `core/media/.../equalizer/dsp/`, alongside existing processors):
- `LoudnessGainProcessor.kt`
- `SoftClipLimiterProcessor.kt`

Controller layer (in `core/media/.../equalizer/`):
- `LoudnessController.kt`
- `LoudnessState.kt`
- `LoudnessStore.kt` (proto DataStore, mirrors `EqStore` pattern)

Measurement layer (in `core/data/.../audio/`):
- `LoudnessMeasurer.kt`
- `LoudnessProgressStore.kt` (DataStore, for UI to read backfill progress)

Worker layer (in `core/data/.../sync/workers/`):
- `LoudnessBackfillWorker.kt`

UI layer (additions to existing files):
- `feature/settings/.../equalizer/EqualizerScreen.kt` — new `LoudnessCard` composable between Bass Boost and Pre-amp cards.
- `feature/settings/.../equalizer/EqualizerViewModel.kt` — surface `loudnessState: StateFlow<LoudnessUiState>` and `onLoudnessToggle`.

### Files modified

- `core/media/.../equalizer/StashRenderersFactory.kt` — append the two new processors to the processor array.
- `data/download/.../shared/TrackFinalizer.kt` — invoke `LoudnessMeasurer.measure()` after final file is in place.
- `app/.../StashApplication.kt` — enqueue `LoudnessBackfillWorker` periodic work at startup, alongside existing workers.
- `core/data/.../db/StashDatabase.kt` — bump version, add `MIGRATION_X_Y`.
- `core/data/.../db/entity/TrackEntity.kt` — add three new columns.
- `core/data/.../db/dao/TrackDao.kt` — add `tracksNeedingLoudness(limit)`, `updateLoudness(...)`, `markLoudnessFailed(...)`.

## Data Model

### Track DB schema delta (Room migration)

Three nullable columns added to the `track` table:

| Column | Type | Meaning |
|---|---|---|
| `loudness_lufs` | `REAL` (nullable) | Integrated LUFS from BS.1770 measurement. `NULL` = not measured. Sentinel `NaN` = "tried, gave up" (corrupt file). |
| `true_peak_dbfs` | `REAL` (nullable) | Sample-peak in dBFS (negative number). Used by `computeGain` to prevent clip. |
| `loudness_measured_at` | `INTEGER` (nullable) | Epoch ms timestamp of the measurement. Lets us identify stale measurements if the algorithm ever changes. |

```kotlin
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE track ADD COLUMN loudness_lufs REAL")
        db.execSQL("ALTER TABLE track ADD COLUMN true_peak_dbfs REAL")
        db.execSQL("ALTER TABLE track ADD COLUMN loudness_measured_at INTEGER")
    }
}
```

All three additive — existing rows survive untouched and naturally enter the backfill queue.

**No `album_gain` column.** Album gain is derived at playback time from per-track LUFS values inside the album, weighted by track duration. Avoids a separate measurement pass and a separate column.

### In-memory state

```kotlin
data class LoudnessState(
    val enabled: Boolean = true,            // user toggle in EQ screen
    val targetLufs: Float = -14f,           // fixed in v1; setting hook for v2
    val currentTrackGainDb: Float = 0f,     // gain to apply right now (post-ramp)
    val currentTargetGainDb: Float = 0f,    // what we're ramping toward
)
```

The processor reads `currentTrackGainDb` per buffer; the controller updates it in-place during the 10–20 ms ramp at track transitions.

### Gain computation

```kotlin
fun computeGain(
    trackLufs: Float?,
    trackPeakDbfs: Float?,
    target: Float = -14f,
): Float {
    if (trackLufs == null || trackLufs.isNaN()) return 0f   // un-measured or failed → bypass
    val raw = target - trackLufs                            // e.g. target=-14, lufs=-20 → +6 dB
    val capped = raw.coerceIn(-15f, +12f)                   // hard caps
    val peakRoom = if (trackPeakDbfs != null) (-1f) - trackPeakDbfs else Float.MAX_VALUE
    return minOf(capped, peakRoom)                          // peak-aware cap
}
```

Two safety belts:
1. **Hard caps** at −15 / +12 dB so a podcast at −40 LUFS doesn't get +26 dB boost.
2. **Peak-aware cap** so the gain never lifts a track's peak above −1 dBFS. The limiter that follows catches residual peaks from EQ / Bass Boost stages, not from our own gain stage.

### Album-context gain (deferred — track gain only in v1)

Auxio's `DYNAMIC` rule (use album gain when `playback.parent == song.album`, else track gain) is the correct future-direction. Stash will stay on track-gain-only for v1 — the user-perceived improvement is overwhelmingly in track gain, and album gain requires a "play this album from album-detail" code path that doesn't yet pass that context through the queue manager. Re-evaluate after v1 ships.

## DSP Layer

Both new processors extend Media3's `BaseAudioProcessor` and reject non-`PCM_16BIT` input via `UnhandledAudioFormatException` (same contract as existing processors). Both operate on interleaved little-endian 16-bit samples.

### `LoudnessGainProcessor`

Applies a per-track linear gain with a click-suppressing ramp at track boundaries.

Sketch:

```kotlin
class LoudnessGainProcessor(
    private val controller: LoudnessController,
) : BaseAudioProcessor() {

    private var currentLinearGain = 1.0f
    private var targetLinearGain = 1.0f
    private var rampSamplesRemaining = 0

    private companion object { const val RAMP_MS = 15 }

    override fun queueInput(input: ByteBuffer) {
        val state = controller.state.value
        val desiredLinear = if (state.enabled) 10f.pow(state.currentTrackGainDb / 20f) else 1f

        if (desiredLinear != targetLinearGain) {
            targetLinearGain = desiredLinear
            rampSamplesRemaining = (sampleRateHz * RAMP_MS / 1000).coerceAtLeast(1)
        }

        val step = if (rampSamplesRemaining > 0)
            (targetLinearGain - currentLinearGain) / rampSamplesRemaining else 0f

        val out = replaceOutputBuffer(input.remaining())
        while (input.hasRemaining()) {
            val s = input.short.toInt()
            val gained = (s * currentLinearGain).toInt()
                          .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out.putShort(gained.toShort())
            if (rampSamplesRemaining > 0) {
                currentLinearGain += step
                rampSamplesRemaining--
                if (rampSamplesRemaining == 0) currentLinearGain = targetLinearGain
            }
        }
        out.flip()
    }
}
```

Hard clamp inside the gain stage is **redundant** with the limiter but cheap and prevents int overflow at extreme positive gain values — keep it.

### `SoftClipLimiterProcessor`

Lookahead peak limiter, sample-peak (not true-peak — research-justified for phone CPU budget).

| Parameter | Value | Why |
|---|---|---|
| Threshold | −1 dBFS (0.891) | Industry standard; matches Spotify's published spec. |
| Attack | 1 ms | Fast enough to catch transients; slow enough to avoid audible click. |
| Release | 50 ms | Smooth recovery; matches iZotope reference. |
| Lookahead | 2 ms | ~88 samples at 44.1 kHz — cheap window-max scan. |
| Detection | Sample peak | True-peak (4× oversample) is overkill for a phone DAC chain; sample-peak gives practically the same result at a fraction of the CPU. |

Sketch:

```kotlin
class SoftClipLimiterProcessor : BaseAudioProcessor() {
    private companion object {
        const val THRESHOLD = 0.891f
        const val ATTACK_MS = 1f
        const val RELEASE_MS = 50f
        const val LOOKAHEAD_MS = 2f
    }

    private lateinit var ringBuffer: ShortArray
    private var ringWrite = 0
    private var ringRead = 0
    private var currentGain = 1.0f
    private var attackCoeff = 0f
    private var releaseCoeff = 0f

    override fun onConfigure(format: AudioFormat) {
        val frames = (format.sampleRate * LOOKAHEAD_MS / 1000).toInt()
        ringBuffer = ShortArray(frames * format.channelCount)
        attackCoeff = expCoeff(ATTACK_MS, format.sampleRate)
        releaseCoeff = expCoeff(RELEASE_MS, format.sampleRate)
        ringWrite = 0; ringRead = 0; currentGain = 1f
    }

    override fun queueInput(input: ByteBuffer) {
        val out = replaceOutputBuffer(input.remaining())
        while (input.hasRemaining()) {
            val sample = input.short
            ringBuffer[ringWrite] = sample
            ringWrite = (ringWrite + 1) % ringBuffer.size

            val peakAbs = lookaheadPeakAbs() / 32768f
            val targetGain = if (peakAbs > THRESHOLD) THRESHOLD / peakAbs else 1f

            val coeff = if (targetGain < currentGain) attackCoeff else releaseCoeff
            currentGain += (targetGain - currentGain) * coeff

            val delayed = ringBuffer[ringRead]
            ringRead = (ringRead + 1) % ringBuffer.size
            val limited = (delayed * currentGain).toInt()
                          .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out.putShort(limited.toShort())
        }
        out.flip()
    }
}
```

`expCoeff` and `lookaheadPeakAbs` are 3-line helpers (`1 - exp(-1 / (sr * ms / 1000))` and a window-max-abs over the ring buffer).

### Chain order

`Preamp → EQ → BassShelf → LoudnessGain → SoftClipLimiter`

The limiter goes **last** so it catches summed peaks from every gain stage, including positive RG values on quiet tracks stacked on top of EQ boost. This matches the ReplayGain 2.0 spec's clip-prevention guidance.

## Measurement Pipeline

### FFmpeg invocation

Stash already links `com.yausername.ffmpeg.FFmpeg` for yt-dlp post-processing. Reuse it:

```
ffmpeg -nostats -hide_banner -i <track_path> \
       -af ebur128=peak=true \
       -f null -
```

Decodes the full track once, runs K-weighted gated measurement, writes summary to stderr, writes no audio. Empirical realtime ratio on mid-range Android: 5–10× (a 4-minute track measures in 25–50 s, decode-bound).

### Output parser

FFmpeg's `ebur128` filter summary is plain text, deterministic format:

```
[Parsed_ebur128_0 @ 0x...] Summary:

  Integrated loudness:
    I:         -14.2 LUFS
    Threshold: -24.3 LUFS
  ...
  True peak:
    Peak:       -0.3 dBFS
```

20-line regex parser pulls `I:` and `Peak:`. Hardened against:
- Filter not running (no `Summary:` line) → `LoudnessResult.Failed("no summary")`.
- Track shorter than 0.4 s (below BS.1770 block size) → integrated will be `-inf LUFS`; treat as `Failed`, don't persist.
- Multiple audio streams → ffmpeg picks the first by default; that's the one ExoPlayer will play, so values match.

### `LoudnessMeasurer` interface

```kotlin
@Singleton
class LoudnessMeasurer @Inject constructor(
    private val ffmpegBridge: FFmpegBridge,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val singleFlightMutex = Mutex()   // app-wide: only one measurement at a time

    suspend fun measure(file: File): Result = singleFlightMutex.withLock {
        withContext(dispatcher) { /* invoke ffmpeg, parse, return */ }
    }

    sealed class Result {
        data class Success(val lufs: Float, val truePeakDbfs: Float) : Result()
        data class Failed(val reason: String) : Result()
    }
}
```

The mutex prevents the backfill worker from competing with a user-initiated download for CPU.

### Wiring into the download path

`TrackFinalizer` already runs after a download lands and is the last step before the file is exposed as playable. Hook in there:

```kotlin
// inside TrackFinalizer, after the file is in its final location
val result = loudnessMeasurer.measure(finalFile)
when (result) {
    is Success -> trackDao.updateLoudness(trackId, result.lufs, result.truePeakDbfs, now())
    is Failed -> Log.w(TAG, "Loudness measurement failed for $trackId: ${result.reason}")
}
```

Measurement is non-fatal — file is still playable, just at 0 dB until backfill retries.

## Backfill Worker

### `LoudnessBackfillWorker`

```kotlin
@HiltWorker
class LoudnessBackfillWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val measurer: LoudnessMeasurer,
    private val progressStore: LoudnessProgressStore,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val budget = TimeBudget(maxMs = 10 * 60_000)
        val batch = trackDao.tracksNeedingLoudness(limit = 20)
        if (batch.isEmpty()) return Result.success(workDataOf(KEY_DONE to true))

        for (track in batch) {
            if (isStopped || budget.expired) break
            val file = File(track.localPath).takeIf { it.exists() } ?: continue
            when (val r = measurer.measure(file)) {
                is Success -> trackDao.updateLoudness(track.id, r.lufs, r.truePeakDbfs, now())
                is Failed -> trackDao.markLoudnessFailed(track.id, now())
            }
            progressStore.increment()
        }
        return Result.success()
    }
}
```

### Scheduling

```kotlin
val request = PeriodicWorkRequestBuilder<LoudnessBackfillWorker>(
        repeatInterval = 6, repeatIntervalTimeUnit = TimeUnit.HOURS,
    )
    .setConstraints(Constraints.Builder()
        .setRequiresCharging(true)
        .setRequiresDeviceIdle(true)
        .setRequiresBatteryNotLow(true)
        .build())
    .build()

workManager.enqueueUniquePeriodicWork(
    "loudness-backfill",
    ExistingPeriodicWorkPolicy.KEEP,
    request,
)
```

Enqueued once at app start by `StashApplication.onCreate`, alongside existing periodic workers (`StashMixRefreshWorker` and friends).

### Failure-sentinel strategy

`markLoudnessFailed(id, timestamp)` writes `loudnessLufs = NaN` (or a sentinel constant) plus a real timestamp. This stops the worker from re-trying corrupt files every 6 hours forever. A separate query (run weekly) can resurrect failed-sentinel rows for one retry attempt — handles "ffmpeg was broken in v0.9.x, fixed in v0.9.y."

### Progress reporting

`LoudnessProgressStore` is a small DataStore holding two numbers: `totalRemaining` and `lastCompletedAt`. The EQ screen reads it as a `StateFlow` to show:

> "Library measured: 1,842 of 2,274 tracks"

Numbers update once per worker run, not once per track — keeps IPC cost negligible.

### Cancellation safety

`isStopped` is checked at the top of every batch iteration. A track caught mid-measurement when WorkManager kills the worker is **not** marked failed — its row stays `NULL` and the next run picks it up. Idempotent.

## UI Integration

### EQ screen — new `LoudnessCard`

Insert between the existing Bass Boost card and Pre-amp card in `EqualizerScreen.kt`. Card order becomes: 5-Band EQ → Bass Boost → **Loudness** → Pre-amp.

Two states inside one `GlassCard`:

```
┌───────────────────────────────────────────────────────┐
│  LOUDNESS NORMALIZATION                       [●▢]   │
│                                                       │
│  Plays every track at a consistent volume.            │
│                                                       │
│  ── divider (only shown if backfilling) ──            │
│  Library measured: 1,842 of 2,274 tracks              │
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━              │
│  Continues automatically while charging.              │
└───────────────────────────────────────────────────────┘
```

When backfill complete (`totalRemaining == 0`), the progress block hides entirely.

Composable sketch:

```kotlin
GlassCard {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Loudness Normalization")
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state.loudnessEnabled,
                onCheckedChange = viewModel::onLoudnessToggle,
                colors = SwitchDefaults.colors(/* same as EqHeader switch */),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Plays every track at a consistent volume.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.backfillRemaining > 0) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(Modifier.height(10.dp))
            LoudnessBackfillBlock(
                remaining = state.backfillRemaining,
                total = state.backfillTotal,
            )
        }
    }
}
```

**Critical**: the master EQ toggle (`state.enabled`) does **not** dim the Loudness card. Loudness operates independently from EQ — a user can run with EQ off and loudness on, which is a common preference ("flat EQ but matched volumes").

### One-time notice

First time the EQ screen opens after the update, show a `Snackbar`:

> *"Loudness normalization is on. Tracks will sound more consistent as your library is measured in the background."*  · **Dismiss**

Backed by `loudness_first_run_notice_shown: Boolean` in DataStore. Set true on first dismiss OR first toggle interaction (whichever comes first). Never shown again.

### ViewModel surface

```kotlin
val loudnessState: StateFlow<LoudnessUiState> = combine(
    loudnessController.state,
    loudnessProgressStore.flow,
) { settings, progress ->
    LoudnessUiState(
        loudnessEnabled = settings.enabled,
        backfillRemaining = progress.remaining,
        backfillTotal = progress.total,
    )
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoudnessUiState())

fun onLoudnessToggle(enabled: Boolean) = loudnessController.setEnabled(enabled)
```

## Rollout & Risk

### Version bump

Follow existing pattern: `versionCode 61 → 62`, `versionName 0.9.24 → 0.9.25`. Single release.

### Default state on update

`LoudnessStore.read()` returns `enabled = true` when the persisted key is missing. Justified by spec decisions: normalization is a "just works" feature 95% of users want on, and silent when no tracks have been measured yet.

### New install behavior

Same default. Empty library → no backfill needed → progress block hidden. As the user downloads tracks, the download-path measurement hook tags them inline. Backfill scanner only runs if the library has un-measured tracks (e.g. user restores from backup).

### Three rollback levels

1. **User-level**: master toggle in EQ screen. One tap, gain returns to 0 dB. Backfill keeps writing to DB but values unused — cheap, no harm.
2. **Build-level**: `BuildConfig.LOUDNESS_FEATURE_ENABLED = false` short-circuits `LoudnessController.setEnabled` and skips enqueuing the backfill worker. DSP processors remain in the chain but their gain stays at 1.0.
3. **Catastrophic**: revert in a hotfix by setting `enableLoudness = false` in `StashRenderersFactory` — the processor array drops the loudness + limiter, chain reverts to the pre-feature shape. No DB cleanup needed.

The data left behind (`lufs` columns) is benign and reusable if the feature is re-enabled later.

## Testing Strategy

| Layer | What's tested | How |
|---|---|---|
| Unit — DSP | `LoudnessGainProcessor` applies known gain to known input; ramp covers expected sample count; clamp prevents overflow at +24 dB. | Pure Kotlin against a `ByteBuffer` fixture, mirroring `PreampProcessorTest`. |
| Unit — limiter | `SoftClipLimiterProcessor` passes sub-threshold signal unchanged; clamps above-threshold to threshold within ATTACK_MS; releases within RELEASE_MS. | Same pattern; sine + impulse fixtures. |
| Unit — gain math | `computeGain` honors caps, returns 0 for null LUFS, prevents peak > −1 dBFS. | Parameterized table test. |
| Unit — parser | `LoudnessMeasurer.parseSummary` extracts `I:` and `Peak:` from canned ffmpeg stderr; returns `Failed` on missing summary, short clip, parse error. | Fixture files in `src/test/resources/ffmpeg_output/`. |
| Integration — chain | Build full Media3 chain in test rig (`AudioProcessorChain`), feed sine sweep through `Preamp(+3) → Eq(+6 @ 60Hz) → BassShelf(+9) → Loudness(+6) → Limiter`, verify no clip in output. | JVM test using Media3's `BaseAudioProcessor` directly. |
| Integration — DB | Backfill query returns rows with `loudnessMeasuredAt IS NULL`; sentinel-failed rows aren't picked up; idempotent worker doesn't double-write. | Room in-memory DB test. |
| Manual — on-device | Play known track, observe gain in logcat matches `target - lufs`. Lock screen, plug in, leave overnight, verify backfill progressed. Check EQ-screen progress block updates. | Device build. |

### Validation milestones (gates before merging)

1. ffmpeg ebur128 measures known reference tracks correctly (5-track fixture set, hand-measured by desktop `rsgain`). Tolerance: ±0.3 LUFS.
2. Round-trip A/B: same source played with normalization off vs on at +0 dB target should be sample-identical (limiter doesn't fire on un-clipping content).
3. No regression in existing EQ tests.
4. `:app:installDebug` succeeds, app starts cleanly, EQ screen renders without crash.

## Research Sources

Primary reference document: `.superpowers/brainstorm/2007-1777554472/design-loudness.html` (prior design pass, EQ rebuild).

External:
- [ITU-R BS.1770-5 (2023)](https://www.itu.int/dms_pubrec/itu-r/rec/bs/R-REC-BS.1770-5-202311-I!!PDF-E.pdf) — the measurement algorithm.
- [EBU R 128](https://tech.ebu.ch/docs/r/r128.pdf) — the target standard.
- [Hydrogenaudio: ReplayGain 2.0](https://wiki.hydrogenaudio.org/index.php?title=ReplayGain_2.0_specification) — tag format.
- [Spotify: Loudness normalization](https://support.spotify.com/us/artists/article/loudness-normalization/) — published target values.
- [AES TD1008 v3.13](https://aes2.org/wp-content/uploads/2024/01/20210924_TD1008_v3.13.pdf) — AES streaming-loudness recommendations.
- [OxygenCobalt/Auxio](https://github.com/OxygenCobalt/Auxio): `ReplayGainAudioProcessor.kt` — canonical Kotlin+Media3 reference shape.
- [FFmpeg ebur128 filter docs](https://ayosec.github.io/ffmpeg-filters-docs/6.0/Filters/Multimedia/ebur128.html) — measurement tool.
- [iZotope: Limiter design](https://www.izotope.com/en/learn/an-introduction-to-limiters-and-how-to-use-them) — limiter parameters.
