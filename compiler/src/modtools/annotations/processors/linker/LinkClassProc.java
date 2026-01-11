package modtools.annotations.processors.linker;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.ArrayType;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.util.List;
import modtools.annotations.linker.LinkVirClass;
import modtools.annotations.processors.asm.BaseASMProc;
import modtools.annotations.unsafe.TopTranslator;
import modtools.annotations.unsafe.TopTranslator.ToTranslate;

import javax.annotation.processing.Processor;
import javax.lang.model.element.ElementKind;
import java.lang.reflect.InvocationHandler;
import java.util.Set;

@AutoService(Processor.class)
public class LinkClassProc extends BaseASMProc<TypeSymbol> {

	public static final String suffix = "c";

	public ClassSymbol proxyClass;
	public ClassSymbol methodClass;
	public void lazyInit() throws Throwable {
		super.lazyInit();
		// java.lang.reflect.Proxy
		proxyClass = findClassSymbolByBoot("java.lang.reflect.Proxy");
		methodClass = findClassSymbolByBoot("java.lang.reflect.Method");
	}
	/** @see java.lang.reflect.Proxy#newProxyInstance(ClassLoader, Class[], InvocationHandler) */
	public void dealElement(TypeSymbol element) throws Throwable {
		DocReference reference = getSeeReference(LinkVirClass.class, element, ElementKind.CLASS, ElementKind.INTERFACE);
		if (reference == null) return;
		ClassSymbol targetClass = (ClassSymbol) reference.element();

		List<Symbol> symbols = List.from(element.members().getSymbols());
		if (!element.isInterface() || symbols.stream().noneMatch(s -> s instanceof MethodSymbol ms && !ms.isStatic())) {
			return;
		}
		translator.addToDo(new TopTranslator.ToTranslate(JCLambda.class, lambda -> {
			if (lambda.type == null || !TopTranslator.isEquals(lambda.type.tsym, element)) {
				return null;
			}

			mMaker.at(lambda);

			Symbol       owner        = translator.currentOwner();
			MethodSymbol fst          = (MethodSymbol) symbols.stream().filter(s -> s instanceof MethodSymbol ms && !ms.isStatic()).findFirst().get();
			JCExpression classLiteral = translator.makeClassExpr(targetClass, owner);
			// 创建proxy
			JCExpression classLoader = mMaker.App(translator.makeSelect(classLiteral, ns("getClassLoader"), mSymtab.classType.tsym));
			JCLambda     newLambda   = mMaker.Lambda(List.nil(), null);
			// 3个参数
			// public Object invoke(Object proxy, Method method, Object[] args);
			JCVariableDecl arg_proxy  = mMaker.Param(ns("proxy"), mSymtab.objectType, owner);
			JCVariableDecl arg_method = mMaker.Param(ns("method"), methodClass.type, owner);
			JCVariableDecl arg_args   = mMaker.Param(ns("args"), new ArrayType(mSymtab.objectType, mSymtab.arrayClass), owner);
			newLambda.params = List.of(arg_proxy, arg_method, arg_args);

			// if (method.getName().equals(%fst.name.toString()%)) {
			//  设置局部变量proxy, method, args
			//  原来的lambda的body
			// }
			// return null;

			int argIndex[] = {0};
			lambda.params.forEach(p -> {
				p.sym.flags_field &= ~Flags.PARAMETER;
			});
			JCStatement body = mMaker.If(
			 mMaker.App(translator.makeSelect(mMaker.Literal(fst.name.toString()), ns("equals"), mSymtab.objectType.tsym),
				List.of(mMaker.App(translator.makeSelect(mMaker.Ident(arg_method), ns("getName"), methodClass)))),
			 mMaker.Block(0, lambda.params.<JCStatement>map(p -> mMaker.VarDef(p.sym,
				 mMaker.TypeCast(p.type, mMaker.Indexed(mMaker.Ident(arg_args), mMaker.Literal(argIndex[0]++)))))
				.appendList(List.of(
				 lambda.body instanceof JCExpression tree ? mMaker.Exec(tree) : (JCStatement) lambda.body)
				)), mMaker.Return(translator.makeNullLiteral())
			);
			MethodSymbol newProxyInstance = (MethodSymbol) proxyClass.members().findFirst(ns("newProxyInstance"), s -> s instanceof MethodSymbol ms && ms.isPublic());
			Type         type             = newProxyInstance.params.get(2).type;
			if (type == element.type) type = targetClass.type;
			newLambda.type = type;
			newLambda.target = type;
			newLambda.body = PBlock(body);

			JCMethodInvocation app = mMaker.App(translator.makeSelect(mMaker.Ident(proxyClass), ns("newProxyInstance"), newProxyInstance),
			 List.of(classLoader,
				mMaker.NewArray(mMaker.Ident(mSymtab.classType.tsym), List.of(mMaker.Literal(0)), List.of(classLiteral)).setType(new ArrayType(mSymtab.classType, mSymtab.arrayClass)),
				newLambda
			 ));
			// println(app);
			return app;
		}));

		translator.addToDo(new ToTranslate(JCTypeCast.class, cast -> {
			if (!TopTranslator.isEquals(TreeInfo.symbolFor(cast.clazz), element)) {
				return null;
			}
			// println(cast);
			if (targetClass.isPrivate()) return cast.expr;
			cast.expr.type = targetClass.type;
			if (cast.expr instanceof JCLambda lambda) {
				lambda.target = targetClass.type;
			}
			cast.type.tsym = targetClass;
			cast.clazz.type = targetClass.type;
			return cast;
		}));
	}
	public void process() throws Throwable {
	}
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(LinkVirClass.class);
	}
}
