package modtools.annotations.processors.asm;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.tree.JCTree.*;
import modtools.annotations.BaseProcessor;
import modtools.annotations.asm.Inline;
import modtools.annotations.unsafe.TopTranslator;
import modtools.annotations.unsafe.TopTranslator.ToTranslate;

import javax.annotation.processing.Processor;
import java.util.Set;

@AutoService(Processor.class)
public class InlineProc extends BaseProcessor<MethodSymbol> {
	public static boolean DEBUG_INLINE = false;
	public void dealElement(MethodSymbol element) throws Throwable {
		Inline annotation = element.getAnnotation(Inline.class);
		if (annotation == null) return;
		if (DEBUG_INLINE) {
			println("Inline: " + element.getQualifiedName());
		}
		TopTranslator translator = TopTranslator.instance(_context);

		translator.addToDo(new ToTranslate(JCExpressionStatement.class, (state) -> {
			if (!(state.expr instanceof JCMethodInvocation m)) return null;

			Symbol sym;
			if ((!(m.meth instanceof JCFieldAccess fa) || !TopTranslator.isEquals(sym = fa.sym, element))
			    && (!(m.meth instanceof JCIdent i) || !TopTranslator.isEquals(sym = i.sym, element))) { return null; }

			MethodSymbol methodSymbol       = (MethodSymbol) sym;
			if (methodSymbol.getReturnType() != mSymtab.voidType) {
				return null;
			}
			return trees.getTree(methodSymbol).body;
		}));
		translator.addToDo(new ToTranslate(JCMethodInvocation.class, (m) -> {
			Symbol sym;
			if ((!(m.meth instanceof JCFieldAccess fa) || !TopTranslator.isEquals(sym = fa.sym, element))
			    && (!(m.meth instanceof JCIdent i) || !TopTranslator.isEquals(sym = i.sym, element))) { return null; }

			MethodSymbol methodSymbol       = (MethodSymbol) sym;
			if (methodSymbol.getReturnType() == mSymtab.voidType) {
				return null;
			}
			JCMethodDecl methodDecl = trees.getTree(methodSymbol);
			LetExpr      letExpr    = translator.translateMethodBlockToLetExpr(methodDecl, sym);
			// println(letExpr);
			return letExpr;
		}));
	}

	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(Inline.class);
	}
}
