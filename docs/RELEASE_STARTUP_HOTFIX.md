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
