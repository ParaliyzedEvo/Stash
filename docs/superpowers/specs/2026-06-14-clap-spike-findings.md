# CLAP On-Device Spike — Findings & Go/No-Go

> **Phase 0 of** `2026-06-14-ai-sonic-recommendations-design.md`. Throwaway spike on branch `spike/clap-on-device`. Run end-to-end 2026-06-14 on a physical **Pixel 6 Pro (raven), Android 16**, ONNX Runtime 1.20.0 (CPU execution provider), against **20 real tracks pulled from the device library** spanning 8 genres (classical, dance, grunge, hip-hop, metal, pop, soul, synth; ≥2 each).

## Decision: **GO** ✅

CLAP runs on-device, the ONNX export is numerically faithful, and the embedding space separates music by sound and matches free-text queries to the right tracks — on the user's own library, on real hardware. Build Phase 1 (substrate + passive sonic-fingerprint discovery) on this substrate.

The one caveat is a **packaging task, not a viability question**: the production model must ship at **fp16 (~143 MB)**, not the dynamic-int8 path (which collapses the audio embeddings) and not raw fp32 (285 MB, used here only to get clean numbers). See "Quantization" below — this is a bounded Phase-1 task with a known cause.

---

## Exit criteria results

| Criterion | Target | Result | Verdict |
|---|---|---|---|
| **#1 Size** | audio encoder ≤ ~200 MB | fp16 audio = **143 MB** (int8 = 76 MB but unusable; fp32 = 285 MB) | **PASS** |
| **#2 Speed** | single-digit seconds / track | **median 1631 ms / 10 s window → ~4.9 s / track** (3 windows), Pixel 6 Pro CPU EP, fp32 | **PASS** |
| **#3 Signal** | cosine separates genres | same-genre **0.465** vs cross-genre **0.317** (sep **+0.148**); text→audio queries correct | **PASS** |
| Fidelity (export correctness) | device ≈ desktop | **1.00000** cosine, all 20 clips | **PASS** |

### #2 Speed — detail
Per-10 s-window `embedAudio` ranged 1340–1796 ms across the 20 clips (median 1631 ms), CPU execution provider, **fp32** model. Production mean-pools 3 windows/track → **~4.9 s/track**. Notes:
- This is the **worst-case** number: CPU-only, fp32 (the heaviest model), no NNAPI/XNNPACK/GPU delegate. fp16 + XNNPACK should be faster; the order of magnitude (seconds, not minutes) is what matters and it's comfortably in budget.
- At ~5 s/track, a one-time backfill of a 3,000-track library while charging is ~4 hours of compute — an overnight job, exactly as the design assumed. Incremental embedding at download-finalize is trivially absorbed.

### #3 Signal — detail
Cross-modal text→audio retrieval, **on-device**, top-3 per query (this is the literal "ask the vibe" mechanic):
- *"calm piano for a rainy drive"* → **classical_brownridge** (0.181), pop_adele (0.138), dance_alicedeejay (0.103)
- *"aggressive heavy guitars"* → **metal_acidbath** (0.334), **grunge_7yb** (0.237), **grunge_aic** (0.195)
- *"upbeat electronic dance"* → **dance_alicedeejay** (0.394), **dance_adamski** (0.201), **dance_alexparty** (0.197)

The piano query lands on the solo-piano track; the heavy-guitars query lands on metal then two grunge; the electronic query returns all three dance tracks as the top 3. This is the feature working.

---

## What broke and why (so Phase 1 doesn't repeat it)

The spike surfaced four real issues; all are diagnosed and fixed in the spike code.

1. **Dynamic int8 quantization COLLAPSES the audio embeddings.** Default `quantize_dynamic` produced an audio model where *every* clip sits at ~0.8 cosine to every other (separation ≈ 0.000) and text queries match nothing. Proven via `diag_native.py`: native laion_clap and ONNX **fp32** both separate cleanly (same 0.6 vs cross 0.1–0.3, fidelity 0.97–1.00 to native), while **int8** fidelity-to-native is **0.07–0.33** — i.e. garbage. **Do not dynamic-int8 the audio encoder.** The production path is **fp16** (see below).

2. **fp16 is the right production format but the converter emits an invalid graph.** `onnxconverter_common.float16.convert_float_to_float16(keep_io_types=True)` gives the correct size (audio **143 MB**) but produces a `/Cast` node whose output type isn't reconciled, so ORT refuses to load it (`Type Error ... /Cast_output_0`). Blocking the audio-sensitive ops (`Conv`/`Cast`/spectrogram path) didn't resolve it. **Phase-1 task:** get a clean-loading fp16 audio model — options: (a) fix/upgrade the fp16 converter or do the Cast-node surgery, (b) ORT's `float16` transformer tool, or (c) static/QDQ int8 *with calibration* (excluding the spectrogram Conv). The spike used fp32 on-device purely to get trustworthy numbers; fp16 packaging is owed before shipping.

3. **285 MB model as a Java `byte[]` OOMs the app heap.** Loading model bytes via `readBytes()` + `createSession(byte[])` exceeded ART's ~268 MB growth limit. Fix (already in the harness, and a **Phase-1 invariant**): open ORT sessions **by file path** so the model is mmap'd into native memory, never the Java heap. Extract the model from APK assets / model-download cache to a file, then `createSession(path)`.

4. **ONNX export of the RoBERTa text branch crashes at opset 17** (`scaled_dot_product_attention` → `z_()` TypeError, a known torch 2.4 exporter bug). Fixed by monkey-patching `F.scaled_dot_product_attention` to its eager math form during export (`export_clap_onnx.py`). The **audio** branch (HTSAT, raw-PCM-in-graph with STFT baked in) exported cleanly — **no log-mel fallback was needed, so Phase 1 owes no Kotlin mel implementation**; the on-device side feeds raw 48 kHz mono PCM exactly as designed.

Also: dynamic int8 of the spectrogram Conv produces `ConvInteger`, which ORT's CPU/Android kernels don't implement (`NOT_IMPLEMENTED`) — another reason int8 is the wrong path for the audio model.

---

## Pinned for Phase 1

- **Checkpoint:** LAION-CLAP `music_audioset_epoch_15_esc_90.14.pt` (HuggingFace `lukewys/laion_clap`, 2,352,471,003 bytes). Architecture `HTSAT-base`, `enable_fusion=False`, text tower **RoBERTa-base** (tokenizer `roberta-base`, max 64 tokens). This maps to `model_version = 1`.
- **Embedding dim:** 512. **Audio input:** raw float32 PCM `[1, 480000]` (10 s @ 48 kHz mono), tensor name `pcm` → output `embed`. **Text input:** `input_ids` + `attention_mask` int64 (no `token_type_ids` — baked as constant) → `embed`.
- **Sampling:** the spike embedded one 10 s mid-track window/clip; production mean-pools **3 windows** (intro-skip / middle / near-end) — budget ~5 s/track from the measured ~1.6 s/window.
- **Export toolchain (reproducible):** Python 3.11, torch 2.4.1, torchvision 0.19.1, laion-clap 1.1.6, numpy **1.23.5** (laion-clap hard-pins it), onnx 1.16.2, onnxruntime 1.20.0, opset 17, SDPA-eager export patch. Env built with `uv`.
- **Production format:** **fp16** (audio ~143 MB), downloaded-on-enable + checksum-verified, **opened by path (mmap)**. NOT dynamic-int8.
- **Runtime:** `onnxruntime-android` 1.20.0; consider NNAPI/XNNPACK EP for the production speed win (spike used CPU EP).

## Open follow-ups for the Phase-1 plan

1. Produce a clean-loading **fp16** audio (and text) model; re-confirm signal ≥ the fp32 baseline (same ~0.46 / cross ~0.32) and re-measure speed with fp16 + XNNPACK.
2. Decide model **provisioning** (GitHub release vs CDN) + checksum + the `model_version`↔checkpoint constant.
3. Wire `embedAudio` to decode via the existing `FFmpegBridge` (the spike decoded off-device); confirm 3-window sampling + mean-pool.
4. Carry forward the **by-path/mmap** session-loading invariant into production `ClapEmbedder`.

---

## Spike artifacts (branch `spike/clap-on-device`, not for merge)

- `spike/clap/` — Python: `export_clap_onnx.py` (+SDPA fix), `quantize.py` (MatMul-only), `make_fixtures.py`, `diag_native.py` (the decisive native-vs-ONNX probe), `fp16_check.py`, `requirements.txt`, `README.md`. (venv, checkpoint, *.onnx, fixtures/, source_clips/ are git-ignored.)
- `app/src/androidTest/.../clapspike/` — `ClapSpikeOnnx.kt`, `WavReader.kt`, `ClapSpikeTest.kt` (passes on-device).
- `spike/clap/device_run.txt` — full Pixel 6 Pro logcat transcript (timings, cosine matrix, queries, fidelity).
