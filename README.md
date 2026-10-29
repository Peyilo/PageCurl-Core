# PageCurl Core

PageCurl Core is a Kotlin Android library for page-curl geometry, gesture recognition, page layout and animation scheduling. It generates vertex buffers and draw commands that a host application can render with its own graphics backend.

The library provides the calculations and interaction state. The host supplies page content, touch events, an animation clock and a renderer.

## Requirements

- Android API 21 or newer for consuming applications.
- JDK 17 and Android SDK Platform 36 to build this repository.
- Gradle 8.13, Android Gradle Plugin 8.11.0 and Kotlin 2.0.21, pinned in the project configuration.

Set `ANDROID_HOME` to your Android SDK directory, or create an untracked `local.properties` file containing `sdk.dir`.

## Build

On macOS or Linux:

```sh
./gradlew :pagecurl-core:assembleRelease :pagecurl-core:lintDebug
```

On Windows:

```powershell
.\gradlew.bat :pagecurl-core:assembleRelease :pagecurl-core:lintDebug
```

The release AAR is written to `pagecurl-core/build/outputs/aar/pagecurl-core-release.aar`.

## Use the library

Add Maven Central to the consuming project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Add the library to your Android module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("org.peyilo.pagecurl:pagecurl-core:0.1.0")
}
```

## Generate a frame

```kotlin
import kotlin.math.PI
import org.peyilo.pagecurl.mesh.CurlMesh
import org.peyilo.pagecurl.mesh.CurlMeshInput
import org.peyilo.pagecurl.model.CurlValues

val mesh = CurlMesh()
val frame = mesh.build(
    CurlMeshInput(
        values = CurlValues(time = 0.5, radius = 50.0, angle = PI),
        width = 320.0,
        height = 480.0,
    )
)

// Submit frame.batches to the host renderer in the returned order.
```

`time` is normalized geometric progress, not elapsed seconds. Radius and page dimensions use logical units; angles use radians. `scale` maps logical units to output coordinates. Vertex positions remain in logical units, while frame dimensions and clip bounds use scaled output coordinates.

Each vertex has a 64-byte little-endian layout: Float position x/y/z/w at offsets 0/4/8/12, Float texture u/v at 16/20, and four premultiplied half-float RGBA channels at 40..47. Texture coordinates are source-relative scaled coordinates and need renderer-specific normalization. Remaining bytes are reserved. Primitive, program and blend values are internal codes, not graphics API constants; see [CurlDrawBatch](pagecurl-core/src/main/kotlin/org/peyilo/pagecurl/mesh/CurlMesh.kt) before implementing an adapter.

`CurlMesh` reuses mutable working buffers. Serialize builds on an instance, or use a separate instance per worker. Returned frames own their vertex bytes and may be retained across later builds.

## Integrate interaction and animation

1. Feed platform touch events into `CurlPan` or `CurlTap`. Use page coordinates and timestamps from one monotonic clock, in seconds. Create a new recognizer after a terminal gesture state.
2. Use `CurlControl` to compute initial, updated and target `CurlValues` from a `CurlConfig` and drag positions.
3. Use `CurlTiming.interpolate` for animation frames. Its `elapsed` argument is a normalized fraction; `duration` is a positive value in seconds.
4. Use `CurlLayout` to select front/back content and static underlays. The host resolves symbolic content slots to actual document pages and checks document bounds.
5. Use `CurlTransitions` when turns overlap or queue. Execute its layer events in order and report host animation completion back to the scheduler.
6. Build the mesh and submit its ordered batches to the renderer, applying the clip, texture and premultiplied-color contracts.

## Extend the implementation

All source packages are under `pagecurl-core/src/main/kotlin/org/peyilo/pagecurl`.

| Package | Responsibility |
| --- | --- |
| `model` | Configuration, geometry values and shared navigation codes |
| `control` | Drag-to-geometry conversion and Bezier animation timing |
| `gesture` | Touch recognition, velocity estimation and navigation decisions |
| `layout` | Content slots, face passes and static page layers |
| `transition` | Queuing, overlap policy and ordered layer events |
| `mesh` | Public mesh inputs, materials and draw commands |
| `mesh/generation` | Projection, sampling, page surfaces, shadows and vertex packing |
| `math` | Compensated multiply-add and binary16 conversion for API 21 support |

Start with [MeshGenerator](pagecurl-core/src/main/kotlin/org/peyilo/pagecurl/mesh/generation/MeshGenerator.kt) for the generation stage order. The page profile is in `CurlCurve.kt`, subdivision logic in `CurveSampling.kt`, surface construction in `PageSurfaceBuilder.kt`, shadow contours in `ShadowBuilder.kt` and `PenumbraBuilder.kt`, and the output format in `VertexBuffer.kt` and `DrawBatchAssembler.kt`.

Preserve descending sample order and intentional duplicate seam samples when changing subdivision. Keep the explicit Float rounding points when preserving existing visual output. Changes to the vertex layout or draw-program codes require corresponding renderer updates. KDoc on public inputs and internal stages describes coordinate spaces, limits and lifecycle assumptions.

## License

The library is licensed under the [MIT License](LICENSE). It can be used by MIT-licensed and GPL-licensed projects while retaining the copyright and permission notice. Combining it with GPL code does not remove the combined work's GPL obligations. See the [GNU license compatibility list](https://www.gnu.org/licenses/license-list.html#Expat).

The bundled Gradle Wrapper is separate build tooling under Apache License 2.0. Its license and attribution are included in `gradle/wrapper/LICENSE` and `gradle/wrapper/NOTICE`.
