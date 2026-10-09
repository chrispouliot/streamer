# Streamer

Native Kotlin/Jetpack Compose Android player for a Navidrome server
(Subsonic/OpenSubsonic API). Material Design 3 and works on both Phone/Tablet and Googlebook desktop mode

## Build

```bash
nix develop        # or direnv
android-doctor
android-build                              # debug APK
android-gradle testDebugUnitTest lintDebug
android-run --logcat                       # install + launch on a device
android-run --release                      # non-debuggable release build
```

The release build is signed with the same project-local debug key
(`.android-user-home/debug.keystore`), so release and debug builds
install over each other and keep app data. Back that keystore up: losing it
means uninstalling (and losing app data) to update. The debug key is for
personal sideloading only, not for distribution.

## Server connection

- Any Subsonic/OpenSubsonic server address, including a reverse-proxy sub-path;
  a pasted `/rest` or `/app` suffix is removed.
- `https://` uses the system certificate store only. Plain `http://` (for example
  over Tailscale or a home network) must be allowed explicitly for that server;
  the app never downgrades an https address.
- Token authentication (`t`/`s`); the password is stored encrypted with an
  Android Keystore key in no-backup storage and is never logged.
- Device tests: `android-gradle connectedDebugAndroidTest`.
