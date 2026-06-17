# CLAP On-Device Spike (Phase 0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove (or kill) the premise that an open-source CLAP audio model can embed tracks on a real phone fast enough and small enough, and that cosine distance in its vector space actually separates music that sounds different — before any production code is written.

**Architecture:** A throwaway, self-contained spike in two halves. **(A) Desktop (Python):** export LAION-CLAP to ONNX with audio preprocessing baked into the graph, quantize to int8, record size, and produce ground-truth reference vectors + a cosine matrix. **(B) On-device (Android instrumented test):** load that ONNX model via ONNX Runtime, embed ~20 bundled clips, measure inference wall-clock per track, reproduce the cosine matrix, and confirm it matches the desktop ground truth. A findings doc records the numbers and a go/no-go decision that pins the model choice for the Phase 1 plan.

**Tech Stack:** Python (`laion_clap`, `torch`, `onnx`, `onnxruntime`) for model prep; Kotlin + ONNX Runtime for Android (`com.microsoft.onnxruntime:onnxruntime-android`); existing project `FFmpegBridge` only as a desktop reference (decode happens off-device for the spike).

---

## Why this is a spike, not a feature

This plan is **exploratory and throwaway**. The code it produces is **not merged to master** — it lives on a `spike/clap-on-device` branch and exists only to generate three numbers and one decision. Steps therefore favor "produce an artifact, measure it, record the number" over red-green TDD. The one place we assert (Task 7) is a *correctness* check that the Android port reproduces the desktop vectors — that protects the measurements from being silently wrong.

**Exit criteria (the whole point):**
1. **Size** — the quantized audio-encoder ONNX is **≤ ~200 MB** (target; int8). Record the real number.
2. **Speed** — embedding one track (3×10 s windows, mean-pooled) takes **single-digit seconds** on the Pixel, not minutes. Record the real number.
3. **Signal** — the on-device cosine matrix visibly **clusters same-genre clips above cross-genre pairs**, and matches the desktop ground truth within tolerance.

If size or speed fails → fall back toward Approach C (metadata-only) in the spec. If signal fails → the substrate is unfit and the project stops. Either way the spike has done its job.

---

## Branch setup

- [ ] **Step 0: Create the spike branch**

We are on `master`. Isolate the throwaway work.

```bash
git checkout -b spike/clap-on-device
```

All spike artifacts live under `spike/clap/` (desktop) and `app/src/androidTest/.../clapspike/` (device). Nothing here is meant to survive into Phase 1 except the findings doc and the pinned model decision.

---

## File Structure

**Desktop (Python), under `spike/clap/`:**
- `spike/clap/export_clap_onnx.py` — load the LAION-CLAP music checkpoint, wrap it so the audio encoder takes **raw 48 kHz mono float PCM** (mel/STFT baked into the graph), export `clap_audio.onnx` and `clap_text.onnx`.
- `spike/clap/quantize.py` — dynamic int8 quantization of both ONNX files; prints fp32 vs int8 sizes.
- `spike/clap/make_fixtures.py` — decode ~20 genre-spread clips to 48 kHz mono WAV (10 s each) via ffmpeg, compute reference audio vectors + the full cosine matrix, dump `ground_truth.json` and token-id arrays for 3 text queries.
- `spike/clap/requirements.txt` — pinned Python deps.
- `spike/clap/README.md` — exact run order + where artifacts land.

**On-device (Android), throwaway instrumented test in the `app` module:**
- `app/src/androidTest/assets/clapspike/` — `clap_audio.int8.onnx`, `clap_text.int8.onnx`, the 20 WAV clips, `ground_truth.json`, `query_tokens.json`.
- `app/src/androidTest/kotlin/com/stash/app/clapspike/ClapSpikeOnnx.kt` — minimal ONNX Runtime wrapper: `embedAudio(FloatArray): FloatArray`, `embedTextTokens(ids: LongArray, mask: LongArray): FloatArray` (two args — the `query_tokens.json` from Task 4 carries both `input_ids` and `attention_mask`), plus `cosine()`.
- `app/src/androidTest/kotlin/com/stash/app/clapspike/WavReader.kt` — read a 48 kHz mono PCM WAV asset into a `FloatArray`.
- `app/src/androidTest/kotlin/com/stash/app/clapspike/ClapSpikeTest.kt` — the instrumented harness: times embeds, builds the cosine matrix, asserts it matches ground truth, logs everything.

**Findings:**
- `docs/superpowers/specs/2026-06-14-clap-spike-findings.md` — the recorded numbers + go/no-go decision and the pinned checkpoint/quantization for Phase 1.

**Build (version catalog + app test deps):**
- `gradle/libs.versions.toml` — add ONNX Runtime.
- `app/build.gradle.kts` — add it as an `androidTestImplementation`.

---

## Task 1: Stand up the Python model-prep environment

**Files:**
- Create: `spike/clap/requirements.txt`
- Create: `spike/clap/README.md`

- [ ] **Step 1: Pin Python deps**

`spike/clap/requirements.txt`:

```
torch==2.4.1
laion-clap==1.1.6
onnx==1.16.2
onnxruntime==1.20.0
librosa==0.10.2
soundfile==0.12.1
numpy==1.26.4
```

- [ ] **Step 2: Create the environment and install**

Run:
```bash
cd spike/clap
python -m venv .venv && . .venv/Scripts/activate   # Windows PowerShell: .venv\Scripts\Activate.ps1
pip install -r requirements.txt
```
Expected: clean install. (CPU torch is fine — export and reference embedding don't need a GPU.)

- [ ] **Step 3: Download the music checkpoint**

`laion_clap` downloads checkpoints on first use, but the music-tuned weights (`music_audioset_epoch_15_esc_90.pt`) are a manual download from the LAION-CLAP repo's release assets. Note the URL + sha256 in `README.md` — this exact checkpoint is what Phase 1 will pin via `model_version`.

- [ ] **Step 4: Commit the scaffold**

```bash
git add spike/clap/requirements.txt spike/clap/README.md
git commit -m "spike: python env scaffold for CLAP ONNX export"
```

---

## Task 2: Export CLAP to ONNX with preprocessing baked in

**Files:**
- Create: `spike/clap/export_clap_onnx.py`

**Key decision (locked here):** the audio encoder ONNX must accept **raw 48 kHz mono float PCM** (shape `[1, 480000]` for a 10 s window) and compute its own mel/STFT inside the graph (torch's `stft` exports to the ONNX `STFT` op). This keeps **all** DSP on the Python/graph side and means the Android harness only ever hands the model a float array — no Kotlin mel filterbank to get subtly wrong. If `STFT` export fails for this torch/opset combo, fall back to exporting a model that takes a precomputed log-mel `[1, 64, T]` tensor AND record that the on-device path now owes a Kotlin mel implementation (a real cost to flag in findings).

- [ ] **Step 1: Write the exporter**

`spike/clap/export_clap_onnx.py` — load the model, wrap audio + text branches as `nn.Module`s with fixed input signatures, `torch.onnx.export` each with `opset_version=17`, dynamic axis only on the text token length. Print each output file's path.

```python
# Pseudocode-level structure — fill in against laion_clap's actual API:
import torch, laion_clap
model = laion_clap.CLAP_Module(enable_fusion=False, amodel='HTSAT-base')
model.load_ckpt('music_audioset_epoch_15_esc_90.pt')

class AudioBranch(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, pcm):                       # pcm: [1, 480000] float32 @48k
        return self.m.get_audio_embedding_from_data(x=pcm, use_tensor=True)  # -> [1, 512]

class TextBranch(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, input_ids, attn):           # token ids -> [1, 512]
        return self.m.model.get_text_embedding(...)  # exact call per laion_clap source

torch.onnx.export(AudioBranch(model.eval()), torch.zeros(1, 480000),
                  'clap_audio.onnx', input_names=['pcm'], output_names=['embed'],
                  opset_version=17)
# ...text export similarly, with dynamic axis on token length...
```

- [ ] **Step 2: Run the export**

Run: `python export_clap_onnx.py`
Expected: `clap_audio.onnx` and `clap_text.onnx` written; no export errors. If `STFT` op errors, switch to the log-mel-input fallback and note it.

- [ ] **Step 3: Smoke-check the ONNX runs in onnxruntime**

Run a 5-line `onnxruntime.InferenceSession` on a zero PCM array; confirm output shape is `[1, 512]`.
Expected: clean inference, correct shape.

- [ ] **Step 4: Commit**

```bash
git add spike/clap/export_clap_onnx.py
git commit -m "spike: export CLAP audio+text encoders to ONNX (raw-PCM input)"
```

---

## Task 3: Quantize and record size (Exit criterion #1)

**Files:**
- Create: `spike/clap/quantize.py`

- [ ] **Step 1: Write dynamic int8 quantization**

`spike/clap/quantize.py` using `onnxruntime.quantization.quantize_dynamic` on both ONNX files → `clap_audio.int8.onnx`, `clap_text.int8.onnx`. Print fp32 and int8 byte sizes for each.

- [ ] **Step 2: Run it and record the numbers**

Run: `python quantize.py`
Expected output (example shape, real numbers vary):
```
clap_audio.onnx       fp32  498.2 MB
clap_audio.int8.onnx  int8  142.7 MB   <-- exit criterion #1 target ≤ ~200 MB
clap_text.onnx        fp32  ...
clap_text.int8.onnx   int8  ...
```
**Record the audio int8 size** — this is exit criterion #1. If it's well over ~200 MB even after quantization, flag it now; it changes the download-on-enable story and may push toward Approach C.

- [ ] **Step 3: Commit**

```bash
git add spike/clap/quantize.py
git commit -m "spike: int8-quantize CLAP ONNX; record model sizes"
```

---

## Task 4: Build genre-spread fixtures + desktop ground truth

**Files:**
- Create: `spike/clap/make_fixtures.py`

- [ ] **Step 1: Assemble ~20 clips across distinct genres**

Pick ~20 tracks spanning clearly different sonic territory (e.g. ambient, metal, hip-hop, classical piano, EDM, folk, jazz) — at least 2 per genre so "same-genre cosine > cross-genre cosine" is testable. Note their sources in `README.md`. (Personal library files are fine — this is a local spike.)

- [ ] **Step 2: Decode each to a single 48 kHz mono 10 s WAV**

In `make_fixtures.py`, shell out to ffmpeg per clip: `ffmpeg -ss 30 -t 10 -i <in> -ac 1 -ar 48000 -f wav <out>.wav` (mid-track 10 s window). Write to `spike/clap/fixtures/wav/`.

- [ ] **Step 3: Compute reference vectors + cosine matrix**

Load each WAV → float PCM → `clap_audio.int8.onnx` via onnxruntime → 512-d vector. Build the full NxN cosine matrix. Also tokenize 3 text queries ("calm piano for a rainy drive", "aggressive heavy guitars", "upbeat electronic dance") with the model's tokenizer and embed via `clap_text.int8.onnx`; record query×clip cosines.

- [ ] **Step 4: Dump ground truth + tokens**

Write `spike/clap/fixtures/ground_truth.json` (per-clip vectors, the NxN matrix, query×clip cosines) and `spike/clap/fixtures/query_tokens.json` (the precomputed `input_ids`/`attention_mask` arrays for the 3 queries — so the Android harness needs **no on-device tokenizer**; tokenization is a Phase 2 concern).

- [ ] **Step 5: Eyeball the matrix (desktop pre-check of Exit criterion #3)**

Print the matrix with genre labels. Expected: same-genre pairs visibly higher than cross-genre. If the *desktop* matrix already fails to separate genres, stop here — the checkpoint/export is wrong and no amount of Android work will fix it.

- [ ] **Step 6: Commit**

```bash
git add spike/clap/make_fixtures.py spike/clap/README.md
git commit -m "spike: genre-spread fixtures + desktop ground-truth cosine matrix"
```

---

## Task 5: Add ONNX Runtime to the Android build (test-only)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add the version + library to the catalog**

In `gradle/libs.versions.toml`, under `[versions]`:
```toml
onnxruntime = "1.20.0"
```
Under `[libraries]`:
```toml
onnxruntime-android = { group = "com.microsoft.onnxruntime", name = "onnxruntime-android", version.ref = "onnxruntime" }
```

- [ ] **Step 2: Wire it (and the instrumented-test runner) as test-only dependencies**

The `app` module currently has **no** `androidTest` dependencies wired, and the harness in Task 8 uses `@RunWith(AndroidJUnit4::class)`, `InstrumentationRegistry`, and Truth's `assertThat`. Mirror the working pattern from `data/download/build.gradle.kts` (the `testInstrumentationRunner` is already set in `app/build.gradle.kts` `defaultConfig`, so only deps are needed). In the `app/build.gradle.kts` dependencies block:
```kotlin
androidTestImplementation(libs.onnxruntime.android)
androidTestImplementation(libs.androidx.test.core)        // already in the catalog
androidTestImplementation("androidx.test:runner:1.6.2")   // AndroidJUnitRunner
androidTestImplementation("androidx.test.ext:junit:1.2.1") // AndroidJUnit4
androidTestImplementation(libs.truth)                     // already in the catalog
```
Test-only on purpose — the spike must not bloat the shipping APK.

- [ ] **Step 3: Sync/compile check**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL (resolves the new dependencies; `AndroidJUnit4` / `InstrumentationRegistry` / `assertThat` all resolve). No device needed yet.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "spike: add onnxruntime-android as an androidTest dependency"
```

---

## Task 6: Copy artifacts into androidTest assets

**Files:**
- Create: `app/src/androidTest/assets/clapspike/` (binary assets)

- [ ] **Step 1: Stage the assets**

Copy into `app/src/androidTest/assets/clapspike/`: `clap_audio.int8.onnx`, `clap_text.int8.onnx`, the 20 `*.wav` clips, `ground_truth.json`, `query_tokens.json`.

- [ ] **Step 2: Confirm they're picked up**

Run: `./gradlew :app:packageDebugAndroidTest` (or rely on the next test run). Expected: build packages the assets without error. Note the test-APK size as a sanity check that the model actually shipped into it.

- [ ] **Step 3: Commit**

> Note: these are large binaries. Commit them on the spike branch only; they are **not** destined for master. If their size is unwieldy for git, keep them out of git and document their local path in `README.md` instead — the spike branch is disposable either way.

```bash
git add app/src/androidTest/assets/clapspike/ 2>/dev/null || true
git commit -m "spike: stage CLAP onnx + fixtures as androidTest assets" || echo "assets kept local; see README"
```

---

## Task 7: The on-device harness — wrapper + WAV reader

**Files:**
- Create: `app/src/androidTest/kotlin/com/stash/app/clapspike/WavReader.kt`
- Create: `app/src/androidTest/kotlin/com/stash/app/clapspike/ClapSpikeOnnx.kt`

- [ ] **Step 1: WAV → FloatArray**

`WavReader.kt`: parse a 48 kHz mono PCM16 WAV from an `InputStream` (the assets are produced by ffmpeg in Task 4, so format is known/fixed) into a normalized `FloatArray` in `[-1, 1]`. Keep it ~40 lines — fixed format, no general WAV support needed.

- [ ] **Step 2: Minimal ONNX Runtime wrapper**

`ClapSpikeOnnx.kt`: open `OrtEnvironment`, create `OrtSession`s for the two `.onnx` byte arrays read from assets. Provide:
```kotlin
fun embedAudio(pcm: FloatArray): FloatArray      // pcm length = 480000 (10s @48k) -> 512
fun embedTextTokens(ids: LongArray, mask: LongArray): FloatArray  // -> 512
fun cosine(a: FloatArray, b: FloatArray): Float
```
`embedAudio` wraps the float array in an `OnnxTensor` of shape `[1, 480000]`, runs the session, reads the `[1, 512]` output. For a full "track" the test mean-pools 3 windows — but the spike WAVs are single 10 s windows, so the harness embeds the one window per clip and notes that production mean-pools 3 (≈3× the per-track time).

- [ ] **Step 3: Compile check**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/kotlin/com/stash/app/clapspike/WavReader.kt app/src/androidTest/kotlin/com/stash/app/clapspike/ClapSpikeOnnx.kt
git commit -m "spike: onnxruntime wrapper + wav reader for on-device harness"
```

---

## Task 8: The on-device harness — measure + assert (Exit criteria #2 and #3)

**Files:**
- Create: `app/src/androidTest/kotlin/com/stash/app/clapspike/ClapSpikeTest.kt`

- [ ] **Step 1: Write the harness test**

`ClapSpikeTest.kt`, an instrumented test (`@RunWith(AndroidJUnit4::class)`), reading assets via `InstrumentationRegistry.getInstrumentation().context.assets.open("clapspike/...")`. It must:
1. Load both sessions + all 20 WAVs + `ground_truth.json` + `query_tokens.json`.
2. **Warm up** (one throwaway `embedAudio` to exclude one-time session/init cost), then **time** each clip's `embedAudio` with `SystemClock.elapsedRealtimeNanos()`; log per-clip ms and the **median** (exit criterion #2). Multiply median ×3 and log it as the projected per-track time (production mean-pools 3 windows).
3. Build the on-device NxN cosine matrix; log it with genre labels.
4. **Assert correctness:** every on-device clip vector is within tolerance of the ground-truth vector (`cosine(device, desktop) > 0.999`). This is the one real assertion — it proves the Android numbers aren't garbage. A loose `1e-2` abs tolerance on the matrix entries accommodates int8/runtime differences. **Note:** the ground truth is computed from int8 ONNX on desktop x86; cross-architecture int8 kernels (x86 vs ARM) can diverge slightly more than fp32, so if `0.999` proves too tight on a run whose cosine matrix still clearly separates genres, relax the per-vector gate (e.g. to `0.99`) rather than reading a correct-but-borderline run as a failure. Record whatever gate value the run actually needed in findings.
5. Embed the 3 query token sets; log query×clip cosines and the top-3 clips per query.
6. Log a one-line **PASS/FAIL summary** per exit criterion.

```kotlin
@Test
fun clapSpike_measuresAndSeparates() {
    val onnx = ClapSpikeOnnx(ctx.assets)
    onnx.embedAudio(WavReader.read(ctx.assets.open("clapspike/${clips[0]}")))  // warm-up
    val vectors = clips.associateWith { name ->
        val pcm = WavReader.read(ctx.assets.open("clapspike/$name"))
        val t0 = SystemClock.elapsedRealtimeNanos()
        val v = onnx.embedAudio(pcm)
        timingsMs += (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
        v
    }
    // correctness vs desktop
    vectors.forEach { (name, v) -> assertThat(onnx.cosine(v, truth[name]!!)).isGreaterThan(0.999f) }
    // log matrix, medians, query results, PASS/FAIL lines
}
```

- [ ] **Step 2: Run on the Pixel**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.stash.app.clapspike.ClapSpikeTest"`
(Device must be connected via adb.) Expected: test PASSES (correctness assertion holds), and logcat shows per-clip timings, the cosine matrix, and the PASS/FAIL summary.

- [ ] **Step 3: Capture the logcat output**

Run: `adb logcat -d -s ClapSpike:* > spike/clap/device_run.txt` (use a consistent log tag in the test). Expected: a saved transcript of medians + matrix for the findings doc.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/kotlin/com/stash/app/clapspike/ClapSpikeTest.kt spike/clap/device_run.txt
git commit -m "spike: on-device CLAP harness — timings, cosine matrix, correctness check"
```

---

## Task 9: Record findings + go/no-go decision

**Files:**
- Create: `docs/superpowers/specs/2026-06-14-clap-spike-findings.md`

- [ ] **Step 1: Write the findings doc**

Record, with the real captured numbers:
- **Size:** fp32 vs int8 for the audio encoder (and text). Pass/fail vs ~200 MB.
- **Speed:** median per-10s-window ms on the Pixel + projected per-track (×3) seconds. Pass/fail vs "single-digit seconds."
- **Signal:** the cosine matrix (or a summary: mean same-genre vs mean cross-genre cosine), plus the 3 query top-3 results. Pass/fail vs "separates genres."
- **Preprocessing path taken:** raw-PCM-in-graph (preferred) vs log-mel-input fallback (and, if fallback, the Kotlin-mel cost that Phase 1 now owes).
- **Pinned for Phase 1:** exact checkpoint name + sha256, opset, quantization mode, ONNX Runtime version, and the integer `model_version` these map to.
- **Decision:** GO (build Phase 1 on this substrate) / NO-GO → Approach C / STOP.

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-06-14-clap-spike-findings.md
git commit -m "spike: CLAP on-device findings + go/no-go decision"
```

- [ ] **Step 3: Hand back**

The findings doc is the input to writing the **Phase 1** plan. Do not start Phase 1 code from this branch — Phase 1 is planned separately, grounded in these numbers, on a fresh branch. The spike branch can be kept for reference or deleted; nothing in it ships.

---

## Notes for the implementer

- **The risky task is Task 2** (ONNX export). `laion_clap`'s internal API for getting embeddings from raw tensors has shifted across versions; budget time to read its source and match `get_audio_embedding_from_data` / text-embedding call signatures exactly. If export fights you, the log-mel-input fallback is the escape hatch — take it and record the added on-device cost.
- **Decode is deliberately off-device for the spike.** Production will decode via the existing `FFmpegBridge` (see `core/data/.../audio/FFmpegBridge.kt`) and `LoudnessMeasurer` is the pattern to mirror; but isolating ONNX inference is what makes Task 8's timing trustworthy. Note decode as a known small addition in findings, not an unknown.
- **minSdk is 26** — ONNX Runtime Android supports it. No manifest changes needed for an androidTest-only dependency.
- **Don't merge this branch.** Its only durable outputs are the two docs (`...-clap-spike-findings.md` and the timing transcript) and the pinned model decision.
