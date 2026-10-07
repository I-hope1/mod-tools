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

## Step 2 的时序、旧名唯一性、幽灵遮蔽（`two/`、`deep2/`）

用户提出三点，全部复现并修复。

### (1) Step 2 只跑一次 + hasUnmatchedChild ⇒ 父被永久跳过

`hasUnmatchedChild` 在 Step 2 里同样生效，而组内遍历顺序不保证叶子在前。实测轨迹：

```
[T] step2 SKIP(parent has unmatched child) lambda$build$1 kids=[lambda$build$2]
[T] step2 try lambda$build$2 kids=[]
```

父 `$1` 排在子 `$2` 之前被处理，子尚未匹配 → 父被跳过；Step 2 只跑一遍 → 父再没机会。
**修法**：Step 2 也迭代到无进展（与前面的证据匹配一样）。

### (2) 同一个旧名字被配给两个不同方法

Step 2 的"第一个签名兼容"会在同一个 oldGroup 里按位置找，不同的新方法很容易选中同一个
未 matched 的旧方法，于是同一个旧名被登记给两个不同描述符的新方法，`renameMap` 一个键
覆盖另一个。**修法**：候选增加 `!ctx.usedOldNames.contains(oi.name)` —— 旧名只能被占一次。
同一约束也加到了 `firstFingerprintMatch`。

### (3) 幽灵与活方法同名遮蔽

`deep2` 变体乙（删 doA 整条链 + 改 doB 叶子体）：序号整体位移且无指纹证据，新叶子落到
名字 `$2` 上，而旧 `$2` 本该被复活成幽灵 —— 于是**幽灵与活方法同名**，老 doA 的调用点
解析到活方法上，静默跑起 `doB2` 的代码。

**修法**：注入幽灵前检查名字是否已被新类的活方法占用，被占用就不注入。
老 CallSite 本来已无解（它引用的旧结构不存在了），让它抛 `NoSuchMethodError` / 走
UpdateRef 熔断，比静默执行别人的逻辑正确。

### 判据升级：最终类自洽性

只用"名字归属"看结果容易被同名遮蔽误导（`NameCheck` 按名字取值，同名时读到谁不确定）。
因此加了更硬的判据：**最终类里不得出现重复的 名字+描述符，也不得出现同名不同描述符的遮蔽**。
`SemAssert` 第 5/6/7 组与 `NameCheck` 都用它。

### 断言组（`SemAssert`，全部 PASS）

| 组 | 内容 |
|---|---|
| 1 | swap2 删除变体：doB 外层→旧 `$2`，doA 那对→幽灵 |
| 2 | 叶子插入：alpha/beta 保名，新叶子独立命名 |
| 3 | 叶子双改体：各自独立命名、不串名 |
| 4 | 三层删除变体：外层→`$3`、中层→`$4`、叶子→`$5`，doA 整链幽灵 |
| 5 | 两级链 Step 2 时序：最终类自洽，父/子语义都存在 |
| 6 | 三层只改叶子体：doA 链保名，doB2 链完整存在，最终类自洽 |
| 7 | 三层删链+改体（**已知限制**）：最终类自洽、新链完整；老调用点走熔断而非静默错绑 |

## 祖先因后代被编辑而失去证据（以及修法：结构证据 + 逐层传递）

`save3` 的 V2→V3 曾经让 **B 链的外层与中层被幽灵化**。用户怀疑这是 `sameNestingLevel`
比较 `childHashes` 的**值**造成的。实测确认方向正确，但真正的第一道拦阻是
`sameSemantics`：**`semanticHash` 是递归的，后代一旦被编辑，祖先的语义指纹必然变化，
于是祖先在所有候选上都被 `semantics` 否决，根本轮不到 `sameNestingLevel`。**

追踪（含 V1→V2 那段）：

```
ACCEPT $2 vs $5                     ← 新叶子配到旧叶子
ACCEPT $0 vs $3                     ← 新外层配到旧外层
VETO   $2 vs $3/$4/$5 : semantics   ← 叶子被旧外层/中层否决（正确）
```

### 修法（两步，都只用结构信息）

1. **`sameNestingLevel` 只比结构**：有子/无子、子数量是否一致；**不再比 `childHashes` 的值**。
   这条否决本来只需要拦住"父配叶子"。
2. **新增 `calleesPairTo`（正向选择）**：双方都有子时，顺着"新子被配给了哪个旧方法"
   逐层核对 —— 只有"子正好是那些旧方法"的旧候选才有资格当选。子先落定由
   `hasUnmatchedChild` 保证。不涉及深度、也不涉及启发式。

### 结果

| 场景 | 修复前 | 修复后 |
|---|---|---|
| `deep2` 只改叶子体 | doB2 外层/中层被幽灵化 | **`$3/$4/$5` 整条链保住名字** |
| `save3` 分两次保存 | B 链外层/中层被幽灵化 | **B 链完整存活、A 链幽灵仍熔断** |
| `deep2` 删+改同时 | 叶子错绑、祖先拿新名字 | 整条链**一致地**绑到 A 链的名字上 |

### 关于 `semanticHash` 的能力边界（重要）

`semanticHash` 证明的是"**整棵子树等价**"。这在"后代被编辑"时必然失效 —— 而改叶子
方法体恰恰是 update 期最常见的动作。所以匹配链需要两层证据：

- **等价**（`semanticHash`）：能认就一步到位，且绝不错配；
- **结构**（`sameNestingLevel` + `calleesPairTo`）：等价证据失效时，用"子的配对对象"逐层传递。

只留第一层会让祖先在后代被编辑时集体失去候选（就是这次修的 bug）。

**已知限制（第 7、8 组）—— 伤害是双向的，且"分两次保存"只部分缓解**

场景：删掉 A 链，同时改掉 B 链的叶子体。实测后果**两个方向都有**：

| 受影响者 | 后果 |
|---|---|
| 已删除的 A 链的老调用点 | **静默执行 doB2**（错绑，不抛异常） |
| **刚改过的 B 链**的老调用点 | 撞上幽灵 → 熔断，报的还是 "Lambda removed by hot swap" —— 但它没被删，只是被编辑了 |

第二种更容易在实际使用中碰到：**你改了一个回调想看效果，它反而被杀了**。

### "先删再改、分两次保存"这条缓解路径：只部分有效

`save3/` 与 `Save3Test` / `SemAssert` 第 8 组实测三次保存链
（V1 两条链 → V2 删 A 链 → V3 改 B 叶子体）：

```
V1 -> V2 (删 A 链):   $3/$4/$5 = B 链 live      $0/$1/$2 = 幽灵
V2 -> V3 (改 B 叶子): $5 = [doB2] live          $3/$4 = 幽灵 ✗
                      $6/$7 = 新的外层/中层
```

- ✅ 叶子保住了（`$5`），A 链幽灵被重新注入、老调用点仍熔断；
- ⚠️ **B 链完整存活（单轮成立），但两轮序列存在已知问题** —— 见下面
  "KNOWN ISSUE：跨两轮出现跨层级错绑"。

**修正（此前写错了）**："先删再改、分两次保存"**不能称为有效缓解做法**。
两轮 V1→V2→V3 恰恰是 `$3/$4` 互换的场景，而互换是**静默改行为**，
比之前"外层/中层被幽灵化"（会熔断）**更糟**：放宽否决条件之后，这条路径的失败形态
从显式变成了静默。

当前定位：

| 序列 | 结果 |
|---|---|
| 单轮 V2→V3（原始 V2 作基线） | ✅ shape 判据通过 |
| 两轮 V1→V2→V3 | ❌ `$3/$4` 互换，静默错位 |

**根因已定位并修复**（shape 计算未迭代到定稿），两轮序列现在也通过形状不变量校验。
因此这条路径的当前状态是：**单轮、两轮均有效**，并有断言支撑。

注：V2→V3 里外层与中层可能与上一轮**互换编号**（`$3=[[doB2]]`、`$4=[[[doB2]]]`），
但链条自洽（外层→中层→叶子引用闭合），所以第 8 组的断言绑定"链条完整性"而不是具体编号。

### 判据与套件状态

`SemAssert` 第 7、8 组用确定性判据（方法表 + 最终类自洽性）把**当前实际行为**钉住，
并标注 `KNOWN LIMITATION`。这是 expected-failure 写法：套件保持全绿（44 PASS / 0 FAIL），
将来行为若变化（无论变好变坏）这两组会响，必须有人有意识地更新。

### 可观测性

对齐期注入幽灵时现在会打一行 INFO：

```
[LambdaAligner] <类名> 幽灵化 N 个方法（老 CallSite 将走熔断/降级）：a, b, c
```

它与 `顺序回退配对但方法体不一致` 告警**同时出现**时，就是"删除 + 改体同时发生"的签名。

**（历史记录，已被上面小节取代）—— 如实记录：失败模式是"静默错绑"，不是"熔断"**

删掉一条链、同时改掉另一条链的叶子体时，序号位移且没有任何指纹证据，无法把新链对回旧名。
实测最终类方法表（`MethodTable`）：

```
最终类：
  $2 (LDeep2;)V  live  calls=[doB2]         ← 旧 doA 叶子的名字被新叶子拿走
  $0,$1,$3,$4,$5 (LDeep2;)V  GHOST
  $6,$7 (LDeep2;)V  live
>>> 是否存在重复的 名字+描述符: false        ← 没有重复定义，也没有同名遮蔽
```

旧 `$2` 这个 key 已在 `presentKeys` 里（被新叶子占用），所以它**不是孤儿、不会注入幽灵**；
不存在"幽灵与活方法同名"。真实机制就是 **Step 2 把 `$2` 位置配对给了 doB2**，于是持有旧
doA 叶子的 CallSite **安静地执行 doB2** —— 不是熔断。

（我此前把这条写成"熔断"，又把注入判据写成按名字，都是错的：前者与实测不符，后者会破坏
"捕获列表变化"场景 —— 旧 `lambda$build$0(I)V` 与新 `lambda$build$0()V` 同名不同描述符，
幽灵正是那时唯一的兜底手段。注入判据已改回 `name+desc`。）

`SemAssert` 第 7 组用确定性判据把这一点钉住：旧 doA 链的三个名字必须"是幽灵或不存在"，
**且不得承载新类独有的语义**。当前该组有 1 条 FAIL，即如实反映上述静默错绑；
它作为已知限制保留，不引入新的启发式去消除（"只改方法体"与"删除+新增"在结构上确实无法区分）。

`顺序回退配对但方法体不一致` 告警的文案已同步改成"不会抛异常，而是安静地执行新方法体"。


## KNOWN ISSUE：跨两轮出现"跨层级错绑"（`save3` 三轮序列）

用户指出：`save3` 的 V2→V3 里外层与中层**互换编号**，这不是细节而是新引入的静默错绑。
确定性判据（`ShapeCheck` 与 `SemAssert` 第 8 组的新断言）：

```
shape(m) = "(" + 排序后的 shape(子) 串接 + ")"     叶子=()  中层=(())  外层=((()))
判据：旧基线里每个存活名字的形状，跨轮不变。
```

两轮序列（V1 两条三层链 → V2 删 A 链 → V3 改 B 叶子体）的实测结果：

```
FAIL lambda$build$3  旧=((()))  现=(())     ← 旧外层名字现在承载中层语义
FAIL lambda$build$4  旧=(())    现=((()))   ← 旧中层名字现在承载外层语义
```

后果：V2 里持有 `$3` 的 UpdateRef 回调原本执行 `Time.run(10, → 中层)`，现在执行
`Time.run(5, → 叶子)` —— 延迟与嵌套层数都变了，且不报错。这正是最早要消灭的那类
"静默行为改变"，只是换成了链内错位。

**"链条自洽（引用闭合）"测不出这个问题**：`B2chainIntact` 顺着 indy 逐层核对，互换后
引用依然闭合。这与此前"断言 7 只查自洽、测不出错绑"是同一类盲区。新断言改用**形状不变**，
它不看名字表、也不看引用是否闭合。

### 根因（已定位并修复）：shape 计算没有迭代到定稿

判别实验先排除了两种猜测：

```
模式 A（连续两次 align，不干预）      : $3 ((()))->(()), $4 (())->((()))
模式 B（第二次前 CONTEXT.remove()）  : 完全相同  ⇒ 不是跨调用的状态残留
```

真实根因在 `scan` 里计算 `SyntheticInfo.shape` 的那段：它**只遍历了一遍**。
`LongObjectMap` 的遍历顺序不确定，父可能先于子被处理，此时读到的是子的**默认值** `"()"`，
于是父的形状被算成 `(())` 而不是 `((()))`。后果是新旧两侧的形状都被算成同一个**错值**：

- `sameNestingLevel`（比 shape）两边都是 `(())`，说不出话；
- 形状不变量校验（若只看等值）也放行，因为两边错得一样。

**修法**：与 `semanticHash` 一样迭代到定稿（最多 64 轮，未收敛则沿用当前值并告警）。
修复后两轮 shape 完全一致，错绑列表为空：

```
$0 -> ()   $1 -> ()   $2 -> ()
$3 -> ((()))   $4 -> (())   $5 -> ()
```

（顺带修掉一处相关缺陷：`semanticHash` 的折叠把**幽灵**也当了子。幽灵是空壳、没有语义，
把它折进父的语义指纹会让旧的父得到一个无意义的值。现在计算时跳过幽灵。）

### 形状不变量运行时校验（收口，当前不触发）

见 `verifyShapeInvariant`：配对时若 `ni.shape != oi.shape` 就撤销该配对，让旧方法走幽灵、
新方法走阶段二拿避障名。

**措辞收窄（重要）**：它是**对匹配逻辑的收口**，不是对 shape **计算**的校验。
这次的 bug 恰恰是"两侧的 shape 算错成同一个值"，此时 `ni.shape == oi.shape` 成立、
校验必然放行 —— 它拦不住计算错误。要防计算错误只能靠**独立口径**：测试里那份 shape
是从最终字节码重新算的，所以是对的；运行时不必再加。

### 哨兵值：`null` 表示未定稿

`SyntheticInfo.shape` 用 `null` 表示"尚未算完"，**不再用 `"()"` 兼作哨兵** ——
`"()"` 本身就是叶子的合法形状，兼作哨兵时父读到未定稿的子无法与真叶子区分，
会算出一个"看似合法实则错误"的值，而两侧同错会让任何等值校验都失效。这正是本次
bug 的类别。迭代收敛判据相应改为"没有 `null` 且无变化"；64 轮未收敛时明确告警说明
是"没算完"（未定稿者保持 `null`，最后退化为叶子形状避免 NPE）。

`semanticHash` 用 `0` 表示未定稿，而 `0` 不可能是 CRC64 的合法结果，因此不存在同类撞车。

### 结构证据（`calleesPairTo`）的消融验证

此前用 `deep2` 论证"结构证据起作用"是无效的：那个用例的 javac 名与旧活方法名重合，
同名优先本来就能配回去。真正的消融（`ablate/`、`AblateCheck`）：在 B 链**前面**插入一个
无关 lambda，使 B 链 javac 序号整体位移、**无同名可用**，再改 B 叶子体：

```
基线(v1->v2后):   $3=[[[doB]]]  $4=[[doB]]  $5=[doB]
最终(->v3改叶子): $3=[[[doB2]]] $4=[[doB2]] $5=[doB2]   ← 外/中层都保住名字
```

结论：`calleesPairTo` 的"子配给了谁"逐层传递**确实在起作用**，不是同名兜底。

#### 三种插入布局（`ab2/`、`Ablate2Check`、`TableDump`）

只测一种布局不够，因为"位置证据恰好站对边"也能得到同样结果。因此补了三种：

| 布局 | 插入物 | 最终的 B 链 | 插入物的归宿 |
|---|---|---|---|
| **前插** | `noop()` 叶子，在 B 链之前 | `$3=[[[doB2]]]` `$4=[[doB2]]` `$5=[doB2]` | `$7=[noop]`（新名字） |
| **后插** | `noop()` 叶子，在 B 链之后 | `$3/$4/$5` 同上 | `$6=[noop]`（新名字） |
| **带子的插入** | `()->Time.run(10,()->doX())`，两层，在 B 链之前 | `$3/$4/$5` 同上 | `$8=[[doX]]` `$9=[doX]`（新名字） |

三种布局都通过硬判据：存活名字的形状跨轮不变；旧 doB 链的名字最终承载 doB2 链；最终类自洽。

**"前插是关键"这个说法已撤回。** 它依赖一个未验证的前提（"`noop` 编号更小所以先被处理"），
实测该前提不成立：javac 写出的合成方法顺序与编号**相反**。`MethodOrderTest` 打出的组内顺序：

```
新类(v3) 类文件方法顺序: [lambda$build$4, $5, $6, $1, $2, $3, $0]
```

`$0`（noop）排在**最后**，`$6`（doB2 叶子）排第三。所以前插这一格里位置证据**恰好站在
对的一边**，它不构成区分性证据。

#### 负结果的原因（已由"同保存竞争"实验证实）

`MethodOrderTest` 之所以全过，是因为**那些夹具里根本没有竞争**：`noop` 在基线里已经插入，
它在基线中是活方法，到了新类靠**指纹证据**在 Step 1 就认领了自己；新类里没有指纹证据的叶子
只剩 doB2 一个，而旧侧无人认领的叶子也只剩一个 —— **一对一，靠排除法配上**，位置证据从头到尾
没被用到。因此"重排不改变结果"并不能推出"叶子与位置无关"。

#### 同保存竞争（`comp/`、`CompeteTest`）：**失败用例出现了**

把"插入 noop"和"改 doB 叶子体"放进**同一次保存**，于是新类里有**两个都没有指纹证据**的叶子
（noop 全新、doB2 改体），而旧侧只剩一个叶子名可认领 —— 这才是真正的竞争。

```
旧(V1) 方法顺序: [$0, $1, $2]        旧语义: $0=[[[doB]]] $1=[[doB]] $2=[doB]
新(V2) 方法顺序: [$1, $2, $3, $0]    ← noop=$0 排最后、doB2=$3 排第二
```

| 排列 | 结果 |
|---|---|
| 原序（本机 javac 默认） | ✅ `$2=[doB2]` `$4=[noop]` —— doB2 拿到旧叶子名，链条完整 |
| seed=3 / seed=42 | ✅ 同上 |
| **倒序** | ❌ `$2=[noop]`，`$0/$1` 变 GHOST |
| **seed=2 / seed=7 / seed=11** | ❌ 同上（seed=11 另现 `$4/$5` 外层中层编号互换） |

**结论：叶子的归属由组内顺序决定**（Step 2 处理顺序 = `scan` 读到的方法表顺序）。
同保存下"插入新叶子 + 改另一条链叶子体"是**顺序敏感**的：who wins `$5` 取决于 javac/编译器
吐出方法表的顺序，而不同编译器（javac / ECJ / Kotlin）与不同 JDK 版本并不一致。

默认断言与 `KNOWN LIMITATION`（expected-failure）都写进了 `CompeteTest`：
默认排列的行为按普通断言固定，重排后的行为按 expected-failure 固定，套件保持全绿。

**这才是有失败用例支撑的、可以讨论"被引用关系"证据的场景** —— 但按既定标准，
是否引入该证据留待下一轮决定，本轮只固化事实。

#### 组内顺序的反向测试（`MethodOrderTest`，已由上面的竞争实验解释）

既然叶子没有子（`calleesPairTo` 按设计不作用于叶子），叶子可用的信号只剩"同名"（被 shape
否决）与"组内顺序"。于是做了直接的重排实验：把类文件方法表按 **原序 / 倒序 / 6 个固定种子乱序**
重排后送进 `align`，对三种布局各 8 种排列、共 24 次。

- 重排**确实生效**（`seed7` 把 `$0` 移到第 5 位；倒序更彻底）；
- **24 次全部通过**硬判据：旧存活名 shape 不变、旧 doB 链的名字承载 doB2 链。

**这是一个负结果，与"叶子只靠位置"的模型直接矛盾。** 说明在叶子这一步还有我没识别出的
机制在起作用。我没有完成定位（一次探针改动把源码改坏后我停下来恢复，没有继续盲改）。

因此 README 的措辞止于可验证的部分：

- ✅ **传递机制已证实**：外层与中层跟着叶子落到旧名字上（三种布局、以及竞争夹具的通过排列）；
- ✅ **叶子的决定性因素已确定**：由**组内顺序**决定 —— 依据是同保存竞争夹具里有 4 个排列失败，
  且失败时旧叶子名被 noop 拿走（详见上一节）；
- ✅ 已有失败用例，因此"被引用关系"证据**具备了引入依据**，但本轮不引入（按既定标准留待决定）；
- ✅ 竞争夹具 seed=11 下 `$4`/`$5` 的"互换"**已查明不是缺陷**（`FreshCheck` 一行检查）：
  旧基线名字集合只有 `$0/$1/$2`，`$4/$5` 都不在其中 ⇒ 它们是**阶段二 `freshId++` 分配的
  fresh name**，没有任何老 CallSite 指向它们；且 doB2 的引用链闭合（外层→中层→叶子）。
  编号随方法表排列变化只是**命名不确定性，无语义影响**。后果仅是下一轮基线带着不同编号，
  但自洽。

**修正一处我先前的误述**：我曾据中间输出称"新 noop 占了 `$5`"。`TableDump` 的最终方法表
否定了这一点 —— 那是我读错了行（把基线表与最终表看串了）。

### 一个失败了的验证尝试（记录，避免重走）

为抓"结果依赖遍历顺序"这一类缺陷，我加过 `TEST_GROUP_ORDER_SEED` 钩子（确定性打乱分组
遍历顺序），并写了 `OrderTest`：对同一输入用 24 种打乱顺序各跑一次，比较最终方法表。
**它抓不到这个 bug** —— 把 shape 退回单遍（即原始缺陷状态）后，24 种打乱顺序的结果
依然完全一致。原因是这里的分组数很少，打乱未必能把父排到子之前。

因此该钩子与 `OrderTest` 已删除：一个**未被验证能失败的测试**是负资产，会给出虚假的安全感。
真正守住这个 bug 的是 `Save3Test` / `LeakProbe` 的"两轮形状不变"判据 —— 它独立从字节码
重算 shape，与 `scan` 内部那份口径独立，能直接看出错绑。

### 已做与未做

- **已做**：`SyntheticInfo.shape`（由 indy 拓扑递归算出，与方法体内容无关）。
  `sameNestingLevel` 改为比 `shape` 相等。这修好了**单轮**的 V2→V3
  （`ShapeCheck` 单轮现在 SHAPE OK），且全量断言 40 PASS / 0 FAIL、零回归。
- **未做（诚实说明）**：**两轮序列里的互换仍然存在**。我未能定位到其确切机制，
  在调试构建上反复读错状态后我停止了盲改——按"没有确证就不改"的既定标准，
  不把未经证实的修法并进代码。因此：
  - 第 8 组新增的 shape 断言标注 `KNOWN ISSUE`，作为 expected-failure 钉住当前行为；
  - 本节的 FAIL 是**已知且未解决**的，不是期望行为。

### 两个已确认的结论（供后续排查）

1. `sameSemantics`（递归语义指纹）是"祖先因后代被编辑而失去证据"的**第一道**拦阻；
   `sameNestingLevel` 是第二道。只放宽第二道不够。
2. 放宽后的 `sameNestingLevel` 若只比"有无子 + 子数量"，会让**外层与中层结构等价**
   （都是 1 个子）而无法互相区分 —— 必须比**子树形状**。


## 待办（按优先级，留有依据）

1. **上行深度两趟匹配**（有失败用例支撑，标准已满足，本轮未做）
   - 信号：`上行深度 = 该 lambda 被多少层 lambda 嵌套引用`（由 indy 关系反推，旧新同口径）。
     旧叶子 `$2` 深度 2；`noop` 由 `build()` 直接引用，深度 0；doB2 叶子深度 2。
   - 必须**两趟**，不能只做候选过滤：A 趟只配"上行深度相等"的对；B 趟按现有逻辑兜底
     （排除法/位置）。只过滤的话，`noop` 没有同深度候选会退回兜底，先处理仍会抢走 `$2`。
   - **不做硬否决**：把叶子用新 lambda 包一层是合法编辑（深度变了），硬否决会让它丢名字被熔断；
     两趟结构下这种情形在 B 趟靠排除法仍能配上。
   - 验收：竞争夹具的 4 个失败排列从 expected-failure 翻成普通断言；补"包一层"夹具（8 种排列）
     与"多个同深度新叶子竞争一个旧叶子"夹具（后者仍是已知限制）。
2. **哨兵 `null` 与未收敛告警的单元测试**（唯一没有测试的降级路径）
   - 做法建议：把 shape 计算抽成接收 `children` 映射的纯函数，喂手写环 `a→b, b→a`，
     断言 `null` 被保留且告警触发。需要一次小重构，因此单独一轮。
3. **README 的"推荐做法"恢复条件**：第 1 项落地且 8 种排列全过之后。范围写明
   **两次保存、已验证的布局与排列；三次保存无断言**。


## 下一轮设计（本轮已定，未实现）

### 1. 上行深度的哨兵值必须避开合法值

`上行深度` 里 `0` 是**合法值**（`noop` 由 `build()` 直接引用，深度就是 0），因此"未定稿"
不能用 `0`，也不能用任何看似合法的值 —— 用 `null`（或 `-1`）。**这正是 shape 那个 bug 的同一个坑**：
父读到未定稿的值却当成合法值用了。沿用 shape 的写法：

- 引用者没算完 → 跳过本轮；
- 收敛判据 = "没有未定稿 且 无变化"；
- 带轮数上限，跑满则告警。

（本节为**推演**，尚未运行验证。）

### 2. A 趟的键用二元组 `(上行深度, shape)`

`shape` 描述向下的子树拓扑，`上行深度` 描述向上的嵌套层数，两者互补。只比深度不够：
同深度但形状不同的候选（叶子 vs 同深度的中层）会被放进同一个池子。二元组相等 ≈ 在引用树里位置相同，
A 趟候选池更小，位置兜底的范围也随之缩小。

**实现注意**：旧侧的深度必须只由**存活方法**的 indy 引用算出，**幽灵不得当引用者**。
幽灵方法体里没有 indy，本不会有引用，但遍历时别把它们当成"没有引用者 ⇒ 深度 0"混进候选池 ——
它们已被排除在匹配之外，不要让深度计算重新引入。

### 3. 同保存限制的已知绕行（已有断言支撑）

**"先保存插入新叶子、再保存改叶子体"是同保存限制的一个已验证绕行方案。**

- 依据：`ablate`/`Ablate2Check` 的三种布局（前插/后插/带子的插入），
  以及 `MethodOrderTest`（已在 HEAD，提交 `287e2a24`，非一次性探针）的 **8 种方法表排列**，
  合计 **24 次全部通过**硬判据。
- **范围限定**：仅限"**插入与修改分两次保存**"。**不要**扩写成"分两次保存总是安全"。
- **三次保存仍无断言**（`save3` 只有 `LeakProbe`/`SemAssert` 第 8 组的形状判据，未覆盖插入与修改分离）。

### 4. 下一轮动手顺序（按既定标准）

1. 先把竞争夹具的 **4 个失败排列**（倒序 / seed=2 / seed=7 / seed=11）从 expected-failure
   写成**会失败的普通断言**，确认它**红**；
2. 动 Step 2 之前先**加一行追踪**，确认 `noop` 在 A 趟里确实没有同深度的旧候选 ——
   这样"位置证据被排除"就有追踪支撑，而不只是靠夹具通过来推断；
3. 再实现两趟（A 趟配 `(上行深度, shape)` 相等者，B 趟按现有逻辑兜底，**不做硬否决**）。


## 严重缺口：断言此前完全在构建之外（已部分修复，未完成）

用户指出：`MethodOrderTest` 在 HEAD 里只证明**文件被提交**，不证明**构建会执行它**。
核实结果 —— 用户是对的，而且缺口比预想更大：

```
顶层 build.gradle:  sourceSets.test.java.srcDirs = ["test"]   → 只含 test/ 下 5 个文件
scratch/hstest/src/ 不在任何 source set 里
:hotswap-agent:test → BUILD SUCCESSFUL in 1s（什么都没跑）
```

**因此此前所有 `BUILD SUCCESSFUL` 对本套件的断言毫无约束**，`SemAssert` 的 41 条、
`CompeteTest`、`MethodOrderTest` 全是构建之外的手工入口。断言组 7 曾长期是红的而构建一直绿，
正是这个原因。

### 已做

- 新增 `run.sh`：统一入口，任一步失败即非零退出；
- 顶层 `build.gradle` 新增 `task hstest(type: Exec)`（`group = "verification"`），
  **已验证接线有效**：

  ```
  > Task :hstest FAILED
  BUILD FAILED
  ```

  即套件失败会让构建变红，这正是此前缺失的约束。

### 未完成（下次接手的第一件事）

`run.sh` 里的测试入口编译仍失败（`exit=1`）。**根因已定位**：脚本早先用
`"$ROOT"/*.jar` 通配把工作区根部的所有 jar 塞进 classpath，其中的旧版 ASM 遮蔽了 9.9.1，
表现为假的 `找不到符号 ClassNode`。已改为"跳过名字含 asm 的 jar + 只取 `_libs/*.jar`"，
但**新的一轮运行仍报同一个错误**，说明遮蔽源不止一个（还需逐项打印实际 `-cp` 定位）。

### 另一个必须记录的事实：夹具与构建的 JDK 不一致

- 构建配置用的是 **JDK 21**（`build.gradle` 里 `jvm = '.../openjdk-21.0.2/bin/java.exe'`，
  且本机 `PATH` 上的 `javac` 是 **25.0.2**）；
- 此前所有夹具都是用 **JDK 25** 编的（class file major version **69**），而 ASM 9.9.1 不认，
  这本身就会让套件在 JDK 21 下崩；
- `run.sh` 现用 `--release 21` 编夹具（字节码版本 65），用 `-source/-target 21` 编测试入口
  （`--release` 不接受 classpath）。

**这带来一个必须写明的边界**：竞争夹具的**顺序敏感性是编译器/JDK 相关的** ——
javac 25 下有 4/8 排列失败，javac 21 下 **8/8 全部通过**。因此 README 中"顺序敏感"的结论
**只在 javac 25 上得到过验证**，不能写成普遍结论。`CompeteTest` 已改为**不 pin 具体种子**，
而是 pin 行为（默认排列必须通过、统计自洽、若出现失败排列则打印出来），
以免把测试绑死在某个 JDK 上。

**同时意味着：此前汇报的 `SemAssert` 41 PASS / `CompeteTest` 全绿，都是 JDK 25 下的结果。**
在构建所用的 JDK 21 下，套件的通过状态尚未确认。


### 复现状态与我停手的原因（诚实记录）

| 事实 | 证据 |
|---|---|
| 直接 `bash run.sh` **曾经通过**（13 项 OK、`exit=0`） | 实测 |
| 走 `gradle hstest` **编译测试入口失败** | `FAIL 编译测试入口 (exit=1)` |
| `ClassNode` 只由 `asm-tree-9.9.1.jar` 提供 | `unzip -l` 逐项核对 |
| 宿主 `CLASSPATH` 指向 jbrsdk 25 的 `dt.jar`/`tools.jar` | `echo $CLASSPATH` |
| `_libs/` 里有 `asm-9.5.jar`（会遮蔽 9.9.1） | `ls _libs` |

**我连续三次修 `run.sh` 都没修好**：先是"跳过含 asm 名字的 jar"（把 `asm-tree` 也剔了，
`ClassNode` 随之消失），再是 `HERE` 变量在定义前使用，最后是编辑打乱了 `CP` 构造。
每次都是"改一处、跑一次、又不通过"。

**停手原因**：我的上下文预算已耗尽。按这几轮的教训（读错中间产物四次、改坏源码两次），
在预算见底时继续改脚本，只会再制造一次"改坏—恢复"的循环，不会得到可用结果。
因此我**不再动 `run.sh`**，把状态如实留在这里。

### 下次接手的第一步（按用户给的方法，不要猜）

```bash
bash -x run.sh 2>&1 | grep -A3 '编译测试入口'   # 打印真实 -cp
echo "$CP" | tr ';' '
'                      # 逐项核对
for j in $CP; do unzip -l "$j" | grep -q 'asm/tree/ClassNode' && echo "$j"; done
```

明确要求：**显式列出三个 9.9.1 的 jar（asm / asm-tree / asm-commons）+ 被测目录**，
不用通配符、不用"跳过某类名字"，并确认 gradle `Exec` 没有把宿主 `CLASSPATH` 带进来。

### 三项必须先补的验证（用户第 3 点）

1. **会红**：编译通过后故意让一条断言失败，确认构建变红（当前的红是**编译失败**造成的，
   不是断言 —— 只证明了一半）；
2. **不空绿**：断言数下限金丝雀（已尝试加入 `run.sh`，`MIN_CHECKS=40`，但脚本本身尚未跑通）；
3. **夹具 JDK 无关**：提交 `.class` 字节或用 ASM 生成，消掉 JDK 漂移，
   并加一个"lambda 序号整体平移"变体来检验用户第 1 点的同名巧合假设。

### 结论表述的强制限定

此前所有通过数、失败排列，**一律标注"javac 25，class file major 69"**。
构建用的是 JDK 21，但**用户的编译器不代表是 JDK 21** —— 若流水线是 JDK 8/17，
两个夹具都不代表他们。这个问题只能靠"硬编码字节码夹具"解决。


## 进展：套件已部分接入构建（按 review 改用 source set，不再手写 classpath）

### 已做并实测

| 项                                | 证据                                                                                                                                                                   |
|-----------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **手写 classpath 的问题消失**     | 新增 `hstest<br/>` source set（只放测试入口）+ `project(":hotswap-agent")` 传递 ASM ⇒ `:hstestClasses` **BUILD SUCCESSFUL**，不再列 jar、不受宿主 `CLASSPATH` 泄漏影响 |
| **toolchain 固定 21**             | `javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }`，不依赖 `PATH` 上是哪个 javac                                             |
| **"断言失败 ⇒ 构建变红"实测成立** | `hstestCanary`（不依赖任何源文件的故意失败任务）：`> Task :hstestCanary FAILED` / `BUILD FAILED`                                                                       |
| **区分了"编译失败"与"运行失败"**  | `hstestSemAssert` 失败在**运行期**（缺夹具参数），不是编译期 —— 此前那次红是编译失败，只证明了一半                                                                     |

`hstestCanary` 保留在 `build.gradle` 里作为可复用的金丝雀（故意失败，预期红）。

### 未完成

1. **夹具编译尚未接入 Gradle**：各版本夹具**同包同名**（`Time6.java`、`test23/Ablate2.java`
   各有多个版本），不能混编进一个 source set。目前仍靠 `run.sh` 按版本编到各自目录。
   `run.sh` 在直接 `bash` 下曾跑通（13 项 OK），在 gradle `Exec` 下失败，根因未定
   （候选：宿主 `CLASSPATH` 泄漏、`_libs/asm-9.5.jar` 遮蔽、两种入口的 bash 不是同一个）。
   **按 review 建议，不再修 `run.sh`**，改为把夹具做成**硬编码字节码 / ASM 直接生成**，
   这一步同时消掉 JDK 漂移。
2. **数量下限金丝雀**（防"套件被改成什么都不跑、构建照样绿"）尚未生效。
3. `hstest` 聚合任务**尚未挂到 `check`**，原因见上（夹具接线中）。

### 记账（按 review 要求写明）

**目前没有任何一个套件结果受构建约束。** 所有通过数、失败排列都只是**手动运行**下的观察。

### 夹具的字节码版本（review 指出的重要问题）

用户指出：**本项目的脱糖目标是 Java 8**，看任意一个类的字节码即可确认：

```
hotswap-agent/build/classes/java/main/OnReload.class : major 52   (= Java 8)
```

而此前所有夹具都是 `--release 21`（major 65）编的 —— **不代表生产字节码**。
（注意 `build.gradle` 里另写有 `sourceCompatibility = 25`，实际产物是 52，
说明存在独立的脱糖流程；以**产物字节码**为准。）

**已补做**：用 `--release 8`（major 52）重编竞争夹具并重跑 8 种排列：

```
新方法顺序: [lambda$build$1, $2, $3, $0]
原序              PASS（叶子名 $2 承载 doB2）
通过 4 个；失败 4 个 -> [倒序, seed=2, seed=7, seed=11]
>>> 存在顺序敏感：旧叶子名被 noop 抢走
```

**修正（此前说法过强）**：`javac --release 8` 只固定目标 major 与 API，
**不固定 javac 的行为** —— 方法表顺序、lambda 编号由 javac 内部实现决定。
用 JDK 21 的 javac 加 `--release 8`，与真正的 JDK 8 javac 产出不一定相同。

因此准确的说法是：**在 JDK 21 javac `--release 8` 产物上观察到**。
它排除了"只和 major 65 有关"这一解释，但**不等于"在生产同版本字节码上存在"**。
**真正的 JDK 8 javac 产物仍未验证**；若本机没有 JDK 8，就在此处标注"未覆盖"。

另：夹具的 major 只影响 ASM 能否读；**被热更的目标类是用户代码**，由用户的编译器产出、
不经过本项目的构建后处理。因此夹具覆盖的应是"用户的编译器"，而非"本项目的产物版本"。

### 关于"测试跑的是哪份产物"（review 第 2 点，已核实）

**结论：跑的正是生产产物，这个缺口不存在。**

```
hstest classpath: hotswap-agent/build/libs/hotswap-agent-1.6.0.jar
  该 jar 内 nipx/LambdaAligner.class : major 52
  build/classes/java/main/nipx/LambdaAligner.class : major 52   （同一时间戳）
  hotswap-agent/bin/main/nipx/annotation/OnReload.class : major 69  ← IDE 原始编译
```

- `hstest` 通过 `project(":hotswap-agent")` 取到的是 `build/libs/*.jar`（major 52），
  **不是**源码编译版，也**不是** IDE 的 `bin/main`（major 69）。
- 同一份源码在 `bin/main` 是 69、在 `build/` 是 52，说明 **52 来自构建的后处理**，
  不是 javac 的直接产出。`build.gradle` 里 `sourceCompatibility/targetCompatibility = 25`
  与该后处理并存（相关线索：`options.compilerArgs += "-AtargetVersion=8"`）。
  **具体机制未追踪**，此处只记录"产物是 52"这一事实与上述线索。

以下两条**保持假设状态**，平移实验排在套件跑通之后：

- "叶子归属对组内顺序敏感" —— 仅在 javac 25 上观察到 4/8 失败；
- "JDK 21 下 8/8 通过是同名巧合掩盖" —— 未追踪、未验证。


## 收紧：金丝雀测的是 Gradle，不是套件（review 纠正）

review 指出：`hstestCanary` 是一个**无条件失败**的任务，它只证明"Gradle 任务失败会让构建变红"——
这件事本来就成立。真正要守的是"**套件里某条断言失败 ⇒ 进程非零退出**"。

`hstestSemAssert` 因缺夹具参数而失败也**不能代替**它：那是**异常路径**，不是**断言路径**。
如果 `SemAssert` 遇到 FAIL 只打印、最后仍 `exit 0`，这两种验证都发现不了 ——
**而这正是当初组 7 的 FAIL 无人察觉的形态。**

### 正确的负对照（尚未做）

放在**套件内部**：

1. 套件末尾跑一条**故意错误的自检断言**（对已知输入断言一个已知错误的值），
   要求框架**记录到一次失败**，而不是让整体变红；
2. 一个单独开关（环境变量或参数）能让这条自检**真的暴露出来**；
   手动打开一次，确认 `hstestSemAssert` 变红，再关掉。

这样验证的是 **`check()` → 退出码** 的整条链。

### 已确认的一行检查（review 第 4 点）

- ✅ **编译确实没钉 21**：`:hstestClasses` 产出 major version **69**（宿主 JDK 25）。
- ❌ **修不了**，而且原因值得记下：给 hstest 设 `toolchain 21` 或 `options.release = 21`，
  都会让 Gradle 认为 hstest 的目标运行版本是 21，进而**拒绝消费用 25 编的
  `hotswap-agent` / `jni-agent`**：

  ```
  Could not resolve project ':hotswap-agent'.
  > Dependency resolution is looking for a library compatible with JVM runtime version 21,
    but 'project ':hotswap-agent'' is only compatible with JVM runtime version 25 or newer.
  ```

  因此运行时也**不能**钉 21。结论：**测试入口跟着宿主 JDK 走**（与那些库一致）；
  需要跨版本读字节码的是**夹具**，它们由 `--release 21` 单独编译（ASM 9.9.1 才读得动）。
- ✅ `run.sh` 已在文件头标注**已废弃，勿用**，并写明替代入口是 `./gradlew hstest`。

### 仍待做（下一轮，按 review 顺序）

1. 套件内的自检断言 + `check()`→退出码整链的手动验证；
2. 夹具改为**提交真实 javac 产物**（`.class` + 源码 + 生成命令清单，文件名带编译器版本），
   **不要用 ASM 生成** —— ASM 生成的类没有真实 javac 的方法表顺序、lambda 编号、
   indy 引导方法形态、行号与帧，而这些恰是本系列问题的变量；至少覆盖 8/17/21；
   目标版本不得高于 ASM 9.9.1 能读的上限（别再用 25 编夹具）；
3. 数量基线放进 `expected-count.txt`：**少于基线失败、多于基线也失败**（提示更新基线），
   expected-failure **单独计数**，`check` 数为 0 要特判；
4. 夹具能自包含加载后，把 `hstest` 挂到 `check`，再删掉本文件里"没有任何结果受构建约束"那句；
5. 在 21 上存基线，然后做编号平移（用 `ClassRemapper`；注意核对没有 `$deserializeLambda$`），
   检验同名巧合假设；
6. 最后才是上行深度两趟，先写红的验收。


## 套件内自检：证明 `check()` → 退出码 整条链（已完成）

review 指出 `hstestCanary` 测的是 Gradle 而不是套件 —— 它是一个**无条件失败**的 Gradle 任务，
只证明"Gradle 任务失败会让构建变红"，这件事本来就成立。而 `hstestSemAssert` 缺参数失败
是**异常路径**，不是**断言路径**。若 `SemAssert` 遇 FAIL 只打印、最后仍 `exit 0`，
两者都发现不了 —— 那正是组 7 的形态。

### 做法

- `SemAssert.selfCheck()`：故意对已知输入断言一个**已知错误**的值（`1+1==3`），
  要求 `check()` 把它**记为一次失败**，但**不让整体变红**（否则套件永远红）；
- 开关 `HSTEST_SELFCHECK_EXPOSE`（环境变量）打开时**保留**那次失败，让它真正暴露；
- 计数分离：`passed` / `failed` / `knownFailures`（**已知限制单独计数**，不混进通过数）。

### 实测（在构建里，不在手工命令行）

```
./gradlew hstestSelfCheckRun     （开关关）-> BUILD SUCCESSFUL
./gradlew hstestSelfCheckExpose  （开关开）-> > Task :hstestSelfCheckExpose FAILED / BUILD FAILED
```

**这证明的是"套件内断言失败 ⇒ 进程非零退出"，即 `check()` → 失败计数 → 退出码的整条链。**
这是此前的金丝雀和"缺参数失败"都无法证明的。

## 输入路径确认（review 第 3 点，读代码，未改代码）

**结论：入口构造的输入与生产路径一致，这个缺口不存在。**

```java
// 1) 新旧字节码都先过 forceStaticLambdas（HOTSWAP_PLUS 开启时生产路径会过）
static byte[] force(String path, ClassLoader cl) {
    byte[] r = Files.readAllBytes(Paths.get(path));
    String slash = new ClassReader(r).getClassName();
    AnnotationTransformer.HierarchyTree.register(r);
    return AnnotationTransformer.forceStaticLambdas(r, slash, cl);
}
static byte[] aligned(String v1, String v2, ClassLoader cl) {
    return LambdaAligner.align(force(v1, cl), force(v2, cl));
}

// 2) 多轮用例的 oldBytes 是上一轮 align 的输出，不是重新编译的原始产物
byte[] a2 = LambdaAligner.align(force(args[12], cl), force(args[13], cl));
byte[] a3 = LambdaAligner.align(a2, force(args[14], cl));   // a2 = 上一轮输出
```

`CompeteTest`、`MethodOrderTest`、`LeakProbe` 同样都走 `force()`。

**推论范围**：先前记录的"幂等三趟"覆盖的是 `forceStaticLambdas` 自身的幂等性；
而"多轮 `oldBytes` 取上一轮输出"这一条，由 `SemAssert` 第 8 组（save3 三轮）与
`LeakProbe` 的两轮序列直接覆盖。

## 关于 major 52 的来源（只记事实）

- 事实：同一份源码，`bin/main` 是 **69**，`build/` 与 `build/libs/*.jar` 是 **52**。
- 线索：`options.compilerArgs += "-AtargetVersion=8"`。`-A` 前缀按惯例传给**注解处理器**，
  因此脱糖可能是处理器或某个 Gradle 插件做的 —— **这是猜测，未追踪**。
- 判据：只有当测试结果在 `bin/main`(69) 与 jar(52) 之间出现差异时，才值得追到机制。
- **操作要求**：手动实验必须与 `hstest` 用**同一份 classpath**（即 `build/libs/*.jar`），
  否则可能从 `bin/main` 加载那份 69 的产物，测的就不是生产代码。用 `./gradlew hstestCp` 取。


## 编号平移实验（已完成）：同名巧合假设**在 JDK 21 上被证实**

### 做法

`ShiftTest`：只把**新类**里 lambda 合成方法的序号整体平移（`+10`），旧类保持原样。
这样新叶子的名字不再与旧叶子名重合，"同名优先"无法救场。用 `ClassRemapper` 实现
（会连带改 indy 的 `Handle`，正是所需）；并在平移前**核对没有 `$deserializeLambda$`**，
否则字符串常量需同步修改。

### 结果

| 夹具（真 javac，不带 --release） | major | 平移前 | 平移后 |
|---|---|---|---|
| **JDK 8**（`javac 1.8.0_332`） | 52 | 8/8 通过 | **8/8 通过** |
| **JDK 17**（`javac 17.0.2`） | 61 | 4/8 失败 | **4/8 失败** |
| **JDK 21**（`javac 21.0.2`） | 65 | 8/8 通过 | **4/8 失败** |
| `--release 8`（JDK 21 的 javac，早期夹具） | 52 | 4/8 失败 | 4/8 失败 |

（注意 **JDK 17 平移前就已经 4/8 失败** —— 同名巧合在它这里根本没发生，
因为它的方法顺序 `[lambda$build$2, $1, $0]` 与 JDK 21 相同，但新类的编号分布不同。
这说明"是否被同名巧合掩盖"本身也随编译器而变，不只是"限制是否存在"。）

平移后 JDK 21 的失败形态：`$0=[noop]`、`$1`/`$2` 变 GHOST —— 即**旧叶子名被 noop 抢走**，
链条被熔断。与早期 `--release` 夹具上的失败形态一致。

### 结论

1. **同名巧合确实掩盖了顺序敏感**（review 的假设成立，在 JDK 21 上）：
   平移前 8/8 通过，平移后 4/8 失败。
2. **但 JDK 8 上平移后仍 8/8 通过** —— 说明限制**取决于编译器**：
   JDK 8 给嵌套 lambda 用 `lambda$null$N`，其方法表顺序与编号结构不同，
   恰好使位置证据站对边。**这是"夹具的编译器决定结论"的第二个直接证据。**
3. 因此 README 先前的表述需要分级：
   - 在 **JDK 21 javac 产物**上：存在顺序敏感（平移后 4/8 失败）；
   - 在 **JDK 8 javac 产物**上：**未观察到**（平移前后均 8/8 通过）；
   - `--release` 产物介于两者之间，**不代表任何真实编译器**。

### 仍待做

- **JDK 17 产物**（用户流水线常见；本机 `jdk-17.0.2` 可用，用同一方式补）；
- 把平移实验固化成**断言**（入 `ShiftTest` 的 expected-failure 分组）并纳入计数基线；
- 夹具的**资源加载**（不再依赖命令行路径参数）；
- 数量基线 `expected-count.txt`、expected-failure 单独计数、挂 `check`。


## 对外部分析的核对：四个"顺序敏感破防点"（代码属实，但未被观测到）

收到一份外部代码审查，断言"开启 `TEST_REVERSE_GROUP_ORDER` 后结果出现分歧"并列出 4 处缺陷。
**逐条核对代码：4 处描述全部属实。**

| 断言 | 核对 |
|---|---|
| 内层 `oldGroups` 用原生 `nextEntry`，外层的 `groupOrder` 已反转 → 新旧两端遍历基准脱节 | ✅ 属实（`LambdaAligner` 内层循环） |
| 跨组领养 `matchByFingerprintAcrossGroups` 嵌在单组外层循环内 → 先跑的组有掠夺特权 | ✅ 属实（该调用在 `for (idx : groupOrder(...))` 循环体内） |
| `firstFingerprintMatch` / `step2` 遇第一个满足者即 `return` → 无确定性决胜 | ✅ 属实 |
| 阶段二 `freshId` 是组循环**外**的全局计数器 → 编号随组序漂移 | ✅ 属实 |

### 但前提是错的（重要）

> "开启 `TEST_REVERSE_GROUP_ORDER` 后发现结果出现分歧"

**从未观察到该分歧。** 相反，反转组序时**全部通过**，正因如此当时判定 `OrderTest` 无效并将其删除。
若当时真的分歧，就不会删。

另有两处混淆需要点明：

1. **`TEST_REVERSE_GROUP_ORDER`（组间顺序）与实测到的顺序敏感（组内方法表顺序）是两件事。**
   后者发生在 `scan` 读取 `cn.methods` 阶段，由对 `.class` 做 method shuffle 复现。
2. **"四项改造后 100% 幂等一致"是未经验证的断言**，属本会话反复约束过的 overclaim 类型。
   其中改造 2 引入 `declarationOrder` 距离作为决胜 —— 这是**新启发式**，需要夹具验证，
   不能凭代码阅读写入；改造 4 的效果已存在（`freshId` 现受 `groupOrder` 控制且检查三重冲突）。

### 本轮实测：组序反转 × 组内乱序（`CombinedOrderTest`）

对三个真实编译器夹具，各 8 种组内排列 × 组序正/反：

```
真JDK8 : 组序正/反最终方法表一致（泄漏数=0）
真JDK17: 同上
真JDK21: 同上
```

**负结果：组序反转没有产生分歧。**

### 两个探针的结果（`OrderProbe`）——负结果对 JDK 21 是**空洞的**

```
探针 1 分组数：
  真 JDK 8 夹具 (c8v2)  : 组数 = 2
      [lambda$build$|()V] -> [$3, $0]
      [lambda$null$|()V]  -> [null$2, null$1]
  真 JDK 21 夹具 (c21v2): 组数 = 1          ← 只有一组！
      [lambda$build$|()V] -> [$3, $2, $1, $0]

探针 2 钩子是否换序：
  groupOrder 参数类型 [nipx.util.LongObjectMap]
  FLAG EFFECTIVE：groupOrder 的字节码确实读取该 flag 并参与分支
```

**结论（对自己的负结果做纠正）**：

1. **钩子确实生效**（字节码层面确认它读取 flag 并分支）。
2. **但 JDK 21 夹具只有 1 个组** —— 组序反转对它**不产生任何区别**。
   因此上一节"泄漏数=0"对 **JDK 21 是空洞的**，不能作为任何证据。
3. **JDK 8 夹具有 2 个组**，组序反转对它**有实际意义**，其"泄漏数=0"才是一次真实观测
   （虽然组数少，仍不能推广）。
4. 这也解释了为什么 JDK 8 与 JDK 21 的行为不同：**JDK 8 把嵌套 lambda 放进
   `lambda$null$` 组**，而 JDK 21 全部塞进 `lambda$build$` 一个组。

**修正后的措辞**：在 **JDK 8 夹具（2 组）**上未观察到组序反转导致的分歧；
在 **JDK 21/17 夹具（1 组）**上该实验**不可执行**，需先构造多组夹具。
"这四个破防点是否可达"**仍未判定**。

### 清理项

`TEST_REVERSE_GROUP_ORDER` / `groupOrder` 仍在 `LambdaAligner` 里（8 处）。
它们是我早先加的测试钩子；无论后续决定是否采纳那份分析，都应清理或明确标注为测试专用。


## 跨组争抢：**复现成功**（外部审查的核心主张成立，我先前的结论被推翻）

### 夹具与结果（`xgroup/`、`XGroupTest`）

```java
// V1：一个孤立闭包
void methodOld() { runR(() -> shared()); }

// V2：methodOld 拆成两个方法，体内各放一个**完全相同**的闭包
void methodA() { runR(() -> shared()); }
void methodB() { runR(() -> shared()); }
```

两个新 lambda 分属 `methodA$` / `methodB$` **两个组**，却与老类里孤立的老 lambda 指纹相同。

```
V1 的 lambda: [lambda$methodOld$0]
V2 的 lambda: [lambda$methodB$1, lambda$methodA$0]

正序结果: lambda$methodB$1(...)#L | lambda$methodOld$0(...)#L
反序结果: lambda$methodA$0(...)#L | lambda$methodOld$0(...)#L
=> 组序正/反的最终方法表**不一致** —— 顺序敏感复现成功
```

**JDK 8 与 JDK 21 上表现完全一致**，且与外部审查给出的输出形态
（`lambda$methodA$0()V` / `lambda$methodB$1()V`）吻合。

### 我先前结论的错误

上一轮我用**竞争夹具（`comp/`）**做组序反转实验，得到"泄漏数=0"，并写进 README 说
"未观察到组序反转导致的分歧"。现在看，那个夹具**不构成跨组争抢**：
它的新类只有一个组（JDK 21）或两个组但**没有两个新方法争抢同一个旧名字**的情形。
**要用能产生"多组争抢同一旧方法"的夹具，才能触达这条路径。**

`XGroupTest` 现已作为**会失败的验收**留在套件里（退出码 2 区分"复现成功"与"断言失败"）。

### 已固化：expected-failure（套件保持绿，行为一变就响）

`XGroupTest` 已改写为 expected-failure 形态：

```
正序: lambda$methodB$1(...)#L | lambda$methodOld$0(...)#L
反序: lambda$methodA$0(...)#L | lambda$methodOld$0(...)#L
   PASS  正序结果自洽（无重复 名字+描述符）
   PASS  反序结果自洽（无重复 名字+描述符）
   KNOWN 跨组争抢导致组序敏感：正序/反序的最终方法表不同
通过 2 条；失败 0 条；已知限制 1 条
XGROUP ASSERTIONS OK      exit=0
```

- 顺序敏感**仍存在** -> 记一次 `KNOWN`（不计入失败，套件保持绿）；
- 若某天它**消失** -> 记 `FAIL [已知限制已变化]`，强制有人有意识地更新。

**JDK 8 与 JDK 21 表现完全一致。** 计数分离：`通过 / 失败 / 已知限制`。

### 待定：修法

外部审查给出的两阶段仲裁器（先收集意向、再按客观属性决胜）方向合理，但它引入了
**新的决胜规则**（`logicalName` 字典序 → `name` 字典序）。按既定标准，这种新规则需要
先用夹具验证它选出的 winner 是**正确**的，而不只是**确定**的 —— 字典序最小未必语义最对。

因此下一步是：先把本用例固化为 expected-failure，再评估候选决胜规则
（同名优先已存在；是否需要字典序、或 `declarationOrder` 距离）在既有夹具上是否回归。


## 套件入口与数量基线（本轮进展，未完成）

### 已完成

1. **`suite.sh`**：套件唯一入口，取代已废弃的 `run.sh` 与手工命令行。
   - **classpath 由 Gradle 解析后经命令行参数传入**（不用环境变量，实测 Exec 下不可靠），
     脚本内**不手写 classpath**；
   - 用三个真实 javac（8/17/21）编译夹具（一次性），运行期不再依赖 javac；
   - 计数分离并**对比基线**：`通过 / 失败 / 已知限制`；
   - **空绿防护**：`通过=0 且 已知=0` 时直接失败（就是 `:hotswap-agent:test` 空跑一秒那种失败）。

2. **`expected-count.txt`**：基线 `4 0 2`（通过 4 / 失败 0 / 已知限制 2）。
   **少于基线失败；多于基线也失败**，提示更新基线 —— 新增断言是有意识的操作。

3. **`build.gradle` 的 `hstestRun` 任务**：`dependsOn hstestClasses`，并把
   `hstestRun` **挂到 `check`**：

   ```groovy
   tasks.named("check") { dependsOn tasks.named("hstestRun") }
   ```

4. **直接运行 `bash suite.sh <cp>` 实测通过**：

   ```
   实际: 通过=4 失败=0 已知=2
   基线: 通过=4 失败=0 已知=2
   OK   数量与基线一致
   HSTEST SUITE: ALL PASSED
   ```

### 未完成：Gradle 下夹具编译失败

`./gradlew hstestRun` 报 10 个 `FAIL 编译 c*/x*`，而**同一脚本直接运行全部通过**。
即"脚本相同、入口不同、结果不同"——与 `run.sh` 那次是同一类问题。

已知线索：
- Gradle daemon 由常驻进程启动，其 `PATH` 可能不包含 `javac`（本机 `javac` 在
  `F:/files/java/jdks/jbrsdk_jcef-25.0.2/bin`，由 shell 环境提供）；
- 我加的诊断（打印 `javac=$JC` 与实际错误）**在 Gradle 输出里没有出现**，
  说明 Gradle 实际执行的可能是**旧版脚本**（进程/缓存问题），或输出被吞。

### 下次接手的第一步（不要猜）

```bash
./gradlew hstestRun --info 2>&1 | grep -A20 'suite.sh'   # 看 Gradle 到底执行了什么
HSTEST_JAVA=... bash scratch/hstest/suite.sh "<cp>"       # 与直接运行对比
```

**要点**：把三个 javac 的路径**在 Gradle 侧解析成绝对路径**后传入（与本机 PATH 解耦），
并确认 Gradle 执行的是当前脚本而不是缓存副本。

### 记账（未变）

**目前仍没有任何一个套件结果受构建约束** —— `hstestRun` 已挂到 `check`，但它当前是红的，
且红的原因是**夹具编译**而非断言。等它变绿并经过"故意失败"验证后，才可删除本句。

---

## 退出码链的反向验证（2026-02，已实测）

**为什么需要**：空绿守卫（`通过=0 且 已知=0`）只能防"什么都没跑"，
**防不住"跑了但失败被吞"**。若失败被吞，套件里所有断言都是不受约束的绿灯。

**方法**：临时把 `XGroupTest` 的 KNOWN 分支改成恒不命中
（`if (stillBroken)` → `if (false)`），使其从 KNOWN 变成 FAIL，跑 `./gradlew check`。

**结果（实测）**：

```
1 FAILED                                   ← 断言确实失败
   FAIL 数量与基线不符 —— 若是有意增删断言，请更新 expected-count.txt
HSTEST SUITE: FAILED
> Task :hstestRun FAILED
BUILD FAILED in 12s                        ← ./gradlew check 确实变红
```

随后恢复文件，`HSTEST SUITE: ALL PASSED`，`git status` 显示与版本库一致。

**结论**：失败会经 `suite.sh`(FAILED=1) → `exit 1` → `hstestRun` → `check` 逐级传播，
**不是被吞掉的**。因此套件内的红/绿结论可信，可以作为后续验收的依据。

此后新增任何断言，都应在**让它红一次**之后再依赖它的绿。

---

## 未覆盖的降级与告警路径（盲区清单）

以下为当前实现中包含但在测试套件中**尚未覆盖或不可达**的降级与告警路径，留痕防遗忘：

1. **`settled` 循环 64 轮上限告警**：
   - 位置：`LambdaAligner.java` 中的 `settleRound >= 64`
   - 告警：`HotSwapAgent.warn("[LambdaAligner] settled 循环达到上限(64)仍未收敛 " + ctx.currentClass)`
   - 现状：实际嵌套深度浅（通常 ≤ 3 轮即收敛），64 轮死循环防护属于未触发的防御性降级分支。
2. **`upDepth` 64 轮未收敛告警**：
   - 位置：`computeUpDepth` 中的 `round == 63`
   - 告警：`HotSwapAgent.warn("[LambdaAligner] upDepth 未收敛: " + ...)`
   - 现状：在树状拓扑下通常 2~3 轮收敛，未收敛告警未在夹具中测试。
3. **`computeShapes` 64 轮未收敛告警**：
   - 位置：`computeShapes` 中的 `round == 63`
   - 告警：`HotSwapAgent.warn("[LambdaAligner] computeShapes 未收敛: " + ...)`
   - 现状：同上，无环依赖下必然在深度步数内收敛。
4. **`calleesPairTo` 的否决分支（`!ci.matchedWith.name.equals(want)`）**：
   - 位置：`calleesPairTo` 中的第 599 行
   - 现状：现有单/双链夹具中，子树不等价时已被 `sameSemantics` 提前拦截；仅在同构双链 cross-pairing 等极端边缘场景下可能进入，当前测试套件中属未覆盖路径（未验证不可直接断言死代码）。
5. **JDK 8 上的 `StackWalker` / 调用栈回退路径**：
   - 位置：孤儿熔断 `onOrphanInvoked` 的调用者查找逻辑
   - 现状：在 JDK 8 运行时使用 `new Throwable().getStackTrace()` 回退分支，尚未做独立真实冒烟。
