# Debug Session: accessibility-service-crash

## Problem Description
- 无障碍服务开启后显示"无法运行"
- 软件显示屏幕捕获成功但后续无法正常运行
- 退出后软件要求重新开启无障碍

## Hypotheses (待验证)

| # | Hypothesis | Observation Point | Status |
|---|------------|-------------------|--------|
| H1 | TouchService.onServiceConnected 未被调用 | TouchService 日志输出 | [PENDING] |
| H2 | 无障碍服务配置缺少必要声明 | AndroidManifest.xml 配置 | [CHECKED] |
| H3 | TouchService.instance 被错误清除 | 服务生命周期日志 | [PENDING] |
| H4 | 服务启动顺序导致依赖未就绪 | MainActivity 启动流程日志 | [PENDING] |
| H5 | Android 版本兼容性问题 | 设备 API 级别和错误日志 | [PENDING] |

## Instrumentation Applied

### 1. TouchService.kt
- 添加 `onCreate`, `onServiceConnected`, `onDestroy`, `onUnbind`, `onInterrupt` 详细日志
- 新增 `isConnected` 状态变量跟踪服务连接状态
- 新增 `isServiceReady()` 方法准确检查服务可用性
- 所有手势操作添加日志和状态检查

### 2. MainActivity.kt
- `checkPermissions()` 添加详细状态日志
- `getMissingPermissions()` 使用 `isServiceReady()` 替代 `isEnabled`

### 3. FloatWindowService.kt
- `executeAIMove()` 添加完整流程日志
- `executeMove()` 添加坐标计算和 TouchService 状态日志
- 使用 `isServiceReady()` 检查服务状态

## User Action Required

1. 重新编译安装 APK
2. 开启无障碍服务
3. 在 Logcat 中过滤以下 TAG:
   - `TouchService`
   - `MainActivity`
   - `FloatWindowService`
4. 点击"启动"按钮，观察日志输出
5. 报告以下关键日志内容:
   - `=== onServiceConnected START ===` 是否出现
   - `TouchService.isServiceReady` 的值
   - 任何 `=== onDestroy called ===` 或 `=== onInterrupt called ===`

## Status: [OPEN] - 等待用户反馈日志