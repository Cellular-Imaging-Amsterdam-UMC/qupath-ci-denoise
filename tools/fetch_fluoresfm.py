"""Fetch the published FluoResFM checkpoint and the matching BiomedCLIP text assets."""

import argparse
import hashlib
from pathlib import Path
from tempfile import NamedTemporaryFile
from urllib.request import urlopen


CLIP_SOURCE = (
    "https://huggingface.co/microsoft/BiomedCLIP-PubMedBERT_256-vit_base_patch16_224"
    "/resolve/9f341de/open_clip_pytorch_model.bin"
)
TOKENIZER_SOURCE = (
    "https://huggingface.co/microsoft/BiomedNLP-BiomedBERT-base-uncased-abstract"
    "/resolve/d673b8835373c6fa116d6d8006b33d48734e305d/"
)
FILES = [
    (
        "epoch_0_iter_700000.pt",
        "https://drive.usercontent.google.com/download?id=1AH0kc4-Niu0pdCvNL9kt6fMgdnlH2YAH&export=download&confirm=t",
        "d2402bff6e52fce05e5442e96eb0a9a39623adc932c73b46ddd8998d2f452db3",
    ),
    ("open_clip_pytorch_model.bin", CLIP_SOURCE, "52cc993c5c5ff962bd0c60931874bc001e7e9b41666a385530f4a036294576be"),
    ("tokenizer/config.json", TOKENIZER_SOURCE + "config.json", "56bab767c02bc792897638feb8addc06d483d9c73769a5f554bbabea1f5507a1"),
    (
        "tokenizer/tokenizer_config.json",
        TOKENIZER_SOURCE + "tokenizer_config.json",
        "53b6f42b8b8daddbdc6d3532c324187a92b46c1602bd6e8d4ad5a413b4fc90e1",
    ),
    ("tokenizer/vocab.txt", TOKENIZER_SOURCE + "vocab.txt", "7b36651908a88bc38bda41b728b2a598191e0d3b553cbacf7b1e5f026d5b5b9f"),
]


def install(destination, url, expected):
    if destination.exists():
        with destination.open("rb") as source:
            if hashlib.file_digest(source, "sha256").hexdigest() == expected:
                print(f"Ready: {destination}", flush=True)
                return
        raise ValueError(f"Existing file has a different checksum: {destination}")

    destination.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading {destination.name}…", flush=True)
    digest = hashlib.sha256()
    with NamedTemporaryFile(dir=destination.parent, suffix=".part", delete=False) as partial:
        temp = Path(partial.name)
        try:
            with urlopen(url, timeout=90) as source:
                while block := source.read(1024 * 1024):
                    digest.update(block)
                    partial.write(block)
            partial.close()
            if digest.hexdigest() != expected:
                raise ValueError(f"Incorrect checksum for {destination.name}")
            temp.replace(destination)
        finally:
            partial.close()
            temp.unlink(missing_ok=True)
    print(f"Ready: {destination}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="FluoResFM model directory, e.g. models/fluoresfm")
    config = parser.parse_args()
    for name, url, expected in FILES:
        install(config.directory / name, url, expected)


if __name__ == "__main__":
    main()
