package test0;

import arc.util.Log;
import mindustry.Vars;
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
		Vars.mods.parser.finishParsing();

		Log.info(sun.reflect.misc.FieldUtil.class);
		Log.info(sun.reflect.generics.visitor.Visitor.class);
	}
}