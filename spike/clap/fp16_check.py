"""Convert audio+text ONNX to fp16 (sensitive ops blocked) and verify signal."""
import os, sys
import numpy as np
import onnx
import onnxruntime as ort
import soundfile as sf
from onnxconverter_common import float16

HERE = os.path.dirname(os.path.abspath(__file__))
BLOCK = ["Conv", "Cast", "ConvTranspose", "STFT", "DFT", "ReduceMean",
         "ReduceMax", "Pow", "Sqrt", "Div", "InstanceNormalization", "Resize"]


def conv(src, dst):
    m = onnx.load(os.path.join(HERE, src))
    m16 = float16.convert_float_to_float16(m, keep_io_types=True, op_block_list=BLOCK)
    onnx.save(m16, os.path.join(HERE, dst))
    return os.path.getsize(os.path.join(HERE, dst)) / 1048576


def load(stem):
    d, sr = sf.read(os.path.join(HERE, "fixtures", "wav", stem + ".wav"),
                    dtype="float32", always_2d=False)
    if d.ndim > 1:
        d = d.mean(1)
    return np.pad(d, (0, max(0, 480000 - len(d))))[:480000].astype(np.float32)


def cos(a, b):
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))


a = conv("clap_audio.onnx", "clap_audio.fp16.onnx")
t = conv("clap_text.onnx", "clap_text.fp16.onnx")
print(f"SIZE audio fp16 = {a:.1f} MB   text fp16 = {t:.1f} MB", flush=True)

s = ort.InferenceSession(os.path.join(HERE, "clap_audio.fp16.onnx"),
                         providers=["CPUExecutionProvider"])
cl = ["classical_brownridge", "classical_tharaud", "metal_acidbath", "hiphop_2pac"]
e = {c: s.run(["embed"], {"pcm": load(c)[None, :]})[0].reshape(-1) for c in cl}
print(f"SIGNAL fp16: cls-cls(SAME)={cos(e[cl[0]], e[cl[1]]):.3f}  "
      f"cls-metal(CROSS)={cos(e[cl[0]], e[cl[2]]):.3f}  "
      f"metal-hiphop(CROSS)={cos(e[cl[2]], e[cl[3]]):.3f}", flush=True)
print("OK", flush=True)
