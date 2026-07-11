package nipx.jvmti;

import java.util.*;

/**
 * Local variables captured for one stack frame.
 * {@code depth} is the JVMTI depth at capture time (0 = topmost Java frame).
 */
public record FrameLocals(String className, String methodName, String methodSignature, int depth, long location,
                          List<LocalVariable> locals) implements AutoCloseable {
	public void close() throws Exception {
		for (LocalVariable local : locals) {
			local.close();
		}
	}
}