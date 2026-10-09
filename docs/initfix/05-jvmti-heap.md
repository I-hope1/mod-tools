# 05 JVMTI 多态堆实例检索

适用：修改 `LibTool.getInstances` 或相关 Native 代码时阅读。实现状态参见 `docs/status.md` 与 `AGENTS.md`。
实现以代码为准，本文只记录必须保持的约束，不复制源码。

## 实现位置

- `LibTool.getInstances`（C++，基于 `IterateOverInstancesOfClass`）

## 必须保持的约束

1. **多态覆盖**：`IterateOverInstancesOfClass` 天然包含目标类、所有派生子类和接口实现，不需要自己展开类层级。
2. **私有 Tag 序列**：用 `std::atomic<jlong>` 动态分配标签（起始值 100000，`fetch_add`），避免与其它 Agent 的标签踩踏。
3. **全局互斥**：整个"打标 → 取对象 → 清标"流程持有 `g_heap_mutex`。
4. **流程**：
   - 遍历目标类实例并打标
   - `GetObjectsWithTags` 取出专属标记的对象
   - 组装 `jobjectArray` 返回
   - 现场清理 Tag，释放局部引用
5. **错误路径必须回滚 Tag**：遍历失败、`GetObjectsWithTags` 失败、`NewObjectArray` 失败，都要把本次分配的 Tag 清掉，只清属于本次的 Tag（回调里比较 `*tag_ptr == 本次 tag`）。
6. **缓冲区释放**：`GetObjectsWithTags` 返回的 `instances` 缓冲区用 RAII（scope guard）调用 `Deallocate` 释放。
7. **局部引用**：循环里对每个 `instances[i]` 调 `DeleteLocalRef`，避免百万级对象撑爆局部引用表。
8. **错误返回**：用 `std::expected<jobjectArray, jvmtiError>`，不要吞掉 `jvmtiError`。

## 已知边界

- 堆遍历期间其他线程可能正在执行旧构造器，会抓到 final 字段尚未赋值的半构造对象。这是已知限制，需在风险报告中标明，调用端应避开高并发初始化峰值热更。
- 遍历耗时受全堆 STW 影响，毫秒到秒级。

## 待验证（P0.5）

- 百万级对象下的耗时与内存开销尚未压测。
