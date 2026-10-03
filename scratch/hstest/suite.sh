#!/usr/bin/env bash
# ============================================================================
# 对齐器验证套件的唯一入口（取代已废弃的 run.sh 与手工命令行）。
#
# 由 build.gradle 的 `hstestRun` 任务调用，classpath 由 Gradle 通过环境变量传入，
# 不在此处手写 —— 手写 classpath 是此前多次失败的根源。
#
# 用法：HSTEST_CP=<classpath> bash suite.sh
# 退出码：0 = 全部通过（含 expected-failure 按 KNOWN 计数）；非 0 = 失败或数量不符基线。
# ============================================================================
set +u
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

if [ $# -ge 1 ] && [ -n "$1" ]; then
  HSTEST_CP="$1"
elif [ -n "${HSTEST_CP:-}" ]; then
  :   # 已由环境提供
else
  echo "必须提供 classpath：suite.sh <classpath>（见 build.gradle 的 hstestRun）" >&2
  exit 2
fi
JAVA_BIN="${HSTEST_JAVA:-java}"
JAVAC_8="${HSTEST_JAVAC8:-F:/files/java/jdks/jdk-1.8/bin/javac}"
JAVAC_17="${HSTEST_JAVAC17:-F:/files/java/jdks/jdk-17.0.2/bin/javac}"
JAVAC_21="${HSTEST_JAVAC21:-F:/files/java/jdks/openjdk-21.0.2/bin/javac}"

# 路径规范化：Gradle 的 Exec 可能调用 **WSL 的 bash**（cwd 形如 /mnt/e/...），
# 而 WSL 不认 F:/... 这种 Windows 风格路径。只在原路径**确实不存在**时才转换，
# 这样 Git Bash 直接运行（路径可用）不受影响。
winpath() { # winpath <windows-style-path>
  local p="$1"
  [ -e "$p" ] && { printf '%s' "$p"; return; }          # 已可用，原样返回
  if command -v wslpath >/dev/null 2>&1; then
    printf '%s' "$(wslpath -u "$p" 2>/dev/null || printf '%s' "$p")"
  elif command -v cygpath >/dev/null 2>&1; then
    printf '%s' "$(cygpath -u "$p" 2>/dev/null || printf '%s' "$p")"
  else
    printf '%s' "$p"
  fi
}
JAVA_BIN="$(winpath "$JAVA_BIN")"
JAVAC_8="$(winpath "$JAVAC_8")"
JAVAC_17="$(winpath "$JAVAC_17")"
JAVAC_21="$(winpath "$JAVAC_21")"

FAILED=0
LOG=$(mktemp)

step() { # step <描述> <命令...>
  local desc="$1"; shift
  local out; out=$("$@" 2>&1); local rc=$?
  printf '%s\n' "$out" >> "$LOG"
  if [ $rc -eq 0 ]; then echo "   OK   $desc"
  else echo "   FAIL $desc (exit=$rc)"; printf '%s\n' "$out" | tail -15; FAILED=1; fi
}

# ---------- 夹具：用三个真实 javac 编译（一次性，运行期不再依赖 javac）----------
echo "--- 编译夹具（真实 javac 8 / 17 / 21）---"
echo "   [javac-8]  $("$JAVAC_8" -version 2>&1 | grep -v "Picked up" | head -1 | tr -d '\r')"
echo "   [javac-17] $("$JAVAC_17" -version 2>&1 | grep -v "Picked up" | head -1 | tr -d '\r')"
echo "   [javac-21] $("$JAVAC_21" -version 2>&1 | grep -v "Picked up" | head -1 | tr -d '\r')"
mkdir -p fx
for v in 8 17 21; do
  case $v in 8) JC="$JAVAC_8";; 17) JC="$JAVAC_17";; 21) JC="$JAVAC_21";; esac
  for ver in v1 v2; do
    out="fx/c${v}${ver}"; rm -rf "$out"; mkdir -p "$out"
    if [ "$ver" = v1 ]; then SRC="comp/v1/test24/Compete.java"; else SRC="comp/v2/test24/Compete.java"; fi
    "$JC" -nowarn -encoding UTF-8 -d "$out" comp/Time7.java "$SRC" >/dev/null 2>&1 \
      || { echo "   FAIL 编译 c${v}${ver} (javac=$JC cwd=$PWD)"; "$JC" -nowarn -encoding UTF-8 -d "$out" comp/Time7.java "$SRC" 2>&1 | head -4; FAILED=1; }
  done
done
for v in 8 21; do
  case $v in 8) JC="$JAVAC_8";; 21) JC="$JAVAC_21";; esac
  for ver in v1 v2; do
    out="fx/x${v}${ver}"; rm -rf "$out"; mkdir -p "$out"
    if [ "$ver" = v1 ]; then SRC="xgroup/v1/test25/XGroup.java"; else SRC="xgroup/v2/test25/XGroup.java"; fi
    "$JC" -nowarn -encoding UTF-8 -d "$out" "$SRC" >/dev/null 2>&1 \
      || { echo "   FAIL 编译 x${v}${ver}"; FAILED=1; }
  done
done
for v in 8 17 21; do
  case $v in 8) JC="$JAVAC_8";; 17) JC="$JAVAC_17";; 21) JC="$JAVAC_21";; esac
  for ver in v1 v2; do
    out="fx/fa${v}${ver}"; rm -rf "$out"; mkdir -p "$out"
    if [ "$ver" = v1 ]; then SRC="compA/v1/FixtureA.java"; else SRC="compA/v2/FixtureA.java"; fi
    "$JC" -nowarn -encoding UTF-8 -d "$out" compA/Time.java "$SRC" >/dev/null 2>&1 \
      || { echo "   FAIL 编译 fa${v}${ver}"; FAILED=1; }
  done
done
# 编译 SemAssert 15 组夹具（使用固定 JDK 21 编译器，解耦宿主 PATH）
for dir in s2a s2b lf1 lf2 lf3 dp1 dp2 tw1 tw2 d21 d22 d23 sv1 sv2 sv3; do
  mkdir -p "$dir"
done
JC_REL="$JAVAC_21 -nowarn -encoding UTF-8"
$JC_REL -d s2a swap2/Time.java swap2/v1/test16/Swap2Case.java >/dev/null 2>&1 || FAILED=1
$JC_REL -d s2b swap2/Time.java swap2/v2/test16/Swap2Case.java >/dev/null 2>&1 || FAILED=1
$JC_REL -d lf1 leaf/v1/test17/LeafCase.java >/dev/null 2>&1 || FAILED=1
$JC_REL -d lf2 leaf/v2/test17/LeafCase.java >/dev/null 2>&1 || FAILED=1
$JC_REL -d lf3 leaf/v3/test17/LeafCase.java >/dev/null 2>&1 || FAILED=1
$JC_REL -d dp1 deep/Time.java deep/v1/test18/DeepCase.java >/dev/null 2>&1 || FAILED=1
$JC_REL -d dp2 deep/Time.java deep/v2/test18/DeepCase.java >/dev/null 2>&1 || FAILED=1
$JC_REL -d tw1 two/Time2.java two/v1/test19/TwoLevel.java >/dev/null 2>&1 || FAILED=1
$JC_REL -d tw2 two/Time2.java two/v2/test19/TwoLevel.java >/dev/null 2>&1 || FAILED=1
$JC_REL -d d21 deep2/Time3.java deep2/v1/test20/Deep2.java >/dev/null 2>&1 || FAILED=1
$JC_REL -d d22 deep2/Time3.java deep2/v2/test20/Deep2.java >/dev/null 2>&1 || FAILED=1
$JC_REL -d d23 deep2/Time3.java deep2/v3/test20/Deep2.java >/dev/null 2>&1 || FAILED=1
$JC_REL -d sv1 save3/Time4.java save3/v1/test21/Save3.java >/dev/null 2>&1 || FAILED=1
$JC_REL -d sv2 save3/Time4.java save3/v2/test21/Save3.java >/dev/null 2>&1 || FAILED=1
$JC_REL -d sv3 save3/Time4.java save3/v3/test21/Save3.java >/dev/null 2>&1 || FAILED=1

[ $FAILED = 0 ] && echo "   OK   夹具编译完成" || { echo "夹具编译失败"; exit 1; }

run() { # run <类> <参数...>
  "$JAVA_BIN" -cp "s2a;s2b;lf1;lf2;lf3;dp1;dp2;tw1;tw2;d21;d22;d23;sv1;sv2;sv3;fx;$HSTEST_CP" "$@"
}

# ---------- 套件入口 ----------
echo "--- 套件入口 ---"
step "XGroupTest (JDK8 夹具)"  run XGroupTest "fx/x8v1/test25/XGroup.class" "fx/x8v2/test25/XGroup.class"
step "XGroupTest (JDK21 夹具)" run XGroupTest "fx/x21v1/test25/XGroup.class" "fx/x21v2/test25/XGroup.class"
step "NameIndexTest (三 JDK + 正反序)" run NameIndexTest
step "FixtureATest (三 JDK + 正反序)" run FixtureATest
step "SemAssert (15 夹具 / 40 条断言)" run SemAssert \
  s2a/test16/Swap2Case.class s2b/test16/Swap2Case.class \
  lf1/test17/LeafCase.class lf2/test17/LeafCase.class lf3/test17/LeafCase.class \
  dp1/test18/DeepCase.class dp2/test18/DeepCase.class \
  tw1/test19/TwoLevel.class tw2/test19/TwoLevel.class \
  d21/test20/Deep2.class d22/test20/Deep2.class d23/test20/Deep2.class \
  sv1/test21/Save3.class sv2/test21/Save3.class sv3/test21/Save3.class
step "PassBTest (验证 Pass B 计数器非零可达性)" run PassBTest
step "CompeteDeleteTest (三层竞争夹具: 删 A 链 + B 叶子改捕获)" run CompeteDeleteTest

# ---------- 数量基线 ----------
echo "--- 数量基线 ---"
BASE_FILE="$HERE/expected-count.txt"
if [ ! -f "$BASE_FILE" ]; then
  echo "   FAIL 缺少基线文件 $BASE_FILE"; exit 1
fi
# 基线格式：<通过> <失败> <已知限制>（合计三类）
read -r BASE_PASS BASE_FAIL BASE_KNOWN < "$BASE_FILE"
ACT_PASS=$(grep -ac "   PASS  " "$LOG" || true)
ACT_FAIL=$(grep -ac "   FAIL  " "$LOG" || true)
ACT_KNOWN=$(grep -ac "   KNOWN " "$LOG" || true)
echo "   实际: 通过=$ACT_PASS 失败=$ACT_FAIL 已知=$ACT_KNOWN"
echo "   基线: 通过=$BASE_PASS 失败=$BASE_FAIL 已知=$BASE_KNOWN"
if [ "$ACT_PASS" -eq 0 ] && [ "$ACT_KNOWN" -eq 0 ]; then
  echo "   FAIL 金丝雀：一条断言都没执行（空绿）"; FAILED=1
elif [ "$ACT_PASS" -ne "$BASE_PASS" ] || [ "$ACT_FAIL" -ne "$BASE_FAIL" ] || [ "$ACT_KNOWN" -ne "$BASE_KNOWN" ]; then
  echo "   FAIL 数量与基线不符 —— 若是有意增删断言，请更新 $BASE_FILE"; FAILED=1
else
  echo "   OK   数量与基线一致"
fi

echo
if [ $FAILED = 0 ]; then echo "HSTEST SUITE: ALL PASSED"; else echo "HSTEST SUITE: FAILED"; fi
rm -f "$LOG"
exit $FAILED
