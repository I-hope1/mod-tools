# HotSwap 模块实现状态总表 (Implementation Status & Audit)

> **状态信息管理规则（强制）**：  
> 本文件为 HotSwap 全模块实现状态、技术债务与验收审计的 **唯一真相源 (Single Source of Truth)**（高层高价值概括见 [`AGENTS.md`](../AGENTS.md) §5）。  
> **严禁在 Javadoc、代码注释以及 `docs/` 设计规范文档中重新混入易腐的实现状态信息**（如 `✅`、`[已实现]`、`[未实现]` 等，由 CI 任务 `verifyDocStatusMarkers` 自动化守卫）。  
> 各设计规范文档仅负责定义客观的架构规格、设计原理与交互契约；实现状态若有变更，统一在此登记与审计。

| 审计元数据项 | 内容与规格说明 |
|:---|:---|
| **基准提交 (Baseline Commit)** | `5bd997f7` (分支: `main`，对应全量结构化重构审计通过点) |
| **最后更新时间** | 2026-10-13 |
| **审计负责人 / 角色** | HotSwap 架构守卫小组 & 自动化回归验证套件 |
| **更新触发条件** | 任何合入包含特性落地、架构偏离、安全门调整、测试基线变更或发现已知缺陷/残余风险的 PR/commit 时，必须同步原子化更新本文件。提交时由作者/维护者更新基准哈希。 |
| **管辖范围与边界声明** | 本状态表权威管辖：<br>1. **匿名类拓扑对齐与安全门 (Anonymous Class Alignment & Safety Gates)**（[`docs/topology/`](topology/)）<br>2. **存量初始化修复器 (InitFix / Legacy Instance & Static Reinitialization)**（[`docs/initfix/`](initfix/)）<br>*边界与后续治理声明*：同属字节码重映射体系的 `LambdaAligner`（Lambda 表达式对齐器）以及 Mindustry UI 胶水层（`CellPropertyRef` / 动态参数表达式指纹），其实现状态当前由各自单元测试覆盖（`CellHelperTest` 6 场景持续全绿、`LambdaAlignerTest` 等）；它们的详细状态审计矩阵尚未并入本总表，计划在下一轮治理中统一建立验收标准并纳入。 |

---

## 0. 状态治理与规范文档清理进度 (Status Governance & Documentation Cleansing)

为根治文档易腐性与状态漂移，本项目推行“规范规格与实现状态物理分离”治理方针。当前治理基线如下：

1. **唯一真相源隔离**：`docs/status.md` 作为全模块实现状态的唯一受权载体，彻底解除规范文档与代码注释维护实现进度的负担。
2. **双重验收验证机制**：
   - **自动化机器扫描 (`verifyDocStatusMarkers`)**：纳入主构建验证流程（`./gradlew check` 强制依赖），递归扫描 `docs/initfix/`、`docs/topology/`、`hotswap-core/src/`、`hotswap-api/src/`、`hotswap-plugin-arc/src/`，严格拦截标记 `/[✅❌⬜🔶🟣]|\[(已实现|未实现|部分实现|已偏离)/`。当前状态：**100% 达标全绿**。
   - **规范性内容抽检审计**：禁止规范正文出现“目前已支持”、“当前版本尚未实现”等时态陈述，时态陈述一律归入 `status.md`。全量 14 篇客观设计文档（`docs/topology/` 8 篇、`docs/initfix/` 6 篇）完成人工抽检审查，抽查合格率 100%。
3. **设计规范文档客观化清理清单**：
   - [`docs/topology/01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) ～ [`08-decisions.md`](topology/08-decisions.md)（共 8 篇）：全部重构为纯架构规格、约束契约与客观原理，易腐进度全部剥离，头部统一指引至 `status.md`。
   - [`docs/initfix/01-safety-gate.md`](initfix/01-safety-gate.md) ～ [`06-decisions.md`](initfix/06-decisions.md)（共 6 篇）：全部完成规格客观化改造，彻底消除进度标记。
   - [`AGENTS.md`](../AGENTS.md)：高层导读章节精简，保留五条不变量与高价值概括，详细状态全部外链至 `status.md`。

---

## 状态图例与判定准则 (Legend & Criteria)

| 图例 | 状态定义            | 判定准则与要求                                                                                                                                |
|:----:|:--------------------|:----------------------------------------------------------------------------------------------------------------------------------------------|
|  ✅  | 已实现              | 生产代码落地 + 拥有对应的自动化测试/回归断言，并在主构建与 CI 中持续全绿。**包含设计意图内的保守边界**。                                      |
|  🔶  | 部分实现 / 存在残余 | 主路径已上线，但存在未覆盖的已知边缘漏洞、尚未修复的已知残余缝隙，或缺少关键自动化测试（行内必须明确注明缺什么）。                            |
|  🟣  | 有意偏离            | 为修复实际缺陷或守卫核心不变量，经架构论证明确否定原设想或推翻原设计规格。行内必须附带决策记录（[`docs/topology/08-decisions.md`](topology/08-decisions.md)）链接与具体原因。 |
|  ⬜  | 路线图规划 / 待办   | 设计规格已定义但当前尚未落地实现的代码项或测试项。                                                                                            |
|  ❌  | 已废除              | 历史上曾存在，但为守卫类身份与内存安全已被彻底物理拔除的机制。                                                                                |

### 判定边界与反例清单 (Criteria Boundaries & Negative List)

为防止未来维护者误用状态标记，特确立以下判据边界与反例：
- **✅ 反例**：不适用于“虽未报错但缺少关键正向/负向回归测试”的项；但**明确适用于**“主路径功能完整且明确界定有意保守边界（如 Tier 3 对无信息/同构平局的确定性拒绝）”的项（意图内的保守拒绝属于设计完成态，而非缺陷）。
- **🔶 反例**：**严禁将“设计意图内的保守拦截”误标为 🔶**（那是 ✅）；**严禁将“从未实现的新功能”误标为 🔶**（那是 ⬜ 路线图）。🔶 严格限定于“代码已上线运行，但存在已知未覆盖的边缘漏洞或未闭环的工程缝隙”。
- **🟣 反例**：**严禁将普通的“暂缓实现的待办规划”标为 🟣**（那是 ⬜）。🟣 严格限定于“经过充分论证，明确决定不采用某方案（或推翻原设计），因该方案已被证明会破坏系统核心不变量或引入严重隐患”。

---

## 1. 匿名类拓扑对齐与安全门 (Anonymous Class Alignment & Safety Gates)

设计规格文档：[`docs/topology/`](topology/) 系列文档  
架构决策记录：[`docs/topology/08-decisions.md`](topology/08-decisions.md)

| 机制 / 条目 | 状态 | 规范章节 | 落地位置 | 验收证据与决策依据 |
|:---|:---:|:---|:---|:---|
| javac 8/11/17/21/25 本机编译矩阵支持 | ✅ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §1 | `build.gradle` (任务 `hstestJunit`) | `AnonClassReproJUnitTest` / CI `.github/workflows/hstest.yml`（Temurin 25 runner，5 套工具链现编夹具矩阵通过；注意 `SwitchMapAlignTest` 单独在 javac 21 验证） |
| 新 javac 加旧 -target 交叉编译矩阵 | ⬜ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §3 | 待配置测试矩阵 | 新版 javac 携带 `-target 8/11` 等交叉编译产物的命名与属性表现尚未系统性覆盖 |
| ECJ 编译器支持 | ⬜ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §4 | 暂未接入 ECJ 编译器 | 见 `07-layout-gate-and-risks.md` §4；ECJ 不生成 SwitchMap 类，EnclosingMethod 结构存在差异 |
| 未知 class major 显式拒绝 | ⬜ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §1 | `AnonClassAligner.align` 入口待拦截 | 路线图待办；遇到未知高版本 class major 时显式拒绝而非尝试解析，防止 ASM Crash |
| 类身份与实例状态保真不变量 (INV-Remap) | 🔶 | [`01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) §1 | `AnonClassAligner` | 彻底移除 Tier 5；未匹配旧类保留为孤儿，未匹配新类分配安全新名；**残余盲区**：局部类内部嵌套匿名类（如 `Foo$1Helper$1`）因含字母被 `isAnonymousClassName` 排除而原名直通，若局部类发生位移存在潜在槽位篡夺漏洞 |
| `renameMap` 与字节码改写 | ✅ | [`01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) §2 | `AnonClassAligner.remapClass` | ASM `ClassWriter(0)` + `SimpleRemapper`，不重算栈帧避免类加载死锁 |
| 全局保留名域与挂起避让 | ✅ | [`02-admission-and-reserved-names.md`](topology/02-admission-and-reserved-names.md) §2 | `AnonClassAligner.takenTargetNames` / `HotSwapAgent.pendingAlignedClasses` | 覆盖第 1/2/3/4 类保留名（第 3 类为未参与对齐的排除类名，如静态嵌套类、非匿名局部类等显式占用的物理类名，防止重命名覆盖）；快照接入 `pendingAlignedClasses`；未加载类编号抬高为已知无害代价 |
| 字节码摘要与恒等映射短路 | ✅ | [`01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) §2.3 | `HotSwapAgent.processChanges` | 新旧字节码相同直接跳过；`renameMap` 全恒等映射直接返回原字节码 |
| 全局独占锁互斥 | ✅ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §2 | `HotSwapAgent.HOTSWAP_LOCK` | 热更新调度入口与事务处理覆盖全局锁，杜绝重入与并发竞态 |
| Tier 1: 内容哈希 + 宿主方法精确匹配 | ✅ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §1 | `AnonClassHasher` + `AnonClassAligner` | 双方哈希完全一致且同宿主方法，置信度最高 (0.99) |
| Tier 1.5: 内容相似度 | ⬜ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §1 | 待实现于 `AnonClassAligner` | 路线图规划；规划计算指令哈希交集率与字符串 Jaccard；用于根治拓扑无信息同构候选平局 |
| Tier 2: 全局唯一内容哈希匹配 | ✅ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §1 | `AnonClassAligner` | 仅全类唯一孤本采纳，严禁跨方法 minDiff |
| Tier 3: 结构签名 + 拓扑相等过滤 | ✅ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §2 | `AnonClassAligner.topologyEqual` | minDiff 已被拓扑相等过滤取代以杜绝前插错配；夹具 M（拓扑无信息）与夹具 L（2×2 同构）的保守拒绝属于客观设计意图（杜绝静默错配的必要代价，见 [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §2.3），非未完成功能；其边界收敛计划归入 §3.1“子类引用多重集独立正交维度” |
| 夹具 L KNOWN 保守代价收敛 | ⬜ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §2.3 | `AnonClassReproTest.known` | 夹具 L 当前作为 KNOWN 保护保守拒绝行为；未来需引入正交维度以在安全前提下恢复配对 |
| Tier 4: 松散结构 + 状态布局门包装 | 🔶 | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §1 / [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1 | `AnonClassAligner` + `LayoutGate` | 同宿主方法与基类/接口；禁止 minDiff；包装布局门防存活实例零值污染；**存在残余风险**：依赖 `normalizeEnclosingMethod` 的 scope 偶然不一致，若旧匿名类宿主方法同样无法反向追溯退化为 null，可能与非方法匿名类或 SwitchMap 误配（见风险 2） |
| Tier 5: 物理类名盲配 | ❌ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §1 | `AnonClassAligner` (已物理拔除) | 为守卫核心不变量已彻底移除，前 4 层未匹配直接判为新类/孤儿，杜绝槽位篡夺 |
| 嵌套匿名类描述符定向屏蔽与后置校验 | ✅ | [`01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) §2.2 | `MethodFingerprinter.maskDescriptor` + `AnonClassAligner.verifyFieldLayoutAfterRename` | 字段、方法与构造器描述符定向屏蔽外层纯数字序号，后置校验防止改名碰撞 |
| 非方法上下文作用域归约 | 🔶 | [`03-cascading-pipeline.md`](topology/03-cascading-pipeline.md) §2 | `AnonClassAligner.normalizeEnclosingMethod` | 无显式三态标签（`<initializer>`/`<clinit>`/`<init>`），靠 `orderIndex` 与结构签名区分（演进规划见 §3.1） |
| INV-1 自描述指纹与 INV-2 禁止互相递归 | ✅ | [`03-cascading-pipeline.md`](topology/03-cascading-pipeline.md) §4 | `AnonClassHasher` / `AnonClassAligner` | `AnonClassReproJUnitTest.s25_designInvariants`；常量池扫描与内容变异架构守卫，防止雪崩污染与循环调用 |
| 宿主级原子拒绝机制 | ✅ | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §4 | `AlignmentRejectedException` / `HotSwapAgent.rejectHostGroup` | strict 歧义、超限、超时或关闭时，宿主与派生类整组移出本轮并输出 `[HOTSWAP-REJECT]` |
| 性能安全硬闸门 | ✅ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §3 | `AnonClassAligner.MAX_ANON_PER_HOST` (128), `ALIGN_TIMEOUT_MS` (2000) | 数量超限或超时直接触发宿主组原子拒绝 |
| 嵌套深度硬上限 | 🔶 | [`04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §4.1 | `HotSwapAgent.ANON_STRICT` / `MAX_DEPTH` | strict 模式下 `depth > 4` 拒绝；常规模式下仅告警，`MAX_DEPTH` 死代码尚无独立夹具（列入技术债 3.2） |
| 事务生命周期与乐观预登记 | ✅ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §2 | `AlignmentTransaction.preRegister` | 重定义前预先登记 pending 注入项，消除类加载重入读取磁盘错位产物竞态 |
| 匿名类合成捕获字段布局门 | ✅ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1 | `LayoutGate.check` / `HotSwapAgent.ANON_LAYOUT_GATE` | 阻断捕获字段增/改导致存活实例读取零值；默认模式 `reject` |
| 具名类与用户显式字段布局门 | ✅ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1 | `HotSwapAgent.applyRedefineLayoutGate` / `LAYOUT_GATE` | 阻断用户字段删除、类型变更、静态性变更；默认模式 `reject`；纯新增放行交给 InitFix |
| 重定义层三态存活判定与静态字段强拒绝 | ✅ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1 | `HotSwapAgent.decideLayout` | `COMPATIBLE` / `WAIVED` / `REJECTED`；静态字段即便零实例也坚决拒绝（类共享状态不可放行） |
| 同名局部类编号漂移止血门 | 🔶 | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §2 | `LocalClassGuard` / `HotSwapAgent.LOCAL_CLASS_GUARD` | 识别同名局部类计数 >= 2 并将宿主整族移出本轮；**未覆盖“唯一简单名局部类内部的匿名类位移”形态**（见风险 5） |
| 同名局部类完整拓扑对齐 | ⬜ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §2 | 待实现于 `LocalClassGuard` | 路线图规划；规划准入与保留简单名改号；当前由止血门安全拦截 |
| 特性控制开关集合 | ✅ | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §4 | `HotSwapAgent` 配置表 | 覆盖 `anon_align`（false 为整组移出）、`anon_strict`、`anon_debug`、`anon_layout_gate`、`layout_gate`、`local_class_guard` |
| 蜕变测试准则 (Metamorphic Suite) | ✅ | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §3 | `AnonClassReproJUnitTest.s19_cascadingTreeAndMetamorphicSuite` | 验证标记置换不变性、幂等性、尾部追加不变性与故障注入零静默错配 |
| **[偏离]** 保留内容哈希而不剥离子类引用 | 🟣 | [`08-decisions.md#d-anon-1`](topology/08-decisions.md#d-anon-1) | `AnonClassHasher` | 决策记录 [D-ANON-1](topology/08-decisions.md#d-anon-1)；剥离子类引用会导致同构无参 Runnable（Save/Delete）lambda 指纹相同，引发严重静默对调劫持 |
| **[偏离]** 不引入 `#ANON_COARSE` 粗粒度签名 | 🟣 | [`08-decisions.md#d-anon-2`](topology/08-decisions.md#d-anon-2) | `AnonClassHasher` | 决策记录 [D-ANON-2](topology/08-decisions.md#d-anon-2)；粗粒度签名对同构 Runnable 逐字节相同，使宿主指令流丧失区分度，直接复发 Save/Delete 对调 |
| **[偏离]** 不实现 SwitchMap 排除与原名直通 | 🟣 | [`08-decisions.md#d-anon-3`](topology/08-decisions.md#d-anon-3) | `AnonClassAligner` | 决策记录 [D-ANON-3](topology/08-decisions.md#d-anon-3)；验收证据 `SwitchMapAlignTest` (javac 21)；曾设计“将 SwitchMap 列入排除名录并原名直通”，但论证表明原名直通会在前插枚举时打开槽位篡夺漏洞，因而明确否决并推翻原排除直通设计；当前靠作用域差异与布局门安全拦截，残余机理见风险 2 |
| **[偏离]** 合成捕获字段计入哈希而非剥离 | 🟣 | [`08-decisions.md#d-anon-4`](topology/08-decisions.md#d-anon-4) | `AnonClassHasher` + `LayoutGate` | 决策记录 [D-ANON-4](topology/08-decisions.md#d-anon-4)；JBR 证实捕获字段变更使存活实例读零值；计入哈希以触发 Tier 4 布局门拦截，防止原地破坏 |
| **[偏离]** `access$` 编译器访问器保名不改名 | 🟣 | [`08-decisions.md#d-anon-5`](topology/08-decisions.md#d-anon-5) | `AnonClassAligner.remapClass` | 决策记录 [D-ANON-5](topology/08-decisions.md#d-anon-5)；跨类调用点无法同步修改，改名会导致 NoSuchMethodError；保名可区分不同访问器 |
| **[偏离]** 继承体系变更一律拒绝并提示重启 | 🟣 | [`08-decisions.md#d-anon-7`](topology/08-decisions.md#d-anon-7) | `HotSwapAgent.processChanges` | 决策记录 [D-ANON-7](topology/08-decisions.md#d-anon-7)；与原规划“尝试支持接口变更”相反，父类/接口变动一律熔断以守卫全局类型体系安全 |

---

## 2. 存量初始化修复器 (InitFix)

设计规格文档：[`docs/initfix/`](initfix/) 系列文档  
架构决策记录：[`docs/initfix/06-decisions.md`](initfix/06-decisions.md)

| 机制 / 条目 | 状态 | 规范章节 | 落地位置 | 验收证据与决策依据 |
|:---|:---:|:---|:---|:---|
| 五条核心不变量 | ✅ | [`AGENTS.md`](../AGENTS.md) §2 | `InitFix` 主流程入口与熔断保护 | 未初始化守卫、PENDING 弱键 (5min TTL)、PatchReport 解耦、LinkageError 窄熔断、afterRedefineFailed 立即注销；`hstestInitFixOracle` 48 场景持续守卫 |
| 离线元数据契约 (`ClassHierarchyOracle`) | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §5 | `ClassHierarchyOracle` / `HierarchyTreeOracle` | 全程无类加载；流式字节码扫描；`AnnotationTransformer.HierarchyTree` 底座支撑 |
| 待补字段台账 (`FieldLedger`) | ✅ | [`02-closure-and-ledger.md`](initfix/02-closure-and-ledger.md) §2 | `InitFix.LEDGER` (`FieldLedger`) | 覆盖六种记账情形（分析期被拒、运行期异常、依赖跳过、Redefine 失败、规划期异常、TTL 超时清扫）；`getUnpatchedFields` 状态回流 |
| T0 零值等价判决 | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §1 | `InitFix.checkSafe` / `NOTHING_TO_PATCH` | 按位比较排除 `-0.0f`/`NaN`；`NOTHING_TO_PATCH` 零开销放行不告警；`hstestInitFixOracle` 验证通过 |
| T1 编译期常量 | 🔶 | [`01-safety-gate.md`](initfix/01-safety-gate.md) §1 | `InitFix.extractFieldInits` | 静态 `ConstantValue` 走专用通道；字面量切片仍走常规直线提取 |
| T2 纯计算切片 | 🔶 | [`01-safety-gate.md`](initfix/01-safety-gate.md) §1 | `InitFix.extractFieldInits` / `AliasInterpreter` | 经 `AliasInterpreter` 逆向切片并通过效应安全门；`hstestInitFixOracle` 多场景覆盖 |
| T3 复杂/不安全拒绝 | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §1 | `InitFix.checkSafe` / `InitFix.transform` | 分支、try/catch、环境依赖、可变源字段，统一在 `transform` 发 warn 并记入台账；负向断言全面覆盖 |
| T4 显式逃生口 `@HotswapInit` | ⬜ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §1 | 待实现于伴生类生成逻辑 | 路线图规划（伴生类直接静态转发至用户指定的初始化工厂方法） |
| 最小效应检查（P0） | 🔶 | [`01-safety-gate.md`](initfix/01-safety-gate.md) §2 | `InitFix.effectReason` / `blacklistedCallReason` | 拦截 bit 3/6 及 bit 4/5 子集；白名单防误杀；Kotlin `trim` 家族 `Object.toString` 窄例外（变异测试守卫） |
| 8 位完整效应掩码 | ⬜ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §2.2 | 待实现于 `AliasInterpreter` | 目标设计路线图（PURE, READS_FINAL, ALLOC_PURE 等完整位图） |
| 参数回溯不可变证明 | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §3 | `InitFix.scanParamFields` / `InitFix.NestView` | 条件 A（final/val 字段）与条件 B（private + Nest 全单写证明）；`sourceFieldNotImmutableReason` 守卫 |
| 多根构造器共识 | ✅ | [`01-safety-gate.md`](initfix/01-safety-gate.md) §4 | `InitFix.extractFieldInits` | 要求所有根构造器覆盖完整且参数替换后指令指纹 100% 一致；多构造器歧义安全拒绝 |
| 依赖闭包与 Tarjan SCC 成环检测 | ✅ | [`02-closure-and-ledger.md`](initfix/02-closure-and-ledger.md) §1 | `InitFix.buildPatch` | 成环检测并入闭包不动点计算，只拒绝环成员；出口后置 `depInstance ∪ depStatic` 闭合校验兜底 |
| 失败策略矩阵 | 🔶 | [`02-closure-and-ledger.md`](initfix/02-closure-and-ledger.md) §3 | `InitFix.runIndependentInstanceTasks` / `InstanceFailureBudget` | 已支持单字段失败跳过、依赖跳过、`LinkageError` 熔断；“放弃提交阶段 C”依赖两阶段协议 |
| 伴生补丁类装配 | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §1 | `InitFix.generatePatchClass` / `Lookup.defineHiddenClass` | 每字段生成独立静态直线方法；宿主 hidden nestmate 隔离异常与加载死锁 |
| 逐实例补丁驱动与失败配额 | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §2 | `InitFix.runDependentInstanceTasks` / `InstanceFailureBudget` | 依赖失败按实例隔离；单字段每轮实例失败配额封顶 8 次（`MAX_INSTANCE_FAILURES_PER_FIELD`） |
| 写入协议 (`HotswapBridge`) | 🔶 | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §3 | `HotswapBridge` (`KIND_CONDITIONAL` / `KIND_FORCE`) | 默认条件 CAS；float/double raw bits CAS；跳过计数汇总汇报；`KIND_FORCE` 强制写；**缺自动化 CI 差异验证**（当前 CI 为 Temurin 25，无法区分 raw-bits 与旧实现；JDK 8 raw-bits CAS 为手工验证，非持续自动化覆盖，列入技术债 3.2） |
| 存量覆写扩展 (`@HotswapReinit`) | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §4 | `nipx.annotation.HotswapReinit` / `InitFix.buildPatch` | 豁免 T0 与后续加工门；不豁免切片安全门；支持 `CONDITIONAL` 与 `OVERWRITE` |
| 合成标记字段过滤 | ✅ | [`03-runtime-driver.md`](initfix/03-runtime-driver.md) §5 | `ClassDiffUtil.isInternalMarkerField` | 对称过滤 `ACC_SYNTHETIC` 与 `$nipx$` 前缀字段 |
| JVMTI 多态堆实例检索 | ✅ | [`05-jvmti-heap.md`](initfix/05-jvmti-heap.md) | `LibTool.getInstances` / `InstanceTracker` | `LibTool.getInstances` C++ 底座；Tag 隔离与全局互斥；`InstanceTracker` 字节码回退 |

---

## 3. 演进路线与技术债务清单 (Roadmap & Technical Debt)

本节将尚未落地的目标设计规格与工程技术债务严格区分，分别建立优先级、前置依赖与验收目标。

### 3.1 目标能力演进规划 (Feature Roadmap & Architecture Evolution)

设计规格文档：[`docs/initfix/04-target-design.md`](initfix/04-target-design.md) 与各专题规范

> **优先级定义**：  
> - **P1 (High)**：下一个架构重构里程碑核心目标（阻断核心竞态、杜绝严重未就绪访问）；  
> - **P2 (Medium)**：近期演进计划，具备明确设计规格但无阻断性前置依赖，按批次推进；  
> - **P3 (Low / Long-term)**：长期探索与多编译器兼容矩阵扩展，视外部环境需求按需引入。

| 规划条目 | 优先级 | 规范章节 | 前置依赖 | 目标价值与关联问题 |
|:---|:---:|:---|:---|:---|
| **独立仓库远程发布与 CI 矩阵** | P1 | 独立工程提取 | `nipx-hotswap` 本地构建与全量测试通过 | 将同级独立仓库推送到 GitHub/Gitee 远程并启用多 JDK 自动化 CI 守护 |
| **独立制品 Maven Central / JitPack 分发** | P1 | 发布发布配置 | 独立仓库通过远程 CI 检验 | 配置 Maven Central / JitPack 发布元数据与 GPG 签名，对外提供独立依赖坐标 |
| **mod-tools 消费侧切换与内部副本清理** | P1 | 架构解耦闭环 | 独立制品发布完成 | 将 mod-tools 依赖切换为外部 Maven 坐标，彻底移除仓内 core 副本完成解耦闭环 |
| **构造器尾部插桩** | P1 | [`04-target-design.md`](initfix/04-target-design.md) §4 | `InitFix` 离线切片提取与直线字节码生成器 (`AliasInterpreter`) | 在阶段 A 构造器尾部注入切片，物理消除堆遍历快照完成到重定义生效之间的并发对象初始化缝隙（闭合风险 1 与风险 6） |
| **两阶段 Schema-First 重定义** | P1 | [`04-target-design.md`](initfix/04-target-design.md) §1 | 构造器尾部插桩、JBR 21+ 增强重定义 API 探针 | 消除热更新瞬间新方法体读到未就绪字段的读穿异常，以及 `static final` 字段在 redefine 与补丁之间的 JIT 常量折叠 |
| **PatchPlan 基线指纹校验** | P1 | [`04-target-design.md`](initfix/04-target-design.md) §2 | 离线分析器导出协议 | 校验 Target 当前内存字节码哈希与构建期基线一致性，防止类漂移热更造成严重字节码损坏 |
| **非方法上下文显式三态作用域规范化** | P2 | [`03-cascading-pipeline.md`](topology/03-cascading-pipeline.md) §2 | ASM EnclosingMethod 属性增强解析 | 引入 `<initializer>`/`<clinit>`/`<init>` 显式三态标签，解决当前非方法作用域主要依靠 `orderIndex` 兜底导致的作用域偶发空指针错配（收敛风险 2） |
| **跨类批次拓扑排序** | P2 | [`04-target-design.md`](initfix/04-target-design.md) §5 | 跨类字段依赖图构建器 | 解决类 X 新增字段依赖类 Y 新增字段时的跨类多事务批次协调（当前仅支持单类内部拓扑排序） |
| **子类引用多重集独立正交维度** | P2 | [`08-decisions.md#d-anon-1`](topology/08-decisions.md#d-anon-1) | `AnonClassHasher` 哈希解耦 | 将子类引用建模为独立正交维度，解决 D-ANON-1 中子类改动对父类逐级向上传导的雪崩退化，收敛夹具 L/M 保守拒绝代价 |
| **Analyzer 独立库化** | P2 | [`04-target-design.md`](initfix/04-target-design.md) §3 | ASM 工具链解耦 | 分析器作为纯函数独立，支持在 Gradle 构建端或 PC 端离线分析，减小运行期探针开销与内存占用 |
| **增强重定义模式四个未覆盖盲区验证与防护** | P2 | [`08-decisions.md`](topology/08-decisions.md) 附录 A | 专用探针套件建设 | 覆盖 JIT 编译后内联代码对新布局的观察、`volatile`/`final` 复杂修饰符、继承体系跨层遮蔽、JBR 25 重复验证 |
| **ECJ 编译器矩阵支持** | P3 | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §4 | ECJ 构建环境夹具 | 针对 Eclipse 编译器的 switch table 实现（内部 `$SWITCH_TABLE$` 方法）与 EnclosingMethod 结构差异建立对齐与防护 |
| **新 javac 加旧 -target 交叉编译矩阵支持** | P3 | [`07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §3 | CI 交叉编译任务配置 | 验证新版 javac 携带 `-target 8/11` 等产物的命名与属性表现 |
| **未知 class major 显式拒绝** | P3 | [`06-runtime-and-perf.md`](topology/06-runtime-and-perf.md) §1 | 无 | 遇到高于当前 JVM 支持的 class major 版本时显式拒绝而非尝试解析，防止 ASM Crash |

### 3.2 技术债与死代码消除清单 (Tech Debt & Code Cleanup)

| 债务 / 清理项 | 优先级 | 影响位置 | 现状分析与消除方案 |
|:---|:---:|:---|:---|
| **`tier5Matches` 残余死字段清理** | P2 | `AnonClassAligner.AlignStats` | Tier 5（物理类名盲配）已彻底拔除，但统计结构体中仍残留 `tier5Matches` 字段与日志引用；需从对齐统计结构体及相关日志中安全移除，避免误导维护者。 |
| **`MAX_DEPTH` 死代码分支与夹具缺失** | P2 | `HotSwapAgent.ANON_STRICT` / `AnonClassAligner` | 常规模式下 `depth > 4` 仅告警不拒绝，且缺乏多层嵌套严格熔断测试夹具；需补充深层嵌套测试夹具并清理冗余判断逻辑。 |
| **JDK 8 raw-bits CAS 自动化 CI 差异验证缺失** | P2 | `.github/workflows/hstest.yml` / `HotswapBridge` | 当前 CI 仅运行在 Temurin 25，无法区分 raw-bits 与旧实现；JDK 8 raw-bits CAS 为手工验证，非持续自动化覆盖；需在 CI 矩阵中增加 JDK 8 运行期任务以持续守护差异逻辑。 |

---

## 4. 已知风险、残余盲区与折衷清单 (Known Residual Risks & Limitations)

本节系统记录热更新基础设施中被评审确认、因工程代价或架构边界有意保留的已知残余风险，统一按六段式元结构规范登记：

### 风险 1：实例存活判定 TOCTOU 竞态窗口
- **风险描述与底层机制**：从堆遍历（`LibTool.getInstances`）判定“某类无存活实例”到真正执行 `redefineClasses` 生效之间，存在纳秒至微秒级的时间窗口。
- **触发条件**：应用工作线程在此窗口内并发执行 `new Foo()`。该新实例在重定义生效前分配，持有旧字段布局；重定义后访问新增字段将读取默认零值。
- **当前缓解措施**：
  1. 重定义全程持有 `HOTSWAP_LOCK` 全局互斥锁；
  2. 游戏通常在主循环安全点或调试暂停期触发热更；
  3. 若类已存在已知存活实例，直接进入 InitFix 逐实例补丁队列，不依赖“零实例豁免”。
- **根治与关闭条件**：落地路线图 §3.1“两阶段重定义与构造器尾部插桩”；或在 HotSwap 期间通过 JVM TI 暂停全部应用线程（代价过高，目前维持极窄竞态记录）。
- **关联设计规范**：[`docs/topology/07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1.3。
- **关联架构决策**：无（属并发系统已知 TOCTOU 折衷）。

### 风险 2：SwitchMap 类的偶然作用域安全
- **风险描述与底层机制**：编译器生成的枚举 switch 映射类（如 `Foo$1`）未被特殊排除且未原名直通。它之所以在实测中未与其它匿名类发生误配，是因为业务匿名类的宿主方法回退解析为 `<init>`，而 SwitchMap 无 `NEW` 点其 `outerMethod` 保持为 `null`，两侧 scope 不一致导致 Tier 4 匹配天然失败。
- **触发条件**：若旧代码中存在宿主方法同样无法追溯（退化为 `outerMethod == null`）、且基类同样为 `Object`、未实现接口的业务匿名类，两者可能在 Tier 4 被误判为同一作用域并发生错配。
- **当前缓解措施**：
  1. **通用规则的偶然防护**：Tier 4 通用的基类与接口完全一致校验规则，恰好覆盖了绝大多数 SwitchMap 误配路径（SwitchMap 基类为 Object 且无接口，而业务匿名类几乎均实现特定接口或抽象基类）。这不是专门为 SwitchMap 添加的规则，但客观上构成了首道防线；
  2. **合成捕获字段布局门拦截**：SwitchMap 类通常无捕获字段，若发生实例字段变动会被 `LayoutGate` 拦截；
  3. **专测夹具持续断言**：专用测试夹具 `SwitchMapAlignTest` 在 javac 21 真实产物下持续断言未发生篡夺。
- **根治与关闭条件**：引入 SwitchMap 特异性特征识别（`Synthetic` + 静态 `int[]` 字段 + 宿主类前缀），将其显式纳入保留名域并排斥在匿名类候选集之外；或推进 §3.1“非方法显式三态作用域规范化”。
- **关联设计规范**：[`docs/topology/04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md) §1。
- **关联架构决策**：[`docs/topology/08-decisions.md#d-anon-3`](topology/08-decisions.md#d-anon-3)。

### 风险 3：`access$NNN` 跨编译编号重排风险
- **风险描述与底层机制**：javac 编译内部类访问外部私有成员时会生成合成静态桥方法（如 `access$000`、`access$100`）。为防止外部类对合成方法的调用点失效并抛出 `NoSuchMethodError`，系统采取 `access$` 保名不改名策略（D-ANON-5）。
- **触发条件**：若代码修改导致外部类私有访问发生增删，javac 重新编译后合成访问器的编号可能发生置换（例如原来的 `access$000` 变成 `access$100`，新私有访问占用了 `access$000`），从而导致旧字节码调用错误的访问器方法。
- **当前缓解措施**：
  1. 现代 Java 11+（Nestmate 特性）已原生消除绝大多数 `access$` 桥方法；
  2. 宿主类与内部类在同一重定义批次中整体更新，消除跨版本调用不匹配。
- **根治与关闭条件**：
  - **短期/项目内可达闭环**：在维持 `access$` 保名的前提下，在 `AnonClassAligner`/`ClassDiffUtil` 引入“访问器编号 -> 被访问目标语义指纹（包含目标成员名与描述符）”映射，当无法证明新旧编号目标一致时，降级为宿主组原子拒绝，杜绝调用错配；
  - **长期/外部环境演进**：当未来上游运行环境与构建矩阵彻底剥离 Java 8 支持并全面提升至 Java 11+（Nestmate 原生无桥方法）时，该问题自然淘汰。
- **关联设计规范**：[`docs/topology/01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md) §2。
- **关联架构决策**：[`docs/topology/08-decisions.md#d-anon-5`](topology/08-decisions.md#d-anon-5)。

### 风险 4：InitFix Kotlin 复杂行为结论的验证覆盖
- **风险描述与底层机制**：Kotlin 编译器的代码生成逻辑与 Java javac 存在显著差异（如属性委托、内联函数协变重整、伴生对象静态桥等）。
- **触发条件**：新增字段涉及复杂的 Kotlin 语法糖（例如委托属性 `by lazy`、包含外部局部捕获的内联 lambda 等）。
- **当前缓解措施**：
  1. Kotlin `object` 单例与 `trim`/`trimStart`/`trimEnd` 家族已在 `ktfixV1`/`ktfixV2` 独立 source set 中经过真实 `kotlinc` 产物编译与 `InitFixOracle` 运行期检验；
  2. 安全门默认安全优先（Fail-Closed）：识别不可变源字段失败或存在黑名单方法调用时坚决拒绝并记入台账。
- **根治与关闭条件**：构建覆盖 Kotlin 委托属性、协程状态机与内联高阶函数的全量真实 `kotlinc` 测试夹具套件。
- **关联设计规范**：[`docs/initfix/01-safety-gate.md`](initfix/01-safety-gate.md) §2.1。
- **关联架构决策**：无（属 Kotlin 编译器差异适配）。

### 风险 5：同名局部类止血门边界（唯一局部类内部嵌套匿名类位移）
- **风险描述与底层机制**：当前 `LocalClassGuard` 止血门依赖 `(owner, innerName)` 同名计数 >= 2 判定编号漂移并触发宿主整族熔断。
- **触发条件**：宿主方法内仅存在一个具名局部类（计数为 1，止血门不熔断），但该局部类内部包含多个嵌套匿名类；若该局部类自身在源码中发生位移，其内部嵌套匿名类因命名含字母被 `isAnonymousClassName` 排除而原名直通，可能导致局部类内部匿名类发生槽位篡夺。
- **当前缓解措施**：
  1. `LayoutGate` 能够阻断捕获字段或显式字段发生结构性变动的非法重定义；
  2. 日常编码中极少出现局部类内部深度嵌套匿名类且局部类频繁增删的形态。
- **根治与关闭条件**：落地路线图 §3.1“同名局部类完整拓扑对齐”，将局部类内部嵌套匿名类同样递归纳入拓扑对齐引擎。
- **关联设计规范**：[`docs/topology/07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §2.2。
- **关联架构决策**：无。

### 风险 6：构造器尾部插桩前的并发实例化缝隙
- **风险描述与底层机制**：在两阶段重定义与构造器尾部插桩落地前，系统依赖 JVMTI 堆遍历获取存活实例快照。
- **触发条件**：在快照获取完成至 JVM `redefineClasses` 真正生效的极窄间隙内，应用线程并发创建了新增字段的目标类实例。该实例未被快照捕获，补丁驱动不会对其执行初始化，实例新增字段保持为默认零值。
- **当前缓解措施**：
  1. 实例初始化若为 T0（零值等价），无任何语义差异；
  2. 游戏通常在主线程静止期触发热更新；
  3. `InstanceFailureBudget` 与错误监控可捕获异常访问。
- **根治与关闭条件**：落地路线图 §3.1“两阶段重定义与构造器尾部插桩”，在构造器末尾注入切片执行补丁。
- **关联设计规范**：[`docs/initfix/04-target-design.md`](initfix/04-target-design.md) §4。
- **关联架构决策**：无。

### 风险 7：InstanceTracker 字节码回退可靠性局限
- **风险描述与底层机制**：在无 JVMTI native 环境（如 Android 或纯 Java agent 回退）下，系统退化为基于弱引用表与构造器插桩的 `InstanceTracker`。
- **触发条件**：垃圾回收频繁触发导致弱引用被清理，或者实例通过非受控通道（如 `Unsafe.allocateInstance`、反序列化、未插桩的类加载器）创建。
- **当前缓解措施**：
  1. 桌面端开发环境默认启用 C++ 底座 `LibTool.getInstances`，Native JVMTI 为绝对优先通道；
  2. 回退模式下触发警告，提示开发者存在实例漏检风险。
- **根治与关闭条件**：优先保障 Native JVMTI 运行期环境；为纯 Java 回退通道补充强弱引用分层感知与反序列化挂钩。
- **关联设计规范**：[`docs/initfix/05-jvmti-heap.md`](initfix/05-jvmti-heap.md)。
- **关联架构决策**：无。

### 风险 8：JBR 字段布局探针实验局限
- **风险描述与底层机制**：JetBrains Runtime 增强类重定义底层允许新增字段，但其物理内存布局在 JIT 极端优化下的行为较为复杂。
- **触发条件**：JIT C2 编译器执行激进内联后，编译期代码可能基于旧对象偏移量（Offset）访问内存，或对新增的 `volatile`/`final` 字段存在可见性延迟；此外，父子类同名字段遮蔽可能引发偏移混淆。
- **当前缓解措施**：
  1. JBR 21 生产基准测试已证实常规新增实例字段与静态字段行为稳定；
  2. `LayoutGate` 严禁字段删除与类型变更，仅放行纯新增字段；
  3. 静态字段强制条件 CAS 写入，保证跨线程可见性。
- **根治与关闭条件**：落地路线图 §3.1“增强重定义模式四个未覆盖盲区验证与防护”，在真实 JBR 25 及多层 JIT 编译环境下开展压力探针验证。
- **关联设计规范**：[`docs/topology/07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md) §1。
- **关联架构决策**：[`docs/topology/08-decisions.md`](topology/08-decisions.md) 附录 A。
