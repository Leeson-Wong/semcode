#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = [
#   "torch>=2.0.0",
#   "transformers>=4.40,<5",
#   "model2vec[distill]>=0.1.0",
#   "safetensors",
#   "numpy",
# ]
# ///
"""Distill a standard-architecture HF embedding model into Model2Vec (offline).

Unlike nomic2vec.py this needs no trust_remote_code and no monkey-patching:
any plain AutoModel (bge-m3, multilingual-e5, gte, ...) loads and distills
directly. Calibration cost is one forward pass over the vocabulary.

Usage:
  distill_2vec.py -m <model_dir> -o ./bge_m3_m2v --pooling first --pca-dims 256
"""
import argparse, json, sys
from pathlib import Path

def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("-m", "--model-dir", required=True, help="Local model directory (config.json + weights)")
    p.add_argument("-o", "--output-dir", required=True)
    p.add_argument("--pca-dims", type=int, default=256)
    p.add_argument("--pooling", default="first", choices=["mean", "first", "last"],
                   help="Teacher pooling to mirror: bge-m3=first(CLS), e5=mean, qwen=last")
    return p.parse_args()

def fix_unk(output_dir: Path):
    """model2vec's saved tokenizer lacks the [UNK] entry some loaders need;
    add it to the vocab and give it a zero embedding row."""
    import numpy as np
    from safetensors.numpy import load_file, save_file
    tok_path = output_dir / "tokenizer.json"
    tok = json.load(open(tok_path, encoding="utf-8"))
    vocab = tok["model"]["vocab"]
    if not any(t == "[UNK]" for t, _ in vocab):
        unk_score = next((s for t, s in vocab if t in ("<unk>", "<UNK>")), -12.6)
        vocab.insert(1, ["[UNK]", unk_score])
    tok["model"]["unk_id"] = next(i for i, (t, _) in enumerate(vocab) if t == "[UNK]")
    json.dump(tok, open(tok_path, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    tensors = load_file(str(output_dir / "model.safetensors"))
    emb_name = next(k for k, v in tensors.items() if getattr(v, "ndim", 0) == 2)
    if tensors[emb_name].shape[0] == len(vocab) - 1:
        tensors[emb_name] = np.vstack(
            [tensors[emb_name],
             np.zeros((1, tensors[emb_name].shape[1]), dtype=tensors[emb_name].dtype)]
        )
        save_file(tensors, str(output_dir / "model.safetensors"))
        print("  • [UNK] zero row appended")

def main():
    import os
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    os.environ["HF_HUB_DISABLE_XET"] = "1"
    args = parse_args()

    from transformers import AutoModel, AutoTokenizer
    model_dir = Path(args.model_dir).expanduser().resolve()
    if not (model_dir / "config.json").exists():
        sys.exit(f"✗ no config.json in {model_dir}")
    print(f"Loading {model_dir} ...")
    tokenizer = AutoTokenizer.from_pretrained(str(model_dir), local_files_only=True)
    model = AutoModel.from_pretrained(str(model_dir), local_files_only=True)
    model.eval()
    print(f"✓ {type(model).__name__} / {type(tokenizer).__name__}")

    from model2vec.distill import distill_from_model
    m2v = distill_from_model(
        model=model,
        tokenizer=tokenizer,
        pca_dims=args.pca_dims,
        device="cpu",
        pooling=args.pooling,
    )
    output_dir = Path(args.output_dir).expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    m2v.save_pretrained(str(output_dir))
    print(f"✓ Saved to {output_dir}")
    fix_unk(output_dir)
    print("All done.")

if __name__ == "__main__":
    main()
