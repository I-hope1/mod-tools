package modtools.override;

import arc.Core;
import arc.scene.Group;
import arc.util.Log;
import modtools.utils.ByteCodeTools.*;

import static modtools.override.ForRhino.forNameOrAddLoader;

public class OScene {
	@Exclude
	public static void override() throws Exception {
		var superClass = Core.scene.root.getClass();
		forNameOrAddLoader(superClass);
		// 读取arc/scene/Element.class字节码并修改
		// 读取

		var myClass = new MyClass<>(superClass.getName() + "_ih", superClass);
		myClass.addInterface(SuperRoot.class);
		// myClass.setFunc("<init>", (Func2) null, Modifier.PUBLIC, void.class, );
		myClass.visit(OScene.class);
		myClass.define();
		// method.invoke("OScene", );
		// myClass.setFunc();
	}
	public static void draw(Group self) {
		try {
			((SuperRoot) self).super$_draw();
		} catch (Throwable e) {
			Log.err(e);
		}
		Test t = new A();
		if (t instanceof A a)
			a.sp(a.anInt);
	}
	static final class A implements Test {
		int anInt;
	}
	public sealed interface Test permits A {
		default void sp(int a) {};
	}
	public interface SuperRoot {
		void super$_draw();
	}
}
