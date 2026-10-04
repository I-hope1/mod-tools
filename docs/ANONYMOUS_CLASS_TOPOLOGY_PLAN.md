# 匿名内部类树状拓扑与混合闭包对齐技术规范 (v3.0 终稿)
(Anonymous Class Cascading Tree Topology & Hybrid Closure Pipeline Specification)

> **文档版本**：v3.0 (Comprehensive Engineering Specification)  
> **适用目标**：JDK 8 ~ 21 (基于 JBR-21 / DCEVM 增强类重定义能力)  
> **核心原则**：**在类热重载中，显式拒绝并原子回滚（Reject & Rollback）远胜于隐式错配导致的方法篡改（Silent Misbinding）**。

---

## 1. 系统目标、核心不变量与映射契约

### 1.1 系统目标与范围
本系统用于解决增强类重定义下，由于源码改动（增删、换序、变量捕获变动）导致匿名内部类（`Foo$N`）及 Lambda 表达式在重新编译后序号漂移，进而引发的**存活实例方法表篡改（Method Hijacking）**与**未对齐类加载错位**问题。

* **受支持范围**：
  * Java 源码编译器（javac 8, 11, 17, 21 与 ECJ）编译的标准类、一级匿名类、多层嵌套匿名类（`Foo$1$1`）以及 Lambda 混合嵌套结构。
  * 闭包嵌套深度 depth <= 4 的复合结构。
* **边界与非目标（Non-Goals）**：
  * **不跨宿主方法物理迁移实例**：若开发者将匿名类从 `methodA` 移动到 `methodB`，系统将其归入“旧类删除 + 新类新增”，不跨宿主方法篡夺老实例。
  * **不接管 Kotlin 编译器跨文件内联展开类**：Kotlin 内联函数的跨文件代码编织不在通用拓扑树中展开。

### 1.2 核心不变量（Core Invariant）
> **类身份与实例状态保真不变量**：  
> **在 JVM 中已经存在存活实例的已加载类（Loaded Class），其物理类名只能被源码上与之对应的新版本实现重定义，其既有存活实例的方法调用与字段状态必须维持预期的语义连续性，禁止被无关的新生类占用物理槽位。**

### 1.3 映射方向与改写范围
* **映射方向定义**：
  `renameMap : 新编译类名 (New Name) -> 目标类名 (Target Name)`
  * **命中已加载旧类**：若新编译类匹配到了已加载的旧类（例如新 `Foo$2` 匹配老 `Foo$1`），目标类名设为旧类名 `Foo$1`。随后通过 `ClassRemapper` 将新字节码改名为 `Foo$1`，使 `redefineClasses` 精准更新老类。
  * **全新未加载类**：若新类为全新类（无对应历史类），分配一个未被占用的安全类名（例如 `Foo$3`），通过 `pendingAlignedClasses` 在初次加载时拦截生效。
* **ClassRemapper 改写范围**：
  1. 指令流与常量：`NEW`, `CHECKCAST`, `INSTANCEOF`, `ANEWARRAY`, `MULTIANEWARRAY`, `LDC` 类常量
  2. 方法与字段调用：`INVOKESPECIAL`, `INVOKEVIRTUAL`, `INVOKESTATIC`, `INVOKEINTERFACE`, `GETFIELD`, `PUTFIELD`
  3. JVMS 类结构属性：`InnerClasses`, `EnclosingMethod`, `Signature`, 运行时注解与异常表
  4. 嵌套特权属性 (JDK 11+)：`NestHost`, `NestMembers`
* **栈图处理与限制**：
  * 栈图（`StackMapTable`）中的类型引用随 ASM `ClassRemapper` 联动转换，不使用耗时的 `COMPUTE_FRAMES`（避免在类转换期触发类加载重入死锁）。
  * 字符串字面量（如反射 `Class.forName("Foo$2")`）不在字节码 Remapper 改写范围内。未显式声明 `serialVersionUID` 的类，其默认序列化哈希受类名影响。

---

## 2. 匿名类元数据判定与保留名体系

### 2.1 基于类属性的权威判定（Attribute-First Admission）
判定一个类是否为不稳定匿名类，以 ClassNode 的字节码属性为第一准则，类名模式为辅助：

| 分类 | 字节码判定准则 | 处理动作 | 级联树定位 |
| :--- | :--- | :--- | :--- |
| **标准匿名类** | `InnerClasses` 属性中 `innerName == null`，且包含 `EnclosingMethod` | **准入** | 提取其宿主类与父级前缀，加入拓扑树 |
| **多层嵌套匿名类** | `innerName == null`，其 `EnclosingMethod` 指向另一个匿名类 | **准入** | 逻辑父为该外层匿名类 |
| **具名内部类** | `innerName != null` 且不为纯数字 | **排除** | 不对齐，父前缀路径完全冻结 |
| **具名局部类** | `innerName != null` 且包含局部命名前缀 | **排除** | 保持原有类名 |
| **javac 枚举 Switch 映射表** | `ACC_SYNTHETIC` + 仅含 `$SwitchMap$` 字段 | **排除（保留）** | 识别为编译器缓存，保持原名直通 |
| **Kotlin When 映射类** | 类名含 `$WhenMappings` | **排除** | 保持原名直通 |
| **枚举常量体** | 带有 `ACC_ENUM` 且基类为直接封闭枚举 | **排除** | 声明顺序与常量严格绑定，不可重命名 |

### 2.2 全局保留名集合（Reserved Names Domain）
在为未匹配类分配新编号时，`takenTargetNames` 集合必须严密涵盖以下范围，杜绝任何物理碰撞：
1. **已分配目标名**：当前对齐批次中已被采纳的所有目标类名；
2. **已加载及孤儿旧类名**：JVM 运行时当前仍加载在内存中的所有历史类名（含已删除但未被 GC 卸载的孤儿类）；
3. **保留与排除类名**：枚举 Switch 映射表、局部类等未参与对齐的固有类名；
4. **加载期拦截挂起名**：当前已登记在 `pendingAlignedClasses` 中的未加载类名。

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

### 3.1 自身哈希 (Self Hash) 规范与伪循环剥离
在预提取阶段，`AnonClassHasher` 计算的自身哈希必须与外部依赖解耦：
* **包含特征**：
  1. 基类全限定名，以及排序后的接口全限定名列表；
  2. 非合成字段的名称、类型描述符与访问标志；
  3. 非合成方法的名称、方法描述符与归一化指令序列；
  4. 相对槽位占位符 `#ANON_relId#`。
* **排除与归一化特征**：
  1. **剥离调试元数据**：忽略 `LineNumberTable`, `LocalVariableTable`, `SourceFile`；
  2. **屏蔽私有合成闭包**：所有以 `lambda$` 开头的方法名统一归一化为 `#SYNTHETIC_METHOD#`，其描述符归一化为 `#SYNTHETIC_DESC#`；
  3. **屏蔽编译器访问器**：`access$000` 等外部访问桩统一归一化为 `#ACCESS_METHOD#`；
  4. **剥离子匿名类实例化指令**：**对子匿名类的 NEW 与初始化调用指令从主哈希中剥离**，改记为独立的“子类引用多重集（Child Invocation Multiset）”。
     * **核心价值**：父匿名类方法体内新增子匿名类时，父类的 Self Hash **绝对保持恒定**，不会引发雪崩。

### 3.2 宿主粗粒度签名隔离
在宿主方法对齐外层 Lambda 时，宿主指令流中的匿名类创建指令仅嵌入粗粒度签名：
`#ANON_COARSE(superName;interfaces;constructorParamTypes)#`
不嵌入匿名类的方法体内容哈希，确保匿名类内部逻辑修改不会破坏外层宿主 Lambda 的指纹。

### 3.3 未匹配子类基于映射后父名前缀派生
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

### 3.4 宿主方法追溯调用链防护
`findCallerMethod` 逆向追溯调用链时执行强校验：
1. **三要素联合比对**：强制校验 `hostNode.name.equals(owner)`、`calleeName.equals(name)` 以及 `calleeDesc.equals(desc)`；
2. **死循环与深度截断**：`visited` 集合记录 `methodName + ":" + methodDesc`，硬编码最大追溯深度 depth <= 32。超过深度未定位则返回 null 触发降级。

### 3.5 非方法上下文的宿主归类
对于非普通方法的匿名类：
* 位于字段初始化器、实例初始化块时：`EnclosingMethod.methodName == null`，其作用域归类为 `<initializer>`；
* 位于静态初始化块时：归类为 `<clinit>`；
* 位于构造器时：归类为 `<init>`。
在同一初始化作用域内，优先比对 Self Hash，同构者按源码出现顺序（`orderIndex`）对齐。

---

## 4. 梯队判定决策表与失败熔断机制

### 4.1 多梯队判定动作决策表
| 梯队 | 判定条件 | 置信度 | 唯一候选时的动作 | 多候选平局时的动作 |
| :--- | :--- | :--- | :--- | :--- |
| **Tier 1** | **Self Hash 相同 + 宿主方法相同 + 描述符相同** | 极高 (0.99) | 立即采纳锁定 | 按 `PAIR_COMPARATOR` 数值 minDiff 仲裁 |
| **Tier 1.5** | **内容相似度 (方法体哈希交集率 >= 70% + 字符串 Jaccard >= 0.8)** | 高 (0.80) | 采纳并记录日志 | 差值相等无法区分时，降级至 Tier 3 |
| **Tier 2** | **Self Hash 同类跨宿主方法匹配 (同外层类)** | 中高 (0.75) | 仅当全类唯一样本时采纳 | 存在多个候选时禁止采纳，降级至 Tier 3 |
| **Tier 3** | **同宿主方法 + 同基类 + 同接口 + 同字段表 + 同声明方法表** | 中 (0.65) | 采纳并记录日志 | 差值相等无法区分时，降级至 Tier 4 |
| **Tier 4** | **同宿主方法 + 同基类 + 同接口** | 低 (0.35) | 仅当作用域内为全局唯一孤本时采纳 | **同作用域存在 >= 2 个同基类候选时，直接拒绝** |
| *(移除)* | *已坚决移除旧版 Tier 5（按名字盲配），前 4 层未匹配则判定为新增类/孤儿，杜绝已删除类内存篡夺* | - | - | - |

### 4.2 多轮基线与 sourceOrder 稳定排序
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

### 4.3 失败安全熔断机制 (Reject & Rollback)
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

---

## 6. 运行时架构、JBR-21 实测证据与性能

### 6.1 JBR-21 / DCEVM 运行时能力实测证据
经在真实 JBR-21 (21.0.9+1) + DCEVM (`-XX:+AllowEnhancedClassRedefinition`) 环境下物理实测验证：
1. **NestMembers 与 InnerClasses 修改**：**完全支持重定义**。向宿主类追加新的 `NestMembers` 和 `InnerClasses` 属性后，调用 `redefineClasses` 成功返回，新内部类可正常访问宿主私有成员。
2. **类继承体系与接口变更**：在开启增强模式下，底层支持添加接口与修改父类。但为确保全局稳定性，对齐器保持谨慎策略。
3. **未加载类的首次加载拦截**：`AnnotationTransformer.pendingAlignedClasses` 必须覆盖所有尚未被类加载器加载的目标类（无论是全新类还是被改名的历史类），确保首次读入的字节码为对齐后的产物。

### 6.2 事务原子性与并发互斥
* **事务生命周期**：
  `prepare -> tx.preRegister() -> inst.redefineClasses() -> tx.commit()`
* **并发控制**：Agent 内部重定义操作由全局独占重入锁控制，多线程并发热重载请求严格串行排队。

### 6.3 算法复杂度与性能防护
* **最坏复杂度**：单宿主类内对齐时间复杂度为 O(N^2)，其中 N 为匿名类数量。
* **性能防护机制**：
  1. **字节码摘要短路**：比对前计算 SHA-1 摘要，未变动的匿名类直接短路跳过 AST 解析；
  2. **数量硬上限**：单个宿主类下匿名类数量上限保护 N <= 128，超过则打印告警并降级；
  3. **计算超时中断**：对齐流程设定 2000ms 软超时，超时自动安全熔断。

### 6.4 系统特性开关
* `-Dnipx.anonAlign.enabled=true`：总开关（默认开启）。
* `-Dnipx.anonAlign.strict=false`：严格模式开关（开启后遇任何 Tier 4/5 歧义直接熔断）。
* `-Dnipx.anonAlign.debug=false`：诊断日志开关（开启后打印完整的层级决策链）。

---

## 7. 蜕变测试准则与风险清单

### 7.1 蜕变测试套件 (Metamorphic Testing Suite)
在回归测试套件中引入以下强预言蜕变测试：
1. **语义标记置换不变性 (Semantic Tag Permutation Invariance)**：
   为每个测试匿名类嵌入唯一的语义字符串（如 `"TAG_SAVE"`）。对新旧输入集合执行随机打乱，断言所有映射关系恒定，且目标语义标记 100% 精准对应。
2. **幂等性测试 (Idempotence)**：
   对同一份新编译产物连续执行两次对齐，断言第二次对齐的映射结果为恒等映射（无多余改名）。
3. **尾部追加不变性 (Append Invariance)**：
   在源码末尾追加全新的匿名类，断言之前所有已配对的一级与嵌套匿名类映射结果不受任何扰动。
4. **故障注入拒绝率 (Failure Injection Reject Rate)**：
   故意构造高危歧义场景（如两个完全同构且无结构差异的候选），断言系统 100% 触发 Reject & Rollback，无静默错配。

### 7.2 风险登记簿与未决问题 (Risk Register & Open Questions)
* **风险 1：超长生命周期实例的字段布局差异**  
  * *缓解*：若检测到匿名类的捕获字段数量或类型发生变更，在重定义时记录告警，提示实例内存迁移风险。
* **未决问题 1：ECJ 编译器的 EnclosingMethod 特殊表现**  
  * *跟踪*：当前已在 javac 8/11/17/21 上完成完备实测，后续需对 Eclipse ECJ 编译器生成的嵌套匿名类展开真实样本集差分测试。
