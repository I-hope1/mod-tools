package modtools.utils;

import arc.files.Fi;
import arc.func.*;
import arc.struct.*;
import arc.util.*;
import hope_android.FieldUtils;
import mindustry.Vars;
import modtools.utils.reflect.IReflect;
import rhino.classfile.ClassFileWriter;

import java.io.FileOutputStream;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

import static ihope_lib.MyReflect.unsafe;
import static rhino.classfile.ByteCode.*;
import static rhino.classfile.ClassFileWriter.ACC_PUBLIC;

public class ByteCodeTools {
	/*public static <T> MyClass<T> newClass(String name, String superName) {
		return new MyClass<>(name, superName);
	}*/

	public static final String
	 functionsKey = "_KSINIA_Functions",
	 TMP_CLASS    = "__BYTE_Class";
	private static int lastID = 0;
	private static int nextID() {
		return lastID++;
	}


	public static class MyClass<T> {
		public final ClassFileWriter writer;
		public final String          adapterName, superName;
		final         Class<?>            superClass;
		private final ArrayList<Queue<?>> queues = new ArrayList<>();
		// private final ArrayList<ClassInfo> superFunctions = new ArrayList<>();

		public MyClass(String name, Class<T> superClass) {
			this.superClass = superClass;
			adapterName = name;
			superName = nativeName(superClass);
			writer = new ClassFileWriter(name, superName, TMP_CLASS + nextID());
		}

		public <V> void setFunc(String name, MyRun run, int flags, Class<V> returnType, Class<?>... args) {
			writer.startMethod(name, nativeMethod(returnType, args), (short) flags);
			writer.stopMethod((short) run.get(writer)); // this + args + var * 1
		}

		public <V> void setFunc(String name, Func2<T, ArrayList<Object>, Object> func2, int flags, boolean buildSuper,
														Class<V> returnType, Class<?>... args) {
			if (func2 == null) {
				writer.startMethod(name, nativeMethod(returnType, args), (short) flags);
				writer.addLoadThis();
				for (int i = 1; i <= args.length; i++) {
					writer.add(addLoad(args[i - 1]), i);
				}
				writer.addInvoke(INVOKESPECIAL, superName, name, nativeMethod(returnType, args));
				addCast(writer, returnType);
				// writer.add(ByteCode.CHECKCAST, nativeName(returnType));
				writer.add(buildReturn(returnType));
				writer.stopMethod((short) (args.length + 1));
				return;
			}
			String fieldName = functionsKey + "$" + nextID();
			short  max       = (short) (args.length + 1);
			int    v1        = max++, v2 = max++;
			queues.add(new Queue<>(fieldName, () -> func2, Func2.class));
			writer.addField(fieldName, typeToNative(Func2.class), (short) (Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL));
			writer.startMethod(name, nativeMethod(returnType, args), (short) flags);

			if (buildSuper) {
				writer.addLoadThis(); // this
				// args
				for (int i = 0; i < args.length; i++) {
					writer.add(addLoad(args[i]), i + 1);
					// addCast(writer, args[i]);
				}
				// super
				writer.addInvoke(INVOKESPECIAL, superName, name, nativeMethod(returnType, args));
				// 储存为v1
				if (returnType != void.class) writer.add(addStore(returnType), v1);
			}

			// new ArrayList(args.length)
			writer.add(NEW, nativeName(ArrayList.class));
			writer.add(DUP);
			writer.addPush(args.length + (buildSuper ? 1 : 0));
			writer.addInvoke(INVOKESPECIAL, nativeName(ArrayList.class), "<init>", nativeMethod(void.class, int.class));
			writer.add(ASTORE, v2);

			// 将参数储存 seq
			for (int i = 0; i < args.length; i++) {
				writer.add(ALOAD, v2); // list
				writer.add(addLoad(args[i]), i + 1);
				addBox(writer, args[i]);
				writer.addInvoke(INVOKEVIRTUAL, nativeName(ArrayList.class), "add", nativeMethod(boolean.class, Object.class));
			}
			// 将super的返回值存入seq
			if (buildSuper && returnType != void.class) {
				writer.add(ALOAD, v2); // list
				writer.add(addLoad(returnType), v1); // super return
				addBox(writer, returnType);
				// add
				writer.addInvoke(INVOKEVIRTUAL, nativeName(ArrayList.class), "add", nativeMethod(boolean.class, Object.class));
			}

			// 获取functionKey字段
			writer.add(GETSTATIC, adapterName, fieldName, typeToNative(Func2.class));

			writer.addLoadThis(); // this
			writer.add(ALOAD, v2); // seq

			// V get(args)
			writer.addInvoke(INVOKEINTERFACE, nativeName(Func2.class), "get", nativeMethod(Object.class, Object.class, Object.class));
			addCast(writer, returnType);
			// writer.add(ByteCode.CHECKCAST, nativeName(returnType));
			writer.add(buildReturn(returnType));

			writer.stopMethod(max); // this + args + var * 1
		}

		/*public <V> void setFunc(String name, arc.func.Func2 func2, int flags, Class<V> returnType, Class<?>... args) {
			setFunc(name, (a, b) -> func2.get(a, b), flags, false, returnType, args);
		}*/


		/**
		 * @param name       方法名
		 * @param flags      方法的修饰符
		 * @param returnType 方法的返回值
		 * @param args       方法的参数
		 **/
		public <V> void setFunc(String name, Func2<T, ArrayList<Object>, Object> func2, int flags, Class<V> returnType,
														Class<?>... args) {
			setFunc(name, func2, flags, false, returnType, args);
		}


		public void setFunc(String name, Cons2<T, ArrayList<Object>> cons2, int flags, Class<?>... args) {
			setFunc(name, (self, a) -> {
				cons2.get(self, a);
				return null;
			}, flags, false, void.class, args);
		}

		public void setFunc(String name, Cons2<T, ArrayList<Object>> cons2, int flags, boolean buildSuper,
												Class<?>... args) {
			setFunc(name, (self, a) -> {
				cons2.get(self, a);
				return null;
			}, flags, buildSuper, void.class, args);
		}

		private int addLoad(Class<?> type) {
			if (type == boolean.class) return ILOAD;
			else if (type == byte.class) return ILOAD;
			else if (type == char.class) return ILOAD;
			else if (type == short.class) return ILOAD;
			else if (type == int.class) return ILOAD;
			else if (type == float.class) return FLOAD;
			else if (type == long.class) return LLOAD;
			else if (type == double.class) return DLOAD;
			else return ALOAD;
		}

		private int addStore(Class<?> type) {
			if (type == boolean.class) return ISTORE;
			else if (type == byte.class) return ISTORE;
			else if (type == char.class) return ISTORE;
			else if (type == short.class) return ISTORE;
			else if (type == int.class) return ISTORE;
			else if (type == float.class) return FSTORE;
			else if (type == long.class) return LSTORE;
			else if (type == double.class) return DSTORE;
			else return ASTORE;
		}

		public void buildSuperFunc(String thisMethodName, String superMethodName, Class<?> returnType,
															 Class<?>... args) {
			writer.startMethod(thisMethodName, nativeMethod(returnType, ArrayList.class), (short) Modifier.PUBLIC);
			writer.addLoadThis(); // this
			for (int i = 0; i < args.length; i++) {
				writer.add(ALOAD_1);
				writer.addPush(i);
				writer.addInvoke(INVOKEVIRTUAL, nativeName(ArrayList.class), "get", nativeMethod(Object.class, int.class));
				addCast(writer, args[i]);
			}
			writer.addInvoke(INVOKESPECIAL, this.superName, superMethodName, nativeMethod(returnType, args));
			// addCast(returnType);
			writer.add(buildReturn(returnType));
			writer.stopMethod((short) 2); // this + args
		}

		public String buildSuperFunc(String methodName, Class<?> returnType, Class<?>... args) {
			String superMethodName = methodName + nextID();
			buildSuperFunc(superMethodName, methodName, returnType, args);
			return superMethodName;
		}

		public <K> void buildGetFieldFunc(String fieldName, String methodName, Class<K> fieldType) {
			writer.startMethod(methodName, nativeMethod(fieldType), (short) Modifier.PUBLIC);
			writer.addLoadThis();
			writer.add(GETFIELD, adapterName, fieldName, typeToNative(fieldType));
			writer.add(buildReturn(fieldType));
			writer.stopMethod((short) 1); // this
		}

		public void buildPutFieldFunc(String fieldName, String methodName, Class<?> fieldType) {
			writer.startMethod(methodName, nativeMethod(void.class, fieldType), (short) Modifier.PUBLIC);
			writer.addLoadThis();
			writer.add(addLoad(fieldType), 1); // arg1
			writer.add(PUTFIELD, adapterName, fieldName, typeToNative(fieldType));
			writer.add(RETURN);
			writer.stopMethod((short) 2); // this + arg1
		}


		public void setField(int flags, Class<?> type, String name) {
			setField(flags, type, name, null);
		}

		public <T2> void setField(int flags, Class<T2> type, String name, T2 val) {
			writer.addField(name, typeToNative(type), (short) flags);
			if (val != null) {
				if (!Modifier.isStatic(flags)) throw new IllegalArgumentException("field " + name + " isn't static");
				queues.add(new Queue<>(name, () -> val, type));
			}
		}

		public void addInterface(Class<?> interfaceClass) {
			if (!interfaceClass.isInterface())
				throw new IllegalArgumentException(interfaceClass + " isn't interface");
			writer.addInterface(interfaceClass.getName());
		}

		public void visit(Class<?> cls) {
			Method[] methods   = cls.getDeclaredMethods();
			String   className = nativeName(cls);
			for (var m : methods) {
				if (m.getAnnotation(Exclude.class) != null) continue;
				int mod = m.getModifiers();
				if (!Modifier.isStatic(mod) || !Modifier.isPublic(mod)) continue;
				// 传给cls方法的参数
				Class<?>[] types = m.getParameterTypes();
				// 用于super方法
				Class<?>[] realTypes  = Arrays.copyOfRange(types, 1, types.length);
				Class<?>   returnType = m.getReturnType();
				String     descriptor = nativeMethod(returnType, realTypes);
				{ // buildSuper
					writer.startMethod("super$_" + m.getName(),
					 descriptor, ACC_PUBLIC);
					writer.addLoadThis(); // this
					for (int i = 1; i <= realTypes.length; i++) {
						writer.add(addLoad(types[i]), i);
						// addCast(writer, types[i]);
					}
					writer.addInvoke(INVOKESPECIAL, superName, m.getName(), descriptor);
					writer.add(buildReturn(returnType));
					writer.stopMethod((short) types.length);
				}
				writer.startMethod(m.getName(), descriptor, (short) Modifier.PUBLIC);
				writer.addLoadThis();
				for (int i = 0; i < realTypes.length; i++) {
					writer.add(addLoad(realTypes[i]), i + 1);
				}
				writer.addInvoke(INVOKESTATIC, className,
				 m.getName(), nativeMethod(returnType, types));
				addCast(writer, returnType);
				// writer.add(ByteCode.CHECKCAST, nativeName(returnType));
				writer.add(buildReturn(returnType));
				writer.stopMethod((short) (realTypes.length + 1));
			}
		}

		public Class<? extends T> define() {
			return define(superClass);
		}

		public Class<? extends T> define(Class<?> superClass) {
			var base = IReflect.defineClass(adapterName, superClass, writer.toByteArray());
			// MyReflect.loader.addChild(base.getClassLoader());
			/*try {
				Class.forName(adapterName, true, base.getClassLoader());
			} catch (Throwable e) {
				throw new RuntimeException(e);
			}*/
			var  map = Seq.with(base.getDeclaredFields()).asMap(Field::getName);
			long off;
			for (var q : queues) {
				off = OS.isAndroid ? FieldUtils.getFieldOffset(map.get(q.name)) : unsafe.staticFieldOffset(map.get(q.name));
				unsafe.putObject(base, off, q.get());
			}
			/*var base = MyReflect.defineClass(adapterName, superClass, writer.toByteArray());
			Field[] fields = base.getDeclaredFields();
			for (var f : fields) {
				if (!Modifier.isStatic(f.getModifiers())) continue;
				queues.removeAll(q -> {
					boolean b = q.name.equals(f.getName());
					if (b) {
						try {
							f.setAccessible(true);
							f.set(null, q.get());
						} catch (Exception e) {
							Log.err(e);
						}
					}
					return b;
				});
			}*/
			return (Class<? extends T>) base;
		}

		public Class<? extends T> define(ClassLoader loader) {
			var base = IReflect.defineClass(adapterName, loader, writer.toByteArray());

			ObjectMap<String, Field> map = Seq.with(base.getDeclaredFields()).asMap(Field::getName);
			long                     off;
			for (var q : queues) {
				off = Vars.mobile ? FieldUtils.getFieldOffset(map.get(q.name)) : unsafe.staticFieldOffset(map.get(q.name));
				unsafe.putObject(base, off, q.get());
			}
			return (Class<? extends T>) base;
		}

		public void writeTo(Fi fi) {
			ByteCodeTools.writeTo(writer, fi);
		}
	}

	public static void writeTo(ClassFileWriter writer, Fi fi) {
		try {
			FileOutputStream outputStream = new FileOutputStream(fi.file());
			outputStream.write(writer.toByteArray());
			outputStream.close();
		} catch (Exception e) {
			Log.err(e);
		}
	}

	public static class Queue<T> {
		public String   name;
		public Prov<T>  func;
		// prov 应该返回的类
		public Class<?> cls;

		public Queue(String name, Prov<T> func, Class<?> cls) {
			this.name = name;
			this.func = func;
			this.cls = cls;
		}

		public T get() {
			return func.get();
		}
	}

	public static String nativeName(Class<?> cls) {
		return cls.getName().replace('.', '/');
	}

	public static String nativeMethod(Class<?> returnType, Class<?>... args) {
		StringBuilder builder = new StringBuilder("(");
		for (var arg : args) {
			if (arg == void.class)
				unsafe.throwException(new IllegalArgumentException("args: " + Arrays.toString(args) + " contains void.class"));
			builder.append(typeToNative(arg));
		}
		builder.append(")").append(typeToNative(returnType));
		return builder.toString();
	}

	public static Class<?> box(Class<?> type) {
		if (type == boolean.class) return Boolean.class;
		else if (type == byte.class) return Byte.class;
		else if (type == char.class) return Character.class;
		else if (type == short.class) return Short.class;
		else if (type == int.class) return Integer.class;
		else if (type == float.class) return Float.class;
		else if (type == long.class) return Long.class;
		else if (type == double.class) return Double.class;
		else return type;
	}

	public static void addCast(ClassFileWriter writer, Class<?> type) {
		if (type == void.class) return;
		if (type.isPrimitive()) {
			String tmp = nativeName(box(type));
			writer.add(CHECKCAST, tmp);
			writer.addInvoke(INVOKEVIRTUAL, tmp,
			 type.getSimpleName() + "Value", nativeMethod(type));
		} else {
			writer.add(CHECKCAST, nativeName(type));
		}
	}

	// int -> Integer (装箱
	public static void addBox(ClassFileWriter writer, Class<?> type) {
		if (type.isPrimitive()) {
			Class<?> boxCls = box(type);
			// Log.debug(type);
			writer.addInvoke(INVOKESTATIC, nativeName(boxCls), "valueOf", nativeMethod(boxCls, type));
		}
	}

	public static String typeToNative(Class<?> cls) {
		if (cls.isArray()) return "[" + typeToNative(cls.getComponentType());
		else if (cls == int.class) return "I";
		else if (cls == long.class) return "J";
		else if (cls == float.class) return "F";
		else if (cls == double.class) return "D";
		else if (cls == char.class) return "C";
		else if (cls == short.class) return "S";
		else if (cls == byte.class) return "B";
		else if (cls == boolean.class) return "Z";
		else if (cls == void.class) return "V";
		else return "L" + nativeName(cls) + ";";
	}

	public static short buildReturn(Class<?> returnType) {
		if (returnType == boolean.class || returnType == int.class
				|| returnType == byte.class || returnType == short.class
				|| returnType == char.class)
			return IRETURN;
		else if (returnType == long.class) return LRETURN;
		else if (returnType == float.class) return FRETURN;
		else if (returnType == double.class) return DRETURN;
		else if (returnType == void.class) return RETURN;
			// else if (returnType == byte.class) return ByteCode.BRETURN;
		else return ARETURN;
	}

	public static <T, V> Func2<V, ArrayList<Object>, T> getFunc(Class<V> cls, String name, Class<?>... args) {
		Method m;
		try {
			m = cls.getDeclaredMethod(name, args);
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
		m.setAccessible(true);

		return (ins, _args) -> {
			try {
				return (T) m.invoke(ins, _args);
			} catch (Exception e) {
				Log.err(e);
				return null;
			}
		};
	}

	/*public interface Func2<P1, P2, R> {
		R get(P1 param1, P2 param2);
	}
	public interface Prov<T> {
		T get();
	}
	public interface Cons2<T, N> {
		void get(T var1, N var2);
	}*/


	/*public interface Func2<P1, P2, R> {
		R get(P1 var1, P2 var2);
	}
	public interface Cons2<T, N> {
		void get(T var1, N var2);
	}
	public interface Prov<T> {
		T get();
	}*/

	@Retention(RetentionPolicy.RUNTIME)
	public @interface Exclude {}

	public interface MyRun {
		int get(ClassFileWriter cfw);
	}
}