package modtools.extending;

import arc.files.Fi;
import arc.func.Func2;
import arc.util.*;
import ihope_lib.MyReflect;
import modtools.Constants.CURL;
import modtools.IntVars;
import modtools.jsfunc.reflect.UNSAFE;
import modtools.utils.ByteCodeTools.MyClass;
import modtools.utils.ByteCodeTools.MyClass.Lambda;
import modtools.utils.*;

import java.io.IOException;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.function.Consumer;

import static jdk.internal.classfile.Classfile.*;

/**
 * 切换镜像
 * 从访问一个网站改成访问另一个网站（镜像站）
 */
@SuppressWarnings("deprecation")
public class URLRedirect {
	public static final String SUFFIX = "-h0";

	static Properties    replacer = new Properties();
	static Fi            defaultConfig;
	static Consumer<URL> cons     = u -> {
		if (replacer.contains(u.getHost())) {
			UNSAFE.putObject(u, CURL.host, replacer.getProperty(u.getHost()));
		}
	};

	public static void load() throws Throwable {
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
				if (value.getClass().getName().endsWith(SUFFIX)) return value;

				var handler = new MyClass<>(value.getClass(), SUFFIX);
				handler.setFunc("<init>", (Func2) null, 1, Void.TYPE);
				Lambda lambda = handler.addLambda(cons, Consumer.class, "accept", "(Ljava/lang/Object;)V");
				handler.setFunc("openConnection", cfw -> {
					handler.execLambda(lambda, () -> cfw.add(ALOAD_1)); // cons.get(url);
					// super.openConnection(url);
					cfw.add(ALOAD_0);
					cfw.add(ALOAD_1);
					cfw.addInvoke(INVOKESPECIAL, handler.superName, "openConnection",
					 "(Ljava/net/URL;)Ljava/net/URLConnection;");
					cfw.add(ARETURN);
					return 2; // this + url
				}, Modifier.PUBLIC, URLConnection.class, URL.class);
				if (OS.isAndroid) {
					/* 同时去除final */
					MyReflect.setPublic(value.getClass());
				}
				URLStreamHandler newValue;
				try {
					Class<URLStreamHandler> newClass = (Class<URLStreamHandler>) handler.define();
					newValue = CatchSR.apply(() ->
					 CatchSR.of(() -> newClass.getDeclaredConstructor().newInstance())
						.get(() -> Tools.newInstance(value, newClass))
					);
				} catch (Throwable e) {
					newValue = value;
					Log.err(e);
				}
				return super.put(key, newValue);
			}
		});
		for (var entry : replacer.entrySet()) {
			Object k = entry.getKey();
			new URL("https://" + k);
			new URL("http://" + k);
		}
	}
	private static boolean loadConfig(Fi config) throws IOException {
		if (!config.exists()) return false;

		replacer.load(config.read());
		if (!defaultConfig.exists()) config.copyTo(defaultConfig);

		return true;
	}
}
