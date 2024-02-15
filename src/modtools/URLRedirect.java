package modtools;

import arc.files.Fi;
import arc.func.Func2;
import ihope_lib.MyReflect;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.utils.ByteCodeTools.MyClass;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.*;
import java.util.*;

public class URLRedirect {
	static Properties replacer = new Properties();
	static Fi         defaultConfig;
	static void load() throws Throwable {
		defaultConfig = IntVars.dataDirectory.child("http_redirect.properties");
		if (loadConfig(defaultConfig)
				|| loadConfig(IntVars.root.child("http_redirect.properties"))) ;

		final Field field = URL.class.getDeclaredField("handlers");
		MyReflect.setOverride(field);
		var hashtable = (Hashtable<String, URLStreamHandler>) field.get(null);
		UNSAFE.openModule(Object.class, "sun.net.www.protocol.https");
		UNSAFE.openModule(Object.class, "sun.net.www.protocol.http");
		field.set(null, new Hashtable<>(hashtable) {
			public synchronized URLStreamHandler put(String key, URLStreamHandler value) {
				if (key.equals("http") || key.equals("https")) {
					var handler = new MyClass<>(value.getClass() + "_1", value.getClass());
					handler.setFunc("<init>", (Func2) null, 1, Void.TYPE);
					handler.addInterface(RedirectHandler.class);
					handler.visit(URLRedirect.class);
					try {
						value = handler.define(URLRedirect.class.getClassLoader()).getDeclaredConstructor().newInstance();
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
				return super.put(key, value);
			}
		});
		new URL("https://github.com");
		new URL("http://github.com");
	}
	private static boolean loadConfig(Fi config) throws IOException {
		if (!config.exists()) return false;
		replacer.load(config.read());
		if (!defaultConfig.exists()) config.copyTo(defaultConfig);
		return true;
	}

	public static URLConnection openConnection(URLStreamHandler self, URL u, Proxy p) throws IOException {
		String def_url  = u.toString().substring(u.getProtocol().length());
		String file_url = def_url.substring(3 + u.getHost().length());
		u = new URL(u.getProtocol() + "://" + replacer.getOrDefault(u.getHost(), u.getHost()) + "/" + file_url);
		// Log.info(u);
		return ((RedirectHandler) self).super$_openConnection(u, p);
	}
	public interface RedirectHandler {
		URLConnection super$_openConnection(URL u, Proxy p) throws IOException;
	}
}
