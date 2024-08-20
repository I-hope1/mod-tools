package modtools.override;

import arc.Core;
import arc.func.*;
import arc.input.KeyCode;
import arc.scene.*;
import arc.util.OS;
import modtools.Constants;
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
import static modtools.override.ForRhino.forNameOrAddLoader;
import static modtools.ui.IntUI.topGroup;

public class HScene {
	public static final String SUFFIX = "-$H";

	@Exclude
	public static void load() throws Exception {
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
			if (pauseAct) {
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
	}
	private static Lookup newLookup(Class<?> lookupClass)
	 throws NoSuchMethodException, InstantiationException, IllegalAccessException, InvocationTargetException {
		Constructor<Lookup> constructor = Lookup.class.getDeclaredConstructor(Class.class);
		constructor.setAccessible(true);
		return constructor.newInstance(lookupClass);
	}
	static boolean  pauseAct     = false;
	static HKeyCode pauseKeyCode = HKeyCode.data.dynamicKeyCode("pauseAct", () -> new HKeyCode(KeyCode.f7))
	 .applyToScene(true, () -> {
		 pauseAct = !pauseAct;
	 });
}
