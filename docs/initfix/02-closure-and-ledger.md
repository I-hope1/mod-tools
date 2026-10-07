# 02 依赖闭包、成环检测、台账与失败策略

适用：修改依赖判定、成环检测、`FieldLedger`、失败策略时阅读。

## 1. 依赖闭包

**规则**
- 候选字段集合 = 本轮 Diff 新增字段 ∪ 台账未决字段 ∪ 注解字段。
- 依赖判定基准 = 候选集 ∖ T0 零值字段。零值字段在存量实例上本来就是默认值，读取它不构成阻断（否则 `a = f(b)`、`b = 0` 会被误杀）。
- 前提：T0 按位比较，`-0.0f`/`-0.0d`/`NaN` 不会被 `zeroInstanceFields`/`zeroStaticFields` 收录，仍参与依赖阻断。
- 依赖闭包迭代到不动点：被拒字段会让读取它的字段在下一轮被连带拒绝。

**成环检测（✅，类内）**
- `detectCycle` 在闭包不动点**内部**反复执行。发现环就拒绝环成员，并置 `changed` 重跑闭包，让连带拒绝继续传播。
- `cycleMembers` 用迭代式 Tarjan 求强连通分量，**只拒绝环成员**，不整组拒绝。环成员的边定义与 `topoSortFields` 同源，保持口径一致。
- 类内拓扑排序已实现；**跨类批次拓扑排序未实现**（见 `04-target-design.md`）。

**出口后置闭合校验（主防线）**
- 最终放行集合必须对依赖闭合：每个放行字段读到的"本轮受补字段"，都必须在放行集合内（T0 零值字段除外）。
- 判定基准必须是**两侧并集**：`depInstance ∪ depStatic`。只传实例侧会漏掉切片里的 `GETSTATIC`，而"实例字段读静态字段"正是跨组漏网的主要形态。
- 违反即**整类拒绝并打 error**。这道校验不依赖各阶段顺序是否正确，是最后兜底。

**诊断文案**
- 环成员：`cyclic dependency among static fields: sa, sb`
- 被连带字段：`dependency: reads new static field 'sa' which is not patched`
- 两者都进报告和台账，不要让修好的漏洞变成另一种静默拒绝。

**运行期**
- 静态字段任务失败后，会连带跳过读取它的实例字段并记台账（`dependencyOf` 同时统计静态侧；静态任务先于实例任务执行，失败已落入共享的 `failedFields`）。

## 2. `FieldLedger`（✅）

**作用**：阶段 A（或当前单阶段 Redefine）提交后类结构不可逆。未成功修补的字段必须记账，下一轮重新纳入候选，避免永久遗忘。

**数据结构**
- 键：弱引用 `Class<?>`；值：纯字符串（字段名 + 原因）。
- 不持有类引用，与 `PENDING`/`REPORTS` 同策略。

**必须记账的六种情形**
1. 分析期被拒（`REJECTED`）
2. 运行期补丁抛异常
3. 依赖字段失败而跳过
4. Redefine 本身失败（计划已生成但一个都没补）
5. `buildPatch` 规划期抛异常（原因 `patch generation failed: ...`；`InitFix.transform` 的 `catch` 分支，此处 Redefine 仍会推进）
6. TTL 清扫丢弃待补补丁

**出账条件**
- `ACCEPTED` 且驱动未报错
- 判定为 `NOTHING_TO_PATCH`
- 字段已从新版本消失

**实现约束**
- 已入账条目不被降级覆盖：第 5 种情形若该字段已有条目，保留更具体的原因。
- TTL 清扫（`cleanupStalePatches`）丢弃超时补丁时，把其中**尚未补上**的字段记入台账，原因 `pending patch expired before apply (age=Ns)`，用 `putIfAbsent`。字段名取自待补补丁的计划（与 `afterRedefineFailed` 同源）。
- 清扫线程不得新增对 `Class` 的强引用：清单里持有 `WeakReference<Class<?>>`，已被回收的条目在锁外直接跳过。
- **锁序**：台账写入在 `PENDING` 锁之外完成，不要在 `removeIf` 判定式里直接写台账。
- 台账只负责"不遗忘"，候选字段回流后仍走同一套安全门。

## 3. 失败策略矩阵（🔶）

| 策略                     | 行为                                                                                                                 | 状态 |
|:-------------------------|:---------------------------------------------------------------------------------------------------------------------|:-----|
| `ABORT_ON_FATAL`（默认） | 单字段逻辑异常跳过；触发 `LinkageError`、VM 崩溃性故障，或 HIGH 风险（非零基本类型）字段被拒时，中止并放弃提交阶段 C | 🔶   |
| `ABORT_ON_ANY`（严格）   | 任何字段修补失败即中止全流程                                                                                         | 🔶   |

当前已具备：单字段失败跳过、依赖失败跳过、`LinkageError` 熔断。"放弃提交阶段 C"依赖两阶段协议，尚未实现。

## 4. `LinkageError` 熔断的例外

- `HotswapBridge.bootstrap` 会把任何 `Throwable` 包成 `BootstrapMethodError`（`LinkageError` 子类）。单字段 bridge 解析失败（如 `findField` 找不到字段）在驱动侧看起来与"类结构被破坏"相同。
- 一律熔断会中止所有字段、所有实例，违背"每字段一个方法、失败隔离"。
- 判定依据是 **cause 而非类型**：只有"`BootstrapMethodError` 且 cause **不是** `LinkageError`"才可隔离。其余（含包装了 `LinkageError` 的 BME、所有其它 `LinkageError`）照旧熔断。
- 判定取窄：把系统性故障误当可隔离，会继续修补一个已不可信的类，代价高于过度中止。
