# On-Device Vision (Object Detection & Facial Identity)

keywords: vision, object detection, detect_objects, RT-DETR, YuNet, facial recognition, facial_scan, face identity, presence, who is here, enrollment, arcface, on-device, camera, privacy, security settings

AIOPE has two on-device computer-vision capabilities that run entirely on the phone via ONNX Runtime — no image ever leaves the device. Both require a model to be downloaded first (models are not bundled in the APK).

## Object detection (`detect_objects`)

Detects objects in an image from a file path or URL and returns labeled bounding boxes with confidence scores.

- **Tool:** `detect_objects(url)` — `url` is a `file://` path or an `http(s)` URL. Returns lines like `- person (0.94) at [x1,y1,x2,y2]`, or "No objects detected."
- **Model:** an ONNX object-detection model (**RT-DETRv4-S** export) downloaded by `ObjectDetectionBootstrap` to app-private storage (`rtdetr.onnx`, ~41 MB; size-floor validated). The download URL is build-configurable; if it's blank the tool reports "not configured." Labels come from the 80-class **COCO** label set (`CocoLabels`).
- **Engine:** `ObjectDetectionEngine` runs inference; results are decoded to boxes/labels/scores.
- **Live mode:** a `LiveObjectDetectionScreen` (in the `vision` package) runs detection on the camera preview in real time.
- **Requires:** the model downloaded (via settings); returns a clear message if it isn't configured or installed.

> Note: the tool's own description text says "YOLO"; the shipped bootstrap downloads an RT-DETRv4-S export. Functionally it's an on-device box detector over the COCO classes either way.

## Facial identity — "who's here" (`facial_scan`)

Identifies the person in front of the device against **enrolled** identities, fully on-device, so the agent can know who it's talking to.

- **Tool:** `facial_scan()` (no args) — silently captures a front-camera frame, matches it against enrolled faces, and returns `identified current user as "<name>"` or `unidentified`.
- **Models:** downloaded by `FaceModelBootstrap` to app-private storage:
  - **YuNet** face *detector* (`yunet.onnx`, ~227 KB) — decoded by `YuNetDecoder`.
  - **ArcFace** face *embedder* (`arcface.onnx`, ~130 MB) — produces the identity embedding.
- **Engine:** `FaceEngine` detects the primary face and embeds it; `FaceIdentityManager` compares the embedding to enrolled identities and returns a confident match or nothing.

### Enrollment

Faces are enrolled in **Settings → Security** (`FaceIdentitiesSection`). Enrollment captures a face, embeds it, and stores it under a label; multiple angle-samples per person are supported for better matching (`FaceEnrollmentStore`). Identities can be listed and deleted there. `facial_scan` returns a clear message if no faces are enrolled or the models aren't downloaded.

### Automatic presence ("who's here")

Beyond the explicit tool, AIOPE can identify the present user automatically:

- `PresenceIdentifier` runs the "who's here" scan when the app comes to the foreground, gated by cheap presence sensors (`PresenceSensorGate` — light + accelerometer + gyro) and debounced to at most once per interval, then silently captures a front-camera frame (`SilentFaceCapture`).
- The identified name is cached with a **5-minute TTL** and **injected into the system prompt** (via `FaceIdentityManager.currentInjectionLine` / `promptInjectionFor`), so the agent knows who it's addressing. A stale/missing identity triggers a fresh scan for the next turn.

### Privacy & scope

- All detection, embedding, and matching run **on-device**; no camera frame or face data is uploaded.
- Face embeddings are stored locally (in the app database) under user-chosen labels.
- Identification only works against faces the user explicitly enrolled; it is a personal "recognize the people I enrolled" feature, not general/third-party face search.
- Requires camera permission and the downloaded models.

## Related

- [Authentication](authentication.md) — the Security settings screen also hosts the auth factors and face enrollment.
- [RAG Knowledge Base](rag.md) — the same on-device ONNX + model-download pattern is used for embeddings.
