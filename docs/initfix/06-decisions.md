# 06 设计决策与历史缺陷

这是"为什么"的记录，不是规则。规则见 `AGENTS.md` 和其它主题文档。
想推翻某条决策前，先读对应条目的理由。

## D1. 台账记账覆盖六种情形

**缺陷**：`buildPatch` 规划期抛异常时，`InitFix.transform` 的 `catch` 分支里 Redefine 仍会推进，如果不记账，字段永久遗忘。TTL 清扫丢弃待补补丁同理：这些字段在 `transform` 时已因 `ACCEPTED` 出账，丢弃时不写回，下一轮既不在 Diff 也不在台账。

**决定**：两种情形都记账；已入账条目不被笼统原因降级覆盖；台账写入放在 `PENDING` 锁之外（避免两把锁的获取顺序随调用路径变化，形成死锁面）。

## D2. 成环检测并入不动点 + 出口后置闭合校验

**缺陷（静默误补）**：成环检测原先在闭包收敛**之后**才跑。环成员被拒会清空接受集合，但依赖它们的字段可能早已通过 `depReason`，保持 `ACCEPTED`，补丁照常执行，读到未补的默认值并写入过期值。

漏网是跨组的：实例字段读静态环成员时，它与被清空的集合不属于同一组，"整组拒绝"覆盖不到它。同组依赖方（静态读静态）反而被整组拒绝顺带覆盖，这是该缺陷长期隐藏的原因。实测复现：修复前该实例字段为 `ACCEPTED`、被写成 `2`、不在台账里。

**决定**
- 成环检测放进闭包不动点内部反复执行。
- 出口加依赖闭合校验作为主防线，不依赖各阶段顺序。
- 校验基准必须是 `depInstance ∪ depStatic`。曾因只传 `depInstance`，校验对"实例字段读静态字段"完全失明，被"关闭闭包传播"的安全探针实测暴露。
- 拒绝范围收窄为只拒环成员（Tarjan SCC）。整组拒绝虽安全，但会因一个小环丢掉整个补丁集。

**验证**：单独关闭不动点里的成环检测，仅靠出口校验仍能拦住全部用例；人为关闭闭包传播时，同样如此。

## D3. 单字段链接失败不熔断

**问题**：`HotswapBridge.bootstrap` 把任何 `Throwable` 包成 `BootstrapMethodError`（`LinkageError` 子类）。一律熔断会让单个字段的解析失败中止所有字段、所有实例。

**决定**：看 `getCause()`。`BootstrapMethodError` 的 cause 实测两种情形都可能出现（我们自己的 `NoSuchFieldException`，或真正的 `NoSuchMethodError`），所以只有"cause 不是 `LinkageError`"才隔离。判定取窄，因为误把系统性故障当可隔离的代价更高。

## D4. float/double 条件写用 raw bits

**问题**：按名字探测 `compareAndSetFloat/Double` 会随 JDK 分叉。`jdk.internal.misc.Unsafe` 有（实测 25.0.2），JDK 8 的 `sun.misc.Unsafe` 没有且无内部 Unsafe（实测 1.8.0_332）。于是同一补丁在 JDK 8 退化成 `putFloatVolatile` 无条件写，会静默覆盖其他线程已写入的值，与本类"保守方向"相反。

**决定**：统一用 `compareAndSetInt/Long` + raw bits。`compareAndSwapInt/Long` 在所有目标 JDK 都存在，一条路径通吃。

**测试局限**：oracle 跑在 JDK 25，新断言无法区分新旧实现（旧实现同样通过）。验证方式是在真实 JDK 8 上直接跑 raw-bits 句柄：`-0.0f`/`9.0d` 被保留，`0.0f`/`0.0d` 被写入；旧回退实现会把四个值全部覆盖。

## D5. 条件写跳过必须被报告

**问题**：此前 CAS 的 boolean 结果被 `asType(changeReturnType(void.class))` 丢弃，"字段已被别人写过所以补丁没生效"与"补丁生效"在驱动侧无法区分，跳过是静默的。

**决定**：保留 boolean 结果并计数。跳过不算失败（设计内的保守行为），所以不进台账、不参与失败告警；首轮 `warn`，台账重试轮 `info`。

## D6. 拒绝告警出口收敛到一处

**问题**：闭包期的拒绝（后续加工、依赖、成环、根构造器覆盖不全、指纹不一致）历史上只调 `log(...)`，而 `log` 受 `DEBUG` 门控（`HotSwapAgent.DefaultLogger.log`），默认环境下什么都不打印。

**决定**：`transform` 在 `buildPatch` 返回后统一遍历 `PatchReport`，对全部 `REJECTED` 按原因聚合发 `warn`。静默的保守与静默的错误同样难排查。

## D7. 失败跳过按实例隔离，并加失败配额

**问题**：全局 `failedFields` 会让一个实例的失败误伤所有实例；按实例隔离后，确定性失败又会让每个实例各失败一遍，日志与耗时放大 N 倍。

**决定**：局部集合只继承静态侧失败；全局集合仅用于计数与记账；`InstanceFailureBudget` 每字段每轮 8 次配额，不跨轮累积。

## D8. `@HotswapReinit` 不豁免切片安全门

覆写只改变"要不要写"，改变不了"表达式能否被直线提取、读到的值是否正确"。因此它豁免 T0 与后续加工检查，但**不是**"信任通道"。Kotlin 分支类表达式的正解是 `@HotswapInit`（T4，未实现）。

## D9. 为什么三张内部表不用 `java.lang.ClassValue`

涉及 `PENDING`、`REPORTS`、`LEDGER`。按重要性：

1. **`PENDING` 的 TTL 清扫需要全表遍历，`ClassValue` 没有迭代接口。** 5 分钟清扫的目的是释放 `PendingPatch` 里挂的 `List<WeakReference<Object>>`（百万实例 = 百万弱引用）。`ClassValue` 条目随 `Class` 存活，没有清扫就没有释放；要保留就得再维护一个弱引用集合专供清扫，等于把 map 加回来一半。
2. **部署约束（决定性）**：模块刻意保留 Android/ART 路径（`Reflect.isAndroid`、`defineAnonymousClass` 回退、`desugar_jdk_libs`、`Android_dalvik`），而 `ClassValue` 在 ART 直到 API 34 才有，且不在 core library desugaring 覆盖范围。把静态字段类型写成 `ClassValue`，旧 ART 上链接 `InitFix` 会直接 `NoClassDefFoundError`。这也是 `Reflect`/`HotswapBridge` 全篇用反射探测新 API 的原因。
3. **语义不匹配**：`ClassValue` 只有 `computeValue`/`get`/`remove`，没有 `put`；`remove` 返回 `void`，而两处用 `if (PENDING.remove(clazz) == null) return;` 做"取走并判空"；`get()` 缺失时会创建，破坏 `ledgerSnapshot`/`transform` 快速路径的 peek 语义。
4. **收益不对称**：这三张表每次 Redefine 访问一次或仅诊断期访问，不在按对象访问的热路径上；现状已满足条目随类卸载消失、值不反向持有 `Class` 引用。

**未来可选**：若确认不再支持旧 ART，可只迁移 `LEDGER`（唯一完全贴合 `ClassValue` 语义）。`PENDING` 因清扫需求必须留在 map。

## D10. Kotlin `trim` 家族例外：选"生产者白名单 + 来源闭包"，不选通用来源追踪或按 `CharSequence` 类型放行

**问题**：kotlinc 把 `"  a ".trim()` 展开为 `checkcast CharSequence` + `StringsKt.trim` + `Object.toString()`。黑名单规则只看 owner 和名字，把 `Object.toString` 判为 `identity-dependent dispatch`，导致 Kotlin 常见写法被拒。Java 直写同一表达式不受影响。缺口是 Kotlin 特有的，已确认影响 `trim`、`trimStart`、`trimEnd`。

**被否决的方案**
- **按 `CharSequence` 静态类型放行 `toString`**：能命中该规则的接收者静态类型必然不是 `String`（否则 owner 就是 `String`，规则看不到），要修只能放行 `CharSequence`。而 `CharSequence` 是接口，`StringBuilder` 等可变对象都实现它，会在没有 mutator 调用的情况下绕开 bit 5 防御，放行"把可复用 builder 的内容拷进新字段"。
- **通用来源追踪（允许切片内任意 `INVOKESTATIC` 作为来源）**：会放行 `static Object make(){return new Object();} static String s = make().toString();`。`make()` 方法体不在切片内，属于已知盲区，结果是非确定的对象标识。

**决定**：接收者的直接来源必须是三条精确签名的 `StringsKt` 函数，且整个来源闭包只含常量、`CHECKCAST`、白名单调用和同样通过判定的中间 `toString`；取不到来源信息时拒绝。`hashCode` 不放宽。条件详见 `01-safety-gate.md` §2.1。提交 `881d93f0`。

**审查记录**：独立审查子代理提出的 5 条"可能误放行"中，最严重的两条建立在"字段读取会被结构化接纳"的错误假设上，已用 `CaseKtFieldTrim` 证伪；采纳了"中间 `toString` 要求 opcode 为 `INVOKEVIRTUAL`/`INVOKEINTERFACE`"一条，使规则更窄。

**教训**：方案报告里"通用方案是白名单方案的超集且拒绝它拒绝的一切"的说法不成立，评审时要检查"超集"是否真的只放行了预期内的东西。

## D11. 对安全门例外做变异检查

**做法**：故意放宽一处实现，只跑 `hstestInitFixOracle`，确认对应用例变红，再撤销。撤销前先确认修复已提交，避免 `git checkout` 抹掉修复。

**结果**：6 个变异里，放行 `GETSTATIC`、放行任意静态方法被原有用例抓住；放行 `GETFIELD`/`ALOAD`、例外覆盖 `hashCode`、去掉"必须有白名单生产者"、`frames == null` 改放行，在补充 `CaseKtInstFieldTrim`、`CaseKtTrimHash`、`CaseJConstObject`、`caseFramesNullIsFailClosed` 之前原套件抓不住，补充后全部变红。

**要点**
- 原套件对"字段读取"只有 `CaseKtFieldTrim` 一个用例守着，覆盖的是静态字段；实例字段（`GETFIELD`）没有用例，这是变异检查才暴露的盲点。
- 构造"`hashCode` 的 owner 是 `Object`"的用例需要 `("  a " as CharSequence).trim().hashCode()`：直接写 `"  a ".trim().hashCode()` 时 kotlinc 先插 `String` 强制转换，owner 是 `String`；Java 写法则发 `invokeinterface CharSequence.hashCode`。**写这类用例必须先 javap，不能凭推断。**
- 无法用夹具探测的分支（`frames == null`）用反射白盒用例守住，并在方法被改名时明确报错。

