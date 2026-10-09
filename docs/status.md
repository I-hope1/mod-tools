# HotSwap 模块实现状态总表 (Implementation Status & Audit)

> **状态信息管理规则（强制）**：  
> 本文件为 HotSwap 全模块实现状态与验收审计的 **唯一详细来源**（高层高价值概括见 `AGENTS.md` §5）。  
> **严禁在 Javadoc、代码注释以及 `docs/` 设计规范文档中重新混入易腐的实现状态信息**（如 `✅`、`[已实现]`、`[未实现]` 等）。  
> 各设计文档与 Javadoc 仅负责定义客观的架构规格、设计原理与交互契约；实现状态若有变更，统一在此登记。

---

## 状态图例与判定准则 (Legend & Criteria)

| 图例 | 状态定义            | 判定准则与要求                                                                                                                                |
|:----:|:--------------------|:----------------------------------------------------------------------------------------------------------------------------------------------|
|  ✅  | 已实现              | 生产代码落地 + 拥有对应的自动化测试/回归断言，并在主构建与 CI 中持续全绿。                                                                    |
|  🔶  | 部分实现 / 存在残余 | 主路径可用，但存在未覆盖的边缘形态、无独立测试夹具或存在已知残余缝隙（行内必须明确注明缺什么）。                                              |
|  🟣  | 有意偏离            | 为修复实际缺陷或守卫核心不变量，经架构决策有意不按原设计规格字面实现。行内必须附带决策记录（`docs/topology/08-decisions.md`）链接与具体原因。 |
|  ⬜  | 路线图规划 / 待办   | 设计规格已定义但当前尚未落地实现的代码项或测试项。                                                                                            |
|  ❌  | 已废除              | 历史上曾存在，但为守卫类身份与内存安全已被彻底物理拔除的机制。                                                                                |

---

## 1. 匿名类拓扑对齐与安全门 (Anonymous Class Alignment & Safety Gates)

设计规格文档：[`docs/topology/`](topology/) 系列文档  
架构决策记录：[`docs/topology/08-decisions.md`](topology/08-decisions.md)

| 模块 / 机制                                 | 状态 | 落地位置 / 验收证据                                                                               | 说明与决策依据                                                                                                                                                                                                               |
|:--------------------------------------------|:----:|:--------------------------------------------------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| javac 8/11/17/21/25 本机编译矩阵支持        |  ✅  | `AnonClassReproJUnitTest` / CI `.github/workflows/hstest.yml`                                     | CI 钉死 Temurin 25 runner，5 套工具链（8/11/17/21/25）现编夹具矩阵验证通过；注意 `SwitchMapAlignTest` 单独在 javac 21 验证                                                                                                   |
| 新 javac 加旧 -target 交叉编译矩阵          |  ⬜  | 待验证                                                                                            | 新版 javac 携带 `-target 8/11` 等交叉编译产物的命名与属性表现尚未系统性覆盖                                                                                                                                                  |
| ECJ 编译器支持                              |  ⬜  | 暂无 ECJ 真实夹具                                                                                 | 见 `docs/topology/07-layout-gate-and-risks.md` §4；ECJ 不生成 SwitchMap 类                                                                                                                                                   |
| 未知 class major 显式拒绝                   |  ⬜  | 路线图待办                                                                                        | 遇到未知高版本 class major 时显式拒绝而非尝试解析                                                                                                                                                                            |
| 类身份与实例状态保真不变量 (INV-Remap)      |  🔶  | `AnonClassAligner`                                                                                | 彻底移除 Tier 5；未匹配旧类保留为孤儿，未匹配新类分配安全新名；**存在盲区**：局部类内部嵌套匿名类（如 `Foo$1Helper$1`）因含字母被 `isAnonymousClassName` 排除而原名直通，若局部类发生位移存在潜在槽位篡夺漏洞                |
| `renameMap` 与字节码改写                    |  ✅  | `AnonClassAligner.remapClass`                                                                     | ASM `ClassWriter(0)` + `SimpleRemapper`，不重算栈帧避免类加载死锁                                                                                                                                                            |
| 全局保留名域与挂起避让                      |  ✅  | `takenTargetNames` / `reserved`                                                                   | 覆盖第 1/2/3/4 类保留名（第 3 类为未参与对齐的排除类名，如静态嵌套类、非匿名局部类等显式占用的物理类名，防止重命名覆盖）；快照接入 `pendingAlignedClasses`；未加载类编号抬高为已知无害代价                                   |
| 字节码摘要与恒等映射短路                    |  ✅  | `HotSwapAgent.processChanges`                                                                     | 新旧字节码相同直接跳过；`renameMap` 全恒等映射直接返回原字节码                                                                                                                                                               |
| 全局独占锁互斥                              |  ✅  | `HotSwapAgent.HOTSWAP_LOCK`                                                                       | 热更新调度入口与事务处理覆盖全局锁，杜绝重入与并发竞态                                                                                                                                                                       |
| Tier 1: 内容哈希 + 宿主方法精确匹配         |  ✅  | `AnonClassHasher` + `AnonClassAligner`                                                            | 双方哈希完全一致且同宿主方法，置信度最高                                                                                                                                                                                     |
| Tier 1.5: 内容相似度                        |  ⬜  | 路线图规划                                                                                        | 规划计算指令哈希交集率与字符串 Jaccard；用于根治拓扑无信息同构候选平局                                                                                                                                                       |
| Tier 2: 全局唯一内容哈希匹配                |  ✅  | `AnonClassAligner`                                                                                | 仅全类唯一孤本采纳，严禁跨方法 minDiff                                                                                                                                                                                       |
| Tier 3: 结构签名 + 拓扑相等过滤             |  🔶  | `AnonClassAligner.topologyEqual`                                                                  | minDiff 已被拓扑相等过滤取代以杜绝前插错配；夹具 M（拓扑无信息）与夹具 L（2×2 同构）仍保守拒绝                                                                                                                               |
| 夹具 L KNOWN 保守代价收敛                   |  ⬜  | `AnonClassReproTest.known`                                                                        | 夹具 L 当前作为 KNOWN 保护保守拒绝行为；未来需引入正交维度以在安全前提下恢复配对                                                                                                                                             |
| Tier 4: 松散结构 + 状态布局门包装           |  🔶  | `AnonClassAligner` + `LayoutGate`                                                                 | 同宿主方法与基类/接口；禁止 minDiff；包装布局门防存活实例零值污染；**存在残余风险**：依赖 `normalizeEnclosingMethod` 的 scope 偶然不一致，若旧匿名类宿主方法同样无法反向追溯退化为 null，可能与非方法匿名类或 SwitchMap 误配 |
| Tier 5: 物理类名盲配                        |  ❌  | `AnonClassAligner`                                                                                | 为守卫核心不变量已彻底移除，前 4 层未匹配直接判为新类/孤儿，杜绝槽位篡夺                                                                                                                                                     |
| 嵌套匿名类描述符定向屏蔽与后置校验          |  ✅  | `MethodFingerprinter.maskDescriptor` + `verifyFieldLayoutAfterRename`                             | 字段、方法与构造器描述符定向屏蔽外层纯数字序号，后置校验防止改名碰撞                                                                                                                                                         |
| 非方法上下文作用域归约                      |  🔶  | `AnonClassAligner.normalizeEnclosingMethod`                                                       | 无三态标签（`<initializer>`/`<clinit>`/`<init>`），靠 `orderIndex` 与结构签名区分                                                                                                                                            |
| INV-1 自描述指纹与 INV-2 禁止互相递归       |  ✅  | `AnonClassReproJUnitTest.s25_designInvariants`                                                    | 常量池扫描与内容变异架构守卫，防止雪崩污染与循环调用                                                                                                                                                                         |
| 宿主级原子拒绝机制                          |  ✅  | `AlignmentRejectedException` / `HotSwapAgent.rejectHostGroup`                                     | strict 歧义、超限、超时或关闭时，宿主与派生类整组移出本轮并输出 `[HOTSWAP-REJECT]`                                                                                                                                           |
| 性能安全硬闸门                              |  ✅  | `MAX_ANON_PER_HOST = 128`, `ALIGN_TIMEOUT_MS = 2000`                                              | 数量超限或超时直接触发宿主组原子拒绝                                                                                                                                                                                         |
| 嵌套深度硬上限                              |  🔶  | `HotSwapAgent.ANON_STRICT`                                                                        | strict 模式下 `depth > 4` 拒绝；常规模式下仅告警，`MAX_DEPTH` 死代码尚无独立夹具                                                                                                                                             |
| 事务生命周期与乐观预登记                    |  ✅  | `AlignmentTransaction.preRegister`                                                                | 重定义前预先登记 pending 注入项，消除类加载重入读取磁盘错位产物竞态                                                                                                                                                          |
| 匿名类合成捕获字段布局门                    |  ✅  | `LayoutGate.check` / `ANON_LAYOUT_GATE`                                                           | 阻断捕获字段增/改导致存活实例读取零值；默认模式 `reject`                                                                                                                                                                     |
| 具名类与用户显式字段布局门                  |  ✅  | `HotSwapAgent.applyRedefineLayoutGate` / `LAYOUT_GATE`                                            | 阻断用户字段删除、类型变更、静态性变更；默认模式 `reject`；纯新增放行交给 InitFix                                                                                                                                            |
| 重定义层三态存活判定与静态字段强拒绝        |  ✅  | `HotSwapAgent.decideLayout`                                                                       | `COMPATIBLE` / `WAIVED` / `REJECTED`；静态字段即便零实例也坚决拒绝（类共享状态不可放行）                                                                                                                                     |
| 同名局部类编号漂移止血门                    |  🔶  | `LocalClassGuard` / `LOCAL_CLASS_GUARD`                                                           | 识别同名局部类计数 $\ge 2$ 并将宿主整族移出本轮；**未覆盖“唯一简单名局部类内部的匿名类位移”形态**                                                                                                                            |
| 同名局部类完整拓扑对齐                      |  ⬜  | 路线图规划                                                                                        | 规划准入与保留简单名改号；当前由止血门安全拦截                                                                                                                                                                               |
| 特性控制开关集合                            |  ✅  | `HotSwapAgent` 配置表                                                                             | 覆盖 `anon_align`（false 为整组移出）、`anon_strict`、`anon_debug`、`anon_layout_gate`、`layout_gate`、`local_class_guard`                                                                                                   |
| 蜕变测试准则 (Metamorphic Suite)            |  ✅  | `AnonClassReproJUnitTest.s19_cascadingTreeAndMetamorphicSuite`                                    | 验证标记置换不变性、幂等性、尾部追加不变性与故障注入零静默错配                                                                                                                                                               |
| 清理项：`tier5Matches` 死字段清理           |  ⬜  | `AnonClassAligner.AlignStats`                                                                     | Tier 5 移除后残留的统计字段待清理                                                                                                                                                                                            |
| **[偏离]** 保留内容哈希而不剥离子类引用     |  🟣  | 决策记录 [D-ANON-1](topology/08-decisions.md#d-anon-1)                                            | 剥离子类引用会导致同构无参 Runnable（Save/Delete）lambda 指纹相同，引发严重静默对调劫持                                                                                                                                      |
| **[偏离]** 不引入 `#ANON_COARSE` 粗粒度签名 |  🟣  | 决策记录 [D-ANON-2](topology/08-decisions.md#d-anon-2)                                            | 粗粒度签名对同构 Runnable 逐字节相同，使宿主指令流丧失区分度，直接复发 Save/Delete 对调                                                                                                                                      |
| **[偏离]** 不实现 SwitchMap 排除与原名直通  |  🟣  | 决策记录 [D-ANON-3](topology/08-decisions.md#d-anon-3) / 验收证据 `SwitchMapAlignTest` (javac 21) | 原名直通会打开槽位篡夺；当前靠 scope 偶然不一致安全，残余形态记录见下方风险清单                                                                                                                                              |
| **[偏离]** 合成捕获字段计入哈希而非剥离     |  🟣  | 决策记录 [D-ANON-4](topology/08-decisions.md#d-anon-4)                                            | JBR 证实捕获字段变更使存活实例读零值；计入哈希以触发 Tier 4 布局门拦截，防止原地破坏                                                                                                                                         |
| **[偏离]** `access$` 编译器访问器保名不改名 |  🟣  | 决策记录 [D-ANON-5](topology/08-decisions.md#d-anon-5)                                            | 跨类调用点无法同步修改，改名会导致 NoSuchMethodError；保名可区分不同访问器                                                                                                                                                   |
| **[偏离]** 继承体系变更一律拒绝并提示重启   |  🟣  | 决策记录 [D-ANON-7](topology/08-decisions.md#d-anon-7) / `HotSwapAgent.processChanges`            | 与原规划"尝试支持接口变更"相反，父类/接口变动一律熔断以守卫全局类型体系安全                                                                                                                                                  |

---

## 2. 存量初始化修复器 (InitFix)

设计规格文档：[`docs/initfix/`](initfix/) 系列文档  
架构决策记录：[`docs/initfix/06-decisions.md`](initfix/06-decisions.md)

| 机制 / 条目                             | 状态 | 规范章节                                   | 说明与验收依据                                                                                                                                                                                                 |
|:----------------------------------------|:----:|:-------------------------------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 五条核心不变量                          |  ✅  | `AGENTS.md` §2                             | 未初始化守卫、PENDING 弱键 (5min TTL)、PatchReport 解耦、LinkageError 窄熔断、失败立即注销                                                                                                                     |
| 离线元数据契约 (`ClassHierarchyOracle`) |  ✅  | `docs/initfix/01-safety-gate.md` §5        | 全程无类加载；流式字节码扫描；`HierarchyTreeOracle`                                                                                                                                                            |
| 待补字段台账 (`FieldLedger`)            |  ✅  | `docs/initfix/02-closure-and-ledger.md` §2 | 覆盖六种记账情形（分析期被拒、运行期异常、依赖跳过、Redefine 失败、规划期异常、TTL 超时清扫）                                                                                                                  |
| T0 零值等价判决                         |  ✅  | `docs/initfix/01-safety-gate.md` §1        | 按位比较排除 `-0.0f`/`NaN`；`NOTHING_TO_PATCH` 零开销放行不告警                                                                                                                                                |
| T1 编译期常量                           |  🔶  | `docs/initfix/01-safety-gate.md` §1        | 静态 `ConstantValue` 走专用通道；字面量切片仍走常规直线提取                                                                                                                                                    |
| T2 纯计算切片                           |  🔶  | `docs/initfix/01-safety-gate.md` §1        | 经 `AliasInterpreter` 逆向切片并通过效应安全门                                                                                                                                                                 |
| T3 复杂/不安全拒绝                      |  ✅  | `docs/initfix/01-safety-gate.md` §1        | 分支、try/catch、环境依赖、可变源字段，统一在 `transform` 发 warn                                                                                                                                              |
| T4 显式逃生口 `@HotswapInit`            |  ⬜  | `docs/initfix/01-safety-gate.md` §1        | 路线图规划（伴生类直接静态转发）                                                                                                                                                                               |
| 最小效应检查（P0）                      |  🔶  | `docs/initfix/01-safety-gate.md` §2        | 拦截 bit 3/6 及 bit 4/5 子集；白名单防误杀；Kotlin `trim` 家族 `Object.toString` 窄例外                                                                                                                        |
| 8 位完整效应掩码                        |  ⬜  | `docs/initfix/01-safety-gate.md` §2.2      | 目标设计（PURE, READS_FINAL, ALLOC_PURE 等）                                                                                                                                                                   |
| 参数回溯不可变证明                      |  ✅  | `docs/initfix/01-safety-gate.md` §3        | 条件 A（final/val）与条件 B（private + `NestView` 全 Nest 单写证明）                                                                                                                                           |
| 多根构造器共识                          |  ✅  | `docs/initfix/01-safety-gate.md` §4        | 要求所有根构造器覆盖完整且参数替换后指令指纹 100% 一致                                                                                                                                                         |
| 依赖闭包与 Tarjan SCC 成环检测          |  ✅  | `docs/initfix/02-closure-and-ledger.md` §1 | 成环检测并入闭包不动点计算，只拒绝环成员；出口后置 `depInstance ∪ depStatic` 闭合校验兜底                                                                                                                      |
| 失败策略矩阵                            |  🔶  | `docs/initfix/02-closure-and-ledger.md` §3 | 已支持单字段失败跳过、依赖跳过、`LinkageError` 熔断；“放弃提交阶段 C”依赖两阶段协议                                                                                                                            |
| 伴生补丁类装配                          |  ✅  | `docs/initfix/03-runtime-driver.md` §1     | 每字段生成独立静态直线方法；宿主 hidden nestmate 隔离异常与加载死锁                                                                                                                                            |
| 逐实例补丁驱动与失败配额                |  ✅  | `docs/initfix/03-runtime-driver.md` §2     | 依赖失败按实例隔离；单字段每轮实例失败配额封顶 8 次（`MAX_INSTANCE_FAILURES_PER_FIELD`）                                                                                                                       |
| 写入协议 (`HotswapBridge`)              |  🔶  | `docs/initfix/03-runtime-driver.md` §3     | 默认条件 CAS；float/double raw bits CAS；跳过计数汇总汇报；`KIND_FORCE` 强制写；**缺自动化 CI 差异验证**（当前 CI 为 Temurin 25，无法区分 raw-bits 与旧实现；JDK 8 raw-bits CAS 为手工验证，非持续自动化覆盖） |
| 存量覆写扩展 (`@HotswapReinit`)         |  ✅  | `docs/initfix/03-runtime-driver.md` §4     | 豁免 T0 与后续加工门；不豁免切片安全门；支持 `CONDITIONAL` 与 `OVERWRITE`                                                                                                                                      |
| 合成标记字段过滤                        |  ✅  | `docs/initfix/03-runtime-driver.md` §5     | `ClassDiffUtil` 对称过滤 `ACC_SYNTHETIC` 与 `$nipx$` 前缀字段                                                                                                                                                  |
| JVMTI 多态堆实例检索                    |  ✅  | `docs/initfix/05-jvmti-heap.md`            | `LibTool.getInstances` C++ 底座；Tag 隔离与全局互斥；`InstanceTracker` 字节码回退                                                                                                                              |

---

## 3. 目标设计路线图 (Roadmap & Target Design)

设计规格文档：[`docs/initfix/04-target-design.md`](initfix/04-target-design.md)

| 规划条目                               | 状态 | 规范章节                                 | 关联问题 / 目标价值                                                                                             |
|:---------------------------------------|:----:|:-----------------------------------------|:----------------------------------------------------------------------------------------------------------------|
| 两阶段 Schema-First 重定义             |  ⬜  | `docs/initfix/04-target-design.md` §1    | 消除热更新瞬间新方法体读到未就绪字段的读穿异常，以及 `static final` 字段在 redefine 与补丁之间的 JIT 常量折叠   |
| `PatchPlan` 基线指纹校验               |  ⬜  | `docs/initfix/04-target-design.md` §2    | 校验 Target 当前内存字节码哈希与构建期基线一致性，防止漂移热更                                                  |
| Analyzer 独立库化                      |  ⬜  | `docs/initfix/04-target-design.md` §3    | 分析器作为纯函数独立，支持在 Gradle 构建端或 PC 端离线分析                                                      |
| 构造器尾部插桩                         |  ⬜  | `docs/initfix/04-target-design.md` §4    | 在阶段 A 构造器尾部注入切片，物理消除堆遍历快照到重定义生效之间的并发对象初始化缝隙                             |
| 跨类批次拓扑排序                       |  ⬜  | `docs/initfix/04-target-design.md` §5    | 解决类 X 新增字段依赖类 Y 新增字段时的跨类多事务批次协调（当前仅支持类内拓扑排序）                              |
| 子类引用多重集独立正交维度             |  ⬜  | `docs/topology/08-decisions.md#d-anon-1` | 将子类引用建模为独立正交维度，解决 D-ANON-1 中子类改动对父类逐级向上传导的雪崩退化，并收敛夹具 L/M 保守拒绝代价 |
| 增强重定义模式四个未覆盖盲区验证与防护 |  ⬜  | `docs/topology/08-decisions.md` 附录 A   | 覆盖 JIT 编译后内联代码对新布局的观察、`volatile`/`final` 复杂修饰符、继承体系跨层遮蔽、JBR 25 重复验证         |

---

## 4. 已知风险、残余盲区与折衷清单 (Known Residual Risks & Limitations)

本节记录系统中被评审确认、因工程代价或架构边界有意保留的已知残余风险：

1. **实例存活判定 TOCTOU 竞态窗口**：
   从堆遍历（`LibTool.getInstances`）判定“某类无存活实例”到真正执行 `redefineClasses` 之间，应用工作线程可能并发 `new`
   出一个旧布局实例。该实例将持有旧字段布局，重定义后读取新字段可能得到零值。加全局停顿锁代价过高，目前作为极窄竞态窗口记录。
2. **SwitchMap 类的偶然作用域安全**：
   SwitchMap 类未被排除且未原名直通。其之所以在实测中未发生误配，是因为旧匿名类宿主方法回退解析为 `<init>`，而 SwitchMap 无
   `NEW` 点其 `outerMethod` 保持为 `null`，两侧 scope 不一致导致 Tier 4 未配对。若未来遇到宿主方法同样为 `null`
   的旧匿名类，存在残余配对风险。
3. **`access$NNN` 跨编译编号重排风险**：
   为了防止破坏外部类对合成访问器的调用点并引发 `NoSuchMethodError`，系统采取了 `access$` 保名不改名策略。若 javac 重新编译导致
   accessor 编号发生置换，可能引发潜在的方法调用错配。
4. **InitFix Kotlin 行为结论的验证覆盖**：
   除 Kotlin `object` 单例与 `trim` 家族方法经过真实 `kotlinc` 产物编译与运行期检验外，其它 Kotlin
   复杂行为结论（如高阶函数内联、属性委托）主要通过 Java 字节码仿真验证。
5. **同名局部类止血门边界**：
   当前 `LocalClassGuard` 针对 `(owner, innerName)` 同名计数 $\ge 2$
   进行整族熔断。但对于“宿主内唯一简单名局部类内部的嵌套匿名类位移”，当前止血门无法感知，需待完整局部类拓扑对齐落地。
6. **构造器尾部插桩前的并发实例化缝隙**：
   在路线图 §4（构造器尾部插桩）落地前，从堆遍历快照完成到 JVM 执行 redefineClasses
   生效之间，并发创建的新对象未被补丁驱动捕获，其存活实例依赖默认零值与后续访问安全。
7. **InstanceTracker 字节码回退可靠性局限**：
   在无 JVMTI native 支持的环境下，`InstanceTracker` 基于弱引用集合和构造器插桩。GC
   触发可能导致弱引用丢失，且未插桩类或动态生成的实例无法被追踪，可能造成存活实例漏判。
8. **JBR 字段布局探针实验局限**：
   附录 A 的 8 组实验仅覆盖了基本字段重定义场景，尚未覆盖 JIT 深度优化后的内联代码观察、`volatile`/`final`
   内存可见性语义、以及复杂的父子类同名字段遮蔽。
