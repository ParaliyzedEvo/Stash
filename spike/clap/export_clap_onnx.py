"""
spike/clap-on-device — DESKTOP HALF — CLAP -> ONNX exporter (THROWAWAY).

Loads the LAION-CLAP *music* checkpoint and exports two ONNX graphs that match
the on-device Kotlin contract EXACTLY (ClapSpikeOnnx.kt wins on any drift):

  clap_audio.onnx : input  "pcm"            float32 [1, 480000]   (10s @ 48k mono)
                    output "embed"          float32 [1, 512]
                    (mel / STFT baked INTO the graph; on-device feeds raw PCM)

  clap_text.onnx  : inputs "input_ids"      int64   [1, seqLen]
                           "attention_mask" int64   [1, seqLen]   (dynamic seqLen)
                    output "embed"          float32 [1, 512]
                    NO third input (token_type_ids baked as a constant inside).

opset 17. Dynamic axis ONLY on text token length. Audio length is FIXED at 480000.

────────────────────────────────────────────────────────────────────────────
laion_clap 1.1.6 API surface this script targets (CHECK THESE FIRST on drift):
  - laion_clap.CLAP_Module(enable_fusion=False, amodel='HTSAT-base')
  - CLAP_Module.load_ckpt(ckpt_path)             # loads music_audioset_*.pt
  - CLAP_Module.model                            # the inner nn.Module (open_clip-ish CLAP)
  - CLAP_Module.get_audio_embedding_from_data(x=<tensor>, use_tensor=True)
        -> returns L2-NORMALIZED [B, 512] audio embeddings; x is raw audio
           float waveform [B, samples] (NOT mel). Mel/STFT happens inside.
  - CLAP_Module.model.get_text_embedding(...) is NOT directly tensor-friendly,
    so the TextBranch below drives the inner text tower:
        model.model.encode_text({'input_ids':..., 'attention_mask':...}, device=...)
        model.model.text_projection(...)         # -> 512, then F.normalize
    The inner text model is RoBERTa-base (text_model_type 'roberta') for the
    music checkpoint -> tokenizer 'roberta-base', token_type_ids all-zeros and
    can be baked / omitted (RoBERTa ignores them).
  *** If 1.1.6 names differ on the human's machine, the two forward()s below are
      the ONLY things to patch. Everything else is plumbing. ***
────────────────────────────────────────────────────────────────────────────

FALLBACK (flag for findings): if torch.onnx.export refuses to trace the in-graph
STFT (some torch/onnx combos won't export torch.stft to opset17 cleanly), switch
the audio input to a precomputed LOG-MEL tensor [1, 64, T] and move the STFT/mel
out of the graph. THE ON-DEVICE SIDE THEN OWES A KOTLIN MEL IMPLEMENTATION
(ClapSpikeOnnx.embedAudio would have to compute mel before run()) — call this
out loudly in findings, because it breaks the "raw PCM in" contract.
"""

import math
import os

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

import laion_clap


# ──────────────────────────────────────────────────────────────────────────
# LIVE-RUN FIX (torch 2.4.1): exporting RoBERTa's fused
# torch.nn.functional.scaled_dot_product_attention to ONNX opset17 crashes the
# symbolic exporter ("TypeError: z_(): incompatible function arguments" inside
# symbolic_opset14.scaled_dot_product_attention). Swap SDPA for its
# mathematically identical eager formulation during export so the tracer emits
# plain matmul/softmax/add ops it CAN export. Numerically equivalent; only the
# graph representation changes. The audio (HTSAT) branch doesn't use SDPA, so
# patching globally is safe.
# ──────────────────────────────────────────────────────────────────────────
def _sdpa_eager(query, key, value, attn_mask=None, dropout_p=0.0,
                is_causal=False, scale=None):
    scale_factor = (1.0 / math.sqrt(query.size(-1))) if scale is None else scale
    attn_weight = (query @ key.transpose(-2, -1)) * scale_factor
    if is_causal:
        L, S = query.size(-2), key.size(-2)
        mask = torch.ones(L, S, dtype=torch.bool, device=query.device).tril()
        attn_weight = attn_weight.masked_fill(~mask, float("-inf"))
    if attn_mask is not None:
        if attn_mask.dtype == torch.bool:
            attn_weight = attn_weight.masked_fill(~attn_mask, float("-inf"))
        else:
            attn_weight = attn_weight + attn_mask
    attn_weight = torch.softmax(attn_weight, dim=-1)
    return attn_weight @ value


F.scaled_dot_product_attention = _sdpa_eager
torch.nn.functional.scaled_dot_product_attention = _sdpa_eager

CKPT = "music_audioset_epoch_15_esc_90.14.pt"
HERE = os.path.dirname(os.path.abspath(__file__))
AUDIO_ONNX = os.path.join(HERE, "clap_audio.onnx")
TEXT_ONNX = os.path.join(HERE, "clap_text.onnx")

AUDIO_SAMPLES = 480_000  # 10s @ 48 kHz mono — FIXED, must match Kotlin AUDIO_SAMPLES
EMBED_DIM = 512


# ──────────────────────────────────────────────────────────────────────────
# Audio branch: raw PCM [1, 480000] -> [1, 512] (normalized).
# get_audio_embedding_from_data already does mel/STFT + HTSAT + projection.
# ──────────────────────────────────────────────────────────────────────────
class AudioBranch(nn.Module):
    def __init__(self, clap_module: "laion_clap.CLAP_Module"):
        super().__init__()
        self.clap = clap_module

    def forward(self, pcm: torch.Tensor) -> torch.Tensor:
        # pcm: [1, 480000] float32. use_tensor=True keeps autograd/trace graph.
        emb = self.clap.get_audio_embedding_from_data(x=pcm, use_tensor=True)
        # get_audio_embedding_from_data returns normalized embeddings already,
        # but normalize again defensively so the contract is explicit.
        return F.normalize(emb, dim=-1)


# ──────────────────────────────────────────────────────────────────────────
# Text branch: input_ids + attention_mask -> [1, 512] (normalized).
# token_type_ids is baked as an internal all-zeros constant so only two tensors
# are external graph inputs (the on-device run only feeds those two).
# ──────────────────────────────────────────────────────────────────────────
class TextBranch(nn.Module):
    def __init__(self, clap_module: "laion_clap.CLAP_Module"):
        super().__init__()
        self.clap = clap_module
        self.inner = clap_module.model  # the CLAP nn.Module

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        # Bake token_type_ids = zeros internally (RoBERTa ignores it anyway).
        token_type_ids = torch.zeros_like(input_ids)
        text_in = {
            "input_ids": input_ids,
            "attention_mask": attention_mask,
            "token_type_ids": token_type_ids,
        }
        # encode_text -> pooled text features; text_projection -> 512.
        feats = self.inner.encode_text(text_in, device=input_ids.device)
        if isinstance(feats, (tuple, list)):
            feats = feats[0]
        # Some laion_clap versions already project inside encode_text. Guard the
        # dim: project only if not already 512.
        if feats.shape[-1] != EMBED_DIM:
            feats = self.inner.text_projection(feats)
        return F.normalize(feats, dim=-1)


def _load_clap() -> "laion_clap.CLAP_Module":
    model = laion_clap.CLAP_Module(enable_fusion=False, amodel="HTSAT-base")
    model.load_ckpt(CKPT)
    model.eval()
    return model


def export() -> None:
    if not os.path.exists(CKPT):
        raise FileNotFoundError(
            f"Checkpoint '{CKPT}' not found. Download it first (see README.md) "
            "and place it next to this script."
        )

    model = _load_clap()

    # ── audio ───────────────────────────────────────────────────────────────
    # Idempotent: skip if a prior run already wrote it (the audio export is the
    # slow part; the text branch is what we're iterating on).
    if os.path.exists(AUDIO_ONNX):
        print(f"[export] audio model already exists, skipping -> {AUDIO_ONNX}")
    else:
        audio_branch = AudioBranch(model).eval()
        dummy_pcm = torch.zeros(1, AUDIO_SAMPLES, dtype=torch.float32)
        torch.onnx.export(
            audio_branch,
            (dummy_pcm,),
            AUDIO_ONNX,
            input_names=["pcm"],
            output_names=["embed"],
            opset_version=17,
            # Audio length is FIXED -> no dynamic axes for the audio graph.
            dynamic_axes=None,
            do_constant_folding=True,
        )
        print(f"[export] wrote audio model -> {AUDIO_ONNX}")

    # ── text ────────────────────────────────────────────────────────────────
    text_branch = TextBranch(model).eval()
    seq = 16
    dummy_ids = torch.ones(1, seq, dtype=torch.int64)
    dummy_mask = torch.ones(1, seq, dtype=torch.int64)
    torch.onnx.export(
        text_branch,
        (dummy_ids, dummy_mask),
        TEXT_ONNX,
        input_names=["input_ids", "attention_mask"],
        output_names=["embed"],
        opset_version=17,
        # Only the token-length axis (dim 1) is dynamic.
        dynamic_axes={
            "input_ids": {1: "seq"},
            "attention_mask": {1: "seq"},
        },
        do_constant_folding=True,
    )
    print(f"[export] wrote text model  -> {TEXT_ONNX}")


def _smoke_check() -> None:
    """Run onnxruntime on a zero PCM array; assert output is [1, 512]."""
    import onnxruntime as ort

    print("[smoke] running onnxruntime on zero PCM ...")
    sess = ort.InferenceSession(AUDIO_ONNX, providers=["CPUExecutionProvider"])
    zero_pcm = np.zeros((1, AUDIO_SAMPLES), dtype=np.float32)
    (out,) = sess.run(["embed"], {"pcm": zero_pcm})
    assert out.shape == (1, EMBED_DIM), f"audio embed shape {out.shape} != (1, {EMBED_DIM})"
    print(f"[smoke] audio embed OK: shape={out.shape}")

    sess_t = ort.InferenceSession(TEXT_ONNX, providers=["CPUExecutionProvider"])
    ids = np.ones((1, 16), dtype=np.int64)
    mask = np.ones((1, 16), dtype=np.int64)
    (out_t,) = sess_t.run(["embed"], {"input_ids": ids, "attention_mask": mask})
    assert out_t.shape == (1, EMBED_DIM), f"text embed shape {out_t.shape} != (1, {EMBED_DIM})"
    print(f"[smoke] text embed OK: shape={out_t.shape}")


if __name__ == "__main__":
    export()
    _smoke_check()
    print("[done] export + smoke-check complete.")
