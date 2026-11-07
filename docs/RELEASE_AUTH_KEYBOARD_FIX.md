# Release authentication and keyboard regression

The signed 0.1.3 release started successfully but failed before its first auth HTTP request. Emulator reproduction captured ExceptionInInitializerError / IllegalArgumentException in Ktor engine attribute initialization. A repeat attempt surfaced the obfuscated class name.

Ktor reflects HttpClientEngineCapability<*> and HttpClientConfig<*>. Preserve these generic types and HttpClientEngineConfig in R8 rules. Shrinking remains enabled. Auth failures now use safe user messages; diagnostic logs record exception types and call sites without exception messages or request contents.

AuthScreen now applies safe drawing and IME insets and scrolls when space is constrained. The activity explicitly requests adjustResize. Emulator UI inspection with the keyboard open confirmed a scrollable form and visible email, password, and submit controls.

Verification: 451 JVM tests, zero failures; release lint passed before the final keep-rule-only addition; final signed assembleRelease passed. The final signed APK installed on the isolated API 34 emulator and a deliberately invalid login reached Supabase, returning the expected credential rejection without initializer failure. Successful real-account sign-in and registration remain unverified; no phone was connected or modified. No release published; version remains 0.1.3/code 4.

APK: app/build/outputs/apk/release/app-release.apk. Existing R8 Kotlin metadata warnings remain.
