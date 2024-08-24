package modtools.override;

import arc.*;
import arc.func.*;
import arc.input.KeyCode;
import arc.scene.*;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import modtools.content.debug.Pause;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.ui.IntUI;
import modtools.ui.comp.Window;
import modtools.ui.control.HKeyCode;
import modtools.utils.ByteCodeTools.MyClass.Lambda;
import modtools.utils.*;
import modtools.utils.reflect.*;
import rhino.classfile.ByteCode;

import java.lang.reflect.Modifier;

import static modtools.IntVars.json;
import static modtools.override.ForRhino.forNameOrAddLoader;
import static modtools.ui.IntUI.topGroup;
import static modtools.utils.ByteCodeTools.*;
import static rhino.classfile.ByteCode.*;

public class HScene {
	public static final String SUFFIX = "-$H";

	@Exclude
	public static void load(Pause pause) throws Exception {
		Class<? extends Group> superClass = Core.scene.root.getClass();
		if (superClass.getName().endsWith(SUFFIX)) return;
		MyClass<? extends Group> sceneClass = new MyClass<>(superClass.getName() + SUFFIX, superClass);

		Floatc floatc      = delta -> topGroup.act(delta);
		Lambda actTopGroup = sceneClass.addLambda(floatc, Floatc.class, "get", "(F)V");
		Lambda boolp       = sceneClass.addLambda(() -> pauseMap.get(Vars.ui.getClass()) == 1/* 暂停了 */, Boolp.class, "get", "()Z");

		sceneClass.setFunc("act", cfw -> {
			sceneClass.execLambda(boolp, null);
			// if (boolp.get()) { Element.this.super(delta); topGroup.act(delta); }
			// else Group.this.super(delta);
			int label = cfw.acquireLabel();
			cfw.add(ByteCode.IFEQ, label); // 判断是否暂停
			sceneClass.execLambda(actTopGroup, () -> {
				cfw.add(FLOAD_1); // load delta
			});
			{// Element.super.act(delta);
				// cfw.add(ALOAD_0); // load this
				// cfw.add(FLOAD_1); // load delta
				// cfw.addInvoke(INVOKESPECIAL, nativeName(Element.class), "act", "(F)V");
				cfw.add(RETURN);
			}

			cfw.markLabel(label);
			{// Group.super.act(delta);
				cfw.add(ALOAD_0); // load this
				cfw.add(FLOAD_1); // load delta
				cfw.addInvoke(INVOKESPECIAL, nativeName(Group.class), "act", "(F)V");
				cfw.add(RETURN);
			}
			return 2; // this + parma(delta)
		}, Modifier.PUBLIC, void.class, float.class);
		// if (!OS.isAndroid) myClass.setFunc("<init>", (Func2) null, Modifier.PUBLIC, void.class, Scene.class);
		forNameOrAddLoader(superClass);
		// sceneClass.writeTo(Vars.tmpDirectory);

		Class<Group> newClas  = (Class<Group>) sceneClass.define();
		Group        newGroup = UNSAFE.allocateInstance(newClas);
		Tools.clone(Core.scene.root, newGroup, superClass, null);
		SnapshotSeq<Element> children = Core.scene.root.getChildren();
		children.begin();
		children.each(e -> e.parent = newGroup);
		children.end();
		// FieldUtils.setValue(Core.scene.root, Group.class, "children",
		//  new SnapshotSeq<>(true, 4, Element.class), Seq.class);
		// Core.scene.root = newGroup;
		FieldUtils.setValue(Core.scene, Scene.class, "root", newGroup, Group.class);

		pauseMap = json.fromJson(ObjectIntMap.class, Class.class, pause.data().toString());
		// hookUpdate(Core.app.getListeners());
	}

	static void hookUpdate(Seq<ApplicationListener> appListeners) {
		// 使用asm将侦听器替换
		appListeners.replace(source -> {
			var _1         = (Object) source;
			var superClass = _1.getClass(); // android上好奇葩
			try {
				if (Modifier.isFinal(superClass.getMethod("update").getModifiers())) {
					pauseMap.remove(superClass);
					return source;
				}
			} catch (NoSuchMethodException e) {
				pauseMap.remove(superClass);
				return source;
			}
			if (superClass.getSimpleName().endsWith(SUFFIX)) return source;
			pauseMap.put(superClass, 0);
			// 使mod-tools继续运行
			if (source == Vars.ui) return source;
			if (source instanceof ApplicationCore core) {
				ApplicationListener[]    array   = Reflect.get(ApplicationCore.class, core, "modules");
				Seq<ApplicationListener> objects = new Seq<>(array);
				objects.items = array;
				hookUpdate(objects);
				return core;
			}
			HopeReflect.setPublic(superClass, Class.class);
			var    myClass = new MyClass<>(superClass.getName() + SUFFIX, superClass);
			Lambda lambda  = myClass.addLambda(() -> pauseMap.get(superClass) == 1, Boolp.class, "get", "()Z");
			myClass.setFunc("update", cfw -> {
				myClass.execLambda(lambda, null);
				// 判断是否暂停
				int i = cfw.acquireLabel();
				cfw.add(IFNE, i);
				// super.update();
				cfw.add(ALOAD_0); // load this
				cfw.addInvoke(INVOKESPECIAL, myClass.superName, "update", "()V");
				cfw.markLabel(i);
				cfw.add(RETURN);
				return 1; // this
			}, Modifier.PUBLIC, void.class);
			var clazz = myClass.define();
			var obj   = UNSAFE.allocateInstance(clazz);
			Tools.clone(source, obj, superClass, null);
			return (ApplicationListener) obj;
		});
	}

	public static ObjectIntMap<Class<?>> pauseMap;

	public static Window lastInfo;
	static HKeyCode      pauseKeyCode = HKeyCode.data.dynamicKeyCode("pauseAct", () -> new HKeyCode(KeyCode.f7).ctrl())
	 .applyToScene(true, () -> {
		 int value = pauseMap.get(Vars.ui.getClass()) == 1 ? 0 : 1;
		 if (lastInfo != null) lastInfo.hide();
		 lastInfo = IntUI.showInfoFade(value == 1 ? "UI Pause" : "UI Resume");
		 pauseMap.put(Vars.ui.getClass(), value);
	 });
}