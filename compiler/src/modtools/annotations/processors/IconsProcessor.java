package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticFlag;
import com.sun.tools.javac.util.List;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import java.io.*;
import java.util.*;

@AutoService(Processor.class)
public class IconsProcessor extends BaseProcessor<ClassSymbol> {
	public void process2() {
		super.process2();
	}
	public void dealElement(ClassSymbol element) throws Throwable {
		JCClassDecl root = trees.getTree(element);
		root.defs = List.nil();
		IconAnn icons = getAnnotationByElement(IconAnn.class, element, false);

		var unit = (JCCompilationUnit) trees.getPath(element).getCompilationUnit();
		if (!root.name.toString().endsWith("c")) {
			log.useSource(unit.sourcefile);
			log.error(DiagnosticFlag.MANDATORY, unit.toString().indexOf(root.name.toString()) + root.name.length() - 1,
			 SPrinter.err("The class name must end with 'c' to use the @IconAnn annotation."));
			return;
		}

		((ClassType) root.sym.type).supertype_field = mSymtab.objectType;
		ClassSymbol drawableSymbol = findClassSymbol("arc.scene.style.TextureRegionDrawable");
		ClassType   drawable       = (ClassType) drawableSymbol.type;
		ClassType   texture        = findType("arc.graphics.Texture");
		// ClassType   pixmap         = findType("arc.graphics.Pixmap");

		addImport(element, drawableSymbol);
		addImport(element, /* TextureRegion */findClassSymbol("arc.graphics.g2d.TextureRegion"));
		addImport(element, findClassSymbol("arc.graphics.Texture"));
		// addImport(element, findClassSymbol("arc.Core"));

		StringBuilder sb = new StringBuilder();
		sb.append("if (modName == null) modName = mindustry.Vars.mods.getMod(")
		 .append(icons.mainClass().getName()).append(".class).name;");
		stringType.tsym.owner.kind = Kind.VAR;
		texture.tsym.owner.kind = Kind.VAR;
		// map.tsym.owner.kind = Kind.VAR;
		addField(root, Flags.STATIC | Flags.PUBLIC, stringType,
		 "modName", null).vartype = mMaker.Ident(stringType.tsym);
		stringType.tsym.owner.kind = Kind.PCK;
		texture.tsym.owner.kind = Kind.PCK;
		// map.tsym.owner.kind = Kind.PCK;

		// 添加TextureRegionDrawable的构造方法n()
		// 添加参数name
		JCVariableDecl name = makeVar(Flags.PARAMETER, stringType, "name", null, root.sym);
		name.vartype = mMaker.Ident(stringType.tsym);
		JCMethodDecl n = mMaker.MethodDef(
		 mMaker.Modifiers(Flags.PRIVATE | Flags.STATIC),
		 ns("n"), mMaker.Ident(drawableSymbol), List.nil(), List.of(name),
		 List.nil(), parseBlock("{return (TextureRegionDrawable)arc.Core.atlas.drawable(modName+\"-\"+name);}"), null);
		root.defs = root.defs.append(n);
		for (File fi : findAll(new File("./assets/" + icons.iconDir()))
		 .stream().filter(f -> extension(f).equalsIgnoreCase("png")).toArray(File[]::new)) {
			Kind last = drawable.tsym.owner.kind;
			drawable.tsym.owner.kind = Kind.VAR;
			String f_name = kebabToCamel(nameWithoutExtension(fi));
			addField(root, Flags.STATIC | Flags.PUBLIC,
			 drawable, f_name, null);
			drawable.tsym.owner.kind = last;
			sb.append(f_name).append("=n(\"").append(nameWithoutExtension(fi)).append("\");");
		}

		// 添加load方法
		JCMethodDecl load = mMaker.MethodDef(
		 mMaker.Modifiers(Flags.STATIC | Flags.PUBLIC),
		 ns("load"), mMaker.TypeIdent(TypeTag.VOID), List.nil(), List.nil(),
		 List.nil(), parseBlock("{" + sb + "}"), null);
		root.defs = root.defs.append(load);

		// var lastPackage = unit.packge;
		String s       = root.name.toString();
		String genName = s.substring(0, s.length() - 1);
		root.name = ns(genName);
		root.mods = mMaker.Modifiers(1);
		String content = unit.toString();
		root.mods = mMaker.Modifiers(0);
		root.name = ns(s);
		var source = /* unit.getSourceFile() */
		 mFiler.createSourceFile((icons.genPackage().equals(".") ? element.getEnclosingElement().toString() : icons.genPackage())
		                         + "." + genName);
		Writer writer = source.openWriter();
		writer.write(content);
		root.defs = List.nil();
		// unit.packge = lastPackage;
		writer.flush();
		writer.close();
		// Log.info(root);
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
