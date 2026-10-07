# InitFix Oracle：Kotlin 夹具编译基建缺口报告

状态：**硬停**。本报告只记录现象与证据，不含实现改动、不含替代方案。
触发条件：任务书要求"若测试基础设施无法编译 Kotlin 源码，立即停止并报告，不得发明替代方案"。

结论：**当前测试基础设施不具备编译 Kotlin 源码的能力**。因此依赖真 Kotlin `object`
字节码形态的场景（S1–S4、S7）在本轮不可测；纯 Java 场景（S5/S6/S8）在现有基建下可测，
但按"全部暂停"的指示本轮也未实施。

---

## 1. 核验矩阵

| #  | 检查项                              | 结果                                             | 证据                                                                                                                                                            |
|:--:|:------------------------------------|:-------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1  | oracle 夹具编译器类型               | 只认 Java                                        | `scratch/hstest/src/InitFixOracle.java:2327-2378`，`compile()` 使用 `ToolProvider.getSystemJavaCompiler()` + `SimpleJavaFileObject`                             |
| 2  | 输入源的文件名                      | 硬编码 `.java`                                   | `InitFixOracle.java:2358`：`URI.create("string:///" + key.replace('.','/') + ".java")`                                                                          |
| 3  | 夹具装载唯一入口                    | 只接受 Java 源串                                 | `InitFixOracle.java:2285-2298`：`loadFixture(dotName, v1Source, v2Source)` → `compile(Map.of(dotName, source))`                                                 |
| 4  | 编译器选项                          | 仅 javac 选项                                    | `InitFixOracle.java:2369`：`List.of("-proc:none","-nowarn")`，无 Kotlin 相关项                                                                                  |
| 5  | `kotlinc` / `kotlinc-jvm` 可执行    | 不存在                                           | `which kotlinc kotlinc-jvm` 无输出                                                                                                                              |
| 6  | 独立 Kotlin 编译器安装              | 不存在                                           | 无 `Program Files/*otlin*`、无 `/d/*otlin*`、`scoop`/`AppData/Local` 下无 `kotlinc*`                                                                            |
| 7  | 仓库内 `.kt` 源文件                 | 0 个                                             | `find . -name "*.kt" -not -path "*/build/*"` → 空                                                                                                               |
| 8  | `hstest` source set 是否接 Kotlin   | 未接                                             | `build.gradle:538-543`：只有 `java.srcDirs`；无 `kotlin.srcDirs`、无 `src/hstest/kotlin` 目录                                                                   |
| 9  | `hstest` 依赖是否含 Kotlin 编译器   | 无                                               | `build.gradle:546-550`：仅 `:hotswap-agent`、`:jni-agent`、`Anuken:Mindustry`                                                                                   |
| 10 | 历史脚本是否编 Kotlin               | 否，且已废弃                                     | `scratch/hstest/run.sh` 首段自述"已废弃，勿用"，夹具用 `javac --release 21`                                                                                     |
| 11 | 项目是否声明 Kotlin 插件            | 声明了，但无 Kotlin 源                           | `build.gradle:10` `id 'org.jetbrains.kotlin.jvm'`；`settings.gradle:3` 版本 `2.3.0`；`build.gradle:153` `kotlin-stdlib-jdk8`                                    |
| 12 | `kotlin-compiler-embeddable` 可达性 | 仅存在于 Gradle 自身发行包，未接入任何 classpath | `/d/data/.gradle/wrapper/dists/gradle-9.7.1-bin/.../gradle-9.7.1/lib/kotlin-compiler-embeddable-2.4.0.jar`；`modules-2/.../kotlin-compiler-embeddable` 下无 jar |
| 13 | 现存"Kotlin"场景实现方式            | 手写 Java 仿字节码（非真 Kotlin）                | `InitFixOracle.java:149-167` `CASE_A_*` 为 Java 类，用 `final` 字段模拟 `val`；`scratch/hstest/kt/v1`、`kt/v2` 亦为 Java 仿 `getFoo$annotations`                |

要点：#11 与 #12 的组合是本次缺口的核心——Kotlin Gradle 插件虽已声明（且其
`kotlin-compiler-embeddable` 恰好躺在 Gradle 发行包 lib 目录里），但**没有任何一条链路把它接到
oracle 的运行期 classpath**，oracle 也没有任何 API 入口消费它。

---

## 2. 场景可测性分类

原始任务的 8 个场景按"是否必须由 Kotlin 编译器产出字节码"分两类。

### 2.1 被阻断：依赖真 Kotlin `object` 字节码（5 个）

| 场景 | 内容 | 为何必须真 Kotlin |
|:--|:--|:--|
| S1 | Kotlin `object` 新增 `val x: Int = 1 + 2` | `object` 的初始化落在 `<clinit>`，并伴随 `public static final INSTANCE` 字段 + 私有构造器，是 kotlinc 的布局产物 |
| S2 | Kotlin `object` 新增 `val s: String = "  a ".trim()` | 同上；`trim()` 的接收者形态与 Kotlin 空断言/intrinsics 相关 |
| S3 | Kotlin `object` 新增 `val l by lazy { 42 }` | `lazy` 生成 `Lazy` 委托字段 + `getL()` 访问器，字段形态与初始化位置由 kotlinc 决定 |
| S4 | Kotlin `object` 新增 `val a = 1` / `val b = a + 1`（b 依赖新字段 a） | 需要真实的 `object` 单例内字段依赖拓扑 |
| S7 | 负例：Kotlin `object` 新增 `val t = System.currentTimeMillis()` | 需要真实 `object` 形态才能验证"单例初始化"路径上 bit 6 拒绝是否成立 |

### 2.2 不受阻断：纯 Java 单例形态（3 个）

| 场景 | 内容 | 现有基建可用性 |
|:--|:--|:--|
| S5 | Java enum 单例新增 `int` 字段，初始化 `Math.abs(-3)` | `loadFixture` 直接可用（普通 Java 类，含 `enum`） |
| S6 | Java Holder 懒加载单例（静态内部类持 `INSTANCE`），已实例化，新增 `String` 字段 `"ab".concat("cd")` | `loadFixture` 直接可用；实例检索可走 `InstanceTracker.register` |
| S8 | 负例：Java 单例新增 `String` 字段 `"ab".toUpperCase()`（无 Locale 参数） | `loadFixture` 直接可用 |

本报告不实施这三个场景——按"全部暂停"的指示保持未动。

---

## 3. 打通 Kotlin 夹具所需的最小基建（仅列需求，未实施）

若后续要恢复 S1–S4、S7，以下条件缺一不可。这里只陈述"需要什么"，
不构成方案选择，也不包含任何已写代码：

1. **运行期编译器可达**：把 `org.jetbrains.kotlin:kotlin-compiler-embeddable`
   （与 `settings.gradle:3` 的插件版本 2.3.0 对齐）加入 `hstest` 的
   `runtime`/`implementation` classpath（`build.gradle:546-550` 处），
   外加 `kotlin-stdlib`。这是 `build.gradle` 改动，不属于"只写测试代码"。
2. **内存内 K2 编译 harness**：oracle 现有 `compile()` 只能驱动 `javax.tools.JavaCompiler`。
   Kotlin 需要独立的驱动路径（`K2JVMCompiler` + 内存 KtFile 源 + 自定义输出收集），
   并要解决"同一 FQN 的 v1/v2 不能共存于同一 source set"这一既有约束
   （现状靠运行期分别在内存编译两份来规避，见 `InitFixOracle.java:2286-2287`）。
3. **源文件扩展名与 URI 约定**：`compile()` 目前把 URI 硬编码为 `.java`（`InitFixOracle.java:2358`），
   Kotlin 需要 `.kt` 的并行路径，且不能影响既有 Java 夹具。
4. **负向断言口径**：S7 属"新增安全门相关"断言，按 `AGENTS.md` §6 必须有负向阻断断言；
   该断言在 Kotlin 路径打通前无法落地。

### 不建议的做法（已按要求回避）

- 用 Java 手写类"模仿" Kotlin `object` 字节码后再冒充场景结论；
- 在结论里把未执行场景写成已覆盖；
- 用反射/字符串改写等手段绕开真实编译器。

---

## 4. 本轮实际改动范围

- 生产代码（`hotswap-agent/src/nipx/ref/InitFix.java`、`HotswapBridge` 等）：**未触碰**。
- `hstestInitFixOracle` 任务与既有 30 场景：**未修改**，既有基线不受影响。
- `build.gradle`：**未修改**。
- 唯一新增文件：本报告。

---

## 5. 需要用户决定的事项

1. 是否授权改动 `build.gradle`，给 `hstest` 接入 `kotlin-compiler-embeddable` 并新建内存 K2 harness
   （属新建测试基建，超出原任务"只写测试"授权范围）；
2. 或者先只实施不受阻断的 S5/S6/S8；
3. 或者维持暂停，待 Kotlin 工具链就绪后再继续。
