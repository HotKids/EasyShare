# APK signing

- Every APK output must use the existing official Release signing identity. Never use debug or test signing, newly created or temporary keys, unsigned APKs, or signing fallbacks.
- All APKs, including universal and arm64 outputs, must follow the same Gradle `:app:assembleRelease` packaging and `signingConfigs.release` signing path. Use native Gradle ABI splits; CI must not strip packaged APK contents or re-sign APKs afterward.
- Keep bundle-to-APK conversion disabled; it is a separate packaging path from native Gradle ABI splits.
- Reject IDE or command-line `android.injected.signing.*` overrides. Only the existing official `SIGNING_*` inputs may configure APK signing.
- Missing required signing inputs must fail APK packaging. Unit tests, lint, compilation, resource processing, and R8 may run without signing inputs when they do not package an APK.
- Preserve the existing opaque credential sources and let Gradle consume them normally. Do not inspect or expose credential values or private keys, create or mutate credential stores, or change the signing identity or credential source without an explicit user instruction.
- The official signing identity has already been used repeatedly for this project. An empty `SIGNING_*` environment in the current shell is not evidence that the identity or its configured source is missing. Before asking the user to configure signing again, recover the established successful build invocation from project documentation, existing local wrappers, or prior build records, then let that client consume its normal opaque credential source. Record the verified invocation here without credential values; do not introduce a second signing path.

## Established local signing source

- Existing keystore: `/Users/joey/.android/easy-share-release.jks`; existing alias: `easy-share`.
- Existing macOS Keychain services: `easy-share-release-store` for `SIGNING_STORE_PASSWORD`, and `easy-share-release-key` for `SIGNING_KEY_PASSWORD`. The established `security find-generic-password` invocation does not specify an account.
- Expected official certificate SHA-256: `cd178ef09e01063f9028e0392d1fb6148661d851c79e3ba11a825351a7c26fcf`. Verify the APK certificate metadata against this fingerprint after packaging.
- Use these existing inputs directly with Gradle. Never replace the key, read passwords into chat or logs, or restore the historical post-packaging `apksigner sign` path.
- Run from this checkout, without shell tracing or verbose Gradle logging:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/joey/Library/Android/sdk \
SIGNING_KEYSTORE_PATH=/Users/joey/.android/easy-share-release.jks \
SIGNING_KEY_ALIAS=easy-share \
SIGNING_STORE_PASSWORD="$(/usr/bin/security find-generic-password -s easy-share-release-store -w)" \
SIGNING_KEY_PASSWORD="$(/usr/bin/security find-generic-password -s easy-share-release-key -w)" \
./gradlew :app:assembleRelease --console=plain
```

# Pixel UI delivery

- After authorized UI changes, build through the existing official `:app:assembleRelease` path and install the current compatible Release APK on the intended connected Pixel before handing the work back for testing.
- Verify the connected device's model and hardware serial. Use `adb -s <serial> install -r` on its current ADB server, preserving app data; do not substitute another phone or clear data.
- Read back the installed version name and code, pull its installed `base.apk`, and confirm its SHA-256 matches that exact local APK.
- Report installation failures and unavailable checks explicitly. Installation and artifact identity do not establish visual acceptance, accessibility, unknown-brand behavior, or successful end-to-end transfers; report those results separately.
