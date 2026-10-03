# 对旧代码评审的核对（四条）

逐条对照源码核对，不采信描述。

---

## ① 幽灵方法在热循环中的栈探测 —— ✅ 属实，且比描述更贵

```java
if (policy == OrphanPolicy.SMART_ADAPTIVE) {
    if (isCalledByUpdateRef()) { throw new NoSuchMethodError(...); }   // 每次调用都走这里
    if (LOGGED_ORPHANS.add(location)) { ... }
    return;
}
```

`isCalledByUpdateRef()` 在 `SMART_ADAPTIVE` 下**每次调用都执行**，且它有**两条**路径：

- `StackWalker.walk(... .limit(16) ...)`（JDK9+）；
- 兜底 `new Throwable().getStackTrace()`。

**评论只说了"即使只读 16 帧"，漏了兜底路径更贵**：`Throwable#getStackTrace()`
要构造异常对象并填充栈帧，成本高于 `StackWalker`。两条都要算进去。

### 关于建议的缓存：要与日志去重分开

`LOGGED_ORPHANS` 现在的语义是"**日志已打印过**"。若直接拿它当"已判定非 UpdateRef"的
短路缓存，会把**日志去重**与**探测短路**两件事绑在一起，语义混乱。

正确做法：**按 `location` 缓存判定结果**（`Map<String, Boolean>` 或 Set），
与 `LOGGED_ORPHANS` 各管各的。

注意：运行时**新**调用点（`location` 不同）第一次仍需探测 —— 每个调用点都得判一次，
这是必需的，不是缺陷。

---

## ② `infoByName` 线性查找 —— ✅ 属实，是 O(64·N²)

三个 64 轮循环里有**两个**在每轮、每个方法、每个子上调用 `infoByName`：

| 环 | 起始 | 调用 `infoByName` |
|---|---|---|
| 子树形状（定稿迭代） | 1372 | ✅ 每个子一次 |
| 上行深度（定稿迭代） | 1429 | ✅ 每个引用者一次 |
| 递归语义指纹 | 1470 | ✅ 每个子一次 |

`infoByName` 自身是"遍历所有 group 的所有元素"的双重循环
⇒ 单轮 O(N·children·N)，三环合计最坏 **O(64·N²)**。评论的估算正确。

### 建议成立且便宜

`childIndex` 已在 `!isOld` 时为**新类**建了索引；为两侧各维护一个完整的
`Map<String, SyntheticInfo> nameIndex` 即可把查找降到 O(1)。

**实现注意**：`nameIndex` 必须在 `scan` 把所有 `SyntheticInfo` 建完之后填充，
否则会查到 `null`（幽灵重注入也会改方法表）。

### 顺带发现（评论未提）

形状环里**每轮每个方法都 `new ArrayList<>(info.children.size())`** —— 64 轮 × N 次分配。
定稿后可以复用缓冲，或先判 `ready` 再建列表。

---

## ③ `UPDATE_REF_CLASS_PREFIX` 的前缀匹配 —— ✅ 属实，但**风险方向被说反了**

```java
f.getClassName().startsWith("nipx.ref.UpdateRef")   // 3 处
```

⚠️ **真正危险的不是"误判成代理类"本身，而是误判的后果**：

`isCalledByUpdateRef()` 返回 true ⇒ 抛 `NoSuchMethodError` ⇒ **触发精准局部熔断**，
把回调注销掉（`el.update(null)`）。

若 `nipx.ref` 下出现 `UpdateRefLogger` / `UpdateRefUtils` 这类非代理类且位于调用栈上，
回调会被**静默注销** —— 这比崩溃更难排查：程序照常运行，只是不再响应。

### 建议成立

```java
className.equals("nipx.ref.UpdateRef") || className.startsWith("nipx.ref.UpdateRef$")
```

---

## ④ 用注解标记幽灵 —— ⚠️ **做不到**它声称的"指纹复原"

评论说："注入一个 `@GhostLambda` 注解，下一轮识别并还原指纹"。**只对了一半。**

### 能达成的：更鲁棒的**识别**

现状 `isGhostMethod` 是**字节码模式匹配** —— 扫方法体里有没有
`LambdaAligner.onOrphanInvoked` 调用：

```java
if (mi.owner.equals(Type.getInternalName(LambdaAligner.class))
    && mi.name.equals("onOrphanInvoked")) return true;
```

空壳的调用形态一旦改动（改方法名 / 改所属类），识别会**静默失效**。注解更稳。

### 达不成的：指纹复原

`scan` 的指纹是**从方法体现算**的，不是从注解读的。
把原指纹写进注解，仍需 `scan` **显式读取注解并优先采用** —— 这是一个不小的机制改动，
不是"注入注解"顺手就能得到的。**评论把这一步略过了。**

### 另外两点代价（评论未提）

- `@GhostLambda` 是**新的 API 类型**，会进入对 Mod 可见的字节码面（本项目有 Mod 受众）；
- 现状检测是**纯字节码形状**，不新增类型、不依赖注解保留策略。

### 结论

方向合理（鲁棒性↑），但**收益限于识别**、成本是新增 API 类型 + 改 `scan`。
按既定标准（**没有失败用例就不改**），先记为已知脆弱点：
"空壳调用形态变化 ⇒ 幽灵识别静默失效"，等真出现或确有需要再做。

---

## 总结

| 条目 | 属实 | 判断 |
|---|---|---|
| ① 栈探测每次执行 | ✅ | 真隐患。按 `location` 缓存判定，**与日志去重分开**；兜底路径比评论说的更贵 |
| ② `infoByName` O(64·N²) | ✅ | 真隐患。**修法便宜低风险**，值得做；另有每轮 `new ArrayList` 分配 |
| ③ 前缀匹配 | ✅ | 属实，但**风险是静默熔断**而非误判本身；修法一行 |
| ④ 注解标记幽灵 | ⚠️ | 只能改善**识别**，**做不到**声称的指纹复原；需新增 API 类型；等失败用例 |

**附带发现（评论未提）**：`LOGGED_ORPHANS` 是 `ConcurrentHashMap` 支撑的**无界集合**
（只在 `clearLoggedWarnings()` 时清）。长跑进程 + 大量不同 `location` 时会持续增长；
键是字符串、量级通常很小，但值得知道。
