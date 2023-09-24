package modtools.annotations.processors;

import arc.util.Log;
import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import modtools.annotations.*;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.util.Set;

@AutoService({Processor.class})
public class DataColorProcessor extends BaseProcessor<Element> {
	ClassSymbol settingUI;
	public void dealElement(Element element) throws Throwable {
		if (element.getKind() == ElementKind.FIELD) {
			JCClassDecl    decl   = trees.getTree((TypeElement) element.getEnclosingElement());
			JCMethodDecl   method = findChild(decl, Tag.METHODDEF, d -> d.name.contentEquals("settingColor"));
			JCVariableDecl field  = (JCVariableDecl) trees.getTree(element);
			String         key    = field.name.toString();
			mMaker.at(method);
			JCVariableDecl param = makeVar0(Flags.PARAMETER, null, "c", null, method.sym);
			param.startPos = 0;
			// Generate SettingsUI.colorBlock(t, "pad", data(), "padColor", padColor, c -> padColor = c.rgba());
			method.body.stats = method.body.stats.append(
			 mMaker.Exec(mMaker.Apply(List.nil(),
				mMaker.Select(mMaker.Ident(settingUI), names.fromString("colorBlock")),
				List.of(mMaker.Ident(names.fromString("t")),
				 mMaker.Literal(key.replace("Color", "")),
				 mMaker.Apply(List.nil(), mMaker.Select(mMaker.This(decl.sym.type), names.fromString("data")), List.nil()),
				 mMaker.Literal(key),
				 mMaker.Ident(field.sym),
				 mMaker.Lambda(
					List.of(param),
					mMaker.Assign(mMaker.Ident(field.sym),
					 mMaker.Apply(List.nil(), mMaker.Select(mMaker.Ident(param),
						names.fromString("rgba")), List.nil()
					 )
					)
					/* parseExpression(fieldName + "=t.enabled()") */
				 )
				)
			 ))
			);
			// Log.info(method);
			field.init = mMaker.Apply(
			 List.nil(),
			 mMaker.Select(mMaker.Apply(List.nil(), mMaker.Select(mMaker.This(decl.sym.type), names.fromString("data")), List.nil()),
				names.fromString("get0xInt")),
			 List.of(
				mMaker.Literal(key),
				field.init
			 )
			);
		}
	}
	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(DataColorFieldInit.class.getCanonicalName());
	}
	public void init() {
		settingUI = findClassSymbol("modtools.ui.content.SettingsUI");
	}
}
