# 用 JMH + `-prof gc` 测 `align`

## 为什么换 JMH

手写计时循环在同一版本上波动 **±30%**（N=1600 实测 55.8 / 61.9 / 66.3 / 68.9 / 74.1 ms），
分辨不出 shape 拼接那类改动。JMH 提供预热、多轮次、fork 与统计输出。

**更关键的是 `-prof gc`**：它给出**每次 `align` 的分配量**（`gc.alloc.rate.norm`，B/op）。
对"少分配一批临时对象"这类改动，**分配量比墙钟时间灵敏得多** ——
这正是 shape 定稿环优化的正确验收指标。

## 已完成的搭建

新增 [`jmh/modtools/AlignBenchmark.java`](../../jmh/modtools/AlignBenchmark.java)：

- 复用 `AlignScaleBench` 的夹具生成（N 个 indy 链式引用的 lambda，
  `(Lowner;)V` 形态以保证 `forceStaticLambdas` 的幂等性）；
- `@Param({"100","400","1600"})`、`@Fork(2)`、`@Warmup(5)`、`@Measurement(5)`；
- 两种模式：`AverageTime` 与 `SampleTime`。

### 接线时踩到的坑（都已解决）

1. **`-PjmhIncludes` 不生效**：jmh 插件的 `includes` 在配置阶段求值，
   传入的项目属性没被用上，跑的还是默认基准。**直接写死 `includes` 才生效。**
2. **`UnsupportedClassVersionError`**：jmh 骨架类被宿主 JDK 25 编成 major 69，
   而 `jmh { jvm = .../openjdk-21 }` 用 JDK 21 运行。
   **改 jmh 用 JDK 25 运行**（与依赖一致）后正常；
   注意**不能**给 jmh 设 `options.release = 21` —— 那会让 Gradle 拒绝消费
   用 25 编的 `:annotations` 等项目（与 hstest 那次同一个坑）。

## 实测数据

`./gradlew jmh`（`includes = ["AlignBenchmark"]`、`profilers = ["gc"]`）已跑通，输出形如：

| N | `align` 平均耗时 | `gc.alloc.rate.norm` |
|---|---|---|
| 100 | 5.796 ms/op `(min,avg,max)=(4.819,5.796,7.605)` | ≈ **2,411,730 B/op**（2.4 MB） |
| 400 | — | ≈ **6,801,438 B/op**（6.8 MB） |
| 1600 | — | — |

**确认可用的结论**：

- JMH 基准**能正常测量**，`-prof gc` 的分配量数据可用；
- 每次 `align` 的分配量是**MB 级**，随 N 增长 —— 说明"定稿环里的临时对象"
  确实是值得优化的对象，而不是理论担忧。

**尚未取全**：N=400 的时间与 N=1600 的两项数据。解析脚本被编码问题挡住，
而输出文件有 1 万多行（含大量构建日志），需要更稳的提取方式。

## 下一步（若要完整对照）

`align` 的分配量现在是可测的基线。若要验证 shape 定稿环那次优化：

1. 用 JMH 量**当前版本**的 `gc.alloc.rate.norm`（本次已部分取得）；
2. `git checkout <优化前提交> -- hotswap-agent/src/nipx/LambdaAligner.java`，重建，再量一次；
3. 两者相减即该优化省下的分配量 —— 这比墙钟时间更能说明问题。

（与 ② 的 A/B 同一手法：**必须与改前代码对照**，不能只报当前绝对值。）
