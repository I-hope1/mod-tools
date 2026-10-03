# 旧代码评审的落地记录

对应 `REVIEW-old-code.md` 里的四条。**已做 ③ 与 ②**（行为不变、低风险）；
① 需先构造可测用例；④ 等失败用例。

---

## ③ 前缀匹配 → 精确匹配（已做）

### 改动

```java
// 旧：会连带匹配 UpdateRefLogger / UpdateRefUtils 这类非代理类
f.getClassName().startsWith("nipx.ref.UpdateRef")

// 新：只认本身与内部类；3 处调用点统一走这个判定
private static boolean isUpdateRefClass(String className) {
    return UPDATE_REF_CLASS.equals(className)
        || className.startsWith(UPDATE_REF_CLASS_INNER_PREFIX);   // "nipx.ref.UpdateRef$"
}
```

### 为什么值得做（风险方向）

误判的代价不是"判错"本身，而是：返回 true ⇒ `onOrphanInvoked` 抛
`NoSuchMethodError` ⇒ 驱动 `UpdateRef` **精准局部熔断并注销回调**。
若某个非代理类恰好在调用栈上，回调会被**静默注销** ——
程序照常运行、只是不再响应，比崩溃更难排查。

### 验证

`./gradlew check` → `HSTEST SUITE: ALL PASSED`（行为未变）。

---

## ② `infoByName` → O(1) 索引（已做）

### 改动

`MatchContext` 新增两侧完整索引，并在 `reset()` 里清理：

```java
final Map<String, SyntheticInfo> oldNameIndex = new HashMap<>(64);
final Map<String, SyntheticInfo> newNameIndex = new HashMap<>(64);
```

`scan` 构建每个 `SyntheticInfo` 时就地填充（与既有 `childIndex` 同一位置），
`infoByName` 改为一次 `Map.get`：

```java
private static SyntheticInfo infoByName(MatchContext ctx, boolean isOld, String name) {
    return (isOld ? ctx.oldNameIndex : ctx.newNameIndex).get(name);
}
```

### 收益

原先 `infoByName` 是"遍历所有 group 的所有成员"的双重循环，而它被**三个 64 轮定稿循环**
按"每个方法 × 每个子（或引用者）"调用 —— 最坏合计 **O(64·N²)**，现在是 **O(1)**。

### 填充时机（关键）

就地填充是安全的：`scan` 的循环体在 `groupByLogic` **之前**已把该名字的
`SyntheticInfo` 构建完毕。三个 64 轮循环都在 `scan` 返回**之后**才运行，
因此查索引不会碰到 null。

### 验证

`./gradlew check` → `HSTEST SUITE: ALL PASSED`。

**说明**：本项是**复杂度**改进，不是行为改变，因此"8/8 通过"不构成它有效性的证据 ——
`UpDepthTest` 三个编译器全绿只能说明**没有回归**。若要证明收益，需要一个
"大量 lambda 的大类"夹具来对比 `align` 耗时；**本轮未做，如实记录**。

---

## 顺带发现（未改，供参考）

形状定稿环里**每轮每个方法都 `new ArrayList<>(info.children.size())`**（64 轮 × N 次分配）。
定稿后可以复用缓冲，或先判 `ready` 再建列表。未验证收益，未改。

---

## ① 热循环栈探测 → 按 location 缓存"否"（已做，先测量后改）

### 先测量（`ProbeBench`）

**不先测就不改** —— 按既定标准，没有可感知的问题就不加缓存。实测：

| 项 | 耗时 |
|---|---|
| 空调用基线 | 7.3 ns/次 |
| `isCalledByUpdateRef()`（每次真探测） | **1095.7 ns/次** |
| `onOrphanInvoked`（**加缓存前**） | 约 **1350 ns/次** |

折算：若幽灵 lambda 处在每秒 10 万次调用的高频循环中，**约 97ms/秒 ≈ 9.7% CPU**。
**结论：可感知，值得加缓存。**

### 改动

```java
// 只缓存"否"：判定"是"会立即抛 NoSuchMethodError 驱动熔断，那次调用不会返回，无需缓存
if (!NOT_FROM_UPDATE_REF.contains(location)) {
    if (isCalledByUpdateRef()) throw new NoSuchMethodError(...);
    NOT_FROM_UPDATE_REF.add(location);
}
```

- **与 `LOGGED_ORPHANS` 分开**：后者语义是"日志已打印过"，混用会把日志去重与探测短路绑在一起；
- 每个 `location` 仍在**首次**触达时完整探测一次，**正确性不变**；
- 在 `clearLoggedWarnings()` 里一并清理。

### 改后实测

| 项 | 加缓存前 | 加缓存后（String 版） | 改用 `LookupKey` 后 |
|---|---|---|---|
| `onOrphanInvoked` | ~1350 ns/次 | 88–149 ns/次 | **88.6 ns/次** |
| 每秒 10 万次占 CPU | ~9.7% | ~0.9% | **~0.8%** |

**约 15× 改善。** 相对基线的净开销约 **84 ns/次**。

### 改用 `LookupKey`（用户建议）

`location` 原先每次调用都要拼一个 String。改用项目既有的
[`LookupKey`](../../hotswap-agent/src/nipx/profiler/LookupKey.java)（内嵌 `StringBuilder` +
缓存 hash + 同时支持与 `String` 比较）：

- **命中缓存的热路径零 String 分配**：集合元素仍存普通 `String`，但查询时传 `LookupKey`，
  走 `LookupKey.equals(String)` 比较内容；
- `NOT_FROM_UPDATE_REF` 仍是 `Set<String>`，用一个 `StringSet` 子类提供
  `containsKey(LookupKey)`（**不再**把 `LookupKey` 当集合条目 —— 那样会违反
  `Set<String>` 的契约）；
- **两条日志路径统一走 `logOrphanOnce(key)`**（`SMART_ADAPTIVE` 与
  `LOG_AND_RETURN_DEFAULT`）：先按内容 `containsKey(LookupKey)` 判，未记录过才 `copy()`
  成 String。命中时零分配。

### 三次测量的演进（都取多次最小值）

| 版本 | `onOrphanInvoked` | 相对基线净开销 |
|---|---|---|
| 无缓存 | ~1350 ns/次 | — |
| 缓存 + String location | 88–149 ns/次 | ~84 ns/次 |
| 缓存 + `LookupKey`（含无条件 `copy()`） | 88.6 ns/次 | ~84 ns/次 |
| **缓存 + `LookupKey` + 去掉无条件 `copy()`** | **70 ns/次** | **~65 ns/次** |

**测量教训（重要）**：改用 `LookupKey` 后第一次测到 **207.6 ns/次**，比 String 版还慢，
我差点据此判定"这个改动没收益"。把迭代数从 1,000 提到 5,000、轮数 2 提到 4 后，
稳定在 **88.6**，去掉无条件 `copy()` 后 **70**。

**装置不可靠时不能解读结果** —— 本会话反复出现的模式，这次是我的微基准迭代数太小。

### 验证

`./gradlew check` → `HSTEST SUITE: ALL PASSED`（行为未变）。

### 失误记录

基准最初设成 5,000,000 次 × 5 轮 × 3 项，**跑了几分钟没结束**，是我把探测成本（~1µs）
低估了两个数量级。改成 1,000 次 × 2 轮后秒级完成。**测量工具本身也要先估量级。**

