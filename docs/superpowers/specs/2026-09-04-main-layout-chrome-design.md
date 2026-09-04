# 主页操作区外移设计

日期：2026-09-04  
范围：仅调整主页（`MainActivity`）的操作入口和布局，不改行情拉取、监控规则求值、前台服务。

## 背景

当前主页从上到下堆了：股票代码输入、检测间隔、监控规则按钮、开始/停止。列表和详情被挤到下面。这些操作不需要常驻内容区。

## 目标

主页只保留盯盘内容（指数、列表、详情）。加自选、改间隔、监控规则、开始/停止改走标题栏和悬浮按钮。

## 非目标

- 不改 `QuoteMonitor` 的加自选、存间隔、轮询、前台服务行为
- 不把设置做成独立 Activity（目前只有检测间隔一项）
- 不做底部操作栏
- 不做仪器 UI 测试
- 不改监控规则页本身

## 信息架构

```
标题栏：应用名 | 监控规则(铃铛) | 加号 | 更多 → 设置
内容区：指数 + 排序 | 列表 | 详情卡
右下角：开始/停止切换 FAB
```

## 标题栏

左侧标题仍是应用名。右侧三个入口，从左到右：

1. **铃铛**：打开现有 `AlertRulesActivity`，与现在主页「监控规则」按钮同一目的地。
2. **加号**：弹出添加自选对话框。
3. **更多菜单**：仅一项「设置」，弹出检测间隔对话框。

图标需要 `contentDescription`（监控规则、添加自选、设置）。

## 添加自选对话框

- 一个代码输入框，确认 / 取消。
- 校验与现在 `addCode` 完全相同：空、无效、重复。成功后关闭对话框并清空输入。
- 成功、失败提示仍走现有底部 Snackbar / `UiEvent`（已添加、代码无效等），不改 ViewModel 事件模型。

## 设置对话框

- 只有「检测间隔（秒）」一项。打开时填入当前已保存值（`uiState.intervalSeconds`）。
- 确认后调用现有 `saveInterval`，立即持久化；下次点 FAB 开始检测用新间隔。
- 检测进行中该项禁用，不能改间隔。与现在输入框在 `isRunning` 时禁用同一条规则。
- 取消不写回。

## 悬浮按钮

- Material 圆形 `FloatingActionButton`，固定右下角。
- 未检测：播放图标，点击 `startPolling`（传入已保存间隔，不再从主页输入框读取）。
- 检测中：停止图标 + 更醒目颜色（error / 强调色），点击 `stopPolling`。
- `contentDescription` 随状态为「开始检测」/「停止检测」。
- 空自选时仍可点开始，结果与现在相同（`EmptyWatchlist` 状态文案）。
- 底部/右侧安全区使用现有 `applyEdgeToEdgeInsets(root, toolbar, content, fab)`，与监控规则页 FAB 一致。

## 状态展示

- 「未开始 / 检测中 / 已停止」不再用内容区大字表达，改由 FAB 图标表达。
- 列表标题下保留一行小字，展示：
  - 午休/收盘/未开盘只更新一次（`SessionOnce`）
  - 空自选、解析失败、网络错误
- 「最近更新」仍留在列表标题旁。
- 加自选相关提示继续用 Snackbar，不塞进这行小字。

## 内容区

主页去掉：代码输入、添加按钮、间隔输入、监控规则按钮、开始/停止双按钮、居中大号状态。

从上到下只保留：

1. 列表标题 + 最近更新 + 上证指数 + 排序
2. 列头 + 自选列表（左滑删除、点选详情不变）
3. 点选后的详情卡

`ScrollView` / 列表底部 padding 至少留出 FAB 高度（约 88dp，与监控规则列表一致），避免最后一行和详情卡被挡住。

根布局对齐监控规则页：外层 `CoordinatorLayout`，内层标题栏 + 可滚动内容，FAB 作为 `CoordinatorLayout` 子视图贴右下。

## 业务逻辑边界

`QuoteMonitor` / `MainViewModel` 现有方法保持可用：

- `addCode(raw)`
- `saveInterval(intervalText)`
- `startPolling(intervalText)`
- `stopPolling()`

FAB 开始检测时传入 `state.intervalSeconds.toString()`，不在 Activity 里另存一份间隔。不新增 `startPolling()` 无参重载。

## 文件

新建：

- `app/src/main/res/menu/menu_main.xml`
- `app/src/main/res/layout/dialog_add_stock.xml`
- `app/src/main/res/layout/dialog_settings.xml`
- 播放 / 停止矢量图标（若工程里还没有）

修改：

- `activity_main.xml`：去掉常驻表单和双按钮，加上 FAB 与状态小字位置
- `MainActivity.kt`：菜单、两个对话框、FAB 绑定；删除对 `etStockCode` / `etInterval` / `btnStart` / `btnStop` / `btnAlerts` 的引用
- `strings.xml`：菜单、对话框、FAB 描述
- `EdgeToEdge.kt`：无需改 API，主页改为传入 `fab`

可复用：`ic_add.xml`、`ic_notifications.xml`。

## 测试

保留 `MainViewModelTest` 里加代码、存间隔、开始/停止、空自选用例。

新增或补强：

1. 使用已保存间隔启动（模拟 FAB：先 `saveInterval("8")`，再 `startPolling` 传入该保存值，轮询按 8 秒而不是默认 5 秒）。
2. 检测进行中 `saveInterval` 仍由 ViewModel 接受写入或由 UI 禁用——**产品规则是检测中不能改间隔**。实现上以 UI 禁用为准，不在 ViewModel 里静默丢弃，避免和现有 `saveInterval_persistsWithoutStarting` 冲突。

不做 Espresso / 截图测试。

## 验收

- 主页打开后看不到代码输入、间隔输入、监控规则大按钮、开始/停止双按钮。
- 标题栏能进监控规则、能加股票、能改间隔。
- 间隔改完后点 FAB 开始，使用新间隔。
- 检测中 FAB 为停止态，设置里间隔不可编辑。
- 点停止后 FAB 回到播放态。
- 列表、详情、左滑删除、排序、上证指数、最近更新均可用。
- 检测中划掉页面后前台服务仍运行（现有行为，回归时点一次即可）。
