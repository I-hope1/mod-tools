#!/usr/bin/env bash
# JIT 混杂因素对照：anonCapture case 在默认 / -Xint / -XX:TieredStopAtLevel=1 下的行为。
# 目的：区分"JIT 编译后的旧构造器未被作废"与"JVM 确实不执行新初始化器"。
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
