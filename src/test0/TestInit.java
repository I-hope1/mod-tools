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
	}
}

@NoAccessCheck
class A {
	static {
		Log.info(FieldUtil.class);
		Log.info(sun.reflect.generics.visitor.Visitor.class);
		Vars.mods.parser.finishParsing();
	}
}