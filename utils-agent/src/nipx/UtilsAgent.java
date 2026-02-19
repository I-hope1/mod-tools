package nipx;

import java.lang.instrument.Instrumentation;

/** 仅提供inst字段  */
public class UtilsAgent {
	public static Instrumentation inst;
	public static void agentmain(String agentArg, Instrumentation inst) {
		UtilsAgent.inst = inst;
	}
}