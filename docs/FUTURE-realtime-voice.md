# Real-Time Voice Conversations

## Summary
Bidirectional real-time voice using Google's `bidiGenerateContent` WebSocket API.
Free tier: unlimited RPM/RPD, 1M TPM — supports hundreds of concurrent sessions on a single key.

## Models
- `gemini-2.5-flash-native-audio` — 131K context, unlimited RPM, 1M TPM
- `gemini-3-flash-live` — 1M context, unlimited RPM, 65K TPM

## Gateway (simple WS proxy)
- Accept WSS from AIOPE client
- Open WSS to `generativelanguage.googleapis.com` bidiGenerateContent
- Inject API key server-side, relay frames both directions
- Close both on disconnect
- No key pooling needed given free tier limits
- ~50-100 lines Go or Node, or nginx WS proxy config

## Client (Android)
- `AudioRecord` → stream PCM chunks over WebSocket
- Receive audio chunks → `AudioTrack` playback
- VAD (voice activity detection) for turn-taking
- UI: push-to-talk or always-listening mode, visual waveform feedback
- Wire into chat so voice messages appear as text transcript
- Handle interruptions (user speaks while AI is responding)

## Effort
- Gateway WS proxy: ~half day
- Android audio pipeline + UI: 2-3 days
- Testing/polish: 1 day

## Notes
- Free tier has no concurrent session cap
- 1M TPM ≈ ~660 concurrent sessions at real-time audio rates
- Audio is ~32kbps per direction, bandwidth is minimal
- Context window means full conversation memory during voice session
