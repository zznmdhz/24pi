# 24π · 人生记录

**把一天中手机能观测到的事实，整理成可以回看的个人时间档案。**

A local-first Android life log built with Kotlin, Jetpack Compose and Room.

[![Android checks](https://github.com/zznmdhz/24pi/actions/workflows/android.yml/badge.svg)](https://github.com/zznmdhz/24pi/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

24π 将应用使用、通知事件、地点停留、步数与采集缺口放在同一条时间轴上。无需账号，没有广告和自建云同步；原始记录保存在设备中。可选的在线地图、地名反查和 AI 回顾在用户启用后才使用网络。

## 可以做什么

- **时间**：按日回看应用、通知、步数和行程，逐层进入时间段与精确记录。
- **档案**：按应用、通知、地点和日期范围查询历史。
- **行程地图**：展示已记录路线和停留，定位中断保留虚线并排除其里程；在线底图不可用时仍可使用本机预览。
- **地点管理**：命名、标记、纠正与可撤销合并，保留原始记录。
- **AI 回顾**：使用自己的 HTTPS 服务、模型和 API Key；先预览聚合摘要，再主动发送请求。
- **数据管理**：AES-256-GCM 加密备份、CSV 导出、采集健康提示和可选诊断构建。

## 当前版本

公开源码基于 **0.19.1 / versionCode 36 / Room v9**。这是测试阶段的应用，主要面向 Android 15/16、HyperOS 2/3；最低支持 Android 14（API 34）。公开源码不附带预配置地图 Key 或维护者签名的 APK。

## 构建

需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。Gradle Wrapper 已包含在仓库中。

```bash
git clone https://github.com/zznmdhz/24pi.git
cd 24pi
# 使用 Android Studio 设置本机 SDK，或在 local.properties 中配置 sdk.dir。
./gradlew testDebugUnitTest --max-workers=2
./gradlew compileDebugAndroidTestKotlin lintDebug assembleDebug --max-workers=2
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。没有签名配置时，`assembleRelease` 生成未签名的 APK。自己的签名和地图配置见 [构建与签名](docs/BUILDING.md)。

**不要通过卸载已有安装来解决签名冲突。** 先在旧版创建并妥善保存加密备份。自行编译的 Debug APK 和个人签名 APK 通常不能覆盖使用其他证书签名的安装。

## 隐私边界

- 记录保存在应用沙盒；Android 系统备份关闭。数据库本身不单独加密，依赖系统文件加密和设备访问控制。
- 通知标题、正文默认不读取；只有用户单独同意后才保存。
- 地名反查、在线地图和 AI 服务各自有独立的使用入口。启用联网功能时，相关服务会接收必要请求数据。
- AI 的发送预览可能包含地点名称、应用名称、时长和用户问题。默认摘要不包含原始坐标、完整轨迹点或通知正文；API Key 使用 Android Keystore 加密存储，并排除在档案导出与备份之外。
- 可读 CSV 没有加密；诊断包也可能包含活动时间、应用信息和模糊位置。请勿上传到公开 Issue。
- 历史记录在应用内永久保留；关闭采集开关只影响之后的记录。

详细说明：[隐私说明](docs/PRIVACY.md) · [地图与隐私](docs/MAP_PRIVACY.md)

## 技术结构

```text
app/src/main/java/com/twentyfourpi/lifelog/
├── ai/          AI 配置、摘要与请求协议
├── backup/      加密备份与恢复
├── collector/   应用、通知、位置与步数采集
├── data/        Room、原始记录与可重算投影
├── debug/       可选观察日志与诊断导出
├── export/      CSV 导出
├── ui/          Compose 页面、地图与时间线
└── util/        时间与地理计算
```

[架构说明](docs/ARCHITECTURE.md) · [参与开发](CONTRIBUTING.md) · [安全报告](SECURITY.md)

## 使用限制

HyperOS 的后台限制、权限撤销、系统升级和定位条件都可能造成断档。应用明确展示缺失数据，不把未知当成零活动，也不补造路线。

自动测试不能替代真机验证；地图鉴权、覆盖升级、长期运行、耗电和窄屏交互仍需在目标设备确认。设备测试脚本仅接受模拟器，避免测试夹具写入个人档案。

## 开源许可

项目自身源代码采用 [MIT License](LICENSE)。第三方库、SDK 和在线服务保留各自的授权及服务条款，见 [第三方依赖](THIRD_PARTY_NOTICES.md)。
