package modtools.annotations.unsafe;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Types.SimpleVisitor;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Context.Key;
import jdk.internal.misc.Unsafe;
import modtools.annotations.HopeReflect;

import java.io.*;
import java.util.HashMap;

import static modtools.annotations.BaseProcessor.*;
import static modtools.annotations.HopeReflect.*;

public class Replace {
	public static void extendingFunc() {
		removeKey(Resolve.class);
		Resolve resolve = new MyResolve(__context);

		setAccess(Resolve.class, resolve, "accessibilityChecker", new SimpleVisitor<>() {
			public Object visitType(Type t, Object o) {return t;}
		});
		setAccess(Check.class, Check.instance(__context), "rs", resolve);
		setAccess(Attr.class, __attr__, "rs", resolve);
	}
	private static void removeKey(Class<?> cls) {
		Key<Resolve>            key = HopeReflect.getAccess(cls, null, cls.getSimpleName().toLowerCase() + "Key");
		HashMap<Key<?>, Object> ht  = HopeReflect.getAccess(Context.class, __context, "ht");
		ht.remove(key);
	}
}
