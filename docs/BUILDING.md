# 构建与签名

使用 JDK 17、Android SDK Platform 35、Build Tools 35.0.0。`local.properties` 只保存本机 SDK 路径，并已被 Git 忽略。

## 不使用个人凭据的构建

```bash
./gradlew testDebugUnitTest --max-workers=2
./gradlew compileDebugAndroidTestKotlin lintDebug assembleDebug --max-workers=2
./gradlew assembleRelease assembleObserve --max-workers=2
```

Debug 使用本机生成的调试签名。没有完整的发布签名环境变量时，Release / Observe 生成未签名 APK。

## 可选地图配置

在线高德底图读取环境变量 `AMAP_ANDROID_KEY`。留空仍可编译；在线地图需要自己的有效配置，本机档案与轨迹预览无需地图 Key。

Android 地图 Key 需要与所用包名和签名证书匹配。设置页的高德 Web REST Key 与此变量是独立配置。不得把有效 Key 写入源码、提交、Issue 或构建日志。

## 自己的发布签名

将以下变量安全地注入构建环境，值不写入 Git：

| 环境变量 | 内容 |
| --- | --- |
| `TWENTYFOURPI_KEYSTORE_PATH` | 自己的密钥库路径 |
| `TWENTYFOURPI_STORE_PASSWORD` | 密钥库密码 |
| `TWENTYFOURPI_KEY_ALIAS` | 签名别名 |
| `TWENTYFOURPI_KEY_PASSWORD` | 私钥密码 |
| `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` | Android SDK 路径 |

```bash
./scripts/build_signed.sh
```

脚本不会读取维护者的本机钥匙串。自己的签名必须妥善备份；使用其他证书签名的 APK 通常不能覆盖已有安装。不要卸载个人安装来解决签名冲突，先保存加密备份并规划迁移。

## 设备检查

```bash
./scripts/device_checks.sh
```

该脚本仅允许隔离的 Android 模拟器，并会写入合成测试档案。目标手机上的地图、系统后台策略、升级和耗电需要单独验证。
