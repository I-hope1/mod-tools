package modtools.utils.reflect;


import arc.files.Fi;
import arc.util.*;
import dalvik.system.DexFile;
import jdk.internal.reflect.ConstantPool;
import jdk.internal.reflect.ConstantPool.Tag;

import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.*;

import static mindustry.Vars.mods;

public class ClassUtils {

	public static Set<Class<?>> getClasses(String pack) {
		return OS.isAndroid ? getClasses1(pack) : getClasses0(pack);
	}
	/** only for {@link OS#isAndroid} */
	public static Set<Class<?>> getClasses1(String pack) {
		if (true) return new LinkedHashSet<>();
		Set<Class<?>> classNameList = new LinkedHashSet<>();
		try {
			DexFile             df          = new DexFile(".");//通过DexFile查找当前的APK中可执行文件
			Enumeration<String> enumeration = df.entries();//获取df中的元素  这里包含了所有可执行的类名 该类名包含了包名+类名的方式
			while (enumeration.hasMoreElements()) {//遍历
				String className = enumeration.nextElement();

				if (className.startsWith(pack)) {//在当前所有可执行的类里面查找包含有该包名的所有类
					classNameList.add(mods.mainLoader().loadClass(className));
				}
			}
		} catch (IOException | ClassNotFoundException e) {
			Log.err(e);
		}
		return classNameList;
	}
	public static Set<Class<?>> getClasses0(String pack) {
		// 第一个class类的集合
		Set<Class<?>> classes = new LinkedHashSet<>();
		// 是否循环迭代
		boolean recursive = true;
		// 获取包的名字 并进行替换
		String packageDirName = pack.replace('.', '/');
		// 定义一个枚举的集合 并进行循环来处理这个目录下的things
		Enumeration<URL> dirs;
		try {
			dirs = mods.mainLoader().getResources(packageDirName);
			// 循环迭代下去
			while (dirs.hasMoreElements()) {
				// 获取下一个元素
				URL url = dirs.nextElement();
				// 得到协议的名称
				String protocol = url.getProtocol();
				// 如果是以文件的形式保存在服务器上
				if ("file".equals(protocol)) {
					// 获取包的物理路径
					String filePath = URLDecoder.decode(url.getFile(), StandardCharsets.UTF_8);
					// 以文件的方式扫描整个包下的文件 并添加到集合中
					findClassesInPackageByFile(pack, filePath, recursive, classes);
				} else if ("jar".equals(protocol)) {
					// 如果是jar包文件
					// 定义一个JarFile
					System.out.println("jar类型的扫描");
					JarFile jar;
					try {
						// 获取jar
						jar = ((JarURLConnection) url.openConnection()).getJarFile();
						// 从此jar包 得到一个枚举类
						Enumeration<JarEntry> entries = jar.entries();
						findClassesInPackageByJar(pack, entries, packageDirName, recursive, classes);
					} catch (IOException e) {
						// log.error("在扫描用户定义视图时从jar包获取文件出错");
						e.printStackTrace();
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return classes;
	}
	/* 以文件的形式来获取包下的所有Class
	 *
	 * @param packageName
	 * @param packagePath
	 * @param recursive
	 * @param classes
	 */
	private static void findClassesInPackageByFile(String packageName, String packagePath, final boolean recursive,
																								 Set<Class<?>> classes) {
		// 获取此包的目录 建立一个File
		File dir = new File(packagePath);
		// 如果不存在或者 也不是目录就直接返回
		if (!dir.exists() || !dir.isDirectory()) {
			// log.warn("用户定义包名 " + packageName + " 下没有任何文件");
			return;
		}
		// 如果存在 就获取包下的所有文件 包括目录
		// 自定义过滤规则 如果可以循环(包含子目录) 或则是以.class结尾的文件(编译好的java类文件)
		File[] dirfiles = dir.listFiles(file ->
		 (recursive && file.isDirectory()) || file.getName().endsWith(".class"));
		// 循环所有文件
		assert dirfiles != null;
		for (File file : dirfiles) {
			// 如果是目录 则继续扫描
			if (file.isDirectory()) {
				findClassesInPackageByFile(packageName + "." + file.getName(), file.getAbsolutePath(), recursive, classes);
			} else {
				// 如果是java类文件 去掉后面的.class 只留下类名
				String className = file.getName().substring(0, file.getName().length() - 6);
				try {
					// 添加到集合中去
					// classes.add(Class.forName(packageName + '.' +
					// className));
					// 经过回复同学的提醒，这里用forName有一些不好，会触发static方法，没有使用classLoader的load干净
					classes.add(Thread.currentThread().getContextClassLoader().loadClass(packageName + '.' + className));
				} catch (ClassNotFoundException e) {
					// log.error("添加用户自定义视图类错误 找不到此类的.class文件");
					e.printStackTrace();
				}
			}
		}
	}
	/**
	 * 以jar的形式来获取包下的所有Class
	 *
	 * @param packageName    包名
	 * @param entries        ？？？
	 * @param packageDirName ？？？
	 * @param recursive      ？？？
	 * @param classes        ？？？
	 */
	private static void findClassesInPackageByJar(String packageName, Enumeration<JarEntry> entries,
																								String packageDirName, final boolean recursive,
																								Set<Class<?>> classes) {
		// 同样的进行循环迭代
		while (entries.hasMoreElements()) {
			// 获取jar里的一个实体 可以是目录 和一些jar包里的其他文件 如META-INF等文件
			JarEntry entry = entries.nextElement();
			String   name  = entry.getName();
			// 如果是以/开头的
			if (name.charAt(0) == '/') {
				// 获取后面的字符串
				name = name.substring(1);
			}
			// 如果前半部分和定义的包名相同
			if (name.startsWith(packageDirName)) {
				int idx = name.lastIndexOf('/');
				// 如果以"/"结尾 是一个包
				if (idx != -1) {
					// 获取包名 把"/"替换成"."
					packageName = name.substring(0, idx).replace('/', '.');
				}
				// 如果可以迭代下去 并且是一个包
				if ((idx != -1) || recursive) {
					// 如果是一个.class文件 而且不是目录
					if (name.endsWith(".class") && !entry.isDirectory()) {
						// 去掉后面的".class" 获取真正的类名
						String className = name.substring(packageName.length() + 1, name.length() - 6);
						try {
							// 添加到classes
							classes.add(Class.forName(packageName + '.' + className));
						} catch (ClassNotFoundException e) {
							// .error("添加用户自定义视图类错误 找不到此类的.class文件");
							e.printStackTrace();
						}
					}
				}
			}
		}
	}
}
