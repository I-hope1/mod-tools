# InitFix 核心架构与技术规格说明书 (RFC - 闭环终版)
### —— 基于两阶段 Schema-First 协议、端端解耦与 JVMTI 实例检索的新增字段初始化系统

---

## 1. 系统定位、范围与基线不变量

### 1.1 目标运行时支持矩阵
* **严格前置条件**：本系统依赖支持运行时类结构（Schema）增删字段的 JVM 运行时：
  * **JBR (JetBrains Runtime 17/21+)**：伴生类加载采用 `Lookup.defineHiddenClass`（`NESTMATE` 模式）；底层依赖 `jdk.internal.misc.Unsafe` 全类型原子 CAS。`[已实现-Java基线]`
  * **DCEVM / TravaJDK (JDK 8/11)**：伴生类加载采用 `sun.misc.Unsafe.defineAnonymousClass`；底层采用 `sun.misc.Unsafe`（子字类型 volatile 降级）。`[已实现-Java基线]`
* **作用域边界与非目标**：
  * **In-Scope**：仅处理本次 Redefine 中**新增声明的字段**在存量实例与静态环境中的初始化；
  * **Out-of-Scope（坚决拒绝）**：修改已有字段初值、删除字段、修改方法签名。**严禁动态修改字段描述符类型**（破坏类型系统且静默丢数据，违背核心原则）；
  * **存量重置扩展（Opt-in Scope Extension）**：默认不处理已有字段。仅当字段显式标注 `@HotswapReinit(mode = OVERWRITE)` 时，作为显式声明的受控扩展，允许覆写存量状态；`[已实现-字段级]`（注解：`nipx.annotation.HotswapReinit`，`Mode = {CONDITIONAL, OVERWRITE}`；写入经 `HotswapBridge.KIND_FORCE` 无条件 volatile 写，因此 final 字段同样适用；同时豁免 §4.1 T0 与"构造器读过 / 别处写过"的后续加工检查，切片安全门不豁免；**触发豁免会打 warn 并在 `PatchReport` 的 `FieldDecision.warnings` 里标记** —— 构造器里的那部分用法不会被重放，且补丁只在热更线程上单线程执行）
  * **平台边界**：Android ART 环境与用户态 `$extras` 扩展槽方案作为独立子项目（Engine II）剥离，不在本规范涉及。`[已确认]`

### 1.2 核心基线不变量 (Invariants)
1. **类未初始化守卫（`isInitialized`）**：若宿主类尚未被应用程序触发初始化（`UNSAFE.shouldBeInitialized(clazz) == true`），补丁系统**直接跳过修补**。未初始化的类将在后续首次访问时自然执行包含新字段的新 `<clinit>`。`[已实现-Java基线]`
2. **`PENDING` 弱键无环缓存**：采用 `Collections.synchronizedMap(new WeakHashMap<Class<?>, PendingPatch>())`。`PendingPatch` **严禁持有 `Class<?>` 强引用**，仅持有字节码 `byte[]` 与弱引用实例快照；保留 5 分钟 TTL（`PENDING_TTL_NANOS`）超时清扫机制。`[已实现-Java基线]`
3. **`PatchReport` 引用解耦**：报告仅存储类名字符串与不可变决策枚举，绝不持有宿主类或其 ClassLoader 引用，防止 Metaspace 泄漏。`[已实现-Java基线]`
4. **`LinkageError` 断路熔断**：一旦补丁执行抛出 `LinkageError`，表明类元数据假设已被底层打破，系统立即熔断中止当前类的修补，防止 JVM 崩溃。`[已实现-Java基线]`
5. **重定义失败补偿（`afterRedefineFailed`）**：底层 Redefine 事务异常退出时，立即注销并弹出暂存的补丁。`[已实现-Java基线]`

---

## 2. 模块拓扑与输入契约 (Host/Target 分离)

系统采用 **Analyzer 库化设计**：分析器核心是**无外部状态依赖的纯函数**，既可直接嵌入 JVM Agent 在本地进程内运行（满足 IDE 单机快速热更），也可打包在构建端（Gradle / PC）执行。`[目标规范]`

```
 [旧 Class 字节码] ──┐
 [新 Class 字节码] ──┼─► [ Analyzer 库 (纯函数) ]
 [ClassHierarchyOracle]──┘         │
                                   ▼
 [策略配置 / 效应掩码] ──► [ Planner (规划器) ]
                                   │
                                   ▼
                         [ Emitter (字节码生成) ]
                                   │
                                   ▼
                        Serializable PatchPlan (包含前置指纹与版本向量)
───────────────────────────────────┼──────────────────────────────────────────
 (执行隔离边界)                     ▼
                        [ Target / Runtime Driver ]
                          ├── 基线版本向量校验 (Hash & Fingerprint Check)
                          ├── Schema-First 两阶段重定义驱动器
                          ├── 多态堆实例收集器 (JVMTI Tag-based Heap Scanner)
                          └── 伴生类加载与逐字段 MethodHandle 隔离执行器
```

### 2.1 离线元数据契约：`ClassHierarchyOracle` `[原型/验证]`
为彻底消除分析期调用 `Class.forName` 导致的类加载死锁与对未链接新类的误拒，抽象出离线层级接口：
```java
public interface ClassHierarchyOracle {
    boolean isAssignableFrom(String superType, String subType);
    boolean isInterface(String className);
    int getClassModifiers(String className);
    /** 获取成员访问标志，消除 protected/private 检查期反射 */
    Optional<Integer> getFieldModifiers(String className, String fieldName, String desc);
    Optional<Integer> getMethodModifiers(String className, String methodName, String desc);
    /** 提取指定类的所有 Nest 成员字节码（优先读取新版本） */
    List<ClassNode> getNestMembers(String className);
}
```
* **实现底座**：运行时内嵌模式直接对接 `AnnotationTransformer.HierarchyTree`（基于 BFS 解析字节码与 `bytecodeCache`），彻底避开动态类加载。`[已实现-转换底座]`

> **实现状态（截至 `358b232f`）**：接口本身尚未抽成 `ClassHierarchyOracle` 类型。已落地的只有其中一项能力的等价物 —— `InitFix.NestView`（§4.3 的全 Nest 单写证明与 §4.1 T0 的"无写入"判定用它读 Nest 成员字节码，**不做 `Class.forName`**）。`InitFix.isProtectedCrossPackageAccess` 仍在用 `Class.forName` + 反射读修饰符，是 §2.1 要消除的那类调用，尚未迁移。

### 2.2 `PatchPlan` 前置条件指纹与多轮热更基线追踪 `[目标规范]`
* **规范化基线对象**：`baseClassHash` 指向**经 `AnnotationTransformer`（`forceStaticLambdas` 等）改写规范化后、传给 JVM redefine 的字节码哈希**，而非磁盘原始字节码。
* **执行前契约**：Target 端记录当前类实际字节码哈希；执行前对比 `baseClassHash`，不匹配则拒绝执行，触发重新分析。

---

## 3. Schema-First 两阶段重定义流水线与事务中止

彻底消除新方法体在热更瞬时读取未就绪字段引发的 NPE（读穿）与 `static final` 的 JIT 常量折叠。

### 3.1 两阶段时序状态机 `[原型/验证]`

```
 阶段 A: 骨架先行                  阶段 B: 补丁修补与金丝雀           阶段 C: 全量上线 (或事务中止)
 ─────────────────────────────     ────────────────────────────      ────────────────────────────
 [提交 Stage A 字节码]            [伴生类对存量实例执行条件 CAS]    [提交 Stage C 完整新字节码]
               │                                 │                                 │
               ▼                                 ▼                                 ▼
   新字段在内存中就位                金丝雀探测 ──► 驱动全量修补       新业务逻辑正式登场
   并发业务线程跑旧逻辑              (出现致命异常或熔断?)             (读穿 NPE 与 JIT 折叠彻底消除)
   (旧逻辑绝不读取新字段)                        │
                                                 ├─► 是: [事务性中止 (ABORT)]
                                                 │       放弃阶段 C，系统停留在稳定旧版本!
                                                 └─► 否: [提交阶段 C，热更成功]
```

### 3.2 阶段 A 成员构成与静态调用防静默误补 `[原型/验证]`
* **新成员预注入**：所有**全新增加的私有方法、新 Lambda（由 `forceStaticLambdas` 保持签名稳定）、内部类及 `@HotswapInit` 方法**，完整写入阶段 A。旧代码无调用点，新成员处于休眠状态，保证阶段 B 伴生类链接合法。
* **切片调用防护（核心原则）**：**切片（含其递归调用闭包）严禁调用“已存在但在本次变更中被修改”的方法**。若切片调用了修改过的已有方法，阶段 B 会执行旧方法体算出过期值，造成静默误补；此时必须**拒绝打补丁**，或由 Emitter 把新方法体克隆进伴生类。
* **T4 极简调用**：阶段 A 既然已预注入 `@HotswapInit` 静态方法，伴生类无需克隆其方法体，直接通过静态调用转发即可。
* **`isInitialized` 检查时机**：在阶段 A 提交成功后、阶段 B 执行前**再次检查**：若类正处于 `being_initialized` 状态，说明其他线程正在并发执行老 `<clinit>`，立即中止本次热更。

### 3.3 快照时序与构造器尾部切片插桩 `[目标规范]`
* **客观时序评估**：从阶段 B 堆遍历快照到阶段 C 提交，受全堆 STW 遍历影响，物理耗时在毫秒到秒级。
* **终极闭环策略（构造器尾部钩子）**：
  * 在阶段 A 的类结构中，向所有构造器尾部（`RETURN` 前）直接注入新增字段的切片代码；
  * **效果**：阶段 A 之后新实例化的对象在构造器中直接带入真实参数完成就位，快照缝隙在物理上被完全封死，无需在阶段 C 前进行耗时的高成本增量全堆扫描。
* **构造中（In-Flight `<init>`）对象已知边界**：若堆遍历抓到了其他线程正在运行 `<init>` 且尚未赋值 final 源字段的对象，可能算出非预期初值。将此类依赖参数回溯的字段在报告中标记为已知风险。

> **实现状态（截至 `358b232f`）**：构造器尾部插桩未实现；而它所闭合的那条缝隙已被<b>实测刻画</b>：补丁只重放<b>字段初始化式</b>，构造器里的其它语句不会为存量实例重放。两个可复现的形态：
> * 新增字段在构造器里被<b>使用</b>（如 `sb.get().append("aass")`、`sb.append("aass")`）时，当前被"后续加工检查"**默认拒绝**（见 §7.2 对应行），存量实例的该字段保持默认值（`null`）——即"漏补"而非"误补"；
> * 被 `@HotswapReinit` 显式豁免后放行，但<b>会打 warn 并在 `PatchReport` 的 `FieldDecision.warnings` 里标记</b>"构造器里的这部分用法不会被重放"。

### 3.4 失败策略矩阵与待补字段台账（`FieldLedger`）`[目标规范]`
* **失败策略矩阵**：
  * `ABORT_ON_FATAL`（默认）：单字段逻辑异常跳过；若触发 `LinkageError`、VM 崩溃性故障或 HIGH 风险（非零基本类型）字段被拒，**立即中止，放弃提交阶段 C**。
  * `ABORT_ON_ANY`（严格）：任何字段修补失败即中止全流程。
* **待补字段台账（`FieldLedger`，解决基线推进遗忘缺陷）**：
  * 阶段 A 提交成功后类结构即不可逆。一旦阶段 B 中止，宿主类已在物理内存中携带新字段。
  * 系统在持久化上下文维护 `FieldLedger: Class -> Set<UnpatchedField>`。中止后，基线哈希推进至阶段 A，未成功修补的字段记入台账。下一轮热更时，**候选字段集合 = 本轮 Diff 新增字段 $\cup$ 台账内未决字段**，确保因故障或用户修正表达式后重新提交的字段能被再次处理。

### 3.5 批处理原子拓扑排序 `[目标规范]`
若类 `X` 的新增字段表达式读取类 `Y` 的新增字段，两类打包为同一事务批次：
$$\text{Batch: } [Y, X] \xrightarrow{\text{Stage A 全量生效}} [Y, X] \xrightarrow{\text{按拓扑序执行 Stage B}} [Y, X] \xrightarrow{\text{Stage C 全量生效}}$$

---

## 4. 判决分层、效应掩码与切片安全门

### 4.1 分层判决模型（T0 ~ T4）`[目标规范]`
* **T0（零值等价）**：显式赋值 `= null`、`= 0`、`= false` 或仅声明未赋值。按位比较确认为零值（特别排除 `-0.0f` 与 `NaN`）。标记为 `NOTHING_TO_PATCH`，零开销放行，不告警。`[已实现-Java基线]`
* **T1（编译期常量快速通道）**：携带 `ConstantValue` 属性，或切片仅含单条原子字面量加载（`ICONST`, `LDC "str"`）。绕过切片器，快速发射代码。`[部分实现]`（静态 `ConstantValue` 走专用通道；字面量切片仍走常规直线提取）
* **T2（纯计算切片）**：经由 `AliasInterpreter` 逆向切片并通过效应掩码审查。`[部分实现]`（切片 + §4.2 的 P0 级防御已生效；完整掩码见 §4.2 状态）
* **T3（复杂/不安全拒绝）**：包含分支、环境依赖、可变源字段。拒绝生成代码，输出结构化诊断报告。`[已实现-Java基线]`
* **T4（显式逃生口）**：类中声明 `@HotswapInit` 静态方法。发射器在伴生类中直接调用该方法，免除效应检查。`[未实现，见 §8 P2]`

### 4.2 正交效应位集合（Effect Bitmask）`[部分实现：P0 级黑名单/白名单 + bit 4/5 子集]`
切片效应采用 8 位掩码表示，聚合规则为按位或：$\mathcal{M}_{slice} = \bigvee_{i} \mathcal{M}(insn_i)$。
```
 bit 0: PURE           (纯确定性运算: Math.abs, Integer.valueOf, String 运算)
 bit 1: READS_FINAL    (读不可变对象的字段: 如 String.length)
 bit 2: ALLOC_PURE     (分配已知纯对象: new ArrayList<>(), new HashMap<>())
 bit 3: ENV_DEPENDENT  (依赖外部环境: 无参 toUpperCase, 默认 Charset)
 bit 4: READS_MUTABLE  (读取非 final 字段或可变对象的堆状态)
 bit 5: MUTATES_HEAP   (外部堆状态变异: 写入外部字段, list.add)
 bit 6: IO_SYS         (文件/网络 I/O, 线程启动, System.currentTimeMillis)
 bit 7: UNKNOWN        (包含虚方法动态分派或黑盒未决调用)
```
* **准入掩码**：$\text{ALLOWED\_MASK} = \text{PURE} \mid \text{READS\_FINAL} \mid \text{ALLOC_PURE}$。
* **接收者敏感的堆读取判定**：
  * 若目标对象类型为 `String`、基本包装类、`UUID`、`BigDecimal` 等标准不可变类，读取字段或调用方法赋 `PURE` / `READS_FINAL`；
  * 若目标为 `final List<String> list`，调用 `list.size()` 读取的是可变堆状态，**标记为 `READS_MUTABLE` 并拦截**。
* **局部逃逸豁免严格边界**：切片内 `NEW` 的实例必须满足**从未逃逸**（未赋值给外部字段、未作为参数传递给任何非纯调用），其链式调用赋予 `ALLOC_PURE`，**不标记 `MUTATES_HEAP`**。

> **实现状态（截至 `358b232f`）**：8 位掩码本身尚未落地，当前是"黑名单负向拦截 + 白名单防误杀 + 两处接收者敏感判定"的 P0 形态：
> * **bit 3 / bit 6 已覆盖**：非确定性（`System.currentTimeMillis`、`Math.random`、`Random` 家族、`UUID.randomUUID`、`Instant.now`/`Clock`、默认 Locale/TimeZone/Charset、`System.getProperty/getenv`）、反射与动态调用、进程与类加载、文件/网络 IO、日志**输出**。未命中的调用仍然**放行**（P0 刻意保留的宽松面）。
> * **bit 4 子集已覆盖**：`ThreadLocal`/`InheritableThreadLocal` 的 `get/set/remove/initialValue/childValue` —— 值取决于执行线程，而补丁固定在热更线程上执行。
> * **bit 5 子集已覆盖（接收者敏感）**：`StringBuilder`/`StringBuffer` 的变异方法，接收者若不是本切片内 `NEW` 出来的对象则拒绝；`new StringBuilder().append(a)` 这类局部逃逸**照旧放行**。
> * **仍然放行**：`list.size()`、`config.getName()`、`enum.name()` 这类"接收者追溯到字段"的可变堆读取 —— 收它们需要完整掩码与逃逸分析，且会误杀实际不可变的类型（enum/record），因此留给 P2。
> * **白名单（防误杀）**：基础集合无参构造、`Logger` 工厂与纯查询、`Objects.requireNonNull`、Kotlin `Intrinsics`、`Collections`/`Arrays` 的不可变工厂。日志**落地**方法（info/debug/log…）按 bit 6 拒绝。

### 4.3 严格不可变参数回溯证明 `[已实现-Java基线]`
在 `<init>` 中扫描到 `this.sourceField = param;` 时，要将后续 `ALOAD n` 重写为 `ALOAD 0; GETFIELD this.sourceField`，必须满足：
1. **条件 A**：`sourceField` 拥有 `ACC_FINAL` 修饰符（Kotlin `val` 属性）；
2. **条件 B**：若非 final，必须是 `private`，且通过 `ClassHierarchyOracle` 证明**全 Nest 范围内绝对不存在第二处针对该字段的 `PUTFIELD` 指令**。证明通过后，该字段的读取在效应审查中**降级为 `READS_FINAL` 等价处理**。

> **实现注记**：Nest 扫描由 `InitFix.NestView` 完成，**只读字节码**（优先 `HotswapAgent.bytecodeCache`，退化为 ClassLoader 资源流），不做 `Class.forName`；任一 Nest 成员读不到即按"无法证明"拒绝。`ClassHierarchyOracle` 接口本身（§2.1）仍未抽出。
> 拒绝原因会带真实根因透传进 `PatchReport`，例如 `private but written again at oracle/CaseC.setTag(...)`（§7.1 案例 B 的 setter 二次写入）。

### 4.4 构造器共识算法放宽 `[已实现-Java基线]`
* 多根构造器场景下，若所有根构造器都包含目标字段赋值切片，且**参数替换完成后的最终指令指纹 100% 一致**（证明所有构造路径下的初始语义完全同构），**予以安全放行**。

---

## 5. 伴生类装配与驱动隔离

### 5.1 伴生类独立静态方法集（Patch Emitter）`[目标规范]`
伴生类为每个放行字段生成独立的静态直线方法，彻底杜绝类加载期 StackMapTable 帧计算死锁：
```java
// 伴生类：Host$$HotswapPatch (持有 NESTMATE 属性)
public final class Host$$HotswapPatch {
    public static void init$timeout(Host self) { /*...*/ }
    public static void init$buffer(Host self) { /*...*/ }
}
```

### 5.2 宿主驱动器（Runtime Driver）类型适配与隔离 `[目标规范]`
```java
public final class PatchDriver {
    public static void applyToInstance(Object target, PatchPlan plan, Map<String, MethodHandle> handles) {
        Set<String> failedFields = new HashSet<>();
        for (FieldPatchTask task : plan.getOrderedTasks()) {
            if (task.dependencies.stream().anyMatch(failedFields::contains)) {
                failedFields.add(task.fieldName);
                continue;
            }
            try {
                // 必须在获取 Handle 时预先通过 asType 适配为 (Object)void
                MethodHandle mh = handles.get(task.fieldName).asType(MethodType.methodType(void.class, Object.class));
                mh.invokeExact(target);
            } catch (LinkageError le) {
                throw le; // 触发熔断中止
            } catch (Throwable t) {
                failedFields.add(task.fieldName);
                handleFieldException(task.fieldName, t);
            }
        }
    }
}
```

### 5.3 写入协议与合成字段自动过滤 `[已实现-Java基线]`
* **条件 CAS 写入（`KIND_CONDITIONAL`）**：`HotswapBridge` 解析物理偏移量，仅在内存值为类型默认零值时写入。
* **强制写入（`KIND_FORCE`，`@HotswapReinit(mode = OVERWRITE)`）**：无条件 volatile 写。必须经 `Unsafe` 而不是 `putfield` —— `final` 字段只允许在声明类的构造器里赋值，而补丁是宿主的 hidden nestmate，直接 `putfield` 会在链接期抛 `IllegalAccessError`。
* **合成标记字段过滤契约**：`ClassDiffUtil` 层统一过滤携带 `ACC_SYNTHETIC` 与 `$nipx$` 前缀的标记字段（如 `forceStaticLambdas` 生成的 `$nipx$lambdasForced`），防止与业务新增字段混淆。
* **每字段独立静态方法（§5.1）尚未实现**：目前仍是单个 `initStatic()` / `initInstance(Object)` 承载全部字段的直线代码 —— 这意味着一行抛异常会跳过其后的所有字段。逐字段驱动是 P1 的第 1 项。

---

## 6. JVMTI 原生多态堆实例检索规范

### 6.1 Native 堆遍历实现规范（对齐真实 C++ 底座）`[已实现-Native底座]`

对齐提供的 `LibTool` 实现，基于 `IterateOverInstancesOfClass`、RAII 作用域守护与原子标签分配：

```cpp
#include <jvmti.h>
#include <jni.h>
#include <atomic>
#include <mutex>
#include <expected>

static std::mutex g_heap_mutex;
static std::atomic<jlong> g_tag_sequence{100000}; // 动态分配私有 TAG 序列，杜绝 Agent 标签踩踏

static inline jlong allocateUniqueTag() {
    return g_tag_sequence.fetch_add(1, std::memory_order_relaxed);
}

// 实例查找回调：JVMTI 规范明确其天然包含目标类、所有派生子类与接口实现
static jvmtiIterationControl JNICALL HeapObjectCallback(
    jlong class_tag, jlong size, jlong* tag_ptr, void* user_data) 
{
    if (tag_ptr) *tag_ptr = *static_cast<const jlong*>(user_data);
    return JVMTI_ITERATION_CONTINUE;
}

// 回滚与清理回调
static jvmtiIterationControl JNICALL RollbackTagCallback(
    jlong class_tag, jlong size, jlong* tag_ptr, void* user_data) 
{
    if (tag_ptr && *tag_ptr == *static_cast<const jlong*>(user_data)) *tag_ptr = 0;
    return JVMTI_ITERATION_CONTINUE;
}

static std::expected<jobjectArray, jvmtiError>
getInstancesInternal(jvmtiEnv* jvmti, JNIEnv* env, jclass klass) {
    if (!jvmti || !env || !klass) return std::unexpected(JVMTI_ERROR_NULL_POINTER);

    std::lock_guard<std::mutex> lock(g_heap_mutex);
    jlong tag = allocateUniqueTag();

    // 1. 遍历目标类及其所有多态子类、接口实现的存量实例并打标
    if (auto e = jvmti->IterateOverInstancesOfClass(klass, JVMTI_HEAP_OBJECT_EITHER, HeapObjectCallback, &tag);
        e != JVMTI_ERROR_NONE) {
        jvmti->IterateOverInstancesOfClass(klass, JVMTI_HEAP_OBJECT_TAGGED, RollbackTagCallback, &tag);
        return std::unexpected(e);
    }

    // 2. 提取打上专属标记的对象句柄
    jint count = 0;
    jobject* instances = nullptr;
    if (auto e = jvmti->GetObjectsWithTags(1, &tag, &count, &instances, nullptr);
        e != JVMTI_ERROR_NONE) {
        jvmti->IterateOverInstancesOfClass(klass, JVMTI_HEAP_OBJECT_TAGGED, RollbackTagCallback, &tag);
        return std::unexpected(e);
    }
    
    // RAII 确保 instances 缓冲区释放
    auto dealloc = make_scope_guard([&] {
        if (instances) jvmti->Deallocate(reinterpret_cast<unsigned char*>(instances));
    });

    // 3. 组装结果数组并现场清理 Tag，释放临时局部引用
    jobjectArray result = env->NewObjectArray(count, klass, nullptr);
    if (!result) {
        for (jint i = 0; i < count; ++i) {
            jvmti->SetTag(instances[i], 0);
            env->DeleteLocalRef(instances[i]);
        }
        return std::unexpected(JVMTI_ERROR_OUT_OF_MEMORY);
    }

    for (jint i = 0; i < count; ++i) {
        env->SetObjectArrayElement(result, i, instances[i]);
        jvmti->SetTag(instances[i], 0); // 仅精准清理属于本次分配的私有 Tag
        env->DeleteLocalRef(instances[i]);
    }

    return result;
}
```

---

## 7. 案例对齐与客观限制全表

### 7.1 案例实录（真实字节码行为对齐）

#### 案例 A：Kotlin 主构造属性参数回溯放行 `[已实现-Java基线]`
```kotlin
class User(rawName: String) {
    val cleanName: String = rawName.trim()
    val name: String = rawName // 单赋值持久化到 final 字段
}
```
* **决议流**：`scanParamFields` 扫描到 `putfield name` 带有 `ACC_FINAL`，建立 `slot 1 -> ParamField(name)` 映射；提取 `cleanName` 时将 `aload_1` 替换为 `ALOAD 0; GETFIELD User.name`；`String.trim()` 接收者类型为不可变 `String`，判定为 `PURE`；**决议状态：`ACCEPTED`**。

#### 案例 B：非 final 且 Setter 二次修改拦截 `[已实现-Java基线]`
```java
public class Counter {
    private String tag;      // 非 final 字段
    private String cleanTag; // 新增字段
    public Counter(String tag) {
        this.tag = tag;
        this.cleanTag = tag.trim();
    }
    public void setTag(String newTag) {
        this.tag = newTag; // 存在二次写入！
    }
}
```
* **决议流**：`scanParamFields` 扫描宿主全 Nest 发现 `setTag` 存在二次 `PUTFIELD`，拒绝参数映射；切片中遇到未映射原生 `aload_1` 触发安全阻断；**决议状态：`REJECTED`**。

#### 案例 C：多构造器共识判定对比 `[已实现-Java基线]`
* **放行场景（共识达成）**：
  ```java
  public class Session {
      private final String id;
      private String token;
      public Session(String id, String extra) { this.id = id; this.token = id.trim(); }
      public Session(String id) { this.id = id; this.token = id.trim(); }
  }
  ```
  两个构造器中 `id` 均为 final 且映射完全一致，替换后指纹签名 100% 一致。**予以放行（`ACCEPTED`）**。
* **阻断场景（真实意图分歧）**：
  若构造器 1 执行 `this.token = id.trim();` 而构造器 2 执行 `this.token = "DEFAULT";`（指纹不一致）；或者构造器 2 根本没有对 `token` 初始化（覆盖不全）。**决议状态：`REJECTED`**。

---

### 7.2 客观物理限制全表 (Known Limitations) `[已实现-Java基线]`

| 边界类型 | 底层物理行为与现象 | 系统防御动作与规约 |
| :--- | :--- | :--- |
| **Kotlin 内联与作用域函数** | `?.`、`?:`、`let`、`apply` 展开为分支跳转与局部临时变量。 | **T3 拒绝**。破坏直线无分支假设。`@HotswapReinit` **救不了这一档** —— 它只改变"要不要写"（覆写存量），改变不了"表达式能不能被直线提取"；此类字段的正解是改用 `@HotswapInit`（T4，由你显式提供初始化方法），或把表达式改写成直线形态。 |
| **参数委托给父类构造器** | `Sub(x) : Base(x)` 中，入参 `x` 的 `PUTFIELD` 在父类 `<init>` 执行，子类看不到。 | **T3 拒绝**。子类无法在自身字节码建立参数回溯映射。 |
| **间接依赖读脏** | 表达式调用了私有辅助方法 `foo()`，而 `foo()` 内部读取了一个被拒绝补丁的新字段。 | **部分防御**。直接写在切片里的字段读取由依赖闭包（`depReason`）拦截；**接收者敏感**的两档已实测拦截（见下两行）；但 `foo()` 方法体本身不在切片内，其内部的读取仍然看不见 —— 完整解是递归效应位（P2）。 |
| **切片读取按线程的值** | `this.key = sb.get().toString()`，而 `sb` 是 `ThreadLocal<StringBuilder>` 缓存；补丁在热更线程上重算。 | **已防御（bit 4 子集）**。`ThreadLocal`/`InheritableThreadLocal` 的 `get/set/remove/initialValue/childValue` 一律拒绝，原因文案为 `thread-local heap state ...`。实测：构造线程上 `'abc'`、补丁线程算出 `''` 并静默写入 —— 现已被拦。 |
| **切片变异可复用 builder 缓存** | `this.key = BUF.append(name).toString()`，重置语句 `BUF.setLength(0)` 写在切片之外、提取时看不见。 | **已防御（bit 5 子集，接收者敏感）**。`StringBuilder`/`StringBuffer` 的变异方法要求接收者是切片内 `NEW` 出来的对象；实测 `'abc'` → 补丁算出 `'abcabc'` 并把实例的 `buf` 一起改脏，现已被拦。`new StringBuilder().append(a)` 的局部逃逸照旧放行。 |
| **构造器里使用新增字段** | 新增字段（如 `ThreadLocal` 缓存）在构造器里被读写：`sb.get().append("aass");`。 | **默认拒绝**。补丁只重放字段初始化式，构造器用法无法重放，存量实例会与正常构造实例分叉；原因为 `thread-affine field ... / field read outside any accepted extraction`。标 `@HotswapReinit` 可显式豁免，但**会打 warn 并在报告 `warnings` 里标记**；按线程的值还需注意补丁是**单线程**跑在热更线程上。 |
| **This 逃逸** | 构造器中存在 `init()`、`register(this)` 等调用，内部修改了新增字段。 | **Nest 加工扫描拦截**（仅针对 §4.3 的参数回溯源字段）。全 Nest `PUTFIELD` 探测排查，无法静态证明视为未知风险；对<b>新增字段</b>经由辅助方法的写入仍不可见。 |
| **多层 `this$0` 跨实例覆盖** | 内部类构造器通过 `Outer.this.f = param` 试图向外部类字段赋值。 | **坚决拒绝**。外部类实例被多内部类共享（Aliasing），跨实例覆盖会导致严重误补。 |
| **构造中对象捕获** | 堆遍历在其他线程执行旧构造器期间运行，抓取到 final 字段尚未赋值的半构造对象。 | **已知限制**。在风险报告中予以标明，依赖调用端避开高并发初始化峰值进行热更。 |
| **同名异型字段索引** | 混淆器生成的同名但描述符不同的重载字段。 | **已知限制**。系统以 `fieldName`（`String`）为单 Key，不支持重载字段。 |

---

## 8. 演进路线图与实施规划

```
 [P0: 原地安全加固] ──► [P0.5: 核心前置微验证] ──► [P1: 架构解耦与驱动] ──► [P2: 效应掩码与逃生] ──► [P3: 终极闭环]
                        ✅ 已完成               ⏳ 当前门槛            ⬜ 未开始            🔶 部分提前落地
```

### 8.0 实现状态总表（截至 `358b232f`）

| 规范条目 | 状态 | 落地位置 / 证据 |
| :--- | :--- | :--- |
| §1.2 五条不变量 | ✅ | `InitFix`（`isInitialized` 守卫、`PENDING` 弱键 + 5min TTL、`PatchReport` 解耦、`LinkageError` 熔断、`afterRedefineFailed`） |
| §2.1 `ClassHierarchyOracle` | 🔶 | `InitFix.NestView`（只读字节码）已覆盖 Nest 部分；`isProtectedCrossPackageAccess` 仍走 `Class.forName` |
| §2.2 `PatchPlan` 基线指纹 | ⬜ | — |
| §3 两阶段 Schema-First / §3.2 阶段 A / §3.4 `FieldLedger` / §3.5 跨类批次 | ⬜ | 单阶段；类内拓扑排序已实现 |
| §3.3 构造器尾部插桩 | ⬜（缝隙已实测刻画，见 §3.3 注记） | — |
| §4.1 T0 / T1 / T2 / T3 / T4 | T0 ✅、T1 🔶、T2 🔶、T3 ✅、T4 ⬜ | `FieldStatus.NOTHING_TO_PATCH`、`ConstantValue` 通道、`checkSafe`、§4.2 P0 防御 |
| §4.2 效应掩码 | 🔶（bit 3/6 已覆盖；bit 4/5 子集） | `InitFix.effectReason` / `blacklistedCallReason` / `builderMutatorReason`，见 §4.2 注记 |
| §4.3 参数回溯不可变证明 | ✅ | `scanParamFields` + `sourceFieldNotImmutableReason` + `NestView` |
| §4.4 多根构造器共识 | ✅ | 指纹一致即放行（原先"参数依赖 + 多根一律拒绝"的守卫已移除） |
| §5.1 每字段独立静态方法 / §5.2 `PatchDriver` | ⬜ | 仍为单个 `initStatic()`/`initInstance(Object)` |
| §5.3 条件 CAS / 合成字段过滤 | ✅ | `KIND_CONDITIONAL`；`KIND_FORCE` 为 `@HotswapReinit(OVERWRITE)` 追加；`ClassDiffUtil.isInternalMarkerField` |
| §6 JVMTI 多态堆检索 | ✅（Native 底座） | `LibTool.getInstances` |
| §1.1 `@HotswapReinit` 字段级存量覆写 | ✅ | `nipx.annotation.HotswapReinit` + `KIND_FORCE` + T0/后续加工检查豁免 + 豁免时 warn |
| §8 P2 `@HotswapInit`（T4） | ⬜ | — |

**回归测试**：`hstestInitFixOracle`（已挂 `check`）14 个场景 / 97 条断言，覆盖正向值比对（`InstanceTracker.register → transform → afterRedefine` 全公开 API）与负向阻断断言；`./gradlew check` 会跑。

### 8.1 分阶段规划

* **P0（原地安全加固与小改，即刻合入当前基线）**：`[已完成]`
  1. ~~在现有 `scanParamFields` 增加源字段不可变校验（`final` 或 private-Nest 单写）~~；
  2. ~~在现有 `checkSafe` 增加基于黑名单（非确定性/IO）+ 集合类无参构造白名单的最小效应防御，严防误杀基础集合构造与 Logger~~；
  3. ~~将 `extractFieldInits` 内部真实 `unsafeReason` 透传回 `PatchReport`，增加 `NOTHING_TO_PATCH` 状态~~；
  4. ~~`ClassDiff` 统一过滤 `$nipx$` 前缀与 `ACC_SYNTHETIC` 字段~~；
  5. ~~搭建基于正向值比对 + 负向阻断断言的差分 Oracle 基础流水线~~。
* **P0.5（核心前置微验证门槛，决定架构落地）**：`[当前门槛，未开始]`
  1. **[运行时验证]** 在目标 JVM（JBR 17/21）上实测连续两次 Schema Redefine（阶段 A $\rightarrow$ 阶段 C）的可行性与 GC 停顿；
  2. **[阶段 A 成员链接验证]** 验证阶段 A 预注入全新私有方法、Lambda 与内部类后，伴生类在阶段 B 链接调用的合法性与稳定性；
  3. **[性能基准]** 压测基于 `IterateOverInstancesOfClass` 的 C++ Native 代码在百万级对象下的耗时与内存开销。
* **P1（两阶段协议与架构解耦）**：`[未开始]`
  1. 伴生类重构为“每字段独立静态单方法 + 宿主 `PatchDriver` 用户态调度”模型；
  2. 落地 Schema-First 两阶段重定义流水线与 `ABORT_ON_FATAL` 事务中止机制；
  3. 引入**待补字段台账（`FieldLedger`）**，闭合阶段 A 中止后的基线遗忘缺陷；
  4. 将 `Analyzer` 库化抽取为纯函数模块。
* **P2（代数模型与业务逃生体系）**：`[部分提前落地]`
  1. 落地 8 位正交效应位集合（Effect Bitmask）与局部对象变异逃逸分析；🔶 已提前落地 bit 3/6 与 bit 4/5 的子集（见 §4.2 注记）；
  2. 落地 `@HotswapInit`（阶段 A 静态方法直调）；✅ `@HotswapReinit` 的**字段级存量覆写**已提前落地（`KIND_FORCE` + T0/后续加工检查豁免 + 豁免告警，见 §1.1），但其"信任通道"语义（豁免切片安全门）**刻意未做** —— 覆写只决定要不要写，不能让读脏的值变正确；
  3. 引入字段组原子性策略（`ALL_OR_NOTHING` 与 `@FieldGroup`）。
* **P3（终极闭环与底层现代化）**：
  1. 在阶段 A 注入构造器切片插桩，物理闭合快照缝隙；
  2. 伴生类底层 CAS 适配 JDK 21+ JEP 471 规范，平滑迁移至 `VarHandle.compareAndSet` 原生操作。