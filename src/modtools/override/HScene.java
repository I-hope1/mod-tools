package modtools.override;

import arc.Core;
import arc.func.*;
import arc.input.KeyCode;
import arc.scene.*;
import modtools.Constants;
import modtools.ui.control.HKeyCode;
import modtools.utils.ByteCodeTools.*;
import modtools.utils.ByteCodeTools.MyClass.Lambda;
import modtools.utils.Tools;
import modtools.utils.reflect.FieldUtils;
import rhino.classfile.ByteCode;

import java.lang.invoke.*;
import java.lang.reflect.Modifier;

import static ihope_lib.MyReflect.lookup;
import static modtools.override.ForRhino.forNameOrAddLoader;
import static modtools.ui.IntUI.topGroup;

public class HScene {
	public static final String SUFFIX = "-$HScene";

	@Exclude
	public static void load() throws Exception {
		Class<? extends Group> superClass = Core.scene.root.getClass();
		if (superClass.getName().endsWith(SUFFIX)) return;
		MyClass<? extends Group> myClass = new MyClass<>(superClass.getName() + SUFFIX, superClass);

		MethodHandle element_act = lookup.unreflectSpecial(Element.class.getDeclaredMethod("act", float.class), Element.class);
		MethodHandle super_act   = lookup.unreflectSpecial(superClass.getMethod("act", float.class), superClass);
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
		myClass.setFunc("<init>", (Func2) null, Modifier.PUBLIC, void.class, Scene.class);
		forNameOrAddLoader(superClass);

		Group newGroup = myClass.define().getDeclaredConstructor(Scene.class).newInstance(Core.scene);
		self[0] = newGroup;
		Tools.clone(Core.scene.root, newGroup, Group.class, null);
		FieldUtils.setValue(Core.scene, Scene.class, "root", newGroup, Group.class);
	}
	static boolean  pauseAct     = false;
	static HKeyCode pauseKeyCode = HKeyCode.data.dynamicKeyCode("pauseAct", () -> new HKeyCode(KeyCode.f7))
	 .applyToScene(true, () -> {
		 pauseAct = !pauseAct;
	 });
}
