package modtools.utils;

import arc.files.Fi;
import arc.func.*;
import arc.struct.Seq;
import arc.util.*;
import modtools.IntVars;
import modtools.annotations.asm.Sample.AConstants;
import modtools.jsfunc.type.CAST;
import modtools.utils.reflect.*;
import rhino.classfile.ClassFileWriter;

import java.io.FileOutputStream;
import java.lang.annotation.*;
import java.lang.reflect.*;
import java.util.*;

import static modtools.IntVars.mainLoader;
import static rhino.classfile.ByteCode.*;
import static rhino.classfile.ClassFileWriter.*;

/** 对rhino的ClassWriter进行封装 */
public class ByteCodeTools {
	public static final String FUNCTION_KEY = "_K_Fn",
	 CLASS_FILE                             = "_ihope";

	public static boolean DEBUG_LOG_FILE = false;

	private static int lastID = 0;

	private static int nextID() {
		return lastID++;
	}

	@SuppressWarnings("removal")
	public static class MyClass<T> {
		public final ClassFileWriter writer;
		public final String          adapterName, superName;
		final         Class<?>            superClass;
		private final ArrayList<Queue<?>> queues = new ArrayList<>();
		// private final ArrayList<ClassInfo> superFunctions = new ArrayList<>();

		public MyClass(Class<T> superClass, String suffix) {
			this(nativeName(superClass) + suffix, superClass);
		}

		/** @param name 必须调用nativeName */
		public MyClass(String name, Class<T> superClass) {
			this.superClass = superClass;
			adapterName = name;
			superName = nativeName(superClass);
			writer = new ClassFileWriter(name, superName, CLASS_FILE + nextID());
		}

		public <V> void setFunc(String name, MyRun run, int flags, Class<V> returnType, Class<?>... args) {
			writer.startMethod(name, nativeMethod(returnType, args), (short) flags);
			writer.stopMethod((short) run.get(writer)); // this + args + var * 1
		}

		public <V> void setFunc(String name, Func2<T, Object[], Object> func2, int flags, boolean buildSuper,
		                        Class<V> returnType, Class<?>... args) {
			if (func2 == null) {
				writer.startMethod(name, nativeMethod(returnType, args), (short) flags);
				if ((flags & ACC_ABSTRACT) != 0) {
					short currentSlot = 1;
					for (Class<?> type : args) {
						currentSlot += typeSize(type);
					}
					writer.stopMethod(currentSlot);
					return;
				}
				writer.addLoadThis();
				short currentSlot = 1;
				for (Class<?> type : args) {
					writer.add(addLoad(type), currentSlot);
					currentSlot += typeSize(type);
				}
				writer.addInvoke(INVOKESPECIAL, superName, name, nativeMethod(returnType, args));
				// emitCast(writer, returnType);
				// writer.add(ByteCode.CHECKCAST, nativeName(returnType));
				writer.add(buildReturn(returnType));
				writer.stopMethod(currentSlot);
				return;
			}
			var lambda = addLambda(func2, Func2.class, "get",
			 "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
			short max = 1;
			for (Class<?> type : args) {
				max += typeSize(type);
			}
			int v1 = max;
			max += typeSize(returnType);
			int v2 = max++; // Object[] 占用的 1 个槽位
			writer.startMethod(name, nativeMethod(returnType, args), (short) flags);

			if (buildSuper) {
				writer.addLoadThis(); // this
				// args
				short currentSlot = 1;
				for (Class<?> type : args) {
					writer.add(addLoad(type), currentSlot);
					currentSlot += typeSize(type);
					// addCast(writer, args[i]);
				}
				// super
				writer.addInvoke(INVOKESPECIAL, superName, name, nativeMethod(returnType, args));
				// 储存为v1
				if (returnType != void.class) { writer.add(addStore(returnType), v1); }
			}

			// new Object[args.length]
			int arrayLen = args.length + (buildSuper && returnType != void.class ? 1 : 0);
			writer.addPush(arrayLen);
			writer.add(ANEWARRAY, "java/lang/Object");
			writer.add(ASTORE, v2);

			// 将参数存入 Object[] 数组
			short currentSlot = 1;
			for (int i = 0; i < args.length; i++) {
				writer.add(ALOAD, v2); // array ref
				writer.addPush(i);     // array index
				writer.add(addLoad(args[i]), currentSlot);
				emitBox(writer, args[i]);
				writer.add(AASTORE);   // 存入数组
				currentSlot += typeSize(args[i]);
			}

			// 将 super 的返回值也存入 Object[]
			if (buildSuper && returnType != void.class) {
				writer.add(ALOAD, v2); // array ref
				writer.addPush(args.length);
				writer.add(addLoad(returnType), v1);
				emitBox(writer, returnType);
				writer.add(AASTORE);
			}

			execLambda(lambda, () -> {
				writer.addLoadThis(); // this
				writer.add(ALOAD, v2); // ref
			});

			if (returnType == void.class) {
				writer.add(POP);
			} else {
				emitCast(writer, returnType);
			}
			// writer.add(ByteCode.CHECKCAST, nativeName(returnType));
			writer.add(buildReturn(returnType));

			writer.stopMethod(max); // this + args + var * 1
		}
		public short typeSize(Class<?> type) {
			if (type == void.class) return 0;
			if (type == long.class || type == double.class) return 2;
			return 1;
		}

		public record Lambda(String fieldName, Class<?> type, String invoker, String desc) {
		}

		public void execLambda(Lambda lambda, Runnable loadParam) {
			// 获取functionKey字段
			writer.add(GETSTATIC, adapterName, lambda.fieldName, typeToNative(lambda.type));

			if (loadParam != null) { loadParam.run(); }

			// V get(args)
			writer.addInvoke(INVOKEINTERFACE, nativeName(lambda.type), lambda.invoker, lambda.desc);
		}

		public <LT> Lambda addLambda(LT lambda, Class<LT> clazz, String invoker, String desc) {
			String fieldName = FUNCTION_KEY + "$" + nextID();
			queues.add(new Queue<>(fieldName, () -> lambda, clazz));
			writer.addField(fieldName, typeToNative(clazz),
			 (short) (Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL));
			return new Lambda(fieldName, clazz, invoker, desc);
		}

		/*
		 * public <V> void setFunc(String name, arc.func.Func2 func2, int flags,
		 * Class<V> returnType, Class<?>... args) {
		 * setFunc(name, (a, b) -> func2.get(a, b), flags, false, returnType, args);
		 * }
		 */

		/**
		 * @param name       方法名
		 * @param flags      方法的修饰符
		 * @param returnType 方法的返回值
		 * @param args       方法的参数
		 **/
		public <V> void setFunc(String name, Func2<T, Object[], Object> func2, int flags, Class<V> returnType,
		                        Class<?>... args) {
			setFunc(name, func2, flags, false, returnType, args);
		}

		public void setFunc(String name, Cons2<T, Object[]> cons2, int flags, Class<?>... args) {
			setFunc(name, (self, a) -> {
				cons2.get(self, a);
				return null;
			}, flags, false, void.class, args);
		}

		public void setFunc(String name, Cons2<T, Object[]> cons2, int flags, boolean buildSuper,
		                    Class<?>... args) {
			setFunc(name, cons2 == null ? null : (self, a) -> {
				cons2.get(self, a);
				return null;
			}, flags, buildSuper, void.class, args);
		}

		private int addLoad(Class<?> type) {
			if (type == boolean.class) {
				return ILOAD;
			} else if (type == byte.class) {
				return ILOAD;
			} else if (type == char.class) {
				return ILOAD;
			} else if (type == short.class) {
				return ILOAD;
			} else if (type == int.class) {
				return ILOAD;
			} else if (type == float.class) {
				return FLOAD;
			} else if (type == long.class) {
				return LLOAD;
			} else if (type == double.class) {
				return DLOAD;
			} else { return ALOAD; }
		}

		private int addStore(Class<?> type) {
			if (type == boolean.class) {
				return ISTORE;
			} else if (type == byte.class) {
				return ISTORE;
			} else if (type == char.class) {
				return ISTORE;
			} else if (type == short.class) {
				return ISTORE;
			} else if (type == int.class) {
				return ISTORE;
			} else if (type == float.class) {
				return FSTORE;
			} else if (type == long.class) {
				return LSTORE;
			} else if (type == double.class) {
				return DSTORE;
			} else {
				return ASTORE;
			}
		}

		public void buildSuperFunc(String thisMethodName, String superMethodName, Class<?> superClass,
		                           Class<?> returnType,
		                           Class<?>... args) {
			writer.startMethod(thisMethodName, nativeMethod(returnType, args), (short) Modifier.PUBLIC);
			writer.addLoadThis(); // this
			short currentSlot = 1;
			for (Class<?> paramType : args) {
				writer.add(addLoad(paramType), currentSlot);
				currentSlot += typeSize(paramType);
			}
			writer.addInvoke(INVOKESPECIAL, nativeName(superClass), superMethodName, nativeMethod(returnType, args));
			// addCast(returnType);
			writer.add(buildReturn(returnType));
			writer.stopMethod(currentSlot);
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
			writer.stopMethod((short) (1 + typeSize(fieldType))); // this + arg1
		}

		public void setField(int flags, Class<?> type, String name) {
			setField(flags, type, name, null);
		}

		public <T2> void setField(int flags, Class<T2> type, String name, T2 val) {
			if (!Modifier.isStatic(flags)) {
				throw new IllegalArgumentException("Field " + name + " isn't static");
			}
			if (type.isPrimitive() && Modifier.isFinal(flags)) {
				throw new IllegalArgumentException("Field " + name + "'s modifier is static final(inline).");
			}
			writer.addField(name, typeToNative(type), (short) flags);
			if (val == null) return;

			queues.add(new Queue<>(name, () -> val, type));
		}

		public void addInterface(Class<?> interfaceClass) {
			if (!interfaceClass.isInterface()) {
				throw new IllegalArgumentException(interfaceClass + " isn't interface");
			}
			writer.addInterface(nativeName(interfaceClass));
		}

		public void visit(Class<?> cls) {
			Method[] methods   = cls.getDeclaredMethods();
			String   className = nativeName(cls);
			for (var m : methods) {
				if (m.getAnnotation(Exclude.class) != null) { continue; }
				int mod = m.getModifiers();
				if (!Modifier.isStatic(mod) || !Modifier.isPublic(mod)) { continue; }
				// 传给cls方法的参数
				Class<?>[] types = m.getParameterTypes();
				// 用于super方法
				Class<?> returnType = m.getReturnType();
				visitEmitMethod(m.getName(), types, returnType, className);
			}
		}

		public void visitEmitMethod(String name, Class<?>[] paramTypes,
		                            Class<?> returnType, String className) {
			visitEmitMethod(name, name, paramTypes, returnType, className);
		}

		/**
		 * @param name       重载方法名
		 * @param targetName 注解所标记的方法的名称
		 * @see modtools.annotations.processors.asm.SampleProcessor
		 **/
		public void visitEmitMethod(String name, String targetName, Class<?>[] paramTypes,
		                            Class<?> returnType, String className) {
			Class<?>[] realTypes  = Arrays.copyOfRange(paramTypes, 1, paramTypes.length);
			String     descriptor = nativeMethod(returnType, realTypes);
			{ // buildSuper
				writer.startMethod(AConstants.SUPER_METHOD_PREFIX + name,
				 descriptor, ACC_PUBLIC);
				writer.addLoadThis(); // this
				short currentSlot = 1;
				for (Class<?> paramType : realTypes) {
					writer.add(addLoad(paramType), currentSlot);
					currentSlot += typeSize(paramType);
				}
				writer.addInvoke(INVOKESPECIAL, superName, name, descriptor);
				writer.add(buildReturn(returnType));
				writer.stopMethod(currentSlot);
			}
			{ // thisMethod
				writer.startMethod(targetName, descriptor, (short) Modifier.PUBLIC);
				writer.addLoadThis();
				short currentSlot = 1;
				for (Class<?> paramType : realTypes) {
					writer.add(addLoad(paramType), currentSlot);
					currentSlot += typeSize(paramType);
				}
				writer.addInvoke(INVOKESTATIC, className,
				 targetName, nativeMethod(returnType, paramTypes));
				// emitCast(writer, returnType);
				// writer.add(ByteCode.CHECKCAST, nativeName(returnType));
				writer.add(buildReturn(returnType));
				writer.stopMethod(currentSlot);
			}
		}

		public void visitEmitField(String name, Class<?> type) {
			{ // buildGetter
				writer.startMethod(name, nativeMethod(type), ACC_PUBLIC);
				writer.addLoadThis();
				writer.add(GETFIELD, superName, name, typeToNative(type));
				writer.add(buildReturn(type));
				writer.stopMethod((short) 1);
			}
			{ // buildSetter
				writer.startMethod(name, nativeMethod(void.class, type), ACC_PUBLIC);
				writer.addLoadThis();
				writer.add(addLoad(type), 1);
				writer.add(PUTFIELD, superName, name, typeToNative(type));
				writer.add(RETURN);
				writer.stopMethod((short) (1 + typeSize(type)));
			}
		}

		/** 使用超类{@link #superClass}的保护域 */
		public Class<T> define() {
			return define(superClass);
		}

		/** @param superClass 用于确定classLoader */
		public Class<T> define(Class<?> superClass) {
			l:
			if (OS.isAndroid) {
				int mod = superClass.getModifiers();
				if (/* Modifier.isFinal(mod) || */!Modifier.isPublic(mod)) {
					HopeReflect.setPublic(superClass, Class.class);
				}
				if (superClass == this.superClass) { break l; }
				mod = this.superClass.getModifiers();
				if (!Modifier.isPublic(mod)) {
					HopeReflect.setPublic(this.superClass, Class.class);
				}
				try {
					superClass.getClassLoader().loadClass(this.superClass.getName());
				} catch (ClassNotFoundException e) {
					mainLoader.addChild(this.superClass.getClassLoader());
				}
			}
			return define(superClass.getClassLoader());
		}

		public Class<T> define(ClassLoader loader) {
			if (DEBUG_LOG_FILE) {
				Fi dir = IntVars.dataDirectory.child("gen");
				dir.mkdirs();
				writeTo(dir);
			}
			return putStatic(HopeReflect.defineClass(adapterName, loader, writer.toByteArray()));
		}

		private Class<T> putStatic(Class<?> base) {
			var  map = Seq.with(base.getDeclaredFields()).asMap(Field::getName);
			long off;
			for (var q : queues) {
				Field field = map.get(q.name);
				off = FieldUtils.fieldOffset(field);
				// queues是内部使用的，字段类型和q.get()类型始终为Object
				FieldUtils.setValue(base, off, q.get(), q.cls());
			}
			// noinspection unchecked
			return (Class<T>) base;
		}

		public void writeTo(Fi fi) {
			ByteCodeTools.writeTo(writer, fi);
		}
	}

	public static void writeTo(ClassFileWriter writer, Fi fi) {
		if (fi.isDirectory()) {
			String name = writer.getClassName();
			fi = fi.child(name.replace('/', '.') + ".class");
		}
		try {
			FileOutputStream outputStream = new FileOutputStream(fi.file());
			outputStream.write(writer.toByteArray());
			outputStream.close();
		} catch (Exception e) {
			Log.err(e);
		}
	}

	/**
	 * @param cls {@code func} 返回的类Class<T>
	 */
	public record Queue<T>(String name, Prov<T> func, Class<T> cls) {
		public Queue {
			if (name == null) { throw new IllegalArgumentException("name is null"); }
			if (func == null) { throw new IllegalArgumentException("func is null"); }
			if (cls == null) { throw new IllegalArgumentException("cls is null"); }
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
			if (arg == void.class) {
				throw new IllegalArgumentException("args: " + Arrays.toString(args) + " contains void.class");
			}
			builder.append(typeToNative(arg));
		}
		builder.append(")").append(typeToNative(returnType));
		return builder.toString();
	}

	static Class<?> box(Class<?> type) {
		return CAST.box(type);
	}

	public static void emitCast(ClassFileWriter writer, Class<?> type) {
		if (type == void.class) { return; }
		if (type.isPrimitive()) {
			String tmp = nativeName(box(type));
			writer.add(CHECKCAST, tmp);
			writer.addInvoke(INVOKEVIRTUAL, tmp,
			 type.getSimpleName() + "Value", nativeMethod(type));
		} else {
			writer.add(CHECKCAST, nativeName(type));
		}
	}

	// int -> Integer (装箱)
	public static void emitBox(ClassFileWriter writer, Class<?> type) {
		if (type.isPrimitive()) {
			Class<?> boxCls = box(type);
			// Log.debug(type);
			writer.addInvoke(INVOKESTATIC, nativeName(boxCls), "valueOf", nativeMethod(boxCls, type));
		}
	}

	public static String typeToNative(Class<?> cls) {
		if (cls.isArray()) { return "[" + typeToNative(cls.getComponentType()); }
		if (cls == int.class) { return "I"; }
		if (cls == long.class) { return "J"; }
		if (cls == float.class) { return "F"; }
		if (cls == double.class) { return "D"; }
		if (cls == char.class) { return "C"; }
		if (cls == short.class) { return "S"; }
		if (cls == byte.class) { return "B"; }
		if (cls == boolean.class) { return "Z"; }
		if (cls == void.class) { return "V"; }
		return "L" + nativeName(cls) + ";";
	}

	public static short buildReturn(Class<?> returnType) {
		if (returnType == boolean.class || returnType == int.class
		    || returnType == byte.class || returnType == short.class
		    || returnType == char.class) {
			return IRETURN;
		} else if (returnType == long.class) {
			return LRETURN;
		} else if (returnType == float.class) {
			return FRETURN;
		} else if (returnType == double.class) {
			return DRETURN;
		} else if (returnType == void.class) {
			return RETURN;
		}
		// else if (returnType == byte.class) return ByteCode.BRETURN;
		else {
			return ARETURN;
		}
	}

	@Retention(RetentionPolicy.RUNTIME)
	public @interface Exclude {
	}

	public interface MyRun {
		int get(ClassFileWriter cfw);
	}
}