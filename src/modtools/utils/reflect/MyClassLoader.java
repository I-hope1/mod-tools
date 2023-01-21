package modtools.utils.reflect;

import java.net.URL;
import java.util.ArrayList;

public class MyClassLoader extends ClassLoader {
	private final ArrayList<ClassLoader> children = new ArrayList<>();
	private final ThreadLocal<Boolean> inChild = ThreadLocal.withInitial(() -> Boolean.FALSE);

	public MyClassLoader(ClassLoader parent) {
		super(parent);
	}

	public void addChild(ClassLoader child) {
		children.add(child);
	}

	public Class<?> findClass(String name) throws ClassNotFoundException {
		// Log.info(name);
		if (inChild.get()) {
			inChild.set(false);
			throw new ClassNotFoundException(name);
		} else {
			ClassNotFoundException last = null;
			int size = children.size();
			int i = 0;

			while (i < size) {
				try {
					Class<?> cls;
					try {
						inChild.set(true);
						cls = children.get(i).loadClass(name);
					} finally {
						inChild.set(false);
					}

					return cls;
				} catch (ClassNotFoundException e) {
					last = e;
					++i;
				}
			}

			throw last == null ? new ClassNotFoundException(name) : last;
		}
	}

	@Override
	public URL getResource(String name) {
		if (inChild.get()) {
			inChild.set(false);
			throw new RuntimeException();
		} else {
			RuntimeException last = null;
			int size = children.size();
			int i = 0;

			while (i < size) {
				try {
					URL url;
					try {
						inChild.set(true);
						url = children.get(i).getResource(name);
					} finally {
						inChild.set(false);
					}

					return url;
				} catch (RuntimeException e) {
					last = e;
					++i;
				}
			}

			throw last == null ? new RuntimeException(name) : last;
		}
	}
}
