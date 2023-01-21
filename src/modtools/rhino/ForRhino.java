package modtools.rhino;

import arc.files.Fi;
import arc.func.Func2;
import arc.util.*;
import mindustry.Vars;
import modtools.utils.ByteCodeTools.*;
import rhino.*;

import java.io.File;
import java.lang.reflect.*;

import static modtools.ui.Contents.tester;

public class ForRhino {
	public static final ContextFactory factory;

	static {
		try {
			factory = createFactory();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Exclude
	public static ContextFactory createFactory() throws Exception {
		ContextFactory global = ContextFactory.getGlobal();
		MyClass<? extends ContextFactory> factoryMyClass = new MyClass<>(global.getClass().getName() + "_aa1", global.getClass());
		factoryMyClass.visit(ForRhino.class);

		factoryMyClass.setFunc("<init>", (Func2) null, Modifier.PUBLIC, void.class, OS.isAndroid ? new Class[]{File.class} : new Class[0]);
		Log.info(global.getClass().getDeclaredConstructors()[0]);

		// factoryMyClass.writer.write(Vars.tmpDirectory.child(factoryMyClass.adapterName + ".class").write());

		Constructor<?> cons = factoryMyClass.define(Vars.mods.mainLoader()).getDeclaredConstructors()[0];
		ContextFactory factory = (ContextFactory) (OS.isAndroid ? cons.newInstance(Vars.tmpDirectory.child("factory").file())
				: cons.newInstance());
		// 设置全局的factory
		if (!ContextFactory.hasExplicitGlobal()) {
			ContextFactory.getGlobalSetter().setContextFactoryGlobal(factory);
		} else {
			Field field = ContextFactory.class.getDeclaredField("global");
			field.setAccessible(true);
			field.set(null, factory);
		}
		return factory;
	}

	public static void observeInstructionCount(ContextFactory factory, Context cx, int instructionCount) {
		if (tester.stopIfOvertime && Time.millis() - tester.lastTime >= 3_000) throw new RuntimeException("超时了");
	}
}