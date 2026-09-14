package hope.magic.js.module;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于本地文件系统的模块解析器，完整支持：
 * 1. 相对路径 (./, ../) 与绝对路径解析；
 * 2. 扩展名自动补全 (.js, .json)；
 * 3. 目录作为模块 (package.json 中的 main 字段，或 fallback 至 index.js / index.json)。
 */
public class FileSystemModuleResolver implements ModuleResolver {
	private static final Pattern PACKAGE_MAIN_PATTERN = Pattern.compile("\"main\"\\s*:\\s*\"([^\"]+)\"");

	private final Path baseDir;

	public FileSystemModuleResolver() {
		this(Paths.get("").toAbsolutePath());
	}

	public FileSystemModuleResolver(Path baseDir) {
		this.baseDir = baseDir != null ? baseDir.toAbsolutePath().normalize() : Paths.get("").toAbsolutePath().normalize();
	}

	public Path getBaseDir() {
		return baseDir;
	}

	@Override
	public ModuleSource resolve(String specifier, JSModule parentModule) {
		if (specifier == null || specifier.isEmpty()) {
			return null;
		}

		// 移除 file: 前缀（若有）
		String pathStr = specifier;
		if (pathStr.startsWith("file:///") || pathStr.startsWith("file://")) {
			try {
				pathStr = Paths.get(java.net.URI.create(pathStr)).toString();
			} catch (Throwable ignored) {
				pathStr = pathStr.replaceFirst("^file:[/]+", "");
			}
		}

		Path startDir = baseDir;
		if (parentModule != null && parentModule.getDirname() != null && !parentModule.getDirname().isEmpty()) {
			try {
				Path pDir = Paths.get(parentModule.getDirname());
				if (Files.exists(pDir)) {
					startDir = pDir.toAbsolutePath().normalize();
				}
			} catch (Throwable ignored) {
			}
		}

		Path target;
		try {
			Path candidatePath = Paths.get(pathStr);
			if (candidatePath.isAbsolute()) {
				target = candidatePath.normalize();
			} else {
				target = startDir.resolve(candidatePath).normalize();
			}
		} catch (Throwable e) {
			return null;
		}

		Path matchedFile = findExactOrCandidateFile(target);
		if (matchedFile == null && !pathStr.startsWith(".") && !pathStr.startsWith("/") && !pathStr.contains(":")) {
			// 如果不是相对路径，也不是绝对路径，尝试从 baseDir 再找一次
			Path fallback = baseDir.resolve(pathStr).normalize();
			matchedFile = findExactOrCandidateFile(fallback);
		}

		if (matchedFile == null) {
			return null;
		}

		try {
			String code     = Files.readString(matchedFile, StandardCharsets.UTF_8);
			String absPath  = matchedFile.toAbsolutePath().normalize().toString().replace('\\', '/');
			Path   parent   = matchedFile.getParent();
			String dirPath  = parent != null ? parent.toAbsolutePath().normalize().toString().replace('\\', '/') : "";
			boolean isJson  = absPath.endsWith(".json");
			return new ModuleSource(absPath, code, absPath, dirPath, isJson);
		} catch (IOException e) {
			return null;
		}
	}

	private Path findExactOrCandidateFile(Path target) {
		if (Files.isRegularFile(target)) {
			return target;
		}

		// 1. 尝试添加 .js
		Path jsCandidate = target.resolveSibling(target.getFileName().toString() + ".js");
		if (Files.isRegularFile(jsCandidate)) {
			return jsCandidate;
		}

		// 2. 尝试添加 .json
		Path jsonCandidate = target.resolveSibling(target.getFileName().toString() + ".json");
		if (Files.isRegularFile(jsonCandidate)) {
			return jsonCandidate;
		}

		// 3. 如果是目录，尝试读取 package.json / index.js / index.json
		if (Files.isDirectory(target)) {
			Path pkgJson = target.resolve("package.json");
			if (Files.isRegularFile(pkgJson)) {
				try {
					String content = Files.readString(pkgJson, StandardCharsets.UTF_8);
					Matcher matcher = PACKAGE_MAIN_PATTERN.matcher(content);
					if (matcher.find()) {
						String mainFile = matcher.group(1);
						Path mainTarget = target.resolve(mainFile).normalize();
						Path resolvedMain = findExactOrCandidateFile(mainTarget);
						if (resolvedMain != null) return resolvedMain;
					}
				} catch (Throwable ignored) {
				}
			}

			Path indexJs = target.resolve("index.js");
			if (Files.isRegularFile(indexJs)) {
				return indexJs;
			}
			Path indexJson = target.resolve("index.json");
			if (Files.isRegularFile(indexJson)) {
				return indexJson;
			}
		}

		return null;
	}
}
