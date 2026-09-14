# Offline Speech-to-Text

keywords: speech to text, stt, dictation, voice input, offline, on-device, sherpa-onnx, zipformer, transducer, VoiceInputManager, SherpaSttEngine, RecognitionService, mic, transcription, privacy

AIOPE transcribes speech **fully on-device** using [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — no audio leaves the phone and it works with no network. This is separate from **Realtime Voice** (a live bidirectional conversation via the Gemini Live API); STT here is dictation / voice *input* that turns what you say into text.

## What it is

- **Engine:** `SherpaSttEngine` wraps a sherpa-onnx **streaming zipformer transducer** (`OnlineRecognizer`). It consumes 16 kHz mono PCM and produces **interim (partial)** text as you speak plus a **final** transcript at the end of an utterance, with on-device endpoint detection.
- **Model:** an int8 English streaming zipformer (encoder + decoder + joiner ONNX files + `tokens.txt`), downloaded and unpacked by `SherpaSttBootstrap` to app-private storage (`filesDir/models/sherpa-stt`, archive ~52 MB, from XNet-NGO/deps; Apache-2.0 k2-fsa asr-models). Not bundled in the APK.
- **Runtime:** runs on the app's unified self-built ONNX Runtime.

## Two ways it's used

**1. In-app voice input (mic button).** `VoiceInputManager` captures the microphone (`AudioRecord`, 16 kHz mono, small ~100 ms read chunks for low latency to the first partial) and streams `onPartial` interim text and a final `onFinal` result to the chat input. Requires microphone permission and the model downloaded (`isModelInstalled` / `isAvailable`).

**2. System-wide recognizer.** `AiopeRecognitionService` is a real Android `RecognitionService` backed by the same engine. It implements the platform `SpeechRecognizer` contract (interim `partialResults`, final `results`), so — once selected as the device's speech-recognition service — **any app** can use AIOPE's offline recognizer for dictation, not just AIOPE itself.

## Privacy

All audio capture and recognition happen on-device; nothing is uploaded. This makes dictation usable offline and private by default. Contrast with **Realtime Voice**, which streams audio to the Gemini Live API for a full spoken conversation with tool use.

## Requirements

- Microphone permission.
- The offline STT model downloaded (via settings). Until then, STT input reports it isn't installed.

## Related

- [Realtime Voice](voice.md) — live bidirectional spoken conversation (cloud Gemini Live), distinct from offline dictation.
- [Conversations](conversations.md) — where transcribed text lands as chat input.
