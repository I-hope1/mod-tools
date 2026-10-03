# 外部分析核对结果（第二轮）

对第二轮外部评审逐条核对。**7 条全部属实**（已核对行号），其中 3 条已修。

---

## 已修

### 第 1 条：`NOT_FROM_UPDATE_REF` 缓存破坏 SMART_ADAPTIVE 语义 ✅ 已修

**属实，且是最严重的一条。** 缓存键是 location（方法），但"是否由 UpdateRef 发起"是
**调用上下文**的属性。同一个旧 CallSite 完全可能先被业务代码直接调一次（初始化期），
之后才注册进 UpdateRef 轮询 —— 首次判定"否"后，后续 UpdateRef 发起的调用被短路成
静默返回，**熔断永不触发**，恰好复现 60FPS 空转。

我原先注释里"每个 location 仍在首次触达时完整探测一次，因此正确性不变"**不成立**。
已**完全移除该缓存**，每次真实探测。

⚠️ **代价**：放弃实测 **11×** 的性能收益（1350→121 ns/次）。那是用错误语义换来的，
不应保留。日志路径的分配优化与它正交，仍然有效。

### 第 3 条：跨组匹配未检查 `oi.renameable` ✅ 已修

**属实**：`acceptCandidate`(696) 与 `firstFingerprintMatch` 的两个候选循环(945-957)
都只查 `ni.renameable`、不查 `oi.renameable`。已补上 —— 这正是先前
Kotlin `NoSuchMethodError` 的同类场景。

### 第 2 条：热路径比注释声称的重 ✅ 已修（按评审建议重写）

**属实**：`StringSet` 是线性扫描 + 双重同步锁；`isMarkedNotFromUpdateRef` 命中后仍继续
落到 `logOrphanOnce`（又一次线性扫描加锁）；`className.replace()` 每次分配 String，
与"零 String 分配"矛盾。

**已按评审建议重写**：桩代码在**注入期**算好 location，运行时只传**一个字符串常量**：

```java
before: onOrphanInvoked(String className, String name, String desc)   // 每次拼 String
after:  onOrphanInvoked(String location)     // 例如 com.example.Foo#lambda$build$0()V
```

- 运行时**完全不分配**（常量 hash 已由 JVM 缓存）；
- 集合改为 `ConcurrentHashMap` 支撑，**O(1) 且无需任何锁**；
- `LookupKey` / `StringSet` / `ThreadLocal` 缓冲**全部删除**（并发问题随之消失）。

---

## 已核实、待修

### 第 4 条：状态记录不一致 ✅ 属实

- **`step2` 配对不设 `ni.matchedWith`**（只有 `pair()` 与跨组匹配设），
  因此 `verifyShapeInvariant` 看不到 Step 2 的配对。目前靠 `sameNestingLevel`
  已保证 shape 相等，暂无害，但是潜在的坑。
- **`verifyShapeInvariant` 回滚不完整**：只清 `renameMap` / `matchedWith` /
  `matched` / `usedOldNames`，未回滚 `simpleNameWitness` / `renameBySimpleName`。
  结果偏保守（会被判歧义），不出错，但状态不一致。
- **根因仍标注"尚未定位"** —— 建议保留为待办。
  评审指出：**后来修的两处（幽灵不参与语义指纹、`shape` 用 `null` 哨兵）很可能就是根因**。
  我认同这个判断。

### 第 5 条：shape 硬否决的代价未写明 ✅ 属实

`sameNestingLevel` 在 Step 1/2 全程是硬否决 ⇒ **任何改变嵌套拓扑的编辑**
（在现有 lambda 里增删内层 lambda）都会让它丢失身份：旧名被幽灵化、新方法拿新名。
这是"安全优先于召回"的合理取舍，但这类编辑很常见，**应写进已知限制**。

### 第 6 条：死代码与注释腐化 ✅ 属实

| 项 | 核实 |
|---|---|
| `childHashes` 死代码 | 只被计算与存储，**不参与任何判断**（`sameNestingLevel` 已改用 shape） |
| `liveKeys` 恒假分支 | `presentKeys` 取自 `alignedCn`，与 `newBytes` 同源；`orphanedKeys` 本就是其补集 ⇒ `liveKeys.contains(key)` **永假**，**还多解析一遍字节码**，与"避免二次读取"目标相悖 |
| Javadoc 叠加/错位 | `hasUnmatchedChild` 的文档贴在 `calleesPairTo` 上；`step2` 的文档被 `step2PassA` 顶替；`SyntheticInfo.shape` 有两段 |
| `Java 7/8 降级`注释已不成立 | 类里用了 `instanceof` 模式匹配与 `Stream.toList()`（Java 16+） |

### 第 7 条：结构问题 ✅ 属实

`scan` 约 170 行同时做扫描/shape/upDepth/语义指纹四件事；
类里约一半是"早先怎样、后来怎样"的演进史且引用了不在版本库里的 `scratch/hstest/...` 路径；
`MatchContext.reset()` 只 clear 不缩容；`catch` 只打 `e.toString()`、无堆栈。

---

## 关于 `LookupKey` 的最后一处使用（用户指出）✅ 已改

shape 定稿环里用 `LookupKey` 但那四个操作 `StringBuilder` 全有等价物：
`reset()`→`setLength(0)`、`equals(CharSequence)`→（`String` 侧的）`contentEquals`、
`copy()`→`toString()`。

**`LookupKey` 唯一多出的是缓存 hash，而这里每次都要先 append 再比较 ⇒ hash 必然失效、
缓存用不上。** 已换成 `StringBuilder` + `info.shape.contentEquals(shapeBuf)`，
`nipx.profiler.LookupKey` 的 import 也随之删除。

（`LookupKey` 适合"稳定 key 反复查哈希表"，运行时入口曾经是那种场景；
那里改用常量 String 后也不需要它了。）

---

## 其他提醒（接受）

- "绝不引发崩溃"过强：幽灵返回的 `null`/`0` 在下游仍可能触发 NPE 或逻辑错误。
  应改为"**不在幽灵本身抛异常**"。
- 幽灵桩调用 `nipx.LambdaAligner.onOrphanInvoked`，要求目标类的类加载器能看到该类。
  父委派正常时成立，**应写进文档**。
