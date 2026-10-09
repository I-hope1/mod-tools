# HotSwap 模块实现状态总表 (Implementation Status & Audit)

> **状态信息管理规则（强制）**：  
> 本文件为 HotSwap 全模块实现状态与验收审计的**唯一详细来源**（高层高价值概括见 `AGENTS.md` §5）。  
> **严禁在 Javadoc、代码注释以及 `docs/` 设计规范文档中重新混入实现状态信息**（如 `✅`、`[已实现]` 等易腐标记）。  
> 各设计文档与 Javadoc 仅负责定义客观的架构规格、设计原理与交互契约；实现状态若有变更，统一在此登记。

---

## 1. 匿名类拓扑对齐与安全门 (Anonymous Class Alignment & Safety Gates)

设计规格文档：[`docs/topology/`](topology/) 系列文档

| 模块 / 机制 | 状态 | 落地位置 / 验收证据 | 说明 |
|:--|:-:|:--|:--|
| javac 8/11/17/21 编译支持 | ✅ | `AnonClassReproTest` | `hstestJunit` 经多 JDK 现编夹具验证通过（javac 25 亦已额外覆盖验证） |
| ECJ 编译器支持 | ⬜ | 暂无 ECJ 真实夹具 | 见 `docs/topology/07-layout-gate-and-risks.md` §4 |
| 类身份与实例状态保真不变量 | ✅ | `AnonClassAligner` | 彻底移除历史 Tier 5（按物理名盲配）；未匹配旧类保留为孤儿，未匹配新类分配安全新名 |
| `renameMap` 与字节码改写 | ✅ | `AnonClassAligner.remapClass` | 使用 ASM `ClassWriter(0)` + `SimpleRemapper`，不重算栈帧避免死锁 |
| 全局保留名域与挂起避让 | ✅ | `takenTargetNames` / `reserved` | 覆盖第 1/2/4 类保留名（`pendingAlignedClasses` 快照接入）；未加载挂起类编号抬高为已知无害代价 |
| Tier 1: 内容哈希 + 宿主方法精确匹配 | ✅ | `AnonClassHasher` + `AnonClassAligner` | 双方哈希完全一致且同宿主方法，置信度最高 |
| Tier 1.5: 内容相似度 | ⬜ | 路线图规划 | 规划计算指令哈希交集率与字符串 Jaccard；用于根治拓扑无信息同构候选平局 |
| Tier 2: 全局唯一内容哈希匹配 | ✅ | `AnonClassAligner` | 仅全类唯一孤本采纳，严禁跨方法 minDiff |
| Tier 3: 结构签名 + 拓扑相等过滤 | ✅ | `AnonClassAligner.topologyEqual` | 在双向唯一之后引入拓扑签名过滤，彻底消除前插+改体场景下 minDiff 带来的错配 |
| Tier 4: 松散结构 + 状态布局门包装 | ✅ | `AnonClassAligner` + `LayoutGate` | 同宿主方法与基类/接口；禁止 minDiff；包装布局门防存活实例零值污染 |
| Tier 5: 物理类名盲配 | ❌ 已废除 | `AnonClassAligner` | 为守卫核心不变量已彻底移除，前 4 层未匹配直接判为新类/孤儿 |
| `access$` 访问器保名不改名 | ✅ | `MethodFingerprinter.isSelfSynthetic` | 排除 `access$` 归一化以维持跨类调用稳定并防止 Lambda 撞哈希 |
| 嵌套匿名类构造器描述符定向屏蔽 | ✅ | `MethodFingerprinter.maskDescriptor` | 定向屏蔽外层父名序号，使嵌套匿名类在父类位移时 100% 恢复 Tier 1 精确匹配 |
| INV-1 自描述指纹与 INV-2 禁止互相递归 | ✅ | `AnonClassReproTest` Scenario 25 | 常量池扫描与内容变异架构守卫，防止雪崩污染 |
| 宿主级原子拒绝机制 | ✅ | `AlignmentRejectedException` / `HotSwapAgent.rejectHostGroup` | strict 歧义、超限、超时或关闭时，宿主与派生类整组移出本轮重定义并输出 `[HOTSWAP-REJECT]` |
| 多轮基线 `sourceOrder` 稳定排序 | ⬜ | 未实现 | 当前使用物理名序号仲裁；因 Tier 3 拓扑过滤落地，触发面已极度收窄 |
| 性能安全闸门 | ✅ | `MAX_ANON_PER_HOST = 128`, `ALIGN_TIMEOUT_MS = 2000` | 数量超限或超时直接触发宿主组原子拒绝 |
| 特性控制开关 | ✅ | `HotSwapAgent.ANON_ALIGN`, `ANON_STRICT`, `ANON_DEBUG` | 支持 `nipx.agent.*` 首选名及 `nipx.anonAlign.*` 兼容别名 |
| 事务生命周期与乐观预登记 | ✅ | `AlignmentTransaction.preRegister` | 重定义前预先登记 pending 注入项，消除类加载重入读取磁盘错位产物竞态 |
| 匿名类合成捕获字段布局门 | ✅ | `LayoutGate.check` / `ANON_LAYOUT_GATE` | 阻断捕获字段增/改导致存活实例读取零值；默认模式 `reject` |
| 具名类与用户显式字段布局门 | ✅ | `HotSwapAgent.applyRedefineLayoutGate` / `LAYOUT_GATE` | 阻断用户字段删除、类型变更、静态性变更；默认模式 `reject` |
| 同名局部类编号漂移止血门 | ✅ | `LocalClassGuard` / `LOCAL_CLASS_GUARD` | 识别同名局部类计数 ≥ 2 并将宿主整族移出本轮；默认模式 `reject` |
| 同名局部类完整拓扑对齐 | ⬜ | 未实现 | 规划中；当前由止血门安全拦截 |
| 蜕变测试准则 (Metamorphic Suite) | ✅ | `AnonClassReproTest` Scenario 19/20/21 | 验证标记置换不变性、幂等性、尾部追加不变性与故障注入零静默错配 |

---

## 2. 存量初始化修复器 (InitFix)

设计规格文档：[`docs/initfix/`](initfix/) 系列文档

| 机制 / 条目 | 状态 | 规范章节 | 说明 |
|:--|:-:|:--|:--|
| 五条核心不变量 | ✅ | `AGENTS.md` §2 | 未初始化守卫、PENDING 弱键 (5min TTL)、PatchReport 解耦、LinkageError 窄熔断、失败立即注销 |
| 离线元数据契约 (`ClassHierarchyOracle`) | ✅ | `docs/initfix/01-safety-gate.md` §5 | 全程无类加载；流式字节码扫描；`HierarchyTreeOracle` |
| 待补字段台账 (`FieldLedger`) | ✅ | `docs/initfix/02-closure-and-ledger.md` §2 | 覆盖六种记账情形（分析期被拒、运行期异常、依赖跳过、Redefine 失败、规划期异常、TTL 超时清扫） |
| T0 零值等价判决 | ✅ | `docs/initfix/01-safety-gate.md` §1 | 按位比较排除 `-0.0f`/`NaN`；`NOTHING_TO_PATCH` 零开销放行不告警 |
| T1 编译期常量 | 🔶 | `docs/initfix/01-safety-gate.md` §1 | 静态 `ConstantValue` 走专用通道；字面量切片仍走常规直线提取 |
| T2 纯计算切片 | 🔶 | `docs/initfix/01-safety-gate.md` §1 | 经 `AliasInterpreter` 逆向切片并通过效应安全门 |
| T3 复杂/不安全拒绝 | ✅ | `docs/initfix/01-safety-gate.md` §1 | 分支、try/catch、环境依赖、可变源字段，统一在 `transform` 发 warn |
| T4 显式逃生口 `@HotswapInit` | ⬜ | `docs/initfix/01-safety-gate.md` §1 | 路线图规划（伴生类直接静态转发） |
| 最小效应检查（P0） | 🔶 | `docs/initfix/01-safety-gate.md` §2 | 拦截 bit 3/6 及 bit 4/5 子集；白名单防误杀；Kotlin `trim` 家族 `Object.toString` 窄例外 |
| 8 位完整效应掩码 | ⬜ | `docs/initfix/01-safety-gate.md` §2.2 | 目标设计（PURE, READS_FINAL, ALLOC_PURE 等） |
| 参数回溯不可变证明 | ✅ | `docs/initfix/01-safety-gate.md` §3 | 条件 A（final/val）与条件 B（private + `NestView` 全 Nest 单写证明） |
| 多根构造器共识 | ✅ | `docs/initfix/01-safety-gate.md` §4 | 要求所有根构造器覆盖完整且参数替换后指令指纹 100% 一致 |
| 依赖闭包与 Tarjan SCC 成环检测 | ✅ | `docs/initfix/02-closure-and-ledger.md` §1 | 成环检测并入闭包不动点计算，只拒绝环成员；出口后置 `depInstance ∪ depStatic` 闭合校验兜底 |
| 失败策略矩阵 | 🔶 | `docs/initfix/02-closure-and-ledger.md` §3 | 已支持单字段失败跳过、依赖跳过、`LinkageError` 熔断；“放弃提交阶段 C”依赖两阶段协议 |
| 伴生补丁类装配 | ✅ | `docs/initfix/03-runtime-driver.md` §1 | 每字段生成独立静态直线方法；宿主 hidden nestmate 隔离异常与加载死锁 |
| 逐实例补丁驱动与失败配额 | ✅ | `docs/initfix/03-runtime-driver.md` §2 | 依赖失败按实例隔离；单字段每轮实例失败配额封顶 8 次（`MAX_INSTANCE_FAILURES_PER_FIELD`） |
| 写入协议 (`HotswapBridge`) | ✅ | `docs/initfix/03-runtime-driver.md` §3 | 默认条件 CAS；float/double raw bits CAS；跳过计数汇总汇报；`KIND_FORCE` 强制写 |
| 存量覆写扩展 (`@HotswapReinit`) | ✅ | `docs/initfix/03-runtime-driver.md` §4 | 豁免 T0 与后续加工门；不豁免切片安全门；支持 `CONDITIONAL` 与 `OVERWRITE` |
| 合成标记字段过滤 | ✅ | `docs/initfix/03-runtime-driver.md` §5 | `ClassDiffUtil` 对称过滤 `ACC_SYNTHETIC` 与 `$nipx$` 前缀字段 |
| JVMTI 多态堆实例检索 | ✅ | `docs/initfix/05-jvmti-heap.md` | `LibTool.getInstances` C++ 底座；Tag 隔离与全局互斥；`InstanceTracker` 字节码回退 |

---

## 3. 目标设计路线图 (Roadmap & Target Design)

设计规格文档：[`docs/initfix/04-target-design.md`](initfix/04-target-design.md)

| 规划条目 | 状态 | 规范章节 | 关联问题 / 目标价值 |
|:--|:-:|:--|:--|
| 两阶段 Schema-First 重定义 | ⬜ | `docs/initfix/04-target-design.md` §1 | 消除热更新瞬间新方法体读到未就绪字段的读穿异常，以及 `static final` 字段在 redefine 与补丁之间的 JIT 常量折叠 |
| `PatchPlan` 基线指纹校验 | ⬜ | `docs/initfix/04-target-design.md` §2 | 校验 Target 当前内存字节码哈希与构建期基线一致性，防止漂移热更 |
| Analyzer 独立库化 | ⬜ | `docs/initfix/04-target-design.md` §3 | 分析器作为纯函数独立，支持在 Gradle 构建端或 PC 端离线分析 |
| 构造器尾部插桩 | ⬜ | `docs/initfix/04-target-design.md` §4 | 在阶段 A 构造器尾部注入切片，物理消除堆遍历快照到重定义生效之间的并发对象初始化缝隙 |
| 跨类批次拓扑排序 | ⬜ | `docs/initfix/04-target-design.md` §5 | 解决类 X 新增字段依赖类 Y 新增字段时的跨类多事务批次协调（当前仅支持类内拓扑排序） |
