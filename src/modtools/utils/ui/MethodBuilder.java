package modtools.utils.ui;

import arc.graphics.Color;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Cell;
import arc.util.*;
import modtools.ui.IntUI;
import modtools.ui.comp.limit.LimitTable;
import modtools.ui.comp.utils.*;
import modtools.utils.*;
import modtools.utils.search.FilterTable;
import rhino.*;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.util.*;

import static modtools.ui.HopeStyles.defaultLabel;
import static modtools.ui.effect.HopeFx.changedFx;
import static modtools.utils.JSFunc.JColor;
import static modtools.utils.ui.ShowInfoWindow.keyword;

public interface MethodBuilder {
	static Object invokeForMethod(Object o, Method m, ReflectValueLabel l, NativeArray args0,
	                              FuncT func) throws Throwable {
		Object[] args = convertArgs(args0, m.getParameterTypes());
		if (l.isStatic || o != null) return func.get(args);
		throw new NullPointerException("'obj' is null.");
	}
	static Object invokeForHandle(MethodHandle handle, Object[] arr) throws Throwable {
		return handle.invokeWithArguments(convertArgs(arr, handle.type().parameterArray()));
	}

	static Object[] convertArgs(NativeArray arr, Class<?>[] types) {
		return convertArgs(arr.toArray(), types);
	}
	static Object[] convertArgs(Object[] arr, Class<?>[] types) {
		Iterator<Class<?>> iterator = Arrays.stream(types).iterator();
		return Arrays.stream(arr)
		 .map(a -> JavaAdapter.convertResult(a, iterator.next()))
		 .toArray();
	}

	/** value: {@value ACCESS_MODIFIERS */
	int ACCESS_MODIFIERS =
	 Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE;

	/**
	 * copy from {@link Executable#sharedToGenericString(int, boolean)}
	 * @see Executable#sharedToGenericString(int, boolean)
	 */
	static StringBuilder buildExecutableModifier(Executable m) {
		int     mod       = m.getModifiers() & (m instanceof Method ? Modifier.methodModifiers() : Modifier.constructorModifiers());
		boolean isDefault = m instanceof Method && ((Method) m).isDefault();

		StringBuilder sb = new StringBuilder();
		if (mod != 0 && !isDefault) {
			sb.append(Modifier.toString(mod)).append(' ');
		} else {
			int access_mod = mod & ACCESS_MODIFIERS;
			if (access_mod != 0)
				sb.append(Modifier.toString(access_mod)).append(' ');
			if (isDefault)
				sb.append("default ");
			mod = (mod & ~ACCESS_MODIFIERS);
			if (mod != 0)
				sb.append(Modifier.toString(mod)).append(' ');
		}
		return sb;
	}
	int argKey = 0b01,
	 throwKey  = 0b10;

	static LimitTable
	buildArgsAndExceptions(Executable executable) {
		Class<?>[] args = executable.getParameterTypes(),
		 exceptions = executable.getExceptionTypes();
		Type[] genericArgs = executable.getGenericParameterTypes();

		/* 0b01 -> args
		 * 0b10 -> throws */
		var table = new FilterTable<Integer>() {
			int name = ~throwKey;
			public <T extends Element> Cell<T> add(T element) {
				return super.add(element).labelAlign(Align.topLeft).style(defaultLabel);
			}
		};
		if (args.length > 4) table.name ^= argKey;
		table.addIntUpdateListener(() -> table.name);
		table.top().defaults().growY().top();
		table.bind(argKey);
		table.add("(").color(Color.lightGray);
		if (args.length > 1) {
			table.unbind();
			table.add("<args>").fontScale(0.8f)
			 .color(Color.gray).with(t ->
				EventHelper.doubleClick(t, null, () -> {
					changedFx(table);
					table.name ^= argKey;
				}));
			table.bind(argKey);
		}

		for (int i = 0, length = args.length; i < length; i++) {
			var ptype = args[i];
			table.add(ReflectTools.makeGenericType(ptype, Tools.getOrNull(genericArgs, i)))
			 .color(Tmp.c1.set(JColor.c_type));
			if (i != length - 1) {
				table.add(", ");
			}
		}
		table.add(")").color(Color.lightGray);
		table.unbind();

		if (exceptions.length > 0) {
			Type[] genericExceptions = executable.getGenericExceptionTypes();
			keyword(table, " throws ").touchable(Touchable.enabled).fontScale(0.9f)
			 .with(t -> EventHelper.doubleClick(t, null, () -> {
				 changedFx(table);
				 table.name ^= throwKey;
			 }));
			table.bind(throwKey);
			for (int i = 0, length = exceptions.length; i < length; i++) {
				var eType = exceptions[i];
				table.add(ReflectTools.makeGenericType(eType, genericExceptions[i]))
				 .color(Tmp.c1.set(JColor.c_type));
				if (i != length - 1) {
					table.add(", ");
				}
			}
			table.unbind();
		}
		table.layout();
		return table;
	}

	static void dealInvokeResult(Object res, Cell<?> cell, ValueLabel l) {
		l.setVal(res);
		if (l.val instanceof Color) {
			cell.setElement(new Image(IntUI.whiteui.tint((Color) l.val))).size(32).padRight(4).touchable(Touchable.disabled);
		}
	}


	interface FuncT<R> {
		R get(Object[] args) throws Throwable;
	}

}
