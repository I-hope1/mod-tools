package modtools.annotations.processors;


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
import java.util.*;

/** 奇怪的注解处理器。。。  */
@AutoService({Processor.class})
public class AOptimizeReflectProcessor extends BaseProcessor<Element> implements ReflectUtils {
	public void dealElement(Element element) {
		if (element instanceof TypeElement) {
			var unit      = trees.getPath(element).getCompilationUnit();
			var classDecl = (JCClassDecl) trees.getTree(element);
			var stats     = new ArrayList<JCStatement>();
			addImport(element, FIELD());
			addImport(element, FIELD_UTILS());

			classDecl.accept(new TreeScanner() {
				public void visitVarDef(JCVariableDecl variable) {
					OptimizeReflect annotationByTree = getAnnotationByTree(OptimizeReflect.class, unit, variable, false);
					if (annotationByTree == null) return;

					JCMethodInvocation invocation   = (JCMethodInvocation) variable.init;
					JCExpression       declaredType = invocation.args.get(0);
					JCExpression       name         = invocation.args.get(2);

					String newFieldName = "F-" + ((JCLiteral) name).value;
					JCVariableDecl x = (JCVariableDecl) trees.getTree(
					 classDecl.sym.members().findFirst(ns(newFieldName))
					);
					if (x == null) {
						x = newFieldVariable(newFieldName, declaredType, name, classDecl, stats);
					}
					mMaker.at(variable.init);
					boolean isSetter = annotationByTree.isSetter();
					variable.init = mMaker.Apply(null,
					 mMaker.Select(mMaker.Ident(FIELD_UTILS()),
						ns((isSetter ? "set" : "get") + (variable.type.isPrimitive() ? capitalize("" + variable.type) : ""))),
					 isSetter ? List.of(invocation.args.get(1), mMaker.Ident(x), invocation.args.get(3)) :
						List.of(invocation.args.get(1), mMaker.Ident(x))
					);
				}
			});
			mMaker.at(classDecl);
			classDecl.defs = classDecl.defs.append(mMaker.Block(Flags.STATIC, List.from(stats)));
		}
	}
	JCVariableDecl newFieldVariable(String newFieldName, JCExpression clazz, JCExpression name, JCClassDecl tree,
																	ArrayList<JCStatement> stats) {
		JCVariableDecl x = addField(tree, Modifier.PRIVATE | Modifier.STATIC,
		 FIELD().type, newFieldName, null);
		mMaker.at(x);
		stats.add(mMaker.Exec(mMaker.Assign(mMaker.Ident(x),
		 mMaker.Apply(
			List.nil(),
			mMaker.Select(mMaker.Ident(FIELD_UTILS()), ns("getFieldAccess")),
			List.of(clazz, name))
		)));
		return x;
	}
	public Set<String> getSupportedAnnotationTypes() {
		return Set.of(OptimizeReflect.class.getCanonicalName());
	}
}
