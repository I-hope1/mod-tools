# 层级交错协同流水线与指纹哈希

> **实现状态与审计**：本规范定义客观的架构与设计规格。当前实现状态、验收证据与详细审计参见 [docs/status.md](../status.md) 与 [AGENTS.md](../../AGENTS.md)。

---

## 1. 层级交错协同推进流水线

针对混合嵌套结构，系统采用**按层级交错推进架构**，打破线性流程在 depth >= 2 时的死锁：

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
(推进至叶子节点；若深度 > 4 则触发诊断告警)
   ▼
Phase 4: 全局原子重构与提交
```

* **流水线推进工程解耦**：
  * 第一趟由 `AnonClassAligner.alignCascading` 推进匿名类层级（父层先定名、子层 scope 随之收敛）；
  * 第二趟对每个类（输入为已重命名的匿名类字节码）单独运行 `LambdaAligner.align`。
  * `depth > 4` 设立为诊断预警阈值。探针实测表明层级循环本身与深度通用，真正的安全闸门在于数量上限与超时控制（见 [06-runtime-and-perf.md](06-runtime-and-perf.md) §3）。

---

## 2. 自身哈希 (Self Hash) 规范

在预提取阶段，`AnonClassHasher` 计算的自身哈希必须与外部依赖解耦：

### 2.1 包含特征
1. **基类与接口**：基类全限定名，以及排序后的接口全限定名列表。
2. **字段表（含合成捕获字段）**：字段名称与类型描述符（`name:desc` 排序后）。捕获字段（`this$0` / `val$x`）作为实例状态布局信号必须计入哈希，确保捕获变量变化时 Tier 1/2 及时失效，触发后续布局安全门判定。
3. **方法表**：非合成方法的名称、描述符与归一化指令序列。
4. **相对槽位占位符**：`#ANON_<relId>_<childHash>#`，在被引用匿名类可解析时折入其内容哈希以维持区分度。

### 2.2 排除与归一化特征
1. **剥离调试元数据**：通过 `ClassReader.SKIP_DEBUG | SKIP_FRAMES` 忽略 `LineNumberTable`, `LocalVariableTable`, `SourceFile`。
2. **屏蔽私有合成闭包**：所有以 `lambda$` 开头的方法名统一归一化为 `#SYNTHETIC_METHOD#`，其描述符归一化为 `#SYNTHETIC_DESC#`。
3. **访问器保留（`access$` 保名不改名策略）**：
   `access$000` 等方法不归一化为占位符。由于访问器涉及跨类调用，本系统采取保名策略；保留 `access$NNN` 可有效防止调用不同访问器的 Lambda 发生哈希碰撞。
4. **嵌套匿名类构造器描述符定向屏蔽**：
   嵌套匿名类构造器（`<init>`）参数中内嵌外层匿名类类名（如 `<init>(Ldeep/Deep$1;)V`）。若外层类位移为 `Deep$2`，子类构造器描述符将随之改变。系统通过 `MethodFingerprinter.maskDescriptor` 定向屏蔽外层匿名类序号（改写为 `#ANON#`），使嵌套匿名类在父类位移时仍能恢复 Tier 1 精确匹配。

### 2.3 宿主粗粒度签名隔离与同构区分
* **设计目标**：宿主指令流中的匿名类创建指令不嵌入匿名类的方法体内容哈希，使匿名类方法修改不破坏外层宿主 Lambda 指纹。
* **同构区分约束**：若完全采用粗粒度签名（仅基类/接口），对于同构的无参 `Runnable` 匿名类（如 `save` 与 `delete` 两个同构回调），粗粒度签名将完全相同，导致宿主 Lambda 误配并篡改方法表。因此系统当前维持将子匿名类内容哈希折入宿主引用的策略以保证区分度。

---

## 3. 未匹配子类基于映射后父名前缀派生

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

`takenTargetNames` 同时包含所有未匹配旧类的名字，确保派生编号绝不发生物理冲突。

---

## 4. 宿主方法追溯调用链防护

`findCallerMethod` 逆向追溯调用链时执行强校验：
1. **三要素联合比对**：强制校验 `hostNode.name.equals(owner)`、`calleeName.equals(name)` 以及 `calleeDesc.equals(desc)`。
2. **死循环与深度截断**：`visited` 集合记录 `methodName + ":" + methodDesc`，硬编码最大追溯深度 `depth <= 32`。超过深度未定位则返回 null 触发安全降级。

---

## 5. 非方法上下文的宿主归类

对于非普通方法的匿名类：
* 位于字段初始化器、实例初始化块、静态初始化块或构造器中的匿名类，通过 `resolveHostMethodForAnon` 反向扫描宿主直接父类字节码中的 `NEW` 实例化指令并沿调用链回溯。
* 嵌套匿名类的实例化扫描必须以其**直接父类节点**为上下文（而非最顶层宿主类），避免 javac 8 在嵌套 lambda 下将 `outerMethod` 抹平为 `"null"` 导致跨方法错配。

---

## 6. Lambda 与匿名类的边界不变量

由于 javac 将 Lambda 编译为所在类的合成方法（`Alt$1.lambda$work$0`），Lambda 不产生新的物理类与命名层级。匿名类包含树已由 `$` 前缀完全表达，无需合并异构树。

为保证系统架构稳定性，坚守以下两条核心不变量：

> **INV-1（自描述指纹）**：  
> 节点的 Primary Fingerprint **只描述自身**；子节点关系（"创建了哪些孩子、各是什么"）作为**独立维度**参与联合判定，**不得**把整棵 descendant 子树递归吸收进同一个哈希中。

> **INV-2（禁止互相递归）**：  
> `LambdaAligner` 与 `AnonClassAligner` **不得互相调用、互相递归求指纹**。统一入口是纯函数 `AnonClassHasher`；流程顺序必须保证"先建立拓扑证据，再由匹配器基于证据独立执行"。
