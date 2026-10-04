import java.lang.instrument.Instrumentation;

/** 极简 agent：只把 Instrumentation 暴露出来，供 LayoutProbe 直接调用 redefineClasses。 */
public class LayoutAgent {
	public static volatile Instrumentation INST;

	public static void premain(String args, Instrumentation inst) {
		INST = inst;
	}

	public static void agentmain(String args, Instrumentation inst) {
		INST = inst;
	}
}
