package modtools.annotations.processors.fieldinit;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.*;
import modtools.annotations.*;
import modtools.annotations.fieldinit.DataBoolFieldInit;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.util.Set;

@AutoService({Processor.class})
public class BoolProcessor extends BaseProcessor<VarSymbol> implements DataUtils {
	public Name selector;
	/* method: settingColor */
	public void dealElement(VarSymbol element) {
		if (element.getKind() == ElementKind.FIELD) {
			DataBoolFieldInit anno  = element.getAnnotation(DataBoolFieldInit.class);
			JCClassDecl       decl  = trees.getTree((TypeElement) element.getEnclosingElement());
			JCVariableDecl    field = (JCVariableDecl) trees.getTree(element);
			String            key   = field.name.toString();
			if (selector == null) selector = names.fromString("getBool");
			JCExpression data = anno.data().isEmpty() ? selfData(decl.sym.type) : internalData(anno.data());
			field.init = mMaker.Apply(
			 List.nil(),
			 mMaker.Select(data, selector),
			 List.of(
				mMaker.Literal(key),
				field.init != null ? field.init : mMaker.Literal(false)
			 )
			);
			// Log.info(field);
		}
	}
	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(DataBoolFieldInit.class.getCanonicalName());
	}
}
