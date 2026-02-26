# Building Klaw

## Quick build

```bash
./scripts/build.sh
```

Runs `./gradlew assembleDist`, then builds Docker images if Docker is available.

## Gradle tasks

| Task | What it produces | When to use |
|------|-----------------|-------------|
| `assembleDist` | `build/dist/` with JARs + OS-native CLI binaries | Full build on Linux/macOS |
| `assembleCliMacos` | `build/dist/klaw-macosArm64`, `klaw-macosX64` only | macOS CI — skips JARs |

```bash
./gradlew assembleDist       # build everything for current OS
./gradlew assembleCliMacos   # macOS CLI binaries only
```

## Artifacts produced

After `assembleDist`, `build/dist/` contains:

| Artifact | Description |
|----------|-------------|
| `klaw-gateway-{version}.jar` | Fat JAR — Gateway service (Micronaut + all deps) |
| `klaw-engine-{version}.jar` | Fat JAR — Engine service (Micronaut + ONNX + all deps) |
| `klaw-linuxArm64` | CLI binary for Raspberry Pi 5 (arm64) |
| `klaw-linuxX64` | CLI binary for Linux x86-64 |
| `klaw-macosArm64` | CLI binary for macOS Apple Silicon |
| `klaw-macosX64` | CLI binary for macOS Intel |

Only the CLI binaries for the **current host OS** are produced. JARs are always produced regardless of OS.

## Build requirements

- JDK 21+ (Gradle wrapper downloads Gradle automatically)
- For native CLI binaries: Kotlin/Native ships its own LLVM toolchain — no separate compiler needed
- Cross-compilation: Linux can cross-compile `linuxArm64` from an `x86-64` host (used in CI)

## Checking artifact sizes

```bash
ls -lh build/dist/
# Expected sizes:
# klaw-engine-*.jar     ~100 MB (ONNX + DJL tokenizers bundled)
# klaw-gateway-*.jar    ~40 MB
# klaw-linuxArm64       ~1.3 MB
# klaw-macosArm64       ~1.3 MB
```

## Verifying JARs

```bash
# Check that the Gateway JAR has the correct main class:
unzip -p build/dist/klaw-gateway-*.jar META-INF/MANIFEST.MF | grep Main-Class
# Expected: Main-Class: io.github.klaw.gateway.Application

unzip -p build/dist/klaw-engine-*.jar META-INF/MANIFEST.MF | grep Main-Class
# Expected: Main-Class: io.github.klaw.engine.Application
```
