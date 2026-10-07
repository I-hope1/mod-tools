# 01 判决分层与切片安全门

适用：修改 `extractFieldInits`、`checkSafe`、效应检查、参数回溯、构造器共识时阅读。

## 1. 判决分层

| 层级 | 含义 | 状态 | 行为 |
|:--|:--|:-:|:--|
| T0 | 零值等价 | ✅ | 显式 `= null/0/false` 或仅声明。按位比较，排除 `-0.0f`/`-0.0d` 和 `NaN`。标 `NOTHING_TO_PATCH`，零开销放行，不告警 |
| T1 | 编译期常量 | 🔶 | 静态 `ConstantValue` 走专用通道；字面量切片仍走常规直线提取 |
| T2 | 纯计算切片 | 🔶 | 经 `AliasInterpreter` 逆向切片，并通过效应检查 |
| T3 | 复杂或不安全 | ✅ | 含分支、环境依赖、可变源字段。拒绝生成代码，输出诊断 |
| T4 | 显式逃生口 `@HotswapInit` | ⬜ | 伴生类直接调用该静态方法，免效应检查 |

### 拒绝必须对用户可见

- `InitFix.transform` 在 `buildPatch` 返回后，统一遍历 `PatchReport`，对全部 `REJECTED` 按原因聚合，逐条发 `warn`。
- 覆盖提取期（`initialization skipped`）和闭包期（后续加工、依赖、成环、根构造器覆盖不全、指纹不一致）。
- 新增拒绝路径时沿用这个出口，不要自建日志路径。

## 2. 效应检查

### 2.1 当前实现（P0 形态）

黑名单拦截 + 白名单防误杀 + 两处接收者敏感判定。

**已拦截**
- bit 3/6：非确定性（`System.currentTimeMillis`、`Math.random`、`Random` 家族、`UUID.randomUUID`、`Instant.now`/`Clock`、默认 Locale/TimeZone/Charset、`System.getProperty/getenv`）、反射与动态调用、进程与类加载、文件/网络 IO、日志输出。
- bit 4 子集：`ThreadLocal`/`InheritableThreadLocal` 的 `get/set/remove/initialValue/childValue`。原因：值取决于执行线程，而补丁固定在热更线程执行。拒绝文案 `thread-local heap state ...`。
- bit 5 子集（接收者敏感）：`StringBuilder`/`StringBuffer` 的变异方法，要求接收者是切片内 `NEW` 出来的对象。`new StringBuilder().append(a)` 照旧放行。

**白名单（防误杀）**
- 基础集合无参构造、`Logger` 工厂与纯查询、`Objects.requireNonNull`、Kotlin `Intrinsics`、`Collections`/`Arrays` 的不可变工厂。
- 日志落地方法（`info`/`debug`/`log` 等）按 bit 6 拒绝。

**仍然放行（已知宽松面，留给 P2）**
- `list.size()`、`config.getName()`、`enum.name()` 这类"接收者追溯到字段"的可变堆读取。收它们需要完整掩码与逃逸分析，且会误杀 enum/record 等不可变类型。
- 未命中黑名单的调用。

### 2.2 目标：8 位效应掩码（⬜ 未实现）

聚合规则：按位或。准入掩码 `ALLOWED_MASK = PURE | READS_FINAL | ALLOC_PURE`。

| bit | 名称 | 含义 |
|:-:|:--|:--|
| 0 | PURE | 纯确定性运算（`Math.abs`、`Integer.valueOf`、String 运算） |
| 1 | READS_FINAL | 读不可变对象字段（`String.length`） |
| 2 | ALLOC_PURE | 分配已知纯对象（`new ArrayList<>()`） |
| 3 | ENV_DEPENDENT | 依赖外部环境（无参 `toUpperCase`、默认 Charset） |
| 4 | READS_MUTABLE | 读非 final 字段或可变对象堆状态 |
| 5 | MUTATES_HEAP | 外部堆变异（写外部字段、`list.add`） |
| 6 | IO_SYS | 文件/网络 I/O、线程启动、`currentTimeMillis` |
| 7 | UNKNOWN | 虚方法动态分派或黑盒调用 |

设计约束：
- 接收者敏感：目标为 `String`、基本包装类、`UUID`、`BigDecimal` 等不可变类，赋 `PURE`/`READS_FINAL`；`final List<String>` 的 `size()` 读可变堆状态，标 `READS_MUTABLE` 并拦截。
- 局部逃逸豁免：切片内 `NEW` 的实例必须从未逃逸（未赋给外部字段、未传给非纯调用），其链式调用赋 `ALLOC_PURE`，不标 `MUTATES_HEAP`。

## 3. 参数回溯不可变证明（✅）

在 `<init>` 中扫描到 `this.sourceField = param;` 时，要把后续 `ALOAD n` 重写为 `ALOAD 0; GETFIELD this.sourceField`，必须满足以下之一：

- **条件 A**：`sourceField` 有 `ACC_FINAL`（含 Kotlin `val`）。
- **条件 B**：非 final 时必须 `private`，且证明**全 Nest 范围内不存在第二处针对该字段的 `PUTFIELD`**。通过后，读取按 `READS_FINAL` 等价处理。

实现要点：
- Nest 扫描由 `InitFix.NestView` 完成，只读字节码（优先 `HotswapAgent.bytecodeCache`，退化为 ClassLoader 资源流），不做 `Class.forName`。
- 任一 Nest 成员读不到，按"无法证明"拒绝。
- 拒绝原因带真实根因进入 `PatchReport`，例如 `private but written again at oracle/CaseC.setTag(...)`。

## 4. 多根构造器共识（✅）

多根构造器场景下，所有根构造器都包含目标字段赋值切片，且**参数替换后的最终指令指纹 100% 一致**，才放行。覆盖不全或指纹不一致一律 `REJECTED`。

## 5. `ClassHierarchyOracle` 契约（✅）

- 能力：层级/接口查询、类与成员修饰符、`resolveMember`（含实际声明类）、Nest 成员读取。
- 全程不触发类加载。类名用 JVM internal form。
- `HierarchyTree` 全局只存 `superName`、interfaces、access；Oracle 的 `ClassNode` 元数据只存在于单次分析实例。
- Nest 成员清单以 `SKIP_CODE` 读取；字段写入证明另用 ASM visitor 流式扫描，只保留紧凑的 `PUTFIELD`/`PUTSTATIC` 记录。
- 失败语义：
  - 层级/接口查询无法解析返回 `false`
  - 类修饰符查询抛 `IllegalArgumentException`
  - 成员查询返回 empty，调用方按"无法证明"处理
  - Nest 任一成员不可读抛 `IllegalStateException`，`InitFix` 捕获、记 warning、拒绝全 Nest 写入证明
- protected 跨包判断：按声明类与宿主的 package 路径比较。Oracle 不携带定义类加载器身份，跨 ClassLoader 同名包不在契约内。

## 6. 已知限制

| 场景 | 现象 | 处理 |
|:--|:--|:--|
| Kotlin `?.`、`?:`、`let`、`apply` | 展开为分支和临时变量，破坏直线假设 | T3 拒绝。`@HotswapReinit` 无效；正解是改用 `@HotswapInit`（T4，未实现）或把表达式改写成直线形态 |
| 参数委托给父类构造器 | `Sub(x) : Base(x)` 的 `PUTFIELD` 在父类 `<init>`，子类看不到 | T3 拒绝 |
| 构造器里使用新增字段 | 补丁只重放字段初始化式，构造器用法不重放，存量实例会与新实例分叉 | 默认拒绝（`thread-affine field ... / field read outside any accepted extraction`）。标 `@HotswapReinit` 可豁免，但会 warn 并写入 `FieldDecision.warnings` |
| 间接依赖读脏 | 切片调用私有 `foo()`，`foo()` 内部读了被拒的新字段 | 部分防御。`foo()` 方法体不在切片内，内部读取不可见；完整解需递归效应位（P2） |
| 切片读取按线程的值 | `ThreadLocal` 缓存在热更线程重算 | 已防御（bit 4 子集） |
| 切片变异可复用 builder | `BUF.append(x).toString()`，重置语句在切片外 | 已防御（bit 5 子集） |
| This 逃逸 | 构造器调用 `init()`、`register(this)` 修改了新增字段 | 仅拦截 §3 的参数回溯源字段；新增字段经辅助方法的写入仍不可见 |
| 多层 `this$0` 跨实例覆盖 | 内部类构造器写 `Outer.this.f` | 坚决拒绝（外部实例被多内部类共享） |
| 构造中对象捕获 | 堆遍历抓到其他线程正在构造、final 字段未赋值的对象 | 已知限制。在报告中标明，调用端应避开高并发初始化峰值 |
| 同名异型字段 | 混淆器生成同名不同描述符字段 | 已知限制。以 `fieldName` 为单 Key |

## 7. 案例

**A. Kotlin 主构造属性回溯（放行）**
```kotlin
class User(rawName: String) {
    val cleanName: String = rawName.trim()
    val name: String = rawName
}
```
`name` 是 final，建立 `slot 1 -> name` 映射；`aload_1` 替换为 `ALOAD 0; GETFIELD User.name`；`String.trim()` 接收者不可变，判 `PURE`。结果 `ACCEPTED`。

**B. 非 final 且有 setter 二次写入（拒绝）**
```java
public class Counter {
    private String tag;
    private String cleanTag; // 新增
    public Counter(String tag) { this.tag = tag; this.cleanTag = tag.trim(); }
    public void setTag(String t) { this.tag = t; }
}
```
Nest 扫描发现 `setTag` 的第二处 `PUTFIELD`，拒绝参数映射；切片里的原生 `aload_1` 触发阻断。结果 `REJECTED`。

**C. 多构造器共识**
- 放行：两个构造器都 `this.id = id; this.token = id.trim();`，`id` 为 final，替换后指纹一致。
- 拒绝：一个构造器 `token = id.trim()`，另一个 `token = "DEFAULT"`，或另一个根本没初始化 `token`。
