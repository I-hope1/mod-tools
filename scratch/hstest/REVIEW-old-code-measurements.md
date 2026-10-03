# ② 与日志路径的实测结果

对应 `REVIEW-old-code-fixes.md`。本节补上此前**"改了但没测"**的两处证据。

---

## ② `infoByName` O(1)：A/B 对照（`AlignScaleBench`）

### 夹具

用 ASM 直接生成"大量 lambda"的类：N 个 `lambda$build$i`，每个体内用 indy 引用下一个，
形成长链（使 `children` 非空，三个定稿环才有活干）；`build()` 引用链首。
N = 100 / 400 / 1600，各测多次取最小值。

### 方法

**必须与改前代码对照**，否则只有"当前实现的绝对耗时"，得不出改动收益。
做法：`git checkout b211d2a4 -- hotswap-agent/src/nipx/LambdaAligner.java`
（`b211d2a4` 是加入 nameIndex **之前**的提交），重建 jar 测一遍，再
`git checkout HEAD --` 恢复。**不是**用字符串替换去伪造 before —— 那条路本轮已失败多次。

### 结果

| N | 改前（双重线性查找） | 改后（O(1) 索引） | 倍数 |
|---|---|---|---|
| 100 | 20.9 ms | 4.4 ms | 4.7× |
| 400 | 122.3 ms | 15.9 ms | 7.7× |
| 1600 | **2320.4 ms** | **56.2 ms** | **≈41×** |

**改前的增长形态本身就是 O(N²) 的证据**：N×4 ⇒ 耗时 **×19**（122ms → 2320ms）；
改后 N×4 ⇒ 耗时 ×3.5（近线性）。N=1600 时 **2320ms → 56ms**。

这解释了评论的估算为何重要：`align` 会随类里 lambda 数量急剧劣化，
而它在**每次热更**都要跑一遍。

---

## 日志路径的零分配实测（`ProbeBench`）

迭代 5,000 次 × 4 轮取最小值（早期用 1,000×2 测出过与实际相反的结论）。

| 路径 | 净开销（相对空调用基线） | 说明 |
|---|---|---|
| 空调用基线 | 5.6 ns/次 | — |
| `isCalledByUpdateRef()` 真探测 | **1527.6 ns/次** | 缓存的价值所在 |
| `SMART_ADAPTIVE`（已缓存） | **120.8 ns/次** | 加缓存前 ~1350 ns/次 ⇒ **约 11×** |
| `LOG_AND_RETURN_DEFAULT` | **223.8 ns/次** | 已零分配 |

### 两点说明

1. **两条路径都已零分配**：命中后都不再生成 String。
   `LOG_AND_RETURN_DEFAULT` 数值更高，是因为它比 `SMART_ADAPTIVE` 多付一次集合查询
   （`containsKey(LookupKey)` + 之后的 `LOGGED_ORPHANS` 检查），不是分配造成的。
2. **数值有运行间波动**（同一版本不同次可差 30–50%）。本表取的是多次最小值；
   **结论只看量级**（约 10² ns/次），不把单次数字当精确值。

### 折算

每秒 10 万次调用时，`SMART_ADAPTIVE` 路径约占 CPU **1.2%**。
加缓存前同口径约 **13.5%**（1527ns 探测 × 10 万次）—— 这就是缓存的实际意义。

---

## 并发安全（用户指出）

`onOrphanInvoked` 是幽灵空壳的入口，会被**任意业务线程**并发调用。
原先有两处隐患：

1. **`LOCATION_KEY` 是静态共享缓冲** —— 两个线程会互相覆盖 `StringBuilder` 内容，
   导致查错集合、打错日志，甚至写坏 location。
   改为 `ThreadLocal.withInitial(() -> new LookupKey(128))`。
   （`GlTimerProfiler` 里同样是静态 `LookupKey`，但那里有"只在 GL 线程访问"的明确前提，
   此处没有，不能照搬。）
2. **两个集合是普通 `HashSet`** —— 且两处用法都是 check-then-act
   （先 `containsKey` 再 `add`），并发下同一 location 可能重复打日志、重复探测。

### 修法

`StringSet` 内部用 `Collections.synchronizedSet` 包住，并把**整个判定与写入放在同一临界区**：

```java
synchronized boolean containsKey(LookupKey k)      // 遍历比较，整体加锁
synchronized boolean addIfAbsentKey(LookupKey k)   // 原子的 check-then-add
```

未改用 `ConcurrentHashMap.newKeySet()`：它保证元素是 `String` 类型，
与 `LookupKey.equals(Object)` 的双向比较语义冲突。
读远多于写（命中即返回），因此 synchronized 的粗粒度不构成实际瓶颈。

### 验证（`ConcurrencyCheck`）

16 线程 × 200 次 = **3,200 次并发调用**（`LOG_AND_RETURN_DEFAULT`），
每个线程使用自己唯一的 location：

```
日志行数     = 16      （每个 location 恰好一条）
重复 location = 无
唯一 location = 16
```

若 ThreadLocal 未生效，各线程的 key 会互相污染，出现**错位的 location 名**或
行数偏离 16；若集合未原子化，同一 location 会出现**重复日志**。两种问题均未出现。

