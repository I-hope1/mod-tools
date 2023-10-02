package modtools.annotations.processors;


import arc.struct.Seq;
import arc.util.*;
import com.google.auto.service.AutoService;
import com.sun.java.accessibility.util.Translator;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import modtools.annotations.*;
import modtools.annotations.reflect.ReflectUtils;

import javax.annotation.processing.Processor;
import javax.lang.model.element.*;
import java.lang.reflect.Modifier;
import java.util.*;

@AutoService({Processor.class})
public class OptimizeReflectProcessor extends BaseProcessor<Element> implements ReflectUtils {

	public void dealElement(Element element) {
		if (element instanceof TypeElement) {
			var              unit      = trees.getPath(element).getCompilationUnit();
			var              classDecl = (JCClassDecl) trees.getTree(element);
			Seq<JCStatement> stats     = new Seq<>();
			addImport((TypeElement) element, FIELD());
			addImport((TypeElement) element, FIELD_UTILS());
			classDecl.accept(new TreeScanner() {
				public void visitVarDef(JCVariableDecl variable) {
					OptimizeReflect annotationByTree = getAnnotationByTree(OptimizeReflect.class, unit, variable, true);
					if (annotationByTree == null) return;

					JCMethodInvocation invocation   = (JCMethodInvocation) variable.init;
					JCExpression       declaredType = invocation.args.get(0);
					JCExpression       name         = invocation.args.get(2);

					String newFieldName = "F-" + ((JCLiteral) name).value;
					JCVariableDecl x = (JCVariableDecl) trees.getTree(
					 classDecl.sym.members().findFirst(names.fromString(newFieldName))
					);
					if (x == null) {
						x = newFieldVariable(newFieldName, declaredType, name, classDecl, stats);
					}
					mMaker.at(variable.init);
					boolean isSetter = annotationByTree.isSetter();
					variable.init = mMaker.Apply(null,
					 mMaker.Select(mMaker.Ident(FIELD_UTILS()),
						names.fromString((isSetter ? "set" : "get") + (variable.type.isPrimitive() ? Strings.capitalize("" + variable.type) : ""))),
					 isSetter ? List.of(invocation.args.get(1), mMaker.Ident(x), invocation.args.get(3)) :
						List.of(invocation.args.get(1), mMaker.Ident(x))
					);
					// Log.info(variable);
				}
			});
			mMaker.at(classDecl);
			classDecl.defs = classDecl.defs.append(mMaker.Block(Flags.STATIC, List.from(stats)));
		}
	}
	private JCVariableDecl newFieldVariable(String newFieldName, JCExpression clazz, JCExpression name, JCClassDecl tree,
																					Seq<JCStatement> stats) {
		JCVariableDecl x;
		x = addField(tree, Modifier.PRIVATE | Modifier.STATIC,
		 FIELD().type, newFieldName, null);
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
		return x;
	}
	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(OptimizeReflect.class.getCanonicalName());
	}
}
