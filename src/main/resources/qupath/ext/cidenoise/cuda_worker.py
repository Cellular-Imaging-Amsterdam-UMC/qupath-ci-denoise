"""Run the bundled Noise2Noise-FMD model in a persistent CUDA worker."""

import sys

import torch


def main():
    model_path, tile_argument = sys.argv[1:]
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is unavailable in this PyTorch environment.")
    tile = int(tile_argument)
    byte_count = tile * tile * 4
    device = torch.device("cuda", 0)
    torch.backends.cudnn.allow_tf32 = False
    model = torch.jit.load(model_path, map_location=device).eval()
    source, target = sys.stdin.buffer, sys.stdout.buffer
    with torch.inference_mode():
        warmup = model(torch.zeros((1, 1, tile, tile), device=device, dtype=torch.float32))
        if warmup.shape != (1, 1, tile, tile) or not torch.isfinite(warmup).all().item():
            raise RuntimeError("Noise2Noise-FMD returned invalid CUDA output.")
        del warmup
        target.write((torch.cuda.get_device_name(device) + "\n").encode("utf-8"))
        target.flush()
        while data := source.read(byte_count):
            if len(data) != byte_count:
                raise EOFError("Incomplete Noise2Noise input tile.")
            tensor = torch.frombuffer(bytearray(data), dtype=torch.float32).reshape(1, 1, tile, tile)
            result = model(tensor.to(device)).cpu().contiguous().numpy()
            target.write(result.tobytes())
            target.flush()


if __name__ == "__main__":
    main()
