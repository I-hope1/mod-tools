package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
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
		// 这里的getAnnotationByElement会直接修改Class的引用，获取class.getName()不需要mirror
		IconAnn icons = getAnnotationByElement(IconAnn.class, element, false);
		// println(element + ":" +icons);
		if (icons.skip()) return;

		if (!element.name.toString().endsWith("c")) {
			log.error(DiagnosticFlag.MANDATORY, 0,
			 SPrinter.err("The class name '" + element.name + "' must end with 'c' to use the @IconAnn annotation."));
			return;
		}

		// 获取原类名和去掉 'c' 的新类名
		String s       = element.name.toString();
		String genName = s.substring(0, s.length() - 1);

		// 解析包名和生成的全限定名
		String packageName = icons.genPackage().equals(".") ? element.getEnclosingElement().toString() : icons.genPackage();
		String flatName    = packageName + "." + genName;

		ClassSymbol typeElement = elements.getTypeElement(flatName);
		if (typeElement != null) {
			// log.error(SPrinter.err(typeElement + ":" + typeElement.members()));
			return;
		}

		// 图片名（不含扩展名）决定生成哪些字段
		List<String> pngNames = new ArrayList<>();
		for (File file : findAll(new File(System.getProperty("user.dir") + "/assets/" + icons.iconDir()))) {
			if (extension(file).equalsIgnoreCase("png")) pngNames.add(nameWithoutExtension(file));
		}

		String source = IconsSource.render(packageName, genName,
		 element.getQualifiedName().toString(), icons.mainClass().getName(), pngNames);

		try (Writer writer = mFiler.createSourceFile(flatName, element).openWriter()) {
			writer.write(source);
		} catch (IOException | RuntimeException e) {
			println("Error when writing file.");
			err(e);
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
