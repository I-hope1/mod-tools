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
