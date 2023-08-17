package modtools.rhino;

import arc.func.Func2;
import arc.util.OS;
import mindustry.Vars;
import mindustry.mod.ModClassLoader;
import modtools.ui.content.debug.Tester;
import modtools.utils.ByteCodeTools.MyClass;
import modtools.utils.Tools;
import rhino.*;

import java.io.File;
import java.lang.reflect.*;
import java.util.concurrent.TimeoutException;

import static modtools.ui.Contents.tester;
import static modtools.utils.Tools.clName;

public class ForRhino {
	public static final ContextFactory factory;

	static {
		try {
			factory = createFactory();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	static ContextFactory createFactory() throws Exception {
		ContextFactory                    global         = ContextFactory.getGlobal();
		MyClass<? extends ContextFactory> factoryMyClass = new MyClass<>(clName(global).replace('.', '/') + "_aa1", global.getClass());
		factoryMyClass.addInterface(MyRhino.class);
		factoryMyClass.visit(ForRhino.class);

		factoryMyClass.setFunc("<init>", (Func2) null, Modifier.PUBLIC, void.class, OS.isAndroid ? new Class[]{File.class} : new Class[0]);
		// factoryMyClass.writer.write(Vars.tmpDirectory.child(factoryMyClass.adapterName + ".class").write());

		forNameOrAddLoader(global.getClass());
		Constructor<?> cons = factoryMyClass.define(Vars.mods.mainLoader()).getDeclaredConstructors()[0];
		ContextFactory factory = (ContextFactory) (OS.isAndroid ? cons.newInstance(Vars.tmpDirectory.child("factory").file())
		 : cons.newInstance());
		// 设置全局的factory
		if (!ContextFactory.hasExplicitGlobal()) {
			ContextFactory.getGlobalSetter().setContextFactoryGlobal(factory);
		} else {
			Tools.setFieldValue(
			 ContextFactory.class.getDeclaredField("global"),
			 null, factory);
		}
		return factory;
	}

	static void forNameOrAddLoader(Class<?> cls) {
		ModClassLoader loader = (ModClassLoader) Vars.mods.mainLoader();
		try {
			Class.forName(cls.getName(), false, loader);
		} catch (ClassNotFoundException e) {
			loader.addChild(cls.getClassLoader());
		}
	}

	public static void observeInstructionCount(ContextFactory factory, Context cx, int instructionCount) {
		if (tester.killScript) throw new RuntimeException(new TimeoutException());
	}
	public static Object doTopCall(ContextFactory factory,
																 Callable callable,
																 Context cx, Scriptable scope,
																 Scriptable thisObj, Object[] args) {
		try {
			return ((ContextFactory & MyRhino) factory).super$_doTopCall(callable, cx, scope, thisObj, args);
		} catch (Throwable t) {
			// Log.err(t instanceof RhinoException ? ((RhinoException) t).getScriptStackTrace() : Strings.neatError(t));
			if (!Tester.catchOutsizeError) throw t;
			tester.handleError(t);
			return t;
		}
	}

	public interface MyRhino {
		Object super$_doTopCall(Callable callable,
														Context cx, Scriptable scope,
														Scriptable thisObj, Object[] args);
	}
}