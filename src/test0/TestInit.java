package test0;

import arc.util.Log;
import jdk.internal.misc.Unsafe;
import mindustry.Vars;
import mindustry.mod.ContentParser;
import modtools.annotations.*;

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
	}
}