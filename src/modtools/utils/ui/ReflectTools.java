package modtools.utils.ui;

import arc.func.*;
import arc.graphics.Color;
import arc.scene.Element;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.Table;
import arc.util.*;
import arc.util.pooling.Pool.Poolable;
import arc.util.serialization.Json;
import ihope_lib.MyReflect;
import mindustry.ui.Styles;
import modtools.annotations.CopyMethodFrom;
import modtools.events.E_JSFunc;
import modtools.ui.*;
import modtools.ui.comp.input.MyLabel;
import modtools.utils.*;
import modtools.utils.JSFunc.JColor;

import java.lang.reflect.*;
import java.util.StringJoiner;

import static modtools.ui.effect.HopeFx.changedFx;

public interface ReflectTools {
	static void setAccessible(AccessibleObject object) {
		try {
			MyReflect.setOverride(object);
		} catch (Throwable ignored) { }
	}
	static String getName(Class<?> clazz) {
		if (clazz == null) throw new IllegalArgumentException("clazz is null");
		if (clazz.isAnonymousClass()) {
			return "[Anonymous]:" + getName(clazz.getSuperclass());
		}
		return getGenericString(clazz);
	}
	/** 找到第一个非匿名超类的simpleName */
	static String getSimpleNameNotAnonymous(Class<?> clazz) {
		while (clazz.getSimpleName().isEmpty() && clazz != Element.class) {
			clazz = clazz.getSuperclass();
		}
		return clazz.getSimpleName();
	}


	// ---Builder-----

	static Prov<String> makeDetails(Class<?> cls, Type type) {
		return cls.isPrimitive() || type == null ? null : type::getTypeName;
	}
	static MyLabel makeGenericType(Class<?> cls, Type genericType) {
		return makeGenericType(() -> getGenericString(cls, genericType), cls.isPrimitive() ? null : makeDetails(cls, genericType));
	}
	static MyLabel makeGenericType(Prov<String> type, Prov<String> details) {
		MyLabel label = new MyLabel(type.get(), HopeStyles.defaultLabel);
		label.color.set(JColor.c_type);
		EventHelper.doubleClick(label, null, details == null ? null : () -> {
			changedFx(label);
			label.setText(label.getText().toString().equals(type.get())
			 ? details.get() : type.get());
		});
		return label;
	}

	static String getGenericString(Class<?> type) {
		return getGenericString(type, null);
	}
	static String getGenericString(Class<?> type, Type genericType) {
		if (!E_JSFunc.display_generic.enabled()) return type.getSimpleName();
		StringBuilder sb         = new StringBuilder();
		String        simpleName;
		int           arrayDepth = 0;
		while (type.isArray()) {
			arrayDepth++;
			type = type.getComponentType();
		}
		if (type.isAnonymousClass()) {
			simpleName = type.getName();
			simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1); // strip the package name
		} else simpleName = type.getSimpleName();

		if (genericType != null) {
			sb.append(getGenericSimpleTypeName(genericType));
		} else {
			sb.append(simpleName);
			TypeVariable<?>[] typeparms = type.getTypeParameters();
			if (typeparms.length > 0) {
				StringJoiner sj = new StringJoiner(",", "<", ">");
				for (TypeVariable<?> typeparm : typeparms) {
					sj.add(typeparm.getName());
				}
				sb.append(sj);
			}
		}

		sb.append(StringUtils.repeat("[]", arrayDepth));

		return sb.toString();
	}

	/** @see Json#getElementType(Field, int) */
	@CopyMethodFrom(method = "arc.util.serialization.Json#getElementType(Field, int)")
	static Class<?> getElementType(Field field, int index) {
		return null;
	}

	static String getGenericSimpleTypeName(Type[] args) {
		if (args.length == 0) return "";
		StringJoiner sj = new StringJoiner(", ", "<", ">");
		for (Type arg : args) {
			sj.add(getGenericSimpleTypeName(arg));
		}
		return sj.toString();
	}

	static String getGenericSimpleTypeName(Type type) {
		if (type instanceof Class) return ((Class<?>) type).getSimpleName();

		StringBuilder sb = new StringBuilder();
		if (type instanceof ParameterizedType ptype) {
			sb.append(((Class<?>) ptype.getRawType()).getSimpleName());
			sb.append(getGenericSimpleTypeName(ptype.getActualTypeArguments()));
		} else {
			sb.append(type.getTypeName());
		}

		return sb.toString();
	}

	// ---reflection getter------

	/**
	 * 生成反射调用
	 * @see Class#getDeclaredConstructor(Class[])
	 * @see Class#getDeclaredMethod(String, Class[])
	 */
	static void copyExecutableReflection(Executable m) {
		StringBuffer sb            = new StringBuffer();
		Class<?>     dcl           = m.getDeclaringClass();
		String       typeName      = m.getClass().getSimpleName();
		char         c             = Character.toLowerCase(typeName.charAt(0));
		boolean      isConstructor = m instanceof Constructor;

		sb.append(typeName);
		if (isConstructor) sb.append("<?>");
		sb.append(" ").append(c)
		 .append(" = ");
		sb.append(getClassString0(dcl));
		sb.append(".getDeclared").append(typeName).append('(');
		if (!isConstructor) sb.append('"').append(m.getName()).append('"');
		for (Class<?> type : m.getParameterTypes()) {
			if (!isConstructor) sb.append(", ");
			sb.append(getClassString0(type));
		}
		sb.append(");");
		JSFunc.copyText(sb);
	}
	/**
	 * 生成反射调用Field field = %type%.class.getDeclaredField("%name%");
	 * @see Class#getDeclaredField(String)
	 */
	static void copyFieldReflection(Field field) {
		StringBuilder sb = new StringBuilder();
		sb.append("Field field = ");
		sb.append(getClassString0(field.getDeclaringClass()))
		 .append(".getDeclaredField(");
		sb.append("\"").append(field.getName()).append("\"");
		sb.append(");");
		JSFunc.copyText(sb);
	}
	/**
	 * 生成反射调用T val = Reflect.get(clazz[, obj], "%name%");
	 * @see Class#getDeclaredField(String)
	 * @see Reflect#get(Class, Object, String) Reflect.get(Class<?> type, Object object, String name)
	 */
	static void copyFieldArcReflection(Field field) {
		boolean isStatic = Modifier.isStatic(field.getModifiers());

		StringBuilder sb = new StringBuilder();
		sb.append("T val = ").append("Reflect.get(");
		sb.append(getClassString0(field.getDeclaringClass())).append(", ");
		sb.append(isStatic ? null : "obj").append(", ");
		sb.append("\"").append(field.getName()).append("\"");
		sb.append(");");
		JSFunc.copyText(sb);
	}
	/**
	 * 生成arc的{@link Reflect 反射}调用
	 * @see Reflect#invoke(Class, Object, String, Object[], Class[]) Reflect.invoke(Class<?> type, Object object, String name, Object[] args, Class<?>... parameterTypes)
	 */
	static void copyExecutableArcReflection(Executable m) {
		StringBuffer sb       = new StringBuffer();
		Class<?>     dcl      = m.getDeclaringClass();
		String       typeName = m.getClass().getSimpleName();
		char         c        = Character.toLowerCase(typeName.charAt(0));
		boolean      isStatic = Modifier.isStatic(m.getModifiers());

		sb.append("T val = Reflect.invoke(");
		sb.append(getClassString0(dcl)).append(", ");
		sb.append(isStatic ? null : "object").append(", ")
		 .append("\"").append(m.getName())
		 .append("\", ");
		if (m.getParameterTypes().length == 0) {
			sb.append("null");
		} else {
			sb.append("new Object[]{");
			Class<?>[] parameterTypes = m.getParameterTypes();
			for (int i = 0, parameterTypesLength = parameterTypes.length; i < parameterTypesLength; i++) {
				sb.append("p").append(i);
				if (i != parameterTypesLength - 1) sb.append(", ");
			}
			sb.append("}");
			for (Class<?> type : m.getParameterTypes()) {
				sb.append(", ");
				sb.append(getClassString0(type));
			}
		}
		sb.append(");");
		JSFunc.copyText(sb);
	}

	/** 返回类的java访问方法 */
	private static String getClassString0(Class<?> dcl) {
		return Modifier.isPublic(dcl.getModifiers())
		 ? dcl.getSimpleName() + ".class" : "Class.forName(\"" + dcl.getName() + "\")";
	}

	// modifier builder

	static void addCodedBtn(
	 Table t, String text, int cols,
	 Intc cons, Intp prov, MarkedCode... seq) {
		t.button("", HopeStyles.flatt, null).with(tbtn -> {
			tbtn.clicked(() -> IntUI.showSelectTable(tbtn, (p, _, _) -> {
				buildModifier(p, cols, cons, prov, seq);
			}, false, Align.center));
			Table fill = tbtn.fill();
			fill.top().add(text, 0.6f).growX().labelAlign(Align.left).color(Color.lightGray);
			tbtn.getCell(fill).colspan(0);
			tbtn.getCells().reverse();
		}).size(85, 32).update(b -> b.setText(String.format("%X", (short) prov.get())));
	}
	static void buildModifier(Table p, int cols, Intc cons, Intp prov, MarkedCode... seq) {
		p.button("All", HopeStyles.flatToggleMenut,
			() -> cons.get(prov.get() != -1 ? -1 : 0))
		 .growX().colspan(4).height(42)
		 .update(b -> b.setChecked(prov.get() == -1))
		 .row();
		int c = 0;
		for (var value : seq) {
			int      bit      = 1 << value.code();
			Runnable runnable = () -> cons.get(prov.get() ^ bit);
			Drawable icon     = value.icon();
			(icon == null ? p.button(value.name(), Styles.flatToggleMenut, runnable)
			 : p.button(value.name(), value.icon(), Styles.flatToggleMenut, 24, runnable))
			 .size(120, 42)
			 .update(b -> b.setChecked((prov.get() & bit) != 0));
			if (++c % cols == 0) p.row();
		}
	}
	/** 具有code的接口 */
	interface MarkedCode {
		int code();
		String name();
		default Drawable icon() {
			return null;
		}
	}
	class ClassMember implements Member, Poolable {
		private Class<?> cl;
		public ClassMember() { }
		public ClassMember init(Class<?> cl) {
			this.cl = cl;
			return this;
		}
		public Class<?> getDeclaringClass() {
			return cl.getDeclaringClass();
		}
		public String getName() {
			return cl.getName();
		}
		public int getModifiers() {
			return cl.getModifiers();
		}
		public boolean isSynthetic() {
			return cl.isSynthetic();
		}
		public void reset() {
			cl = Object.class;
		}
	}
}
