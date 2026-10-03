# shape 定稿环的优化（LookupKey + 缓冲复用 + 轻量短路）

## 改动

原先每轮（最多 64 轮）对**每个方法**都执行：

```java
List<String> shapes = new ArrayList<>(...);   // 每轮每方法分配
...
Collections.sort(shapes);
String ns = "(" + String.join("", shapes) + ")";   // 每轮每方法生成 String
```

改为：

```java
LookupKey shapeKey = new LookupKey(64);        // 循环外，一次
...
Collections.sort(shapes);                       // 排序无法跳过（见下）
shapeKey.reset();
shapeKey.append('(');
for (int i = 0; i < shapes.size(); i++) shapeKey.append(shapes.get(i));
shapeKey.append(')');
if (shapeKey.equals(info.shape)) continue;      // 轻量短路：相同则不生成 String
info.shape = shapeKey.copy();                   // 只有真正变化时才物化
changed = true;
```

### 为什么排序不能跳过

子形状必须**排序**后才能比较：否则 `(a,b)` 与 `(b,a)` 会被判为不同形状，
而定稿迭代的收敛判据正是"值有没有变"。所以能省的只有"串接与 String 生成"。

### 轻量短路的实际意义

定稿通常只需少数几轮，而**绝大多数轮次里绝大多数方法的形状是不变的**。
短路后这些轮次不再生成 String —— 这是每次 `align` 都能省下的一批分配。

---

## 测量结果：**无法分辨**

同一版本连跑多次，N=1600 的 `align` 最快耗时：

```
55.8 ms   66.3 ms   61.9 ms   68.9 ms   74.1 ms
```

**波动 ±30%**，而改动预期的收益远小于这个区间。因此：

- ❌ **不能声称这次优化带来了可测的性能改善**；
- ✅ 可以确认的是**没有行为变化**（`./gradlew check` 全绿，
  `UpDepthTest` 三个真实编译器 × 8 排列全过）；
- 代码层面的收益（不再每轮每方法 `new ArrayList` + `String.join`）是**结构性**的，
  但在这个规模与这个测试端上测不出来。

### 测量教训（本会话第 N 次同一模式）

我一度据一次 74.1 ms 的读数判定"优化后反而更慢"，**那是噪声**。
`AlignScaleBench` 的分辨率不足以支撑这个量级的结论 ——
要么提高测量精度（更多轮次、更长夹具、预热更充分），要么**不下结论**。

与前面几次同源：**装置的分辨率决定能主张什么**。
`②` 的 41× 之所以可信，是因为差异远大于噪声；本次差异落在噪声里，就只能记"不可测"。
