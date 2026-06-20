"""
spike/clap-on-device — DESKTOP HALF — dynamic int8 quantizer (THROWAWAY).

Takes the fp32 ONNX graphs from export_clap_onnx.py and produces int8 variants
that the on-device test loads:

  clap_audio.onnx -> clap_audio.int8.onnx
  clap_text.onnx  -> clap_text.int8.onnx

Prints fp32 vs int8 size for both, and labels the audio int8 size against
EXIT CRITERION #1 (audio model must be <= ~200 MB to be shippable on-device).

Dynamic quantization is weight-only int8 (QInt8), no calibration data needed —
appropriate for a throwaway spike and matches how the device loads them.
"""

import os

from onnxruntime.quantization import QuantType, quantize_dynamic

HERE = os.path.dirname(os.path.abspath(__file__))
AUDIO_FP32 = os.path.join(HERE, "clap_audio.onnx")
TEXT_FP32 = os.path.join(HERE, "clap_text.onnx")
AUDIO_INT8 = os.path.join(HERE, "clap_audio.int8.onnx")
TEXT_INT8 = os.path.join(HERE, "clap_text.int8.onnx")

EXIT1_TARGET_MB = 200.0


def _mb(path: str) -> float:
    return os.path.getsize(path) / (1024.0 * 1024.0)


def quantize_one(src: str, dst: str) -> None:
    if not os.path.exists(src):
        raise FileNotFoundError(f"{src} not found - run export_clap_onnx.py first.")
    # LIVE-RUN FIX: quantize ONLY MatMul. Default dynamic quant also quantizes
    # Conv -> ConvInteger, which the spectrogram-extractor's STFT-as-Conv in the
    # audio graph uses, and ORT's CPU/Android kernels do NOT implement
    # ConvInteger (NOT_IMPLEMENTED at session init). MatMul carries the bulk of
    # the weights (all transformer/projection layers) so size still drops hard,
    # and the model stays runnable. Embeddings (Gather) + Conv stay fp32.
    quantize_dynamic(
        model_input=src,
        model_output=dst,
        weight_type=QuantType.QInt8,
        op_types_to_quantize=["MatMul"],
    )


def main() -> None:
    quantize_one(AUDIO_FP32, AUDIO_INT8)
    quantize_one(TEXT_FP32, TEXT_INT8)

    audio_fp32, audio_int8 = _mb(AUDIO_FP32), _mb(AUDIO_INT8)
    text_fp32, text_int8 = _mb(TEXT_FP32), _mb(TEXT_INT8)

    print("-" * 58)
    print(f"audio  fp32 = {audio_fp32:7.1f} MB   int8 = {audio_int8:7.1f} MB")
    print(f"text   fp32 = {text_fp32:7.1f} MB   int8 = {text_int8:7.1f} MB")
    print("-" * 58)
    verdict = "PASS" if audio_int8 <= EXIT1_TARGET_MB else "FAIL"
    print(
        f"EXIT CRITERION #1 [{verdict}]: audio int8 = {audio_int8:.1f} MB "
        f"(target <= ~{EXIT1_TARGET_MB:.0f} MB)"
    )
    print(f"wrote {AUDIO_INT8}")
    print(f"wrote {TEXT_INT8}")


if __name__ == "__main__":
    main()
