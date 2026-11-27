# CI Denoise for QuPath

Denoise fluorescence microscopy images in QuPath 0.7.0 with the fast
**Noise2Noise-FMD** model from [CIDenoise](https://github.com/Cellular-Imaging-Amsterdam-UMC/cidenoise).
The extension includes the model and runs offline. It supports the OpenCV CPU
runtime supplied with QuPath and NVIDIA CUDA through a CUDA-enabled PyTorch
environment.

## Installation

CI Denoise is included with QuPath 7 Standalone Full for Windows. For a separate
QuPath 0.7.0 installation, download the [release JAR](https://github.com/Cellular-Imaging-Amsterdam-UMC/qupath-ci-denoise/releases/latest),
add it through QuPath's extension manager or copy it into your QuPath extensions
folder, then restart QuPath. Keep one version of the JAR in the extensions folder.
Run **Extensions → CI Denoise → Validate Noise2Noise model** to check inference.

## Run denoising

Open a fluorescence image and choose **Extensions → CI Denoise → Run denoising…**.

- **Image region** offers the full image, selected area annotations, or all area
  annotations with a chosen class. Each annotation is exported as a rectangular
  crop around its XY bounds, with the full Z and T extent.
- **Channels** controls which channels are denoised. Unselected channels use the
  original intensities.
- **Output** offers pyramidal OME-Zarr and OME-TIFF with float32 intensities,
  channel metadata and spatial calibration. The default folder is `cidenoise`
  in the active project or next to the source image. You can open the first
  result in the viewer and add the exported images to the project.
- **Create max-Z projection** takes the maximum across the denoised slices,
  separately for each channel and time point. Unselected channels are
  projected from their original intensities. Turn this option off to keep
  all Z slices. Time frames are retained in both formats. Choose OME-Zarr if
  you need the time calibration exported by QuPath 0.7.0.
- **Advanced settings** sets tile size and overlap. Defaults are 512 and 128
  pixels, as in CIDenoise. Normalization uses the maximum intensity per
  channel, Z slice and time point in the chosen region. Overlapping tile
  predictions are blended.
- **Device** defaults to **Auto**. Standalone Full uses its bundled PyTorch
  environment for CUDA when available, with a CPU fallback. **NVIDIA CUDA**
  requires successful GPU initialization. On other QuPath installations,
  set **Python executable** to a Python environment with CUDA-enabled PyTorch.
  The progress window identifies the device used for inference.

Results open with an assigned QuPath image type, and project entries use the
exported filename. The source and earlier exports stay intact. The progress dialog supports
cancellation, and completed exports from the batch remain on disk.

## Groovy scripting

Use **Extensions → CI Denoise → Open example Groovy script**, or run:

```groovy
import qupath.ext.cidenoise.CIDenoise
import java.nio.file.Path

def source = getCurrentServer()
def region = CIDenoise.Region.fullImage(source)
// For a selected annotation: CIDenoise.Region.annotation(source, getSelectedROI(), 'ROI')
def options = new CIDenoise.Options(CIDenoise.Format.OME_ZARR, true, 512, 128, [],
        CIDenoise.Device.AUTO, null)
def base = PROJECT_BASE_DIR ?: System.getProperty('user.home')
println CIDenoise.run(source, region, Path.of(base, 'cidenoise'), options)
```

An empty channel list selects all channels. A list such as `[0, 2]` selects the
first and third channels. The boolean option enables max-Z projection.
Pass a Python executable as the last argument to use a separate CUDA environment.

## Build from source

With JDK 25:

```powershell
.\gradlew.bat clean jar
```

On macOS or Linux, use `./gradlew clean jar`. The distributable is in `build/libs`.
Use `-PqupathAppDir=<path-to-QuPath-app-jars>` to compile directly against the
JARs from a local QuPath installation. The checked-in ONNX and TorchScript
models are ready to build. To regenerate them, install PyTorch, NumPy and ONNX, then run
`python tools/export_model.py`. The script downloads and verifies the original
checkpoint. Use `--checkpoint <path>` to use a local copy instead.

See [third-party notices](THIRD_PARTY.md) for model attribution and licensing.
