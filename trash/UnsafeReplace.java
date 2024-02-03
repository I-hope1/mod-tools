package modtools.annotations;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Types.SimpleVisitor;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Context.Key;
import jdk.internal.misc.Unsafe;
import modtools.annotations.classfile.ByteCodeTools.MyClass;
import rhino.classfile.*;

import java.io.*;
import java.util.HashMap;

import static modtools.annotations.BaseProcessor.*;
import static modtools.annotations.HopeReflect.*;
import static modtools.annotations.classfile.ByteCodeTools.*;

public class UnsafeReplace {
	static void extendingFunc() throws Throwable {
		removeKey(Resolve.class);
		ClassLoader superLoader = Resolve.class.getClassLoader();
		Unsafe      junsafe     = Unsafe.getUnsafe();

		String          RS_NAME     = nativeName(Resolve.class);
		MyClass<?>      NEW_RESOLVE = new MyClass<>(RS_NAME + ("_a" + Math.random()), Resolve.class);
		ClassFileWriter cfw         = NEW_RESOLVE.writer;
		String          type        = "(Lcom/sun/tools/javac/util/Context;)V";
		cfw.startMethod("<init>", type, (short) 1);
		cfw.addLoadThis();
		cfw.addALoad(1);
		cfw.addInvoke(ByteCode.INVOKESPECIAL, RS_NAME, "<init>", type);
		cfw.add(ByteCode.RETURN);
		cfw.stopMethod((short) 2);

		String type1 = "(Lcom/sun/tools/javac/code/Symbol;" +
									 "Lcom/sun/tools/javac/util/JCDiagnostic$DiagnosticPosition;" +
									 "Lcom/sun/tools/javac/code/Symbol;" +
									 "Lcom/sun/tools/javac/code/Type;" +
									 "Lcom/sun/tools/javac/util/Name;" +
									 "Z" +
									 "Lcom/sun/tools/javac/util/List;" +
									 "Lcom/sun/tools/javac/util/List;" +
									 "Lcom/sun/tools/javac/comp/Resolve$LogResolveHelper;" +
									 ")Lcom/sun/tools/javac/code/Symbol;";
		String methodName = "accessInternal";
		cfw.startMethod(methodName, type1, (short) 1);
		// cfw.addPush("aaasaijspo");
		// cfw.addInvoke(ByteCode.INVOKESTATIC ,StaticPrinter.class.getName().replace('.', '/'), "println","(Ljava/lang/String;)V" );
		// sym instanceof InvalidSymbolError
		cfw.addALoad(1);
		String InvalidSymbol = "com/sun/tools/javac/comp/Resolve$InvisibleSymbolError";
		// String InvisibleSymbol = "com/sun/tools/javac/comp/Resolve$InvisibleSymbolError";
		String NSymbol = Symbol.class.getName().replace('.', '/');
		cfw.add(ByteCode.INSTANCEOF, InvalidSymbol);
		int superLabel = cfw.acquireLabel();
		int c1         = cfw.acquireLabel();
		int myLabel    = cfw.acquireLabel();

		cfw.add(ByteCode.IFNE, myLabel);
		cfw.add(ByteCode.GOTO, superLabel);

		// ((InvalidSymbolError)sym).sym instanceof PackageSymbol
		cfw.markLabel(c1);
		cfw.addALoad(1);
		cfw.add(ByteCode.CHECKCAST, InvalidSymbol);
		cfw.add(ByteCode.GETFIELD, InvalidSymbol, "sym", "L" + NSymbol + ";");
		cfw.add(ByteCode.INSTANCEOF, TypeSymbol.class.getName().replace('.', '/'));
		cfw.add(ByteCode.IFNE, myLabel);
		cfw.add(ByteCode.GOTO, superLabel);

		// ((InvalidSymbolError)sym).sym.owner instanceof PackageSymbol
		// cfw.addALoad(1);
		// cfw.add(ByteCode.CHECKCAST, InvalidSymbol);
		// cfw.add(ByteCode.GETFIELD, InvalidSymbol, "sym", "L" + NSymbol + ";");
		// cfw.add(ByteCode.GETFIELD, NSymbol, "owner", "L" + NSymbol + ";");
		// cfw.add(ByteCode.INSTANCEOF, PackageSymbol.class.getName().replace('.', '/'));
		// cfw.add(ByteCode.IOR);

		cfw.markLabel(myLabel);
		cfw.addALoad(1);
		cfw.add(ByteCode.CHECKCAST, InvalidSymbol);
		cfw.add(ByteCode.GETFIELD, InvalidSymbol, "sym", "L" + NSymbol + ";");
		cfw.add(ByteCode.ARETURN);

		cfw.markLabel(superLabel);
		for (short i = 0; i <= 5; i++) cfw.addALoad(i);
		cfw.addILoad(6);
		for (short i = 7; i <= 9; i++) cfw.addALoad(i);
		cfw.addInvoke(ByteCode.INVOKESPECIAL, RS_NAME,
		 methodName, type1);
		cfw.add(ByteCode.ARETURN);
		cfw.stopMethod((short) 10);

		NEW_RESOLVE.setFunc("isAccessible", (self, args) -> true, 1, boolean.class, Env.class, TypeSymbol.class, boolean.class);
		NEW_RESOLVE.setFunc("isAccessible", (self, args) -> {
			Symbol sym = (Symbol) args.get(2);
			if (!sym.owner.isAbstract() && !sym.isInner() && !sym.isAnonymous()
					&& (sym.flags_field & Flags.PARAMETER) == 0 &&
					((Env)args.get(0)).enclClass.sym.getAnnotation(NoAccessCheck.class) != null) {
				sym.flags_field |= Flags.PUBLIC;
				sym.flags_field &= ~Flags.PRIVATE;
			}
			return true;
		}, 1,boolean.class, Env.class, Type.class, System.class, boolean.class);

		Resolve resolve;
		try {
			byte[]           bytes  = cfw.toByteArray();
			FileOutputStream writer = new FileOutputStream("./OAOAK.class");
			writer.write(bytes);
			writer.close();
			Class<?> cl = NEW_RESOLVE.define();
			resolve = (Resolve) cl.getDeclaredConstructor(Context.class).newInstance(__context);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
		// replaceAccess(Resolve.class, resolve, "doRecoveryLoadClass", "starImportScopeRecovery");
		// replaceAccess(Resolve.class, resolve, "namedImportScopeRecovery", "doRecoveryLoadClass");

		// HopeReflect.replaceAccess(Resolve.class, resolve, "methodLogResolveHelper", "silentLogResolveHelper");
		// HopeReflect.invoke(Resolve.class, resolve, "accessBase",
		//  new Object[]{null, null, null, null, null, false},
		//  Symbol.class, DiagnosticPosition.class, Symbol.class, Type.class, Name.class, boolean.class);

		setAccess(Resolve.class, resolve, "accessibilityChecker", new SimpleVisitor<>() {
			public Object visitType(Type t, Object o) {return t;}
		});
		setAccess(Check.class, Check.instance(__context), "rs", resolve);
		setAccess(Attr.class, __attr__, "rs", resolve);
	}
	private static void defineAppClass(String name, Unsafe junsafe, ClassLoader superLoader) throws IOException {
		try {
			byte[] b = UnsafeReplace.class.getResourceAsStream(name).readAllBytes();
			junsafe.defineClass0(null, b, 0, b.length, superLoader, null);
		} catch (LinkageError ignored) {} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}
	private static void removeKey(Class<?> cls) {
		Key<Resolve>            key = HopeReflect.getAccess(cls, null, cls.getSimpleName().toLowerCase() + "Key");
		HashMap<Key<?>, Object> ht  = HopeReflect.getAccess(Context.class, __context, "ht");
		ht.remove(key);
	}
}
