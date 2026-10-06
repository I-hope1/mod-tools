#!/usr/bin/env bash
# 真机实例状态布局实验（§8.3 第 7 项）。
#
# 在 JBR + -XX:+AllowEnhancedClassRedefinition 下，对**存活实例**逐项试字段表变更，
# 并用 -XX:-AllowEnhancedClassRedefinition 做对照。每个 case 独立 JVM（失败的 redefine
# 可能让该类不可再用）。
#
# 用法：bash run.sh            # 跑完整矩阵
#       bash run.sh addInt    # 只跑一个 case
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
JBR="${LAYOUT_JBR:-/f/files/java/jdks/jbrsdk_jcef-21.0.9}"
JAVA="$JBR/bin/java.exe"; JAVAC="$JBR/bin/javac.exe"; JAR="$JBR/bin/jar.exe"
for f in "$JAVA" "$JAVAC" "$JAR"; do [ -x "$f" ] || { echo "缺少 $f（可用 LAYOUT_JBR=... 覆盖）"; exit 1; }; done

cd "$HERE" || exit 1
rm -rf out && mkdir -p out
"$JAVAC" -d out LayoutAgent.java LayoutProbe.java 2>&1 | grep -v '^Picked up'
"$JAR" cfm layout-agent.jar manifest.txt -C out . 2>&1 | grep -v '^Picked up'

CASES="${*:-addInt addRef delField int2long int2String obj2String inst2static static2inst}"
for case in $CASES; do
	for mode in on off; do
		if [ "$mode" = on ]; then flag="-XX:+AllowEnhancedClassRedefinition"; enh=true
		else                        flag="-XX:-AllowEnhancedClassRedefinition"; enh=false; fi
		echo "===== $case enhanced=$enh ====="
		"$JAVA" $flag -Dlp.enhanced=$enh -javaagent:layout-agent.jar -cp out LayoutProbe "$case" 2>&1 \
			| grep -v '^Picked up' \
			| grep -E '^(FIELD|BEFORE|REDEFINE|AFTER|DONE|ANON)|Exception|Error' | sed 's/^/  /'
	done
done
