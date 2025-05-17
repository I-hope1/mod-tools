package modtools.annotations.processors;

import com.google.auto.service.AutoService;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import modtools.annotations.linker.*;
import modtools.annotations.processors.asm.BaseASMProc;
import modtools.annotations.unsafe.TopTranslator;
import modtools.annotations.unsafe.TopTranslator.ToTranslate;

import javax.annotation.processing.Processor;
import javax.lang.model.element.ElementKind;
import java.util.Set;

@AutoService(Processor.class)
public class LinkProcessor extends BaseASMProc<VarSymbol> {

	private ClassSymbol arcReflectSym;

	@Override
	public void lazyInit() throws Throwable {
		super.lazyInit();
		arcReflectSym = findClassSymbol("arc.util.Reflect");
		if (arcReflectSym == null) {
			err("arc.util.Reflect class symbol not found. LinkProcessor will not function correctly for reflection.");
		}
	}

	@Override
	public void dealElement(VarSymbol sourceField) throws Throwable {
		LinkFieldToField  linkToField = getAnnotationByElement(LinkFieldToField.class, sourceField, true);
		LinkFieldToMethod linkMethod  = getAnnotationByElement(LinkFieldToMethod.class, sourceField, true);

		TopTranslator translator = TopTranslator.instance(_context);

		if (linkToField != null) {
			handleLinkToField(sourceField, translator);
		}

		if (linkMethod != null) {
			handleLinkMethod(sourceField, linkMethod, translator);
		}
	}

	private void handleLinkToField(VarSymbol sourceField, TopTranslator translator) {
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

		boolean isInherited = sourceOwnerClass.isSubClass(targetOwnerClass, types);
		boolean useReflection;

		long targetFlags = targetField.flags_field;
		if ((targetFlags & Flags.PUBLIC) != 0) {
			useReflection = false;
		} else if ((targetFlags & Flags.PROTECTED) != 0 && isInherited) {
			useReflection = false;
		} else if (((targetFlags & Flags.PRIVATE) == 0) && targetOwnerClass.packge() == sourceOwnerClass.packge()) {
			useReflection = false;
		} else {
			useReflection = true;
		}

		if (!isTargetStatic && !isInherited && !targetOwnerClass.equals(sourceOwnerClass) && !useReflection) {
			log.error(trees.getTree(sourceField), SPrinter.err("@LinkToField: Field '" + sourceField.name + "' attempts to directly access non-static field '" + targetField.name + "' from unrelated class '" + targetOwnerClass.getSimpleName() + "'. Forcing reflection."));
			useReflection = true;
		}

		final boolean finalUseReflection = useReflection;

		translator.todos.add(new ToTranslate(JCFieldAccess.class, (fieldAccess) -> {
			if (!TopTranslator.isEquals(fieldAccess.sym, sourceField)) return null;

			JCExpression instanceExpr = fieldAccess.selected;

			if (finalUseReflection) {
				if (arcReflectSym == null) {
					log.error(fieldAccess, SPrinter.err("arc.util.Reflect not found for @LinkToField read."));
					return fieldAccess;
				}
				JCExpression targetClassLiteral = mMaker.ClassLiteral(targetOwnerClass.type);
				JCExpression instanceForReflection = isTargetStatic ?
					translator.makeNullLiteral() : instanceExpr;

				JCMethodInvocation getCall = mMaker.App(
					translator.makeSelect(mMaker.QualIdent(arcReflectSym), names.fromString("get"), arcReflectSym),
					List.of(targetClassLiteral, instanceForReflection, translator.makeString(targetField.name.toString()))
				);
				getCall.setType(mSymtab.objectType);
				return mMaker.TypeCast(sourceField.type, getCall);
			} else {
				if (isTargetStatic) {
					return translator.makeSelect(mMaker.QualIdent(targetOwnerClass), targetField.name, targetOwnerClass);
				} else {
					return translator.makeSelect(instanceExpr, targetField.name, targetOwnerClass);
				}
			}
		}));

		translator.todos.add(new ToTranslate(JCIdent.class, (ident) -> {
			if (!TopTranslator.isEquals(ident.sym, sourceField) || isSourceStatic) return null;

			if (finalUseReflection) {
				if (arcReflectSym == null) {
					log.error(ident, SPrinter.err("arc.util.Reflect not found for @LinkToField (ident read)."));
					return ident;
				}
				JCExpression targetClassLiteral = mMaker.ClassLiteral(targetOwnerClass.type);
				JCExpression thisExpr = mMaker.This(sourceOwnerClass.type);

				JCMethodInvocation getCall = mMaker.App(
					translator.makeSelect(mMaker.QualIdent(arcReflectSym), names.fromString("get"), arcReflectSym),
					List.of(targetClassLiteral, thisExpr, translator.makeString(targetField.name.toString()))
				);
				getCall.setType(mSymtab.objectType);
				return mMaker.TypeCast(sourceField.type, getCall);
			} else {
				return translator.makeSelect(mMaker.This(sourceOwnerClass.type), targetField.name, targetOwnerClass);
			}
		}));

		translator.todos.add(ToTranslate.Assign(
			(assignedSym) -> assignedSym == sourceField,
			(assign, assignedSym) -> {
				JCExpression actualInstanceExpr;
				if (assign.lhs instanceof JCFieldAccess faLhs) {
					actualInstanceExpr = faLhs.selected;
				} else if (assign.lhs instanceof JCIdent) {
					actualInstanceExpr = mMaker.This(sourceOwnerClass.type);
				} else {
					log.error(assign.lhs, SPrinter.err("Unexpected LHS in assignment to linked field: " + assign.lhs.getClass()));
					return assign;
				}

				if (finalUseReflection) {
					if (arcReflectSym == null) {
						log.error(assign, SPrinter.err("arc.util.Reflect not found for @LinkToField assignment."));
						return assign;
					}
					JCExpression targetClassLiteral = mMaker.ClassLiteral(targetOwnerClass.type);
					JCExpression instanceForReflection = isTargetStatic ?
						translator.makeNullLiteral() : actualInstanceExpr;

					JCMethodInvocation setCall = mMaker.App(
						translator.makeSelect(mMaker.QualIdent(arcReflectSym), names.fromString("set"), arcReflectSym),
						List.of(targetClassLiteral, instanceForReflection, translator.makeString(targetField.name.toString()), assign.rhs)
					);
					setCall.setType(mSymtab.voidType);
					return setCall;
				} else {
					JCExpression selectLhs;
					if (isTargetStatic) {
						selectLhs = translator.makeSelect(mMaker.QualIdent(targetOwnerClass), targetField.name, targetOwnerClass);
					} else {
						selectLhs = translator.makeSelect(actualInstanceExpr, targetField.name, targetOwnerClass);
					}
					return mMaker.Assign(selectLhs, assign.rhs).setType(targetField.type);
				}
			}
		));
	}

	private void handleLinkMethod(VarSymbol sourceField, LinkFieldToMethod linkMethodAnnon, TopTranslator translator) {
		java.util.List<Pair<String, DocReference>> links = getLinkReference(LinkFieldToMethod.class, sourceField, ElementKind.METHOD);
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

		if (getterMethod.params.size() != 3) {
			log.error(trees.getTree(getterMethod), SPrinter.err("@LinkMethod: Getter method " + getterMethod.name + " must have 3 parameters: Class<?>, String, Object. Found: " + getterMethod.params.size()));
			return;
		}
		if (setterMethod.params.size() != 4) {
			log.error(trees.getTree(setterMethod), SPrinter.err("@LinkMethod: Setter method " + setterMethod.name + " must have 4 parameters: Class<?>, String, Object, ValueType. Found: " + setterMethod.params.size()));
			return;
		}

		ClassSymbol sourceOwnerClassSym = (ClassSymbol) sourceField.owner;
		Type targetClassForCallType;
		String targetFieldNameForCall;

		// Use the Class<?> returned by linkMethodAnnon.clazz() directly,
		// thanks to getAnnotationByElement's processing.
		Class<?> annonClazzValue = linkMethodAnnon.clazz();

		if (annonClazzValue == void.class) { // Default value
			targetClassForCallType = sourceOwnerClassSym.type;
		} else {
			// Convert the Class<?> from annotation to a Javac Type
			Symbol ts = elements.getTypeElement(annonClazzValue.getCanonicalName());
			if (ts == null || ts.kind == Kinds.Kind.ERR) {
				// This might happen if VirtualClass returned a synthetic Class<?> whose canonical name isn't found by elements.
				// Or if annonClazzValue is a primitive/array type class.
				// A more robust way would be to get the Type directly if getAnnotationByElement could provide it.
				// For now, let's try to find it via fully qualified name.
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

		translator.todos.add(new ToTranslate(JCFieldAccess.class, (fieldAccess) -> {
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

		translator.todos.add(new ToTranslate(JCIdent.class, (ident) -> {
			if (ident.sym != sourceField || sourceField.isStatic()) return null;

			JCExpression instanceArgForCall = mMaker.This(sourceOwnerClassSym.type);
			JCMethodInvocation getCall = mMaker.App(
				mMaker.QualIdent(getterMethod),
				List.of(targetClassLiteral, targetFieldNameLiteral, instanceArgForCall)
			);
			getCall.setType(getterMethod.getReturnType());
			return mMaker.TypeCast(sourceField.type, getCall);
		}));

		translator.todos.add(ToTranslate.Assign(
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
				// if (true) return assign.lhs;
				LetExpr letExpr = mMaker.LetExpr(List.of(mMaker.Exec(setCall)), assign.rhs);
				letExpr.type = assign.rhs.type.baseType();
				// letExpr.needsCond = true;
				return letExpr;
			}
		));
	}

	// Helper to convert Class.getName() to Javac Type
	private Type typeFromString(String className) {
		if (className == null) return mSymtab.errType;
		// Handle primitive types
		switch (className) {
			case "void": return mSymtab.voidType;
			case "boolean": return mSymtab.booleanType;
			case "byte": return mSymtab.byteType;
			case "char": return mSymtab.charType;
			case "short": return mSymtab.shortType;
			case "int": return mSymtab.intType;
			case "long": return mSymtab.longType;
			case "float": return mSymtab.floatType;
			case "double": return mSymtab.doubleType;
		}
		// Handle arrays
		if (className.startsWith("[")) {
			int         arrayDepth = 0;
			int         i          = 0;
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
				currentType = new Type.ArrayType(currentType, mSymtab.arrayClass);
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

	@Override
	public void process() throws Throwable {
		// No class file generation by this processor directly.
	}
}