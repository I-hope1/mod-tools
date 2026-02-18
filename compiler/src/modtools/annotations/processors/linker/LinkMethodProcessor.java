package modtools.annotations.processors.linker;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.List;
import modtools.annotations.linker.LinkMethod;
import modtools.annotations.processors.asm.BaseASMProc;
import modtools.annotations.unsafe.TopTranslator;
import modtools.annotations.unsafe.TopTranslator.ToTranslate;

import javax.annotation.processing.Processor;
import javax.lang.model.element.ElementKind;
import java.util.*;

@AutoService(Processor.class)
public class LinkMethodProcessor extends BaseASMProc<MethodSymbol> {

	private static final boolean LOG_RES = false;

	private final Map<ClassSymbol, ArrayList<Pair<MethodSymbol, Name>>> classMethodFields = new LinkedHashMap<>();

	private ClassSymbol reflectSym;
	private ClassSymbol methodSym;
	private ClassSymbol classSym;
	private ClassSymbol objectSym;

	@Override
	public void lazyInit() throws Throwable {
		super.lazyInit();
		reflectSym = findClassSymbolAny("java.lang.reflect.AccessibleObject");
		methodSym = findClassSymbolAny("java.lang.reflect.Method");
		classSym = (ClassSymbol) mSymtab.classType.tsym;
		objectSym = (ClassSymbol) mSymtab.objectType.tsym;

		if (methodSym == null || classSym == null || objectSym == null || reflectSym == null) {
			err("Could not find essential reflection class symbols. LinkMethod reflective features will fail or produce uncompilable code.");
		}
	}

	@Override
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(LinkMethod.class);
	}

	@Override
	public void dealElement(MethodSymbol sourceMethod) throws Throwable {
		java.util.List<Pair<String, DocReference>> links = getLinkReference(LinkMethod.class, sourceMethod, ElementKind.METHOD, "METHOD");

		JCMethodDecl methodDecl = trees.getTree(sourceMethod);
		if (links == null || links.isEmpty()) {
			log.error(methodDecl, SPrinter.err("@LinkMethod requires a {@link ... METHOD} Javadoc tag."));
			return;
		}
		DocReference targetMethodRef = links.get(0).snd;
		if (!(targetMethodRef.element() instanceof MethodSymbol targetMethod)) {
			log.error(methodDecl, SPrinter.err("@LinkMethod: Tag must point to a method."));
			return;
		}

		ClassSymbol sourceOwnerClass = (ClassSymbol) sourceMethod.owner;
		ClassSymbol targetOwnerClass = (ClassSymbol) targetMethod.owner;
		boolean     isSourceStatic   = sourceMethod.isStatic();
		boolean     isTargetStatic   = targetMethod.isStatic();

		boolean useDirectCall = (targetMethod.flags() & Flags.PUBLIC) != 0;
		if (!isTargetStatic && (targetMethod.flags() & Flags.PROTECTED) != 0 && sourceOwnerClass.isSubClass(targetOwnerClass, types)) {
			useDirectCall = true;
		}
		if (((targetMethod.flags() & Flags.PRIVATE) == 0 && (targetMethod.flags() & Flags.PROTECTED) == 0) && targetOwnerClass.packge() == sourceOwnerClass.packge()) {
			if (isTargetStatic || sourceOwnerClass.equals(targetOwnerClass) || sourceOwnerClass.isSubClass(targetOwnerClass, types)) {
				useDirectCall = true; // Package-private access
			}
		}
		if ((targetMethod.flags() & Flags.PRIVATE) != 0) {
			useDirectCall = false;
		}


		List<VarSymbol> sourceParams         = sourceMethod.params();
		Type            sourceReturnType     = sourceMethod.getReturnType();
		List<VarSymbol> targetParamsOriginal = targetMethod.params();
		Type            targetReturnType     = targetMethod.getReturnType();

		boolean         implicitThisScenario  = false;
		List<VarSymbol> effectiveTargetParams = targetParamsOriginal;

		if (!isSourceStatic && isTargetStatic &&
		    sourceParams.size() + 1 == targetParamsOriginal.size() &&
		    targetParamsOriginal.nonEmpty() &&
		    types.isAssignable(sourceOwnerClass.type, types.erasure(targetParamsOriginal.head.type))) {
			implicitThisScenario = true;
			effectiveTargetParams = targetParamsOriginal.tail;
		}

		if (sourceParams.size() != effectiveTargetParams.size()) {
			log.error(methodDecl, SPrinter.err("@LinkMethod: Parameter count mismatch. Source: " + sourceParams.size() + ", Target effectively: " + effectiveTargetParams.size()));
			return;
		}
		for (int i = 0; i < sourceParams.size(); i++) {
			if (!types.isSameType(types.erasure(sourceParams.get(i).type), types.erasure(effectiveTargetParams.get(i).type))) {
				log.error(methodDecl, SPrinter.err("@LinkMethod: Parameter type mismatch at index " + i));
				return;
			}
		}
		if (!types.isSameType(types.erasure(sourceReturnType), types.erasure(targetReturnType)) &&
		    !(sourceReturnType.getTag() == TypeTag.VOID && targetReturnType.getTag() != TypeTag.VOID)) {
			log.error(methodDecl, SPrinter.err("@LinkMethod: Return type mismatch."));
			return;
		}
		if (isSourceStatic && !isTargetStatic) {
			log.error(methodDecl, SPrinter.err("@LinkMethod: Static source cannot link to non-static target."));
			return;
		}
		if (!isTargetStatic && !isSourceStatic && !targetOwnerClass.equals(sourceOwnerClass) && !types.isSubtype(sourceOwnerClass.type, targetOwnerClass.type)) {
			log.error(methodDecl, SPrinter.err("@LinkMethod: Incompatible instance types for non-static link."));
			return;
		}

		final boolean      finalUseDirectCall = useDirectCall;
		final boolean      finalImplicitThis  = implicitThisScenario;
		final MethodSymbol finalTargetMethod  = targetMethod; // Need final for lambda

		TopTranslator translator      = TopTranslator.instance(_context);
		Name          methodFieldName = names.fromString(sourceMethod.name.toString() + "$" + finalTargetMethod.name.toString().replace("<", "_").replace(">", "_") + "$MethodRef");
		VarSymbol     methodFieldVarSym;
		if (!useDirectCall) {
			classMethodFields.computeIfAbsent(sourceOwnerClass, k -> new ArrayList<>()).add(new Pair<>(finalTargetMethod, methodFieldName));
			methodFieldVarSym = getOrCreateFieldSymbol(sourceOwnerClass, methodFieldName, methodSym.type);
		} else {
			methodFieldVarSym = null;
		}

		translator.addToDo(new ToTranslate(JCMethodInvocation.class, inv -> {
			if (!TopTranslator.isEquals(TreeInfo.symbol(inv.meth), sourceMethod)) return null;

			List<JCExpression> callArgs = prepareCallArgs(sourceMethod, sourceOwnerClass, finalImplicitThis);

			if (finalUseDirectCall) {
				return inv;
			} // Use Reflection - NO TRY-CATCH
			if (methodSym == null) { // Basic check, more comprehensive check in lazyInit
				log.error(inv, SPrinter.err("Reflection Method symbol not found. Reflective call generation aborted."));
				return inv;
			}

			JCIdent methodFieldIdent = mMaker.Ident(methodFieldVarSym);

			JCExpression       instanceForInvoke;
			List<JCExpression> paramsForInvokeArr;

			if (isTargetStatic) {
				instanceForInvoke = translator.makeNullLiteral();
				paramsForInvokeArr = boxArguments(callArgs, translator); // All callArgs are actual method params
			} else { // Target is non-static
				instanceForInvoke = mMaker.This(sourceOwnerClass.type); // Standard 'this'
				// If implicitThis, callArgs = [this_for_first_param, p1, p2]. We only want [p1, p2] for invoke's varargs.
				// However, implicitThis is primarily for static targets. If target non-static & implicitThis, it's an odd case.
				// Assuming standard: source non-static -> target non-static, 'this' is the instance, callArgs are method params.
				paramsForInvokeArr = boxArguments(inv.args, translator);
			}

			JCNewArray argsArray = mMaker.NewArray(mMaker.QualIdent(objectSym), List.nil(), paramsForInvokeArr);
			argsArray.type = new Type.ArrayType(objectSym.type, mSymtab.arrayClass);

			Name         name      = names.fromString("invoke");
			MethodSymbol invokeSym = (MethodSymbol) methodSym.members().findFirst(name, s -> s instanceof MethodSymbol ms && ms.params().size() == 2);
			JCMethodInvocation invokeCall = mMaker.App(
			 translator.makeSelect(methodFieldIdent, name, invokeSym),
			 List.of(instanceForInvoke, argsArray));
			invokeCall.setType(objectSym.type); // Method.invoke returns Object

			JCExpression resultExpression = invokeCall;
			if (sourceMethod.getReturnType().getTag() != TypeTag.VOID) {
				resultExpression = unboxOrCast(invokeCall, finalTargetMethod.getReturnType(), translator);
			}

			return resultExpression;
		}));
	}


	static VarSymbol getOrCreateFieldSymbol(ClassSymbol ownerClass, Name fieldName, Type fieldType) {
		Symbol existingSym = ownerClass.members_field.findFirst(fieldName);
		if (existingSym instanceof VarSymbol && types.isSameType(existingSym.type, fieldType)) {
			return (VarSymbol) existingSym;
		}
		VarSymbol newSym = new VarSymbol(Flags.PRIVATE | Flags.STATIC, fieldName, fieldType, ownerClass);
		ownerClass.members_field.enterIfAbsent(newSym);
		JCClassDecl classDecl = trees.getTree(ownerClass);
		mMaker.at(classDecl);
		classDecl.defs = classDecl.defs.append(mMaker.VarDef(newSym, null));
		return newSym;
	}

	private List<JCExpression> prepareCallArgs(MethodSymbol sourceMethod, ClassSymbol sourceOwnerClass,
	                                           boolean implicitThis) {
		java.util.List<JCExpression> argList = new ArrayList<>();
		if (implicitThis) {
			argList.add(mMaker.This(sourceOwnerClass.type));
		}
		for (VarSymbol paramSym : sourceMethod.params()) {
			argList.add(mMaker.Ident(paramSym));
		}
		return List.from(argList);
	}

	private List<JCExpression> boxArguments(List<JCExpression> args, TopTranslator translator) {
		List<JCExpression> boxedArgs = List.nil();
		for (JCExpression arg : args) {
			boxedArgs = boxedArgs.append(box(arg));
		}
		return boxedArgs;
	}

	@Override
	public void process() throws Throwable {
		for (var entry : classMethodFields.entrySet()) {
			var ownerClass    = entry.getKey();
			var methodsToInit = entry.getValue();
			if (methodsToInit.isEmpty()) continue;

			JCBlock           clinit        = getOrCreateClinit(ownerClass);
			List<JCStatement> tryStatements = List.nil();

			for (Pair<MethodSymbol, Name> pair : methodsToInit) {
				MethodSymbol targetMethodSym = pair.fst;
				Name         fieldName       = pair.snd;
				ClassSymbol  targetOwner     = (ClassSymbol) targetMethodSym.owner;

				VarSymbol fieldVarSym = getOrCreateFieldSymbol(ownerClass, fieldName, methodSym.type);
				JCIdent   fieldIdent  = mMaker.Ident(fieldVarSym);

				List<JCExpression> paramTypesExpr = List.nil();
				for (VarSymbol param : targetMethodSym.params()) {
					paramTypesExpr = paramTypesExpr.append(mMaker.ClassLiteral(types.erasure(param.type)));
				}
				JCNewArray paramTypesArray = mMaker.NewArray(mMaker.QualIdent(classSym), List.nil(), paramTypesExpr);
				paramTypesArray.type = new Type.ArrayType(classSym.type, mSymtab.arrayClass);

				// **WARNING:** The following call to getDeclaredMethod() throws NoSuchMethodException (checked).
				// Without a try-catch, this WILL CAUSE A COMPILATION ERROR in <clinit>.
				JCMethodInvocation getDeclaredMethodCall = mMaker.App(
				 translator.makeSelect(mMaker.ClassLiteral(targetOwner), names.fromString("getDeclaredMethod"), classSym),
				 List.of(translator.makeString(targetMethodSym.name.toString()), paramTypesArray));
				getDeclaredMethodCall.setType(methodSym.type);

				JCAssign assignToField = mMaker.Assign(fieldIdent, getDeclaredMethodCall);
				tryStatements = tryStatements.append(mMaker.Exec(assignToField));

				// **WARNING:** setAccessible can throw SecurityException (checked, though rare without SecurityManager).
				// More importantly, fieldIdent might refer to a null field if getDeclaredMethod failed.
				JCMethodInvocation setAccessibleCall = mMaker.App(
				 translator.makeSelect(fieldIdent, names.fromString("setAccessible"), methodSym), // Assumes fieldIdent is not null
				 List.of(mMaker.Literal(true)));
				setAccessibleCall.setType(mSymtab.voidType);
				tryStatements = tryStatements.append(mMaker.Exec(setAccessibleCall));
			}
			// 创建try-catch
			JCTry aTry = mMaker.Try(mMaker.Block(0, tryStatements),
			 List.of(mMaker.Catch(mMaker.Param(ns("e"), mSymtab.throwableType, ownerClass), mMaker.Block(0, List.nil()))), null);
			clinit.stats = clinit.stats.append(aTry);
			// log.warning(clinit, SPrinter.warn("<clinit> in " + ownerClass + " for reflective methods generated WITHOUT try-catch. " +
			//                                   "THIS WILL CAUSE COMPILE ERRORS due to unhandled checked exceptions (e.g., NoSuchMethodException)."));

			if (LOG_RES) println(trees.getTree(ownerClass));
		}
		classMethodFields.clear();
	}

	private JCExpression box(JCExpression expr) {
		return expr;
		/* if (!expr.type.isPrimitive()) return expr;
		Type.JCPrimitiveType pt = (Type.JCPrimitiveType) expr.type;
		ClassSymbol          wc = pt.wrapperClass(mSymtab);
		if (wc == null) return expr;
		JCMethodInvocation call = mMaker.App(translator.makeSelect(mMaker.QualIdent(wc), names.fromString("valueOf"), wc), List.of(expr));
		call.setType(wc.type);
		return call; */
	}

	private JCExpression unboxOrCast(JCExpression expr, Type targetType, TopTranslator translator) {
		if (targetType.isPrimitive()) {
			/* Type.JCPrimitiveType pt = (Type.JCPrimitiveType) targetType;
			ClassSymbol          wc = pt.wrapperClass(mSymtab);
			if (wc == null) return mMaker.TypeCast(targetType, expr); // Should not happen

			JCExpression castToWrapper = expr;
			if (!types.isSameType(expr.type, wc.type) && expr.type.tsym == objectSym) { // Common case from invoke
				castToWrapper = mMaker.TypeCast(wc.type, expr);
			} else if (!types.isSameType(expr.type, wc.type)) {
				// If expr is not Object and not already wrapper, still cast to wrapper before unboxing
				castToWrapper = mMaker.TypeCast(wc.type, expr);
			}

			String             unboxMethod = pt.getTag().name().toLowerCase() + "Value";
			JCMethodInvocation call        = mMaker.App(translator.makeSelect(castToWrapper, names.fromString(unboxMethod), wc), List.nil());
			call.setType(pt);
			return call; */
			return expr;
		} else {
			return mMaker.TypeCast(targetType, expr);
		}
	}

	static JCBlock getOrCreateClinit(ClassSymbol classSymbol) {
		JCClassDecl classDecl = trees.getTree(classSymbol);
		if (classDecl == null) {
			log.error(classDecl, SPrinter.err("Cannot find JCClassDecl for " + classSymbol.getQualifiedName() + " to add/get <clinit>."));
			throw new RuntimeException("Cannot find JCClassDecl for " + classSymbol.getQualifiedName() + " to add/get <clinit>.");
		}
		for (JCTree def : classDecl.defs) {
			if (def instanceof JCBlock b && b.isStatic()) {
				return b;
			}
		}
		JCBlock block = mMaker.Block(Flags.STATIC, List.nil());
		classDecl.defs = classDecl.defs.prepend(block);
		return block;
	}

}