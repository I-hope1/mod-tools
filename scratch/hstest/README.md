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
