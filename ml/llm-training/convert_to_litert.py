#!/usr/bin/env python3
"""Convert a merged Gemma Hugging Face checkpoint to dynamic-INT8 LiteRT."""

from __future__ import annotations

import argparse
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT = HERE / "artifacts" / "litert"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--name", default="alarmcontrol-gemma3-270m")
    parser.add_argument("--model-size", choices=("270m", "1b"), default="270m")
    parser.add_argument("--prefill-seq-len", type=int, default=2_048)
    parser.add_argument("--kv-cache-max-len", type=int, default=4_096)
    parser.add_argument(
        "--quantize",
        choices=("none", "fp16", "dynamic_int8", "weight_only_int8"),
        default="dynamic_int8",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    model_dir = args.model_dir.resolve()
    output_dir = args.output_dir.resolve()
    if not model_dir.is_dir():
        raise SystemExit(f"Merged model directory not found: {model_dir}")
    if args.prefill_seq_len > args.kv_cache_max_len:
        raise SystemExit("prefill-seq-len cannot exceed kv-cache-max-len")
    output_dir.mkdir(parents=True, exist_ok=True)

    from litert_torch.generative.examples.gemma3 import gemma3
    from litert_torch.generative.layers import kv_cache
    from litert_torch.generative.utilities import converter
    from litert_torch.generative.utilities.export_config import ExportConfig

    model = (
        gemma3.build_model_270m(str(model_dir))
        if args.model_size == "270m"
        else gemma3.build_model_1b(str(model_dir))
    )
    export_config = ExportConfig()
    export_config.kvcache_layout = kv_cache.KV_LAYOUT_TRANSPOSED
    export_config.mask_as_input = True
    converter.convert_to_tflite(
        model,
        output_path=str(output_dir),
        output_name_prefix=args.name,
        prefill_seq_len=args.prefill_seq_len,
        kv_cache_max_len=args.kv_cache_max_len,
        quantize=args.quantize,
        export_config=export_config,
    )
    candidates = sorted(output_dir.glob(f"{args.name}*.tflite"))
    if not candidates:
        raise SystemExit(f"Converter did not emit {args.name}*.tflite in {output_dir}")
    print("LiteRT output:")
    for candidate in candidates:
        print(f"  {candidate} ({candidate.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
