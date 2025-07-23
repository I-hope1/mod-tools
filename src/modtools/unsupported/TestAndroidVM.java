package modtools.unsupported;

import android.os.FileUtils;
import arc.Core;
import arc.files.Fi;
import arc.util.*;
import mindustry.mod.Mod;

import java.io.*;
import java.lang.reflect.*;

import static modtools.IntVars.*;

public class TestAndroidVM {
	public static void main()
	 throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InvocationTargetException, NoSuchFieldException, IOException {
		Class<?> VMDebug               = mainLoader.loadClass("dalvik.system.VMDebug");
		Method   getInstancesOfClasses = VMDebug.getDeclaredMethod("getInstancesOfClasses", Class[].class, boolean.class);
		getInstancesOfClasses.setAccessible(true);
		Object[][] instances = (Object[][]) getInstancesOfClasses.invoke(null, new Class[]{Mod.class}, true);
		for (Object[] instance : instances) {
			for (Object obj : instance) {
				if (obj instanceof Mod mod) {
					Log.info("ModTools found mod: @", mod.getClass().getName());
				}
			}
		}
		// Debug.waitForDebugger();
		Method method = TestAndroidVM.class.getDeclaredMethod("fakeMethod");
		method.setAccessible(true);
		Field artMethod = Executable.class.getDeclaredField("artMethod");
		artMethod.setAccessible(true);
		artMethod.set(method, artMethod.get(A.class.getDeclaredConstructor()));
		A a = new A();
		a.field = 10;
		Log.info("field: @", a.field);
		method.invoke(a);
		Log.info("field: @", a.field);

		Method attachAgent = VMDebug.getDeclaredMethod("attachAgent", String.class);
		attachAgent.setAccessible(true);
		// new SharedLibraryLoader(Vars.mods.getMod(IntVars.modName).file.path()).load("myagent.so");
		// copyLib("libc++_shared.so", true);
		// String target = copyLib("libmyagent.so", true);
		// System.load(target);
		// attachAgent.invoke(null, target);
		/* if (Vars.class.getClassLoader() instanceof BaseDexClassLoader loader) {
			Field pathList = BaseDexClassLoader.class.getDeclaredField("pathList");
			pathList.setAccessible(true);
			Object pathListObject = pathList.get(loader);
			Field  dexElements    = pathListObject.getClass().getDeclaredField("dexElements");
			dexElements.setAccessible(true);
			Object[] dexElementsArray = (Object[]) dexElements.get(pathListObject);
			for (Object dexElement : dexElementsArray) {
				Log.info("dexElement: @", dexElement);
			}
		} */
		// FileUtils.class.getDeclaredMethod("set")
		// attachAgent.invoke(null, "libhope.so");
	}
	private static String copyLib(String name, boolean loadLib) throws IOException {
		String target = OS.getAppDataDirectoryString(Core.settings.getAppName()) + "/lib/" + name;
		Log.info("Loading "+target);
		FileUtils.copy(root.child("libs").child(name).read(), Fi.get(target).write());
		if (loadLib) System.load(target);
		return target;
	}
	static void fakeMethod() { }
	static class A {
		float field;
		A() {
			field = 100;
		}
	}
}
