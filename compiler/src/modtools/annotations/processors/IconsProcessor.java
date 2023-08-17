package modtools.annotations.processors;

import arc.files.Fi;
import arc.graphics.Texture;
import arc.scene.style.TextureRegionDrawable;
import arc.struct.ObjectMap;
import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import javax.lang.model.element.Element;
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
		var icons = getAnnotationByElement(IconAnn.class, element, false);

		((ClassType) root.sym.type).supertype_field = mSymtab.objectType;
		ClassType drawable = findType(TextureRegionDrawable.class.getName());
		ClassType texture  = findType(Texture.class.getName());
		ClassType map      = (ClassType) findType(ObjectMap.class.getName()).constType(123);
		map.typarams_field = List.of(stringType, texture);
		StringBuilder sb = new StringBuilder();
		addField(root, Flags.STATIC | Flags.PUBLIC, map,
		 "map", "mindustry.Vars.mods.getMod(" + icons.mainClass().getName()
						+ ".class).root.child(\"" + icons.iconDir()
						+ "\").findAll().asMap(arc.files.Fi::nameWithoutExtension,arc.graphics.Texture::new)"
		);
		for (Fi fi : Fi.get("./assets/" + icons.iconDir()).findAll(f -> f.extEquals("png"))) {
			addField(root, Flags.STATIC | Flags.PUBLIC,
			 drawable, fi.nameWithoutExtension(), null);
			sb.append(fi.nameWithoutExtension())
			 .append("=new arc.scene.style.TextureRegionDrawable(new arc.graphics.g2d.TextureRegion(map.get(\"")
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
		String content = unit.toString();
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
