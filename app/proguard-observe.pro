# observe 变体专用混淆配置（release 不读这份）
#
# 背景（0.17.4）：R8 8.9.35 全模式在 observe 变体上内部崩溃——「ERROR: R8: java.util.NoSuchElementException」，
# 无源码位置；同一份代码的 release 变体正常出包，把 android.enableR8.fullMode 关掉也正常，
# 说明落在全模式特有的那批优化/收缩流程里。已逐一排除：投影重建算法、投影数据类（keep 规则无效）、
# 诊断导出代码、首页/设置界面代码、枚举 unboxing。这是**变体级绕行**：只让内部诊断包不做优化，
# 用户实际使用的 release 包保持全模式全优化不变。
-dontoptimize
