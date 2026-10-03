# IntelliJ 语义索引接入后的实际收益

结论：**有用，而且第一次查询就查到了 grep 查不准的东西。**

## 立刻得到的收益：确认了生产调用链

用 `ide_find_references` 查 `LambdaAligner#align(byte[], byte[])`，返回 **9 处**，
其中**唯一的生产调用点**是：

```
hotswap-agent/src/nipx/HotSwapAgent.java:288
    newBytecode = LambdaAligner.align(oldBytecode, newBytecode);
    astPath: ["HotSwapAgent", "processChanges"]
```

其余 7 处是 Javadoc `@link`，1 处是新增的 JMH 基准。

### 读上下文后确认的关键事实

```java
// HotSwapAgent.processChanges
if (oldBytecode == null) oldBytecode = fetchOriginalBytecode(targetClass);   // 失败时回落
...
if (LAMBDA_ALIGN) {
    if (HOTSWAP_PLUS) {
        newBytecode = AnnotationTransformer.forceStaticLambdas(newBytecode, ...);  // 归一化
    }
    newBytecode = LambdaAligner.align(oldBytecode, newBytecode);
}
bytecodeCache.put(className, newBytecode);   // ← 新基线在 align **之后**才写回
```

两点因此**从"读代码推断"变成"已核实"**：

1. **`oldBytecode` 确实是上一轮 `align` 的输出**（基线一致性成立）。
   `bytecodeCache` 存的是 transform 后实际生效的形态，`align` 的结果在其**之后**写回；
   因此下一轮读到的 `oldBytes` 与 align 文档要求的"上一轮对齐并注入幽灵之后、"相符。
   —— 这正是我先前列为"待确认"的那条假设。
2. **`forceStaticLambdas` 在 `align` 之前**（且被 `HOTSWAP_PLUS` 门控），
   与测试装置 `SemAssert.force()` / `aligned()` 的做法一致。

## 为什么这是 grep 做不到的

`grep -rn "align("` 会命中大量同名方法与注释；而语义查询给出
**`astPath`（调用者链）+ `type: METHOD_CALL`（区分注释与真实调用）+ 精确到参数的
`resolvedSymbol`**，所以能一眼分离"7 处 Javadoc 引用 / 1 处生产调用 / 1 处基准"。

## 其他可用能力（尚未使用）

- `ide_call_hierarchy`：追 `align` → `step2` / `scan` 的调用树，适合做 `scan` 拆分前的依赖梳理；
- `ide_diagnostics`：**不需要跑 Gradle 就能拿编译错误与 IDE 检查**——对"改完立刻知道有没有写错"
  很有价值，尤其在本会话多次出现"编辑没生效/写错"的情况下；
- `ide_find_references` + `ide_refactor_safe_delete`：确认死代码（本会话的 `childHashes`、
  `liveKeys` 就是这类，但当时只能用 grep 逐个核实）；
- `ide_structural_search_replace`：AST 级批量改写，比字符串替换可靠——
  本会话我的 `python` 字符串替换**静默失败过多次**，这类工具正好补上那个缺口。

## 建议的用法

1. **改完源码立刻 `ide_diagnostics` 取该文件的错误**，作为"编辑是否真的生效且合法"的第一道验证
   （比 Gradle 快得多）；
2. `scan` 拆分前用 `ide_call_hierarchy` 摸清调用关系；
3. 删死代码前用 `ide_find_references` 而非 grep 确认零引用。
