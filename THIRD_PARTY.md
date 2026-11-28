# Third-party notices

- **Noise2Noise-FMD** — © 2019 Varun Mannam, University of Notre Dame.
  GPL-3.0. The bundled ONNX model uses the original Noise2Noise NBN weights
  from [Instant-Image-Denoising](https://github.com/ND-HowardGroup/Instant-Image-Denoising/tree/c885aee8adb62bcd2d5c3862273d4ea0f68b3068).
  The corresponding PyTorch architecture was adapted by
  [CIDenoise](https://github.com/Cellular-Imaging-Amsterdam-UMC/cidenoise).
  This extension exports it as ONNX and TorchScript without retraining. See `tools/noise2noise.py`
  and `tools/export_model.py` for the source and checkpoint verification,
  and `licenses/Instant-Image-Denoising.txt` for the upstream license.
- The JavaFX workspace stylesheet and Windows caption icon integration are adapted from CI Deconvolve for QuPath,
  © 2024–2025 Cellular Imaging, Amsterdam UMC (MIT).
  See `licenses/CI-Deconvolve.txt`.
- **FluoResFM** inference architecture adapted from
  [napari-fluoresfm, commit 2fded7c](https://github.com/qiqi-lu/napari-fluoresfm/tree/2fded7c31be8c52476de89934ced9093ebe2c307)
  (Qiqi Lu, MIT). The architecture builds on the [LabML implementation](https://github.com/labmlai/annotated_deep_learning_paper_implementations)
  (MIT). See `licenses/FluoResFM.txt` and `licenses/LabML.txt`.
  The user-installed checkpoint comes from the [FluoResFM authors](https://github.com/qiqi-lu/fluoresfm).
  Text conditioning uses Microsoft's [BiomedCLIP](https://huggingface.co/microsoft/BiomedCLIP-PubMedBERT_256-vit_base_patch16_224)
  weights and BiomedBERT tokenizer fetched separately.
