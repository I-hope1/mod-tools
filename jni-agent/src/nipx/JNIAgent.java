package nipx;

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.connect.Connector.Argument;
import com.sun.tools.jdi.VirtualMachineManagerImpl;

import java.io.IOException;
import java.util.*;

public class JNIAgent {
	public static void load() throws IllegalConnectorArgumentsException, IOException {
		/* try (Arena arena = Arena.ofConfined()) {
			JNIEnv jniEnv = new JNIEnv(arena);
			for (FrameLocals locals : StackCapture.capture(jniEnv, Thread.currentThread())) {
				if (!locals.locals().isEmpty()) System.out.println((locals.locals().get(0).getValue(jniEnv)));
			}
		} */
		/* for (var connector : VirtualMachineManagerImpl.virtualMachineManager().allConnectors()) {
			System.out.println((connector));
		} */
	}
}
