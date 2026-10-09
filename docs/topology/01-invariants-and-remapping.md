# 系统目标、核心不变量与映射契约

> **实现状态与审计**：本规范定义客观的架构与设计规格。当前实现状态、验收证据与详细审计参见 [docs/status.md](../status.md) 与 [AGENTS.md](../../AGENTS.md)。

---

## 1. 系统目标与范围

本系统用于解决增强类重定义下，由于源码改动（增删、换序、变量捕获变动）导致匿名内部类（`Foo$N`）及 Lambda 表达式在重新编译后序号漂移，进而引发的**存活实例方法表篡改（Method Hijacking）**与**未对齐类加载错位**问题。

* **受支持范围**：
  * Java 源码编译器（javac 8, 11, 17, 21 与 ECJ）编译的标准类、一级匿名类、多层嵌套匿名类（`Foo$1$1`）以及 Lambda 混合嵌套结构。
  * 闭包嵌套深度 depth <= 4 的复合结构（实现支持任意深度，depth > 4 仅触发诊断阈值提示）。
* **边界与非目标（Non-Goals）**：
  * **不跨宿主方法物理迁移实例**：若开发者将匿名类从 `methodA` 移动到 `methodB`，系统将其归入“旧类删除 + 新类新增”，不跨宿主方法篡夺老实例。
  * **不接管 Kotlin 编译器跨文件内联展开类**：Kotlin 内联函数的跨文件代码编织不在通用拓扑树中展开。

---

## 2. 核心不变量（Core Invariant）

> **类身份与实例状态保真不变量**：  
> **在 JVM 中已经存在存活实例的已加载类（Loaded Class），其物理类名只能被源码上与之对应的新版本实现重定义，其既有存活实例的方法调用与字段状态必须维持预期的语义连续性，禁止被无关的新生类占用物理槽位。**

### 架构保障机制
该不变量依靠以下三条机制共同保证：
1. **废除物理名盲配**：历史 Tier 5（按名字盲配）被彻底废除，前 4 层未匹配一律判为"新类/孤儿"而非"沿用旧物理槽位"。
2. **孤儿类保留（Orphan Retain）**：未匹配的旧类进入 `orphanOldClasses`，在 `HotSwapAgent.processChanges` 中被显式跳过重定义，存活实例继续执行旧逻辑以维持语义连续性。
3. **安全新名分配与初次加载拦截**：新类一律分配**未被占用**的编号，随后通过 `pendingAlignedClasses` 登记，在类加载器首次加载时拦截并提供对齐字节码。

*注：本不变量对匿名类全面成立；具名局部类因编译器原名直通机制，由专用的局部类止血门（`LocalClassGuard`，见 [07-layout-gate-and-risks.md](07-layout-gate-and-risks.md) §2）予以防御。*

---

## 3. 映射方向与改写范围

### 3.1 映射方向定义
```
renameMap : 新编译类名 (New Name) -> 目标类名 (Target Name)
```
* **命中已加载旧类**：若新编译类匹配到了已加载的旧类（例如新 `Foo$2` 匹配老 `Foo$1`），目标类名设为旧类名 `Foo$1`。随后通过 `ClassRemapper` 将新字节码改名为 `Foo$1`，使 `redefineClasses` 精准更新老类。
* **全新未加载类**：若新类为全新类（无对应历史类），分配一个未被占用的安全类名（例如 `Foo$3`），通过 `pendingAlignedClasses` 在初次加载时拦截生效。

### 3.2 改写范围契约
由 `AnonClassAligner.remapClass` 驱动 ASM `ClassRemapper` 完成改写：
1. **指令流与常量**：`NEW`, `CHECKCAST`, `INSTANCEOF`, `ANEWARRAY`, `MULTIANEWARRAY`, `LDC` 类常量。
2. **方法与字段调用**：`INVOKESPECIAL`, `INVOKEVIRTUAL`, `INVOKESTATIC`, `INVOKEINTERFACE`, `GETFIELD`, `PUTFIELD`。
3. **JVMS 类结构属性**：`InnerClasses`, `EnclosingMethod`, `Signature`, 运行时注解与异常表。
4. **嵌套特权属性 (JDK 11+)**：`NestHost`, `NestMembers`。

### 3.3 栈图处理与限制
* **栈图（`StackMapTable`）**：类型引用随 ASM `ClassRemapper` 联动转换。采用 `ClassWriter(0)`（不重算 maxs、不使用耗时的 `COMPUTE_FRAMES`），避免在类转换期触发类加载重入死锁。
* **恒等改写短路**：在映射表全为恒等映射时，`remapClass` 直接返回原始字节码，消除无谓的对象分配与解析。
* **已知边界**：字符串字面量（如反射 `Class.forName("Foo$2")`）不在字节码 Remapper 改写范围内。未显式声明 `serialVersionUID` 的类，其默认序列化哈希受类名影响。
