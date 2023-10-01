package modtools.annotations.processors;


import arc.struct.Seq;
import arc.util.Strings;
import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.List;
import modtools.annotations.*;
import modtools.annotations.reflect.ReflectUtils;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.lang.reflect.Modifier;
import java.util.Set;

@AutoService({Processor.class})
public class OptimizeReflectProcessor extends BaseProcessor<Element> implements ReflectUtils {

	public void dealElement(Element element) {
		if (element instanceof TypeElement) {
			var              unit  = trees.getPath(element).getCompilationUnit();
			var              tree  = (JCClassDecl) trees.getTree(element);
			Seq<JCStatement> stats = new Seq<>();
			addImport(element, FIELD());
			addImport(element, FIELD_UTILS());
			tree.accept(new TreeScanner() {
				public void visitVarDef(JCVariableDecl variable) {
					OptimizeReflect annotationByTree = getAnnotationByTree(OptimizeReflect.class, unit, variable, true);
					if (annotationByTree == null) return;

					JCExpression clazz = ((JCMethodInvocation) variable.init).args.get(0);
					JCExpression name  = ((JCMethodInvocation) variable.init).args.get(2);

					JCVariableDecl x = addField(tree, Modifier.PRIVATE | Modifier.STATIC,
					 FIELD().type, "FX" + variable.name, null);
					mMaker.at(variable);
					variable.init = mMaker.Apply(null,
					 mMaker.Select(mMaker.Ident(FIELD_UTILS()), names.fromString("get" + (variable.type.isPrimitive() ? Strings.capitalize("" + variable.type) : ""))),
					 List.of(((JCMethodInvocation) variable.init).args.get(1),
						mMaker.Ident(x)
					 )
					);
					mMaker.at(x);
					stats.add(mMaker.Exec(mMaker.Assign(mMaker.Ident(x),
					 mMaker.Apply(
						List.nil(),
						mMaker.Select(mMaker.Ident(FIELD_UTILS()), names.fromString("getFieldAccess")),
						List.of(clazz, name))
					)));
					stats.add(mMaker.Exec(
					 mMaker.Apply(List.nil(),
						mMaker.Select(mMaker.Ident(x), names.fromString("setAccessible")),
						List.of(mMaker.Literal(true))
					 )
					));
				}
			});
			mMaker.at(tree);
			tree.defs = tree.defs.append(mMaker.Block(Flags.STATIC, List.from(stats)));
		}
	}
	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(OptimizeReflect.class.getCanonicalName());
	}
}
