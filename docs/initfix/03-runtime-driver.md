# 03 伴生类、补丁驱动与写入协议

适用：修改补丁发射、`applyPatch`、`HotswapBridge`、`@HotswapReinit` 时阅读。实现状态参见 `docs/status.md` 与 `AGENTS.md`。

## 1. 伴生类（Patch Emitter）

- 每个放行字段生成独立静态直线方法：实例 `init$F(LHost;)V`，静态 `initStatic$F()V`。
- 伴生类 `Host$$HotswapPatch` 为宿主的 hidden nestmate。
- 方法边界即异常边界：单字段失败只影响该字段及其下游。不要把多个字段的切片串进同一个方法，否则一行异常会跳过其后所有字段。
- 独立方法也避免了类加载期 StackMapTable 帧计算死锁。

## 2. 驱动（`PatchDriver` / `InitFix.applyPatch`）

**调度**
- 宿主侧逐字段驱动：`PatchPlan`/`FieldPatchTask` 按拓扑序执行。
- 获取 `MethodHandle` 时必须用 `asType` 适配为 `(Object)void`，再 `invokeExact`。
- 依赖失败的字段直接跳过并记失败。
- `LinkageError` 上抛熔断（单字段 indy 链接失败例外，见 `02-closure-and-ledger.md` §4）。

**按实例隔离**
- 每个 target 有局部 `failed`/`skipped` 集合，只有"本实例上确实失败的字段"才阻断本实例的下游。
- 局部集合只继承**静态侧**失败（静态字段全局唯一，失败时每个实例的下游都应跳过）。
- 全局 `failedFields`/`skippedFields` 仍是 `FieldLedger` 的输入，只做计数与记账，告警不按实例刷屏。

**失败配额（`InstanceFailureBudget`）**
- 确定性失败会让每个实例各失败一遍，日志与耗时放大 N 倍。
- 每字段每轮配额 `MAX_INSTANCE_FAILURES_PER_FIELD = 8`，用满后对后续实例放弃该字段。
- 放弃原因记为 `field failed 8 times, giving up for this round`，覆盖首次失败的原始异常文本。
- 配额不跨轮累积，下一轮重新给足。
- 回归断言：20 个实例全失败时，详细日志封顶 5 条，放弃原因可见，字段仍入台账。

## 3. 写入协议（`HotswapBridge`）

**条件写（`KIND_CONDITIONAL`，默认）**
- 解析物理偏移量，仅当内存值为类型默认零值时写入。
- `float`/`double`：用 `compareAndSetInt/Long` + `floatToRawIntBits`/`doubleToRawLongBits`（经 `filterArguments` 适配签名）。不要按名字探测 `compareAndSetFloat/Double`（JDK 8 没有，会退化成无条件写）。
- 与 T0 口径一致：比较位模式。`-0.0f`（`0x80000000`）和各类 NaN 视为"已有值"而跳过；只有真正的 `0.0f`/`0.0d` 才被写。

**跳过必须被报告**
- 条件写模板保留 CAS 的 boolean 结果，调用点用 `filterReturnValue` 接计数器（键 `owner#field`，值 `[written, skipped]`），`applyPatch` 结束后 drain 汇总。
- 跳过不是失败：不进台账，不参与失败告警。
- 首轮报 `warn`；台账重试轮降为 `info`（重试轮的跳过多半来自上一轮已补过的实例）。
- 无 CAS 语义的无条件回退（子字模拟、volatile put）统一返回 `true`，不产生跳过计数。

**强制写（`KIND_FORCE`）**
- 无条件 volatile 写，仅用于 `@HotswapReinit(mode = OVERWRITE)`。
- 必须经 `Unsafe`，不能用 `putfield`：`final` 字段只允许在声明类构造器里赋值，补丁是 hidden nestmate，直接 `putfield` 会在链接期抛 `IllegalAccessError`。因此 final 字段同样适用。

## 4. `@HotswapReinit`

- 注解：`nipx.annotation.HotswapReinit`，`Mode = {CONDITIONAL, OVERWRITE}`。
- `OVERWRITE` 豁免：T0 判定、"构造器读过 / 别处写过"的后续加工检查。
- **不豁免**：切片安全门。覆写只决定"要不要写"，不能让读脏的值变正确。
- 触发豁免时打 `warn`，并写入 `PatchReport` 的 `FieldDecision.warnings`：构造器里的那部分用法不会被重放。
- 补丁只在热更线程上单线程执行，按线程的值要注意这一点。

## 5. 合成字段过滤

- `ClassDiffUtil` 统一过滤携带 `ACC_SYNTHETIC` 或 `$nipx$` 前缀的标记字段（如 `forceStaticLambdas` 生成的 `$nipx$lambdasForced`），避免与业务新增字段混淆。
