package hope.magic.js.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 组合模块解析器，使用责任链模式按顺序依次尝试各个子解析器。
 * 默认具备：
 * 1. VirtualModuleResolver (优先查找内存虚拟模块/Java宿主注入)
 * 2. FileSystemModuleResolver (查找本地磁盘文件系统)
 * 3. ClasspathModuleResolver (查找类路径/Jar包内嵌资源)
 */
public class CompositeModuleResolver implements ModuleResolver {
	private final List<ModuleResolver>   resolvers = new CopyOnWriteArrayList<>();
	private final VirtualModuleResolver    virtualResolver;
	private final FileSystemModuleResolver fileSystemResolver;
	private final ClasspathModuleResolver  classpathResolver;

	public CompositeModuleResolver() {
		this.virtualResolver = new VirtualModuleResolver();
		this.fileSystemResolver = new FileSystemModuleResolver();
		this.classpathResolver = new ClasspathModuleResolver();

		resolvers.add(this.virtualResolver);
		resolvers.add(this.fileSystemResolver);
		resolvers.add(this.classpathResolver);
	}

	public CompositeModuleResolver(List<ModuleResolver> initialResolvers) {
		this.virtualResolver = new VirtualModuleResolver();
		this.fileSystemResolver = new FileSystemModuleResolver();
		this.classpathResolver = new ClasspathModuleResolver();

		if (initialResolvers != null) {
			resolvers.addAll(initialResolvers);
		}
	}

	public CompositeModuleResolver addResolver(ModuleResolver resolver) {
		if (resolver != null && !resolvers.contains(resolver)) {
			resolvers.add(resolver);
		}
		return this;
	}

	public CompositeModuleResolver addResolverFirst(ModuleResolver resolver) {
		if (resolver != null && !resolvers.contains(resolver)) {
			resolvers.add(0, resolver);
		}
		return this;
	}

	public VirtualModuleResolver getVirtualResolver() {
		return virtualResolver;
	}

	public FileSystemModuleResolver getFileSystemResolver() {
		return fileSystemResolver;
	}

	public ClasspathModuleResolver getClasspathResolver() {
		return classpathResolver;
	}

	public List<ModuleResolver> getResolvers() {
		return Collections.unmodifiableList(resolvers);
	}

	@Override
	public ModuleSource resolve(String specifier, JSModule parentModule) {
		for (ModuleResolver r : resolvers) {
			try {
				ModuleSource source = r.resolve(specifier, parentModule);
				if (source != null) {
					return source;
				}
			} catch (Throwable ignored) {
			}
		}
		return null;
	}
}
