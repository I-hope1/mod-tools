package modtools.annotations.unsafe;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Kinds.KindSelector;
import com.sun.tools.javac.code.Lint.LintCategory;
import com.sun.tools.javac.code.Source.Feature;
import com.sun.tools.javac.comp.Check;
import com.sun.tools.javac.resources.CompilerProperties.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;

import static com.sun.tools.javac.code.Flags.*;
import static com.sun.tools.javac.code.Kinds.Kind.*;
import static com.sun.tools.javac.tree.JCTree.Tag.CLASSDEF;

public class MyCheck extends Check {
	public MyCheck(Context context) {
		super(context);
		log = Log.instance(context);
		lint = Lint.instance(context);
		names = Names.instance(context);
		deferredLintHandler = DeferredLintHandler.instance(context);

		source = Source.instance(context);
		allowRecords = Feature.RECORDS.allowedInSource(source);
		allowSealed = Feature.SEALED_CLASSES.allowedInSource(source);
	}
	Log     log;
	Lint    lint;
	Names   names;
	Source  source;
	boolean allowRecords, allowSealed;
	/** @see Check#checkFlags(DiagnosticPosition, long, Symbol, JCTree) */
	long checkFlags(DiagnosticPosition pos, long flags, Symbol sym, JCTree tree) {
		System.out.println(flags);
		long mask;
		long implicit = 0;

		switch (sym.kind) {
			case VAR:
				if (TreeInfo.isReceiverParam(tree))
					mask = ReceiverParamFlags;
				else if (sym.owner.kind != TYP)
					mask = LocalVarFlags;
				else if ((sym.owner.flags_field & INTERFACE) != 0)
					mask = implicit = InterfaceVarFlags;
				else
					mask = VarFlags;
				break;
			case MTH:
				if (sym.name == names.init) {
					if ((sym.owner.flags_field & ENUM) != 0) {
						// enum constructors cannot be declared public or
						// protected and must be implicitly or explicitly
						// private
						implicit = PRIVATE;
						mask = PRIVATE;
					} else
						mask = ConstructorFlags;
				} else if ((sym.owner.flags_field & INTERFACE) != 0) {
					if ((sym.owner.flags_field & ANNOTATION) != 0) {
						mask = AnnotationTypeElementMask;
						implicit = PUBLIC | ABSTRACT;
					} else if ((flags & (DEFAULT | STATIC | PRIVATE)) != 0) {
						mask = InterfaceMethodMask;
						implicit = (flags & PRIVATE) != 0 ? 0 : PUBLIC;
						if ((flags & DEFAULT) != 0) {
							implicit |= ABSTRACT;
						}
					} else {
						mask = implicit = InterfaceMethodFlags;
					}
				} else if ((sym.owner.flags_field & RECORD) != 0) {
					mask = RecordMethodFlags;
				} else {
					mask = MethodFlags;
				}
				if ((flags & STRICTFP) != 0) {
					warnOnExplicitStrictfp(pos);
				}
				// Imply STRICTFP if owner has STRICTFP set.
				if (((flags | implicit) & Flags.ABSTRACT) == 0 ||
						((flags) & Flags.DEFAULT) != 0)
					implicit |= sym.owner.flags_field & STRICTFP;
				break;
			case TYP:
				if (sym.owner.kind.matches(KindSelector.VAL_MTH) ||
						(sym.isDirectlyOrIndirectlyLocal() && (flags & ANNOTATION) != 0)) {
					boolean implicitlyStatic = !sym.isAnonymous() &&
																		 ((flags & RECORD) != 0 || (flags & ENUM) != 0 || (flags & INTERFACE) != 0);
					boolean staticOrImplicitlyStatic = (flags & STATIC) != 0 || implicitlyStatic;
					// local statics are allowed only if records are allowed too
					mask = staticOrImplicitlyStatic && allowRecords && (flags & ANNOTATION) == 0 ? StaticLocalFlags : LocalClassFlags;
					implicit = implicitlyStatic ? STATIC : implicit;
				} else if (sym.owner.kind == TYP) {
					// statics in inner classes are allowed only if records are allowed too
					mask = ((flags & STATIC) != 0) && allowRecords && (flags & ANNOTATION) == 0 ? ExtendedMemberStaticClassFlags : ExtendedMemberClassFlags;
					if (sym.owner.owner.kind == PCK ||
							(sym.owner.flags_field & STATIC) != 0) {
						mask |= STATIC;
					} else if (!allowRecords && ((flags & ENUM) != 0 || (flags & RECORD) != 0)) {
						log.error(pos, Errors.StaticDeclarationNotAllowedInInnerClasses);
					}
					// Nested interfaces and enums are always STATIC (Spec ???)
					if ((flags & (INTERFACE | ENUM | RECORD)) != 0) implicit = STATIC;
				} else {
					mask = ExtendedClassFlags;
				}
				// Interfaces are always ABSTRACT
				if ((flags & INTERFACE) != 0) implicit |= ABSTRACT;

				if ((flags & ENUM) != 0) {
					// enums can't be declared abstract, final, sealed or non-sealed
					mask &= ~(ABSTRACT | FINAL | SEALED | NON_SEALED);
					implicit |= implicitEnumFinalFlag(tree);
				}
				if ((flags & RECORD) != 0) {
					// records can't be declared abstract
					mask &= ~ABSTRACT;
					implicit |= FINAL;
				}
				if ((flags & STRICTFP) != 0) {
					warnOnExplicitStrictfp(pos);
				}
				// Imply STRICTFP if owner has STRICTFP set.
				implicit |= sym.owner.flags_field & STRICTFP;
				break;
			default:
				throw new AssertionError();
		}
		long illegal = flags & ExtendedStandardFlags & ~mask;
		if (illegal != 0) {
			if ((illegal & INTERFACE) != 0) {
				log.error(pos, ((flags & ANNOTATION) != 0) ? Errors.AnnotationDeclNotAllowedHere : Errors.IntfNotAllowedHere);
				mask |= INTERFACE;
			} else {
				log.error(pos,
				 Errors.ModNotAllowedHere(asFlagSet(illegal)));
			}
		} else if ((sym.kind == TYP ||
								// ISSUE: Disallowing abstract&private is no longer appropriate
								// in the presence of inner classes. Should it be deleted here?
								checkDisjoint(pos, flags,
								 ABSTRACT,
								 PRIVATE | STATIC | DEFAULT))
							 &&
							 checkDisjoint(pos, flags,
								STATIC | PRIVATE,
								DEFAULT)
							 &&
							 checkDisjoint(pos, flags,
								ABSTRACT | INTERFACE,
								FINAL | NATIVE | SYNCHRONIZED)
							 &&
							 checkDisjoint(pos, flags,
								PUBLIC,
								PRIVATE | PROTECTED)
							 &&
							 checkDisjoint(pos, flags,
								PRIVATE,
								PUBLIC | PROTECTED)
							 &&
							 checkDisjoint(pos, flags,
								FINAL,
								VOLATILE)
							 &&
							 (sym.kind == TYP ||
								checkDisjoint(pos, flags,
								 ABSTRACT | NATIVE,
								 STRICTFP))
							 && checkDisjoint(pos, flags,
		 FINAL,
		 SEALED | NON_SEALED)
							 && checkDisjoint(pos, flags,
		 SEALED,
		 FINAL | NON_SEALED)
							 && checkDisjoint(pos, flags,
		 SEALED,
		 ANNOTATION)) {
			// skip
		}
		return flags & (mask | ~ExtendedStandardFlags) | implicit;
	}
	DeferredLintHandler deferredLintHandler;
	void warnOnExplicitStrictfp(DiagnosticPosition pos) {
		DiagnosticPosition prevLintPos = deferredLintHandler.setPos(pos);
		try {
			deferredLintHandler.report(() -> {
				if (lint.isEnabled(LintCategory.STRICTFP)) {
					log.warning(LintCategory.STRICTFP,
					 pos, Warnings.Strictfp);
				}
			});
		} finally {
			deferredLintHandler.setPos(prevLintPos);
		}
	}
	long implicitEnumFinalFlag(JCTree tree) {
		if (!tree.hasTag(CLASSDEF)) return 0;

		SpecialTreeVisitor sts  = new SpecialTreeVisitor();
		JCClassDecl        cdef = (JCClassDecl) tree;
		for (JCTree defs : cdef.defs) {
			defs.accept(sts);
			if (sts.specialized) return allowSealed ? SEALED : 0;
		}
		return FINAL;
	}
	boolean checkDisjoint(DiagnosticPosition pos, long flags, long set1, long set2) {
		if ((flags & set1) != 0 && (flags & set2) != 0) {
			log.error(pos,
			 Errors.IllegalCombinationOfModifiers(asFlagSet(TreeInfo.firstFlag(flags & set1)),
				asFlagSet(TreeInfo.firstFlag(flags & set2))));
			return false;
		} else
			return true;
	}

}
