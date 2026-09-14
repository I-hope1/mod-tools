package hope.magic.js.module;

import java.util.Objects;

/**
 * 表示已解析的模块源数据，包含规范化标识、源码内容、文件名与目录路径。
 */
public class ModuleSource {
	private final String  id;
	private final String  code;
	private final String  filename;
	private final String  dirname;
	private final boolean isJson;

	public ModuleSource(String id, String code, String filename, String dirname, boolean isJson) {
		this.id = Objects.requireNonNull(id, "id cannot be null");
		this.code = Objects.requireNonNull(code, "code cannot be null");
		this.filename = filename != null ? filename : id;
		this.dirname = dirname != null ? dirname : "";
		this.isJson = isJson;
	}

	public String getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getFilename() {
		return filename;
	}

	public String getDirname() {
		return dirname;
	}

	public boolean isJson() {
		return isJson;
	}

	@Override
	public String toString() {
		return "ModuleSource{" +
		       "id='" + id + '\'' +
		       ", filename='" + filename + '\'' +
		       ", isJson=" + isJson +
		       '}';
	}
}
