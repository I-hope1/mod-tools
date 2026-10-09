# InitFix 开发规则

InitFix：热更新（Redefine）后，为**新增字段**初始化存量实例和静态环境的系统。
修改 InitFix 相关代码前先读本文件；细节按文末索引按需读取。

## 1. 作用域

- 只处理本次 Redefine **新增声明的字段**（存量实例 + 静态）。
- 禁止实现：修改已有字段初值、删除字段、修改方法签名、动态修改字段描述符类型。
- 唯一例外：字段标 `@HotswapReinit(mode = OVERWRITE)` 时，才允许覆写存量值。
- Android ART 与 `$extras` 扩展槽属于独立子项目（Engine II），不在此处理。
- 运行时前提：
  - JBR 17/21+：`Lookup.defineHiddenClass`（NESTMATE）+ `jdk.internal.misc.Unsafe`
  - DCEVM/TravaJDK 8/11：`Unsafe.defineAnonymousClass` + `sun.misc.Unsafe`

## 2. 五条不变量（任何改动都不得破坏）

1. **未初始化守卫**：`UNSAFE.shouldBeInitialized(clazz) == true` 时直接跳过修补。
2. **`PENDING` 弱键**：`synchronizedMap(WeakHashMap<Class<?>, PendingPatch>)`。`PendingPatch` 不得持有 `Class<?>` 强引用；保留 5 分钟 TTL 清扫（`PENDING_TTL_NANOS`）。
3. **`PatchReport` 解耦**：只存类名字符串和不可变枚举，不得持有 Class 或 ClassLoader。
4. **`LinkageError` 熔断**：补丁抛 `LinkageError` 立即中止当前类。唯一例外：`BootstrapMethodError` 且 `getCause()` **不是** `LinkageError`，视为单字段链接失败，只隔离该字段。判定保持窄。
5. **`afterRedefineFailed`**：Redefine 失败时立即注销并弹出暂存补丁。

## 3. 编码规则

**安全优先**
- 拿不准就拒绝。宁可漏补，不可静默写入过期值。
- 切片（含递归调用闭包）禁止调用"已存在但本次被修改"的方法。
- `Object.toString` 的例外只允许 Kotlin `trim`/`trimStart`/`trimEnd` 展开形态（条件见 `docs/initfix/01-safety-gate.md` §2.1）。`Object.hashCode` 不得放宽；不得新增"任意静态方法"作为放行来源。
- 判定逻辑取不到来源信息（`frames` 为 null 等）时必须拒绝（fail-closed），不要照抄 `builderMutatorReason` 里 `frames == null` 即放行的写法。
- 新增拒绝路径时，必须经 `InitFix.transform` 的统一出口发 `warn`。不要只调用 `log(...)`，它受 `DEBUG` 门控，默认不输出。
- 台账（`FieldLedger`）只负责"不遗忘"，不负责"放行"。台账字段回流后仍走同一套安全门。

**写入**
- 默认用条件 CAS（`KIND_CONDITIONAL`）。仅 `OVERWRITE` 使用 `KIND_FORCE`。
- `float`/`double` 一律走 raw bits（`compareAndSetInt/Long`），不要按名字探测 `compareAndSetFloat/Double`。

**分析期**
- 层级、修饰符、Nest 查询走 `ClassHierarchyOracle`，不得使用 `Class.forName` 或反射。
- 字段名（`String`）是唯一 Key，不支持同名异型字段。

**数据结构与兼容**
- 以 `Class` 为键的内部表用弱引用，值里不得反向持有 `Class`。不要换成 `java.lang.ClassValue`。
- 本模块保留 Android/ART 路径。引用 ART 低版本不存在的新 API 必须用反射探测，不得直接写进静态字段类型。
**状态管理规则**
- 状态信息唯一真相源：详细状态见 `docs/status.md`，高层高价值概括见本文件第 5 节。
- **严禁在 Javadoc、代码注释以及各 `docs/` 设计文档中重新混入实现状态信息**（如 `✅`、`[已实现]` 等易腐标记）。设计文档与 Javadoc 仅负责定义客观的设计规格、原理与交互契约；直接重复处一律转为链接。

## 4. 代码地图

| 关注点 | 位置 |
|:--|:--|
| 入口与主流程 | `InitFix.transform` / `buildPatch` / `applyPatch` |
| 提取与安全门 | `extractFieldInits`、`checkSafe`、`effectReason`、`blacklistedCallReason`、`builderMutatorReason` |
| 参数回溯 | `scanParamFields`、`sourceFieldNotImmutableReason`、`InitFix.NestView` |
| 离线层级 | `ClassHierarchyOracle` / `HierarchyTreeOracle`，底层 `AnnotationTransformer.HierarchyTree` |
| 台账 | `InitFix.LEDGER`、`getUnpatchedFields` |
| 逐实例驱动 | `runDependentInstanceTasks` / `runIndependentInstanceTasks`、`InstanceFailureBudget` |
| 写入桥 | `HotswapBridge`（`KIND_CONDITIONAL` / `KIND_FORCE`） |
| 注解 | `nipx.annotation.HotswapReinit` |
| 合成字段过滤 | `ClassDiffUtil.isInternalMarkerField` |
| 堆实例检索（Native） | `LibTool.getInstances` |
| 匿名类与局部类安全门 | `AnonClassAligner`、`LayoutGate`、`LocalClassGuard` |
| `Object.toString` 例外 | `InitFix.allowedStringCoercion`、`isInertProducer`、`isIntermediateObjectToString`（`effectReason` 在白名单之后、黑名单之前调用） |
| 测试夹具（Java） | `scratch/hstest/src/InitFixOracle.java`（内存 `javac`） |
| 测试夹具（Kotlin） | `scratch/hstest/ktfix/v1/`、`v2/` 下的 `.kt`，由 `ktfixV1`/`ktfixV2` source set 编译 |

## 5. 实现状态概要（详细参见 `docs/status.md`）

详细逐项状态表、落地位置与验收证据参见 [`docs/status.md`](docs/status.md)。本表仅提供核心机制的高价值概括：

| 机制 / 领域 | 状态 | 关键说明 |
|:--|:-:|:--|
| 五条核心不变量 | ✅ | 全面守护，零破坏 |
| 匿名类拓扑对齐 (Tier 1~4) | ✅ | 彻底废除 Tier 5；未匹配旧类保留为孤儿，未匹配新类分配安全新名 |
| 实例布局门与局部类止血门 | ✅ | `LayoutGate` 阻断字段变化；`LocalClassGuard` 阻断局部类位移 |
| InitFix 离线元数据 (`ClassHierarchyOracle`) | ✅ | 全程不触发类加载 |
| InitFix 待补台账 (`FieldLedger`) | ✅ | 六种异常与超时场景全覆盖 |
| T0 零值等价 / T3 显式拒绝 | ✅ | 零值零开销放行；危险切片安全门拒绝并在 `transform` 发 warn |
| T1 常量 / T2 纯计算切片 / 效应掩码 | 🔶 | 静态 `ConstantValue` 走专用通道；P0 效应黑白名单 + Kotlin `trim` 窄例外 |
| 参数回溯不可变证明 / 多根构造器共识 | ✅ | 条件 A（final）与条件 B（NestView 单写证明）；全根构造器指纹 100% 一致 |
| 伴生补丁类 / 逐实例驱动与失败配额 | ✅ | 独立静态方法隔离；按实例隔离；单字段失败配额封顶 8 次 |
| 条件 CAS 写入与跳过报告 | ✅ | 默认条件 CAS；float/double 走 raw bits；跳过计数汇总报告 |
| `@HotswapReinit` 存量覆写 | ✅ | 豁免 T0 与后续加工检查；不豁免切片安全门 |
| Native JVMTI 堆遍历 (`LibTool`) | ✅ | C++ 底座；Tag 隔离与全局互斥；`InstanceTracker` 字节码回退 |
| 两阶段重定义 / 构造器插桩 / 跨类拓扑 | ⬜ | 路线图设计（见 `docs/initfix/04-target-design.md`） |
| 真实 kotlinc 产物验证 | 🔶 | `object` 单例与 `trim` 家族已验证；其它 Kotlin 行为仅经仿真验证 |

## 6. 测试要求

- 回归任务：`hstestInitFixOracle`（挂在 `check`，当前 48 场景 / 318 断言，以实际输出为准）。需要 Mindustry 运行期依赖（`hstestImplementation`）。
- 必须通过 `./gradlew hstestInitFixOracle` 运行，不要直接 `java -cp`：Kotlin 夹具由 Gradle 先编译，输出目录经 `-Dnipx.ktfix.v1/v2` 传入。
- Kotlin 夹具的 v1/v2 输出目录**绝不能**进入任何 classpath（同名类会静默只命中其一，造成假绿）。
- 迭代期间只跑 `hstestInitFixOracle`，收尾时才跑一次 `./gradlew check`。
- 匿名类/对齐主题套件已从 `suite.sh`（`hstestRun`）迁到 JUnit：`./gradlew hstestJunit`（挂在 `check`）。入口用 `-Dhstest.javac8/11/17/21/25` 现编夹具，不再有 `expected-count.txt` 基线。
- `hstestJunit` 的测试数下限由 `build.gradle` 的 `hstestMinTests` 守卫（`verifyHstestTestCount` 读 XML 断言总数 ≥ 基线且 `skipped == 0`）：删除/禁用/跳过测试会让 `check` 变红，上调基线要有意识地改这一行。
- CI：`.github/workflows/hstest.yml`（`setup-java` 装 8/11/17/21/25 + `fromEnv`，关 auto-detect/auto-download，`hstest.requireJdks=true`；runner JDK 显式钉成 25）。首次 Linux 跑，Windows 假设（路径分隔符、GBK、`F:/` 默认值）可能暴露，按原因分类再处理。
- 新增或放宽安全门：必须有负向阻断断言。
- 修复缺陷：必须有先红后绿的回归断言。
- 新增或放宽安全门例外：除正向、负向用例外，必须做**变异检查**——故意放宽一处实现，确认对应用例会变红，再撤销。
- 新增测试注释和断言消息用英文，已有中文不改。涉及中文输出的任务需带 UTF-8 JVM 参数（`hstestInitFixOracle` 已配置）。
- 涉及 float/double CAS：必须在真实 JDK 8 上验证（oracle 跑在 JDK 25，区分不出两种实现）。

## 7. 按需文档

### InitFix 存量初始化
| 修改内容 | 先读 |
|:--|:--|
| 判决分层、效应检查、参数回溯、已知限制、单例与 Kotlin 字节码事实、测试夹具机制 | `docs/initfix/01-safety-gate.md` |
| 依赖闭包、成环、台账、失败策略 | `docs/initfix/02-closure-and-ledger.md` |
| 补丁驱动、写入协议、`@HotswapReinit` | `docs/initfix/03-runtime-driver.md` |
| 两阶段、基线指纹、构造器插桩、路线图（未实现） | `docs/initfix/04-target-design.md` |
| JVMTI 堆遍历 | `docs/initfix/05-jvmti-heap.md` |
| 为什么这么设计、历史缺陷 | `docs/initfix/06-decisions.md` |

### 匿名类拓扑对齐与安全门
| 修改内容 | 先读 |
|:--|:--|
| 核心不变量、改写范围契约、栈图处理 | `docs/topology/01-invariants-and-remapping.md` |
| 属性优先准入、SwitchMap 评估、保留名域与挂起避让 | `docs/topology/02-admission-and-reserved-names.md` |
| 层级交错流水线、Self Hash、调用链追溯、INV-1/INV-2 架构不变量 | `docs/topology/03-cascading-pipeline.md` |
| Tier 1~4 置信梯队、Tier 3 拓扑相等过滤、宿主级原子拒绝 | `docs/topology/04-tiers-and-rejection.md` |
| 典型场景端到端推演案例 | `docs/topology/05-walkthrough.md` |
| JBR-21/DCEVM 能力与限制、事务乐观预登记、数量与超时闸门、开关配置 | `docs/topology/06-runtime-and-perf.md` |
| 实例状态布局安全门（LayoutGate）、局部类编号漂移止血门（LocalClassGuard）、蜕变测试 | `docs/topology/07-layout-gate-and-risks.md` |
| 架构决策记录、偏离原因、JBR 8 组真机数据 | `docs/topology/08-decisions.md` |
