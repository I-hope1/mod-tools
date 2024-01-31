package modtools.files;

import arc.Files.FileType;
import arc.files.Fi;
import modtools.utils.io.FileUtils;

import java.io.*;
import java.net.URISyntaxException;
import java.util.Objects;

public class HFi extends Fi {
	public final ClassLoader loader;
	public final Fi          zipFile;
	public final String      ipath;
	public HFi(ClassLoader loader) {
		this(FileUtils.findRoot(), null, loader);
	}
	private HFi(Fi zipFile, String ipath, ClassLoader loader) {
		super(zipFile + (ipath == null ? "" : "/" + ipath), FileType.classpath);
		if (loader == null) throw new IllegalArgumentException("loader cannot be null.");
		this.zipFile = zipFile;
		this.ipath = ipath;
		this.loader = loader;
	}
	public InputStream read() {
		if (ipath == null) return zipFile.read();
		return loader.getResourceAsStream(ipath);
	}
	public boolean exists() {
		return loader.getResource(ipath) != null;
	}
	public OutputStream write() {
		throw new UnsupportedOperationException("HFi cannot write anything.");
	}
	public Fi child(String name) {
		return new HFi(zipFile, (ipath != null ? ipath + "/" : "") + name, loader);
	}
	public File file() {
		try {
			return new File(Objects.requireNonNull(loader.getResource(ipath)).toURI());
		} catch (URISyntaxException e) {
			return new File(ipath);
		}
	}
}