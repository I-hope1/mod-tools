# 匿名内部类树状拓扑与混合闭包对齐技术规范

> **规范重构与拆分说明**：  
> 本单体技术规范已完成模块化拆分，客观架构规格已拆分至 [`docs/topology/`](topology/) 细分子规范。  
> 实现状态、验收证据与详细审计已归入 [`docs/status.md`](status.md)（高层规则见 [`AGENTS.md`](../AGENTS.md)）。  
> 请勿在 Javadoc 或各子规范文档中重新混入易腐的实现状态标记。

---

## 细分子规范索引地图 (Specification Map)

| 领域 / 章节       | 拆分后子文档                                                                                      | 核心内容                                                                                                                  |
|:------------------|:--------------------------------------------------------------------------------------------------|:--------------------------------------------------------------------------------------------------------------------------|
| §1 核心契约       | [`docs/topology/01-invariants-and-remapping.md`](topology/01-invariants-and-remapping.md)         | 系统目标、类身份与实例状态保真核心不变量、改写范围契约、栈图处理                                                          |
| §2 准入与保留名   | [`docs/topology/02-admission-and-reserved-names.md`](topology/02-admission-and-reserved-names.md) | 属性优先准入判定（Attribute-First Admission）、SwitchMap 类分析、全局保留名域与挂起避让                                   |
| §3 协同推进流水线 | [`docs/topology/03-cascading-pipeline.md`](topology/03-cascading-pipeline.md)                     | 层级交错流水线、Self Hash 提取与排除项、父名前缀派生、调用链追溯防护、非方法上下文归约、INV-1/INV-2 架构不变量            |
| §4 置信梯队与熔断 | [`docs/topology/04-tiers-and-rejection.md`](topology/04-tiers-and-rejection.md)                   | Tier 1~4 置信度梯队判定表、Tier 3 拓扑相等过滤（取代 minDiff）、确定性比较器、宿主级原子拒绝（Reject & Rollback）         |
| §5 推演案例       | [`docs/topology/05-walkthrough.md`](topology/05-walkthrough.md)                                   | 场景 1（外层插入新类导致整体位移）与场景 2（父匿名类新增子匿名类）机械推演                                                |
| §6 运行时与性能   | [`docs/topology/06-runtime-and-perf.md`](topology/06-runtime-and-perf.md)                         | JBR-21/DCEVM 增强能力实测、事务生命周期与乐观预登记、数量硬上限与超时安全闸门、特性控制开关                               |
| §7 布局门与风险   | [`docs/topology/07-layout-gate-and-risks.md`](topology/07-layout-gate-and-risks.md)               | 实例状态布局安全门（`LayoutGate`）、同名局部类编号漂移与止血门（`LocalClassGuard`）、蜕变测试套件准则、ECJ 编译器差异跟踪 |
| §8 架构决策记录   | [`docs/topology/08-decisions.md`](topology/08-decisions.md)                                       | D-ANON 系列架构决策与偏离说明（保内容哈希、不引入 `#ANON_COARSE`、SwitchMap 偶然安全、捕获字段计入指纹等）、JBR 8 组真机数据 |

---

## 实现状态与审计 (Status & Audit)

详细逐项状态表、落地位置与验收证据参见 [`docs/status.md`](status.md) §1。  
高层规则与核心不变量参见 [`AGENTS.md`](../AGENTS.md)。
