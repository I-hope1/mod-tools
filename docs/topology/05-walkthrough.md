# 端到端推演案例 (End-to-End Walkthrough)

> **实现状态与审计**：本规范定义客观的架构与设计规格。当前实现状态、验收证据与详细审计参见 [docs/status.md](../status.md)
> 与 [AGENTS.md](../../AGENTS.md)。

---

## 场景 1：父匿名类结构未变，外层插入新类导致整体位移

### 1. 结构上下文

* **老版本 (V1)**：
    * `Foo$1`: 任务 Save，包含内层 `Foo$1$1`（Worker A）
    * `Foo$2`: 任务 Delete
* **新版本 (V2 编译产物)**：
    * `Foo$1`: **[新增]** 任务 Audit
    * `Foo$2`: 任务 Save（由原 `$1` 整体位移而来），包含内层 `Foo$2$1`（Worker A）
    * `Foo$3`: 任务 Delete（由原 `$2` 整体位移而来）

### 2. 执行推演

1. **Phase 0**：提取 Self Hash：`H(Audit)=0xA1`, `H(Save)=0xB2`, `H(Delete)=0xC3`, `H(WorkerA)=0xD4`。
2. **Level 1**：
    * `Foo$2` (H=0xB2) 与 `Foo$1` (H=0xB2) Tier 1 互为唯一，锁定：`Foo$2 -> Foo$1`。
    * `Foo$3` (H=0xC3) 与 `Foo$2` (H=0xC3) Tier 1 互为唯一，锁定：`Foo$3 -> Foo$2`。
    * `Foo$1` (Audit) 未匹配，分配未占用编号：`Foo$1 -> Foo$3`。
3. **Level 2**：
    * 限制在新 `Foo$2` 对应的目标父名 `Foo$1` 作用域内。
    * 新 `Foo$2$1` (H=0xD4) 与老 `Foo$1$1` (H=0xD4) Tier 1 互为唯一，锁定：`Foo$2$1 -> Foo$1$1`。
4. **Phase 4**：重定义列表为 `[Foo, Foo$1, Foo$2, Foo$1$1]`。老实例无感自愈，新 `Foo$3` 等待首次加载拦截。

---

## 场景 2：父匿名类新增子匿名类 (方法体产生指令变动)

### 1. 结构上下文

* **老版本 (V1)**：`Foo$1`: 任务 Save，仅创建 Worker A（`Foo$1$1`）。
* **新版本 (V2)**：`Foo$1`: 任务 Save，内部追加创建 Worker B（`Foo$1$2`）。

### 2. 执行推演

1. **Self Hash 提取**：由于父匿名类方法体内新增了 `NEW Foo$1$2` 指令，父类 Self Hash 发生改变。
2. **Level 1 匹配**：
    * Tier 1 内容哈希精确匹配失效；
    * 平滑进入 **Tier 3 结构签名**（同宿主方法 + 同基类接口 + 同字段表 + 同声明方法表），达成互为唯一配对，锁定
      `Foo$1 -> Foo$1`。
3. **Level 2 匹配**：
    * 新 `Foo$1$1` 匹配旧 `Foo$1$1`，锁定 `Foo$1$1 -> Foo$1$1`。
    * 新 `Foo$1$2` 为新增类，前缀取映射后的父名 `Foo$1`，分配目标类名 `Foo$1$2` 并预登记到 pending。
4. **Phase 4**：`Foo` 与 `Foo$1` 原子提交重定义，`Foo$1$2` 待初次加载时拦截生效。
