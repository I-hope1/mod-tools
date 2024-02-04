package modtools.annotations.processors.fieldinit;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import modtools.annotations.*;
import modtools.annotations.builder.DataColorFieldInit;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.util.Set;

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
				ns("get0xInt")),
			 List.of(
				mMaker.Literal(key),
				init
			 )
			);
			addImport(element.getEnclosingElement(), SettingUI());
			// Log.info(field);
			if (!anno.needSetting()) return;

			JCMethodDecl method = findChild(decl, Tag.METHODDEF, d -> d.name.contentEquals("settingColor"));
			if (method == null) throw new NullPointerException("could not find method: settingColor()");
			mMaker.at(method);
			JCVariableDecl param = makeVar0(Flags.PARAMETER, null, "c", null, method.sym);
			param.startPos = 0;
			// Generate SettingsUI.colorBlock(t, "pad", data(), "padColor", padColor, c -> padColor = c.rgba());
			JCExpressionStatement exec = mMaker.Exec(mMaker.Apply(List.nil(),
			 mMaker.Select(mMaker.Ident(SettingUI()), ns("colorBlock")),
			 List.of(mMaker.Ident(ns("t")),
				mMaker.Literal(key.replace("Color", "")),
				data,
				mMaker.Literal(key),
				init,
				mMaker.Lambda(
				 List.of(param),
				 mMaker.Assign(mMaker.Ident(field.sym),
					mMaker.Apply(List.nil(), mMaker.Select(mMaker.Ident(param),
					 ns("rgba")), List.nil()
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
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(DataColorFieldInit.class);
	}
}
