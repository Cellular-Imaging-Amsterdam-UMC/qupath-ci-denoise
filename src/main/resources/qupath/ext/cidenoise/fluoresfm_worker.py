"""Run FluoResFM deconvolution on single-channel tiles using the published checkpoint."""

from contextlib import redirect_stdout
import json
from pathlib import Path
from struct import unpack
import sys

import torch
from transformers import AutoTokenizer, BertConfig, BertModel

# The Java worker uses isolated mode. Load the two bundled architecture modules.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from fluoresfm_unet import UNetModel


def load_text_encoder(weights):
    tokenizer_dir = weights / "tokenizer"
    config = BertConfig.from_pretrained(str(tokenizer_dir), local_files_only=True)
    tokenizer = AutoTokenizer.from_pretrained(str(tokenizer_dir), local_files_only=True)
    checkpoint = torch.load(weights / "open_clip_pytorch_model.bin",
                            map_location="cpu", weights_only=True, mmap=True)
    prefix = "text.transformer."
    state = {name[len(prefix):]: tensor for name, tensor in checkpoint.items() if name.startswith(prefix)}
    # Earlier Transformers releases serialized these buffers. Current BERT creates them from the config.
    state.pop("embeddings.position_ids", None)
    state.pop("embeddings.token_type_ids", None)
    encoder = BertModel(config, add_pooling_layer=False)
    encoder.load_state_dict(state, strict=True, assign=True)
    return tokenizer, encoder.eval()


def encode_prompts(weights, prompts, device):
    tokenizer, encoder = load_text_encoder(weights)
    encoder = encoder.to(device)
    embeddings = {}
    for prompt in dict.fromkeys(prompts):
        tokens = tokenizer(prompt, max_length=160, padding="max_length", truncation=True, return_tensors="pt")
        # FluoResFM uses BiomedCLIP's per-token hidden states, including CLS and padding positions.
        embeddings[prompt] = encoder(input_ids=tokens.input_ids.to(device),
                                     attention_mask=tokens.attention_mask.to(device)).last_hidden_state
    return [embeddings[prompt] for prompt in prompts]


def load_restoration_model(weights, device):
    checkpoint = torch.load(weights / "epoch_0_iter_700000.pt",
                            map_location="cpu", weights_only=True, mmap=True)
    state = {key.replace("_orig_mod.", ""): tensor for key, tensor in checkpoint["model_state_dict"].items()}
    with torch.device("meta"):
        model = UNetModel(in_channels=1, out_channels=1, channels=320, n_res_blocks=1,
                          attention_levels=[0, 1, 2, 3], channel_multipliers=[1, 2, 4, 4],
                          n_heads=8, tf_layers=1, d_cond=768, pixel_shuffle=False)
    model.load_state_dict(state, strict=True, assign=True)
    return model.eval().to(device)


def main():
    folder, tile_argument, prompt_file = sys.argv[1:]
    weights = Path(folder)
    tile = int(tile_argument)
    if not torch.cuda.is_available():
        raise RuntimeError("FluoResFM requires CUDA-enabled PyTorch and an NVIDIA GPU.")
    device = torch.device("cuda", 0)
    prompts = json.loads(Path(prompt_file).read_text(encoding="utf-8"))
    byte_count = tile * tile * 4
    source, target = sys.stdin.buffer, sys.stdout.buffer

    with redirect_stdout(sys.stderr), torch.inference_mode():
        embeddings = encode_prompts(weights, prompts, device)
        model = load_restoration_model(weights, device)
        target.write((torch.cuda.get_device_name(device) + "\n").encode("utf-8"))
        target.flush()
        while header := source.read(4):
            if len(header) != 4:
                raise EOFError("Incomplete FluoResFM channel header.")
            channel = unpack("<i", header)[0]
            if not 0 <= channel < len(embeddings):
                raise ValueError(f"Unknown image channel: {channel}")
            data = source.read(byte_count)
            if len(data) != byte_count:
                raise EOFError("Incomplete FluoResFM input tile.")
            tensor = torch.frombuffer(bytearray(data), dtype=torch.float32).reshape(1, 1, tile, tile)
            with torch.autocast("cuda", torch.float16):
                output = model(tensor.to(device), None, embeddings[channel])
            if output.shape != tensor.shape:
                raise RuntimeError(f"FluoResFM returned an unexpected tile shape: {output.shape}")
            target.write(output.float().cpu().contiguous().numpy().tobytes())
            target.flush()


if __name__ == "__main__":
    main()
