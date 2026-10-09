# 梯队判定决策表与失败熔断机制

> **实现状态与审计**：本规范定义客观的架构与设计规格。当前实现状态、验收证据与详细审计参见 [docs/status.md](../status.md) 与 [AGENTS.md](../../AGENTS.md)。

---

## 1. 四级置信度梯队判定表

系统按四级置信度体系分层推进，实行**两趟制**匹配：第一趟仅采纳双向互为唯一候选的配对，消除遍历顺序依赖；第二趟按规则处理歧义候选：

| 梯队         | 判定条件                                                                                | 置信度      | 唯一候选时的动作                 | 多候选平局时的动作                                  |
|:-------------|:----------------------------------------------------------------------------------------|:------------|:---------------------------------|:----------------------------------------------------|
| **Tier 1**   | **Self Hash 相同 + 宿主方法相同 + 描述符相同**                                          | 极高 (0.99) | 立即采纳锁定                     | 按 `PAIR_COMPARATOR` 数值 minDiff 仲裁              |
| **Tier 1.5** | **内容相似度 (方法体哈希交集率 >= 70% + 字符串 Jaccard >= 0.8)**                        | 高 (0.80)   | 采纳并记录日志                   | 差值相等无法区分时，降级至 Tier 3（目标路线图规划） |
| **Tier 2**   | **Self Hash 同类跨宿主方法匹配 (同外层类)**                                             | 中高 (0.75) | 仅当全类唯一样本时采纳           | 存在多个候选时禁止采纳，降级至 Tier 3               |
| **Tier 3**   | **同宿主方法 + 同基类 + 同接口 + 同字段表 + 同声明方法表**                              | 中 (0.65)   | 采纳并记录日志                   | **拓扑相等过滤**（取代 minDiff 仲裁，见下文）       |
| **Tier 4**   | **同宿主方法 + 同基类 + 同接口 + 状态布局门检查**                                       | 低 (0.35)   | 仅当作用域内为双向唯一孤本时采纳 | **同作用域存在 >= 2 个同基类候选时，直接拒绝**      |
| *(已废除)*   | *历史 Tier 5（按物理类名盲配）已彻底移除，未匹配类一律判为新增类或孤儿类，严禁内存篡夺* | -           | -                                | -                                                   |

---

## 2. Tier 3 拓扑相等过滤（取代 minDiff 仲裁）

### 2.1 为什么废除 Tier 3 minDiff 仲裁
传统 minDiff 使用物理类名序号之差 `|n.orderIndex - o.orderIndex|` 作为 tie-breaker。在前插新匿名类且旧类方法体被修改的场景下，新插入的类序号恰好等于旧类序号（`diff = 0`），而真正的旧类位移后 `diff = 1`。minDiff 会系统性地偏向新插入的类，导致老实例的方法表被新类篡夺。

### 2.2 拓扑签名定义（`TopologySignature`）
为在 Tier 3 确定性区分多候选且不依赖物理序号，引入正交的粗粒度拓扑签名 5 元组：
1. `anonChildren`：直接匿名子类个数（按名字层级：直接父类为本类）；
2. `anonDescendants`：更深层的匿名后代总数；
3. `indySites`：`invokedynamic` 调用点数量（Lambda 创建点）；
4. `lambdaMethods`：合成 `lambda$` 方法个数；
5. `childKinds`：直接子类的类型多重集（父类 + 排序接口，类名屏蔽为 `#ANON#`）。

### 2.3 过滤与判定规则
1. 对剩余候选集进行拓扑签名比对；
2. 仅当新类与旧类拓扑签名**严格相等**且满足**双向唯一**时予以配对（计入 `topologyMatches`）；
3. 签名未知（子类字节码无法获取）视为不相等；
4. **无法唯一区分时的保守代价**：若候选在拓扑维度同样无信息（如夹具 M：两个候选与旧类均为空拓扑结构）或属于同构候选群（如夹具 L：2×2 候选对拓扑签名完全相同），系统**坚决不进行盲猜仲裁**，在非严格模式下退化为新增/孤儿类，在严格模式下直接熔断拒绝宿主组。这是消除静默错配所付出的保守代价。

---

## 3. 多轮基线与比较器契约

在允许 minDiff 仲裁的极高置信层（Tier 1）中，采用确定性比较器：

```java
private static final Comparator<CandidatePair> PAIR_COMPARATOR = (p1, p2) -> {
    int cmp = Integer.compare(p1.diff, p2.diff);
    if (cmp != 0) return cmp;
    cmp = Integer.compare(p1.n.orderIndex, p2.n.orderIndex);
    if (cmp != 0) return cmp;
    cmp = Integer.compare(p1.o.orderIndex, p2.o.orderIndex);
    if (cmp != 0) return cmp;
    cmp = p1.n.name.compareTo(p2.n.name);
    if (cmp != 0) return cmp;
    return p1.o.name.compareTo(p2.o.name);
};
```

---

## 4. 失败安全熔断机制 (Reject & Rollback)

### 4.1 熔断触发条件
当触发以下条件时，系统**拒绝本次结构性重定义**并执行回滚：
1. **低置信度同构平局**：在 Tier 4 阶段，同一个宿主作用域内存在 >= 2 个同基类匿名类且无法确定性区分；
2. **嵌套深度超限**：闭包嵌套深度 `depth > 4`（在 strict 模式下触发拒绝）；
3. **后置校验失败**：目标类名发生冲突（非单射），或子类目标前缀未能以父类目标前缀收敛（`validateRenameMap`）；
4. **数量硬上限与超时**：匿名类数量超过 128 或处理时间超过 2000ms（见 [06-runtime-and-perf.md](06-runtime-and-perf.md) §3）；
5. **对齐总开关关闭**：`-Dnipx.agent.anon_align=false` 时，含匿名类的宿主整体拒绝。

### 4.2 拒绝粒度与响应规范
* **拒绝粒度**：以**“宿主类 + 其下属全部匿名类”**为原子单元整体拒绝移出本批重定义，避免宿主新字节码引用未对齐编号或老实例被无关新类顶替。
* **回滚动作**：
  1. 抛出统一拒绝异常 `AlignmentRejectedException(hostSlash, reason)`；
  2. 调用 `tx.rollback(true)`，清除预登记的 pending 注入项；
  3. 控制台输出显式告警提示：  
     `[HOTSWAP-REJECT] Structural ambiguity detected in Foo. Redefine skipped safely. Please hot-swap again or restart.`
  4. 其它未受影响的宿主类继续正常推进热更新，杜绝单类故障导致整轮热更静默失效。
