# 08 架构决策记录与历史缺陷 (Decisions & Trade-offs)

这是匿名类拓扑对齐与安全门系统的"为什么"记录，不是规格。客观规格见 `docs/topology/01` ~ `07`。
修改对齐算法或试图"照原规划字面实现"前，**务必先读对应决策理由**。

---

<a id="d-anon-1"></a>
## D-ANON-1. 保留内容哈希而不剥离被引用子类引用

- **原规划条款**：`docs/topology/03-cascading-pipeline.md` §2（原 §3.1 排除项 4）曾规划"剥离子匿名类 NEW 指令 / 子类引用多重集，确保父匿名类哈希恒定"。
- **实际偏离决策**：**不剥离**，被引用子匿名类的内容哈希被折进引用方指纹。
- **冲突与复发缺陷（Save/Delete 静默对调）**：
  回归测试 `scratch/hstest/anon/{v1,v2}/testAnon/AnonCase.java` 中，`Foo$1` (Save) 与 `Foo$2` (Delete) 均实现为无参 `Runnable` 匿名类，宿主 lambda 体结构完全同构：
  ```java
  Runnable save   = () -> post(new Runnable() { @Override public void run() { doSave(); } });
  Runnable delete = () -> post(new Runnable() { @Override public void run() { doDelete(); } });
  ```
  若将方法体中的被引用匿名类特征剥离，两处 lambda 的指纹将逐字节相同。配对退化为"物理名字盲配"，导致老 `Foo$1` 存活实例的方法实现被重定义为 Delete，插入的 `$0` lambda 篡夺 `$1` 并劫持老实例方法表。这正是本系统致力于根治的严重静默错配。
- **折衷权衡与向上传导雪崩代价**：
  父匿名类若在方法体中新增 `NEW 子类` 指令序列，其 Self Hash 必然改变，导致父类从 Tier 1 精确匹配退化至 Tier 3 结构签名匹配。虽然在同宿主方法与同方法表的约束下仍能正确配对，同时彻底阻断同构匿名类错配。  
  **更广泛的代价在于日常代码修改**：若被引用的子匿名类的方法体发生任何改动（这是热更中最常见的场景），子类的 Self Hash 改变将沿着引用/调用链逐级向上传导，导致引用该子类的父匿名类乃至更外层的 Lambda 全部丧失 Tier 1 匹配能力，退化至 Tier 3。结合 Tier 3 在拓扑无信息（夹具 M）或 2×2 同构（夹具 L）场景下出于防错配只能保守拒绝，实际雪崩退化的代价比仅考虑“新增 NEW”更显著。这在客观上与架构不变量 INV-1（“自描述指纹、防雪崩”）构成了一定张力——指纹为了绝对防止 Save/Delete 静默对调，必须牺牲掉部分抗雪崩隔离性。
- **后续优化方向**：
  未来若需兼顾"父哈希对子类改动不敏感"，必须将"子类引用多重集"建模为独立的正交维度参与候选唯一性判定，而不能无条件剥离指令。

---

<a id="d-anon-2"></a>
## D-ANON-2. 宿主方法指纹不引入 `#ANON_COARSE` 粗粒度签名

- **原规划条款**：`docs/topology/03-cascading-pipeline.md` §2（原 §3.2）曾规划"在宿主方法对齐 Lambda 时，指令流中的匿名类创建仅嵌入粗粒度签名 `#ANON_COARSE(superName;interfaces;constructorParamTypes)#`"。
- **实际偏离决策**：**不实现且严禁按字面实现**。
- **理由**：
  同 D-ANON-1，在 `AnonCase` 等大量工业界场景中，多个同构无参 `Runnable` / 事件监听器具有完全相同的接口与构造器入参。`#ANON_COARSE` 对此类候选逐字节相同，使宿主指令流丧失唯一区分度，导致 Save/Delete 互换灾难复发。
- **现状机制与两种超限的严格区别**：
  由 `MethodFingerprinter.maskAnonymousClass` 接入：
  - 能够获取内容哈希时：`#ANON_<relId>_<childHash>#`；
  - **深度截断（`depth > 32`）或递归解析成环**：在方法指纹计算中局部降级为粗签名 `#ANON_<relId>#`，仅影响当前方法指令的指纹计算；
  - **数量闸门（总数 > 128）与耗时闸门（> 2000ms）**：由 `AnonClassAligner` 的性能安全硬闸门直接阻断，触发**宿主组原子整体拒绝**（回退至安全模式并告警），绝不会进入带缺陷的局部重命名。
- **局部退化残余风险**：
  当深度超限（`depth > 32`）导致退化为 `#ANON_<relId>#` 时，其区分度甚至弱于原规划的 `#ANON_COARSE`（丢失了父类和构造器参数），若在该深度下发生同构匿名类对调，理论上存在错配可能。当前依托 `depth > 32` 在实际 Java 字节码中极罕见作为安全防护。

---

<a id="d-anon-3"></a>
## D-ANON-3. 不实现 javac 枚举 Switch 映射表的独立排除（依赖作用域偶然不一致安全）

- **原规划条款**：`docs/topology/02-admission-and-reserved-names.md` §1（原 §2.1）曾规划将 javac 生成的 `$SwitchMap$` 类排除并原名直通。
- **实际偏离决策**：**不实现排除与原名直通**。
- **理由**：
  javac 生成的枚举 switch 映射类名为 `宿主$N` 纯数字形式，且修饰符为合成类。若将其从对齐系统排除并走"原名直通"，一旦在其前插匿名类，`$SwitchMap$` 的物理编号将位移，原名直通会直接打开核心不变量（INV-Remap）禁止的槽位篡夺漏洞。
- **实测现状与偶然安全性（诚实记录）**：
  在 `SwitchMapAlignTest`（javac 21）中实测四种形态（前插匿名类、case 集合变化、字段匿名类删除+新增 switch、删除 switch）均未发生槽位篡夺。但深入分析表明：形态 C（删字段匿名类、加 switch）之所以未发生错误配对，是因为**两侧作用域偶然不一致**：旧匿名类的宿主方法被回退解析为 `<init>`，而 SwitchMap 无 `NEW` 实例化点因此 `outerMethod == null`，Tier 4 谓词（要求 `outerMethod` 相等）自然不满足。
- **残余风险**：
  若出现"旧匿名类宿主方法同样无法反向追溯（两端均退化为 null）"的形态，Tier 4 可能产生唯一配对，且 `LayoutGate` 对 SwitchMap 不设防（它新增的是**静态**合成字段）。目前作为已知残余风险记录。

---

<a id="d-anon-4"></a>
## D-ANON-4. 匿名类合成捕获字段计入哈希而非剥离

- **原规划条款**：`docs/topology/03-cascading-pipeline.md` §2（原 §3.1 特征第 2 条）曾草拟"过滤 ACC_SYNTHETIC 捕获字段，使外部捕获位移不破坏内容哈希"。
- **实际偏离决策**：**合成捕获字段（`val$*`, `this$0`）必须计入哈希**，由 `LayoutGate` 进行防御。
- **理由与 JBR 真机事实**：
  JBR-21 DCEVM 实验（见附录 A）证实：存活实例的内存布局在实例化时已锁定。若代码改动引起捕获字段类型或数量变动，重定义后存活实例读取新字段只能得到默认零值。若在对齐阶段从哈希中剥离这些字段，将导致不同捕获字段的类在 Tier 1/2 发生误匹配并在原地重定义，造成存活实例状态损坏。
- **架构分工**：
  合成捕获字段参与字段签名；Tier 4 松散匹配前由 `LayoutGate.check` 进行实例状态兼容性校验，若存在存活实例且布局不兼容则严厉拒绝配对（降级为孤儿保留）。

---

<a id="d-anon-5"></a>
## D-ANON-5. `access$` 编译器访问器保名不改名

- **原规划条款**：`docs/topology/03-cascading-pipeline.md` §2（原 §3.1 排除项 3）曾规划"将 `access$000` 统一归一化为 `#ACCESS_METHOD#`"。
- **实际偏离决策**：**保名不改名**，`MethodFingerprinter.isSelfSynthetic` 显式排除 `access$`。
- **理由**：
  `access$` 是编译器生成的跨类访问桩。若改写其名称，重命名只会更新本类内部指令，外部调用方（如宿主或兄弟内部类）的代码不会联动改写，导致运行期抛出 `NoSuchMethodError`（参考 `LambdaAligner` 历史踩坑）。既然运行期名字不能动，`access$NNN` 便属于**稳定区分信息**，保留编号有助于区分调用不同 accessor 的两个 Lambda，抹除后反而容易撞哈希。
- **残余风险**：
  若重编译导致 javac 重新编号 `access$`，可能引发编号语义漂移。当前权衡接受该风险，换取不破坏跨类调用点。

---

<a id="d-anon-6"></a>
## D-ANON-6. 嵌套匿名类构造器与成员描述符定向屏蔽与后置校验

- **缺陷**：
  嵌套匿名类的构造器描述符直接嵌有父匿名类的类名（如 `Outer$1$1.<init>(LOuter$1;)V`）。当父类因前插位移成 `Outer$2` 时，子类构造器描述符改变，导致即使子类完全同构，其内容哈希也必定改变，使得 depth $\ge 2$ 的类全线丧失 Tier 1 匹配能力并退化至 Tier 4。
- **决定**：
  在生成方法签名与指纹时，对描述符调用 `MethodFingerprinter.maskDescriptor`，将 `L<宿主>$<纯数字>;` 规范化为统一占位符。范围涵盖字段描述符、方法描述符与构造器描述符。
- **定向性与后置校验，以及与准入规则的边界差异**：
  - `maskDescriptor` 定向屏蔽：仅检查**最后一个** `$` 之后的字符是否为纯数字（`isUnstableNestedSuffix`）。例如 `LOuter$1;` 和 `LOuter$Inner$1;` 均会被屏蔽；不含 `$` 或后缀非纯数字的描述符（如具名内部类 `LOuter$Inner;`）原样保留，防止候选集无端扩大。
  - `AnonClassAligner.isAnonymousClassName` 准入规则：检查从宿主类名后**第一个** `$` 开始，整个后缀是否**全由数字和 `$` 组成**。
  - 边界差异影响：具名局部类内部嵌套的匿名类（如 `Foo$1Helper$1`）因包含字母 `Helper`，在 `AnonClassAligner` 入口阶段就被直接排除准入（返回 false），不进入拓扑对齐而走原名直通。即使 `maskDescriptor` 能识别其末尾纯数字，该类也不会进入重命名流程（造成同名局部类内部匿名类的原名直通盲区）。
  - 对齐完成后，由 `verifyFieldLayoutAfterRename` 执行后置校验，确保重命名后不会引发字段描述符碰撞。

---

<a id="d-anon-7"></a>
## D-ANON-7. 继承体系变更一律熔断拒绝（提示重启）

- **原规划条款**：`docs/topology/06-runtime-and-perf.md` §1（原 §6.1-2）曾假定底层 JVM 支持时允许修改继承体系。
- **实际偏离决策**：**一律熔断拒绝并提示重启**。`HotSwapAgent.processChanges` 对父类（`super_class`）或接口集合（`interfaces`）发生变动的类直接标记失败并发出重编译/重启警告，绝不尝试拓扑映射或强制 Redefine。
- **理由**：
  1. DCEVM / JBR 虽然在特定版本实验性允许修改继承树，但在 JVM 规范与多数 LTS 实现中，变更父类会彻底破坏 `vtable`/`itable` 虚方法分发布局、内联缓存（IC）以及 JIT 编译假设，具有极高崩溃风险；
  2. 匿名类对齐的核心目标是解决相同继承体系下的物理编号漂移。若继承体系本身被篡改，已超出了局部拓扑对齐的安全语义范畴，保守阻断是保证 JVM 稳定性的唯一防线。
- **关联代码**：`HotSwapAgent.processChanges`，`ClassDiff.isHierarchyChanged`。

---

## 附录 A. JBR 21 增强类重定义模式下 8 组字段布局实测数据

探针来源：`scratch/layoutprobe/`（每个测试用例运行在独立 JVM 环境下）。  
测试环境：JBR 21 (21.0.9+1) + DCEVM (`-XX:+AllowEnhancedClassRedefinition`)。方法体逐字节相同，仅变更字段表。

| 变更类型 | 增强模式重定义结果 | 存活旧实例上的值表现 | 方法调用是否正常 | 非增强模式对照组 (`-XX:-AllowEnhanced...`) |
|:---|:---:|:---|:---:|:---:|
| 增加 `int` 字段 | 接受 | 旧字段值保留，**新字段为默认值 `0`** | 正常 | 拒绝 (UnsupportedOperationException: attempted to change the schema) |
| 增加 `String` 字段 | 接受 | 旧字段值保留，**新字段为默认值 `null`** | 正常 | 拒绝 (UnsupportedOperationException) |
| 删除字段 | 接受 | 其它字段保留，被删除字段消失 | 正常 | 拒绝 (UnsupportedOperationException) |
| `int` → `long` | 接受 | **旧值被丢弃，新字段为默认值 `0`** | 正常 | 拒绝 (UnsupportedOperationException) |
| `int` → `String` | 接受 | **旧值被丢弃，新字段为默认值 `null`** | 正常 | 拒绝 (UnsupportedOperationException) |
| `Object`(7) → `String` | 接受 | **旧值被丢弃，新字段为默认值 `null`** | 正常 | 拒绝 (UnsupportedOperationException) |
| 实例字段 → 静态字段 | 接受 | **旧值被丢弃，新值置默认值 `0`** | 正常 | 拒绝 (UnsupportedOperationException) |
| 静态字段 → 实例字段 | 接受 | **旧值被丢弃，新值置默认值 `0`** | 正常 | 拒绝 (UnsupportedOperationException) |

### 核心结论
1. **JVM 不会主动拦截类型与静态性变更**：增强模式下全部接受，不会抛出 LinkageError，但会**静默丢弃旧值并写入零值**。
2. **纯新增字段合法**：旧值保留，新字段取默认值，存活实例可安全由 InitFix 执行补丁初始化。
3. **改类型 / 删字段 / 改静态性具有毁灭性**：存活实例状态直接损坏（实测删字段虽保留其他字段，但旧代码调用或重定义后实例读取均会导致不可预期行为；类型变更与静态性互换则旧值被直接丢弃并写入默认零值）。因此 `LayoutGate` 对删字段、改类型、改静态性统一实施严格阻断（默认 `reject`）；纯新增字段则放行并交由 InitFix 补丁初始化。
4. **静态字段连零实例也必须拒绝**：静态字段为类级别共享状态，只要类被加载就可能已有有效状态，因此静态字段变更（改类型/改静态性/删除）不得因“无存活堆实例”而放行。
5. **未覆盖盲区（与路线图关联）**：① JIT 编译后内联代码对新布局的观察；② `volatile`/`final` 复杂修饰符；③ 继承体系跨层遮蔽；④ JBR 25 重复验证。对应 `docs/status.md` §3 路线图规划条目。

---

## 附录 B. javac 内部类命名规律与 `this$N` 事实

1. **局部类命名**：`宿主$<N><简单名>`。编号 $N$ 按 `(宿主类, 简单名)` 二元组递增分配，各简单名之间编号独立。若同一宿主的不同方法中存在同名局部类，前插新增局部类将导致后续同名局部类编号整体平移。
2. **局部类内部的嵌套类**：命名如 `Foo$1Helper$1`。由于其第一个 `$` 后的后缀包含字母 `Helper`，被 `AnonClassAligner.isAnonymousClassName` 判定为非匿名类，因而**不参与拓扑对齐而原名直通**。若外层局部类发生前插编号漂移，该内部嵌套类将面临槽位篡夺风险（见已知风险清单 §5）。
3. **嵌套匿名类内部的 `this$N`**：外层类的捕获字段根据嵌套层级递增命名为 `this$0`、`this$1` 等，修饰符带有 `ACC_SYNTHETIC`。
