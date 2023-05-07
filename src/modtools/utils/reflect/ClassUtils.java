package modtools.utils.reflect;


import arc.util.Log;
import ihope_lib.MyReflect;
import javassist.*;
import jdk.internal.reflect.ConstantPool;
import jdk.internal.reflect.ConstantPool.Tag;
import mindustry.Vars;
import modtools.ModTools;
import modtools.ui.components.input.highlight.*;
import rhino.NativeJavaClass;

import java.lang.reflect.Method;
import java.util.Arrays;

import static modtools.ui.components.input.highlight.JSSyntax.*;

public class ClassUtils {
	public static final String defName = "aco";
	static Method getConstantPool;

	static {
		try {
			getConstantPool = Class.class.getDeclaredMethod("getConstantPool");
			getConstantPool.setAccessible(true);
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}

	public static String toSource(Class<?> klass) throws Exception {
		ConstantPool  pool = (ConstantPool) getConstantPool.invoke(klass);
		StringBuilder sb   = new StringBuilder();
		int           size = pool.getSize();
		for (int i = 0; i < size; i++) {
			Tag tag = pool.getTagAt(i);
			try {
				switch (tag) {
					case UTF8 -> sb.append(pool.getUTF8At(i));
					case INTEGER -> sb.append(pool.getIntAt(i));
					case FLOAT -> sb.append(pool.getFloatAt(i));
					case LONG -> sb.append(pool.getLongAt(i));
					case DOUBLE -> sb.append(pool.getDoubleAt(i));
					case CLASS -> sb.append(pool.getClassAt(i));
					case STRING -> sb.append(pool.getStringAt(i));
					case FIELDREF -> sb.append(pool.getFieldAt(i));
					case METHODREF -> sb.append(pool.getMethodAt(i));
					// case INTERFACEMETHODREF -> sb.append(pool.getMethodAt(i));
					case NAMEANDTYPE -> sb.append(Arrays.toString(pool.getNameAndTypeRefInfoAt(i)));
					// case METHODHANDLE ->sb.append(pool.getMethodAt(i));
					// case METHODTYPE -> sb.append(pool.getMethodAt(i));
					// case INVOKEDYNAMIC, INVALID -> {}
				}
			} catch (Throwable e) {
				Log.info("tag: @, i: @", tag, i);
			}
		}
		return sb.toString();
	}

	static {
		ClassPool.getDefault().appendClassPath(new ClassClassPath(Vars.class));
		ClassPool.getDefault().appendClassPath(new ClassClassPath(ModTools.class));
		/* ClassPool.getDefault().appendClassPath(new ClassPath() {
			public InputStream openClassfile(String s) {
				String filename = '/' + s.replace('.', '/') + ".class";
				return Vars.mods.mainLoader().getResourceAsStream(filename);
			}
			public URL find(String s) {
				String filename = '/' + s.replace('.', '/') + ".class";
				return Vars.mods.mainLoader().getResource(filename);
			}
		}); */
	}

	public static Runnable makeRun(byte[] bytes) throws Exception {
		Class<?> cl = IReflect.defineClass(defName, new ClassLoader(Vars.mods.mainLoader()) {}, bytes);
		return (Runnable) MyReflect.unsafe.allocateInstance(cl);
	}


	public static byte[] toBytecode(String name, String text) throws Exception {
		StringBuilder sb = new StringBuilder();
		Syntax syntax = new JavaSyntax(null) {{
			tokenDraws = new TokenDraw[]{task -> {
				String token = (String) task.token;
				if (lastTask != operatesSymbol || operatesSymbol.lastSymbol == '\u0000' || operatesSymbol.lastSymbol != '.') {
					if (constantSet.contains(token) && scope.get(token, scope) instanceof NativeJavaClass cl) {
						token = cl.getClassObject().getName();
					}
				}
				// Log.info(token);
				sb.append(token);
				return c_number;
			}};
			drawDefCons = (a, b) -> {
				if (cTask == drawToken) return;
				sb.append(text, a, b);
			};
			drawToken.tokenDraws = tokenDraws;
		}};
		// sb.append(text, lastIndex[0], text.length());
		syntax.highlightingDraw(text);
		// Log.info(sb);
		ClassPool pool    = ClassPool.getDefault();
		CtClass   ctClass = pool.makeClass(name);
		ctClass.addInterface(pool.get("java.lang.Runnable"));
		CtMethod ctMethod = CtMethod.make("public void run() {" + sb + "}", ctClass);
		// ctMethod.setBody(String.valueOf(sb));
		ctClass.addMethod(ctMethod);
		try {
			return ctClass.toBytecode();
		} finally {
			ctClass.defrost();
		}
	}

}
