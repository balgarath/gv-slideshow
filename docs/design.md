# GV-Slideshow: design doc

Android app that turns photos into a Ken Burns slideshow: pick photos, get a random pan/zoom per photo, tweak each one, export an MP4. Sideloaded, no store listing. The app uses no network: the face model is bundled and the INTERNET and ACCESS_NETWORK_STATE permissions are stripped from the merged manifest.

Verified against Media3 **1.11.1** (stable, 2026-09-10). Key verified facts that shape this design:

- Transformer takes still images as `EditedMediaItem` with `setDurationUs` + `setFrameRate`; items in one `EditedMediaItemSequence` play back to back.
- **Crossfading between items/sequences is explicitly listed as unsupported** in Composition docs ("Crossfading video or audio tracks" under current limitations). Overlapping sequences exist conceptually but the compositor APIs for per-sequence alpha/offset are thin and underdocumented.
- `CompositionPlayer` is `@ExperimentalApi`, single-sequence preview only, API subject to change.
- Custom `GlEffect` / `BaseGlShaderProgram` is a first-class supported extension point; `drawFrame(inputTexId, presentationTimeUs)` gives us per-frame control.

## 1. Headline decisions

| Decision | Choice | Why (rejected alternative) |
|---|---|---|
| Transitions | One custom `GlEffect` per item does Ken Burns **and** transitions in-shader; the next photo is uploaded as a second GL texture and blended during the tail of the current item | Overlapping-sequence crossfade is unsupported by Media3 (verified); single sequence + custom shader stays entirely on supported API surface. We control everything because sources are stills we decoded ourselves. |
| Ken Burns transform | Done in our shader via UV-rect sampling, not `MatrixTransformation` | One shader owns crop + blend; mixing MatrixTransformation with an overlay effect splits the math across two stages and makes crossfade geometry wrong. |
| Live preview | Compose `Canvas` `drawImage` with a source rect from the same pure-Kotlin math used by the shader | CompositionPlayer is experimental and would still need the shared math; Canvas preview is exact (same rect function), instant, zero GL on the UI thread. Rejected: ExoPlayer/CompositionPlayer preview. |
| Photo persistence | Copy into app storage at import, downscaled (long edge 2560 px, JPEG q90), EXIF rotation baked in | Persistable URI grants are capped (128 pre-11 / 512 after), break when Photos moves/deletes/offloads to cloud, and leave EXIF + oversized decodes to handle at render time. Cost: ~1–2 MB/photo, acceptable. Original URI kept in the model for a future "reimport full-res". |
| Transition scope | Per-project setting in the UI; an optional per-boundary override (`transitionOverride`) is set from chips in the photo editor | Consistent transitions look better and need one tap; the per-boundary override covers the exceptions. |
| Project storage | JSON (kotlinx.serialization) file per project, `schemaVersion` field | Room is overkill for tens of projects; JSON is JVM-unit-testable and diffable. |
| minSdk / targetSdk | minSdk 33, targetSdk 36, compileSdk 36 | Android 13+ is the target; minSdk 33 gives system photo picker natively and no storage permissions at all (picker + MediaStore pending-insert for output). |
| Audio later | Single video `EditedMediaItemSequence` now; adding one looping audio sequence is the one multi-sequence case CompositionPlayer/Transformer document as supported | No rework needed: `Composition(videoSeq, audioSeq)`. |

## 2. Architecture and layout

Single Gradle module `:app` plus a pure-JVM module `:core` (no Android deps) so all logic runs under plain JUnit on any machine, with no emulator or device.

```
kenburns/
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/wrapper/
  keystore.properties            (gitignored, optional)
  core/                          pure Kotlin JVM module, all testable logic
    src/main/kotlin/com/geovisual/kenburns/core/
      model/      Model.kt (Project, PhotoItem, FramingRect, FaceBox, Easing, TransitionType, AspectRatio)
      math/       KenBurnsMath, Easings, FramingRandomizer, FaceFraming
      timeline/   Timeline (item start times, per-frame TransitionState), DropPhotos
      render/     ShaderUniforms (uniform values, texture flip, ItemClock)
      serde/      ProjectJson (versioned), ProjectFiles (atomic file IO)
      imports/    ImportLogic (resize math, naming, aspect conversion)
      editor/     FramingGesture (hit-testing, drag/resize)
      export/     ExportEstimate (bitrate table, size estimate)
    src/test/kotlin/...          JUnit tests for everything above
  app/                           thin Android layer
    src/main/kotlin/com/geovisual/kenburns/
      MainActivity.kt
      ui/         App, AppModel, TimelineScreen, EditorScreen, PreviewScreen,
                  ProjectListScreen, ExportStarter, Theme, Common, components/GlowButton
      io/         PhotoImporter (decode, downscale, face detection), ProjectStore, ErrorLog
      render/     CompositionBuilder, KenBurnsShaderProgram (GlEffect)
      export/     Exporter, ExportService (foreground), ExportNotifications, MediaStorePublisher
  tools/mp4info.py               prints dimensions, fps and bitrate of an exported MP4
```

Rule: if a class can compile without `android.*` imports, it goes in `:core`.

## 3. Data model and on-disk format

Normalized coordinates throughout: a `FramingRect` is the **source crop rect in image space**, each component in `[0,1]` relative to image width/height. Its aspect (in pixels) always equals the project output aspect; invariant enforced by constructors in `:core`.

```kotlin
// core/model — all @Serializable
enum class AspectRatio(val w: Int, val h: Int) { V9x16(1080,1920), H16x9(1920,1080), S1x1(1080,1080) }
enum class Easing { LINEAR, EASE_IN_OUT, EASE_IN, EASE_OUT }
enum class TransitionType { CUT, CROSSFADE, DIP_TO_BLACK, SLIDE }   // SLIDE is last so older JSON still loads

data class FramingRect(val cx: Double, val cy: Double, val w: Double, val h: Double)
// cx,cy = center; w,h = size, all normalized to image dims. w/h in pixels == output aspect.

data class FaceBox(val left: Double, val top: Double, val w: Double, val h: Double)
// normalized rect in stored-image space (all in [0,1])

data class PhotoItem(
  val id: String,                  // UUID
  val fileName: String,            // relative to project photos/ dir
  val sourceUri: String?,          // original content URI, informational only
  val imageWidth: Int, val imageHeight: Int,   // post-rotation pixel size of stored copy
  val startRect: FramingRect, val endRect: FramingRect,
  val durationMs: Long,            // hold duration, randomized 4000..6000 at import
  val easing: Easing = Easing.EASE_IN_OUT,
  val transitionOverride: TransitionType? = null,   // boundary AFTER this photo; null = project default
  val faces: List<FaceBox>? = null,   // null = detection not run/failed; empty = ran, none found
)

data class Project(
  val schemaVersion: Int = 2,
  val id: String, val name: String,
  val createdAt: Long, val modifiedAt: Long,
  val aspect: AspectRatio,
  val transition: TransitionType = TransitionType.CROSSFADE,
  val transitionDurationMs: Long = 600,
  val photos: List<PhotoItem>,     // list order = playback order
)
```

On disk (app `filesDir`, no permissions needed):

```
files/projects/<projectId>/project.json
files/projects/<projectId>/photos/<photoId>.jpg
```

`project.json.bak` is a one-deep copy of the previous `project.json`, made before each overwrite; photo recovery reads it. Photos that no saved project lists move to `files/projects/<projectId>/.trash/` as `<ms>_<name>` instead of being deleted, and are purged after 7 days.

Loading: read `schemaVersion` first; unknown higher version → refuse with message; migrations are version-to-version functions in `serde/`. Writes are atomic (write `.tmp`, rename). Deleting a project deletes the directory.

## 4. Ken Burns math (single source of truth, `:core/math`)

**Base crop.** For image (W,H) and output aspect A = outW/outH: the largest in-bounds crop with aspect A is `baseW = min(W, H*A)`, `baseH = baseW/A` (normalized: divide by W,H). Zoom `z >= 1` means crop size = base size / z. `z = 1` shows the most of the image possible without letterboxing; aspect mismatch is therefore handled by always cropping, never by bars.

**Interpolation.** Given startRect S, endRect E, eased progress `p = ease(t / dur)`:

- Zoom in **log space**: `z(p) = exp(lerp(ln zS, ln zE, p))` where `zS = baseW / S.w` etc. Linear size interpolation makes motion look like it accelerates when zooming in; log-space keeps perceived zoom rate constant.
- Center linearly between the centers, then **clamp** the resulting rect inside `[0,1]^2` (clamp center so `cx ± w/2` and `cy ± h/2` stay in bounds). With valid endpoints and log-zoom the path stays in bounds anyway; the clamp is a safety net against float drift.
- Output of `KenBurnsMath.rectAt(photo, tMs): FramingRect` is consumed identically by (a) the Compose preview (`drawImage` srcRect = rect * image pixel size) and (b) the shader (UV rect: `uvOffset = rect.topLeft`, `uvScale = rect.size`).

**Easing functions** (pure): LINEAR `p`; EASE_IN_OUT smootherstep `6p^5-15p^4+10p^3`; EASE_IN `p^3`; EASE_OUT `1-(1-p)^3`.

**Randomizer** (`FramingRandomizer(seed)`, deterministic for tests):

- Zoom range: `z ∈ [1.05, 1.6]`. Draw zStart, zEnd independently, then reject-and-redraw until `|ln zE - ln zS| >= ln 1.12` **or** pan distance ≥ 0.10 (so there is always visible motion).
- Direction mix: 50% zoom-in (zE > zS), 50% zoom-out; implement by drawing both then swapping to match a coin flip.
- Centers: uniform within the valid center range for each rect's size (so rects are in bounds by construction). Redraw up to 8 times if pan distance `hypot(dcx, dcy)` < 0.06 when zoom change is also small; after 8 tries, accept (degenerate skinny panoramas can't always satisfy both).
- Duration: uniform integer 4000..6000 ms.
- Re-randomize button = call again with a fresh seed for that photo only.

**Face-aware framing** (`FaceFraming` + `FramingRandomizer.next(..., faces)`). `faces` null or empty = everything above, unchanged. Otherwise:

- Target = union of all faces, grown by 50% of the union size on each side plus 25% extra above (boxes sit on the forehead). If the smallest valid crop containing that target is larger than the full base crop (faces too spread out), the target is the largest single face grown the same way.
- Tight rect = smallest valid crop containing the target, at most the max zoom (1.6), centered on the target and clamped in bounds.
- Style per photo, equal odds: PUSH_IN (wide to tight), PULL_OUT (tight to wide), KEEP_IN_FRAME (start and end both random per the rules above but constrained to contain the target, so the pan direction is random).
- Wide rects (push/pull) are random in zoom 1.05 up to tightZoom/1.121 and also contain the target.
- Visible motion: tight zoom must be >= 1.121, so the zoom step alone always meets the 1.12 rule. If the margin target allows less, the margin shrinks to 10% (headroom 12.5%); if even that allows less (faces fill the frame), the photo is framed as if it had no faces.
- Reframing on an aspect change uses the stored faces.

## 5. Rendering pipeline (Media3 Transformer)

**Composition.** One `EditedMediaItemSequence` of image items, in project order. Per item: `MediaItem` from the project-local file, `setDurationUs(durationMs * 1000)`, `setFrameRate(60)`, effects = `[KenBurnsTransitionEffect(itemSpec)]`. Output: H.264 + MP4, 1080p at project aspect, hardware encoder (Transformer default). Final sizing: the shader program's `configure()` returns the output size (e.g. 1080x1920), so no separate `Presentation` effect is needed.

**Timeline math** (in `:core/timeline`). Transition duration `c` (0 for CUT). Item i occupies `[start_i, start_i + d_i)`. Photo i+1 becomes visible at `start_{i+1} - c` (fading in over photo i's tail), so photo i+1's **motion clock** starts then: its motion duration is `d_{i+1} + c` and inside item i+1 local time `t` maps to motion time `t + c`. Inside item i's tail (`t > d_i - c`), the shader also evaluates photo i+1 at motion time `t - (d_i - c)` and blends with `alpha = (t - (d_i - c)) / c`. `Timeline.frameState(itemIndex, tMs)` returns `(rectCurrent, rectNext?, alpha, dipBlack)` — this one function drives both export shader and (later) a full-slideshow preview.

**The shader** (`render/KenBurnsShaderProgram`, extends `BaseGlShaderProgram`): samplers `uCurrent` (input frame tex id from `drawFrame`) and `uNext` (texture we upload ourselves on the GL thread, lazily, from the next photo's downscaled file); uniforms `uCurrentRect`, `uNextRect`, `uAlpha`, `uDip`. Fragment shader: sample current at `uCurrentRect` UVs, if `uAlpha > 0` mix with next sampled at `uNextRect`; DIP_TO_BLACK mixes toward black out/in instead (no second texture). CUT sets `uAlpha = 0` always. Free the next-texture in `release()`.

**Timestamp semantics.** Whether `presentationTimeUs` passed to a per-item effect is item-local or composition-global for image items is not documented. `CompositionBuilder` passes each item's computed `startUs` into its effect and `core/render/ItemClock` works either way (see section 9).

**Decoding and memory.** All decoding happens once, at import (`io/PhotoImporter`): `ImageDecoder` with target sample size so long edge ≤ 2560, EXIF orientation applied (ImageDecoder does this automatically), saved as JPEG q90 into the project dir, recording post-rotation dimensions. Render and preview only ever touch these bounded copies — ≤ ~2560×2560 RGBA ≈ 26 MB peak per decode, two live textures max in the shader. No full-res bitmaps anywhere after import.

**Export flow** (`export/ExportService`):
- Foreground service; type `mediaProcessing` on API 35+, `dataSync` below (manifest declares both plus the matching `FOREGROUND_SERVICE_*` permissions). This keeps the encode alive when the user backgrounds the app. `Exporter` owns the export; the service holds the process in the foreground, a wake lock, and the notification.
- Transformer writes to a private temp file in `cacheDir`. On success: insert into MediaStore `Movies/GV-Slideshow/` with `IS_PENDING=1`, stream-copy, clear pending. No storage permission needed for app-created media.
- Progress: poll `transformer.getProgress(ProgressHolder)` every 500 ms → notification progress bar + a `StateFlow` the export screen collects.
- Cancel: `transformer.cancel()`, delete temp, stop service.
- Errors: surface the failure in the notification and screen. If only the MediaStore publish failed, the encoded file is kept so "Try again" re-publishes without re-encoding.

## 6. Screens and UX flow

Theme: dark background (#121216-ish), teal #4fd1c5 for accents/highlights/selected states, purple #503aa8 strictly for buttons/FABs/interactive fills, cycle accent colors (teal, orange, green, pink) for per-photo tint dots in the timeline. Triangle motif: play markers, easing icons, empty-state graphic. No square/diamond decoration (crop handles are functional and exempt). Plain direct UI text.

1. **Project list** (launch). Cards: name, photo count, aspect badge, modified date. FAB "New video" → dialog: name + aspect (three triangle-marked toggle chips). Long-press: delete/rename.
2. **Timeline** (project home). Reorderable grid of photo thumbnails (drag handle; `sh.calvin.reorderable` Compose lib or equivalent — pick whatever current maintained reorderable-LazyGrid lib builds cleanly). Top bar: project transition picker (cut / crossfade / dip to black) + transition length. Buttons: "Add photos" (system photo picker, `PickMultipleVisualMedia`), "Export". Tap thumbnail → editor. Import shows per-photo progress while copying/downscaling.
3. **Per-photo editor.** Photo letterboxed on a Canvas; two draggable/resizable rect overlays locked to output aspect (teal = start, orange = end, labels "A"/"B"; corner-drag resizes about the opposite corner, body-drag moves, both clamped in-bounds and zoom-clamped to [1.0, 2.0]). Below: duration slider 2–10 s, easing selector (4 chips with tiny curve glyphs), "Re-randomize" button, "Preview" button that animates the crop live on the same Canvas using `KenBurnsMath.rectAt` driven by `withFrameNanos`. Chips under Easing set the per-photo transition override (project default, cut, crossfade, dip to black, slide; hidden on the last photo). Changes autosave (debounced) and on exit.
4. **Export screen.** Resolution line (fixed by aspect), estimated duration, Export button (purple). Progress bar + cancel while running; on finish, "Open video" (ACTION_VIEW on the MediaStore URI). A result notification is posted too if the app was backgrounded.

## 7. Build environment

`gradle.properties` is tuned for low-memory build machines (single worker, no daemon, capped heap); raise or remove those settings on a normal workstation:

```properties
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+HeapDumpOnOutOfMemoryError
org.gradle.daemon=false
org.gradle.parallel=false
org.gradle.workers.max=1
org.gradle.caching=true
kotlin.daemon.jvmargs=-Xmx768m
android.useAndroidX=true
android.nonTransitiveRClass=true
```

Toolchain: Gradle wrapper at the latest Gradle the chosen AGP requires, AGP latest stable at setup time (must support compileSdk 36), Kotlin with the bundled Compose compiler plugin, JDK 21. `local.properties` holds `sdk.dir` (gitignored).

Signing: `app/build.gradle.kts` reads an optional, gitignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; see `keystore.properties.example`) and applies that config to both `debug` and `release`, so debug and release builds install over each other. Without the file, debug uses the default debug key and release falls back to it with a Gradle warning, so a fresh clone builds with no setup. To make your own key:

```bash
keytool -genkeypair -v -keystore my-release-key.jks -alias release \
  -keyalg RSA -keysize 2048 -validity 10000
```

Release builds run R8 and resource shrinking with `isDebuggable=false`. ABI splits produce one APK each for arm64-v8a, armeabi-v7a and x86_64 plus a universal APK (ML Kit ships a native library per ABI). Version codes are `versionCode * 10 + slot`.

Verification: no device or emulator is required for the gate. `./gradlew :core:test` covers all pure logic, and `./gradlew :app:assembleDebug` (or `assembleRelease`) checks that the Android layer compiles. GL and encoder behaviour can only be confirmed on a real device.

## 8. Risks and decisions

Risks (engineering):
1. **Effect timestamp semantics** (local vs global per item): absorbed by `ItemClock`.
2. **Shader output sizing**: relies on `BaseGlShaderProgram.configure()` to set the output size.
3. **CompositionPlayer**: deliberately not used. The full-slideshow preview is a Canvas route that shares the export's timeline math.
4. **GL and encoder bugs** surface only on real devices. Everything else is JVM-tested.

Decided values:
1. Frame rate: 60 fps.
2. Crossfade length: 0.6 s default, adjustable.
3. Export location: `Movies/GV-Slideshow/` (files named `GV-Slideshow_yyyyMMdd_HHmmss.mp4`). Older builds used `Movies/KenBurns/`; the stale-pending cleanup matches both names.

## 9. Notes and known limits

- **R8 keep rules.** ML Kit's bundled native library and its component registrars reach Java classes by JNI and reflection, and R8 full mode otherwise strips whole `com.google.mlkit.common.*` and `mlkit_vision_face_bundled` classes, which broke import in a release build. `proguard-rules.pro` keeps `com.google.mlkit.**`, `com.google.android.gms.internal.mlkit_**`, `gms.vision.**`, `odml.image.**` and native methods. The exact class that mattered was not isolated, so the keeps are broad. Face detector creation is guarded: any failure or an 8 s timeout means `faces = null` and the photo is framed without faces. Exception class names are also kept so error text is readable.
- **Network.** The merged manifest strips INTERNET and ACCESS_NETWORK_STATE (`tools:node="remove"`). ML Kit's usage-data channel (`com.google.android.datatransport`) still registers its scheduler service and receiver; without the permission it cannot send anything. Face detection uses the bundled model and does not need the network.
- **Texture flip.** Media3's bitmap input stage mirrors Y, so the input frame has the image top at v = 1, while a bitmap uploaded with `GlUtil.createTexture` has it at v = 0. Both flips are handled in `core/render/ShaderUniforms` (rect vec4 = offset + vTex * scale) and unit tested. The preview uses the same uniform values so it cannot drift from export.
- **Presentation time.** Per-item effects most likely see composition-global timestamps, but this is only read from source. `ItemClock` decides on an item's first frame: GLOBAL (pts near the item's start), LOCAL (pts near 0), or ANCHORED (treat the first frame as local 0).
- **Image items.** `MediaItem.Builder.setImageDurationMs` must be set as well as `EditedMediaItem.setDurationUs`, or the image loader is not used. `setFrameRate(60)` on the item does give 60 fps output.
- **Sampling.** Crops use mipmapped linear filtering to avoid shimmer when a 2560 px source is shrunk to 1080; the program falls back to plain linear if that raises a GL error.
- **Encoder bitrate.** Media3 1.11.1 `DefaultEncoderFactory` assumes 30 fps when the encoder input has no frame rate, which is the case for image input, so the default bitrate was half of what 60 fps needs. `Exporter` sets an explicit VBR bitrate from `ExportEstimate.bitrateBps` (20 Mbps for 9:16 and 16:9, 12 Mbps for 1:1), and the size estimate uses the same table. The encoder may clamp to its supported range.
- **Codec.** With no output MIME type, Transformer picked HEVC for image input on the test device. `Exporter` sets H.264 for the widest playback support; for smaller HEVC files change the MIME type and lower the bitrate table together.
- **Foreground service.** `mediaProcessing` on API 35+, `dataSync` on 33-34; `startForeground` tries mediaProcessing, then dataSync. Both have a 6 h per 24 h budget on Android 15+, and `onTimeout` cancels the export. `ExportService.start` uses `startService`, not `startForegroundService`, to avoid the "must call startForeground" crash contract. If `startForeground` is refused the export still runs in-process and the app shows the reason; the cause of refusals seen on one test device was not confirmed. `Exporter` posts the result notification itself, so it appears even if the service is stopped mid-save.
- **Diagnostics.** Failures are appended to `filesDir/errors.log` (last 50 KB), viewable from the project list's overflow menu.
- **Signing.** Without `keystore.properties`, release builds fall back to the debug key with a Gradle warning, so a fresh clone builds with no setup.
- **Schema.** `ProjectJson.CURRENT_VERSION` is 2 (SLIDE was added); v1 files load through a no-op migration and saving writes v2.
- **Builds.** Release builds (R8, resource shrinking) are slow on one CPU.
- **Device-only behaviour.** GL output and encoder behaviour can only be confirmed on a real device.
