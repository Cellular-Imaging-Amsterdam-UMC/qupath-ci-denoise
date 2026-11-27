"""Rebuild the ONNX and TorchScript models from the checksum-pinned FMD checkpoint."""

import argparse
import hashlib
import io
from pathlib import Path
from urllib.request import urlopen

import onnx
import torch

from noise2noise import Noise2Noise

REVISION = "c885aee8adb62bcd2d5c3862273d4ea0f68b3068"
URL = (
    f"https://raw.githubusercontent.com/ND-HowardGroup/Instant-Image-Denoising/{REVISION}"
    "/Plugins/Image_Denoising_Plugins_Journal/best_results_with_configurations/"
    "Noise2Noise_NBN/model_multiple_gpus_unet_nbn_lr3.h5"
)
SHA256 = "49f7bb4abf235c9f93eae6bdec256c41a49ad9e0cad9743ab9a57900afb0ab40"
OUTPUT = Path(__file__).resolve().parents[1] / "src/main/resources/qupath/ext/cidenoise/noise2noise-fmd.onnx"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, help="Use a local copy of the original checkpoint")
    args = parser.parse_args()
    if args.checkpoint:
        checkpoint = args.checkpoint.read_bytes()
    else:
        with urlopen(URL, timeout=60) as response:
            checkpoint = response.read()
    if hashlib.sha256(checkpoint).hexdigest() != SHA256:
        raise ValueError("The checkpoint SHA-256 does not match the published Noise2Noise-FMD weights")

    model = Noise2Noise().eval()
    torch.set_num_threads(2)
    model.load_published(io.BytesIO(checkpoint))
    torch.onnx.export(
        model, (torch.zeros(1, 1, 64, 64),), OUTPUT,
        dynamo=False, opset_version=11, input_names=["input"], output_names=["output"],
        dynamic_axes={"input": {2: "height", 3: "width"}, "output": {2: "height", 3: "width"}},
    )
    onnx.checker.check_model(str(OUTPUT))
    example = torch.zeros(1, 1, 64, 64)
    torch.jit.trace(model, example).save(str(OUTPUT.with_suffix(".pt")))


if __name__ == "__main__":
    main()
