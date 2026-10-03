# hstest —— `lambda$build$21` / NoSuchMethodError 修复的验证脚手架

针对 `LambdaAligner` 的 static/instance 签名失配问题做端到端复现与验证。**不属于产品代码**，
仅用于回归验证，确认结论后可整体删除。

## 三个被测版本

同一份 `test.Case`，差别只在 lambda 对 `this` / 局部变量的捕获形态：

| 目录 | `run()` 里的 lambda | 编译后形态 |
|---|---|---|
| `v1/` | `s -> println(field + s + loc)` | `instance lambda$run$0(ILjava/lang/String;)V` |
| `v2/` | `s -> println(field + s)`（少捕获 `loc`） | `instance lambda$run$0(Ljava/lang/String;)V` |
| `v3/` | 同 v1，但方法体多一个 `"!"`（指纹不同、描述符同形） | `instance lambda$run$0(ILjava/lang/String;)V` |

`forceStaticLambdas` 会把它们统一变成 `static lambda$run$0(Ltest/Case;...)V`。

## 运行方式

```bash
# 1) 先编译被验证的 agent
./gradlew :hotswap-agent:compileJava --offline

cd scratch/hstest
mkdir -p out1 out2 out3 outT
javac -d out1 v1/test/Case.java
javac -d out2 v2/test/Case.java
javac -d out3 v3/test/Case.java

ASM=D:/data/.gradle/caches/modules-2/files-2.1/org.ow2.asm
CP="../../hotswap-agent/build/classes/java/main;../../jni-agent/build/classes/java/main;\
$ASM/asm/9.9.1/2ceea6ab43bcae1979b2a6d85fc0ca429877e5ab/asm-9.9.1.jar;\
$ASM/asm-tree/9.9.1/b6b1b3366296163b4b1f540731aad0a2baa484d8/asm-tree-9.9.1.jar;\
$ASM/asm-commons/9.9.1/ab35de4c537184a09339069f1a3b3aacf2289149/asm-commons-9.9.1.jar;\
$ASM/asm-analysis/9.9.1/1ab8d9316ef7a67240087919a708246c37ed1660/asm-analysis-9.9.1.jar;\
D:/data/.gradle/caches/modules-2/files-2.1/Anuken/Mindustry/v160.5/cff2148777000ffca97f5ca3a88f79af51eb4c8f/Mindustry-v160.5.jar;\
../../_libs/magicClass.jar"

# Git for Windows 的 bash 会把 -cp 里的 ';' 当路径分隔符转换，必须关掉
MSYS2_ARG_CONV_EXCL='*' javac -nowarn -cp "$CP" -d outT src/ForceIdemTest.java
MSYS2_ARG_CONV_EXCL='*' java -cp "outT;out1;out2;out3;$CP" ForceIdemTest \
    out1/test/Case.class out2/test/Case.class out3/test/Case.class
```

## Context 包装提案 / raw 基线可行性（`ctx/`、`CtxTest`、`RawVsForcedTest`、`RawHandoffTest`）

三个夹具 `ctx/v1`、`ctx/v2`、`ctx/v3` 是同一份 `test2.CtxCase`，两个 lambda 各自只捕获一个
`PaneContext` 引用（提案形态）。`v2` 在 `build()` 最前面插入一个新 lambda，原有 lambda 源码
一字未改；`v3` 只改方法体。

```bash
mkdir -p cout1 cout2 cout3 coutT
javac -d cout1 ctx/v1/test2/CtxCase.java
javac -d cout2 ctx/v2/test2/CtxCase.java
javac -d cout3 ctx/v3/test2/CtxCase.java
MSYS2_ARG_CONV_EXCL='*' javac -nowarn -cp "$CP" -d coutT src/CtxTest.java src/RawVsForcedTest.java src/RawHandoffTest.java
MSYS2_ARG_CONV_EXCL='*' java -cp "coutT;cout1;cout2;$CP" CtxTest    cout1/test2/CtxCase.class cout2/test2/CtxCase.class cout3/test2/CtxCase.class
MSYS2_ARG_CONV_EXCL='*' java -cp "coutT;cout1;cout2;$CP" RawVsForcedTest cout1/test2/CtxCase.class cout2/test2/CtxCase.class
MSYS2_ARG_CONV_EXCL='*' java -cp "coutT;cout1;cout2;$CP" RawHandoffTest  cout1/test2/CtxCase.class cout2/test2/CtxCase.class
```

结论（三个都已复现）：

1. **只改方法体（v1 -> v3）**：`added=[] removed=[]`。注意 `MethodFingerprinter.visitFieldInsn`
   把字段名算进指纹，所以 body hash 会变、Step 1 失配，真正保住名字的是 **Step 2**
   （同组 + 签名逻辑等价 + 同名优先，不看指纹）。
2. **插入新 lambda（v1 -> v2）**：两个原有 lambda 的归一化描述符完全相同，Step 2 只能按组内
   顺序配对，结果**语义对调** —— 老 callsite `lambda$build$0`（原为 `ctx.pane`）解析到打印
   `ctx.infoCell` 的方法体。`RawVsForcedTest` 证明：**raw 基线与 forced 基线的对齐产物逐项相同**，
   换基线不解决交叉。
3. **raw 基线的手递手问题（`RawHandoffTest`）**：`LambdaAligner` 的 `renameMap` 以
   `name + desc` 为键；改用 raw 基线后键是 raw 描述符，而 `applyTransform` 面对的是已被
   `forceStaticLambdas` 改过描述符的字节码。实测 `rename 落地了？ false`（改用 forced 描述符
   做键则 `true`）。即 rename 会被静默丢弃，除非另加"描述符无关回退"。

## 按钮注册形态的可区分性（`btn/`、`id/`、`BtnTest`、`IdTest`、`RefTest`）

用户反例：`t.button("新建", () -> create()); t.button("保存", () -> save()); ...`
在 `build()` 最前面插入一个新按钮。三种写法实测结论完全不同：

| 写法 | 编译产物 | 指纹可区分？ | 结果 |
|---|---|---|---|
| `() -> create()` / `() -> save()` / `() -> delete()` | 三个 synthetic lambda 方法 | ✅ 互不相同 | 插入后可正确认领，无对调 |
| `() -> create()` ×3（三个体完全相同） | 三个 synthetic lambda 方法，**逐字节等价** | ❌ 只有 1 种指纹 | 无法区分，但对调也无行为差异 |
| `this::create`（方法引用） | **没有** synthetic lambda 方法，indy 直接指向 `create` | — | 不在对齐器处理范围内，只作为普通方法增删出现在 DIFF |

```bash
mkdir -p bout1 bout2 boutL boutL2 boutT iout1 iout2 ioutT
javac -d bout1 btn/Table.java btn/v1/test4/BtnCase.java
javac -d bout2 btn/Table.java btn/v2/test4/BtnCase.java
javac -d boutL  btn/Table.java btn/v1lambda/test4/BtnCase.java
javac -d boutL2 btn/Table.java btn/v2lambda/test4/BtnCase.java
javac -d iout1 id/Table.java id/v1/test5/IdCase.java
javac -d iout2 id/Table.java id/v2/test5/IdCase.java
MSYS2_ARG_CONV_EXCL='*' javac -nowarn -cp "$CP" -d boutT src/BtnTest.java src/RefTest.java
MSYS2_ARG_CONV_EXCL='*' javac -nowarn -cp "$CP" -d ioutT src/IdTest.java

MSYS2_ARG_CONV_EXCL='*' java -cp "boutT;boutL;boutL2;$CP" BtnTest boutL/test4/BtnCase.class boutL2/test4/BtnCase.class
MSYS2_ARG_CONV_EXCL='*' java -cp "boutT;bout1;$CP"        RefTest bout1/test4/BtnCase.class
MSYS2_ARG_CONV_EXCL='*' java -cp "ioutT;iout1;iout2;$CP"  IdTest  iout1/test5/IdCase.class iout2/test5/IdCase.class
```

关键实测输出：

```
# 三个不同调用目标的 lambda：指纹互不相同
v1: lambda$build$0 hash=c33858f85aa7c03b 调用链=[create]
    lambda$build$1 hash=0fb80e7ad347e60f 调用链=[save]
    lambda$build$2 hash=2f30545f307ce793 调用链=[delete]
>>> 三个 lambda 指纹互不相同？ true

# 插入新按钮后，原三个原地保留，新按钮拿到新名字
aligned: $0=[create]  $1=[save]  $2=[delete]  $4=[createNew]   added=[createNew()V, lambda$build$4]

# 三个体完全相同时：只有 1 种指纹
>>> 三个 lambda 指纹互不相同？ false（只有 1 种指纹）
aligned: 四个方法体全等，added=[lambda$build$3] —— 无对调，也无从对调

# 方法引用
raw 方法表：<init> build create delete save     ← 没有 lambda$
>>> 有 lambda$ 合成方法吗？ false
```

## 容器 lambda 与告警判据（`CarrierProbe`）

用户提出：容器 lambda（体内挂着内层 lambda）的槽位微调不该刷 WARN。`CarrierProbe` 量了判据：

```bash
MSYS2_ARG_CONV_EXCL='*' javac -nowarn -cp "$CP" -d soutT src/CarrierProbe.java
MSYS2_ARG_CONV_EXCL='*' java -cp "soutT;$CP" CarrierProbe nout1/test6/NestCase.class boutL/test4/BtnCase.class out1/test/Case.class
```

```
NestCase: lambda$build$0  indy=1 其中指向本类合成=1  -> 容器
          lambda$build$1  indy=0                     -> 普通业务 lambda
BtnCase:  三个都 indy=0                              -> 普通（不会误判）
Case:     lambda$run$0    indy=1 其中指向本类合成=0   -> 普通（forEach 之类不算容器）
```

**要点**：判据必须是"体内含 invokedynamic，且其 impl 句柄指向本类的合成方法"，
不能只数 indy —— `list.forEach(x -> ...)` 也有 indy，但它指向 JDK，不是容器。

不过最终实现采用了**更便宜且更严的等价判据**：`ni.name != bestOld.name`
（即"旧名字换了主人"）。理由：

| 情况 | 名字保住？ | 老 CallSite 的行为 | 该不该 WARN |
|---|---|---|---|
| 普通 body 编辑 | ✅ | 原地更新，正确 | ❌ 不是故障 |
| **容器 lambda 内层改动/插入** | ✅ | 新容器 + 已重映射的内层，正确 | ❌ 不是故障 |
| 组内同形候选错位让位 | ❌ | 被绑到别的方法体上 | ✅ 真信号 |

"名字是否保住"本身就是充分证据，不需要额外遍历指令判断容器；
按指纹报警则会把上表前两行（日常编辑）全部刷出来，淹没第三行。

实测（三个场景 WARN 计数）：改动前嵌套场景每次都报，改动后全为 0，且对齐结果不变。

## 嵌套 lambda 与 #SYNTHETIC_METHOD# 占位（`nest/`、`swap/`、`NestTest`、`SwapTest`、`FpProbe`、`PlaceholderTest`）

### 结论一：嵌套 lambda 的身份由指纹恢复，不会被对调

`nest/v1` 是 `outer = () -> { Runnable inner = () -> {...}; inner.run(); }`，
`nest/v2` 在 outer 体内**最前面插入一个新的内层 lambda**（原有内层序号 $1 → $2）。

```bash
mkdir -p nout1 nout2 noutT
javac -d nout1 nest/v1/test6/NestCase.java
javac -d nout2 nest/v2/test6/NestCase.java
MSYS2_ARG_CONV_EXCL='*' javac -nowarn -cp "$CP" -d noutT src/NestTest.java
MSYS2_ARG_CONV_EXCL='*' java -cp "noutT;nout1;nout2;$CP" NestTest nout1/test6/NestCase.class nout2/test6/NestCase.class
```

```
v2 forced: $1(hash=8504b2…, 新代码)  $2(hash=4e1dab…, 原内层)
aligned:   $1(hash=4e1dab…, 原内层)  $3(hash=8504b2…, 新代码)
added = [lambda$build$3()V]
```

原内层 lambda 被指纹精确认回 `$1`，新插入的拿到避障名 `$3`，**没有对调**。

### 结论二：占位机制正常，但它不保护"槽位变化"

`FpProbe` 逐项打印 CRC 更新。对调两个内层 lambda 的声明顺序后，外层 lambda 的
两处 implMethod 句柄名字都被成功屏蔽：

```
handle owner=test7/SwapCase name=lambda$build$1 -> 计入[#SYNTHETIC_METHOD#]
handle owner=test7/SwapCase name=lambda$build$2 -> 计入[#SYNTHETIC_METHOD#]
```

**但外层 hash 仍然变了**。差异在最后三条指令：

```
v1: ... var 58 0 ... var 58 1 ... var 25 0 / run ... var 25 1 / run
v2: ... var 58 0 ... var 58 1 ... var 25 1 / run ... var 25 0 / run
```

对调声明使得 `a`/`b` 两个局部变量的**槽位互换**，而
`MethodFingerprinter.visitVarInsn` 把变量号计入指纹 —— 所以这是**真实的方法体差异**，
不是占位失效。`PlaceholderTest` 用 ASM 精确复现了这一点：

```bash
javac -d soutT src/PlaceholderTest.java
MSYS2_ARG_CONV_EXCL='*' java -cp "soutT;$CP" PlaceholderTest
```

```
A: 内层名=lambda$build$1 槽位=0  hash=5ee60045c25af6d3
B: 内层名=lambda$build$9 槽位=0  hash=5ee60045c25af6d3   ← 只有名字不同 → hash 相同 ✓
C: 内层名=lambda$build$1 槽位=1  hash=7b52196a45dfd981   ← 只有槽位不同 → hash 不同
>>> 只有内层名字不同 -> 外层 hash 相同？ true   （占位机制生效）
>>> 只有槽位不同     -> 外层 hash 相同？ false  （槽位参与指纹）
```

推论：改动内层 lambda 的**数量或顺序**会改变外层 lambda 的槽位布局，因此外层必然被判定为
"方法体变了"，走顺序回退（并触发告警）。这是设计使然，无法靠占位消除。

### 结论三：内层对调时，内层自己靠指纹各就各位

`swap/v1` 与 `swap/v2` 只对调两个内层 lambda 的声明顺序，内层体不变：

```bash
MSYS2_ARG_CONV_EXCL='*' java -cp "soutT;sout1;sout2;$CP" SwapTest sout1/test7/SwapCase.class sout2/test7/SwapCase.class
```

```
v2 forced: $1=[BBB]  $2=[AAA]      ← 声明顺序对调，名字跟着对调
aligned:   $1=[AAA]  $2=[BBB]      ← AAA 仍是 $1，BBB 仍是 $2，未被交换
added = []  removed = []
```

## 顺序回退告警 (a) 与跨组指纹匹配 (c)

`pair/v1`、`pair/v2` 是同一方法内两个"同形"lambda（描述符相同），`v2` 只改第一个的方法体。

```bash
mkdir -p pout1 pout2 poutT
javac -d pout1 pair/v1/test3/PairCase.java
javac -d pout2 pair/v2/test3/PairCase.java
MSYS2_ARG_CONV_EXCL='*' javac -nowarn -cp "$CP" -d poutT src/PairTest.java
MSYS2_ARG_CONV_EXCL='*' java -cp "poutT;pout1;pout2;$CP" PairTest pout1/test3/PairCase.class pout2/test3/PairCase.class
```

实测结果：

```
[NIPX] [WARN] [LambdaAligner] 顺序回退配对但方法体不一致 test3/PairCase：
        旧 lambda$build$0(Ltest3/PairCase;)V <- 新 lambda$build$0(Ltest3/PairCase;)V ...

old:      lambda$build$0 -> [ctx, a]      lambda$build$1 -> [ctx, b]
new:      lambda$build$0 -> [ctx, sb]     lambda$build$1 -> [ctx, b]
aligned:  lambda$build$0 -> [ctx, sb]     lambda$build$1 -> [ctx, b]     ← 未被对调
```

- **(a) 告警**：改动的那个 lambda 指纹对不上，只能顺序回退 → 触发告警，指明"旧 <- 新"是哪一对。
- **(c) 跨组指纹匹配**：没改动的那个 lambda 指纹仍然吻合 → 在顺序回退之前就被认领回旧名，
  因此不会被顺序回退对调。

`IdemDebug` 用于验证 `forceStaticLambdas` 的幂等性（`pass1 == pass2`）：

```bash
MSYS2_ARG_CONV_EXCL='*' javac -nowarn -cp "$CP" -d outT src/IdemDebug.java
MSYS2_ARG_CONV_EXCL='*' java -cp "outT;out1;$CP" IdemDebug out1/test/Case.class
# 期望：pass1 == pass2 ? true / pass2 == pass3 ? true
```

## 断言要点（对应修复点）

- `forceStaticLambdas IDEMPOTENT = true`
  —— 同一份输入跑两次结果逐字节相同。**这是修复 #2 能成立的前提**：热更调度层先归一化
  `newBytes`，随后 redefine 触发的 `transform()` 还会再调一次，若第二次再前置一个 `this`，
  描述符会变成 `(LFoo;LFoo;...)`，与 lambda 体的局部变量槽位错位。
- `Case D`（`align(forcedV1, RAW v3)`，即修复前的输入）：
  `added=[lambda$r1$0()V, lambda$run2$0(Ljava/lang/String;)V, lambda$run$0(ILjava/lang/String;)V]`
  —— 老名字被顶掉、新实例方法被当成"新增"，这正是 `+Added: lambda$build$21` 的来源。
- `Case E`（`align(forcedV1, forcedV3)`，即修复后的调用时序）：
  `added=[] removed=[]`，`lambda$run$0(Ltest/Case;ILjava/lang/String;)V` 原地保留，
  老 `CallSite` 稳定命中。
- 运行期验证：把对齐后的字节码写盘、`URLClassLoader` 加载 + 反射调用 `run()` / `r1()`，
  确认 `COMPUTE_FRAMES` 产物可验证、可执行（无 `VerifyError`）。

## 幽灵空壳必须退出匹配（`del/`、`DelTest`）

链式场景（review 第 3 条的复现）：

```
V1: build(){ run(()->a()); run(()->b()); }   -> $0=[a]  $1=[b]
V2: build(){ run(()->b()); }                 -> 删掉第一个，b 从 $1 变 $0
V3: build(){ run(()->b2()); }                -> 改 b 的方法体
```

V2 对齐后：`$0`=幽灵（兜住被删的 a）、`$1`=[b]。此时若幽灵仍参与匹配，V3 会变成：

```
修复前 V3 aligned:  $0=[b2]   $1=幽灵    build()->[$0]
                    ^^ 活着的 lambda 丢了自己的名字 $1，持有 $1 的调用点去跑 b2
修复后 V3 aligned:  $0=幽灵   $1=[b2]    build()->[$1]
                    ^^ 名字保住了，幽灵稳定留在 $0 继续兜老调用点
```

`LambdaAligner.scan` 通过"方法体里调用了 `onOrphanInvoked`"识别幽灵，标记
`SyntheticInfo.ghost`；幽灵**保留**在 `oldGroups` 里供孤儿计算复现注入，但**不参与**
Step 1a/1b、Step 2 与 `firstFingerprintMatch` 的任何候选选择。

```bash
mkdir -p dout1 dout2 dout3 doutT
javac -d dout1 del/v1/test10/DelCase.java
javac -d dout2 del/v2/test10/DelCase.java
javac -d dout3 del/v3/test10/DelCase.java
MSYS2_ARG_CONV_EXCL='*' javac -nowarn -cp "$CP" -d doutT src/DelTest.java
MSYS2_ARG_CONV_EXCL='*' java -cp "doutT;dout1;dout2;dout3;$CP" DelTest dout1/test10/DelCase.class dout2/test10/DelCase.class dout3/test10/DelCase.class
```

## 非 lambda 合成方法被跨组污染（`kt/`、`KtTest`）

`scan` 的准入是 `ACC_SYNTHETIC || 名字命中 lambda 系模式`，而 `ACC_SYNTHETIC` 覆盖的远不止
lambda —— Kotlin 的 `foo$default` / `getX$annotations` 也带这个标志，且常常体相同（一堆空体）。
跨组指纹匹配不受逻辑名约束，于是"删掉一个、新增一个同体的"会把新增的改名成被删的那个名字：

```
v1:      getBar$annotations  getFoo$annotations
v2:      getBar$annotations  getBaz$annotations     (Foo 删、Baz 增)
aligned: getBar$annotations  getBaz$annotations  getFoo$annotations(幽灵)
                      ^^ 修复后保住自己的名字
修复前:  getBar$annotations  getFoo$annotations      ← Baz 被改名，外部类调用点不会跟着改 -> NoSuchMethodError
```

修法：`renameable = matchesPattern && !name.startsWith("access$")`，即**只有 lambda 系名字**
参与匹配与改名；其它合成方法仍被无条件登记进避障集（`existingNewNames` / `oldNameDescSet`），
阶段二生成避障名时照样避开它们。

```bash
MSYS2_ARG_CONV_EXCL='*' javac -nowarn -cp "$CP" -d ktout src/KtTest.java
MSYS2_ARG_CONV_EXCL='*' java -cp "ktout;$CP" KtTest
```

## 外层 lambda 指纹确实会碰撞（`nest4/`、`Nest4Test`）

`#SYNTHETIC_METHOD#` 会把本类 lambda 系方法名替换掉，于是"体外层只是求值一个内层 lambda"时，
两个外层指纹完全相同 —— 实测确认：

```
v1: lambda$carrierA$0 hash=4504391648418999158
    lambda$carrierB$0 hash=4504391648418999158   ← 同一个值
>>> 两个外层 lambda 指纹相同？ true
```

（注意区分：若外层直接调用的是**普通方法** `handleA()`，名字不会被屏蔽，指纹自然不同 ——
`nest2/`、`nest3/` 两个夹具证明了这个区别。）

但**没能构造出实际错配**：内层 lambda 的名字由它自己那条路径独立重映射，外层名字即使被
换掉，调用链最终仍指向正确的内层。因此这一条记为"真实存在的碰撞、当前无可复现症状"，
未做改动 —— 用户的 `callees` 决胜方案只有在能证明症状后才值得引入。

## 嵌套 lambda：父与子的指纹碰撞导致静默对调（`swap2/`、`SemCheck`）

用户给出的链式用例（`run(() -> Time.run(10, () -> doA()))`）：

```
V1: run(() -> Time.run(10, () -> doA()));   O0 -> 内层 $1
    run(() -> Time.run(10, () -> doB()));   O1 -> 内层 $3
V2: run(() -> Time.run(10, () -> doB()));   N0 -> 内层 $1
```

实测确认两点：

1. **hash 碰撞成立**：两个外层（以及两个内层）的指纹完全相同 ——
   `lambda$build$0` 与 `lambda$build$2` 都是 `5e01dca6d610fd00`，
   因为内层名被 `#SYNTHETIC_METHOD#` 屏蔽。跨独立编译稳定可复现。
   （注意：外层与外层同 hash、内层与内层同 hash；外层与外层之间本来就不该同 hash，
   我第一轮测量把内外层的值混着看了，是错的。）

2. **失败点与用户推演不同**。真实轨迹（`SemCheck` + 决策追踪）：

```
[T] 1a MATCH lambda$build$0 <- lambda$build$0     ← 外层按同名配上了（推演里这步会错，实际没有）
[T] CROSS lambda$build$1 <- lambda$build$3        ← 内层被改名走
```

   外层**保住了名字**，但它的**子节点被改名走了** —— 于是 `$0` 的方法体里那句指向
   `$1` 的引用转而去调用别人。语义对拍：

```
旧:   lambda$build$0 -> [doA]
对齐: lambda$build$0 -> [doB]     ← 同名方法语义被换掉
```

### 关于 `callees` 决胜条件（措辞更正）

**不成立的只是"未映射的 callees"**：新旧外层的 callees 都是同一个内层名 `$1`，
直接比较零区分度。

用户方案比的是**经 `renameMap` 映射后**的 callees —— 新外层 `[$1]` 映射为 `[$3]`，
与旧 `$2` 的 `[$3]` 一致，与旧 `$0` 的 `[$1]` 不一致。**在"叶子先配对"这个前提下，
它是有区分度的。** 我此前写成"callees 方案不成立"是误述。

最终采用 `childHashes` 而非映射后 callees，理由是它不依赖匹配顺序、也不受改名影响
（映射后的结果依赖"叶子已经配完"这一时序假设）；两者在正确前提下的判别力相当。

### 落地的修法

1. `SyntheticInfo.children` / `childHashes`：`scan` 收集方法体里引用的本类 lambda 名
   及其指纹（整类扫完后再算，因为子信息要先存在）。
2. `hasUnmatchedChild`：**父必须等子落定** —— 每轮匹配只处理"子已全部 matched"的方法，
   整轮无进展才收敛（每轮至少确认一个方法，最多 n 轮）。
3. `sameNestingLevel`：候选必须同层级。父有子、子是叶子，`childHashes` 必然不同，
   据此否决"父配到子"或"子配到父"。不靠推断深度，只比集合。
4. Step 1a 拆成"同名优先 + 不限名兜底"两趟，两者都带上上述护栏。
5. 匹配改为按轮迭代：Step 1（同组）→ 跨组指纹 → Step 2（顺序），每轮重跑直到收敛。

### 结果（名字归属实测，`NameCheck` / `SemAssert`）

删除变体（删掉 doA 那对，doB 那对没动）：

| 旧名字 | 旧语义 | 对齐后 | 判定 |
|---|---|---|---|
| `lambda$build$0` | doA 外层 | GHOST | 正确：它确实被删了 |
| `lambda$build$1` | doA 内层 | GHOST | 正确 |
| `lambda$build$2` | doB 外层 | `[[doB]]` | **正确：活 lambda 保住了自己的旧名字** |
| `lambda$build$3` | doB 内层 | `[doB]` | **正确：保住了旧名字** |

即新外层认领的是旧 `$2`（doB 的外层），**不是**变幽灵、也没有拿新名字。
（我最初用 `SemCheck` 时把"新方法的名字"和"旧名字的归宿"混着看了，误报成"旧外层全部变幽灵"；
`NameCheck` 显式打印"旧名字 -> 现在承载什么语义"，才看清。）

| 变体 | 修复前 | 修复后 |
|---|---|---|
| 删除变体 | `$0` 语义被换成 doB（静默对调） | doB 外层/内层各自保住旧名 `$2`/`$3`，仅 doA 那对变幽灵 |
| 插入变体（开头插入同形外层） | 两处语义互换 | 原有外层/内层全部保住名字，新块拿新名字 |

### 匹配轮次结构（Step 2 只在最后跑一次）

```
do { Step 1（同组同 hash）+ 跨组指纹 } while (有新增配对);
Step 2                                    // 兜底，只跑一次
```

Step 2 完全不看指纹。若让它参与中间轮次，某个旧方法可能被"按位置"占走，
而它本该由后续某轮的精确指纹认领 —— `hasUnmatchedChild` 只挡得住父，挡不住叶子之间的抢占。
把它推迟到最后，就使"有证据的匹配"总能先于"无证据的匹配"用尽候选。

### 断言清单（`SemAssert`，全部 PASS）

1. 删除变体：旧 `$2` 仍是 doB 外层；旧 `$0`/`$1` 是幽灵；旧 `$3` 仍是 doB 内层。
2. `leaf` 插入：开头插入一个 gamma 叶子后，alpha 保住 `$0`、beta 保住 `$1`、
   gamma 拿独立名字（互不抢占）。
3. `leaf` 双改体：alpha→alpha2、beta→beta2 时各自拿独立名字，
   旧名字不得承载无关语义，且 alpha2/beta2 不落在同一名字上。

```bash
mkdir -p s2out1b s2out2b s2out3 s2outT
javac -d s2out1b swap2/Time.java swap2/v1/test16/Swap2Case.java
javac -d s2out2b swap2/Time.java swap2/v2/test16/Swap2Case.java
javac -d s2out3  swap2/Time.java swap2/v3/test16/Swap2Case.java
MSYS2_ARG_CONV_EXCL='*' javac -nowarn -cp "$CP" -d s2outT src/SemCheck.java src/Swap2Test.java
MSYS2_ARG_CONV_EXCL='*' java -cp "s2outT;s2out1b;s2out2b;$CP" SemCheck s2out1b/test16/Swap2Case.class s2out2b/test16/Swap2Case.class
MSYS2_ARG_CONV_EXCL='*' java -cp "s2outT;s2out1b;s2out3;$CP"  SemCheck s2out1b/test16/Swap2Case.class s2out3/test16/Swap2Case.class
```

## 三层嵌套：票据指纹、childHashes 同时失效（`deep/`、`DeepCase`）

用户构造的三层用例，实测确认预测命中：

```
V1: run(() -> Time.run(10, () -> Time.run(5, () -> doA())))
    run(() -> Time.run(10, () -> Time.run(5, () -> doB())))
V2: 删掉第一个
```

修复前的结果：

```
旧 $0 ([[[doA]]]) -> [[[doB]]]   CHANGED   ← 老 O_A 的调用点去跑 doB
旧 $3 ([[[doB]]]) -> GHOST                 ← 活着的 O_B 被误杀、走熔断
旧 $4/$5          -> OK
```

**为什么 `childHashes` 也救不了**：中层的体完全同构（内层名被 `#SYNTHETIC_METHOD#` 抹掉），
所以 `hash(M_A) == hash(M_B)`；于是两个外层的 `childHashes` 都退化成 `{hash(M)}` —— 集合相等，
`sameNestingLevel` 的否决失效，父层退回同名优先。**深度也解决不了**：两个外层同深度，
差异在最深处的叶子。

### 修法：递归语义指纹（不是深度、也不是正向选择）

`SyntheticInfo.semanticHash` = 方法自身的票据指纹，再逐层折入每个子 lambda 的语义指纹
（`scan` 里整类扫完后由下往上迭代到定稿；叶子没有子，语义指纹就等于票据指纹）。

差异因此沿树**向上传播**：叶子不同 ⇒ 中层语义指纹不同 ⇒ 外层语义指纹也不同。
匹配把原来的 `hash` 比较换成 `sameSemantics`，于是：

- 同深度、子集合相同、但子树不同的方法**不再撞车**；
- 不需要知道嵌套深度，也不需要"正向选择"这一层单独逻辑。

修复后：

```
旧 $0/$1/$2 (doA 整条链) -> GHOST        正确：确实被删了
旧 $3 ([[[doB]]]) -> [[[doB]]]  OK
旧 $4 ([[doB]]])  -> [[doB]]    OK
旧 $5 ([doB])     -> [doB]      OK
```

### 走错的两版（记录，避免重走）

1. **比"映射后 callees 的名字"**：新旧两侧的直接子恰好同名（都叫 `lambda$build$1`），
   `oldChild.equals(newChild)` 直接通过，正向选择形同虚设。
2. **比"子被配给了谁"（身份）**：太严 —— 外层受 `sameNestingLevel` 限制（它有两个子、
   子有一个子），只能配到同构的另一侧，于是活着的 doB 整条链全部领不到名字。
   根因是"只比直接子"无法表达"整棵子树"。

递归语义指纹同时解决了这两个问题的反面：它既不比名字、也不止于直接子。

### 三层断言（`SemAssert` 第 4 组）

```
== deep 三层删除变体 ==
   PASS  活的外层落在旧名字 lambda$build$3
   PASS  中层落在旧名字 lambda$build$4
   PASS  叶子落在旧名字 lambda$build$5
   PASS  doA 外层/中层/叶子 变幽灵
```
