# 发布说明

Easy Share 使用 GitHub Actions 构建并签名正式 APK。release keystore 不存放在仓库中，Workflow 只通过 GitHub Actions Secrets 在临时目录中恢复密钥，并在任务结束后删除。

## 签名配置

Gradle 的 Release 签名优先读取环境变量，其次读取用户目录下的 `~/.gradle/gradle.properties`。支持以下键：

- `SIGNING_KEYSTORE_PATH`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_PASSWORD`
- `SIGNING_KEY_ALIAS`

四项均有效时使用正式 Release 签名；任意一项缺失或 keystore 不存在时自动回退到 debug 签名，因此新 clone 的项目无需私钥也能直接构建。正式构建不要使用 Gradle 的 `--info` 或 `--debug` 日志级别。

本机构建应从系统钥匙串读取密码并通过环境变量注入，不在项目或用户属性文件中落盘。CI 使用以下 Repository Secrets：

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_PASSWORD`
- `SIGNING_KEY_ALIAS`

本机 keystore 应离线备份。GitHub Secrets 无法作为可靠的密钥备份，也无法在保存后读取明文。丢失用于直接分发 APK 的签名私钥，将无法继续为现有安装提供无缝更新。

## 日常构建

推送到 `main` 后，Workflow 会运行单元测试、Lint 和 Release 构建，并上传以下 artifact：

- `easy-share-universal.apk`
- `easy-share-arm64.apk`
- `SHA256SUMS`

Pull Request 使用 debug 回退签名执行完整 Release 构建，不读取签名 Secrets，也不生成正式签名 artifact。

## 自动创建 Release

1. 更新 `app/build.gradle.kts` 中的 `versionCode` 与 `versionName`。
2. 确认 Pull Request 的 CI 通过。
3. 将版本变更合并到 `main`。

`main` 的 Workflow 会读取 `versionName`，自动创建对应的 `v<versionName>` 标签，使用正式签名构建 APK，并发布 GitHub Release。同一版本的 Release 已存在时会安全跳过，不会重复发布。手动运行 Workflow 只构建并上传 artifact，不会创建 Release。

新 Release 创建成功后，Workflow 会自动删除此前的 GitHub Releases 及其 `v<数字>` 发布标签，仓库只保留最新 Release 和当前版本标签。清理步骤不会在新 Release 创建失败时执行，也不会删除其他用途的标签。

`main` 的发布任务不会取消正在运行的发布；如果短时间内连续推送多次，GitHub 可能用较新的等待任务替换较旧的等待任务，最终会以最新的 `main` 和 `versionName` 为准发布。

## 签名迁移提醒

正式 release key 与早期本地 debug key 不同。设备从 debug 签名版本迁移到正式版本时，需要先卸载旧版；完成一次迁移后，后续正式版本可以直接覆盖升级。
