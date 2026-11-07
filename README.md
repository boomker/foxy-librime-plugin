# Rime Plugin Template

A standalone Android app that ships a `librime.so` payload for Rime frontends
that support external native backends. Foxy is one such frontend; the payload
and the generic manifest contract are not tied to it.

A frontend discovers this app, validates the library against the declared API
ABI, and loads it from the app's `nativeLibraryDir`. Supporting another frontend
means adding its discovery action/metadata (and any librime patches it needs);
the payload stays the same.

This repository is a template. It builds a near-upstream librime from the pinned
submodule plus the standard plugins, so the end-to-end flow works out of the box;
replace the payload with your own librime fork later.

## Repository layout

```
app/                     Android app: manifest, discovery Activity, native build glue
  src/main/AndroidManifest.xml   discovery contract (actions + metadata)
  src/main/cpp/CMakeLists.txt    builds the plugin and stages librime.so
  src/main/cpp/plugin_template.c placeholder library; the real payload is librime.so
lib/                     librime build orchestration
  librime/               git submodule, the librime source
  patches/               patches applied to librime and its plugins
  CMakeLists.txt         ExternalProject rules that build librime.so
.github/workflows/build.yml    CI: builds debug + release APKs
```

## Build requirements

- JDK 21 (Android Gradle Plugin 9.3.1 requires 17+, CI uses 21)
- Android SDK with **platform 36** (`compileSdk`/`targetSdk` = 36, `minSdk` = 23)
- Android NDK, CMake, and build-tools (see versions below)
- Gradle 9.6.1 (provided by the wrapper)
- `git` and network access: the first build downloads Boost and clones the
  librime plugins
- ABI splits are emitted per ABI. A full build also produces one universal APK;
  a subset build (`-PbuildABI=...`) does not. Default ABIs are `armeabi-v7a`,
  `arm64-v8a`, `x86`, `x86_64`; build a subset with `-PbuildABI=arm64-v8a`
  (comma separated)
- Linux/macOS or Termux on-device builds; Windows is not tested

Version selection is read from `local.properties` at build time:

| `local.properties` key | Default | CI value |
| --- | --- | --- |
| `rime.ndkVersion` | `29.0.14206865` | `28.2.13676358` |
| `rime.cmakeVersion` | `4.2.1` | `3.31.6` |
| `rime.buildToolsVersion` | `37.0.0` | `36.0.0` |

`lib/librime` is pinned as a git submodule. Clone recursively or run
`git submodule update --init --recursive`; the build records the submodule HEAD
so updates are incremental.

## Building

```bash
git clone --recursive <this-repo>
# or, in an existing checkout:
git submodule update --init --recursive

./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

The build produces one APK per ABI plus a universal APK. File name is
`<applicationId>-<pluginVersionName>-<variant>-<abi|universal>.apk`:

```
app/build/outputs/apk/debug/org.rime.plugin.template.debug-1.0.0-debug-arm64-v8a.apk
app/build/outputs/apk/debug/org.rime.plugin.template.debug-1.0.0-debug-armeabi-v7a.apk
app/build/outputs/apk/debug/org.rime.plugin.template.debug-1.0.0-debug-universal.apk
app/build/outputs/apk/release/org.rime.plugin.template-1.0.0-release-arm64-v8a.apk
app/build/outputs/apk/release/org.rime.plugin.template-1.0.0-release-universal.apk
...
```

Users pick the split matching their device ABI, or the universal APK to cover
all ABIs in one file. Building only the ABIs you distribute keeps the per-ABI
build cost down. The universal APK is only produced when no `-PbuildABI` is
given, so a targeted build (for example `-PbuildABI=arm64-v8a`) yields just the
requested split APK(s).

Notes:

- The first build is heavy: it downloads Boost 1.89.0 and clones the librime
  plugins (`hchunhui/librime-lua`, `lotem/librime-octagram`,
  `rime/librime-predict`), then builds `librime.so` once per included ABI.
- The librime build cache lives in `.cxx/rime/`. Use `./gradlew :app:rimeClean`
  to delete it. Pass `-PrimeBuildRoot=<dir>` to relocate it.
- Boost is cached as `boost_1_89_0.tar.gz` in the repository root (gitignored)
  and reused on later builds.

## Signing

Release signing is optional: without keys the release APK is built unsigned.

Local builds:

```bash
cp keystore.properties.example keystore.properties
# edit storeFile / storePassword / keyAlias / keyPassword
```

CI builds use environment variables instead: `KEYSTORE_FILE`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. The workflow additionally
accepts a base64-encoded keystore in the `KEYSTORE_BASE64` secret.

Never commit `keystore.properties`, `*.jks`, or `*.keystore`. Frontends
authorize a plugin against its signing certificate, so re-signing with a
different key requires re-authorization.

## Package name and identifiers

| Item | Value in this template | Where to change |
| --- | --- | --- |
| `applicationId` (release) | `org.rime.plugin.template` | `app/build.gradle.kts` |
| `applicationId` (debug) | `...plugin.template.debug` | `applicationIdSuffix` in `app/build.gradle.kts` |
| `namespace` / Java package | `org.rime.plugin.template` | `app/build.gradle.kts` + `app/src/main/java/**` |
| `PLUGIN_ID` | `template` | `app/src/main/AndroidManifest.xml` |
| `SONAME` | `librime.so` | `app/src/main/AndroidManifest.xml` |
| App label / strings | `Rime Plugin Template` | `app/src/main/res/values*/strings.xml` |
| `pluginVersionName` | `1.0.0` | `app/build.gradle.kts` |

The `applicationId` must differ from every frontend's package (Foxy ignores its
own package during discovery).

APK file names are generated from the package name:
`<applicationId>-<pluginVersionName>-<variant>-<abi|universal>.apk`.

## Native payload contract

These properties describe the `librime.so` payload itself and are what a
frontend validates. They are independent of any frontend:

- The library file named by `SONAME` (default `librime.so`) is packaged in
  `lib/<abi>/` and ends up in the app's `nativeLibraryDir`.
- It is a complete librime and exports `RimeGetApi` (and the usual librime
  entry points).
- `RIME_API_ABI` declares the librime `RimeApi` struct ABI. A frontend should
  call `RimeGetApi()` and check `api->data_size` against its own compiled-in
  `RimeApi` before using it.
- The ABI must match the frontend process word size (32-bit or 64-bit).

## Frontend integration

Discovery uses an exported Activity with an intent-filter action plus
`<meta-data>` on `<application>`. The manifest in this template declares the
neutral contract (`org.rime.plugin.MANIFEST` action and `org.rime.plugin.*`
keys); adopting this contract is all a frontend needs to support the plugin.

Metadata keys:

| Metadata key | Meaning | Required |
| --- | --- | --- |
| `org.rime.plugin.API_VERSION` | Plugin discovery API version | Yes, must be `1` |
| `org.rime.plugin.RIME_API_ABI` | librime `RimeApi` ABI version | Yes, must be `1` |
| `org.rime.plugin.SONAME` | File name of the native library in `nativeLibraryDir` | Yes |
| `org.rime.plugin.PLUGIN_ID` | Plugin id (display) | Recommended |
| `org.rime.plugin.LIBRIME_VERSION` | librime version (display) | Recommended |
| `org.rime.plugin.CHANNEL` | `debug` or `release` (display) | Recommended |

`CHANNEL` comes from the `pluginChannel` manifest placeholder, set per build
type in `app/build.gradle.kts`.

### Foxy

Foxy consumes the neutral contract above and additionally:

- loads `File(nativeLibraryDir, SONAME)` directly (no copy into Foxy's data
  directory),
- marks the plugin compatible only when `API_VERSION == 1`,
  `RIME_API_ABI == 1`, the library exists, the package is signed, and the ABI
  matches the Foxy process,
- requires explicit user authorization, keyed by the signing certificate
  SHA-256, the library SHA-256, the `SONAME`, and `RIME_API_ABI` (any change
  invalidates it),
- is reachable at **Rime engine → Rime engine backend**.

### Frontend-specific adapter

A frontend that does not adopt the neutral contract can still be supported by
adding an adapter: a `<meta-data>` block in that frontend's own namespace plus a
matching `<intent-filter>` on `InfoActivity` (see the commented example in
`AndroidManifest.xml`). Implement that frontend's own discover/validate/load
policy, and add its librime patches if it needs runtime behavior changes.

## librime patches

The template keeps the payload close to upstream. `lib/patches/` only contains
build-enabling changes for Android (suppress the missing X11 keysym warning,
strip absolute source paths, and disable embedded Boost GDB scripts). It does
**not** carry behavior patches.

Behavior patches are frontend-specific. Foxy, for example, historically patched
librime to write back autosaved switch options from `RimeSetOption`, to skip
trashing deprecated user configs, and to expose `Switcher`/`Session::engine`.
If a frontend requires such behavior, add its patch set here (or build a
per-frontend payload). Keeping them out by default is what makes this payload
usable as a neutral `librime.so`.

## Making your own plugin

1. Rename the package: change `namespace`/`applicationId` in
   `app/build.gradle.kts` and move `app/src/main/java/...` to the new package.
2. Change `PLUGIN_ID`, `LIBRIME_VERSION`, the app label, and the generic
   metadata in `app/src/main/AndroidManifest.xml`.
3. Replace the payload: point `lib/librime` at your fork, or change
   `lib/CMakeLists.txt` and `app/src/main/cpp/CMakeLists.txt` to build your
   library. Keep the output file name equal to `SONAME`.
4. Keep `API_VERSION`/`RIME_API_ABI` at `1` unless the contract changes. Adjust
   `supportedABIs` in `app/build.gradle.kts` to the ABIs you ship (32-bit and
   64-bit are both valid as long as they match the frontend process).
5. Add adapter blocks for every frontend you want to support.

Do not commit `keystore.properties`, `release.jks`, `local.properties`,
`boost_1_89_0.tar.gz`, `.cxx/`, or `build/` directories.

## CI

`.github/workflows/build.yml` builds debug and release APKs on push (branches
`master`/`main`, tags `v*`), pull requests, and manual dispatch, then uploads
the APKs as artifacts. The Boost source and the librime build tree are cached
between runs. A manual dispatch exposes an `abis` input to build only a subset
(comma separated, e.g. `arm64-v8a`); leave it empty to build every ABI plus the
universal APK. Configure these repository secrets for signed releases:

- `KEYSTORE_BASE64` — base64 of the `.jks`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Without them, the release APK is built unsigned.