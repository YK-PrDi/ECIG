# Grok Video Modes And Fixed Output Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split Grok text-to-video and image-to-video into independent choices and normalize every completed video to the selected fixed MP4 dimensions.

**Architecture:** Extend the video catalog with distinct Grok provider groups and input modes, enforce the contract in both controller and provider service, and add a focused FFmpeg normalizer invoked after provider generation. Keep the existing OkHttp integration because the proxy video contract is not fully represented by the OpenAI Java SDK.

**Tech Stack:** Java 17, Spring Boot 3.3, OkHttp, JUnit 5, Mockito, browser-side JavaScript, FFmpeg/FFprobe.

---

### Task 1: Lock The Grok Model Contract

**Files:**
- Modify: `src/test/java/com/elebusiness/service/video/VideoModelCatalogTest.java`
- Modify: `src/test/java/com/elebusiness/service/video/OpenAiCompatibleVideoServiceTest.java`
- Modify: `src/test/java/com/elebusiness/controller/VideoControllerProviderTest.java`

- [ ] Add failing assertions for distinct Grok provider IDs and input modes.
- [ ] Add failing request tests proving text-to-video omits images and image-to-video requires `image_url`.
- [ ] Add failing controller tests proving illegal image counts are rejected before billing.
- [ ] Run the target tests and confirm they fail for the missing behavior.

### Task 2: Implement Grok Mode Separation

**Files:**
- Modify: `src/main/java/com/elebusiness/service/video/VideoModelCatalog.java`
- Modify: `src/main/java/com/elebusiness/service/video/OpenAiCompatibleVideoService.java`
- Modify: `src/main/java/com/elebusiness/controller/VideoController.java`

- [ ] Add `TEXT_ONLY`, `IMAGE_ONLY`, and flexible input metadata to model views.
- [ ] Split Grok provider IDs and labels while retaining the shared Grok credential.
- [ ] Validate image counts before task creation and billing.
- [ ] Emit `image_url` only for `grok-imagine-video-1.5`.
- [ ] Run the target tests and confirm they pass.

### Task 3: Add Fixed Video Output Normalization

**Files:**
- Create: `src/main/java/com/elebusiness/service/video/VideoOutputNormalizer.java`
- Create: `src/test/java/com/elebusiness/service/video/VideoOutputNormalizerTest.java`
- Modify: `src/main/java/com/elebusiness/controller/VideoController.java`

- [ ] Write failing tests for aspect-ratio dimensions, remux selection, crop command construction, cleanup, and command failure.
- [ ] Implement FFprobe inspection and FFmpeg normalization with cancellation-aware child processes.
- [ ] Invoke normalization after every provider returns and before history is recorded.
- [ ] Run the normalizer and controller tests and confirm they pass.

### Task 4: Update The Video Model UI

**Files:**
- Modify: `frontend/index.html`
- Modify: `tools/check-video-model-rail.js`

- [ ] Add failing static/browser assertions for two Grok dropdown entries.
- [ ] Update fallback metadata and provider inference.
- [ ] Hide the model rail for one-model groups and retain it for 即梦.
- [ ] Add client-side Grok image-count validation.
- [ ] Run the frontend video check and confirm it passes.

### Task 5: Verify, Build, And Deploy

**Files:**
- Modify only if required by verification findings.

- [ ] Run all mandatory frontend checks from `docs/运行与发布验收门禁.md`.
- [ ] Run the complete Maven test suite and confirm zero failures and errors.
- [ ] Build the JAR and record local SHA-256 hashes.
- [ ] Back up the production JAR and changed frontend files.
- [ ] Upload to temporary paths, compare hashes, atomically replace, and restart `ai-studio`.
- [ ] Verify service state, HTTP, authentication smoke tests, browser flows, logs, and deployed hashes.

### Task 6: Run One Controlled Provider Validation

**Files:**
- No source changes unless the controlled validation exposes a reproducible defect.

- [ ] Submit exactly one `grok-imagine-video-1.5` request with one reference image, `3:4`, and `4s`.
- [ ] Poll the same task to a terminal state without creating a retry task.
- [ ] Download the result and verify MP4, H.264, `yuv420p`, and `720x960` with FFprobe.
- [ ] Save the validated video to the desktop and record the task ID and file hash.
