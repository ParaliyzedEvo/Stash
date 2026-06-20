"""Diagnostic: native laion_clap vs our ONNX (fp32 + int8) on 3 clips.

Isolates whether the flat cosine matrix is an export/quant bug or upstream.
Compares cross-genre separation across three pipelines on the SAME wavs.
"""
import os
import numpy as np
import soundfile as sf
import onnxruntime as ort
import torch
import laion_clap

HERE = os.path.dirname(os.path.abspath(__file__))
WAV = os.path.join(HERE, "fixtures", "wav")
CKPT = os.path.join(HERE, "music_audioset_epoch_15_esc_90.14.pt")
CLIPS = ["classical_brownridge", "metal_acidbath", "hiphop_2pac", "classical_tharaud"]


def load_pcm(stem):
    data, sr = sf.read(os.path.join(WAV, stem + ".wav"), dtype="float32", always_2d=False)
    if data.ndim > 1:
        data = data.mean(axis=1)
    n = 480000
    data = np.pad(data, (0, max(0, n - len(data))))[:n]
    return data.astype(np.float32)


def cos(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))


pcms = {c: load_pcm(c) for c in CLIPS}

# 1) native laion_clap
print("loading native laion_clap ...")
m = laion_clap.CLAP_Module(enable_fusion=False, amodel="HTSAT-base")
m.load_ckpt(CKPT)
m.eval()
native = {}
with torch.no_grad():
    for c in CLIPS:
        v = m.get_audio_embedding_from_data(x=pcms[c][None, :], use_tensor=False)
        native[c] = np.asarray(v).reshape(-1)

# 2) onnx fp32
fp32 = ort.InferenceSession(os.path.join(HERE, "clap_audio.onnx"), providers=["CPUExecutionProvider"])
onnx_fp32 = {c: fp32.run(["embed"], {"pcm": pcms[c][None, :]})[0].reshape(-1) for c in CLIPS}

# 3) onnx int8
i8 = ort.InferenceSession(os.path.join(HERE, "clap_audio.int8.onnx"), providers=["CPUExecutionProvider"])
onnx_i8 = {c: i8.run(["embed"], {"pcm": pcms[c][None, :]})[0].reshape(-1) for c in CLIPS}


def report(name, emb):
    print(f"\n=== {name} ===")
    # cross-genre pairs should be LOW; same-genre (the two classical) should be HIGH
    print(f"  classical_brownridge vs classical_tharaud (SAME) = {cos(emb['classical_brownridge'], emb['classical_tharaud']):.3f}")
    print(f"  classical_brownridge vs metal_acidbath  (CROSS) = {cos(emb['classical_brownridge'], emb['metal_acidbath']):.3f}")
    print(f"  classical_brownridge vs hiphop_2pac     (CROSS) = {cos(emb['classical_brownridge'], emb['hiphop_2pac']):.3f}")
    print(f"  metal_acidbath       vs hiphop_2pac     (CROSS) = {cos(emb['metal_acidbath'], emb['hiphop_2pac']):.3f}")


report("NATIVE laion_clap", native)
report("ONNX fp32", onnx_fp32)
report("ONNX int8", onnx_i8)

# fidelity: native vs onnx per clip
print("\n=== fidelity (native vs onnx, per clip) ===")
for c in CLIPS:
    print(f"  {c:22s} fp32={cos(native[c], onnx_fp32[c]):.4f}  int8={cos(native[c], onnx_i8[c]):.4f}")
