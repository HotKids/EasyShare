# 参与贡献

感谢你关注 Easy Share。提交改动前，请先确认问题能够在受支持的 Android 版本上复现，并尽量提供发送端与接收端的设备型号、系统版本和调试日志。

## 开发环境

- JDK 21
- Android SDK 37
- Android 12（API 31）或更高版本的测试设备

## 验证改动

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease
```

涉及传输协议、Wi‑Fi Direct、BLE 或通知状态的改动，应至少完成一次双向真机互传。请勿在 Issue、日志或提交中包含 Wi‑Fi Direct 密码、会话令牌、私钥或其他敏感信息。

## 提交建议

- 每个提交聚焦一个明确问题
- 对协议解析、安全边界和文件路径处理补充测试
- 保持现有互传联盟兼容性，除非变更已明确记录
- 不要提交 APK、AAB、keystore、签名密码、设备日志或本地验证截图
