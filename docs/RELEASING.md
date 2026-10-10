# 发布说明

Easy Share 使用 GitHub Actions 构建并签名正式 APK。release keystore 不存放在仓库中，Workflow 只通过 GitHub Actions Secrets 在临时目录中恢复密钥，并在任务结束后删除。

## 签名配置

Gradle 的 Release 签名优先读取环境变量，其次读取用户目录下的 `~/.gradle/gradle.properties`。支持以下键：

- `SIGNING_KEYSTORE_PATH`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_PASSWORD`
- `SIGNING_KEY_ALIAS`

The project builds only the Release variant and uses the existing official signing key. All four inputs and the keystore file are required for APK or AAB packaging. Missing inputs fail packaging; no alternate signature or unsigned artifact is produced. Tests, lint, compilation, and R8 checks remain available without the private key. Do not use Gradle's `--info` or `--debug` logging levels for signed builds.

本机构建应从系统钥匙串读取密码并通过环境变量注入，不在项目或用户属性文件中落盘。CI 使用以下 Repository Secrets：

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_PASSWORD`
- `SIGNING_KEY_ALIAS`

The established local keystore is `/Users/joey/.android/easy-share-release.jks`,
with alias `easy-share`. The existing Keychain services are
`easy-share-release-store` and `easy-share-release-key`. The complete local
Gradle invocation is recorded in `AGENTS.md`; use it before concluding that
signing inputs are unavailable. Passwords remain in Keychain and are consumed
only through the build process environment. Do not sign APKs after packaging.

本机 keystore 应离线备份。GitHub Secrets 无法作为可靠的密钥备份，也无法在保存后读取明文。丢失用于直接分发 APK 的签名私钥，将无法继续为现有安装提供无缝更新。

## 日常构建

推送到 `main` 后，Workflow 会运行单元测试、Lint 和 Release 构建，并上传以下 artifact：

- `easy-share-universal.apk`
- `easy-share-arm64.apk`
- `SHA256SUMS`

Gradle generates universal, arm64-v8a, and x86_64 APKs in one `assembleRelease` run using native ABI splits and the same Release signing configuration. The x86_64 split uses the native libraries already supplied by the dependencies. CI distributes the universal and arm64 outputs without modifying or re-signing either APK. The signing constraints are recorded in the project `AGENTS.md`.

Pull requests run Release unit tests, lint, Kotlin/resource compilation, and R8 without reading signing Secrets or generating an APK. Main and manual workflow runs retain the existing official signing process.

## Publish a Release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add the current version to `CHANGELOG.md` and retain earlier entries.
3. Verify CI and merge the approved changes into `main`.
4. Manually run **Android CI** on `main` and select **Publish a GitHub Release from main**.

Pushing to `main` runs verification and uploads signed APK artifacts without creating a tag or Release. Manual runs also build only by default. Release publication requires an explicit `publish_release: true` input on `main`; other branches cannot publish. The workflow reads `versionName`, creates the corresponding `v<versionName>` tag, and publishes the signed APKs. An existing Release for that version is skipped.

Release notes are published from `CHANGELOG.md`, including the current and previous version entries. The workflow retains existing Releases and version tags. The 1.0.0 changelog entry corresponds to the original `v1.0` release; its tag and download links remain unchanged.

Publishing runs are not canceled by later pushes. GitHub may replace an older queued run with a newer run on the same ref; the release job publishes only the commit selected by an explicitly requested publishing run.
