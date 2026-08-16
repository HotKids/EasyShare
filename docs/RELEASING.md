# 发布说明

Easy Share 使用 GitHub Actions 构建并签名正式 APK。release keystore 不存放在仓库中，Workflow 只通过 GitHub Actions Secrets 在临时目录中恢复密钥，并在任务结束后删除。

## 签名 Secrets

仓库需要配置以下 Secrets：

- `EASY_SHARE_KEYSTORE_BASE64`
- `EASY_SHARE_KEYSTORE_PASSWORD`
- `EASY_SHARE_KEY_ALIAS`
- `EASY_SHARE_KEY_PASSWORD`

本机 keystore 应离线备份。GitHub Secrets 无法作为可靠的密钥备份，也无法在保存后读取明文。丢失用于直接分发 APK 的签名私钥，将无法继续为现有安装提供无缝更新。

## 日常构建

推送到 `main` 后，Workflow 会运行单元测试、Lint 和 Release 构建，并上传以下 artifact：

- `easy-share-universal.apk`
- `easy-share-arm64.apk`
- `SHA256SUMS`

Pull Request 只执行验证，不读取签名 Secrets，也不生成正式签名包。

## 创建 Release

1. 更新 `app/build.gradle.kts` 中的 `versionCode` 与 `versionName`。
2. 确认默认分支的 CI 通过。
3. 创建并推送与版本一致的标签，例如：

```bash
git tag v0.1
git push origin v0.1
```

标签 Workflow 会验证、签名并创建对应的 GitHub Release。

## 签名迁移提醒

正式 release key 与早期本地 debug key 不同。设备从 debug 签名版本迁移到正式版本时，需要先卸载旧版；完成一次迁移后，后续正式版本可以直接覆盖升级。
