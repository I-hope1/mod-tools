#!/usr/bin/env bash
# 反射混杂因素对照（JEP 416）：named case，正常反射 vs 退回旧反射实现。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
JBR="${LAYOUT_JBR:-/f/files/java/jdks/jbrsdk_jcef-21.0.9}"
JAVA="$JBR/bin/java.exe"; JAVAC="$JBR/bin/javac.exe"; JAR="$JBR/bin/jar.exe"
cd "$HERE" || exit 1
rm -rf out && mkdir -p out
"$JAVAC" -d out LayoutAgent.java LayoutProbe.java 2>&1 | grep -v '^Picked up'
"$JAR" cfm layout-agent.jar manifest.txt -C out . 2>&1 | grep -v '^Picked up'
for refl in dmh nodmh; do
  if [ "$refl" = nodmh ]; then RF="-Djdk.reflect.useDirectMethodHandle=false"; else RF=""; fi
  for enh in on off; do
    if [ "$enh" = on ]; then flag="-XX:+AllowEnhancedClassRedefinition"; else flag="-XX:-AllowEnhancedClassRedefinition"; fi
    echo "===== REFLECT=$refl enhanced=$enh ====="
    "$JAVA" $RF $flag -Dlp.enhanced=$([ "$enh" = on ] && echo true || echo false) \
      -javaagent:layout-agent.jar -cp out LayoutProbe named 2>&1 \
      | grep -v '^Picked up' \
      | grep -E '^(REFLECT|CTRL|REDEFINE|FIELD v2|DONE)' | sed 's/^/  /'
  done
done
