# 匿名内部类树状拓扑与混合闭包对齐技术规范 (v3.0) —— 架构规格与实现状态审计
(Anonymous Class Cascading Tree Topology & Hybrid Closure Pipeline — Architecture Specification & Implementation Audit)

> **文档定位**：本文是**规格 + 实现差距分析 + 迁移计划**三合一，**不是"已完成的终稿"**。规格条款按原意保留（作为目标形态），但每一条都带实现状态标记。
>
> **文档版本**：v3.0 (Architecture Specification & Implementation Audit)  
> **适用目标**：JDK 8 ~ 21 (基于 JBR-21 / DCEVM 增强类重定义能力)  
> **核心原则**：**在类热重载中，显式拒绝并原子回滚（Reject & Rollback）远胜于隐式错配导致的方法篡改（Silent Misbinding）**。  
> **实现状态**：见 §8「实现状态总表与验收基线」。逐条标记约定：`[已实现]` / `[部分实现]` / `[未实现]` / `[已偏离-有意保留]`（规格文本原样保留，但实现有意不照做，理由见该节实现注记）。
>
> **⚠️ 给读者的前置提醒**：本文是**规格**，不是现状描述 —— 只有 §8.1 状态总表才是现状描述。读任何条款前先看该条的状态标记与「实现注记」。其中 §3.1 第 4 条与 §3.2 是全文**唯二与已落地实现（且有回归测试保护）相冲突**的条款 —— 照字面改回去会重新打开一个已经修好的静默错配缺陷；§3.1 包含特征第 2 条的"非合成字段"与 §7.2 风险 1 的情况类似（照字面做会削弱一条安全信号）。

---

## 1. 系统目标、核心不变量与映射契约

### 1.1 系统目标与范围
本系统用于解决增强类重定义下，由于源码改动（增删、换序、变量捕获变动）导致匿名内部类（`Foo$N`）及 Lambda 表达式在重新编译后序号漂移，进而引发的**存活实例方法表篡改（Method Hijacking）**与**未对齐类加载错位**问题。

* **受支持范围**：
  * Java 源码编译器（javac 8, 11, 17, 21 与 ECJ）编译的标准类、一级匿名类、多层嵌套匿名类（`Foo$1$1`）以及 Lambda 混合嵌套结构。`[部分实现]` —— javac 8/17/21 已有真实夹具；**ECJ 仍开放**（见 §7.2 未决问题 1）。
  * 闭包嵌套深度 depth <= 4 的复合结构。
* **边界与非目标（Non-Goals）**：
  * **不跨宿主方法物理迁移实例**：若开发者将匿名类从 `methodA` 移动到 `methodB`，系统将其归入“旧类删除 + 新类新增”，不跨宿主方法篡夺老实例。
  * **不接管 Kotlin 编译器跨文件内联展开类**：Kotlin 内联函数的跨文件代码编织不在通用拓扑树中展开。

### 1.2 核心不变量（Core Invariant）`[已实现]`
> **类身份与实例状态保真不变量**：  
> **在 JVM 中已经存在存活实例的已加载类（Loaded Class），其物理类名只能被源码上与之对应的新版本实现重定义，其既有存活实例的方法调用与字段状态必须维持预期的语义连续性，禁止被无关的新生类占用物理槽位。**

> **实现注记**：该不变量靠三条机制共同保证 —— ① Tier 5（按名字盲配）被彻底移除，前 4 层未匹配一律判为"新类/孤儿"而非"沿用旧物理槽位"；② 未匹配的旧类进入 `orphanOldClasses`，在 `HotSwapAgent.processChanges` 里被显式跳过重定义（`[ORPHAN-RETAIN]`）；③ 新类一律分配**未被占用**的编号后再经 `pendingAlignedClasses` 在首次加载时拦截。回归证据：`AnonClassReproTest` Scenario 4/5/6/20。

### 1.3 映射方向与改写范围
* **映射方向定义**：
  `renameMap : 新编译类名 (New Name) -> 目标类名 (Target Name)`
  * **命中已加载旧类**：若新编译类匹配到了已加载的旧类（例如新 `Foo$2` 匹配老 `Foo$1`），目标类名设为旧类名 `Foo$1`。随后通过 `ClassRemapper` 将新字节码改名为 `Foo$1`，使 `redefineClasses` 精准更新老类。
  * **全新未加载类**：若新类为全新类（无对应历史类），分配一个未被占用的安全类名（例如 `Foo$3`），通过 `pendingAlignedClasses` 在初次加载时拦截生效。
* **ClassRemapper 改写范围**：`[已实现]`
  1. 指令流与常量：`NEW`, `CHECKCAST`, `INSTANCEOF`, `ANEWARRAY`, `MULTIANEWARRAY`, `LDC` 类常量
  2. 方法与字段调用：`INVOKESPECIAL`, `INVOKEVIRTUAL`, `INVOKESTATIC`, `INVOKEINTERFACE`, `GETFIELD`, `PUTFIELD`
  3. JVMS 类结构属性：`InnerClasses`, `EnclosingMethod`, `Signature`, 运行时注解与异常表
  4. 嵌套特权属性 (JDK 11+)：`NestHost`, `NestMembers`
* **栈图处理与限制**：`[已实现]`
  * 栈图（`StackMapTable`）中的类型引用随 ASM `ClassRemapper` 联动转换，不使用耗时的 `COMPUTE_FRAMES`（避免在类转换期触发类加载重入死锁）。
  * 字符串字面量（如反射 `Class.forName("Foo$2")`）不在字节码 Remapper 改写范围内。未显式声明 `serialVersionUID` 的类，其默认序列化哈希受类名影响。

> **实现注记（§1.3）**：改写由 `AnonClassAligner.remapClass` 用 `ClassWriter(0)` + `ClassRemapper(SimpleRemapper)` 完成 —— `ClassWriter(0)` 即"不重算 maxs、不重算帧"，与"禁用 `COMPUTE_FRAMES`"的约束一致；`InnerClasses` / `EnclosingMethod` / `Signature` / `NestHost` / `NestMembers` 由 ASM `ClassRemapper` 的对应 `visit*` 钩子联动处理。另外 `remapClass` 在映射表全为恒等映射时**直接返回原字节码**（不做无谓的 parse/写回）。

---

## 2. 匿名类元数据判定与保留名体系

### 2.1 基于类属性的权威判定（Attribute-First Admission）`[部分实现]`
判定一个类是否为不稳定匿名类，以 ClassNode 的字节码属性为第一准则，类名模式为辅助：

| 分类                         | 字节码判定准则                                                      | 处理动作         | 级联树定位                         |
|:-----------------------------|:--------------------------------------------------------------------|:-----------------|:-----------------------------------|
| **标准匿名类**               | `InnerClasses` 属性中 `innerName == null`，且包含 `EnclosingMethod` | **准入**         | 提取其宿主类与父级前缀，加入拓扑树 |
| **多层嵌套匿名类**           | `innerName == null`，其 `EnclosingMethod` 指向另一个匿名类          | **准入**         | 逻辑父为该外层匿名类               |
| **具名内部类**               | `innerName != null` 且不为纯数字                                    | **排除**         | 不对齐，父前缀路径完全冻结         |
| **具名局部类**               | `innerName != null` 且包含局部命名前缀                              | **排除**         | 保持原有类名                       |
| **javac 枚举 Switch 映射表** | `ACC_SYNTHETIC` + 仅含 `$SwitchMap$` 字段                           | **排除（保留）** | 识别为编译器缓存，保持原名直通     |
| **Kotlin When 映射类**       | 类名含 `$WhenMappings`                                              | **排除**         | 保持原名直通                       |
| **枚举常量体**               | 带有 `ACC_ENUM` 且基类为直接封闭枚举                                | **排除**         | 声明顺序与常量严格绑定，不可重命名 |

> **实现注记（§2.1）**：`[部分实现]`。落地位置 `AnonClassAligner.isAnonymousClass` / `isAnonymousClassName`。
>
> 实际判定顺序是：先看 `ACC_ENUM` → 排除（覆盖"枚举常量体"）；再看自身 `InnerClasses` 条目的 `innerName != null` → 排除（覆盖"具名内部类/具名局部类"）；**最后回退到类名模式** `宿主$<纯数字>($<纯数字>)*` 作为准入闸门。由此：
>
> | 表中的行                     | 实际结论            | 依据                                                                                                                                                                                                                     |
> |:-----------------------------|:--------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
> | 标准匿名类                   | ✅ 准入             | `innerName == null` + 名字模式；**但未强制要求存在 `EnclosingMethod`**                                                                                                                                                   |
> | 多层嵌套匿名类               | ✅ 准入             | 名字模式允许 `$1$1`；层级由 `getHierarchyLevel` 切分                                                                                                                                                                     |
> | 具名内部类 / 具名局部类      | ✅ 排除             | `innerName != null`；`Foo$1Local` 也会被名字模式二次挡掉                                                                                                                                                                 |
> | **javac 枚举 Switch 映射表** | ⚠️ **排除机制缺失** | 没有任何代码检查 `ACC_SYNTHETIC` + 仅含 `$SwitchMap$` 字段；javac 生成的 `Foo$1` 恰好满足纯数字名字模式，**会被当成匿名类纳入对齐**。它自身无实例，危害限于"占用编号/参与候选集"，但会让未匹配新类的编号分配结果偏离预期 |
> | Kotlin When 映射类           | ✅ 排除（顺带）     | `$WhenMappings` 含字母，被名字模式挡掉，并非由显式规则排除                                                                                                                                                               |
>
> **另一处偏差**：表中"标准匿名类"要求"包含 `EnclosingMethod`"，实现里没有这条校验（`cn.outerMethod == null` 时会走 `resolveHostMethodForAnon` 反向追溯补救，而不是排除）。这是有意为之：javac 8 的嵌套 lambda 会把 `EnclosingMethod` 记成虚拟的 `lambda$null$0`（`AnonClassReproTest` Scenario 12 已固化该事实），若强校验 `EnclosingMethod` 反而会漏掉真实匿名类。

### 2.2 全局保留名集合（Reserved Names Domain）`[部分实现]`
在为未匹配类分配新编号时，`takenTargetNames` 集合必须严密涵盖以下范围，杜绝任何物理碰撞：
1. **已分配目标名**：当前对齐批次中已被采纳的所有目标类名；
2. **已加载及孤儿旧类名**：JVM 运行时当前仍加载在内存中的所有历史类名（含已删除但未被 GC 卸载的孤儿类）；
3. **保留与排除类名**：枚举 Switch 映射表、局部类等未参与对齐的固有类名；
4. **加载期拦截挂起名**：当前已登记在 `pendingAlignedClasses` 中的未加载类名。

> **实现注记（§2.2）**：`takenTargetNames` 的初始播种**只有** `normOld.keySet()`（即调用方传入的旧匿名类表），四类保留名中：
>
> * 第 1 类 ✅ 每分配一个即 `add`；
> * 第 2 类 ✅ 由 `HotSwapAgent` 侧保证 —— 它先把 `loadedClassesMap` 中已加载但未缓存的历史匿名类字节码"偷"进 `bytecodeCache`，再整表作为 `oldAnonClasses` 传入；孤儿同样落在其中；
> * 第 3 类 ⚠️ **未显式加入**。风险被"候选名恒为纯数字"这一事实大幅削弱（`Foo$Builder`、`Foo$1Local` 都不可能等于 `Foo$<n>`），故当前只有理论风险；
> * 第 4 类 ❌ **完全未接入**。`AnonClassAligner.align(...)` 的签名里没有任何"保留名/挂起名"输入参数，上一轮还挂在 `pendingAlignedClasses` 里、尚未加载的目标名不参与避让。这是唯一有真实碰撞窗口的一条，修法只需给 `align` 加一个 `Set<String> reserved` 参数并由 `HotSwapAgent` 传入 `AnnotationTransformer.pendingAlignedClasses.keySet()`。

---

## 3. 层级交错协同流水线 (Level-Interleaved Pipeline)

针对混合嵌套结构，系统采用**按层级交错推进架构**，彻底打破线性流程在 depth >= 2 时的死锁：

```
Level 0: 宿主类 Foo 与顶层 Lambda 对齐
   │   (输出 lambdaRenameMap_L0)
   ▼
Level 1: 匿名类 Foo$1, Foo$2 对齐
   │   (基于 lambdaRenameMap_L0 修正 EnclosingMethod，输出 anonRenameMap_L1)
   ▼
Level 1: 匿名类内部私有 Lambda 对齐
   │   (以已配对的匿名类为独立宿主，输出 lambdaRenameMap_L1)
   ▼
Level 2: 嵌套匿名类 Foo$1$1 对齐
   │   (父前缀收敛，基于 lambdaRenameMap_L1 修正 EnclosingMethod，输出 anonRenameMap_L2)
   ▼
(递归推进直到叶子节点；若深度 > 4 则触发安全熔断)
   ▼
Phase 4: 全局原子重构与提交
```

> **实现注记（§3 流水线）**：`[部分实现]`。层级推进本身已落地为 `AnonClassAligner.alignCascading` 的 `for (level = 1 .. maxLevel)` 循环：每层用 `getParentName` 切分作用域、按层做候选收敛、按层分配未匹配编号，`validateRenameMap` 做单射与前缀校验。
>
> 与图上流程的两处差异需要明确：
> 1. **Lambda 对齐不在同一个循环里穿插**。宿主/匿名类的 lambda 对齐由 `LambdaAligner` 独立完成，`HotSwapAgent` 里的顺序是"先整批 anon 对齐（`processChanges` 的宿主循环）→ 再逐类 lambda 对齐（`LambdaAligner.align`）"。图上"Level 1 匿名类内部私有 Lambda"这一步实际是第 2 遍才做的 —— 能成立是因为 lambda 对齐以**重命名后**的匿名类字节码为输入（`AnonClassReproTest` Scenario 10/13 固化）。
> 2. **`depth > 4` 只 warn，不熔断**。落地代码是 `HotSwapAgent.warn("[ANON_ALIGN] ... Diagnostic threshold only ...")`。
>
>    **ⓘ 这个 4 没有任何强制力（实测更正，2026-02）**：早期版本的本注记曾声称"真正的深度硬限制在 `AnonClassHasher.MAX_DEPTH = 4`"。**那是错的** ——
>   * `AnonClassHasher.hash(...)` 内部**从不递归调用自身**（`git log -p` 确认自 `fb495fe5` 引入起就从未有过），三处调用点全部传 `depth = 0`，因此 `if (depth > MAX_DEPTH) return null;` 是**不可达分支**，`visiting` 集合也永远只有一个元素；
>   * `alignCascading` 的层级循环本身与深度无关，`maxLevel > 4` 只影响那一条 warn。
>
>   **实测证据**（探针 `scratch/hstest/src/DeepNestProbe.java`，N = 1..8，v2 在顶层插入一个匿名类制造整链位移）：**depth 5/6/8 全部逐层映射正确、零孤儿、零 minDiff 盲猜**。所以 4 既不是能力边界，也不是安全门 —— 它是一个从死常量抄来的诊断阈值。真正的闸门是 §6.3-2 的数量上限与 §6.3-3 的软超时。
>
>   与本文 §4.3 要求的"熔断"不一致：现在只有 `strict` 模式下才拒绝宿主组，见 §4.3 注记。

### 3.1 自身哈希 (Self Hash) 规范与伪循环剥离 `[部分实现 + 两条有意偏离]`
在预提取阶段，`AnonClassHasher` 计算的自身哈希必须与外部依赖解耦：
* **包含特征**：
  1. 基类全限定名，以及排序后的接口全限定名列表；`[已实现]`
  2. 非合成字段的名称、类型描述符与访问标志；`[部分实现 + 一处有意偏离]` —— 只入 `name:desc`（排序后），**未含访问标志**；且**未过滤合成字段**（`this$0` / `arg$N` / `val$x` 等捕获字段全部计入）。后者是**有意保留且应当保留**的：捕获字段正是"实例状态布局"信号本身，若按本条字面把它们过滤掉，Tier 1/2/3 会对"捕获变量变了"完全失敏，从而把 §7.2 风险 1 从"可被结构谓词挡住"退化成"必然落到 Tier 4"；
  3. 非合成方法的名称、方法描述符与归一化指令序列；`[已实现]`
  4. 相对槽位占位符 `#ANON_relId#`。`[已实现-增强形式]` —— 实际占位符是 `#ANON_<relId>_<childHash>#`，即**折入了被引用匿名类的内容哈希**，见第 4 条排除项。
* **排除与归一化特征**：
  1. **剥离调试元数据**：忽略 `LineNumberTable`, `LocalVariableTable`, `SourceFile`；`[已实现]`（`ClassReader.SKIP_DEBUG | SKIP_FRAMES`）
  2. **屏蔽私有合成闭包**：所有以 `lambda$` 开头的方法名统一归一化为 `#SYNTHETIC_METHOD#`，其描述符归一化为 `#SYNTHETIC_DESC#`；`[已实现]`（`MethodFingerprinter.isSelfSynthetic` / `updateHandle`）
  3. **屏蔽编译器访问器**：`access$000` 等外部访问桩统一归一化为 `#ACCESS_METHOD#`；`[已偏离-有意保留]` —— **有意不归一化**，见下方注记；
  4. **剥离子匿名类实例化指令**：**对子匿名类的 NEW 与初始化调用指令从主哈希中剥离**，改记为独立的“子类引用多重集（Child Invocation Multiset）”。
     * **核心价值**：父匿名类方法体内新增子匿名类时，父类的 Self Hash **绝对保持恒定**，不会引发雪崩。
     `[已偏离-有意保留]` —— **未剥离，且有意如此**，见下方注记。

> **实现注记（§3.1 排除项 3：`access$` 为什么保名）**
>
> `MethodFingerprinter.isSelfSynthetic` 显式把 `access$` 排除在归一化之外，理由是**本对齐器对 `access$` 采用"保名不改名"策略**：它是跨类引用，改名字只会重写本类内部引用，别的类里的调用点不会跟着改（这正是 `LambdaAligner` 在 `kt/` 夹具上踩到 `NoSuchMethodError` 的那类问题，见 `LambdaAligner` 里 `renameable = matchesPattern && !mn.name.startsWith("access$")` 处的长注释）。既然名字不变，`access$NNN` 就是**稳定信息**，保留它才能区分"调用不同 accessor 的两个 lambda"；抹成 `#ACCESS_METHOD#` 只会让它们撞哈希。
>
> **已知残留风险**：`LambdaAligner` 自己也在注释里承认 javac 的 `access$NNN` 编号在两次编译间可能指向不同成员。当前选择是"接受该风险，换取不误改跨类调用点"。若要收敛，正确方向是**在保名的同时把 accessor 的 `名字→目标成员` 映射也纳入指纹**，而不是简单归一化。

> **实现注记（§3.1 包含特征第 2/3 条：嵌套匿名类曾必然丢失 Tier 1/2 —— 已修复并加守卫）**
>
> **症状（修复前）**：只要 v2 在某层**插入**一个匿名类使整条链位移，则 **depth ≥ 2 的每一层都只能靠 Tier 4 配上**（`T1` 恒为 1，其余全是 `T4`）。也就是说"支持 depth ≤ 4"这个说法本身就有误导性：**深度 2 已经拿不到内容哈希匹配了**，而 Tier 4 恰恰是**不比字段表**的那一层（与 §7.2 风险 1 叠加）。
>
> **根因**：`AnonClassHasher` 构造非合成方法的签名条目时用的是**未经屏蔽的原始描述符**：
>
> ```java
> methodSignatures.add(mn.name + ":" + mn.desc + ":" + mHash);   // 修复前
> ```
>
> 而嵌套匿名类的**构造器**描述符里嵌着父匿名类的名字。实测确认 `<init>` 的 access flags 是 `0x0`（**非** `ACC_SYNTHETIC`），因此它**确实**走这条"带描述符"的分支：`Deep$1$1.<init>(Ldeep/Deep$1;)V` → 父类位移成 `Deep$2` 后变成 `<init>(Ldeep/Deep$2;)V` → 子类哈希必变 → Tier 1（要求 `contentHash` 全等）与 Tier 2 全部失效。
>
> **受控实验**（payload 完全不变、只位移父类，其余逐字节同构）：`Deep$1$1` hash = `45116a2a5b103038`，`Deep$2$1` = `f9f34dc1c2c4de37`。对照组第 1 层的构造器描述符嵌的是**不位移的宿主名** `Deep`，hash 恒等、Tier 1 命中 —— 变量只有一个。
>
> **修复**（已落地）：组装签名时对 `mn.desc` 过一遍 `MethodFingerprinter.maskDescriptor`（由 `private` 放宽为包内可见并补注理由）。
>
> | 场景 | 修复前 | 修复后 |
> |:---|:---|:---|
> | 内容不变，depth N | `T1=1, T4=N-1` | **`T1=N, T4=0`** |
> | 内容变了（最内层），depth N ≥ 2 | `T1=1, T4=N-1` | `T1=N-1, T4=1`（最内层内容确实变了，落 T4 属正常） |
>
> **屏蔽是定向的，这一点必须有守卫**：`maskDescriptor` 的前置条件是 `desc.indexOf('$') < 0` 即原样返回，且只改写 `L<本上下文类名>$<纯数字后缀>;`。因此 `()V` / `(I)V` / `(Ljava/lang/String;)V` 这类普通重载描述符**逐字节不变**，候选域不会被无端扩大；`(LOuter$Inner;)V`（具名内部类）也因后缀非纯数字而不改写。**反例守卫已进 `check`**：`extra(I)V` 与 `extra(Ljava/lang/String;)V` 两个匿名类的哈希必须不同（`AnonClassReproTest` Scenario 22）。
>
> **为什么此前没人发现**：现有场景只断言*映射是否正确*（选对了就算过），从不检查*用了哪一层*。其实跑 Scenario 19 时日志里一直打着 `Tier 4 bi-unique paired: ...CascadeSubject$2$1 -> ...$1$1`，只是没人看。Scenario 22 现在把"用哪一层"纳入了断言。


> **实现注记（§3.1 排除项 4 / §3.2：与已落地实现的正面冲突）** ⚠️
>
> **这是全文唯二"照字面实现会重新打开已修好缺陷"的条款，动手前务必读完本节。**
>
> **现状**：被引用匿名类的内容哈希被**折进**了引用方指纹，而非剥离。机制在 `MethodFingerprinter.maskAnonymousClass`：把 `宿主$1` 这类名字替换成
>
> ```java
> "#ANON_" + relId + "_" + Long.toHexString(childHash) + "#"   // 拿得到内容哈希时
> "#ANON_" + relId + "#"                                        // 拿不到时（深度超限/解析失败）
> ```
>
> 两条路径都走这套：`AnonClassHasher` 给匿名类自身算哈希时 `fp.setContext(宿主)`；`LambdaAligner.scan` 给宿主/匿名类的 lambda 算指纹时 `fp.setAnonHashes(...)`，哈希表正是由 `AnonClassHasher` 预计算的。
>
> **为什么不能按 §3.2 改成 `#ANON_COARSE(superName;interfaces;constructorParamTypes)#`**：
> 回归夹具 `scratch/hstest/anon/{v1,v2}/testAnon/AnonCase.java` 里，`Foo$1`(Save) 与 `Foo$2`(Delete) **都是无参 `Runnable` 匿名类**，两个宿主 lambda 体结构完全相同（都只是 `post(new Runnable(){...})`）：
>
> ```java
> Runnable save   = () -> post(new Runnable() { @Override public void run() { doSave(); } });
> Runnable delete = () -> post(new Runnable() { @Override public void run() { doDelete(); } });
> ```
>
> 于是 `#ANON_COARSE(Runnable;...;...)` 对两者**逐字节相同** → 两个宿主 lambda 指纹相同 → 配对退化为"名字盲配"。这正是 `AnonClassTest`（已在 `check` 上）守住的 **Save/Delete 静默对调**：老 `Foo$1` 实例会被重定义成 Delete 实现，插入的 `$0` lambda 被改名为 `$1` 从而劫持老实例的方法表。换句话说，**§3.1 排除项 4 与 §3.2 的"粗粒度签名"方案，本身就是 AnonCase 这一整类同构场景的失效条件**。
>
> **§3.1 排除项 4 的"父哈希恒定"同理**：父匿名类方法体里多一条 `NEW 子类 / DUP / INVOKESPECIAL` 指令序列，哈希就必然变，无法既有该指令又剥离该指令。现实现下这类场景（`AnonClassReproTest` Scenario 18/19 的 Level 2 递增）配对会从 Tier 1 退到 Tier 3（同宿主方法 + 同基类接口 + 同字段表 + 同方法表），**结果正确但置信度降级**；同时少一次数字位移带来的雪崩。
>
> **结论与后续方向**：维持现状（保内容哈希）。若确实要回归规格，必须先给出一个**同时满足**"父哈希对子类新增不敏感"与"AnonCase 同构可区分"的判别式；可行方向是把"子类引用多重集"做成**与主哈希并列的独立维度**并参与候选唯一性判定，而不是像现在这样并进同一个 CRC64 —— 那样既保住区分度，又让主哈希对子类内部逻辑修改免疫。这一设计尚未落地，也**不应在无回归测试保护的情况下直接改**。

### 3.2 宿主粗粒度签名隔离 `[已偏离-有意保留]`
在宿主方法对齐外层 Lambda 时，宿主指令流中的匿名类创建指令仅嵌入粗粒度签名：
`#ANON_COARSE(superName;interfaces;constructorParamTypes)#`
不嵌入匿名类的方法体内容哈希，确保匿名类内部逻辑修改不会破坏外层宿主 Lambda 的指纹。

> ⚠️ **本条的 `#ANON_COARSE` 机制未实现，且不应按字面实现** —— 粗粒度签名对 `AnonCase` 这类"多个同构无参 `Runnable` 匿名类"完全无法区分，照做会让已修复的 Save/Delete 静默对调复发。完整论证（含夹具源码、涉及文件与行号、可行的替代方向）见上方 §3.1 的「实现注记（§3.1 排除项 4 / §3.2）」。

### 3.3 未匹配子类基于映射后父名前缀派生 `[已实现]`
若父类已被重命名（例如新编译产物中的 `Foo$2` 映射回已加载的旧类 `Foo$1`），未匹配子类（如新 `Foo$2$1`）的目标前缀**必须基于映射后的父名 `Foo$1` 派生**：
```java
int lastDollar = n.name.lastIndexOf('$');
String prefix = lastDollar > 0 ? n.name.substring(0, lastDollar) : hostSlash;
String mappedParent = renameMap.get(prefix);
if (mappedParent != null) {
    prefix = mappedParent;
}
int idx = 1;
String candidate;
do {
    candidate = prefix + "$" + idx++;
} while (takenTargetNames.contains(candidate));
takenTargetNames.add(candidate);
renameMap.put(n.name, candidate);
```

> **实现注记**：落地在 `AnonClassAligner.alignCascading` 的 `level > 1` 分支，与上面伪代码逐句对应（`targetParent = renameMap.get(newParent)` → `candidate = targetParent + "$" + idx++`）。比伪代码多一层防护：`takenTargetNames` 还包含**所有未匹配旧类的名字**，因此不会出现"父被改名为 `Foo$1`、子又被分配成已存在的 `Foo$2`"的碰撞。回归证据：`AnonClassReproTest` Scenario 18（父被重命名时未匹配子类前缀跟随映射后的父名）、Scenario 19（Level 2 作用域收敛）。

### 3.4 宿主方法追溯调用链防护 `[已实现]`
`findCallerMethod` 逆向追溯调用链时执行强校验：
1. **三要素联合比对**：强制校验 `hostNode.name.equals(owner)`、`calleeName.equals(name)` 以及 `calleeDesc.equals(desc)`；
2. **死循环与深度截断**：`visited` 集合记录 `methodName + ":" + methodDesc`，硬编码最大追溯深度 depth <= 32。超过深度未定位则返回 null 触发降级。

> **实现注记**：三要素比对已落地（`invokedynamic` 的 BSM `Handle` 与普通 `MethodInsnNode` 两条路径都查 `owner + name + desc`），`visited` 与 `depth <= 32` 亦已落地。回归证据：`AnonClassReproTest` Scenario 17（外部类同名方法不夺舍宿主）、Scenario 12（javac 8 三层嵌套 lambda 的 `lambda$null$x` 沿调用链回溯到真实宿主方法 `runJob`）。

### 3.5 非方法上下文的宿主归类 `[部分实现]`
对于非普通方法的匿名类：
* 位于字段初始化器、实例初始化块时：`EnclosingMethod.methodName == null`，其作用域归类为 `<initializer>`；
* 位于静态初始化块时：归类为 `<clinit>`；
* 位于构造器时：归类为 `<init>`。
在同一初始化作用域内，优先比对 Self Hash，同构者按源码出现顺序（`orderIndex`）对齐。

> **实现注记**：**没有显式的 `<initializer>` / `<clinit>` / `<init>` 作用域标签**。实际做法是：`normalizeEnclosingMethod` 先把 `cn.outerMethod` 归约（剥掉 `lambda$` / Kotlin `$lambda` 前缀与编号），若仍为空则调 `resolveHostMethodForAnon` 在宿主类里反向扫 `NEW` 指令找实例化方法并沿调用链回溯；回溯不到就保持 `null`。于是"同作用域"退化为"`outerMethod` 与 `outerMethodDesc` 都相等"（`null == null` 也算相等），Tier 1/3/4 的谓词正是这么写的 —— 对非方法上下文这一退化**可用**，但没有构造器/初始化块三态区分，因此"字段初始化器里两个同构匿名类"与"静态块里两个同构匿名类"之间的隔离，实际靠 `orderIndex` + 结构谓词，而不是靠作用域类别。
>
> **⚠️ 曾存在的缺口（javac 8 嵌套层）—— 已修复**：上面那条回退扫描原本走的是**宿主类**的 `ClassNode`，而嵌套匿名类的实例化点位于**它的直接父匿名类**里。探针 `DeepNestProbe` 的交替嵌套实验：
>
> | 类                                  | `EnclosingMethod` | 用宿主 `deep/Alt` 扫 | 用父类 `deep/Alt$1` 扫 |
> |:------------------------------------|:------------------|:---------------------|:-----------------------|
> | `Alt$1`（A0）                       | `Alt.run()V`      | ✅ `run()V`          | ✅ `run()V`            |
> | `Alt$1$1`（A1，在 A0 的 lambda 内） | `Alt$1.work()V`   | ❌ `null`            | ✅ `work()V`           |
>
> **触发条件被实测收窄到一个精确形态**：该扫描只在 `cn.outerMethod` 为空或归约成字面量 `"null"` 时才触发。`lambda$work$0`（lambda 直接写在方法体里）会被归约成 `"work"`，**不触发**；真正触发的是 javac 8 对"**lambda 套在 lambda 里**"生成的 `lambda$null$N` —— 而归约后两侧都变成字面量 `"null"`，于是**方法作用域这个判据被抹平**。
>
> **后果（可复现的跨方法错配）**：同一父匿名类里两个方法各含一条 `lambda→lambda→匿名类` 链，两条链结构全等、v2 把**方法声明顺序反转**且两侧方法体都改（Tier 1 失效）。修复前两侧 `outerMethod` 都是 `"null"` → 2×2 歧义 → minDiff 按物理序号仲裁，而**恒等映射的 `|Δ|` 全为 0**，所以必然产出恒等映射 —— 两条链跨方法错配。修复后 `outerMethod` 分别为 `alpha` / `beta` → Tier 3 双向唯一、跨方法正确。
>
> **修复**：`parseInfos` 改用 `getParentName(hostSlash, name)` 对应的**直接父类** `ClassNode`（level 1 时父类即宿主，复用已解析的 `hostNode`；父类节点按需解析并缓存）。影响面极小：javac 11+ 的 `EnclosingMethod` 本就给出源码方法名，走不到这条分支。
>
> **回归证据**：`AnonClassReproTest` Scenario 24（6 条断言，用真实 javac 8）：靶子存在（`lambda$null$N` → `"null"`）、宿主扫描返回 null、父类扫描能区分 `alpha`/`beta`、端到端跨方法正确（`$1$1→$1$2`、`$1$2→$1$1`）、`ambiguousMatches == 0` 且无孤儿无新增。
>
> **顺带确认的命名事实**：该实验同时证明 **javac 8 的嵌套命名也是 `Alt$1$1`**（不是平铺），所以 §3.6 的"`$` 段级联 = 匿名类包含树"对 javac 8 成立。

### 3.6 Lambda 与匿名类的边界：为什么不需要一棵异构树 `[设计澄清 + 一条不变量]`

> 本节回应一类常见的设计提案：**"Lambda 与匿名类交替嵌套时，应放弃两棵树、合并成一棵异构拓扑树（containment graph）"**。结论是：**目标原则对，具体形态不对** —— 合并成树不是修复真实缺口所必需的，而且会与 javac 的实际命名规则冲突。

**（1）实测事实：Lambda 不构成命名层级。** 对

```java
void run() {
    Runnable a = () -> {                       // L0
        new Worker() {                         // A0
            void work() {
                Runnable b = () -> {           // L1（在 A0 内部）
                    new Task() {};             // A1（在 L1 内部）
                };
            }
        }.work();
    };
}
```

javac 21 的产物是 `Alt.class`、`Alt$1.class`(A0)、`Alt$1$1.class`(A1)、以及具名的 `Alt$Worker`/`Alt$Task`。也就是说 **A1 是 `Alt$1$1`，中间的 lambda 没有贡献任何 `$` 段**：

```
提案中的树:  Foo#run → L0 → A0 → L1 → A1        ← L1 被当成一个层级
javac 实际:  Alt     →        Alt$1(A0)  → Alt$1$1(A1)   ← L1 不可见
```

原因：lambda 体被编译成**所在类的合成方法**（`Alt$1.lambda$work$0`），它不产生新类，因此不产生新的命名层级；匿名类的编号始终是"**直接封闭类**的名字 + `$` + 该类内计数器"。所以：

* **匿名类侧根本不存在"第二棵树"** —— `AnonClassAligner` 按 `$` 段数做的层级级联（`getHierarchyLevel`）**已经就是**匿名类的包含树；
* 提案担心的"缺少 `L0 contains A0` / `A0 contains L1` 这层信息"中，真正被算法用到的只有 `A0 contains A1`（已由名字前缀给出），而"L1 在哪"属于**方法/创建点维度**，已由 `EnclosingMethod` + indy BSM + 合成方法名承担；
* 若真按提案插入 lambda 节点，`AnonClassAligner` 的 `getParentName`/层级编号会与 javac 命名脱节，等于凭空造出一个不存在的层级。

**（2）提案推荐的"拓扑展开"顺序，其实就是现在的两趟流水线。** 提案建议：

```
① 建 topology → ② 对齐 L0 → ③ 用 L0 的映射定 A0 scope → ④ 对齐 A0 → ⑤ 定 L1 scope → ⑥ 对齐 L1 …
```

而现实现是：第一趟按宿主类做**匿名类层级级联**（父层先定名、子层 scope 随之收敛，见 §3.3）；第二趟对**每个类**（含刚被改名后的匿名类字节码）单独跑 `LambdaAligner.align`。由于第二趟的输入是**改名之后**的字节码，"A0 的映射决定其内部 lambda 的 scope"这一条天然成立 —— 这不是遗漏，而是 `AnonClassReproTest` Scenario 10/13 已经固化的性质。

**（3）真正缺的不是 topology，而是"低置信层的第二判据"。** 提案里那个很典型的例子

```
V1:  L0 → A0 → L1
V2:  L0-new → { A-new,  A0 → L1 }
```

确实是真问题 —— 但它与本文 §4.1 注记里实测到的**Tier 3 minDiff 静默错配**是**同一个现象**（两个新类结构全等、Tier 1 因方法体改变而失效、于是靠物理序号仲裁，而序号永远偏向"插在前面的那个"）。它需要的判据是：

* **后代拓扑（descendant topology）**：`A0` 含一个 lambda 子节点、`A-new` 不含 → 可以区分；或
* **相似度（Tier 1.5）**：`A0` 与旧 `A0` 的方法体只差一点、与 `A-new` 完全不像 → 可以区分。

两者都是**在既有 Tier 框架内加一个正交维度**，不需要把两个 matcher 合并成一张图。注意"后代拓扑"这一维度**不会**自动落入内容哈希 —— lambda 子节点是合成方法（名字被 `#SYNTHETIC_METHOD#` 屏蔽），所以它必须在指纹之外单独建维度。

**（4）要固化为不变量的两条原则**（这是该提案最有价值的贡献，采纳）：

> **INV-1（自描述指纹）**：节点的 primary fingerprint **只描述自身**；子节点关系（"创建了哪些孩子、各是什么"）作为**独立维度**参与联合判定，**不得**把整棵 descendant 子树递归吸收进同一个哈希。
>
> **INV-2（禁止互相递归）**：`LambdaAligner` 与 `AnonClassAligner` **不得互相调用、互相递归求指纹**。统一入口是纯函数 `AnonClassHasher`；顺序永远是"先建/用拓扑证据，再由 matcher 在这份证据上工作"。

**现状核对**：INV-1 在**匿名类侧目前是 0 层折叠**（`AnonClassHasher` 从不调用 `MethodFingerprinter.setAnonHashes`，引用到的匿名类只记为无语的 `#ANON_relId#`），在 **lambda 侧是 1 层折叠**（`LambdaAligner.scan` 用 `setAnonHashes` 把被引用匿名类的内容哈希折进宿主 lambda 指纹 —— 这正是 §3.1 排除项 4 / §3.2 讨论的那处偏离）。INV-2 成立：`LambdaAligner` 调的是 `AnonClassHasher` 而非 `AnonClassAligner`，两个 aligner 之间没有调用关系。

⚠️ **但要诚实标注**：INV-1 在匿名类侧的成立**带有偶然性** —— 它来自 `AnonClassHasher.hash` **内部没有自递归**（那个递归参数与 `MAX_DEPTH` 是死代码，见 §3 注记），而不是来自任何显式设计约束。一旦有人"修好"那个递归，就会立刻违反 INV-1 并引入提案担心的雪崩（新增一个子匿名类 → 整条祖先链的哈希全变）。**因此这两条必须写进文档与代码注释，而不是继续依赖一个碰巧不生效的参数。**

✅ **已落成回归断言**（`AnonClassReproTest` Scenario 25，6 条，已挂 `check`）：

| 断言                                                   | 实测值                                   | 守的是什么                                              |
|:-------------------------------------------------------|:-----------------------------------------|:--------------------------------------------------------|
| 只改**子**匿名类内容 → **父**匿名类指纹不变            | `8807ec5a2a420738` == `8807ec5a2a420738` | 若有人让 `AnonClassHasher` 递归折入子哈希，此处立刻变红 |
| 负向对照：子匿名类自身内容确实变了                     | `fe64bce7ac46e2f4` != `fd40bf6eeeeb6845` | 防止上一条**空过**（夹具没真改动）                      |
| 正对照：改父类**自身**方法体 → 指纹必须变              | `8807ec5a...` != `5b4b6505...`           | 防止指纹退化成常量                                      |
| `LambdaAligner` 常量池不得出现 `nipx/AnonClassAligner` | 通过                                     | INV-2（架构守卫：扫字节码常量池，位置无关）             |
| `AnonClassAligner` 不得出现 `nipx/LambdaAligner`       | 通过                                     | INV-2                                                   |
| 正对照：两者都只经 `nipx/AnonClassHasher` 取指纹       | 通过                                     | 钉住**允许**的依赖方向                                  |


---

## 4. 梯队判定决策表与失败熔断机制

### 4.1 多梯队判定动作决策表 `[部分实现]`
| 梯队         | 判定条件                                                                                      | 置信度      | 唯一候选时的动作                 | 多候选平局时的动作                             |
|:-------------|:----------------------------------------------------------------------------------------------|:------------|:---------------------------------|:-----------------------------------------------|
| **Tier 1**   | **Self Hash 相同 + 宿主方法相同 + 描述符相同**                                                | 极高 (0.99) | 立即采纳锁定                     | 按 `PAIR_COMPARATOR` 数值 minDiff 仲裁         |
| **Tier 1.5** | **内容相似度 (方法体哈希交集率 >= 70% + 字符串 Jaccard >= 0.8)**                              | 高 (0.80)   | 采纳并记录日志                   | 差值相等无法区分时，降级至 Tier 3              |
| **Tier 2**   | **Self Hash 同类跨宿主方法匹配 (同外层类)**                                                   | 中高 (0.75) | 仅当全类唯一样本时采纳           | 存在多个候选时禁止采纳，降级至 Tier 3          |
| **Tier 3**   | **同宿主方法 + 同基类 + 同接口 + 同字段表 + 同声明方法表**                                    | 中 (0.65)   | 采纳并记录日志                   | 差值相等无法区分时，降级至 Tier 4              |
| **Tier 4**   | **同宿主方法 + 同基类 + 同接口**                                                              | 低 (0.35)   | 仅当作用域内为全局唯一孤本时采纳 | **同作用域存在 >= 2 个同基类候选时，直接拒绝** |
| *(移除)*     | *已坚决移除旧版 Tier 5（按名字盲配），前 4 层未匹配则判定为新增类/孤儿，杜绝已删除类内存篡夺* | -           | -                                | -                                              |

> **实现注记（§4.1）**：Tier 1/2/3/4 四层已落地（`AnonClassAligner.matchHierarchical` → `matchTier`），且每层内部是**两趟制**：先做"双向互为唯一候选"配对（消除遍历顺序依赖），再做 minDiff 仲裁（仅当该层允许）。**Tier 1.5 完全未实现** —— 没有任何"方法体哈希交集率 / 字符串 Jaccard"的相似度计算，哈希一变就直接落到 Tier 3（结构签名）。
>
> | 层     | 谓词（实现）                                                                               | `allowMinDiff` | 与规格一致性                                   |
> |:-------|:-------------------------------------------------------------------------------------------|:---------------|:-----------------------------------------------|
> | Tier 1 | `contentHash` 相同 + `outerMethod` + `outerMethodDesc` 相同，且 `contentHash != null`      | ✅             | 一致                                           |
> | Tier 2 | 仅 `contentHash` 相同（跨宿主方法），且 `contentHash != null`                              | ❌             | 一致（"仅当全类唯一样本时采纳"靠双向唯一实现） |
> | Tier 3 | `outerMethod` + `outerMethodDesc` + `superName` + `interfaces` + `fields` + `methods` 全等 | ✅             | 一致                                           |
> | Tier 4 | `outerMethod` + `outerMethodDesc` + `superName` + `interfaces` 全等                        | ❌             | 一致（"多候选"即不配对，见 §4.3 注记）         |
>
> **遗留清理项**：`AlignmentStats` 仍保留 `tier5Matches` 字段与 `recordTierMatch` 的 `case 5` 分支，而 Tier 5 已被移除 —— 该字段恒为 0，属死代码，可删。

> **实现注记（§4.1 实测缺陷：Tier 3 的 minDiff 仲裁会静默错配，且系统性偏向"被插入的新类"）** ⚠️
>
> **现象**（探针 `DeepNestProbe`，depth 1 一例即可复现）：只要**同时**满足
> 1. 在某个匿名类**前面插入**一个新匿名类（造成位移），且
> 2. 被位移的那个类的**方法体也变了**（于是 Tier 1 的 `contentHash` 不再相等），
>
> 那么两个新类都会满足 Tier 3 谓词（同宿主方法 + 同基类接口 + 同字段表 + 同方法表 —— 对同方法内两个无捕获的 `Runnable` 来说这些完全相同），形成 **1-to-N 歧义**；Tier 3 允许 minDiff 仲裁，而 `CandidatePair.diff = |n.orderIndex - o.orderIndex|` 用的是**物理名序号**，于是：
>
> ```
> v1: Deep$1 = 链头(payload TAG)
> v2: Deep$1 = 插入的 extra,  Deep$2 = 链头(payload TAG2)
> 期望: v2$2 → v1$1
> 实际: v2$1 → v1$1        ← extra 抢走了旧身份
>       v2$2 → 新编号 $2
> 日志: [WARN-ANON] Ambiguous anonymous class match in Tier 3 resolved by minDiff: Deep$1 -> Deep$1 (diff=0)
> ```
>
> **这不是巧合，而是系统性偏向**：插入发生在前面时，被插入类的 `orderIndex` 恰好等于旧类的序号，`diff = 0`；真正的老类变成 `diff = 1`。minDiff 永远选前者 → **"插在谁前面，谁就继承旧身份"**。对存活实例而言，这就是一次静默的方法表篡改（老实例的 `run()` 被换成插入类的实现）—— 正是本模块存在的理由。
>
> **规格层面怎么看**：§4.1 的 Tier 3 行确实写了"多候选平局 → 差值相等无法区分时降级至 Tier 4"，即允许 diff 不等时仲裁；§4.2 也承认 `orderIndex` 在多轮后会失去源码序含义。所以这条**不是实现偏离规格，而是规格自身的 minDiff 策略在"前插 + 改体"下不安全**。它与 §1.2 的核心原则（宁可不配对，不可配错）冲突。
>
> **爆炸半径（先用真实运行量出来再动手）**：用 `nipx.agent.debug=true` 跑仓库内全部会调用 `AnonClassAligner` 的入口（`AnonClassTest` / `AnonClassReproTest` / `DeepNestProbe`），分 tier 统计 `resolved by minDiff`：
>
> | tier   | bi-unique（唯一候选，必须不变） | minDiff（多候选仲裁，改动面） |
> |:-------|:--------------------------------|:------------------------------|
> | Tier 1 | 126                             | **0**                         |
> | Tier 3 | 8                               | **9**                         |
> | Tier 4 | 6                               | 本层不仲裁                    |
>
> 9 次 Tier 3 命中的性质：**7 次是错配**（前插 + 改体；含"真 A0 被判孤儿"的一例），**2 次原本正确**（夹具 L：2×2 同构原地改体，恒等映射恰好语义正确），**零次"碰巧对"**；且**既有 Scenario 1–20 与 `AnonClassTest` 全部零命中**（既有语料只通过 Tier 3 的**唯一候选**路径碰过 Tier 3，如 Scenario 3）。Tier 1 为 0 次 → "Tier 1 的 minDiff 先不动"实测零代价。
>
> **落地修法（已实现）**：Tier 3 的 minDiff 仲裁被**拓扑相等过滤**取代（见下方 §4.1 拓扑判据小节与 §8.3 第 4 条）。
>
> **为什么此前没人发现**：现有场景里凡是"前插"的（Scenario 1/13）**都没有同时改方法体**，所以 Tier 1 直接命中，永远走不到 Tier 3 的仲裁分支。探针把这两个条件叠在一起才暴露出来。

> **实现注记（§4.1 拓扑判据：Tier 3 用"拓扑相等过滤"取代 minDiff）** `[部分缓解，根本修法未做]`
>
> **判据定义（刻意只做相等，不做距离）**：`TopologySignature` 是一个**粗粒度的 5 元组**，与内容哈希**正交**、**不得**折进哈希（否则违反 INV-1，Scenario 25 变红），也**不得**回调其它 aligner（守 INV-2）：
>
> ```
> anonChildren    直接匿名子类个数（按名字层级：其直接父类正是本类）
> anonDescendants 更深的匿名后代个数
> indySites       invokedynamic 数量（lambda 创建点）
> lambdaMethods   合成 lambda$ 方法个数
> childKinds      直接子类的 kind 多重集（super + 排序接口，类名已屏蔽）
> ```
>
> **两个必须写清的边界**：
> 1. **`childKinds` 里的类名必须屏蔽成相对形式**（`#ANON#`，同 `maskDescriptor` 的前缀规则）。理由与 §3.1 那个缺陷同源：嵌套匿名类的父类/接口引用里嵌着会随位移改变的名字，不屏蔽就会把结构相同的两个类误判为拓扑不等。
> 2. **签名未知 ≠ 计数为 0**：任一子类字节码取不到时整个签名判 `null`（无信息），不得与任何候选配对。夹具 M 的"空拓扑真的相等"是另一种情形。
>
> **判定阶梯**（只在 Tier 3 多候选分支生效；Tier 1 的 minDiff 一行未动）：
> ```
> size == 0 -> 下一 Tier
> size == 1 -> accept（原样，不变）
> size  > 1 -> 拓扑相等过滤
>              恰好一个候选签名全等 且 双向唯一 -> accept（计入 topologyMatches，不混进 tier3Matches）
>              0 个 / >=2 个 / 签名未知        -> 不仲裁（non-strict 降级为新增/孤儿；strict 拒绝宿主组）
> ```
> 双向唯一之后**一次性**应用全部互唯配对（不是逐对贪心），因此与遍历顺序无关 —— 反序遍历断言即守此性质。
>
> **为什么不需要额外"否决集"防 Tier 4 绕过**：Tier 4 谓词（`outerMethod+desc+super+interfaces`）是 Tier 3 谓词的真超集（少了 `fields`/`methods`），而双向唯一性对边数单调递减 —— 同一剩余集上 Tier 3 非双向唯一 ⇒ 边更多的 Tier 4 必然也非双向唯一。夹具 M 的 `tier4Matches == 0` 断言守住这条不变量。
>
> **实测效果（三入口，`nipx.agent.debug=true`）**：
>
> | 夹具  | 形态                                            | 结果                                                                                                                                                      |
> |:------|:------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------|
> | **T** | V1 `A0{L1{A1}}` → V2 前插空壳 + `A0'{L1'{A1'}}` | **判对**：`Topo$2→Topo$1`（真 A0 继承旧身份）、`Topo$2$1→Topo$1$1`（§3.3 前缀跟随）、空壳拿未占用新号；**零孤儿**；`topology=1, T3=0`                     |
> | **M** | V1 `X` → V2 `extra + X2`（皆无子节点）          | **拒绝**：过滤后候选数 `2→2` 故不仲裁；两个新类都不占旧槽（`$1→$2, $2→$3`），旧 `$1` 成孤儿保留旧语义；`ambiguousPairs=1`；**`T4=0`（未被 Tier 4 绕过）** |
> | **L** | 2×2 同构匿名类**原地改体**（拓扑全等）          | **拒绝**（代价）：过滤后 `4→4`，两条编辑都不作用于存活实例。旧实现靠 minDiff 取恒等映射恰好正确 —— 这是**有意付出的保守代价**，以 `KNOWN` 条目钉在基线里  |
>
> **分 tier 变化（改动前 → 改动后）**：Tier 3 minDiff **9 → 0**；Tier 3 bi-unique **8 → 8**（逐条日志完全一致，见 §8.2）；Tier 3 新增 `topology=5`；Tier 1 bi-unique 因新增夹具而 +5，既有配对未受影响。
>
> **仍然开放的部分（不得标为完成）**：
> * 夹具 M 那一类（拓扑无信息、候选全等）**根本修法仍是 Tier 1.5 相似度**，本次未做；
> * 夹具 L 的保守代价同上（§8.3 第 4 条 ③）；
> * 曾评估过一个 carve-out（"只在 minDiff 能给出完全匹配且序号单调时才允许它仲裁"，可保住 L）：**已否决** —— 它仍然依赖物理序号，"删一个 + 插一个"这类两侧等量的编辑会满足完全匹配条件却判错，等于把已证明有系统性偏向的证据请回 matcher。该选项记入 §8.3 备查。
> * 本文档 §4.1 Tier 1.5 行、§8.1 相关行均标 **🔶 部分缓解**。

### 4.2 多轮基线与 sourceOrder 稳定排序 `[未实现]`
为防止第 N 轮热更后，旧侧 `orderIndex` 丧失源码顺序含义：
* **基线元数据**：在 `bytecodeCache` 中为每个已加载类附带记录其首次加载或对齐时的 `sourceOrder`（源码中的原始物理次序）。
* **距离度量**：仲裁比较器计算距离时统一采用：
  `diff = Math.abs(n.orderIndex - o.sourceOrder)`
* **稳定比较器**：
```java
private static final Comparator<CandidatePair> PAIR_COMPARATOR = (p1, p2) -> {
    int cmp = Integer.compare(p1.diff, p2.diff);
    if (cmp != 0) return cmp;
    cmp = Integer.compare(p1.n.orderIndex, p2.n.orderIndex);
    if (cmp != 0) return cmp;
    cmp = Integer.compare(p1.o.sourceOrder, p2.o.sourceOrder);
    if (cmp != 0) return cmp;
    cmp = p1.n.name.compareTo(p2.n.name);
    if (cmp != 0) return cmp;
    return p1.o.name.compareTo(p2.o.name);
};
```

> **实现注记（§4.2）**：❌ **`sourceOrder` 概念在代码中完全不存在**。现实现的两处偏差：
> 1. **距离度量用的是物理名序号，不是源码序**：`CandidatePair.diff = Math.abs(n.orderIndex - o.orderIndex)`，而 `o.orderIndex` 由 `parseIndex(hostSlash, o.name)` 从**当前物理类名**重算。类一旦经过一轮对齐被改名为"目标名"，这个数字就不再代表源码出现顺序 —— 正是本条要解决的问题。
> 2. **比较器的第 3~5 键是 `n.orderIndex` / `o.orderIndex` / `n.name` / `o.name`**，没有 `sourceOrder` 参与。
>
> **为什么现在还不出事**：Tier 1/2 的哈希比对与 `orderIndex` 无关；`diff` 只在**同一层内存在候选平局**（1-to-N / N-to-1）时才作为仲裁依据。所以触发条件是"多轮热更 + 同级同构候选平局"，属窄路径。
>
> **要落地的前置改动**：`HotSwapAgent.bytecodeCache` 目前是裸的 `Map<String, byte[]>`，需要扩成带元数据的结构（或旁路维护 `name -> sourceOrder` 表），并保证 `AnonClassAligner` 能拿到旧侧的 `sourceOrder`。影响面涉及 `HotSwapAgent` / `AnnotationTransformer` / `AnonClassAligner` 三处，**建议先写一个能红的用例再动手**（构造"第一轮发生过中部位移、第二轮出现同级平局"的夹具），否则无法证明改对了。

### 4.3 失败安全熔断机制 (Reject & Rollback) `[已实现-三条触发条件齐备]`
当触发以下条件时，系统**拒绝本次结构性重定义**并回滚：
1. **低置信度同构平局**：在 Tier 4 阶段，同一个宿主作用域内存在 >= 2 个同基类匿名类，且无法确定性区分；
2. **嵌套深度超限**：闭包嵌套深度 depth > 4；
3. **后置校验失败**：目标类名发生冲突（非单射），或子类目标前缀未能以父类目标前缀收敛。

**拒绝粒度与响应规范**：
* **拒绝粒度**：以**“宿主类 + 其下属全部匿名类”**为原子单元整体拒绝回滚，避免宿主与匿名类处于不一致版本。
* **回滚动作**：
  1. 调用 `tx.rollback(true)`，清除预登记的 pending 注入项；
  2. 将 `bytecodeCache` 中已生效的历史旧字节码重新钉扎到 `pendingAlignedClasses`；
  3. 取消本次提交，控制台输出显式告警提示：
     `[HOTSWAP-REJECT] Structural ambiguity detected in Foo. Redefine skipped safely. Please hot-swap again or restart.`

> **实现注记（§4.3）**：`[已实现]`。落地形态是**一个统一的拒绝通道** `AnonClassAligner.AlignmentRejectedException`
> （继承 `IllegalStateException`，携带 `hostSlash` + `reason`），三种触发条件都经它上抛，
> 由 `HotSwapAgent.rejectHostGroup(...)` 统一处理：打 `[HOTSWAP-REJECT]` 告警，并把
> 「宿主 + 其下属全部匿名类（含新侧原始类名）」整体移出本批重定义。
>
> | 触发条件 | 触发时机 | 默认行为 | `strict` 模式（§6.4） |
> |:---|:---|:---|:---|
> | ① Tier 4 同基类平局 | 每层 `matchTier(4, ..., allowMinDiff=false)` 之后统计 `stats.ambiguousPairs` | 不配对 → 退化为"新增/孤儿"（保守但静默） | `ambiguousPairs > 0` 即拒绝宿主组 |
> | ② `depth > 4` | `alignCascading` 层级分组之后 | `warn` 后继续（**该阈值无任何强制力**，见 §3 注记） | 直接拒绝宿主组 |
> | ③ 后置校验失败 | `validateRenameMap`（单射 + 前缀不变量） | **始终**拒绝宿主组 | 同左 |
> | ④ 数量超上限 / 软超时 | §6.3-2 / §6.3-3 | **始终**拒绝宿主组 | 同左 |
> | ⑤ 总开关关闭 | §6.4 `enabled=false` | **始终**拒绝宿主组（见下方"关闭语义"） | 同左 |
>
> **为什么拒绝必须连宿主一起移出本批**：不对齐时，新编译产物的 `Foo$2` 与 JVM 中已加载的旧 `Foo$2` 同名但语义不同 —— 把它送进 redefinition 就是把老实例的方法表交给无关的新类，正是本模块存在的理由；而只跳过匿名类、保留宿主，又会让宿主的新字节码引用到没被对齐过的 `Foo$N`。因此拒绝的语义只能是"这一组本轮完全不动"。
>
> **关闭语义（`enabled=false`）**：**不是**"恢复成按类名照旧重定义"（那等于把编号位移篡夺 bug 重新打开），而是对**含匿名类的宿主**整体拒绝；不含匿名类的宿主不受影响。这是唯一安全的"关掉"。
>
> **关于曾经的"半提交"疑点（已核实并更正）**：早期分析认为"第 N 个宿主抛异常时，前 N-1 个宿主写进 `newBatchBytes` 的改名结果会残留成半提交状态"。**这个判断是错的** —— `newBatchBytes` / `classToPath` / `transactions` / `definitions` 全是 `processChanges` 的局部变量，`tx.pendingAdds` 也只在 `applyRedefinitions → tx.preRegister()` 时才写进 `AnnotationTransformer.pendingAlignedClasses`，而异常冒泡时根本走不到那里。
>
> 所以当时**真正的缺陷不是状态污染，而是"整轮静默失效"**：异常从 `align` 冒泡出 `processChanges`，再冒泡出 `triggerHotswapWith`，最终只落在 `scheduler.schedule(...)` 返回的 `ScheduledFuture` 上 —— **没有任何人观测它**。后果是"本轮所有类的热更全都没生效，控制台一个字都不说"（且不只是出错的那个宿主，整轮都没了）。这比状态污染更难排查，因此仍然必须修 —— 现在的实现对预期拒绝打 `[HOTSWAP-REJECT]` 告警 + `continue`，对意外异常打 `error` + 堆栈 + `continue`，其余宿主照常热更。
>
> **回归覆盖**：`AnonClassReproTest` Scenario 21 覆盖 ③④ 的拒绝通道、① 的 `ambiguousPairs` 统计与 `strict` 熔断、以及拒绝异常的宿主名/类型契约。②（深度）与 ⑤（总开关）无独立断言 —— 前者需要构造 depth ≥ 5 的真实夹具，后者需要走完整的 `processChanges` 流水线（当前只有 direct-call 级测试），已记入 §8.3。

---

## 5. 端到端推演案例 (End-to-End Walkthrough)

### 场景 1：父匿名类结构未变，外层插入新类导致整体位移
* **老版本 (V1)**：
  * `Foo$1`: 任务 Save，包含内层 `Foo$1$1`（Worker A）
  * `Foo$2`: 任务 Delete
* **新版本 (V2 编译产物)**：
  * `Foo$1`: **[新增]** 任务 Audit
  * `Foo$2`: 任务 Save（由原 `$1` 整体位移而来），包含内层 `Foo$2$1`（Worker A）
  * `Foo$3`: 任务 Delete（由原 `$2` 整体位移而来）

**机械推演**：
1. **Phase 0**：提取 Self Hash：`H(Audit)=0xA1`, `H(Save)=0xB2`, `H(Delete)=0xC3`, `H(WorkerA)=0xD4`。
2. **Level 1**：
   * `Foo$2` (H=0xB2) 与 `Foo$1` (H=0xB2) Tier 1 互为唯一，锁定：`Foo$2 -> Foo$1`。
   * `Foo$3` (H=0xC3) 与 `Foo$2` (H=0xC3) Tier 1 互为唯一，锁定：`Foo$3 -> Foo$2`。
   * `Foo$1` (Audit) 未匹配，分配未占用编号：`Foo$1 -> Foo$3`。
3. **Level 2**：
   * 限制在新 `Foo$2` 对应的目标父名 `Foo$1` 作用域内。
   * 新 `Foo$2$1` (H=0xD4) 与老 `Foo$1$1` (H=0xD4) Tier 1 互为唯一，锁定：`Foo$2$1 -> Foo$1$1`。
4. **Phase 4**：重定义列表为 `[Foo, Foo$1, Foo$2, Foo$1$1]`。老实例无感自愈。

### 场景 2：父匿名类新增子匿名类 (方法体产生指令变动)
* **老版本 (V1)**：`Foo$1`: 任务 Save，仅创建 Worker A（`Foo$1$1`）。
* **新版本 (V2)**：`Foo$1`: 任务 Save，内部追加创建 Worker B（`Foo$1$2`）。

**机械推演**：
1. **依据 3.1 规则**：对子类的 `NEW` 与初始化调用已从 Self Hash 中剥离，因此 `Foo$1` 的 Self Hash **保持不变**。
2. **Level 1**：`Foo$1` 达成 Tier 1 互为唯一配对，锁定 `Foo$1 -> Foo$1`。
3. **Level 2**：
   * 新 `Foo$1$1` 匹配旧 `Foo$1$1`，锁定 `Foo$1$1 -> Foo$1$1`。
   * 新 `Foo$1$2` 为新增类，前缀取父名 `Foo$1`，分配目标类名 `Foo$1$2`。
4. **Phase 4**：`Foo$1$2` 预登记到 pending，`Foo` 与 `Foo$1` 原子提交重定义。

> **实现注记（场景 2）**：⚠️ 第 1 步的推论**在当前实现下不成立**。因为子匿名类的 `NEW` / 初始化调用**没有**从主哈希中剥离（见 §3.1 的实现注记），`Foo$1` 方法体里多出一组 `NEW Foo$1$2 / DUP / INVOKESPECIAL` 后 Self Hash 必然改变。实际走法是：Tier 1 配对失败 → **Tier 3 结构签名**（同宿主方法 + 同基类接口 + 同字段表 + 同方法表）配对成功 → 最终结果与推演的第 2~4 步一致（`Foo$1 -> Foo$1`、`Foo$1$1 -> Foo$1$1`、`Foo$1$2` 分配新号并预登记 pending），只是**置信度从"极高"降为"中"**。
>
> 该降级路径已被 `AnonClassReproTest` Scenario 18/19 覆盖，属"结果正确、机制不同"，不属于缺陷；但若将来落地 §3.1 第 4 条，这里需要改回 Tier 1 并补一条断言。

---

## 6. 运行时架构、JBR-21 实测证据与性能

### 6.1 JBR-21 / DCEVM 运行时能力实测证据 `[部分实现]`
经在真实 JBR-21 (21.0.9+1) + DCEVM (`-XX:+AllowEnhancedClassRedefinition`) 环境下物理实测验证：
1. **NestMembers 与 InnerClasses 修改**：**完全支持重定义**。向宿主类追加新的 `NestMembers` 和 `InnerClasses` 属性后，调用 `redefineClasses` 成功返回，新内部类可正常访问宿主私有成员。
2. **类继承体系与接口变更**：在开启增强模式下，底层支持添加接口与修改父类。但为确保全局稳定性，对齐器保持谨慎策略。
3. **未加载类的首次加载拦截**：`AnnotationTransformer.pendingAlignedClasses` 必须覆盖所有尚未被类加载器加载的目标类（无论是全新类还是被改名的历史类），确保首次读入的字节码为对齐后的产物。

> **实现注记（§6.1）**：第 1、3 条已由 `AnnotationTransformer` + `AlignmentTransaction` 落地，拦截路径见 `AnonClassReproTest` Scenario 11（pending 注入 → `transform` 拦截 → 断言注入的确是**位移后的正确版本**而非磁盘上的原始产物）。
>
> **⚠️ 第 2 条与 `HotSwapAgent` 的现状相反**：`processChanges` 里对 `ClassDiffUtil.diff(...).hierarchyChanged` 的处理是**直接拒绝**该类的重定义并 `tx.rollback(true)`（错误信息是 `REJECTED: Class hierarchy change detected ... Please RESTART application.`），即"即使增强模式底层支持，也一律不碰"。这与本条"对齐器保持谨慎策略"的描述一致，但**不是"支持但谨慎"，而是"完全不支持"**。原实测证据（`scratch/hstest/src/LiveDcevmTest.java`）未接入 Gradle 任务，属一次性验证记录 —— 若要长期引用，应把它接进 `check` 或降级为"历史实测，未纳入回归"。

### 6.2 事务原子性与并发互斥 `[部分实现]`
* **事务生命周期**：
  `prepare -> tx.preRegister() -> inst.redefineClasses() -> tx.commit()`
* **并发控制**：Agent 内部重定义操作由全局独占重入锁控制，多线程并发热重载请求严格串行排队。

> **实现注记（§6.2）**：事务生命周期已按规格实现，且比规格多做了一步**乐观预登记**：在 `inst.redefineClasses` 之前就对所有事务 `preRegister()`，消除"宿主重定义成功 → 事务提交之间并发线程首次加载未加载类时读到磁盘错位产物"的竞态；批量失败后切换到单类模式，并按事务组校验一致性（宿主与全部目标匿名类都成功才 `commit`，否则 `rollback(true)`）。
>
> **并发控制未按规格实现**：**不存在全局独占重入锁**。实际的串行化来自"文件监听线程单线程 + 防抖窗口"，共享状态只对 `pendingChanges` / `fileDiskHashes` 做局部 `synchronized`。`AnonClassAligner` 与 `AlignmentTransaction` 本身对并发调用**不安全**（前者有 `TEST_REVERSE_ORDER` 这类可变静态，后者含非线程安全的 `LinkedHashMap` 与 `boolean committed` 标志）。若将来在别处（如 IDE 主动触发的 `triggerHotswap()`）与监听线程并发调用，需要先补这把锁。

### 6.3 算法复杂度与性能防护 `[部分实现]`
* **最坏复杂度**：单宿主类内对齐时间复杂度为 O(N^2)，其中 N 为匿名类数量。
* **性能防护机制**：
  1. **字节码摘要短路**：比对前计算 SHA-1 摘要，未变动的匿名类直接短路跳过 AST 解析；`[未实现]`
  2. **数量硬上限**：单个宿主类下匿名类数量上限保护 N <= 128，超过则打印告警并降级；`[已实现-口径已修正]`
  3. **计算超时中断**：对齐流程设定 2000ms 软超时，超时自动安全熔断。`[已实现]`

> **实现注记（§6.3）**：第 2、3 条已落地，第 1 条未落地。
>
> * **数量上限**：`AnonClassAligner.MAX_ANON_PER_HOST = 128`，比较口径为 `max(oldInfos.size(), newInfos.size())`，超过即抛 `AlignmentRejectedException` 走 §4.3 拒绝通道。**注意这里有意偏离规格的"打印告警并降级"**：唯一安全的降级是不对齐，因为"降级为按类名照旧重定义"恰好会重新制造编号位移篡夺（详见 `MAX_ANON_PER_HOST` 的 javadoc）。该字段是 `public static`，可在测试/诊断中调小或设为 `Integer.MAX_VALUE` 关闭。
> * **软超时**：`AnonClassAligner.ALIGN_TIMEOUT_MS = 2000`，采用 elapsed 比较（`now - start`，天然免溢出），检查点设在 `parseInfos` 之后、每个层级开头、以及 `matchTier` 的 O(N²) 外层循环。超时同样走 §4.3 拒绝通道。设为 `Long.MAX_VALUE` 关闭；设为 `<= 0` 表示"无预算"，在第一个检查点确定性熔断（便于回归测试，不依赖墙上时钟分辨率）。
> * **摘要短路仍未做**：`parseInfos` 用 `ClassReader.SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES` 只读类结构，`alignCascading` 也只解析一次宿主 `ClassNode` 供全流程复用（`LambdaAligner` 侧同理），但**内容哈希仍要全量解析指令**（`AnonClassHasher` 走完整 `visit*`）。所以"未变动即跳过 AST 解析"这条仍未实现，属**已知的性能缺口**（不是正确性缺口）—— 因为第 2 条上限已经把 worst case 钉死在 N=128。
>
> **当前状态可以这样概括**：常规输入下性能没有问题；worst case 现在**有闸门**（上限 + 超时），但**没有加速**（无摘要短路）。

### 6.4 系统特性开关 `[已实现]`
* `-Dnipx.anonAlign.enabled=true`：总开关（默认开启）。
* `-Dnipx.anonAlign.strict=false`：严格模式开关（开启后遇任何 Tier 4/5 歧义直接熔断）。
* `-Dnipx.anonAlign.debug=false`：诊断日志开关（开启后打印完整的层级决策链）。

> **实现注记（§6.4）**：三个开关已落地，但**属性名以代码库既有约定为主、规格拼写为别名**：
>
> | 规格拼写（§6.4）         | 实现首选拼写             | 默认    | 语义                                                                                                                                      |
> |:-------------------------|:-------------------------|:--------|:------------------------------------------------------------------------------------------------------------------------------------------|
> | `nipx.anonAlign.enabled` | `nipx.agent.anon_align`  | `true`  | 总开关。`false` 时**含匿名类的宿主整体拒绝**，而不是按类名照旧重定义（见 §4.3 关闭语义）                                                  |
> | `nipx.anonAlign.strict`  | `nipx.agent.anon_strict` | `false` | `true` 时 Tier 4 歧义（`stats.ambiguousPairs > 0`）与 `depth > 4` 都升级为宿主级拒绝                                                      |
> | `nipx.anonAlign.debug`   | `nipx.agent.anon_debug`  | `false` | 打印层级决策链（每层 old/new 计数、逐条 `matched`/`unmatched -> 新编号`、作用域收敛、最终 `renameMap`）；亦随全局 `nipx.agent.debug` 打开 |
>
> **为什么首选 `nipx.agent.*` 而不是规格的 `nipx.anonAlign.*`**：代码库中"流水线级开关"一律是 `nipx.agent.*`（共 10 个，其中最近的同类项就是控制 Lambda 对齐的 `nipx.agent.lambda_align`），而 §6.4 的拼写在写入本文时尚未实现。
>
> **为什么仍然兼容规格拼写**：这是一个**止血开关**。对止血开关而言，"操作者照文档写了名字却被静默忽略"比"同一个开关多认一个名字"危险得多 —— 所以 `HotSwapAgent.boolProp(primary, alias, def)` 在首选名缺失时回退到规格名。代价是文档里要同时列出两种写法（如本表）。
>
> **开关是在类初始化期读取的**（字段初始值即 `boolProp(...)`），而不是只在 `initConfig()` 里赋值 —— 这样即便某条代码路径没走 `initConfig()`（测试、嵌入式调用），默认值也仍然是安全的 `true`/`false`，且总开关不会因为初始化顺序而静默变成"关闭"。
>
> **回归覆盖**：`strict` 由 `AnonClassReproTest` Scenario 21 覆盖（含"非严格模式下退化为孤儿"的对照断言）；`enabled` 的三种宿主组拒绝路径与 `[HOTSWAP-REJECT]` 文案目前**没有自动化断言**（需要走完整 `processChanges` 流水线），已记入 §8.3。


---

## 7. 蜕变测试准则与风险清单

### 7.1 蜕变测试套件 (Metamorphic Testing Suite) `[已实现]`
在回归测试套件中引入以下强预言蜕变测试：
1. **语义标记置换不变性 (Semantic Tag Permutation Invariance)**：
   为每个测试匿名类嵌入唯一的语义字符串（如 `"TAG_SAVE"`）。对新旧输入集合执行随机打乱，断言所有映射关系恒定，且目标语义标记 100% 精准对应。
2. **幂等性测试 (Idempotence)**：
   对同一份新编译产物连续执行两次对齐，断言第二次对齐的映射结果为恒等映射（无多余改名）。
3. **尾部追加不变性 (Append Invariance)**：
   在源码末尾追加全新的匿名类，断言之前所有已配对的一级与嵌套匿名类映射结果不受任何扰动。
4. **故障注入拒绝率 (Failure Injection Reject Rate)**：
   故意构造高危歧义场景（如两个完全同构且无结构差异的候选），断言系统 100% 触发 Reject & Rollback，无静默错配。

> **实现注记（§7.1）**：第 1~3 条**已落地**于 `AnonClassReproTest` Scenario 19（`permPassed` / `isIdentity` / `appendInvariant` 三条断言），并在 `TEST_REVERSE_ORDER` 钩子下额外做正反序双向断言（Scenario 8/16）。
>
> **第 4 条拆成两个可分别验证的断言后，现均已落地**：
> * **"无静默错配"** ✅ —— Scenario 20 断言"结构完全不同、前 4 层均无法匹配的匿名类绝不按名字盲配（孤儿数 = 2）"，Scenario 16 断言"双向唯一匹配消除遍历顺序依赖"。
> * **"触发 Reject & Rollback"** ✅ —— Scenario 21 用一对二 Tier 4 歧义夹具断言：非严格模式下退化为"不配对 + 2 孤儿 + 1 新增"，严格模式（`-Dnipx.agent.anon_strict=true`）下抛 `AlignmentRejectedException` 并携带宿主名；同一 Scenario 另断言数量上限（§6.3-2）、软超时（§6.3-3）与后置校验（§4.3-③）都复用同一个拒绝通道。
>
> 因此本条现在可以按"**断言零静默错配 + 断言两种模式下的差异化行为**"来读，与 §4.3 的实现一致。

### 7.2 风险登记簿与未决问题 (Risk Register & Open Questions) `[未实现]`
* **风险 1：超长生命周期实例的字段布局差异** —— `[已做真机实测；门未实现]`
  * *缓解（规格原意）*：若检测到匿名类的捕获字段数量或类型发生变更，在重定义时记录告警，提示实例内存迁移风险。

  > **✅ 真机实测结果（JBR 21.0.9 + `-XX:+AllowEnhancedClassRedefinition`）**
  >
  > 探针：`scratch/layoutprobe/`（`run.sh` 一键复现）。做法：编译 v1/v2 两份 `Subject`（**方法体逐字节相同，只改字段表**），
  > v1 经独立 `URLClassLoader` 加载、造存活实例、把 `spin()` 跑到 3 万次触发 JIT，然后 `redefineClasses` 到 v2，
  > 再用反射读旧实例上的字段。每个 case **独立 JVM**；另用 `-XX:-AllowEnhancedClassRedefinition` 做对照。
  >
  > | case | 增强开：redefine | 旧实例上的值 | 方法是否仍正常 | 增强关（对照） |
  > |:---|:---|:---|:---|:---|
  > | 加 `int` 字段 | ✅ 接受 | 旧字段保留（`a=7`），**新字段 = 默认值 `0`** | ✅ | ❌ 被 JVM 拒绝 |
  > | 加 `String` 字段 | ✅ 接受 | 旧字段保留，**新字段 = `null`** | ✅ | ❌ 被 JVM 拒绝 |
  > | 删字段 | ✅ 接受 | 其它字段保留，被删字段消失 | ✅ | ❌ 被 JVM 拒绝 |
  > | `int` → `long` | ✅ 接受 | **旧值被丢弃，新字段 = `0`** | ✅ | ❌ 被 JVM 拒绝 |
  > | `int` → `String` | ✅ 接受 | **旧值被丢弃，新字段 = `null`** | ✅ | ❌ 被 JVM 拒绝 |
  > | `Object`(值=`7`) → `String` | ✅ 接受 | **旧值被丢弃，新字段 = `null`**（不是"复制引用"） | ✅ | ❌ 被 JVM 拒绝 |
  > | 实例字段 → 静态字段 | ✅ 接受 | **旧值被丢弃，新值 = `0`** | ✅ | ❌ 被 JVM 拒绝 |
  > | 静态字段 → 实例字段 | ✅ 接受 | **旧值被丢弃，新值 = `0`** | ✅ | ❌ 被 JVM 拒绝 |
  >
  > **对照组的错误信息统一是**：`java.lang.UnsupportedOperationException: class redefinition failed: attempted to change the schema (add/remove fields)`。
  >
  > **结论 —— 落在三种可能里的第 2 种**：
  > * **不是 (1) JVM 自己拒绝**：增强模式下 **8/8 全部接受**，无 `LinkageError`、无崩溃。
  > * **不是 (3) 静默错解释**：没有观察到"拿旧字节当新类型读"；新字段干净地取默认值。
  > * **是 (2) 丢弃并置默认值**（同名字段改类型 / 改静态性），加字段则是"旧值保留 + 新字段默认值"。
  > * 且 **`-XX:-AllowEnhancedClassRedefinition` 下 8/8 由 JVM 自己拒绝** → 字段表门**只在增强模式下才有意义**；非增强环境里 JVM 自带这道门（错误信息可直接透传给用户）。
  >
  > **因此分级门（风险 1 的缓解方案）是正确路径，且判据已现成**：`ClassDiff.changedFields` 用的是
  > `compositeHash(name, desc)`，于是四种情形在它的列表里可以**直接配对区分**：
  > * **纯加字段** → 只有 `+ name` → **放行**（JVM 语义正确：旧值保留、新字段默认值，正是"热更时新增捕获"的期望语义）；
  > * **删字段** → 只有 `- name` → **拒绝或强告警**（旧值静默消失，用户会以为无损）；
  > * **同名字段改类型** → **同名同时出现 `- a` 与 `+ a`**（desc 不同导致旧 key 不匹配）→ **拒绝**；
  > * **静态性变更** → `- *a` / `+ a`（带 `*` 前缀标记静态）→ **拒绝**。
  > 即门只需按名字把 `+`/`-` 配对，比重写一个字段布局比较器便宜得多。**建议做在事务/重定义层**（`applyRedefinitions` 之前），这样具名类与匿名类一起被覆盖。
  >
  > **本次实测未覆盖（要诚实标注）**：① 方法**不读**被改字段，所以"JIT 编译过的代码如何看待新布局"未验证；② 未测 `volatile` / `final` / 引用类型具体子类的多字段组合；③ 未测继承层次中的字段（父类字段与子类遮蔽）；④ 未在 JBR 25 上重复；⑤ 未测"改类型后方法仍能读到旧值"的反射之外路径。

  > **实现注记（风险 1 —— 本文最被低估的一条）**
  >
  > **现状**：代码中没有任何"前后捕获字段表不一致即告警"的检查。相关信号其实**已经被算出来了**：`ClassDiffUtil.ClassDiff.changedFields` 会记录字段增删（键是 `compositeHash(name, desc)`，所以**类型变更也会被识别为 `- 旧` + `+ 新`**），`structureChanged()` 也会因此为真 —— 但 `HotSwapAgent` 只拿它**打日志**（`STRUCTURAL CHANGE DETECTED!` / `[DCEVM] ... proceeding`），**不 gate 任何类的重定义，匿名类与具名类都不 gate**。也就是说：本系统目前**没有实例状态布局守卫**，"字段布局变了仍照常 redefine"这条路径对**所有**类都是敞开的，匿名类对齐只是其中一条触达路径。
  >
  > **为什么这比"配错名字"更危险**：配错的后果被 Tier 设计限制在"新增/孤儿"（老实例保持旧语义，只是陈旧）；而跨布局的原地重定义会让**老实例的字段值被新字节码以不同布局解释** —— 直接破坏"存活实例状态可被同样语义解释"这一核心不变量。前者是保守性损失，后者是状态损坏。
  >
  > **为什么这不是边角路径（这正是评审把它的优先级往前提的理由）**：`Tier 1/2` 的内容哈希**含字段表**，`Tier 3` 显式比较 `fields`，所以只有 `Tier 4` 能跨字段布局配对。而 Tier 4 是双向唯一即配对（`allowMinDiff=false`）。于是当"某个方法里只有一个匿名 `Runnable`、用户改了它捕获哪个局部变量"时：哈希变 → Tier 1/2 失败；`fields` 变 → Tier 3 失败；Tier 4 双向唯一 → **配对成功并原地重定义**。这不是罕见构造，而是**"改了捕获变量"这个常见编辑的默认走法**。
  >
  > **建议的守卫形态（分级，而非一刀切）**：`Tier 4` 的谓词增加"字段布局兼容"检查，按变更类型分级：
  > | 字段布局变更 | 建议动作 | 理由 |
  > | :--- | :--- | :--- |
  > | 纯新增字段 | **放行 + 记日志** | DCEVM 增强重定义支持加字段，老实例的新字段取默认值 —— 这正是"热更时新增捕获"的期望语义 |
  > | 删除字段 / 改变字段类型（同名不同 desc） | **拒绝配对**（判为新增旧类孤儿） | 老实例的既有值无处安放，只能被丢弃或按错布局重解释 |
  > | 静态性变更（`static` ↔ 实例） | **拒绝配对** | `ClassDiff` 已能识别（`- *name` / `+ *name`） |
  >
  > **更精确的变体（可选，代价更高）**：只有当**该旧类存在存活实例**时，布局差异才危险；否则重定义任意布局都无害。`hotswap-agent` 已经有现成能力 —— `LibTool.getInstances(clazz)`（`HotSwapAgent` 与 `InitFix` 都在用，`LibTool.initialized()` 失败时回退 `InstanceTracker`）。因此可以只在"存在存活实例且布局不兼容"时拒绝，避免误伤"类刚加载还没实例"的正常热更。建议**先落地便宜的静态门**（上表），把堆扫描版本留作后续精化。
  >
  > **归属建议（架构层面）**：这道门应该做在**事务/重定义层**（`ClassDiffUtil` / `applyRedefinitions` 一侧），而不是只塞进 `AnonClassAligner` —— 因为具名类的字段类型变更同样没有守卫，而那是用户显式编辑，语义上更该由统一的重定义门来判。对齐器需要额外做的只有一件：**用户只改了外层方法、却在背后触发了匿名类布局变化**，这种"用户没主动要求改字段"的情形必须由对齐器自己拒绝配对。
* **未决问题 1：ECJ 编译器的 EnclosingMethod 特殊表现**  
  * *跟踪*：当前已在 javac 8/11/17/21 上完成完备实测，后续需对 Eclipse ECJ 编译器生成的嵌套匿名类展开真实样本集差分测试。
  * **[仍开放]** —— `scratch/hstest` 下的夹具与 `suite.sh` 只覆盖 javac 8/17/21，没有 ECJ 产物。代码侧对 ECJ 仅有一处顺带处理：`MethodFingerprinter.isExcluded` 把 `$SWITCH_TABLE$`（Eclipse 的 switch 表方法名）列入排除 —— 那是**方法**名，与本文 §2.1 讨论的**类**名无关，ECJ 的匿名类准入/`EnclosingMethod` 行为仍未经任何真实样本验证。

---

## 8. 实现状态总表与验收基线

> 对齐日期：`7c19e6e6` + 「§4.3 拒绝通道 / §6.3 闸门 / §6.4 开关」一轮（未提交）。约定：`[已实现]` 语义为"有代码 + 有回归断言"；`[部分实现]` 为"主路径可用，但规格中的某些子项未做"；`[已偏离-有意保留]` 为"实现有意不照规格做，且已在原节写明理由"。

### 8.1 逐条状态

| 规范条目                                                        | 状态               | 落地位置 / 证据                                                                                                                                                                |
|:----------------------------------------------------------------|:-------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| §1.1 受支持范围（javac 8/11/17/21）                             | ✅                 | `suite.sh` 用 `HSTEST_JAVAC8/17/21` 三套 javac 编译夹具                                                                                                                        |
| §1.1 受支持范围（ECJ）                                          | ⬜                 | 无夹具（§7.2 未决问题 1）                                                                                                                                                      |
| §1.2 类身份与实例状态保真不变量                                 | ✅                 | 移除 Tier 5 + `orphanOldClasses` 保留 + 新类分配未占用编号                                                                                                                     |
| §1.3 `renameMap` 方向与 `ClassRemapper` 改写范围                | ✅                 | `AnonClassAligner.remapClass`（`ClassWriter(0)` + `SimpleRemapper`）                                                                                                           |
| §2.1 Attribute-First Admission                                  | 🔶                 | `isAnonymousClass` / `isAnonymousClassName`；**枚举 Switch 映射表排除缺失**、**未强制 `EnclosingMethod`**                                                                      |
| §2.2 全局保留名集合                                             | 🔶                 | `takenTargetNames`；**第 3 类未显式加入、第 4 类（`pendingAlignedClasses`）完全未接入**                                                                                        |
| §3 层级交错流水线                                               | 🔶                 | `alignCascading` 层级循环；Lambda 对齐在独立第二遍；`depth > 4` 非 strict 下只 warn                                                                                            |
| §3.1 自身哈希（包含特征）                                       | ✅                 | 未含访问标志（不影响匹配）；`#ANON_relId#` 为增强形式；合成捕获字段**有意计入**。**描述符定向屏蔽缺陷已修复**（嵌套匿名类全层恢复 Tier 1，Scenario 22 守卫），见 §3.1 注记     |
| §3.1 排除项 1/2（调试元数据、lambda 名归一化）                  | ✅                 | `SKIP_DEBUG`；`isSelfSynthetic` → `#SYNTHETIC_METHOD#`                                                                                                                         |
| §3.1 排除项 3（`access$` → `#ACCESS_METHOD#`）                  | ➖ 已偏离-有意保留 | 保名不改名策略，理由见 §3.1 注记                                                                                                                                               |
| §3.1 排除项 4（剥离子匿名类 NEW / 子类引用多重集）              | ➖ 已偏离-有意保留 | 内容哈希被折入指纹，理由与证据见 §3.1 注记                                                                                                                                     |
| §3.2 宿主粗粒度签名隔离                                         | ➖ 已偏离-有意保留 | `#ANON_COARSE` 未实现且不应按字面实现，见 §3.2 注记                                                                                                                            |
| §3.3 未匹配子类前缀派生                                         | ✅                 | `alignCascading` 的 `level > 1` 分支；Scenario 18/19                                                                                                                           |
| §3.4 调用链三要素比对 + 深度 32                                 | ✅                 | `findCallerMethod`；Scenario 12/17                                                                                                                                             |
| §3.5 非方法上下文宿主归类                                       | 🔶                 | 靠 `outerMethod == null` 退化，无 `<initializer>`/`<clinit>`/`<init>` 三态。**回退扫描上下文已修**：改用直接父类节点（javac 8 `lambda$null$N` + 嵌套匿名类，Scenario 24 守卫） |
| §3.6 Lambda/匿名类边界（INV-1 自描述指纹 / INV-2 禁止互相递归） | ✅                 | INV-1/INV-2 均已落成回归断言（Scenario 25，含负向/正对照与常量池架构守卫）；lambda 侧仍是 1 层折叠（§3.1 排除项 4 的偏离）；见 §3.6                                            |
| §3.6 提案的"异构拓扑树"                                         | ➖ 不需要          | 实测 javac 中 lambda **不构成命名层级**（`Alt$1$1` 而非 `Alt$1$1$1`），匿名类包含树已由 `$` 前缀完全表达；无须合并两棵树（§3.6）                                               |
| §4.1 Tier 1 / 2 / 3 / 4                                         | 🔶                 | `matchTier` + 双向唯一。**Tier 3 的 minDiff 已被拓扑相等过滤取代**（前插 + 改体不再错配）；Tier 1 的 minDiff 未动（实测 0 命中）                                               |
| §4.1 嵌套深度支持范围（§1.1 声明 depth ≤ 4）                    | ➖ 声明无效        | `maxLevel > 4` 仅 warn；`AnonClassHasher.MAX_DEPTH` 为**不可达死代码**；探针实测 depth 8 逐层正确（§3 注记）                                                                   |
| §4.1 Tier 3 拓扑相等过滤（新增维度）                            | ✅                 | `TopologySignature` + `applyTopologyFilter`；`topologyMatches` 独立计数；Scenario 26 守卫（T 判对 / M 拒绝且 T4 不绕过 / L 记 KNOWN）                                          |
| §4.1 夹具 L：2x2 同构原地改体                                   | ➖ 有意代价        | 拓扑全等 -> 不仲裁 -> 拒绝配对；KNOWN 条目钉住（根治需 Tier 1.5）                                                                                                              |
| §4.1 Tier 1.5（相似度）                                         | ⬜                 | 无相似度计算；`tier5Matches` 为死字段。**拓扑过滤只做“相等”：拓扑无信息的候选集（夹具 M）与 2x2 同构原地改体（夹具 L）仍只能拒绝 —— 根治仍需本行**                             |
| §4.2 `sourceOrder` 多轮基线                                     | ⬜                 | 无 `sourceOrder`；`diff` 用物理名序号                                                                                                                                          |
| §4.3 拒绝通道（宿主组整体拒绝 + `[HOTSWAP-REJECT]`）            | ✅                 | `AlignmentRejectedException` + `HotSwapAgent.rejectHostGroup`；Scenario 21                                                                                                     |
| §4.3-① Tier 4 平局拒绝                                          | ✅                 | 非 strict：统计 `stats.ambiguousPairs` 后退化为新增/孤儿；strict：拒绝宿主组；Scenario 21                                                                                      |
| §4.3-② `depth > 4` 熔断                                         | 🔶                 | strict 下拒绝宿主组；非 strict 仍只 `warn`。**无独立回归夹具**（需 depth ≥ 5 真实样本）                                                                                        |
| §4.3-③ 后置校验失败回滚                                         | ✅                 | `validateRenameMap` 改抛 `AlignmentRejectedException`，与 ①②④⑤ 共用同一拒绝通道；Scenario 21                                                                                   |
| §6.1-1/3 Nest/InnerClasses 改写 + 加载期拦截                    | ✅                 | Scenario 11                                                                                                                                                                    |
| §6.1-2 继承体系变更                                             | ➖ 与现状相反      | `hierarchyChanged` 一律拒绝并要求重启                                                                                                                                          |
| §6.2 事务生命周期 + 乐观预登记                                  | ✅                 | `AlignmentTransaction` + 批量失败降级单类 + 事务组一致性校验                                                                                                                   |
| §6.2 全局独占重入锁                                             | ⬜                 | 仅靠单监听线程 + 防抖                                                                                                                                                          |
| §6.3-1 字节码摘要短路                                           | ⬜                 | 未做（仍是全量指令解析）；已有 `SKIP_CODE` 等部分缓解                                                                                                                          |
| §6.3-2 数量硬上限 `N <= 128`                                    | ✅                 | `MAX_ANON_PER_HOST`，超过即走拒绝通道（有意不"降级为不重命名"）；Scenario 21                                                                                                   |
| §6.3-3 2000ms 软超时                                            | ✅                 | `ALIGN_TIMEOUT_MS` + `parseInfos`/每层/`matchTier` 检查点；Scenario 21                                                                                                         |
| §6.4 三个系统属性开关                                           | ✅                 | `nipx.agent.anon_align/anon_strict/anon_debug`（`nipx.anonAlign.*` 为兼容别名）；`strict` 由 Scenario 21 覆盖                                                                  |
| §7.1-1/2/3 置换不变性 / 幂等 / 尾部追加                         | ✅                 | Scenario 19                                                                                                                                                                    |
| §7.1-4 故障注入拒绝率                                           | ✅                 | "零静默错配"（Scenario 16/20）+ "两种模式差异化行为"（Scenario 21）                                                                                                            |
| §7.2 风险 1 字段布局差异                                        | 🔶                 | **真机实测已做**（JBR 21 增强模式：8/8 接受，改类型/改静态性 = 旧值静默丢弃并置默认值；非增强模式 8/8 被 JVM 拒绝）；**分级门未实现**，判据已现成（见 §7.2 注记）              |
| 实例状态布局守卫（全体类，非仅匿名类）                          | ⬜                 | `ClassDiff.changedFields` / `structureChanged()` 仍只用于日志，不 gate 重定义；但实测已确认判据充分（§7.2 注记），落地在事务/重定义层                                          |
| §7.2 未决问题 1 ECJ                                             | ⬜                 | 无样本                                                                                                                                                                         |

### 8.2 验收基线（回归门槛）

本系统的回归门槛**不在 Gradle 的 `test` 任务里**，而是 `scratch/hstest` 下的一组 main 程序，经 `suite.sh` 由 `:hstestRun`（`Exec`，走 Git Bash）驱动，并已挂在根项目的 `check` 上：

* **入口**：`./gradlew check` → `:hstestRun` → `scratch/hstest/suite.sh`。
* **本主题直接相关**：`AnonClassTest`（Save/Delete 静默对调防线）、`AnonClassReproTest`（**26 场景**，§1~§4 的绝大多数断言来自它；Scenario 21 专测 §4.3 拒绝通道与 §6.3/§6.4 闸门与开关，Scenario 22 专测嵌套匿名类的内容哈希可用性与设计不变量，Scenario 23 专测 strict 的行为边界，Scenario 24 专测 javac 8 回退扫描上下文，Scenario 25 专测设计不变量 INV-1/INV-2，Scenario 26 专测 Tier 3 拓扑相等过滤）。
* **同一次运行还包括**（Lambda 对齐主题，与本主题共用夹具与哈希器）：`SemAssert`、`CompeteDeleteTest`、`PassBTest`、`NameIndexTest`、`FixtureATest`、`XGroupTest`。
* **数量基线**：`scratch/hstest/expected-count.txt` 记录 `<通过> <失败> <已知限制>` 三元组，实测值与基线不符即构建失败；另有"一条断言都没执行即判 FAIL"的空绿金丝雀。当前基线 `266 0 4`（KNOWN 的第 4 条即夹具 L 的保守代价）。
* **注意**：`AnonClassReproTest` 的夹具由脚本按 JDK 版本分别编译（同包同名不能混编），且 `hstestRun` 依赖 `hotswap-agent` 的 **jar 重建** —— hstest 的 `runtimeClasspath` 解析到的是 `build/libs` 下的 jar 而非 `classes` 目录，少了这一步会静默跑陈旧产物。

### 8.3 建议的实施优先级

按"风险 ÷ 成本"排序，均为独立小改动，互不阻塞。**排序依据不是"规格完成度"，而是"这条不做会不会让线上 JVM 进入不一致状态"**：

1. ~~**§6.4 + §6.3 开关与熔断**（`enabled/strict/debug`、`N <= 128`、2000ms 软超时）~~ ✅ **已完成**（`nipx.agent.anon_align/anon_strict/anon_debug` + `MAX_ANON_PER_HOST` + `ALIGN_TIMEOUT_MS`；默认值保持原行为，既有断言全绿）。它是唯一对"未知风险"也有效的缓解手段 —— 其余各项都只针对某个已知缺陷，而这一项决定了出事时能不能立刻回到"不干预"。
2. ~~**§4.3 对齐期异常路径的宿主级拒绝**（`[HOTSWAP-REJECT]` + 宿主组整体移出本批）~~ ✅ **已完成**（统一拒绝通道 `AlignmentRejectedException` → `HotSwapAgent.rejectHostGroup`；见 §4.3 注记里对"半提交"疑点的更正）。属**事务完整性**问题，与算法保守性不是一个层级。
3. ~~**修 `AnonClassHasher` 未屏蔽的方法描述符**~~ ✅ **已完成**（改为走 `MethodFingerprinter.maskDescriptor`，定向屏蔽；嵌套匿名类**全层恢复 Tier 1**，`T1` 由恒为 1 变为等于嵌套层数）。守卫断言已进 `check`：`AnonClassReproTest` Scenario 22（4 层嵌套 T1=4 / T4=0；描述符定向性反例；层级守卫）。附带收益：不再依赖"不比字段表"的 Tier 4，等于把 §7.2 风险 1 的暴露面收窄一大半。
4. ~~**决定 Tier 3 minDiff 策略**~~ 🔶 **部分缓解**：Tier 3 的 minDiff 仲裁已被**拓扑相等过滤**取代（见 §4.1 拓扑判据注记）。夹具 T 判对、夹具 M 与 L 拒绝（L 记 KNOWN）。**仍开放**：拓扑无信息的候选集与 2x2 同构原地改体只能拒绝 —— 根本修法仍是 ③ **Tier 1.5 相似度**。④ 曾评估的 carve-out（"仅在 minDiff 能给出完全匹配且序号单调时才允许仲裁"，可保住 L）**已否决**：它仍依赖物理序号，"删一个 + 插一个"这类两侧等量编辑会满足完全匹配却判错。
5. ~~**`parseInfos` 改用直接父类节点做实例化点扫描**~~ ✅ **已完成**（level 1 复用 `hostNode`，嵌套层按需解析并缓存直接父类节点）。靶子经实测收窄为 javac 8 的 `lambda$null$N`（`lambda$work$0` 不触发）；修复前该形态下两侧 `outerMethod` 都停在 `"null"`、方法作用域判据被抹平，导致"方法顺序反转 + 两侧改体"时两条同构链**跨方法错配**。守卫断言已进 `check`：`AnonClassReproTest` Scenario 24（真实 javac 8，6 条）。附带确认 javac 8 的嵌套命名同样是 `$1$1`。
6. ~~**把 INV-1 / INV-2 写进代码注释与测试**~~ ✅ **已完成**（§3.6）：`AnonClassReproTest` Scenario 25 用"只改子层 → 父层指纹必须不变"把 INV-1 变成受保护断言（若有人让 `AnonClassHasher` 恢复递归折入子哈希，此处立刻变红），并用字节码常量池扫描做 INV-2 架构守卫。
7. **§7.2 风险 1 升级为实例状态布局安全门**（分级：纯加字段放行 + 日志；删字段 / 改类型 / 改静态性拒绝配对）。理由是它**不是边角路径而是默认路径**（见 §7.2 注记），且后果是"老实例状态被错布局解释"，比配错名字更重。建议这道门做在**事务/重定义层**（顺带覆盖具名类），对齐器只额外拒绝"用户没要求改字段却在背后发生的布局变化"。**建议先补真机实验**：在 `scratch/hstest/src/LiveDcevmTest.java` 的 JBR + `-XX:+AllowEnhancedClassRedefinition` 路径上跑一例"捕获变量类型变更后原地重定义"，看 JBR 是拒绝、复制旧值、还是静默错解释 —— 三种结果对应三种门。
8. **§2.1 枚举 Switch 映射表排除 + §2.2 第 4 类保留名接入**（`align` 增 `Set<String> reserved` 参数）。两者都能写确定性的负向断言。
9. **§3.1/§3.2 的规格收敛**：先把文档改成"主哈希 + 子类引用多重集（并列独立维度）"的目标形态，再考虑实现；**在给出能同时满足父哈希稳定与 AnonCase 同构可区分的判别式之前，不要动 `MethodFingerprinter` 的匿名类占位符**。同批应一并把 §3.1 包含特征第 2 条的"非合成字段"改成"含合成捕获字段"，因为它现在是安全信号而非噪声。
10. **补三个自动化缺口**（都属已实现但未覆盖）：① `depth > 4` 的 strict 熔断（现在有探针夹具可复用，把 `DeepNestProbe` 的 depth ≥ 5 用例搬进 Scenario 21 即可）；② `[HOTSWAP-REJECT]` 与"宿主组整体移出本批"的端到端断言（需让 `processChanges` 可测，或把拒绝决策抽成可单测的纯函数）；③ **把"用了哪一层"纳入断言**（`stats.tier1Matches` 应等于嵌套层数）—— 现有 21 个场景只断言"映射对不对"，这正是 §3.1/§4.1 两个缺陷能长期潜伏的原因。
11. **§4.2 `sourceOrder`**：先写能红的夹具（多轮 + 同级平局），再决定是否扩 `bytecodeCache` 的数据结构。属稳定性增强，不是 correctness blocker（Tier 1/2 不依赖它）。注意 §4.1 注记里的错配**不是** `sourceOrder` 能修的（该场景下两种距离度量都选错）。
12. **§6.3-1 摘要短路 / §6.2 全局锁**：收益明确但不紧急，可并入后续迭代。注意 §6.3-2 的硬上限已经封住 worst case，因此摘要短路现在是纯加速项。

> **关于第 1 与第 2 条的排序**：有评审意见把"事务回滚"排在"总开关"之前。本文档维持**开关优先**，理由是二者解决的问题不同维度 —— 回滚修的是一个**已知**的窄窗口（对齐期异常），开关提供的是对**未知**风险的统一止血能力（默认 `true` 不改变任何现有行为）。工程上"先装上刹车再修发动机"通常是对的。两条现已一并落地。
