# 第三方依赖

MIT 许可证适用于本项目自身源代码。第三方依赖遵循其各自的许可证；依赖版本以 Gradle 配置为准。

主要依赖包括 AndroidX / Jetpack Compose、Room、WorkManager、Kotlin / kotlinx.coroutines、Jafama、JUnit 与 org.json。分发构建产物时需要保留适用的许可证和版权声明。

高德地图 SDK 通过 Gradle 获取，不由本仓库重新授权。地图 Key 由构建者自行申请和配置；使用该 SDK、地图数据与服务需要遵守其授权、服务条款和隐私说明。

地点名称反查还可能调用 Android 系统地理编码、OpenStreetMap Nominatim 或可选的高德 REST 服务；AI 回顾使用用户配置的服务。这些在线服务的授权、限制和数据处理规则由各服务提供方规定。
