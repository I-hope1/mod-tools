package modtools.override;

import arc.*;
import arc.func.*;
import arc.input.KeyCode;
import arc.scene.*;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import modtools.Constants;
import modtools.content.debug.Pause;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.ui.control.HKeyCode;
import modtools.utils.ByteCodeTools.*;
import modtools.utils.ByteCodeTools.MyClass.Lambda;
import modtools.utils.*;
import modtools.utils.reflect.FieldUtils;
import rhino.classfile.ByteCode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.*;

import static ihope_lib.MyReflect.lookup;
import static modtools.IntVars.json;
import static modtools.override.ForRhino.forNameOrAddLoader;
import static modtools.ui.IntUI.topGroup;

public class HScene {
	public static final String SUFFIX = "-$H";

	@Exclude
	public static void load(Pause pause) throws Exception {
		Class<? extends Group> superClass = Core.scene.root.getClass();
		if (superClass.getName().endsWith(SUFFIX)) return;
		MyClass<? extends Group> myClass = new MyClass<>(superClass.getName() + SUFFIX, superClass);

		Method method1 = Element.class.getDeclaredMethod("act", float.class);
		MethodHandle element_act = CatchSR.apply(() ->
		 CatchSR.of(() -> lookup.unreflectSpecial(method1, Element.class))
			.get(() -> newLookup(Element.class).unreflectSpecial(method1, Element.class))
		);
		Method method2 = Group.class.getDeclaredMethod("act", float.class);
		MethodHandle super_act = CatchSR.apply(() ->
		 CatchSR.of(() -> lookup.unreflectSpecial(method2, Group.class))
			.get(() -> newLookup(Group.class).unreflectSpecial(method2, Group.class))
		);
		Object[] self = {null};
		Floatc floatc = delta -> {
			if (pauseMap.get(Vars.ui.getClass()) == 1/* 暂停了 */) {
				// Log.info(args.get(0));
				Constants.iv(element_act, self[0], delta);
				topGroup.act(delta);
			} else {
				Constants.iv(super_act, self[0], delta);
			}
		};
		Lambda lambda = myClass.addLambda(floatc, Floatc.class, "get", "(F)V");

		myClass.setFunc("act", cfw -> {
			myClass.execLambda(lambda, () -> {
				cfw.add(ByteCode.FLOAD_1); // load delta
			});
			cfw.add(ByteCode.RETURN);
			return 2; // this + parma(delta)
		}, Modifier.PUBLIC, void.class, float.class);
		if (!OS.isAndroid) myClass.setFunc("<init>", (Func2) null, Modifier.PUBLIC, void.class, Scene.class);
		forNameOrAddLoader(superClass);

		Class<Group> newClas = (Class<Group>) myClass.define();
		Group newGroup = CatchSR.apply(() ->
		 CatchSR.of(() -> newClas.getDeclaredConstructor(Scene.class).newInstance(Core.scene))
			.get(() -> UNSAFE.allocateInstance(newClas))
		);
		self[0] = newGroup;
		Tools.clone(Core.scene.root, newGroup, Group.class, null);
		FieldUtils.setValue(Core.scene, Scene.class, "root", newGroup, Group.class);

		pauseMap = json.fromJson(ObjectIntMap.class, Class.class, pause.data().toString());
		hookUpdate(Core.app.getListeners());
	}

	static void hookUpdate(Seq<ApplicationListener> appListeners) {
		// 使用asm将侦听器替换
		appListeners.replace(source -> {
			var superClass = source.getClass();
			if (superClass.getSimpleName().endsWith(SUFFIX)) return source;
			if (source instanceof ApplicationCore core) {
				ApplicationListener[]    array   = Reflect.get(ApplicationCore.class, core, "modules");
				Seq<ApplicationListener> objects = new Seq<>(array);
				objects.items = array;
				hookUpdate(objects);
				return core;
			}
			pauseMap.put(superClass, 0);
			// 使mod-tools继续运行
			if (source == Vars.ui) return source;
			var myClass = new MyClass<>(superClass.getName() + SUFFIX, superClass);
			Lambda lambda = myClass.addLambda(() -> pauseMap.get(superClass) == 1, Boolp.class, "get", "()Z");
			myClass.setFunc("update", cfw -> {
				myClass.execLambda(lambda, null);
				// 判断是否暂停
				int i = cfw.acquireLabel();
				cfw.add(ByteCode.IFNE, i);
				// super.update();
				cfw.add(ByteCode.ALOAD_0); // load this
				cfw.addInvoke(ByteCode.INVOKESPECIAL, myClass.superName, "update", "()V");
				cfw.markLabel(i);
				cfw.add(ByteCode.RETURN);
				return 1; // this
			}, Modifier.PUBLIC, void.class);
			var clazz = myClass.define();
			var obj   = UNSAFE.allocateInstance(clazz);
			Tools.clone(source, obj, superClass, null);
			return obj;
		});
	}
	private static Lookup newLookup(Class<?> lookupClass)
	 throws NoSuchMethodException, InstantiationException, IllegalAccessException, InvocationTargetException {
		Constructor<Lookup> constructor = Lookup.class.getDeclaredConstructor(Class.class);
		constructor.setAccessible(true);
		return constructor.newInstance(lookupClass);
	}
	public static ObjectIntMap<Class<?>> pauseMap;

	static HKeyCode pauseKeyCode = HKeyCode.data.dynamicKeyCode("pauseAct", () -> new HKeyCode(KeyCode.f7).ctrl())
	 .applyToScene(true, () -> {
		 pauseMap.put(Vars.ui.getClass(), pauseMap.get(Vars.ui.getClass()) == 1 ? 0 : 1);
	 });
}