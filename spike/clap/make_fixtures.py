"""
spike/clap-on-device — DESKTOP HALF — fixture + ground-truth generator (THROWAWAY).

Produces everything the on-device test (ClapSpikeTest.kt) loads from
`app/src/androidTest/assets/clapspike/`:

  <stem>.wav            one 48kHz / mono / PCM16 / 10s clip per ground-truth entry
  ground_truth.json     desktop int8 audio embeddings + genre labels
  query_tokens.json     3 tokenized text queries (input_ids + attention_mask)

JSON SHAPES — these MUST match the header of ClapSpikeTest.kt EXACTLY:

  ground_truth.json:
    {
      "clips":   ["bluesy_riff", "techno_loop", ...],      # file stems, NO .wav
      "genres":  { "bluesy_riff": "blues", ... },           # stem -> genre label
      "vectors": { "bluesy_riff": [<512 floats>], ... }     # desktop int8 embedding
    }

  query_tokens.json:
    {
      "queries": [
        { "text": "...", "input_ids": [<ints>], "attention_mask": [<ints>] },
        ...   # exactly 3 queries
      ]
    }

It also prints the NxN cosine matrix and mean same-genre vs cross-genre cosine —
a DESKTOP pre-check of exit criterion #3 (genre separation) before the human
even installs the APK.

Run AFTER export_clap_onnx.py + quantize.py (it consumes the .int8.onnx files).
Requires `ffmpeg` on PATH for decoding.
"""

import json
import os
import subprocess

import numpy as np
import onnxruntime as ort
import soundfile as sf

HERE = os.path.dirname(os.path.abspath(__file__))
WAV_DIR = os.path.join(HERE, "fixtures", "wav")
FIX_DIR = os.path.join(HERE, "fixtures")
AUDIO_INT8 = os.path.join(HERE, "clap_audio.int8.onnx")
TEXT_INT8 = os.path.join(HERE, "clap_text.int8.onnx")

SR = 48_000
SECONDS = 10
AUDIO_SAMPLES = SR * SECONDS  # 480000 — must match Kotlin AUDIO_SAMPLES
EMBED_DIM = 512

# The text encoder for the music checkpoint is RoBERTa-base. The tokenizer MUST
# match what CLAP trained with, or query embeddings are garbage. Patch this if
# the human's laion_clap build uses a different text_model_type.
TOKENIZER_NAME = "roberta-base"
MAX_TOKENS = 64  # CLAP's text context length

# 3 natural-language queries the on-device test scores against every clip.
QUERIES = [
    "calm piano for a rainy drive",
    "aggressive heavy guitars",
    "upbeat electronic dance",
]

# ──────────────────────────────────────────────────────────────────────────
# EDIT ME: ~20 genre-spread clips. (path-to-source-audio, genre-label).
# Paths can be mp3/flac/wav/m4a — anything ffmpeg decodes. ffmpeg grabs a 10s
# window starting at 30s (-ss 30 -t 10). The stem of <path> (sans extension)
# becomes the clip name in the JSON AND the .wav filename, so keep stems unique.
# Aim for several genres with >=2 clips each so same-vs-cross separation is real.
# These are PLACEHOLDERS — replace with real local files before running.
# ──────────────────────────────────────────────────────────────────────────
# Built live from spike/clap/source_clips/ (20 tracks pulled off the device,
# named "<genre>_<artist>.m4a"). Genre = filename prefix before the first "_".
import glob as _glob

_SRC_DIR = os.path.join(HERE, "source_clips")
MANIFEST = [
    (p, os.path.basename(p).split("_", 1)[0])
    for p in sorted(_glob.glob(os.path.join(_SRC_DIR, "*.m4a")))
]


def stem_of(path: str) -> str:
    return os.path.splitext(os.path.basename(path))[0]


def decode_to_wav(src: str, dst: str) -> None:
    """ffmpeg: 10s window @30s, mono, 48k, PCM16 WAV."""
    cmd = [
        "ffmpeg", "-y", "-ss", "30", "-t", str(SECONDS),
        "-i", src, "-ac", "1", "-ar", str(SR), "-f", "wav", dst,
    ]
    subprocess.run(cmd, check=True, capture_output=True)


def load_pcm(wav_path: str) -> np.ndarray:
    """Load a wav as float32 [AUDIO_SAMPLES] mono, pad/truncate to exact length."""
    data, sr = sf.read(wav_path, dtype="float32", always_2d=False)
    if data.ndim > 1:
        data = data.mean(axis=1)
    if sr != SR:
        raise ValueError(f"{wav_path}: sr={sr}, expected {SR}")
    if len(data) < AUDIO_SAMPLES:
        data = np.pad(data, (0, AUDIO_SAMPLES - len(data)))
    elif len(data) > AUDIO_SAMPLES:
        data = data[:AUDIO_SAMPLES]
    return data.astype(np.float32)


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    denom = np.linalg.norm(a) * np.linalg.norm(b)
    return float(np.dot(a, b) / denom) if denom else 0.0


def main() -> None:
    if not MANIFEST:
        raise SystemExit(
            "MANIFEST is empty — edit make_fixtures.py and add ~20 (path, genre) "
            "entries pointing at real local audio files."
        )
    for f in (AUDIO_INT8, TEXT_INT8):
        if not os.path.exists(f):
            raise FileNotFoundError(f"{f} missing — run export + quantize first.")

    os.makedirs(WAV_DIR, exist_ok=True)
    audio_sess = ort.InferenceSession(AUDIO_INT8, providers=["CPUExecutionProvider"])
    text_sess = ort.InferenceSession(TEXT_INT8, providers=["CPUExecutionProvider"])

    clips: list[str] = []
    genres: dict[str, str] = {}
    vectors: dict[str, list] = {}
    embeds: dict[str, np.ndarray] = {}

    for src, genre in MANIFEST:
        stem = stem_of(src)
        if stem in genres:
            raise ValueError(f"duplicate clip stem '{stem}' — stems must be unique")
        wav_path = os.path.join(WAV_DIR, f"{stem}.wav")
        decode_to_wav(src, wav_path)
        pcm = load_pcm(wav_path)  # [480000]

        (emb,) = audio_sess.run(["embed"], {"pcm": pcm[None, :]})  # [1, 512]
        vec = emb[0].astype(np.float32)

        clips.append(stem)
        genres[stem] = genre
        vectors[stem] = vec.tolist()
        embeds[stem] = vec
        print(f"[fixture] {stem:24s} genre={genre:10s} -> {wav_path}")

    # ── ground_truth.json (EXACT shape from ClapSpikeTest.kt header) ─────────
    ground_truth = {"clips": clips, "genres": genres, "vectors": vectors}
    gt_path = os.path.join(FIX_DIR, "ground_truth.json")
    with open(gt_path, "w", encoding="utf-8") as fh:
        json.dump(ground_truth, fh)
    print(f"[write] {gt_path}  ({len(clips)} clips)")

    # ── query_tokens.json (EXACT shape from ClapSpikeTest.kt header) ─────────
    from transformers import RobertaTokenizer

    tok = RobertaTokenizer.from_pretrained(TOKENIZER_NAME)
    queries_out = []
    query_embeds: dict[str, np.ndarray] = {}
    for text in QUERIES:
        enc = tok(
            text,
            padding="max_length",
            truncation=True,
            max_length=MAX_TOKENS,
            return_tensors="np",
        )
        ids = enc["input_ids"].astype(np.int64)          # [1, MAX_TOKENS]
        mask = enc["attention_mask"].astype(np.int64)    # [1, MAX_TOKENS]
        queries_out.append({
            "text": text,
            "input_ids": ids[0].tolist(),
            "attention_mask": mask[0].tolist(),
        })
        (qemb,) = text_sess.run(["embed"], {"input_ids": ids, "attention_mask": mask})
        query_embeds[text] = qemb[0].astype(np.float32)

    qt_path = os.path.join(FIX_DIR, "query_tokens.json")
    with open(qt_path, "w", encoding="utf-8") as fh:
        json.dump({"queries": queries_out}, fh)
    print(f"[write] {qt_path}  ({len(queries_out)} queries)")

    # ── NxN cosine matrix + same vs cross genre means (exit criterion #3) ────
    print("\nCosine matrix (rows/cols in clip order):")
    header = "          " + " ".join(genres[c][:6].ljust(7) for c in clips)
    print(header)
    same_sum, same_n = 0.0, 0
    cross_sum, cross_n = 0.0, 0
    for a in clips:
        row = f"{genres[a][:8]:<9s}"
        for b in clips:
            c = cosine(embeds[a], embeds[b])
            row += f" {c:6.3f} "
            if a != b:
                if genres[a] == genres[b]:
                    same_sum += c
                    same_n += 1
                else:
                    cross_sum += c
                    cross_n += 1
        print(row)

    same_mean = same_sum / same_n if same_n else float("nan")
    cross_mean = cross_sum / cross_n if cross_n else float("nan")
    print(
        f"\nSIGNAL (desktop pre-check): mean same-genre cosine = {same_mean:.4f} ; "
        f"mean cross-genre cosine = {cross_mean:.4f} ; "
        f"separation = {same_mean - cross_mean:.4f}"
    )

    # ── query x clip top-3 (sanity that text tower is sane) ──────────────────
    print("\nQuery -> top-3 clips:")
    for text in QUERIES:
        scored = sorted(
            ((c, cosine(query_embeds[text], embeds[c])) for c in clips),
            key=lambda t: t[1],
            reverse=True,
        )[:3]
        top3 = ", ".join(f"{name}({genres[name]})={val:.3f}" for name, val in scored)
        print(f'  "{text}" -> {top3}')

    print(
        "\n[next] copy fixtures/*.wav, fixtures/ground_truth.json, "
        "fixtures/query_tokens.json, clap_audio.int8.onnx, clap_text.int8.onnx "
        "into app/src/androidTest/assets/clapspike/ then run connectedAndroidTest."
    )


if __name__ == "__main__":
    main()
