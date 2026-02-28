package modtools.annotations.processors;


import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import modtools.annotations.BaseProcessor;
import modtools.annotations.msic.ModuleOpen;
import modtools.annotations.unsafe.Replace;

import javax.annotation.processing.Processor;
import java.util.*;
import java.util.stream.Collectors;

// @AutoService(Processor.class)
public class ModuleOpenProc extends BaseProcessor<MethodSymbol> {
	ClassSymbol c_unsafe;
	ClassSymbol c_field;
	ClassSymbol c_map;
	public void lazyInit() {
		c_unsafe = findClassSymbolAny("sun.misc.Unsafe");
		c_field = findClassSymbolByBoot("java.lang.reflect.Field");
		c_map = findClassSymbolByBoot("java.util.Map");
	}
	HashSet<PackageSymbol> all = Replace.needExportedApi;
	public void dealElement(MethodSymbol element) throws Throwable {
		JCMethodDecl methodDecl = trees.getTree(element);
		ModuleOpen   annotation = element.getAnnotation(ModuleOpen.class);

		if (annotation.showUsed()) {
			all.forEach(SPrinter::println);
		}

		addImport(element, c_unsafe);
		addImport(element, c_field);
		//noinspection StringTemplateMigration
		methodDecl.body = parseBlock(
		 "{try{" +
		 """
			// 获取unsafe
			Field f = Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			Unsafe unsafe = (Unsafe)f.get(null);
			// 设置模块
			f = Class.class.getDeclaredField("module");
			long  off = unsafe.objectFieldOffset(f);
			Module module = Object.class.getModule();
			unsafe.putObject(HopeReflect .class, off, module);
			""" +
		 all.stream().map(pkg ->  "jdk.internal.module.Modules.addExports(" + Replace.moduleRepresentClass.get(pkg.modle).getQualifiedName() + ".class.getModule(), \"" + pkg + "\");")
			.collect(Collectors.joining())
		 + "}catch(Exception e) {throw new RuntimeException(e);}}");

		println(methodDecl);
	}
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(ModuleOpen.class);
	}
}
