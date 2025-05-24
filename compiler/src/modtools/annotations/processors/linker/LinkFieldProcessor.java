package modtools.annotations.processors.linker;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Type.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.List;
import modtools.annotations.linker.*;
import modtools.annotations.processors.asm.BaseASMProc;
import modtools.annotations.unsafe.TopTranslator;
import modtools.annotations.unsafe.TopTranslator.ToTranslate;

import javax.annotation.processing.Processor;
import javax.lang.model.element.ElementKind;
import java.util.*;

@AutoService(Processor.class)
public class LinkFieldProcessor extends BaseASMProc<VarSymbol> {

	public static String FIELD_PREFIX = "f$";

	ClassType     reflectFieldType;
	TopTranslator translator;
	private final Map<ClassSymbol, Set<Pair<VarSymbol, VarSymbol>>> classFields = new HashMap<>();
	@Override
	public void lazyInit() throws Throwable {
		super.lazyInit();
		reflectFieldType = findTypeBoot("java.lang.reflect.Field");
		translator = TopTranslator.instance(_context);
	}

	@Override
	public void dealElement(VarSymbol sourceField) throws Throwable {
		LinkFieldToField  linkToField = getAnnotationByElement(LinkFieldToField.class, sourceField, true);
		LinkFieldToMethod linkMethod  = getAnnotationByElement(LinkFieldToMethod.class, sourceField, true);

		JCClassDecl classDecl = (JCClassDecl) trees.getTree(sourceField.owner);

		if (linkToField != null) {
			handleLinkToField(classDecl, sourceField);
		}

		if (linkMethod != null) {
			handleLinkMethod(sourceField, linkMethod);
		}
	}

	private void handleLinkToField(JCClassDecl classDecl, VarSymbol sourceField) {
		DocReference docRef = getSeeReference(LinkFieldToField.class, sourceField, ElementKind.FIELD);
		if (docRef == null) {
			log.error(trees.getTree(sourceField), SPrinter.err("@LinkToField requires a valid @see tag pointing to a field."));
			return;
		}

		if (!(docRef.element() instanceof VarSymbol targetField)) {
			log.error(trees.getTree(sourceField), SPrinter.err("@LinkToField @see tag must point to a field, but found " + docRef.element().getKind() + " for " + docRef.reference()));
			return;
		}

		ClassSymbol sourceOwnerClass = (ClassSymbol) sourceField.owner;
		ClassSymbol targetOwnerClass = (ClassSymbol) targetField.owner;

		boolean isTargetStatic = targetField.isStatic();
		boolean isSourceStatic = sourceField.isStatic();

		if (!isTargetStatic && isSourceStatic) {
			log.error(trees.getTree(sourceField), SPrinter.err("@LinkToField: Static field '" + sourceField.name + "' cannot link to non-static field '" + targetField.name + "' in '" + targetOwnerClass.getSimpleName() + "'. An instance of the target class would be required."));
			return;
		}

		// MANDATE PUBLIC TARGET FIELD
		// if ((targetField.flags_field & Flags.PUBLIC) == 0) {
		// 	log.error(trees.getTree(sourceField), SPrinter.err("@LinkToField: Target field '" + targetField.name + "' in '" + targetOwnerClass.getSimpleName() + "' must be public."));
		// 	return;
		// }
		// 如果是非public，添加field到target class，
		VarSymbol reflectField;
		if ((targetField.flags_field & Flags.PUBLIC) == 0) {
			reflectField = LinkMethodProcessor.getOrCreateFieldSymbol(sourceOwnerClass, ns(FIELD_PREFIX + targetField.name), reflectFieldType);
			classFields.computeIfAbsent(targetOwnerClass, k -> new HashSet<>())
			 .add(Pair.of(reflectField, targetField));
		} else { reflectField = null; }

		// Accessibility of targetOwnerClass itself will be checked by Javac during normal compilation
		// if direct access is generated. We ensure the field is public.

		final boolean isEffectivelyLocalOrInherited = targetOwnerClass.equals(sourceOwnerClass) || sourceOwnerClass.isSubClass(targetOwnerClass, types);

		translator.addToDo(ToTranslate.AccessField(sourceField, (tree, _) -> {
			if (isTargetStatic) {
				if (reflectField != null) {
					return makeReflectGet(reflectField, tree.type, null);
				}
				return translator.makeSelect(mMaker.QualIdent(targetOwnerClass), targetField.name, targetOwnerClass);
			}
			JCExpression instanceExpr;
			if (tree instanceof JCFieldAccess access) {
				instanceExpr = access.selected;
			} else if (tree instanceof JCIdent) {
				instanceExpr = mMaker.This(sourceOwnerClass.type);
			} else {
				log.error(tree, SPrinter.err("Unexpected tree in @LinkToField: " + tree.getClass()));
				return tree;
			}
			if (reflectField != null) {
				return makeReflectGet(reflectField, tree.type, instanceExpr);
			}
			return translator.makeSelect(instanceExpr, targetField.name, targetOwnerClass);
		}));

		translator.addToDo(ToTranslate.AssignLHS(
		 (assignedSym) -> TopTranslator.isEquals(assignedSym, sourceField),
		 (assign, _) -> {
			 JCExpression actualInstanceExprForTarget = null;
			 boolean      lhsIsSimpleIdent            = false;

			 if (assign.lhs instanceof JCFieldAccess faLhs) {
				 actualInstanceExprForTarget = faLhs.selected;
			 } else if (assign.lhs instanceof JCIdent) {
				 lhsIsSimpleIdent = true;
				 if (!sourceField.isStatic()) { // If source is non-static, ident implies 'this'
					 actualInstanceExprForTarget = mMaker.This(sourceOwnerClass.type);
				 }
			 } else {
				 log.error(assign.lhs, SPrinter.err("Unexpected LHS in assignment to linked field: " + assign.lhs.getClass()));
				 return assign;
			 }

			 // Error case: assigning to `this.sourceField` (non-static) -> `this.targetField` (non-static)
			 // but targetField is in an unrelated class.
			 if (lhsIsSimpleIdent && !sourceField.isStatic() && !isTargetStatic && !isEffectivelyLocalOrInherited) {
				 log.error(assign.lhs, SPrinter.err("@LinkToField: Assigning to non-static field '" + sourceField.name +
				                                    "' (implicitly 'this." + sourceField.name + "') which links to non-static field '" + targetField.name +
				                                    "' in unrelated class '" + targetOwnerClass.getSimpleName() + "'. This requires an explicit instance of '" +
				                                    targetOwnerClass.getSimpleName() + "'."));
				 return assign;
			 }

			 JCExpression selectLhs;
			 if (isTargetStatic) {
				 selectLhs = translator.makeSelect(mMaker.QualIdent(targetOwnerClass), targetField.name, targetOwnerClass);
			 } else { // Target is non-static
				 // Due to the early check `(!isTargetStatic && isSourceStatic)`, if target is non-static, source must also be non-static.
				 // Thus, actualInstanceExprForTarget should be valid.
				 if (actualInstanceExprForTarget == null && !sourceField.isStatic()) {
					 // This state should ideally not be reached if source is non-static, as actualInstanceExprForTarget should be set.
					 // If source IS static, but target is non-static, it's an error caught earlier.
					 log.error(assign.lhs, SPrinter.err("Internal error: Could not determine instance for non-static target field assignment. LHS: " + assign.lhs));
					 return assign;
				 }
				 selectLhs = translator.makeSelect(actualInstanceExprForTarget, targetField.name, targetOwnerClass);
			 }
			 if (reflectField != null) {
				 return makeReflectSet(reflectField, actualInstanceExprForTarget, assign.rhs);
			 }
			 return mMaker.Assign(selectLhs, assign.rhs).setType(targetField.type);
		 }
		));
	}
	private JCExpression makeReflectGet(VarSymbol reflectField, Type type, JCExpression instance) {
		String suffix = getSuffix(reflectField);
		JCTypeCast cast = mMaker.TypeCast(type.tsym.erasure(types),
		 mMaker.App(translator.makeSelect(mMaker.Ident(reflectField), ns("get" + suffix), reflectFieldType.tsym), List.of(instance)));
		// println(cast);
		return cast;
	}
	private JCExpression makeReflectSet(VarSymbol reflectField, JCExpression instance, JCExpression value) {
		String suffix = getSuffix(reflectField);
		return mMaker.App(translator.makeSelect(mMaker.Ident(reflectField), ns("set" + suffix), reflectFieldType.tsym), List.of(instance, value));
	}

	private void handleLinkMethod(VarSymbol sourceField, LinkFieldToMethod linkMethodAnnon) {
		var links = getLinkReference(LinkFieldToMethod.class, sourceField, ElementKind.METHOD);
		if (links == null || links.isEmpty() || links.stream().noneMatch(p -> "GETTER".equals(p.fst)) || links.stream().noneMatch(p -> "SETTER".equals(p.fst))) {
			log.error(trees.getTree(sourceField), SPrinter.err("@LinkMethod requires both {@link ... GETTER} and {@link ... SETTER} tags."));
			return;
		}

		DocReference getterRef = links.stream().filter(p -> "GETTER".equals(p.fst)).map(p -> p.snd).findFirst().orElse(null);
		DocReference setterRef = links.stream().filter(p -> "SETTER".equals(p.fst)).map(p -> p.snd).findFirst().orElse(null);

		if (getterRef == null || !(getterRef.element() instanceof MethodSymbol getterMethod)) {
			log.error(trees.getTree(sourceField), SPrinter.err("@LinkMethod: Valid GETTER link not found or not a method."));
			return;
		}
		if (setterRef == null || !(setterRef.element() instanceof MethodSymbol setterMethod)) {
			log.error(trees.getTree(sourceField), SPrinter.err("@LinkMethod: Valid SETTER link not found or not a method."));
			return;
		}

		if (!getterMethod.isStatic() || !setterMethod.isStatic()) {
			log.error(trees.getTree(sourceField), SPrinter.err("@LinkMethod: Getter and Setter utility methods must be static."));
			return;
		}

		// Enforce utility methods are public
		if ((getterMethod.flags() & Flags.PUBLIC) == 0) {
			log.error(trees.getTree(getterMethod), SPrinter.err("@LinkMethod: Getter utility method " + getterMethod.name + " in " + getterMethod.owner.getQualifiedName() + " must be public."));
			return;
		}
		if ((setterMethod.flags() & Flags.PUBLIC) == 0) {
			log.error(trees.getTree(setterMethod), SPrinter.err("@LinkMethod: Setter utility method " + setterMethod.name + " in " + setterMethod.owner.getQualifiedName() + " must be public."));
			return;
		}

		if (getterMethod.params.size() != 3) {
			log.error(trees.getTree(getterMethod), SPrinter.err("@LinkMethod: Getter method " + getterMethod.name + " must have 3 parameters: Class<?>, String, Object. Found: " + getterMethod.params.size()));
			return;
		}
		if (setterMethod.params.size() != 4) {
			log.error(trees.getTree(setterMethod), SPrinter.err("@LinkMethod: Setter method " + setterMethod.name + " must have 4 parameters: Class<?>, String, Object, ValueType. Found: " + setterMethod.params.size()));
			return;
		}

		ClassSymbol sourceOwnerClassSym = (ClassSymbol) sourceField.owner;
		Type        targetClassForCallType;
		String      targetFieldNameForCall;

		Class<?> annonClazzValue = linkMethodAnnon.clazz();

		if (annonClazzValue == void.class) {
			targetClassForCallType = sourceOwnerClassSym.type;
		} else {
			Symbol ts = elements.getTypeElement(annonClazzValue.getCanonicalName());
			if (ts == null || ts.kind == Kind.ERR) {
				Type foundType = typeFromString(annonClazzValue.getName());
				if (foundType == null || foundType.getTag() == TypeTag.ERROR) {
					log.error(trees.getTree(sourceField), SPrinter.err("@LinkMethod: Could not resolve class specified in clazz(): " + annonClazzValue.getName()));
					return;
				}
				targetClassForCallType = foundType;
			} else {
				targetClassForCallType = ts.type;
			}
		}
		JCExpression targetClassLiteral = mMaker.ClassLiteral(targetClassForCallType);


		if (linkMethodAnnon.fieldName().isEmpty()) {
			targetFieldNameForCall = sourceField.name.toString();
		} else {
			targetFieldNameForCall = linkMethodAnnon.fieldName();
		}
		JCExpression targetFieldNameLiteral = translator.makeString(targetFieldNameForCall);

		/* translator.addToDo(new ToTranslate(JCFieldAccess.class, (fieldAccess) -> {
			if (fieldAccess.sym != sourceField) return null;

			JCExpression instanceArgForCall;
			if (sourceField.isStatic()) {
				instanceArgForCall = translator.makeNullLiteral();
			} else {
				instanceArgForCall = fieldAccess.selected;
			}

			JCMethodInvocation getCall = mMaker.App(
			 mMaker.QualIdent(getterMethod),
			 List.of(targetClassLiteral, targetFieldNameLiteral, instanceArgForCall)
			);
			getCall.setType(getterMethod.getReturnType());
			return mMaker.TypeCast(sourceField.type, getCall);
		}));

		translator.addToDo(new ToTranslate(JCIdent.class, (ident) -> {
			if (ident.sym != sourceField || sourceField.isStatic()) return null;

			JCExpression instanceArgForCall = mMaker.This(sourceOwnerClassSym.type);
			JCMethodInvocation getCall = mMaker.App(
			 mMaker.QualIdent(getterMethod),
			 List.of(targetClassLiteral, targetFieldNameLiteral, instanceArgForCall)
			);
			getCall.setType(getterMethod.getReturnType());
			return mMaker.TypeCast(sourceField.type, getCall);
		})); */

		translator.addToDo(ToTranslate.AssignLHS(
		 (assignedSym) -> TopTranslator.isEquals(assignedSym, sourceField),
		 (assign, assignedSym) -> {
			 JCExpression actualInstanceExpr;
			 if (assign.lhs instanceof JCFieldAccess faLhs) {
				 actualInstanceExpr = faLhs.selected;
			 } else if (assign.lhs instanceof JCIdent) {
				 actualInstanceExpr = mMaker.This(sourceOwnerClassSym.type);
			 } else {
				 log.error(assign.lhs, SPrinter.err("Unexpected LHS in assignment to linked method field: " + assign.lhs.getClass()));
				 return assign;
			 }

			 JCExpression instanceArgForCall;
			 if (sourceField.isStatic()) {
				 instanceArgForCall = translator.makeNullLiteral();
			 } else {
				 instanceArgForCall = actualInstanceExpr;
			 }

			 JCMethodInvocation setCall = mMaker.App(
				mMaker.QualIdent(setterMethod),
				List.of(targetClassLiteral, targetFieldNameLiteral, instanceArgForCall, assign.rhs)
			 );
			 setCall.setType(mSymtab.voidType);
			 LetExpr letExpr = mMaker.LetExpr(List.of(mMaker.Exec(setCall)), assign.rhs);
			 letExpr.type = assign.rhs.type.baseType();
			 return letExpr;
		 }
		));
	}

	// Helper to convert Class.getName() to Javac Type
	private Type typeFromString(String className) {
		if (className == null) return mSymtab.errType;
		// Handle primitive types
		switch (className) {
			case "void":
				return mSymtab.voidType;
			case "boolean":
				return mSymtab.booleanType;
			case "byte":
				return mSymtab.byteType;
			case "char":
				return mSymtab.charType;
			case "short":
				return mSymtab.shortType;
			case "int":
				return mSymtab.intType;
			case "long":
				return mSymtab.longType;
			case "float":
				return mSymtab.floatType;
			case "double":
				return mSymtab.doubleType;
		}
		// Handle arrays
		if (className.startsWith("[")) {
			int arrayDepth = 0;
			int i          = 0;
			while (i < className.length() && className.charAt(i) == '[') {
				arrayDepth++;
				i++;
			}
			String componentTypeName;
			if (className.charAt(i) == 'L' && className.endsWith(";")) {
				componentTypeName = className.substring(i + 1, className.length() - 1);
			} else { // Primitive array
				componentTypeName = className.substring(i);
			}
			Type componentType = typeFromString(componentTypeName);
			if (componentType.getTag() == TypeTag.ERROR) return mSymtab.errType;

			Type currentType = componentType;
			for (int d = 0; d < arrayDepth; d++) {
				currentType = new ArrayType(currentType, mSymtab.arrayClass);
			}
			return currentType;
		}
		// Handle object types
		ClassSymbol cs = elements.getTypeElement(className);
		if (cs != null) {
			return cs.type;
		}
		return mSymtab.errType; // Fallback
	}


	@Override
	public Set<Class<?>> getSupportedAnnotationTypes0() {
		return Set.of(LinkFieldToField.class, LinkFieldToMethod.class);
	}


	/**
	 * @see java.lang.reflect.Field#getByte(Object)
	 * @see java.lang.reflect.Field#getChar(Object)
	 * ...
	 */
	private static String getSuffix(VarSymbol reflectField) {
		return switch (reflectField.type) {
			case JCPrimitiveType type -> switch (type.getTag()) {
				case BYTE -> "Byte";
				case CHAR -> "Char";
				case SHORT -> "Short";
				case LONG -> "Long";
				case FLOAT -> "Float";
				case INT -> "Int";
				case DOUBLE -> "Double";
				case BOOLEAN -> "Boolean";
				default -> "";
			};
			default -> "";
		};
	}
	@Override
	public void process() throws Throwable {
		// No class file generation by this processor directly.
		classFields.forEach((targetOwnerClass, sets) -> {
			sets.forEach(pair -> {
				VarSymbol reflectField = pair.fst;
				VarSymbol targetField  = pair.snd;
				// 添加static初始化
				JCExpressionStatement exec = mMaker.Exec(mMaker.Assign(mMaker.Ident(reflectField.name), mMaker.Apply(List.nil(), mMaker.Select(
				 mMaker.ClassLiteral(targetOwnerClass),
				 ns("getDeclaredField")), List.of(mMaker.Literal(targetField.name.toString())))));
				JCExpressionStatement setAccess = mMaker.Exec(mMaker.Apply(List.nil(), mMaker.Select(mMaker.Ident(reflectField.name), ns("setAccessible")), List.of(mMaker.Literal(true))));

				JCBlock clinit = LinkMethodProcessor.getOrCreateClinit((ClassSymbol) reflectField.owner);
				mMaker.at(clinit);
				JCTry jcTry = mMaker.Try(PBlock(exec, setAccess),
				 List.of(mMaker.Catch(mMaker.Param(ns("_"), mSymtab.throwableType, reflectField.owner), PBlock())), null);
				clinit.stats = clinit.stats.append(jcTry);
				// println(clinit);
			});
		});
	}

}