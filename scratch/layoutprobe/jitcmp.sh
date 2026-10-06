#!/usr/bin/env bash
# JIT 混杂因素对照：anonCapture case 在默认 / -Xint / -XX:TieredStopAtLevel=1 下的行为。
#
# ⚠️ 本脚本**只**用来排除 JIT 这一个因素，它当年没能排除真正的原因。
# 历史上 anonCapture 曾据"新实例读到 0"推出"增强模式不执行新初始化器"，
# 并用本脚本三种模式结果一致来"证明不是 JIT" —— 那一步是对的，
# 但真正的混杂因素是**创建/读取路径**（宿主是存活实例），本脚本测不到。
# 正确对照见 ctrl.sh（named case：重取构造器 / 字节码 new / 直读字段）。
#
# 保留本脚本的价值：它是"JIT 不是原因"这条结论的可复现证据。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
JBR="${LAYOUT_JBR:-/f/files/java/jdks/jbrsdk_jcef-21.0.9}"
JAVA="$JBR/bin/java.exe"; JAVAC="$JBR/bin/javac.exe"; JAR="$JBR/bin/jar.exe"
cd "$HERE" || exit 1
rm -rf out && mkdir -p out
"$JAVAC" -d out LayoutAgent.java LayoutProbe.java 2>&1 | grep -v '^Picked up'
"$JAR" cfm layout-agent.jar manifest.txt -C out . 2>&1 | grep -v '^Picked up'
for mode in default int tier1; do
  case $mode in
    default) EXTRA="";;
    int)     EXTRA="-Xint";;
    tier1)   EXTRA="-XX:TieredStopAtLevel=1";;
  esac
  for enh in on off; do
    if [ "$enh" = on ]; then flag="-XX:+AllowEnhancedClassRedefinition"; else flag="-XX:-AllowEnhancedClassRedefinition"; fi
    echo "===== JIT=$mode enhanced=$enh ====="
    "$JAVA" $EXTRA $flag -Dlp.enhanced=$([ "$enh" = on ] && echo true || echo false) \
      -javaagent:layout-agent.jar -cp out LayoutProbe anonCapture 2>&1 \
      | grep -v '^Picked up' \
      | grep -E '^(ANON|REDEFINE|FIELD v2|DONE)' | sed 's/^/  /'
  done
done
