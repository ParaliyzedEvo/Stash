# spike/clap-on-device — desktop (Python) half

THROWAWAY Phase-0 spike. Measures whether LAION-CLAP audio/text embeddings are
viable **on-device** (int8 ONNX on a Pixel). Not production, not merged to master.

This desktop half exports + quantizes the CLAP encoders and generates the test
fixtures that the Android `connectedAndroidTest` harness (`ClapSpikeTest.kt`)
consumes. The Android half is already authored.

---

## BANDWIDTH WARNING

Running this pulls **torch + laion-clap (~hundreds of MB of wheels)** and the
**~2 GB CLAP music checkpoint**. Do this on an unmetered connection. Nothing here
was pre-run for exactly that reason — every command below is yours to execute.

---

## What is pre-authored vs what you run

**Pre-authored (in this repo, `py_compile`-clean):**
- `requirements.txt` — pinned deps
- `export_clap_onnx.py` — CLAP -> `clap_audio.onnx` + `clap_text.onnx`
- `quantize.py` — fp32 -> `*.int8.onnx` + size report
- `make_fixtures.py` — decode clips, embed, emit `ground_truth.json` + `query_tokens.json`
- `README.md` — this file

**You run (heavy / bandwidth / hardware):**
1. Create the venv + `pip install -r requirements.txt`
2. Download the checkpoint (see below)
3. `python export_clap_onnx.py`
4. `python quantize.py`
5. **Edit the `MANIFEST` in `make_fixtures.py`** with ~20 real local clips
6. `python make_fixtures.py`  (needs `ffmpeg` on PATH)
7. Copy artifacts into `app/src/androidTest/assets/clapspike/`
8. `./gradlew connectedAndroidTest` on a physical Pixel, read logcat tag `ClapSpike`

---

## Checkpoint

File: `music_audioset_epoch_15_esc_90.pt` (this is the checkpoint Phase 1 pins via
`model_version`). Place it next to the scripts in `spike/clap/`.

- Download URL: `<FILL ME>`  (LAION-CLAP release; the music-trained HTSAT-base ckpt.
  Confirm against the laion_clap GitHub releases / HF mirror — do not trust a guess.)
- SHA-256: `<FILL ME>`  (verify after download)

Verify on Windows PowerShell:
```powershell
Get-FileHash music_audioset_epoch_15_esc_90.pt -Algorithm SHA256
```

---

## Run order (Windows PowerShell)

```powershell
# from spike/clap/
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt

# 1) export fp32 ONNX (loads ckpt, bakes mel/STFT into the audio graph)
python export_clap_onnx.py
#    -> clap_audio.onnx, clap_text.onnx  (+ onnxruntime smoke-check asserts [1,512])

# 2) dynamic int8 quantize + size report (exit criterion #1)
python quantize.py
#    -> clap_audio.int8.onnx, clap_text.int8.onnx

# 3) EDIT make_fixtures.py MANIFEST first, then:
python make_fixtures.py          # ffmpeg must be on PATH
#    -> fixtures/wav/*.wav, fixtures/ground_truth.json, fixtures/query_tokens.json
#    + prints the NxN cosine matrix and same- vs cross-genre means (exit criterion #3 pre-check)
```

### Where artifacts land
- `spike/clap/clap_audio.onnx`, `clap_text.onnx` — fp32 (intermediate)
- `spike/clap/clap_audio.int8.onnx`, `clap_text.int8.onnx` — int8 (ship to device)
- `spike/clap/fixtures/wav/<stem>.wav` — decoded 48k/mono/10s clips
- `spike/clap/fixtures/ground_truth.json`, `query_tokens.json`

### Hand-off to the device
Copy into `app/src/androidTest/assets/clapspike/`:
- `clap_audio.int8.onnx`, `clap_text.int8.onnx`
- every `fixtures/wav/<stem>.wav`
- `ground_truth.json`, `query_tokens.json`

Then `connectedAndroidTest`. The test (`ClapSpikeTest.measureClapOnDevice`) logs
SPEED / SIGNAL / FIDELITY under logcat tag `ClapSpike`.

---

## Contract (Android side wins on any drift)
- audio: input `pcm` f32 `[1,480000]` (10s @48k mono) -> output `embed` f32 `[1,512]`
- text: inputs `input_ids` + `attention_mask` i64 `[1,seqLen]` -> `embed` f32 `[1,512]`
  (no `token_type_ids` input — baked as a constant in the export)

## Exit criteria
1. audio int8 model size <= ~200 MB (`quantize.py` labels this)
2. SPEED: median per-window `embedAudio` ms (device test; production mean-pools 3 windows)
3. SIGNAL: same-genre cosine clearly > cross-genre (pre-checked desktop, confirmed device)
4. FIDELITY: device vector vs desktop ground-truth cosine > 0.999 (relaxable to ~0.99)

## Known risk
If `torch.onnx.export` won't trace the in-graph STFT, `export_clap_onnx.py`'s
FALLBACK note kicks in: switch the audio input to a precomputed log-mel `[1,64,T]`
tensor — which then **owes a Kotlin mel implementation on-device** and breaks the
"raw PCM in" contract. Flag in findings if you hit it.
