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
