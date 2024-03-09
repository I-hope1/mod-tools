package test0;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.*;
import jdk.internal.misc.Unsafe;
import mindustry.Vars;

import java.net.*;
import java.util.*;

import static ihope_lib.MyReflect.unsafe;

@SuppressWarnings({"rawtypes", "unchecked"})
public class TestRedefine {
	static {
		try {
			load0();
		} catch (Throwable e) {
			Log.err(e);
		}
	}
	public static void load() {}

	static void load0() throws Throwable{
		Class BuiltinClassLoader = Class.forName("jdk.internal.loader.BuiltinClassLoader");
		Class  URLClassPath = Class.forName("jdk.internal.loader.URLClassPath");
		Unsafe junsafe      = Unsafe.getUnsafe();
		long   ucpOff       = junsafe.objectFieldOffset(BuiltinClassLoader, "ucp");
		Object ucp  = unsafe.getObject(Vars.class.getClassLoader(), ucpOff);
		ArrayDeque list = Reflect.get(URLClassPath, ucp, "unopenedUrls");
		ArrayList  path = Reflect.get(URLClassPath, ucp, "path");

		Fi fi = Fi.get(
		 // "E:/Users/ASUS/Desktop/Mods/mod-tools136/_libs/Override.jar"
		 "E:/Users/ASUS/Desktop/Mindustry136/Mindustry-CN-ARC-Desktop-30771.jar"
		);
		if (!fi.exists() || fi.isDirectory()) return;
		// Log.info("real: @ (expect: @)", Token.TEMPLATE_LITERAL, 170);
		try {
			URL url = fi.file().toURI().toURL();
			list.addFirst(url);
			path.add(0, url);
			/* 一个不存在的文件 */
			Vars.class.getClassLoader().getResource("A-Not-Exist-File");
			ArrayList loaders = Reflect.get(URLClassPath, ucp, "loaders");
			Reflect.set(URLClassPath, ucp, "loaders", Seq.with(loaders).reverse().list());
		} catch (MalformedURLException ignored) {}
	}
}
