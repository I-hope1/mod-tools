package modtools.override;

import arc.func.Func2;
import arc.util.*;
import mindustry.Vars;
import modtools.annotations.asm.Sample;
import modtools.annotations.asm.Sample.SampleForMethod;
import modtools.utils.reflect.*;
import rhino.*;

import java.io.File;
import java.lang.reflect.*;

import static modtools.content.debug.Tester.Settings.catch_outsize_error;
import static modtools.ui.Contents.tester;

@SuppressWarnings("unused")
@Sample
public class ForRhino {
	public static final ContextFactory factory;

	// @Exclude
	public static void load() { }
	;
	static {
		try {
			factory = createFactory();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	static ContextFactory createFactory() throws Exception {
		ContextFactory global = ContextFactory.getGlobal();
		if (global instanceof ForRhinoInterface) return global;

		var newClass = ForRhinoInterface.visit(global.getClass(), myClass -> {
			myClass.setFunc("<init>", (Func2)null, Modifier.PUBLIC, void.class,
			 OS.isAndroid ? new Class[]{File.class} : new Class[0]);
		});
		if (OS.isAndroid) {
			HopeReflect.changeClass(global, newClass);
			return global;
		}
		Constructor<?> cons = newClass.getDeclaredConstructors()[0];
		ContextFactory factory = (ContextFactory) (OS.isAndroid ? cons.newInstance(Vars.tmpDirectory.child("factory").file())
		 : cons.newInstance());
		// 设置全局的factory
		if (!ContextFactory.hasExplicitGlobal()) {
			ContextFactory.getGlobalSetter().setContextFactoryGlobal(factory);
		} else {
			FieldUtils.setValue(
			 ContextFactory.class.getDeclaredField("global"),
			 null, factory);
		}
		return factory;
	}

	@SampleForMethod
	public static void observeInstructionCount(ContextFactory factory, Context cx, int instructionCount) {
		if (tester.stopIfOvertime && (tester.multiThread ? tester.killScript : Time.timeSinceMillis(tester.startTime) > 2000)) {
			throw new TimeoutException();
		}
	}
	@SampleForMethod
	public static Object doTopCall(ContextFactory factory,
	                               Callable callable,
	                               Context cx, Scriptable scope,
	                               Scriptable thisObj, Object[] args) {
		try {
			return ((ForRhinoInterface) factory).super$$doTopCall(callable, cx, scope, thisObj, args);
		} catch (Throwable t) {
			if (!(catch_outsize_error.enabled() || t instanceof TimeoutException)) throw t;
			tester.handleError(t);
			return null;
		}
	}

	public static class TimeoutException extends RuntimeException { }
}