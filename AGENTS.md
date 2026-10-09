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
- 锁序：台账写入必须在 `PENDING` 锁之外，不得在 `removeIf` 判定式里写台账。

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
| `Object.toString` 例外 | `InitFix.allowedStringCoercion`、`isInertProducer`、`isIntermediateObjectToString`（`effectReason` 在白名单之后、黑名单之前调用） |
| 测试夹具（Java） | `scratch/hstest/src/InitFixOracle.java`（内存 `javac`） |
| 测试夹具（Kotlin） | `scratch/hstest/ktfix/v1/`、`v2/` 下的 `.kt`，由 `ktfixV1`/`ktfixV2` source set 编译 |

## 5. 实现状态（截至 `881d93f0` 及其后的测试提交）

状态以本表为唯一来源。`✅` 已实现，`🔶` 部分，`⬜` 未实现。

| 条目 | 状态 | 备注 |
|:--|:-:|:--|
| 五条不变量 | ✅ | |
| `ClassHierarchyOracle` | ✅ | |
| `FieldLedger` | ✅ | 六种情形记账 |
| T0 零值等价 | ✅ | |
| T1 编译期常量 | 🔶 | 静态 `ConstantValue` 有专用通道 |
| T2 纯计算切片 | 🔶 | |
| T3 拒绝 | ✅ | |
| T4 `@HotswapInit` | ⬜ | |
| 效应掩码 | 🔶 | 仅 bit 3/6 与 bit 4/5 子集；另有 Kotlin `trim` 家族的 `Object.toString` 窄例外 |
| 参数回溯不可变证明 | ✅ | |
| 多根构造器共识 | ✅ | |
| 每字段独立静态方法 + `PatchDriver` | ✅ | |
| 条件 CAS / 合成字段过滤 | ✅ | |
| `@HotswapReinit` 字段级覆写 | ✅ | |
| JVMTI 堆检索 | ✅ | 仅 Native 底座 |
| 失败策略矩阵 | 🔶 | "放弃提交阶段 C"依赖两阶段 |
| 跨类批次拓扑排序 | ⬜ | 类内拓扑已实现 |
| `PatchPlan` 基线指纹 | ⬜ | |
| 两阶段 Schema-First | ⬜ | 当前是单阶段 |
| 构造器尾部插桩 | ⬜ | |
| Kotlin `object` 单例形态 | ✅ | 已用真实 kotlinc 产物验证（属性为静态字段、初始化在 `<clinit>`） |
| Java 单例形态（enum / Holder） | ✅ | 无需专门识别，走普通实例/静态路径 |
| Kotlin `trim`/`trimStart`/`trimEnd` | ✅ | 已用真实 kotlinc 产物验证 |
| 其它 Kotlin 行为（`?.`、`?:`、`let`、`apply`、主构造属性回溯、父类构造器委托） | ⚠ | **仅经手写 Java 仿真验证，未用 kotlinc 真实产物验证**；不要据此推断 Kotlin 真实字节码的行为 |

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

| 修改内容 | 先读 |
|:--|:--|
| 判决分层、效应检查、参数回溯、已知限制、单例与 Kotlin 字节码事实、测试夹具机制 | `docs/initfix/01-safety-gate.md` |
| 依赖闭包、成环、台账、失败策略 | `docs/initfix/02-closure-and-ledger.md` |
| 补丁驱动、写入协议、`@HotswapReinit` | `docs/initfix/03-runtime-driver.md` |
| 两阶段、基线指纹、构造器插桩、路线图（未实现） | `docs/initfix/04-target-design.md` |
| JVMTI 堆遍历 | `docs/initfix/05-jvmti-heap.md` |
| 为什么这么设计、历史缺陷 | `docs/initfix/06-decisions.md` |
