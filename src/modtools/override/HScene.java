package modtools.override;

import arc.Core;
import arc.func.Func2;
import arc.input.KeyCode;
import arc.scene.*;
import arc.util.Log;
import modtools.Constants;
import modtools.ui.control.HKeyCode;
import modtools.utils.ByteCodeTools.*;
import modtools.utils.Tools;
import modtools.utils.reflect.FieldUtils;

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

		MethodHandle element_act = lookup.findSpecial(Element.class, "act", MethodType.methodType(void.class, float.class), Element.class);
		MethodHandle super_act   = lookup.findSpecial(Group.class, "act", MethodType.methodType(void.class, float.class), superClass);
		myClass.setFunc("act", (self, args) -> {
			if (pauseAct) {
				Log.info(args.get(0));
				Constants.iv(element_act, self, args.get(0));
				topGroup.act((Float) args.get(0));
			} else {
				Constants.iv(super_act, self, args.get(0));
			}
		}, Modifier.PUBLIC, float.class);
		myClass.setFunc("<init>", (Func2) null, Modifier.PUBLIC, void.class, Scene.class);
		forNameOrAddLoader(superClass);

		Group newGroup = myClass.define().getDeclaredConstructor(Scene.class).newInstance(Core.scene);
		Tools.clone(Core.scene.root, newGroup, Group.class, null);
		FieldUtils.setValue(Core.scene, Scene.class, "root", newGroup, Group.class);
	}
	static boolean  pauseAct     = false;
	static HKeyCode pauseKeyCode = HKeyCode.data.keyCode("pauseAct", () -> new HKeyCode(KeyCode.f7))
	 .applyToScene(true, () -> {
		 pauseAct = !pauseAct;
	 });
}
