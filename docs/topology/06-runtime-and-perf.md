# 运行时架构与性能约束

> **实现状态与审计**：本规范定义客观的架构与设计规格。当前实现状态、验收证据与详细审计参见 [docs/status.md](../status.md) 与 [AGENTS.md](../../AGENTS.md)。

---

## 1. JBR-21 / DCEVM 运行时能力与约束

在开启增强类重定义能力的环境下（JBR-21+ 或 DCEVM `-XX:+AllowEnhancedClassRedefinition`）：

1. **NestMembers 与 InnerClasses 修改**：
   支持向宿主类追加新的 `NestMembers` 和 `InnerClasses` 属性，调用 `redefineClasses` 成功返回，新内部类可正常访问宿主私有成员。
2. **继承体系守卫（拒绝变更）**：
   当检测到类继承关系变更（父类或接口修改，`diff.hierarchyChanged`）时，系统选择安全熔断并拒绝重定义，提示重启应用，确保全局类型系统的稳定性。
3. **初次加载拦截通道**：
   通过 `AnnotationTransformer.pendingAlignedClasses` 覆盖所有尚未加载的目标类（无论是全新类还是被改名的历史类），确保类加载器首次读入的字节码即为对齐后的正确产物。

---

## 2. 事务生命周期与并发控制

### 2.1 事务阶段规范
```
prepare -> tx.preRegister() -> inst.redefineClasses() -> tx.commit()
```
* **乐观预登记（Pre-Registration）**：在执行 `redefineClasses` 之前对所有事务执行 `preRegister()`，消除"宿主重定义成功到事务提交之间并发线程首次加载未加载类时读到磁盘错位产物"的竞态。
* **降级与一致性校验**：当批量重定义失败时，自动切换至单类降级重定义，并按事务组校验一致性（宿主与下属全部匿名类均成功才 `commit`，否则 `rollback(true)`）。

### 2.2 并发与执行调度
Agent 依赖单线程文件监听与防抖窗口调度热重载请求，核心集合对关键路径进行局部同步保护。

---

## 3. 算法复杂度与安全闸门

### 3.1 复杂度特征
单宿主类内对齐时间复杂度为 $O(N^2)$（其中 $N$ 为匿名类数量）。

### 3.2 性能安全闸门
1. **数量硬上限（`MAX_ANON_PER_HOST = 128`）**：
   单个宿主类下匿名类数量超过 128 时，抛出 `AlignmentRejectedException` 整体拒绝该宿主组。坚决不采用"降级为不重命名"策略，因为不对齐会直接打开编号位移篡夺的内存安全漏洞。
2. **软超时中断（`ALIGN_TIMEOUT_MS = 2000`）**：
   对齐流程设定 2000ms 软超时，采用 elapsed 方式比较（`now - start`）。检查点设立在 `parseInfos` 之后、每个层级循环入口以及 `matchTier` 的外层循环处。超时立即触发宿主组原子拒绝。

---

## 4. 系统特性开关配置

系统支持通过 JVM 系统属性控制对齐行为，首选属性名遵循 `nipx.agent.*` 规范，同时兼容 `nipx.anonAlign.*` 历史别名：

| 首选属性名               | 兼容别名                 | 默认值  | 语义与行为                                                        |
|:-------------------------|:-------------------------|:--------|:------------------------------------------------------------------|
| `nipx.agent.anon_align`  | `nipx.anonAlign.enabled` | `true`  | 对齐总开关。为 `false` 时含匿名类的宿主整体拒绝，杜绝编号位移篡夺 |
| `nipx.agent.anon_strict` | `nipx.anonAlign.strict`  | `false` | 严格模式开关。开启后遇 Tier 4 歧义或 `depth > 4` 直接拒绝宿主组   |
| `nipx.agent.anon_debug`  | `nipx.anonAlign.debug`   | `false` | 诊断日志开关。打印完整的层级决策链与详细匹配计数                  |
