package hope.magic.js.module;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 基于 Java Classpath / Jar 包资源文件的模块解析器。
 * 支持从 classpath 或 jar 内部加载脚本库。
 */
public class ClasspathModuleResolver implements ModuleResolver {
	private final ClassLoader classLoader;
	private final String      prefix;

	public ClasspathModuleResolver() {
		this(Thread.currentThread().getContextClassLoader() != null
		     ? Thread.currentThread().getContextClassLoader()
		     : ClasspathModuleResolver.class.getClassLoader(), "");
	}

	public ClasspathModuleResolver(ClassLoader classLoader, String prefix) {
		this.classLoader = classLoader != null ? classLoader : ClasspathModuleResolver.class.getClassLoader();
		this.prefix = prefix != null ? prefix.replaceAll("^/+|/+$", "") : "";
	}

	@Override
	public ModuleSource resolve(String specifier, JSModule parentModule) {
		if (specifier == null || specifier.isEmpty()) {
			return null;
		}

		boolean isExplicitClasspath = specifier.startsWith("classpath:");
		String path = isExplicitClasspath ? specifier.substring("classpath:".length()) : specifier;
		path = path.replaceAll("^/+", "");

		if (parentModule != null && parentModule.getId() != null && parentModule.getId().startsWith("classpath:/") && (specifier.startsWith("./") || specifier.startsWith("../"))) {
			String parentPath = parentModule.getId().substring("classpath:/".length());
			int lastSlash = parentPath.lastIndexOf('/');
			String parentDir = lastSlash >= 0 ? parentPath.substring(0, lastSlash) : "";
			path = normalizeRelativePath(parentDir, specifier);
		}

		if (!prefix.isEmpty() && !path.startsWith(prefix + "/")) {
			path = prefix + "/" + path;
		}

		String[] candidates = new String[]{
				path,
				path + ".js",
				path + ".json",
				path + "/index.js",
				path + "/index.json"
		};

		for (String candidate : candidates) {
			InputStream in = classLoader.getResourceAsStream(candidate);
			if (in != null) {
				try (in) {
					byte[] bytes = in.readAllBytes();
					String code = new String(bytes, StandardCharsets.UTF_8);
					String id = "classpath:/" + candidate;
					int lastSlash = candidate.lastIndexOf('/');
					String dirname = lastSlash >= 0 ? "classpath:/" + candidate.substring(0, lastSlash) : "classpath:/";
					boolean isJson = candidate.endsWith(".json");
					return new ModuleSource(id, code, id, dirname, isJson);
				} catch (Throwable ignored) {
				}
			}
		}

		return null;
	}

	private String normalizeRelativePath(String baseDir, String relative) {
		String combined = baseDir.isEmpty() ? relative : baseDir + "/" + relative;
		String[] segments = combined.split("[/\\\\]+");
		java.util.List<String> list = new java.util.ArrayList<>();
		for (String seg : segments) {
			if (seg.isEmpty() || ".".equals(seg)) continue;
			if ("..".equals(seg)) {
				if (!list.isEmpty()) {
					list.remove(list.size() - 1);
				}
			} else {
				list.add(seg);
			}
		}
		return String.join("/", list);
	}
}
