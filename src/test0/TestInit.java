package test0;

import arc.util.Log;
import modtools.annotations.NoAccessCheck;

public class TestInit {
	public double a;
	public TestInit(double a) {
		Log.info("<init>");
		this.a = a * 1024;
	}
}


@NoAccessCheck
class A {
	static {
		// Log.info(sun.nio.ByteBuffered.class);
		// Vars.mods.parser.finishParsing();
	}
}
