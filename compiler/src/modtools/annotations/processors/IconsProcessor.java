package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.io.*;
import java.util.*;

@AutoService({Processor.class})
public class IconsProcessor extends BaseProcessor {
	public static final String GEN_PREFIX = "modtools.gen.";

	public void dealElement(Element element) throws Throwable {
		JCClassDecl root = /* new JCClassDecl(maker.Modifiers(Flags.PUBLIC),
		 ns(name), List.nil(), null, List.nil(), List.nil(),
		 List.nil(), symbol) {} */(JCClassDecl) trees.getTree(element);
		root.defs = List.nil();
		IconAnn icons = getAnnotationByElement(IconAnn.class, element, false);

		((ClassType) root.sym.type).supertype_field = mSymtab.objectType;
		ClassSymbol drawableSymbol = findClassSymbol("arc.scene.style.TextureRegionDrawable");
		ClassType   drawable       = (ClassType) drawableSymbol.type;
		ClassType   texture        = findType("arc.graphics.Texture");
		ClassType   pixmap         = findType("arc.graphics.Pixmap");
		ClassType   fiType         = findType("arc.files.Fi");


		ClassType map = (ClassType) findType("arc.struct.ObjectMap").constType(123);
		map.typarams_field = List.of(stringType, pixmap);

		addImport(element, map);
		addImport(element, drawableSymbol);
		// addImport(element, fiType);
		addImport(element, pixmap);
		addImport(element, /* Pixmaps */findClassSymbol("arc.graphics.Pixmaps"));
		addImport(element, /* TextureRegion */findClassSymbol("arc.graphics.g2d.TextureRegion"));
		addImport(element, findClassSymbol("arc.graphics.Texture"));
		addImport(element, findClassSymbol("arc.Core"));

		StringBuilder sb = new StringBuilder();
		stringType.tsym.owner.kind = Kind.VAR;
		texture.tsym.owner.kind = Kind.VAR;
		map.tsym.owner.kind = Kind.VAR;
		addField(root, Flags.STATIC | Flags.PUBLIC | Flags.FINAL, fiType,
		 "dir", "mindustry.Vars.mods.getMod(" + icons.mainClass().getName()
						+ ".class).root.child(\"" + icons.iconDir()
						+ "\")"
		);
		stringType.tsym.owner.kind = Kind.PCK;
		texture.tsym.owner.kind = Kind.PCK;
		map.tsym.owner.kind = Kind.PCK;

		// 添加TextureRegionDrawable的构造方法n()
		// 添加参数name
		JCVariableDecl name = makeVar(Flags.PARAMETER, stringType, "name", null, root.sym);
		JCMethodDecl n = mMaker.MethodDef(
		 mMaker.Modifiers(Flags.PRIVATE | Flags.STATIC),
		 ns("n"), mMaker.Ident(drawableSymbol), List.nil(), List.of(name),
		 List.nil(), parseBlock("{Texture t = new Texture(dir.child(name));" +
															 "t.setFilter(Texture.TextureFilter.linear);" +
															 "return new TextureRegionDrawable(new TextureRegion(t));" +
															 "}"), null);
		root.defs = root.defs.append(n);
		for (File fi : findAll(new File("./assets/" + icons.iconDir()))
		 .stream().filter(f -> extension(f).equalsIgnoreCase("png")).toArray(File[]::new)) {
			Kind last = drawable.tsym.owner.kind;
			drawable.tsym.owner.kind = Kind.VAR;
			String f_name = kebabToCamel(nameWithoutExtension(fi));
			addField(root, Flags.STATIC | Flags.PUBLIC,
			 drawable, f_name, null);
			drawable.tsym.owner.kind = last;
			sb.append(f_name).append("=n(\"").append(fi.getName()).append("\");");
		}

		// 添加load方法
		JCMethodDecl load = mMaker.MethodDef(
		 mMaker.Modifiers(Flags.STATIC | Flags.PUBLIC),
		 ns("load"), mMaker.TypeIdent(TypeTag.VOID), List.nil(), List.nil(),
		 List.nil(), parseBlock("{" + sb + "}"), null);
		root.defs = root.defs.append(load);
		// JCBlock x = parseBlock(Flags.STATIC, "{" + sb + "}");
		// x.pos = 1000;
		// root.defs = root.defs.append(x);
		// Log.info(root);
		var unit = (JCCompilationUnit) trees.getPath(element).getCompilationUnit();

		// var lastPackage = unit.packge;
		String s       = root.name.toString();
		String genName = s.substring(0, s.length() - 1);
		root.name = ns(genName);
		root.mods = mMaker.Modifiers(1);
		String content = unit.toString();
		root.mods = mMaker.Modifiers(0);
		root.name = ns(s);
		var source = /* unit.getSourceFile() */
		 mFiler.createSourceFile(GEN_PREFIX + genName);
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
	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(IconAnn.class.getCanonicalName());
	}
}
