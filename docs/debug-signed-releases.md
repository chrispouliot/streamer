# Debug-signed release builds

How to build a non-debuggable release APK signed with the project-local debug
key, for sideloading to your own devices. Written to be reused in other projects
with the same setup: a Nix flake with `nix/android.nix`, the `android-*` helpers,
and an application module named `app`.

The steps:

1. Sign `release` with the debug key (Gradle).
2. Add `--release` to the `android-build` / `android-install` / `android-run`
   helpers (`flake.nix`).
3. Optional: fix the `apksigner` and `d8` launchers in the Nix SDK
   (`nix/android.nix`).
4. Update the docs.

## 1. Sign the release build with the debug key

In `app/build.gradle.kts`:

```kotlin
buildTypes {
    release {
        isMinifyEnabled = false
        // Personal sideloading: sign with the project-local debug key
        // (.android-user-home) so release and debug install over each other
        // and keep app data. Not suitable for distribution.
        signingConfig = signingConfigs.getByName("debug")
    }
}
```

- `assembleRelease` now produces a signed `app-release.apk` instead of
  `app-release-unsigned.apk`.
- The key is `$ANDROID_USER_HOME/debug.keystore`. The dev shell points
  `ANDROID_USER_HOME` at the project's `.android-user-home/`, and the Android
  build tools create the key there on first build. Keep `.android-user-home/` in
  `.gitignore`.
- To have both projects share one signing key, copy an existing
  `debug.keystore` into the new project's `.android-user-home/` before the first
  build. Otherwise each project gets its own key, which is fine.
- Debug-key credentials (Android defaults): store password `android`, key alias
  `androiddebugkey`, key password `android`.

## 2. Add `--release` to the helpers in `flake.nix`

In Nix `''…''` strings, `${` has to be written as `''${`. Expanding an empty
array with `"${args[@]}"` is safe under `set -u` in bash 4.4 and later.

### `android-build`

Take out `--release` and pass everything else to Gradle:

```nix
text = ''
  # --release builds the release variant; other arguments go to Gradle.
  task=assembleDebug
  args=()
  for arg in "$@"; do
    if [[ "$arg" == --release ]]; then
      task=assembleRelease
    else
      args+=("$arg")
    fi
  done
  exec android-gradle "$task" "''${args[@]}"
'';
```

### `android-install`

Choose the APK folder by variant:

```nix
launch=1
variant=debug
for arg in "$@"; do
  case "$arg" in
    --no-launch) launch=0 ;;
    --release) variant=release ;;
    -h | --help)
      echo "Usage: android-install [--release] [--no-launch]"
      exit 0
      ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done
apk_dir=app/build/outputs/apk/$variant
metadata="$apk_dir/output-metadata.json"
if [[ ! -f "$metadata" ]]; then
  build_cmd=android-build
  [[ "$variant" == release ]] && build_cmd="android-build --release"
  echo "No $variant APK in $apk_dir; run $build_cmd first." >&2
  exit 1
fi
# Fail explicitly for split APKs rather than installing only one.
if [[ "$(jq -r '.elements | length' "$metadata")" != 1 ]]; then
  echo "Expected one $variant APK. Use the android-gradle install task for split APKs." >&2
  exit 1
fi
# ...rest unchanged (read applicationId/outputFile, adb install -r, launch)
```

### `android-run`

Pass the flag to both steps and read the matching metadata for `--logcat`:

```nix
logcat=0
variant=debug
variant_args=()
for arg in "$@"; do
  case "$arg" in
    --logcat) logcat=1 ;;
    --release) variant=release; variant_args=(--release) ;;
    -h | --help)
      echo "Usage: android-run [--release] [--logcat]"
      exit 0
      ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done
android-build "''${variant_args[@]}"
android-install "''${variant_args[@]}"
if [[ "$logcat" == 1 ]]; then
  app_id=$(jq -er '.applicationId' "app/build/outputs/apk/$variant/output-metadata.json")
  # ...rest unchanged (pidof, adb logcat --pid)
fi
```

## 3. Fix `apksigner` and `d8` in `nix/android.nix` (optional)

This fix is separate from release signing and only matters if you run
`apksigner` yourself. Gradle signs APKs without it.

Google's launcher scripts follow symlinks with `/bin/ls`, which NixOS doesn't
have, so the SDK's `bin/apksigner` symlink failed with
`/bin/ls: No such file or directory` and `can't find apksigner.jar`. The scripts
also need `expr`, `dirname`, `basename` and `java` on `PATH`. In the
`buildTools` derivation:

```nix
buildTools =
  pkgs.runCommand "android-build-tools-${buildToolsVersion}"
    {
      nativeBuildInputs = nativeBuildInputs ++ [ pkgs.makeWrapper ];
    }
    ''
      ${unpackOne (archive "build-tools" buildToolsVersion) "$out"}
      ${patchHostTools "$out"}
      patchShebangs "$out"
      # Google's Java launchers follow symlinks with /bin/ls (absent on
      # NixOS) and otherwise need coreutils and java from PATH.
      for tool in apksigner d8; do
        substituteInPlace "$out/$tool" --replace-fail '`/bin/ls -ld' '`ls -ld'
        wrapProgram "$out/$tool" --prefix PATH : ${
          lib.makeBinPath [
            pkgs.coreutils
            pkgs.jdk21
          ]
        }
      done
    '';
```

`--replace-fail` makes a future build-tools version that changes these scripts
fail to build instead of breaking quietly. This changes the build-tools store
path, so run `android-gradle --stop` after re-entering the shell so a running
Gradle daemon doesn't keep the old AAPT2 path.

## 4. Docs

- `AGENTS.md`: add `--release` to the helper table and the command list.
- `README.md`: add a note that the release build uses the debug key, where the
  key is, that it should be backed up, and that it's for personal sideloading
  only.

## Usage and checks

```bash
nix fmt && nix flake check --no-build --all-systems
nix develop
android-build --release        # → app/build/outputs/apk/release/app-release.apk
android-run --release          # build, install, launch; add --logcat to follow logs
android-install --release      # install an already-built release APK
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
keytool -list -v -keystore .android-user-home/debug.keystore -storepass android | grep SHA256
```

Without the helpers, `android-gradle assembleRelease` produces the same APK.
The debug and release APKs should show the same `CN=Android Debug` certificate
fingerprint.

## Caveats

- The debug key's password is the well-known `android`. Don't use this for the
  Play Store or for distributing to other people; use a dedicated release
  keystore kept out of the repo.
- Back up `.android-user-home/debug.keystore`; `debug.keystore.lock` is only a
  lock file. Without the key, the next build creates a new one, and the device
  won't accept an update signed by a different key. You'd have to uninstall the
  app and lose its data.
- Release and debug installs replace each other only if they have the same
  application ID and `versionCode` doesn't go down.
- `isMinifyEnabled` stays `false`. Turning on R8 (`isMinifyEnabled = true`,
  `isShrinkResources = true`) makes the APK smaller and faster, but needs keep
  rules (e.g. for kotlinx.serialization) and testing on a device.
