package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticFlag;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import java.io.*;
import java.util.*;

@AutoService(Processor.class)
public class IconsProcessor extends BaseProcessor<ClassSymbol> {
	@Override
	public void dealElement(ClassSymbol element) throws Throwable {
		JCClassDecl root  = trees.getTree(element);
		// 这里的getAnnotationByElement会直接修改Class的引用，获取class.getName()不需要mirror
		IconAnn     icons = getAnnotationByElement(IconAnn.class, element, false);

		var unit = (JCCompilationUnit) trees.getPath(element).getCompilationUnit();
		if (!root.name.toString().endsWith("c")) {
			log.error(DiagnosticFlag.MANDATORY, unit.toString().indexOf(root.name.toString()) + root.name.length() - 1,
			 SPrinter.err("The class name must end with 'c' to use the @IconAnn annotation."));
			return;
		}

		// 获取原类名和去掉 'c' 的新类名
		String s       = root.name.toString();
		String genName = s.substring(0, s.length() - 1);

		// 解析包名和生成的全限定名
		String packageName = icons.genPackage().equals(".") ? element.getEnclosingElement().toString() : icons.genPackage();
		String flatName    = packageName + "." + genName;

		// 使用 StringBuilder 直接构造新文件的代码文本（完全不修改原 root 和 unit）
		StringBuilder out = new StringBuilder();

		// 写入包名
		out.append("package ").append(packageName).append(";\n\n");

		// 复制原文件的 imports
		for (JCTree def : unit.defs) {
			if (def instanceof JCImport) {
				out.append(def).append("\n");
			}
		}
		// 添加我们生成的类所需的 imports
		out.append("import arc.scene.style.TextureRegionDrawable;\n");
		out.append("import arc.graphics.g2d.TextureRegion;\n");
		out.append("import arc.graphics.Texture;\n\n");

		// 声明新类 (Public 修饰)
		out.append("public class ").append(genName).append(" {\n");

		// modName 字段
		out.append("    public static String modName;\n\n");

		// 准备图片目录
		File[] pngs = findAll(new File(System.getProperty("user.dir") + "/assets/" + icons.iconDir()))
		 .stream().filter(f -> extension(f).equalsIgnoreCase("png")).toArray(File[]::new);

		// 生成每个图片的字段，并同时收集 load 方法中需要的赋值逻辑
		StringBuilder loadBody = new StringBuilder();

		String mainClassName = icons.mainClass().getName();

		loadBody.append("        if (modName == null) modName = mindustry.Vars.mods.getMod(")
		 .append(mainClassName).append(".class).name;\n");

		for (File fi : pngs) {
			String f_name = kebabToCamel(nameWithoutExtension(fi));
			out.append("    public static TextureRegionDrawable ").append(f_name).append(";\n");
			loadBody.append("        ").append(f_name).append(" = n(\"").append(nameWithoutExtension(fi)).append("\");\n");
		}

		// 生成 n() 方法
		out.append("\n    private static TextureRegionDrawable n(String name) {\n");
		out.append("        return (TextureRegionDrawable) arc.Core.atlas.drawable(modName + \"-\" + name);\n");
		out.append("    }\n\n");

		// 生成 load() 方法
		out.append("    public static void load() {\n");
		out.append(loadBody);
		out.append("    }\n");

		// 结束类
		out.append("}\n");

		// --写入到新文件中--
		Writer writer = null;
		try {
			var source = mFiler.createSourceFile(flatName, element);
			writer = source.openWriter();
			writer.write(out.toString());
			writer.flush();
		} catch (IOException e) {
			println("Error when writing file.");
			err(e);
		} finally {
			if (writer != null) {
				writer.close();
			}
		}
	}
	ArrayList<File> findAll(File file) {
		return findAll(new ArrayList<>(), file);
	}
	ArrayList<File> findAll(ArrayList<File> list, File file) {
		File[] files = file.listFiles();
		if (files == null) return list;
		for (File f : files) {
			if (f.isDirectory()) {
				list.addAll(findAll(f));
			} else {
				list.add(f);
			}
		}
		return list;
	}
	public String extension(File file) {
		String name     = file.getName();
		int    dotIndex = name.lastIndexOf('.');
		if (dotIndex == -1) return "";
		return name.substring(dotIndex + 1);
	}
	public String nameWithoutExtension(File file) {
		String name     = file.getName();
		int    dotIndex = name.lastIndexOf('.');
		if (dotIndex == -1) return name;
		return name.substring(0, dotIndex);
	}
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(IconAnn.class);
	}
}
