# 架构

应用使用 Kotlin、Jetpack Compose、Room 与 WorkManager。`LifeLogApp` 初始化数据与采集组件，`MainViewModel` 为时间、档案和设置页面提供状态。

## 原始记录与派生视图

`collector` 使用 Android 系统的 UsageStats、通知监听、位置和计步能力，写入 Room。原始应用会话、通知生命周期、位置点、到访和采集缺口分别保存。

`data` 中的日路线、缺口投影和统计由原始记录重算。实测、推断与未知分别表达；轨迹中断使用虚线且不计入里程。地点修订与合并作为可撤销关系保存，不靠删除历史来修正展示。

`ui` 的时间页按日组织记录，档案页提供对象与范围查询，设置页管理来源、权限、联网选择、数据与 AI 配置。

## AI 与导出

`ai` 在本机计算摘要和日期范围，显示发送预览后通过用户配置的 HTTPS 服务请求回顾。配置与加密后的 API Key 保存在 Android 不参与备份的目录。

`backup` 提供密码加密备份与恢复；`export` 输出可读 CSV；`debug` 提供可选观察日志与诊断。Release 默认关闭完整观察工具，Observe / Debug 开启。

## 数据库

当前为 Room v9，schema 在 `app/schemas/`，显式迁移定义在 `LifeLogDatabase`。迁移测试位于 `androidTest`；更新 schema 必须保留原始数据并验证已有版本的升级路径。

## 构建与测试

发布签名和地图配置通过环境变量输入。公共 CI 无需维护者凭据，运行合成数据的单元测试、Lint、Debug 构建与设备测试代码编译。仪器测试使用隔离模拟器，不能把源码检查等同于 HyperOS 真机验收。
