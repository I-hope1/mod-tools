package test0;

import arc.util.Log;
// import sun.reflect.misc.*;

public class TestInit {
	public double a;
	public TestInit(double a) {
		this();
		this.a = a * 1024;

		// Log.info(FieldUtil.class);
		// Log.info(AccessFlags.class);
		// Log.info(sun.reflect.generics.visitor.Visitor.class);
	}
	public TestInit() {
		Log.info("<init>");
	}
}

// @NoAccessCheck
class A {
	static {
		// Vars.mods.parser.finishParsing();
	}
}