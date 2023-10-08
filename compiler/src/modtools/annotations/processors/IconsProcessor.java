package modtools.annotations.processors;

import arc.files.Fi;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.scene.style.TextureRegionDrawable;
import arc.struct.ObjectMap;
import arc.util.Strings;
import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.Name;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.io.Writer;
import java.util.Set;

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
		ClassSymbol drawableSymbol = findClassSymbol(TextureRegionDrawable.class.getName());
		ClassType   drawable       = (ClassType) drawableSymbol.type;
		ClassType   texture        = findType(Texture.class.getName());
		ClassType   map            = (ClassType) findType(ObjectMap.class.getName()).constType(123);
		map.typarams_field = List.of(stringType, texture);

		addImport((TypeElement) element, (ClassSymbol) map.tsym);
		addImport((TypeElement) element, drawableSymbol);
		addImport((TypeElement) element, findClassSymbol(Fi.class.getName()));
		addImport((TypeElement) element, findClassSymbol(TextureRegion.class.getName()));
		addImport((TypeElement) element, findClassSymbol(Texture.class.getName()));
		addImport((TypeElement) element, findClassSymbol("mindustry.Vars"));

		StringBuilder sb = new StringBuilder();
		stringType.tsym.owner.kind = Kind.VAR;
		texture.tsym.owner.kind = Kind.VAR;
		map.tsym.owner.kind = Kind.VAR;
		addField(root, Flags.STATIC | Flags.PUBLIC | Flags.FINAL, map,
		 "map", "Vars.mods.getMod(" + icons.mainClass().getName()
						+ ".class).root.child(\"" + icons.iconDir()
						+ "\").findAll().asMap(Fi::nameWithoutExtension, Texture::new)"
		);
		stringType.tsym.owner.kind = Kind.PCK;
		texture.tsym.owner.kind = Kind.PCK;
		map.tsym.owner.kind = Kind.PCK;

		for (Fi fi : Fi.get("./assets/" + icons.iconDir()).findAll(f -> f.extEquals("png"))) {
			Kind last = drawable.tsym.owner.kind;
			drawable.tsym.owner.kind = Kind.VAR;
			String f_name = Strings.kebabToCamel(fi.nameWithoutExtension());
			addField(root, Flags.STATIC | Flags.PUBLIC,
			 drawable, f_name, null);
			drawable.tsym.owner.kind = last;
			sb.append(f_name)
			 .append("=new TextureRegionDrawable(new TextureRegion(map.get(\"")
			 .append(fi.nameWithoutExtension())
			 .append("\")));");
		}

		JCBlock x = parseBlock(Flags.STATIC, "{" + sb + "}");
		x.pos = 1000;
		root.defs = root.defs.append(x);
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
	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(IconAnn.class.getCanonicalName());
	}
	private static Name ns(String gen) {
		return names.fromString(gen);
	}
}
