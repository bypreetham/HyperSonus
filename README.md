# HyperSonus: Hi-Res Bit-Perfect Audio Player

HyperSonus is a state-of-the-art Android music player designed for audiophiles who demand the highest possible audio fidelity. It bypasses conventional Android audio limitations through a custom-built native engine, offering true Bit-Perfect playback, advanced DAC integration, and a high-performance DSP pipeline.

![HyperSonus Architecture](/hyper-architecture.png)

## Asynchronous Streaming Engine

HyperSonus achieves glitch-free, ultra-low-latency playback using a sophisticated **Asynchronous Multi-Threaded Architecture**:

1.  **JNI Bridge (native-lib.cpp)**: Acts as the high-speed gateway between the Kotlin `NativeHiResEngine` and the C++ core. It handles **FD (File Descriptor) mapping** via `/proc/self/fd/` and orchestrates real-time callbacks.
2.  **HyperDecoder Thread**: A dedicated native thread running with **SCHED_FIFO Real-Time Priority** and **CPU core affinity** (pinned to Big cores) to eliminate context-switching jitter during heavy system load.
3.  **Shared 3MB Jitter-Free Ring Buffer**: A high-capacity asynchronous bridge that decouples production from consumption. It provides a 2-second safety margin at 192kHz, ensuring glitch-free playback even during system-level interrupts.
4.  **Path-Specific Optimization**:
    *   **Bluetooth/Shared Path**: Pulls from the ring buffer into an **Oboe (AAudio)** stream, routing through the Android AudioFlinger mixer.
    *   **Exclusive USB Path**: Communicates directly with USB hardware. Bypasses the Android ALSA mixer entirely for true bit-perfect transmission.
    *   **UAC2 Async Feedback Loop**: Actively monitors the USB DAC's hardware clock; uses an **Exponential Moving Average (EMA)** drift correction algorithm to adjust packet chunk sizes in real-time, matching the phone's output to the DAC's specific oscillator.

---

## Core Philosophy: Bit-Perfect Audio & USB Exclusive Mode

Standard Android playback often resamples audio to 48kHz, degrading high-resolution source material. Hyperplay v2 introduces two high-fidelity paths:
- **Bit-Perfect (Oboe/AAudio)**: Requests **Exclusive Mode** to bypass the system mixer while using standard Android audio drivers.
- **USB Exclusive Mode (Bulk Engine)**: A specialized driver-less path that communicates directly with USB DACs via `bulkTransfer` / ISO endpoints, bypassing the Android audio stack entirely for ultra-low jitter.

---

## Architecture & Engineering

### 1. Multi-Engine Architecture
Hyperplay provides a flexible abstraction layer via the `IAudioEngine` interface, allowing the app to switch between three distinct backends:
- **Standard Engine (MediaPlayer)**: Optimized for low battery consumption and standard 16-bit/44.1kHz playback.
- **Native Hi-Res Engine (Oboe + FFmpeg)**: A C++ powerhouse that handles 24-bit/32-bit audio decoding and low-latency output.
- **USB Exclusive Engine (Direct Bulk/ISO)**: Direct-to-hardware streaming engine for mission-critical audiophile listening.

### 2. Intelligent Device Discovery & Quirk Management
- **USB DAC Probing**: Automatically detects connected USB DACs and probes their hardware-supported sample rates (up to 384kHz and beyond).
- **Device Quirk Manager**: A built-in database of hardware-specific fixes for popular DACs (SMSL, Topping, FiiO, iFi, etc.) to ensure stable playback across various USB controller implementations.
- **Advanced Bluetooth Detection**: Uses Hidden API reflection to identify high-quality codecs (LDAC, aptX HD, aptX Adaptive) and display real-time technical info (96kHz / 24-bit).

### 3. Native Processing Layer
- **Oboe**: Google’s high-performance C++ library for low-latency audio.
- **FFmpeg**: Powers the decoding of various formats (FLAC, ALAC, WAV, DSD) with high-precision resampling.
- **SoX Resampler (libsoxr)**: High-quality resampling with configurable profiles (Standard, Extreme/VHQ, Auto).
- **JNI Bridge**: A robust connectivity layer (`native-lib.cpp`) that enables real-time synchronization between the Kotlin service and the C++ engine.

---

## Advanced Features

### Audiophile DSP Pipeline
- **64-bit Double Precision Pipeline**: All DSP operations are performed in `double` (64-bit) precision to prevent rounding errors and preserve signal integrity before the final dithering stage.
- **Bauer Stereophonic-to-Binaural (BS2B)**: High-quality cross-feed for headphones to reduce listening fatigue and simulate a speaker-like soundstage.
- **Google Resonance 3D Audio**: High-fidelity spatial audio SDK integrated directly into the native DSP chain.
- **10-Band Native Equalizer**: High-precision EQ (31Hz to 16kHz) available in the Native Hi-Res engine.
- **Quantization & Dither**: TPDF Dither with noise-shaping for transparent bit-depth reduction.
- **Safety Limiter**: Hard-clipping protection for the floating-point audio pipeline.
- **Pre-Amp Boost**: Adjustable gain control to match different headphone sensitivities.

### Real-Time Technical Insights
- **Interactive Audio Pathway**: A visual map showing the journey of your audio from Source -> Decoder -> Resampler -> DSP -> Limiter -> Dither -> Output.
- **Audiophile Technical Info**: Real-time display of engine type, output sample rate, channel count, and bit-perfect status.

### Library & Playback
- **Gapless Playback**: Seamlessly transitions between tracks without silence.
- **Folder-Based Navigation**: Robust folder picker and dedicated folder view for organized library browsing.
- **Smart Song Caching**: Efficient library indexing that avoids redundant scans.
- **Pause on Disconnect**: Automatically pauses playback when headphones or DACs are unplugged.
- **Background Stability**: Built-in prompts to bypass Android battery optimizations for uninterrupted listening sessions.

---

## Project Structure

```text
app/src/main/
├── java/com/example/first/
│   ├── engine/          # Audio engine abstractions (MediaPlayer, Native, USB Bulk)
│   ├── usb/             # USB Exclusive mode, Volume Control, & Quirk Manager
│   ├── MainActivity.kt  # UI orchestration & library scanning
│   └── MusicService.kt  # Central playback & MediaSession hub
├── cpp/
│   ├── engine/          # C++ AudioEngine, DSP Pipeline, & USB Native Streamer
│   ├── include/         # FFmpeg & SoX headers
│   └── external/oboe/   # Oboe source code
└── jniLibs/             # Pre-built FFmpeg/SoX binaries (.so) for arm64-v8a
```

---

## Setup & Build Requirements

- **Android SDK**: 36 (Min SDK 26)
- **NDK**: 25.x or later
- **CMake**: 3.22.1+
- **FFmpeg**: Requires pre-built libraries placed in `app/src/main/jniLibs`.
- **Oboe**: Included as a Git submodule/folder in `external/`.

## 📜 Credits & Third-Party Libraries

HyperSonus is built upon the incredible work of the open-source community. We gratefully acknowledge the following libraries:

- **[Oboe](https://github.com/google/oboe)**: Google's high-performance C++ library for low-latency audio on Android.
- **[FFmpeg](https://ffmpeg.org/)**: The leading multimedia framework, used for high-precision decoding of FLAC, WAV, ALAC, and DSD.
- **[libsoxr](https://sourceforge.net/projects/soxr/)**: The SoX Resampler library, providing high-quality sample rate conversion.
- **[libFLAC](https://xiph.org/flac/)**: The reference implementation for the Free Lossless Audio Codec.
- **[bs2b](https://bs2b.sourceforge.net/)**: The Bauer Stereophonic-to-Binaural library for fatigue-free headphone listening.
- **Resonance Audio**: Google's high-fidelity spatial audio SDK.
- **[Shibata](https://github.com/shibatch/SSRC)**: High-order noise-shaping dithering algorithm used for high-fidelity bit-depth conversion.

## License & Proprietary Status

**Proprietary License**: All rights reserved.

The following core components of HyperSonus are strictly proprietary and protected under this license:
- **Native Header Files**: All C++ `.h` files defining the engine's internal structure.
- **JNI Layer**: The `native-lib.cpp` bridge and related JNI mappings.
- **USB Quirk Manager**: Specialized hardware-specific fixes for USB DACs.
- **Architecture Plans**: All files within the `plan/` directory.


