# 快速量价监听设计

日期：2026-09-07  
范围：在现有监控规则里增加四个不填阈值的比较符。不改通知方式、前台服务、检测间隔。

## 目标

用户可以给自选股加上「快速放量 / 快速缩量 / 快速拉升 / 快速下跌」，用检测间隔的相邻行情判断，阈值写死在求值里。

## 非目标

- 不做可配置阈值或设置页
- 不把成交额当量
- 不把三口行情写入数据库
- 不改涨停、穿越等现有条件的语义

## 比较符

`AlertOperator` 新增（`needsValue = false`，与涨停同类）：

| 枚举 | 文案 |
| --- | --- |
| `VOLUME_SURGE` | 快速放量 |
| `VOLUME_SHRINK` | 快速缩量 |
| `PRICE_SURGE` | 快速拉升 |
| `PRICE_DROP` | 快速下跌 |

条件句式：`{股票名}  {比较符}`，例如「华汇智能  快速放量」。

添加条件时选中这些比较符：隐藏指标、阈值、对比另一只股票。持久化时 `metric` 固定为 `PRICE`（界面不用，和涨停一致）。Room 只存枚举名，无需迁移。

## 内置阈值

写死在 `AlertEvaluator`，本次不做成设置：

- 快速放量：本口新增成交量 ≥ 上一口新增成交量 × **2**
- 快速缩量：本口新增成交量 ≤ 上一口新增成交量 × **0.5**
- 快速拉升：本口现价相对上一口涨幅 ≥ **1%**
- 快速下跌：本口现价相对上一口跌幅 ≥ **1%**

涨跌幅按上一口现价计算：`(current - previous) / previous`。

## 行情窗口

腾讯字段 6 是全日累计成交量（手）。一口新增量 = 这一口字段 6 − 上一口字段 6。因此放量/缩量需要三口快照：更早一口、上一口、这一口。

拉升/下跌只用相邻两口现价。

`QuoteMonitor` 内存再留一份「上一口之前」的列表。每次拉取成功后：

1. `older = previousQuotes`
2. `previousQuotes = quotes`（更新前的当前列表）
3. `quotes = latest`（过滤后的自选）
4. 把 current / previous / older 交给 `AlertEvaluator.evaluate`

不写库。进程被杀或刚开始检测时，凑不齐窗口则不触发对应条件。

字段 6 缺失或无法解析时，该股该口的量条件不满足。增量为负（偶发脏数据）按不满足处理。

## 求值规则

`AlertEvaluator.evaluate` 增加参数 `older: List<QuoteSnapshot>`，与 `current` / `previous` 一样按 `requestCode` 建 map。`isSatisfied` 能读到这三口。现有调用处传入空列表，旧条件行为不变。

**拉升 / 下跌**

- 缺当前或上一口、上一口现价 ≤ 0：不满足
- 拉升：`(currentPrice - previousPrice) / previousPrice >= 0.01`
- 下跌：`(previousPrice - currentPrice) / previousPrice >= 0.01`

**放量 / 缩量**

- 缺三口任一、任一口字段 6 无法解析：不满足
- `prevDelta = previousVol - olderVol`，`currDelta = currentVol - previousVol`
- `prevDelta <= 0` 或不满足：不触发（避免开盘第一段从 0 变成任意量被当成放量）
- `currDelta < 0`：不满足
- 放量：`currDelta >= prevDelta * 2`
- 缩量：`currDelta <= prevDelta * 0.5`

现有 `needsValue == false` 的涨跌停逻辑保持不变；新比较符走量价窗口分支，不走涨跌停。

通知方式仍是规则上的仅一次 / 每次 / 冷却 10 分钟。

## 通知详情

- 拉升 / 下跌：`{句子}（当前 {现价}）`
- 放量 / 缩量：`{句子}（本口 {currDelta} 手 / 上一口 {prevDelta} 手）`

## 文件

修改：

- `app/src/main/java/com/example/gptest/ui/alert/AlertModels.kt`：四个比较符
- `app/src/main/java/com/example/gptest/ui/alert/AlertEvaluator.kt`：三口窗口 + 求值 + 详情
- `app/src/main/java/com/example/gptest/ui/QuoteMonitor.kt`：保留 older 行情并传入 evaluate
- `app/src/test/java/com/example/gptest/ui/alert/AlertEvaluatorTest.kt`：新用例
- 若 `evaluate` 签名变更：同步 `MainViewModelTest` 等调用处

不改：`AddConditionSheet` 的显隐逻辑（已按 `needsValue` 处理）、Room schema、设置页。

## 测试

先写失败用例再改求值。

1. 拉升：10.00 → 10.10 触发；10.00 → 10.09 不触发；无上一口不触发。
2. 下跌：10.00 → 9.90 触发；10.00 → 9.91 不触发。
3. 放量：更早/上一口/当前累计 100/200/400（增量 100 与 200）触发；100/200/350（增量 150）不触发。
4. 缩量：100/300/400（增量 200 与 100）触发；100/300/500（增量 200）不触发。
5. 缺第三口、或上一口增量 ≤ 0：放量/缩量不触发。
6. 现有涨停、穿越、GTE 用例仍通过。

`evaluate` 的调用方补一个 older 空列表即可保持旧行为。

## 验收

- 添加条件能选到四个新比较符，且不用填数字。
- 检测中，一口涨满 1% 会按规则通知方式提醒拉升；量增量达到上一口两倍提醒放量。
- 刚开始检测的前两口，放量/缩量不误报。
- 旧规则（涨停、现价涨到）行为不变。
