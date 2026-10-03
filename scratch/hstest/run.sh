#!/usr/bin/env bash
# ============================================================================
# 已废弃，勿用。
#
# 本脚本已被 build.gradle 里的 `hstest` source set + JavaExec 取代。
# 废弃原因：手写 classpath 本身就是问题来源（宿主 CLASSPATH 泄漏、_libs/asm-9.5.jar
# 遮蔽 9.9.1、两种入口的 bash 不是同一个），它在本机直接运行曾通过（13 项 OK），
# 在 gradle Exec 下失败，根因未定且不值得继续打补丁。
#
# 如需运行套件，用：./gradlew hstest
# ============================================================================
# 以下内容仅作历史参考，请勿执行。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
cd "$HERE"
unset CLASSPATH   # gradle Exec 会继承宿主 CLASSPATH（本机指向 jbrsdk 25 的 dt.jar/tools.jar）
CP="$ROOT/hotswap-agent/build/classes/java/main"
CP="$CP;$ROOT/jni-agent/build/classes/java/main"
for j in \
  "D:/data/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.9.1/2ceea6ab43bcae1979b2a6d85fc0ca429877e5ab/asm-9.9.1.jar" \
  "D:/data/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm-tree/9.9.1/b6b1b3366296163b4b1f540731aad0a2baa484d8/asm-tree-9.9.1.jar" \
  "D:/data/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm-commons/9.9.1/ab35de4c537184a09339069f1a3b3aacf2289149/asm-commons-9.9.1.jar" ; do
  [ -f "$j" ] && CP="$CP;$j"
done
[ -f "$ROOT/_libs/magicClass.jar" ] && CP="$CP;$ROOT/_libs/magicClass.jar"

# 只加显式依赖。**不要**用 "$ROOT"/*.jar 通配：工作区根部若有旧版 ASM，
# 会遮蔽这里的 9.9.1，表现为 "找不到符号 ClassNode" 这类假错误。
for j in "$ROOT/_libs/"*.jar; do
  [ -f "$j" ] && CP="$CP;$j"
done
for j in "$ROOT"/*.jar; do
  case "$(basename "$j")" in *asm*|*ASM*) continue;; esac
  [ -f "$j" ] && CP="$CP;$j"
done

JK="javac --release 21"          # 夹具：只要字节码版本 21，不用 classpath
JH="javac -source 21 -target 21" # 测试入口：要读 classpath（--release 不接受）
FAILED=0
: > /tmp/hstest_log
run() { echo "--- $1 ---"; }
step() { # step <描述> <命令...>
  local desc="$1"; shift
  local log=/tmp/hstest_step.$$.log
  "$@" > "$log" 2>&1
  local rc=$?
  cat "$log" >> /tmp/hstest_log 2>/dev/null
  if [ $rc -eq 0 ]; then
    echo "   OK   $desc"
  else
    echo "   FAIL $desc (exit=$rc)"; sed -n '1,30p' "$log"; FAILED=1
  fi
  rm -f "$log"
}

# ---------- 1. 编译夹具 ----------
compile_fixture() { # dir srcdir [extra srcdir...]
  local out="$1"; shift
  rm -rf "$out"; mkdir -p "$out"
  "$JK" -nowarn -d "$out" "$@" >/dev/null 2>&1
}
rm -rf s2a s2b lf1 lf2 lf3 dp1 dp2 tw1 tw2 d21 d22 d23 sv1 sv2 sv3 av1 aof aob aon aof2 aob2 aon2 cv1 cv2 r_out1 r_out2 r_out3 r_nest1 r_nest2 r_move1 r_move2 hout
run "编译夹具"
step "swap2/leaf/deep/deep2/save3/ablate/ab2/comp 夹具" bash -c '
  set -e
  J="'"$JK"'"
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d s2a swap2/Time.java swap2/v1/test16/Swap2Case.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d s2b swap2/Time.java swap2/v2/test16/Swap2Case.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d lf1 leaf/v1/test17/LeafCase.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d lf2 leaf/v2/test17/LeafCase.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d lf3 leaf/v3/test17/LeafCase.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d dp1 deep/Time.java deep/v1/test18/DeepCase.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d dp2 deep/Time.java deep/v2/test18/DeepCase.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d tw1 two/Time2.java two/v1/test19/TwoLevel.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d tw2 two/Time2.java two/v2/test19/TwoLevel.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d d21 deep2/Time3.java deep2/v1/test20/Deep2.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d d22 deep2/Time3.java deep2/v2/test20/Deep2.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d d23 deep2/Time3.java deep2/v3/test20/Deep2.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d sv1 save3/Time4.java save3/v1/test21/Save3.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d sv2 save3/Time4.java save3/v2/test21/Save3.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d sv3 save3/Time4.java save3/v3/test21/Save3.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d av1 ab2/Time6.java ab2/v1/test23/Ablate2.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d aof ab2/Time6.java ab2/front_mid/test23/Ablate2.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d aob ab2/Time6.java ab2/back_mid/test23/Ablate2.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d aon ab2/Time6.java ab2/nested_mid/test23/Ablate2.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d aof2 ab2/Time6.java ab2/front_fin/test23/Ablate2.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d aob2 ab2/Time6.java ab2/back_fin/test23/Ablate2.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d aon2 ab2/Time6.java ab2/nested_fin/test23/Ablate2.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d cv1 comp/Time7.java comp/v1/test24/Compete.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d cv2 comp/Time7.java comp/v2/test24/Compete.java
'

# ---------- 2. 编译测试入口 ----------
HOUT=hout; rm -rf "$HOUT"; mkdir -p "$HOUT"
step "编译测试入口（src/*.java）" $JH -nowarn -cp "$CP" -d "$HOUT" src/SemAssert.java src/CompeteTest.java src/MethodOrderTest.java

[ "$FAILED" = 0 ] || { echo "套件失败：编译或夹具阶段"; exit 1; }

# ---------- 3. SemAssert（41 条硬判据）----------
run "SemAssert"
step "41 条断言" java -cp "$HOUT;s2a;s2b;lf1;lf2;lf3;dp1;dp2;tw1;tw2;d21;d22;d23;sv1;sv2;sv3;$CP" SemAssert \
  s2a/test16/Swap2Case.class s2b/test16/Swap2Case.class \
  lf1/test17/LeafCase.class lf2/test17/LeafCase.class lf3/test17/LeafCase.class \
  dp1/test18/DeepCase.class dp2/test18/DeepCase.class \
  tw1/test19/TwoLevel.class tw2/test19/TwoLevel.class \
  d21/test20/Deep2.class d22/test20/Deep2.class d23/test20/Deep2.class \
  sv1/test21/Save3.class sv2/test21/Save3.class sv3/test21/Save3.class

# ---------- 4. CompeteTest（默认排列普通断言 + 重排 expected-failure）----------
run "CompeteTest"
step "同保存竞争（8 排列）" java -cp "$HOUT;cv1;cv2;$CP" CompeteTest cv1/test24/Compete.class cv2/test24/Compete.class

# ---------- 5. MethodOrderTest（三种布局 × 8 排列）----------
run "MethodOrderTest"
step "前插布局 8 排列" java -cp "$HOUT;av1;aof;aof2;$CP" MethodOrderTest av1/test23/Ablate2.class aof/test23/Ablate2.class aof2/test23/Ablate2.class "前插"
step "后插布局 8 排列" java -cp "$HOUT;av1;aob;aob2;$CP" MethodOrderTest av1/test23/Ablate2.class aob/test23/Ablate2.class aob2/test23/Ablate2.class "后插"
step "带子插入 8 排列" java -cp "$HOUT;av1;aon;aon2;$CP" MethodOrderTest av1/test23/Ablate2.class aon/test23/Ablate2.class aon2/test23/Ablate2.class "带子的插入"

# ---------- 6. 回归入口（幂等 / 移动 / 嵌套 / Kotlin / 强制静态）----------
run "回归"
step "编译回归夹具" bash -c '
  set -e
  J="'"$JK"'"
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d r_out1 v1/test/Case.java; $J -d r_out2 v2/test/Case.java; $J -d r_out3 v3/test/Case.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d r_nest1 nest/v1/test6/NestCase.java; $J -d r_nest2 nest/v2/test6/NestCase.java
  JAVA_HOME=F:/files/java/jdks/JBR-21 $J -d r_move1 move/v1/test8/MoveCase.java; $J -d r_move2 move/v2/test8/MoveCase.java
'
step "编译回归入口" $JH -nowarn -cp "$CP" -d "$HOUT" src/IdemDebug.java src/ForceIdemTest.java src/NestTest.java src/MoveTest.java src/KtTest.java
step "幂等 pass1==pass2==pass3" java -cp "$HOUT;r_out1;$CP" IdemDebug r_out1/test/Case.class
step "MoveCase added=[] removed=[]" bash -c 'java -cp "'"$HOUT"';r_move1;r_move2;'"$CP"'" MoveTest r_move1/test8/MoveCase.class r_move2/test8/MoveCase.class | grep -q "added   = \[\]" && java -cp "'"$HOUT"';r_move1;r_move2;'"$CP"'" MoveTest r_move1/test8/MoveCase.class r_move2/test8/MoveCase.class | grep -q "removed = \[\]"'
step "NestTest" bash -c 'java -cp "'"$HOUT"';r_nest1;r_nest2;'"$CP"'" NestTest r_nest1/test6/NestCase.class r_nest2/test6/NestCase.class | grep -q "added"'
step "ForceIdemTest 幂等" bash -c 'java -cp "'"$HOUT"';r_out1;r_out2;r_out3;'"$CP"'" ForceIdemTest r_out1/test/Case.class r_out2/test/Case.class r_out3/test/Case.class | grep -q "IDEMPOTENT = true"'

# ---------- 金丝雀：断言数下限 ----------
# 防止"套件被无意改成什么都不跑，构建照样是绿的"。基线数字随套件增长只增不减。
MIN_CHECKS=40
CHECKS=$(grep -ac "PASS  \|FAIL  " /tmp/hstest_log 2>/dev/null || echo 0)
if [ "${CHECKS:-0}" -lt "$MIN_CHECKS" ]; then
  echo "   FAIL 金丝雀：实际执行的断言数 $CHECKS < 下限 $MIN_CHECKS（套件可能没真正跑）"
  FAILED=1
else
  echo "   OK   金丝雀：执行了 $CHECKS 条断言（下限 $MIN_CHECKS）"
fi

echo
if [ "$FAILED" = 0 ]; then echo "HSTEST SUITE: ALL PASSED"; else echo "HSTEST SUITE: FAILED"; fi
exit "$FAILED"
