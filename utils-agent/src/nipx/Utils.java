package nipx;

import java.lang.instrument.Instrumentation;

/** 仅提供inst字段  */
public class Utils {
	public static Instrumentation inst;
	public static void agentmain(String agentArg, Instrumentation inst) {
		Utils.inst = inst;
	}
}