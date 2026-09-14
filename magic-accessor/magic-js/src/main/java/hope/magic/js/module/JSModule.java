package hope.magic.js.module;

import hope.magic.js.runtime.JSObject;

/**
 * CommonJS 模块对象 (module)，继承自 JSObject 以具备完整的 JS 属性访问与隐藏类加速能力。
 */
public class JSModule extends JSObject {
	private final String   id;
	private final String   filename;
	private final String   dirname;
	private final JSModule parent;
	private boolean        loaded;

	public JSModule(String id, String filename, String dirname, JSModule parent) {
		this.id = id != null ? id : "";
		this.filename = filename != null ? filename : this.id;
		this.dirname = dirname != null ? dirname : "";
		this.parent = parent;
		this.loaded = false;

		put("id", this.id);
		put("filename", this.filename);
		put("loaded", false);
		put("exports", new JSObject());
		if (parent != null) {
			put("parent", parent);
		}
	}

	public Object getExports() {
		return get("exports");
	}

	public void setExports(Object exports) {
		put("exports", exports);
	}

	public boolean isLoaded() {
		return loaded;
	}

	public void setLoaded(boolean loaded) {
		this.loaded = loaded;
		put("loaded", loaded);
	}

	public String getId() {
		return id;
	}

	public String getFilename() {
		return filename;
	}

	public String getDirname() {
		return dirname;
	}

	public JSModule getParent() {
		return parent;
	}

	@Override
	public String toString() {
		return "Module [id=" + id + ", loaded=" + loaded + "]";
	}
}
