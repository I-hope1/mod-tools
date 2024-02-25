package test0;

import arc.util.Log;
import mindustry.Vars;
import modtools.annotations.NoAccessCheck;
import sun.reflect.misc.*;

public class TestInit {
	public double a;
	public TestInit(double a) {
		Log.info("<init>");
		this.a = a * 1024;

		Log.info(FieldUtil.class);
		Log.info(ConstructorUtil.class);
		Log.info(sun.reflect.generics.visitor.Visitor.class);
	}
}

@NoAccessCheck
class A {
	static {
		Vars.mods.parser.finishParsing();
	}
}