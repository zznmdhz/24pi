# 参与开发

欢迎通过 Issue 和 Pull Request 提交问题、建议和修复。安全问题使用 [私密报告渠道](SECURITY.md)。

## 开发检查

使用 JDK 17、Android SDK 35 和 Build Tools 35.0.0。

```bash
python3 scripts/check_privacy.py
./gradlew testDebugUnitTest --max-workers=2
./gradlew compileDebugAndroidTestKotlin lintDebug assembleDebug --max-workers=2
```

功能使用 `feature/` 分支，修复使用 `fix/` 分支。PR 请说明触发问题的场景、变化后的行为和实际执行的验证。只用合成数据构造测试；不要从个人观察包或设备导出中抽取时间、坐标、通知或应用记录。

## 数据安全

不要提交密钥、签名文件、数据库、真实记录、诊断包、截图中的个人内容或本机配置。发现误提交后，立即通过私密安全渠道报告；删除当前文件不能清除历史内容，暴露的凭据需要撤销或轮换。

保留采集缺口与原始记录。展示层可以重新计算投影，但不伪造测量、静默改写历史或把缺失当成零活动。

## 数据库与发布

Room 变更必须提供显式迁移与迁移测试，不使用破坏性回退。Android `versionCode` 必须递增，包名或签名调整必须说明对已有安装的影响。修改行为后同步 README、隐私说明和更新记录。

真机验证应使用备份后的独立测试安装；不要在个人生产档案上运行夹具注入。`scripts/device_checks.sh` 只允许模拟器。
