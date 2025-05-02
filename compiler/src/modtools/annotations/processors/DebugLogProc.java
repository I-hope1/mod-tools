package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import modtools.annotations.*;
import modtools.annotations.unsafe.TopTranslator;

import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import java.util.Set;

@AutoService(Processor.class)
public class DebugLogProc extends BaseProcessor<Symbol> {
	ClassSymbol  sym_Log;
	MethodSymbol infoMethod;
	public void dealElement(Symbol element) {
		String fmt           = element.getAnnotation(DebugMark.class).fmt();
		var    topTranslator = TopTranslator.instance(_context);
		topTranslator.todos.add(new TopTranslator.Todo(JCMethodInvocation.class, tree -> {
			if (!(topTranslator.inAnnotation(DebugMark.class) && TopTranslator.isEquals(getSymbol(topTranslator.toplevel, tree), element) &&
			      tree.meth instanceof JCFieldAccess access)) {
				return null;
			}
			if (!(access.sym instanceof MethodSymbol ms
			      && TopTranslator.isEquals(ms, infoMethod))) {
				return null;
			}

			JCExpression expr = tree.args.get(0);
			if (expr instanceof JCLiteral) return null;
			// 默认: % = @
			// 将%替换位表达式字符串，@替换为表达式
			// TODO
			try {
				// 1. Get the source code representation of the expression
				String exprString = expr.toString();

				// 2. Prepare the literal part of the format string
				// Replace "%" with the expression string
				String literalPartRaw = fmt.replace("%", exprString);
				// Find the "@" symbol to know where to split and insert the expression value
				int atIndex = literalPartRaw.indexOf('@');

				String literalPart; // The string literal to be concatenated
				if (atIndex != -1) {
					// Use the part before "@"
					literalPart = literalPartRaw.substring(0, atIndex);
					// Potentially handle content after "@" if the format allows, e.g., "% = @ (units)"
					// String suffixPart = literalPartRaw.substring(atIndex + 1);
					// For now, assume "@" is the end placeholder for the value.
				} else {
					// Default behavior if "@" is missing: "exprString = "
					processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
					 "DebugMark format string '" + fmt + "' is missing '@'. Defaulting to 'exprString = ' format.", element);
					literalPart = exprString + " = ";
				}

				// Create the JCLiteral for the string part using the TreeMaker
				JCLiteral stringLiteral = mMaker.Literal(literalPart);
				stringLiteral.type = mSymtab.stringType; // Explicitly set type to String

				// 3. Create the string concatenation expression: stringLiteral + expr
				// Use maker.Binary for the '+' operator
				JCBinary concatenation = topTranslator.makeBinary(
				 JCTree.Tag.PLUS,    // Operator tag for '+'
				 stringLiteral,      // Left operand (our created literal)
				 expr                // Right operand (the original expression)
				);
				// Set the type of the resulting binary operation to String
				concatenation.type = mSymtab.stringType;
				// It's also good practice to resolve and set the operator symbol if possible/needed,
				// although javac often infers it.
				// concatenation.operator = (OperatorSymbol) mSymtab.resolveBinaryOperator(
				//     Tag.PLUS, mSymtab.stringType, expr.type);


				// 4. Create the new argument list containing only the concatenation
				List<JCExpression> newArgs = List.of(concatenation);

				// 5. Create the new method invocation using the TreeMaker's App method
				// maker.App(method_expression, arguments)
				JCMethodInvocation newMethodInvocation = mMaker.App(
				 access,    // The original method access (e.g., Log.info)
				 newArgs    // The new arguments list
				);
				// Copy necessary type information from the original invocation
				// Log.info returns void, so the type is likely mSymtab.voidType
				newMethodInvocation.type = tree.type; // Should be void type
				// Copy other relevant fields if necessary (polyKind, varargsElement often needed)
				newMethodInvocation.polyKind = tree.polyKind;
				newMethodInvocation.varargsElement = tree.varargsElement;


				// 6. Return the newly constructed method invocation tree
				println(newMethodInvocation);
				return newMethodInvocation;
			} catch (Exception e) {
				// Log errors during transformation attempt
				processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
				 "Error transforming DebugMark log call: " + e.getMessage(), element);
				// Optionally print stack trace during development
				// e.printStackTrace();
				return tree; // Return original tree on error to avoid breaking compilation
			}
		}));
	}
	public void lazyInit() throws Throwable {
		sym_Log = mSymtab.getClass(mSymtab.unnamedModule, ns("arc.util.Log"));
		infoMethod = (MethodSymbol) sym_Log.members().findFirst(ns("info"), t -> t instanceof MethodSymbol m && m.params.get(0).type.tsym == mSymtab.objectType.tsym);
	}
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(DebugMark.class);
	}
}
