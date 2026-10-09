# 匿名类元数据判定与保留名体系

> **实现状态与审计**：本规范定义客观的架构与设计规格。当前实现状态、验收证据与详细审计参见 [docs/status.md](../status.md) 与 [AGENTS.md](../../AGENTS.md)。

---

## 1. 基于类属性的权威判定（Attribute-First Admission）

判定一个类是否为不稳定匿名类，以 ClassNode 的字节码属性为第一准则，类名模式为辅助：

| 分类 | 字节码判定准则 | 处理动作 | 级联树定位 |
|:---|:---|:---|:---|
| **标准匿名类** | `InnerClasses` 属性中 `innerName == null`，且包含 `EnclosingMethod` | **准入** | 提取其宿主类与父级前缀，加入拓扑树 |
| **多层嵌套匿名类** | `innerName == null`，其 `EnclosingMethod` 指向另一个匿名类 | **准入** | 逻辑父为该外层匿名类 |
| **具名内部类** | `innerName != null` 且不为纯数字 | **排除** | 不对齐，父前缀路径完全冻结 |
| **具名局部类** | `innerName != null` 且 `outer_class == null` | **排除（不对齐）**，另有止血门 | 原名直通；同名局部类漂移由 `LocalClassGuard` 止血（见 [07-layout-gate-and-risks.md](07-layout-gate-and-risks.md) §2） |
| **javac 枚举 Switch 映射表** | `ACC_SYNTHETIC` + 仅含 `$SwitchMap$` 字段 | **排除（保留）** | 识别为编译器缓存，保持原名直通（见下文设计分析） |
| **Kotlin When 映射类** | 类名含 `$WhenMappings` | **排除** | 保持原名直通 |
| **枚举常量体** | 带有 `ACC_ENUM` 且基类为直接封闭枚举 | **排除** | 声明顺序与常量严格绑定，不可重命名 |

### 设计分析与实现决策
落地位置位于 `AnonClassAligner.isAnonymousClass` 与 `isAnonymousClassName`。

1. **多重闸门顺序**：
   - 先检查 `ACC_ENUM` → 排除（覆盖"枚举常量体"）；
   - 再检查自身 `InnerClasses` 条目的 `innerName != null` → 排除（覆盖"具名内部类/具名局部类"）；
   - 最后使用类名模式 `宿主$<纯数字>($<纯数字>)*` 作为准入闸门。
2. **`EnclosingMethod` 容错**：
   - 未强制要求存在 `EnclosingMethod`：当 `cn.outerMethod == null` 时，通过 `resolveHostMethodForAnon` 反向追溯补救。原因在于 javac 8 的嵌套 lambda 会将 `EnclosingMethod` 记录为虚拟的 `lambda$null$0`，若强校验 `EnclosingMethod` 会漏掉真实匿名类。
3. **关于 SwitchMap 类的安全性分析**：
   - javac 生成的枚举 Switch 映射表类（`Foo$N`）满足纯数字命名模式，会被纳入对齐分析。
   - 在真实实测中，由于 SwitchMap 类没有 `NEW` 实例化点且 `outerMethod` 保持为 `null`，与普通匿名类 scope 不一致，因此在 Tier 4（要求 `outerMethod` 相等）下不会发生误配。字面上的"排除 + 原名直通"反而会打破槽位占用保护，因此当前维持纳入对齐流程。

---

## 2. 全局保留名集合（Reserved Names Domain）

在为未匹配类分配新编号时，`takenTargetNames` 集合必须严密涵盖以下范围，杜绝任何物理碰撞：

1. **已分配目标名**：当前对齐批次中已被采纳的所有目标类名；
2. **已加载及孤儿旧类名**：JVM 运行时当前仍加载在内存中的所有历史类名（含已删除但未被 GC 卸载的孤儿类）；
3. **保留与排除类名**：枚举 Switch 映射表、局部类等未参与对齐的固有类名；
4. **加载期拦截挂起名**：当前已登记在 `pendingAlignedClasses` 中的未加载类名。

### 保留名集合维护规则
* **旧类种子初始化**：`takenTargetNames` 初始播种包含调用方传入的全部旧匿名类表（`oldAnonClasses`），以及 `loadedClassesMap` 中已加载的所有历史匿名类。
* **挂起名快照接入**：`AnonClassAligner.align(...)` 接收 `pendingAlignedClasses` 的快照（`Set.copyOf(...)`）。挂起名只播种 `takenTargetNames` 用于编号避让，**绝不进入匹配候选**（挂起类尚未加载，无旧字节码，不具备 Tier 匹配资格）。
* **编号单调性与无害膨胀**：永久未被加载的挂起类会保留占用其编号，使后续新类编号逐轮递增。此现象不影响语义正确性，且单次对齐数量上限（`MAX_ANON_PER_HOST`）统计的是类总数而非最大编号。
