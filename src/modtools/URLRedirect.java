package modtools;

import arc.files.Fi;
import arc.func.Func2;
import arc.util.*;
import ihope_lib.MyReflect;
import modtools.Constants.CURL;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.utils.ByteCodeTools.MyClass;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.*;
import java.util.*;

/**
 * 切换镜像
 * 从访问一个网站改成访问另一个网站（镜像站）
 */
@SuppressWarnings("deprecation")
public class URLRedirect {
	static Properties replacer = new Properties();
	static Fi         defaultConfig;
	public static final String SUFFIX = "-h0";

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
				if (!key.equals("http") && !key.equals("https")) return super.put(key, value);
				if (value.getClass().getName().endsWith("-h0")) return value;

				var handler = new MyClass<>(value.getClass().getName() + SUFFIX, value.getClass());
				handler.setFunc("<init>", (Func2) null, 1, Void.TYPE);
				handler.addInterface(RedirectHandler.class);
				handler.visit(URLRedirect.class);
				if (OS.isAndroid) {
					/* 同时去除final */
					MyReflect.setPublic(value.getClass());
				}
				try {
					value = handler.define(URLRedirect.class.getClassLoader()).getDeclaredConstructor().newInstance();
				} catch (Throwable e) {
					Log.err(e);
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

	public static URLConnection openConnection(URLStreamHandler self, URL u) throws IOException {
		if (replacer.contains(u.getHost())) {
			UNSAFE.putObject(u, CURL.host, replacer.getProperty(u.getHost()));
		}
		// Log.info(u);
		return ((RedirectHandler) self).super$_openConnection(u);
	}
	public interface RedirectHandler {
		URLConnection super$_openConnection(URL u) throws IOException;
	}
}
