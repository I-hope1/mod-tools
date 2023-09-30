package modtools.annotations.processors.fieldinit;

import arc.struct.Seq;
import arc.util.Log;
import com.google.auto.service.AutoService;
import com.sun.source.tree.*;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import modtools.annotations.*;
import modtools.annotations.builder.DataColorFieldInit;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.util.*;

@AutoService({Processor.class})
public class ColorProcessor extends BaseProcessor<Element> implements DataUtils {
	/* method: settingColor */
	public void dealElement(Element element) {
		if (element.getKind() == ElementKind.FIELD) {
			DataColorFieldInit anno  = element.getAnnotation(DataColorFieldInit.class);
			JCClassDecl        decl  = trees.getTree((TypeElement) element.getEnclosingElement());
			JCVariableDecl     field = (JCVariableDecl) trees.getTree(element);
			String             key   = field.name.toString().substring(anno.fieldPrefix().length());
			JCExpression       init  = field.init;
			JCExpression       data  = getData(anno.data(), decl.sym.type);
			field.init = mMaker.Apply(
			 List.nil(),
			 mMaker.Select(data,
				names.fromString("get0xInt")),
			 List.of(
				mMaker.Literal(key),
				init
			 )
			);
			JCCompilationUnit unit = (JCCompilationUnit) trees.getPath(element.getEnclosingElement()).getCompilationUnit();
			if (!unit.namedImportScope.includes(SettingUI()) && !unit.starImportScope.includes(SettingUI())) {
				/* unit.namedImportScope.importType(
				 SettingUI().members(), SettingUI().members(), SettingUI()
				); */
				Seq seq = Seq.with(unit.defs.toArray());
				seq.insert(1, mMaker.Import(mMaker.Select(mMaker.Ident(SettingUI().packge().fullname), SettingUI()), false));
				unit.defs = List.from(seq);
			}
			// Log.info(field);
			if (!anno.needSetting()) return;

			JCMethodDecl method = findChild(decl, Tag.METHODDEF, d -> d.name.contentEquals("settingColor"));
			mMaker.at(method);
			JCVariableDecl param = makeVar0(Flags.PARAMETER, null, "c", null, method.sym);
			param.startPos = 0;
			// Generate SettingsUI.colorBlock(t, "pad", data(), "padColor", padColor, c -> padColor = c.rgba());
			JCExpressionStatement exec = mMaker.Exec(mMaker.Apply(List.nil(),
			 mMaker.Select(mMaker.Ident(SettingUI()), names.fromString("colorBlock")),
			 List.of(mMaker.Ident(names.fromString("t")),
				mMaker.Literal(key.replace("Color", "")),
				data,
				mMaker.Literal(key),
				init,
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
			));
			// Log.info(exec);
			method.body.stats = method.body.stats.append(exec);
		}
	}
	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(DataColorFieldInit.class.getCanonicalName());
	}
	public void init() {
	}
}
