package modtools.override;

import arc.*;
import arc.func.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.*;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.core.*;
import modtools.content.debug.Pause;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.ui.IntUI;
import modtools.ui.comp.Window;
import modtools.ui.control.HKeyCode;
import modtools.utils.ByteCodeTools.MyClass.Lambda;
import modtools.utils.Tools;
import modtools.utils.reflect.*;
import rhino.classfile.ByteCode;

import java.lang.reflect.*;

import static modtools.IntVars.json;
import static modtools.ui.IntUI.topGroup;
import static modtools.utils.ByteCodeTools.*;
import static rhino.classfile.ByteCode.*;

public class HScene {
	public static final String SUFFIX = "-$H";

	@Exclude
	public static void load(Pause pause) throws Exception {
		Class<? extends Group> superClass = Core.scene.root.getClass();
		if (superClass.getName().endsWith(SUFFIX)) return;
		var sceneClass0 = new MyClass<>(superClass, SUFFIX);
		var sceneClass  = new MyClass<>(sceneClass0.define(), "i");

		Floatc floatc      = delta -> topGroup.act(delta);
		Lambda actTopGroup = sceneClass.addLambda(floatc, Floatc.class, "get", "(F)V");
		Lambda boolp       = sceneClass.addLambda(() -> pauseMap.get(Vars.ui.getClass(), 0) > 0/* 暂停了 */, Boolp.class, "get", "()Z");

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
		// sceneClass.writeTo(Vars.tmpDirectory);

		Class<Group> newClas = (Class<Group>) sceneClass.define();
		if (OS.isAndroid) {
			HopeReflect.changeClass(Core.scene.root, newClas);
		} else {
			Group newGroup = UNSAFE.allocateInstance(newClas);
			Tools.clone(Core.scene.root, newGroup, superClass, null);
			SnapshotSeq<Element> children = Core.scene.root.getChildren();
			children.begin();
			children.each(e -> e.parent = newGroup);
			children.end();
			// FieldUtils.setValue(Core.scene.root, Group.class, "children",
			//  new SnapshotSeq<>(true, 4, Element.class), Seq.class);
			// Core.scene.root = newGroup;
			FieldUtils.setValue(Core.scene, Scene.class, "root", newGroup, Group.class);
		}

		pauseMap = json.fromJson(ObjectFloatMap.class, Class.class, pause.data().toString());

		hookUpdate(Core.app.getListeners());
	}

	static void hookUpdate(Seq<ApplicationListener> appListeners) {
		// 使用asm将侦听器替换
		appListeners.replace(source -> {
			if (source instanceof NetServer) return source;
			if (source instanceof NetClient) return source;

			var _1         = (Object) source;
			var superClass = _1.getClass(); // android 上由 shadow$_klass_ 决定
			try {
				if (Modifier.isFinal(superClass.getMethod("update").getModifiers())) {
					pauseMap.remove(superClass, 0);
					return source;
				}
			} catch (NoSuchMethodException e) {
				pauseMap.remove(superClass, 0);
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
			//region asm
			HopeReflect.setPublic(superClass, Class.class);
			var myClass = new MyClass<>(superClass, SUFFIX);
			Lambda lambda = myClass.addLambda(() -> {
				return pauseMap.increment(superClass, 0, -Time.delta) >= 0;
			}, Boolp.class, "get", "()Z");
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
			if (OS.isAndroid) {
				HopeReflect.changeClass(source, clazz);
				return source;
			}
			//endregion

			var newVal = Tools.newInstance(source, clazz);
			Log.info("Source: " + source + ";Val" + newVal);

			// 找出primitive的field，并随Tools.TASKS不断替换
			/* for (Field field : superClass.getDeclaredFields()) {
				if (field.getType().isPrimitive() && !Modifier.isStatic(field.getModifiers())) {
					field.setAccessible(true);
					Tools.TASKS.add(() -> Tools.copyValue(field, newVal, source));
				}
			} */
			Cons<Class<?>> replace = lookupClass -> {
				// 查找Vars/Core中是否有对应的实例，如果有也替换掉
				for (Field field : lookupClass.getFields()) {
					try {
						field.setAccessible(true);
						if (field.get(null) == source) {
							Log.info("Replace " + field.getName() + " in " + lookupClass.getSimpleName());
							FieldUtils.setValue(field, null, newVal);
						}
					} catch (IllegalAccessException e) {
						Log.err(e);
					}
				}
			};
			replace.get(Vars.class);
			replace.get(Core.class);
			return newVal;
		});
	}

	public static ObjectFloatMap<Class<?>> pauseMap;

	public static Window   lastInfo;
	static        HKeyCode pauseKeyCode = HKeyCode.data.dynamicKeyCode("pauseAct", () -> new HKeyCode(KeyCode.f7).ctrl())
	 .applyToScene(true, () -> {
		 float value = Mathf.equal(pauseMap.get(Vars.ui.getClass(), 0), Float.POSITIVE_INFINITY) ? 0 : Float.POSITIVE_INFINITY;
		 if (lastInfo != null) lastInfo.hide();
		 lastInfo = IntUI.showInfoFade(value == Float.POSITIVE_INFINITY ? "UI Pause" : "UI Resume");
		 pauseMap.put(Vars.ui.getClass(), value);
	 });
}