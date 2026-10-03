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
