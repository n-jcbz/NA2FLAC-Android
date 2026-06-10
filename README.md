# NA2FLAC — Android Port

**Nintendo Audio to FLAC Converter**

NA2FLAC is an audio conversion tool that scans and converts Nintendo audio formats into high-quality FLAC files.  

---

## Builds

- **Android:**
  - **Standard** — uses up to 4 threads*
  - **Turbo** — uses up to 8 threads*
 
- **C#:**
  - **WPF build** — modern .NET 8 WPF UI with folder selection, output folder option, progress bar, and status text. Designed for easy, visual conversion and to work from any selected input/output folder.
  - **Legacy build** — original batch script simply ported to C# as a console application, matching the original v1.2.1 behaviour for users who prefer the simple console workflow.

##### Check the main repository of [NA2FLAC](https://github.com/n-jcbz/NA2FLAC) for the other builds.

###### *Threads vary by device. Using many threads will result in high temperatures, increased battery consumption and decreased system performance. Check your CPU specifications before installing. Use only the Standard build on low-end devices.

---

## Features

- **Supported formats**
  - `AST`, `BRSTM`, `BCSTM`, `BFSTM`, `BFWAV`, `BWAV`, `SWAV`, `STRM`, `LOPUS`, `IDSP`, `HPS`, `DSP`, `ADX`, `MP3`, and `OGG`

- **Intelligent channel handling**
  - Files split into `_l` (left) and `_r` (right) channels are automatically detected and merged into a single stereo track where appropriate.
  - Files with up to 8 channels are converted into FLAC. Files with more than 8 channels are kept as WAV.¹

- **Organized output with preserved folder tree**
  - Converted files are placed into a mirrored `converted` folder that preserves the original folder structure.
  - By default the app lets you pick a separate output directory; if left empty it defaults to the input folder (it still creates `converted` there).

- **File-size estimation**
  - Scans report `original -> ~estimated after conversion` using per-format heuristics to give a reasonable range for final FLAC size.

- **Per-file progress**
  - Displays a `(N/Total) Processing ...` counter while converting.
  - Progress is also shown via a notification.

- **Automatic cleanup**
  - WAV intermediate files are deleted automatically when a FLAC is created successfully.
  - WAVs are kept if conversion fails or if the file has too many channels.
 
- **Conversion logging**
  - A log file is created and moved into the `converted` folder after conversion
 
- **Background conversion**
  - NA2FLAC will also run in the background whenever you're doing something else while converting files.²

###### ¹ Not all music players support files with a high channel-count.
###### ² Running in the background will result in a slower conversion.

---

## How It Works

1. Install the app
2. Use *Select Input* to choose your input folder (the folder with your audio files or any folder above it), and optionally set an output folder
4. Tap **Scan** — app will scan supported audio formats and show counts and a size estimate
5. Tap **Convert** — progress bar and status text update while the app converts files (vgmstream → WAV → ffmpeg → FLAC)
6. Converted files appear in the `converted` folder inside the selected output (or input) directory, preserving folder structure

---

## Requirements

- ARM64 Android 8.0 or later  
- No separate install needed for ffmpeg/ffprobe/vgmstream — they are bundled in the app itself.

---

## Licenses

- **NA2FLAC** — MIT License (see `LICENSE.txt`)  
- **FFmpeg** — GNU GPL v3 (see `licenses/FFMPEG_COPYING.GPLv3.md` and `licenses/FFMPEG_LICENSE.md`)  
  Visit [https://ffmpeg.org](https://ffmpeg.org)  
- **VGMStream** — MIT License (see `licenses/VGMSTREAM_COPYING.md`)  
  Visit [https://vgmstream.org](https://vgmstream.org/)

---

## Credits

- FFmpeg by the FFmpeg developers  
- VGMStream by the VGMStream team
- [Prebuilt Android FFmpeg binary](https://github.com/Khang-NT/ffmpeg-binary-android) by [Khang-NT](https://https://github.com/Khang-NT)
- [Prebuilt Android FFprobe binary](https://github.com/hzw1199/Android-FFmpeg-Prebuilt) (file found [here](https://github.com/hzw1199/Android-FFmpeg-Prebuilt/tree/main/ffmpeg-8.1.1/bin)) by [hzw1199](https://github.com/hzw1199)
- [NA2FLAC](https://github.com/n-jcbz/NA2FLAC) by n.jcbz

---

## Notes & Caveats

- The ARM64 vgmstream-cli binary file was made through a workflow here in this repository.
- Size estimations are heuristic — formats can be variable and may require tuning of multipliers for better accuracy.

---

## Contact / Contribute

If you want to help tune estimations, add new formats or features, open an issue or a PR in the repository. Pull requests that add format support should include a test file and expected behavior.
