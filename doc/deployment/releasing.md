# Releasing Klaw

## Creating a release

Push a version tag to trigger the release workflow:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The GitHub Actions release workflow (`.github/workflows/release.yml`) runs automatically.

## What the release workflow does

### Build jobs (parallel)

| Job | Runner | Produces |
|-----|--------|----------|
| `build-linux` | `ubuntu-latest` | `klaw-gateway-*.jar`, `klaw-engine-*.jar`, `klaw-linuxX64`, `klaw-linuxArm64` |
| `build-macos` | `macos-latest` | `klaw-macosArm64`, `klaw-macosX64` |

The Linux runner cross-compiles `klaw-linuxArm64` for Raspberry Pi 5 (Kotlin/Native ships its own LLVM toolchain that supports arm64 cross-compilation from x86-64).

### Docker publish job

After `build-linux` succeeds, `publish-docker` runs in parallel with `build-macos`:

1. Downloads Linux artifacts (JARs + `klaw-linuxX64`)
2. Logs in to GHCR using `GITHUB_TOKEN`
3. Builds and pushes three Docker images:

| Image | Tags |
|-------|------|
| `ghcr.io/sickfar/klaw-engine` | `:latest`, `:{version}` |
| `ghcr.io/sickfar/klaw-gateway` | `:latest`, `:{version}` |
| `ghcr.io/sickfar/klaw-cli` | `:latest`, `:{version}` |

The `klaw-cli` image contains the `klaw-linuxX64` binary and the production compose file baked in at `/app/docker-compose.json`.

### Publish GitHub Release job

After `build-linux`, `build-macos`, and `publish-docker` all succeed:
1. Downloads all artifacts from both build jobs into `release-dist/`
2. Creates a GitHub Release named after the tag
3. Uploads all artifacts to the release
4. Generates release notes from commits since the previous tag

## Release artifacts

A full release contains 6 files:

| File | Target |
|------|--------|
| `klaw-gateway-{version}.jar` | All platforms (JVM) |
| `klaw-engine-{version}.jar` | All platforms (JVM) |
| `klaw-linuxArm64` | Raspberry Pi 5, Linux arm64 |
| `klaw-linuxX64` | Linux x86-64 |
| `klaw-macosArm64` | macOS Apple Silicon |
| `klaw-macosX64` | macOS Intel |

## Version number

Version is set in root `build.gradle.kts`:

```kotlin
allprojects {
    version = "0.1.0-SNAPSHOT"
}
```

Remove `-SNAPSHOT` before tagging a release. The JAR artifact names include the version string.

## Monitoring a release

```bash
# Check workflow status:
gh run list --workflow=release.yml

# Watch a specific run:
gh run watch

# View release:
gh release view v0.1.0
```

## Installing from a GitHub Release

```bash
# Download engine and gateway JARs:
gh release download v0.1.0 --pattern "*.jar" --dir ~/.local/share/klaw/bin/

# Download CLI binary for Pi:
gh release download v0.1.0 --pattern "klaw-linuxArm64" --output ~/.local/bin/klaw
chmod +x ~/.local/bin/klaw
```
