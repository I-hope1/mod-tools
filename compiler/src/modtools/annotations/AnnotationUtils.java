package modtools.annotations;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Attribute.*;
import com.sun.tools.javac.code.Attribute.Array;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import modtools.annotations.reflect.VirtualClass;
import sun.reflect.annotation.*;

import javax.lang.model.element.Element;
import java.lang.Class;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;

import static modtools.annotations.BaseProcessor.*;

public interface AnnotationUtils {
	Class<?> AnnotationInvocationHandler = classOrThrow("sun.reflect.annotation.AnnotationInvocationHandler");

	private void overwrite(CompilationUnitTree unit, Tree tree) {
		// unit.getTypeDecls().forEach(t -> typeAnnotations.organizeTypeAnnotationsBodies((JCClassDecl) t));
		JCModifiers mods = HopeReflect.get(tree, "mods");
		mods.annotations.forEach(ann -> {
			ann.args.forEach(arg -> {
				covertTreeToAttribute(unit, ann.attribute, (JCAssign) arg);
			});
		});
	}
	private static void covertTreeToAttribute(Attribute attribute, Object value) {
		if (attribute instanceof Constant) {
			HopeReflect.set(Constant.class, attribute, "value", value);
		} else if (attribute instanceof Array) {
			Attribute[] values = ((Array) attribute).values;
			var         list   = value instanceof List<?> l ? l : List.of(value);
			for (int i = 0, valuesLength = values.length; i < valuesLength; i++) {
				Attribute attribute1 = values[i];
				covertTreeToAttribute(attribute1, list.get(i));
			}
		}
	}
	private void covertTreeToAttribute(CompilationUnitTree unit, Compound parent, JCAssign assign) {
		Object    value     = treeToConstant(unit, assign.rhs);
		Attribute attribute = parent.member(((JCIdent) assign.lhs).name);
		// Log.info("attr: @, value: @", attribute, value);
		covertTreeToAttribute(attribute, value);
	}

	/** @see #getAnnotationByElement(Class, Element, boolean) */
	default <T extends Annotation> T getAnnotationByTree(
	 Class<T> clazz, CompilationUnitTree unit, Tree tree, boolean overwrite) {
		return getAnnotation0(clazz, unit, tree, trees.getElement(trees.getPath(unit, tree)), overwrite);
	}

	/**
	 * 这个会替换{@link ExceptionProxy}，使之可以直接访问{@link Class}
	 * 如{@snippet lang = "java":
	 * public class A {
	 *   public @interface Test {
	 *     Class<?> value();
	 *   }
	 *   @Test(value = Test.class) class TestClass {}
	 *   // 编译器处理
	 *   void process() {
	 *     Test test = TestClass.class.getAnnotation(Test.class);
	 *     test.value(); // 这会抛出异常
	 *   }
	 *   void process2() {
	 *     Test test = getAnnotationByElement(Test.class);
	 *     test.value(); // 这不会抛出异常
	 *   }
	 * }
	 *}
	 * @param clazz     the annotation class
	 * @param el        the element to get annotation from
	 * @param overwrite whether to overwrite the attribute if it exists
	 */
	default <T extends Annotation> T getAnnotationByElement(
	 Class<T> clazz, Element el, boolean overwrite) {
		CompilationUnitTree unit = overwrite ? trees.getPath(el).getCompilationUnit() : null;
		JCTree              tree = overwrite ? elements.getTree(el) : null;
		return getAnnotation0(clazz, unit, tree, el, overwrite);
	}

	private <T extends Annotation> T
	getAnnotation0(Class<T> clazz, CompilationUnitTree unit,
	               Tree tree, Element el, boolean overwriteValue) {
		if (overwriteValue) overwrite(unit, tree);
		T ann = el.getAnnotation(clazz);
		if (ann == null) return null;
		InvocationHandler h = Proxy.getInvocationHandler(ann);
		HashMap<String, Object> map = HopeReflect.getAccess(AnnotationInvocationHandler,
		 h, "memberValues");
		// println("map: " + map);
		map.replaceAll((k, v) ->
		 v instanceof ExceptionProxy ep ? VirtualClass.defineMirrorClass(ep) : v);
		return ann;
	}

	/** @see AnnotationValues#genericAnnotation(Class, CompilationUnitTree, Tree) */
	default Map<String, Object>
	genericAnnotation(Class<? extends Annotation> clazz,
	                  CompilationUnitTree unit,
	                  Tree node) {
		return AnnotationValues.genericAnnotation(clazz, unit, node);
	}

	/** @see AnnotationValues#treeToConstant(CompilationUnitTree, JCTree) */
	default Object treeToConstant(CompilationUnitTree unit, JCTree node) {
		return AnnotationValues.treeToConstant(unit, node);
	}
}
