package modtools.utils.ui;

import arc.func.Prov;
import arc.util.Reflect;
import ihope_lib.MyReflect;
import modtools.events.E_JSFunc;
import modtools.ui.*;
import modtools.ui.components.input.MyLabel;
import modtools.utils.JSFunc;

import java.lang.reflect.*;
import java.util.StringJoiner;

import static modtools.utils.ui.ShowInfoWindow.applyChangedFx;

public interface ReflectTools {
	static void setAccessible(AccessibleObject object) {
		try {
			MyReflect.setOverride(object);
		} catch (Throwable ignored) {}
	}
	static Prov<String> makeDetails(Class<?> cls, Type type) {
		return cls.isPrimitive() || type == null ? null : type::getTypeName;
	}
	static MyLabel makeGenericType(Class<?> type, Prov<String> details) {
		return makeGenericType(() -> getGenericString(type), type.isPrimitive() ? null : details);
	}
	static MyLabel makeGenericType(Prov<String> type, Prov<String> details) {
		MyLabel label = new MyLabel(type.get(), HopeStyles.MOMO_LabelStyle);
		label.color.set(JSFunc.c_type);
		IntUI.doubleClick(label, null, details == null ? null : () -> {
			applyChangedFx(label);
			label.setText(label.getText().toString().equals(type.get())
			 ? details.get() : type.get());
		});
		return label;
	}


	static String getGenericString(Class<?> cls) {
		if (!E_JSFunc.display_generic.enabled()) return cls.getSimpleName();
		StringBuilder sb         = new StringBuilder();
		String        simpleName;
		int           arrayDepth = 0;
		while (cls.isArray()) {
			arrayDepth++;
			cls = cls.getComponentType();
		}
		if (cls.isAnonymousClass()) {
			simpleName = cls.getName();
			simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1); // strip the package name
		} else simpleName = cls.getSimpleName();
		sb.append(simpleName);
		// if (!cls.isPrimitive()) {
		TypeVariable<?>[] typeparms = cls.getTypeParameters();
		if (typeparms.length > 0) {
			StringJoiner sj = new StringJoiner(",", "<", ">");
			for (TypeVariable<?> typeparm : typeparms) {
				sj.add(typeparm.getTypeName());
			}
			sb.append(sj);
		}
		// }

		sb.append("[]".repeat(arrayDepth));

		return sb.toString();
	}

	static String getName(Class<?> clazz) {
		if (clazz == null) throw new IllegalArgumentException("clazz is null");
		if (clazz.isAnonymousClass()) {
			return "[Anonymous]:" + getGenericString(clazz);
		}
		return getGenericString(clazz);
	}

	static Member wrapMember(Class<?> cl) {
		return new Member() {
			public Class<?> getDeclaringClass() {
				return null;
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
		};
	}

	/* -------reflection getter */

	/**
	 * 生成反射调用
	 * @see Class#getDeclaredConstructor(Class[])
	 * @see Class#getDeclaredMethod(String, Class[])
	 */
	static void copyExecutableReflection(Executable m) {
		StringBuffer sb       = new StringBuffer();
		Class<?>     dcl      = m.getDeclaringClass();
		String       typeName = m.getClass().getSimpleName();
		char         c        = Character.toLowerCase(typeName.charAt(0));

		sb.append(typeName);
		if (c == 'c') sb.append("<?>");
		sb.append(" ").append(c)
		 .append(" = ");
		sb.append(getClassString0(dcl));
		sb.append(".getDeclared").append(typeName)
		 .append("(\"").append(m.getName())
		 .append('"');
		for (Class<?> type : m.getParameterTypes()) {
			sb.append(", ");
			sb.append(getClassString0(type));
		}
		sb.append(");");
		JSFunc.copyText(sb);
	}
	/**
	 * 生成反射调用
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
	 * 生成反射调用
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
}
