# 0.1.3 minified startup regression

The signed 0.1.2 APK crashed immediately on a Realme RMX3636. Its crash buffer reported Kotlin reflection failing to resolve `createClientPlugin(String, Function0, Function1): ClientPlugin` while constructing the Supabase client.

The compiled Ktor API is `io.ktor.client.plugins.api.CreatePluginUtilsKt`. R8 changed its method/descriptor names, while the plugin's callable reference retained the original JVM signature string. Debug tests and successful APK signature checks did not exercise this minified startup path.

The hotfix keeps the Ktor plugin API and the two Kotlin function interfaces used in its reflected descriptor, along with generic/enclosing metadata. Shrinking, resource shrinking, non-debuggable mode and production signing remain enabled.

## Device regression

- Signed/minified 0.1.3 / code 4, same production certificate as 0.1.2.
- Installed over the existing `com.auris.app` package with `adb install -r`; no uninstall or storage clearing.
- Three cold launches passed on the connected Realme RMX3636; the process stayed alive and the login UI was visually inspected. Subsequent test-process crash buffers contained no fatal exception.
- These checks establish startup/login rendering, not authenticated playback or all app behavior.

Before publishing future changes to shrinking or startup dependencies, test the signed/minified APK on a device. Debug tests, lint and artifact-signature verification do not replace that runtime check.

Local `:app:assembleRelease :app:lintRelease` completed successfully after the device regression. The first offline attempt required uncached release dependencies; the online build succeeded. R8 Kotlin-metadata compatibility warnings remain; the targeted reflected API retention was verified by actual minified startup.

## Published verification

- Release: https://github.com/miguelraphael/ionic-auris/releases/tag/v0.1.3 ; tag source `8c876c6`.
- CI run: https://github.com/miguelraphael/ionic-auris/actions/runs/42351099168 — regression build/tests, signed release build/lint and publication succeeded.
- Local release lint: 0 errors, 92 warnings.
- Published APK SHA-256: `65ca3fd7ab9c9b7e5ceb4bcff0ed566d2f9651c90bd897fccbaa233fc448d223`, matches published checksum.
- Certificate SHA-256: `5a43f5baf2439c20ab4c52bea9f76267ae76fc1149a334d27c8c0bc786b78993`, same as 0.1.2; verified non-debuggable `com.auris.app`, version 0.1.3 / code 4.
- The exact downloaded GitHub APK was installed in place and cold-launched on the Realme RMX3636. It stayed alive with no new fatal crash. No app data was cleared.
- The 0.1.2 release description now warns about its startup failure and points to 0.1.3. Old tags and assets were retained.
