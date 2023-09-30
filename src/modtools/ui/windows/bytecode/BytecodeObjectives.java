package modtools.ui.windows.bytecode;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import arc.util.*;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.graphics.Pal;
import mindustry.io.JsonIO;
import mindustry.world.Block;
import modtools.ui.windows.bytecode.BytecodeCanvas.ObjectiveTilemap.ObjectiveTile;
import modtools.ui.windows.bytecode.BytecodeObjectives.BytecodeObjective;

import java.lang.annotation.*;
import java.util.Iterator;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static modtools.ui.windows.bytecode.BytecodeObjectives.Status.*;

/** Handles and executes in-map objectives. */
public class BytecodeObjectives implements Iterable<BytecodeObjective>, Eachable<BytecodeObjective> {
	public static final Seq<Prov<? extends BytecodeObjective>> allObjectiveTypes = new Seq<>();

	/**
	 * All objectives the executor contains. Do not modify directly, ever!
	 */
	public              Seq<BytecodeObjective> all = new Seq<>(4);
	protected transient boolean                changed;

	static {
		registerObjective(
		 ALoad::new,
		 THIS::new,
		 ILoad::new,
		 InvokeVirtual::new
		);

		/* registerMarker(
		 ShapeTextMarker::new,
		 MinimapMarker::new,
		 ShapeMarker::new
		); */
	}

	@SafeVarargs
	public static void registerObjective(Prov<? extends BytecodeObjective>... providers) {
		for (var prov : providers) {
			allObjectiveTypes.add(prov);

			Class<? extends BytecodeObjective> type = prov.get().getClass();
			JsonIO.classTag(Strings.camelize(type.getSimpleName().replace("Objective", "")), type);
			JsonIO.classTag(type.getSimpleName().replace("Objective", ""), type);
		}
	}

	/** Adds all given objectives to the executor as root objectives. */
	public void add(BytecodeObjective... objectives) {
		for (var objective : objectives) flatten(objective);
	}

	/** Recursively adds the objective and its children. */
	private void flatten(BytecodeObjective objective) {
		for (var child : objective.children) flatten(child);

		objective.children.clear();
		all.add(objective);
	}


	public void clear() {
		if (all.size > 0) changed = true;
		all.clear();
	}

	@Override
	public Iterator<BytecodeObjective> iterator() {
		return all.iterator();
	}

	@Override
	public void each(Cons<? super BytecodeObjective> cons) {
		all.each(cons);
	}

	/** Base abstract class for any in-map objective. */
	public static abstract class BytecodeObjective {
		public transient boolean needInput = false;

		/** The parents of this objective. All parents must be done in order for this to be updated. */
		public transient        Seq<BytecodeObjective> inputs   = new Seq<>(2);
		/** Temporary container to store references since this class is static. Will immediately be flattened. */
		private transient final Seq<BytecodeObjective> children = new Seq<>(2);

		public transient ObjectiveTile tile = null;

		/** For the objectives UI dialog. Do not modify directly! */
		public transient int editorX = -1, editorY = -1;

		/** Internal value. Do not modify! */
		private transient boolean depFinished;

		/** 更新 */
		public void update() {}

		/** Reset internal state, if any. */
		public void reset() {}

		/** @return True if all {@link #inputs} are completed, rendering this objective able to execute. */
		public final boolean dependencyFinished() {
			if (depFinished) return true;

			for (var parent : inputs) {
				if (parent.status != normal) return false;
			}

			return depFinished = true;
		}

		public transient String tooltipText = null;
		public transient Status status      = normal;


		/** @return This objective, with the given child's parents added with this, for chaining operations. */
		public BytecodeObjective child(BytecodeObjective child) {
			child.inputs.add(this);
			children.add(child);
			return this;
		}

		/** @return This objective, with the given parent added to this objective's parents, for chaining operations. */
		public BytecodeObjective parent(BytecodeObjective parent) {
			inputs.add(parent);
			return this;
		}

		/** @return The localized type-name of this objective, defaulting to the class simple name without the "Objective" prefix. */
		public String typeName() {
			String className = getClass().getSimpleName().replace("Objective", "");
			return Core.bundle == null ? className : Core.bundle.get("objective." + className.toLowerCase() + ".name", className);
		}

		public abstract ValueType valueType();

		public int getObjWidth() {
			return needInput ? BytecodeCanvas.objWidth : 3;
		}
		public int getObjHeight() {
			return needInput ? BytecodeCanvas.objHeight : 2;
		}
	}

	public abstract static class LoadNoIndex extends BytecodeObjective {
		abstract int getIndex();
		abstract LoadType loadType();
	}
	public static abstract class Load extends LoadNoIndex {
		public int index;
		int getIndex() {
			return index;
		}
	}

	public static class ILoad extends Load {
		public ValueType valueType() {
			return ValueType._int;
		}
		LoadType loadType() {
			return LoadType.iload;
		}
	}

	public static class ALoad extends Load {
		public ValueType valueType() {
			return ValueType._object;
		}
		LoadType loadType() {
			return LoadType.aload;
		}
	}
	public static class THIS extends LoadNoIndex {
		public transient int index = 0;
		int getIndex() {
			return 0;
		}
		LoadType loadType() {
			return LoadType.aload;
		}
		public ValueType valueType() {
			return ValueType._object;
		}
	}

	public static class Invoke extends BytecodeObjective {
		public Class<?>   declaring  = Object.class;
		public String     methodName = "hashCode";
		public Class<?>   returnType = int.class;
		public Class<?>[] args       = {};

		{
			needInput = true;
		}

		public ValueType valueType() {
			return ValueType.getByClass(returnType);
		}
	}

	/** 执行虚拟方法 */
	public static class InvokeVirtual extends Invoke {
		public void update() {
			int need = 1 + args.length;
			tooltipText = null;
			status = normal;
			if (inputs.size != need) {
				status = err;
				tooltipText = "Type size doesn't match. (Needed " + need + ", but given " + inputs.size + ")";
				return;
			}
			if (!(inputs.get(0).tile.obj instanceof LoadNoIndex load)
					|| load.getIndex() != 0
					|| load.loadType() != LoadType.aload) {
				status = err;
				tooltipText = "Input 1 must be THIS.";
				return;
			}
			ValueType needed, provided;
			for (int i = 0, argsLength = args.length; i < argsLength; i++) {
				needed = ValueType.getByClass(args[i]);
				provided = inputs.get(i + 1).tile.obj.valueType();
				if (provided != needed) {
					status = err;
					tooltipText = "Input " + (i + 1) + " doesn't match. (Needed " + needed + ", but given " + provided + ")";
				}
			}
		}
	}

	public enum Status {
		normal(Color.gray), err(Color.red), warning(Color.yellow);
		public final Color color;
		Status(Color color) {
			this.color = color;
		}
	}

	/** For arrays or {@link Seq}s; does not create element rearrangement buttons. */
	@Target(FIELD)
	@Retention(RUNTIME)
	public @interface Unordered {}

	/** For {@code byte}; treats it as a world label flag. */
	@Target(FIELD)
	@Retention(RUNTIME)
	public @interface LabelFlag {}

	/** For {@link UnlockableContent}; filters all un-researchable content. */
	@Target(FIELD)
	@Retention(RUNTIME)
	public @interface Researchable {}

	/** For {@link Block}; filters all un-buildable blocks. */
	@Target(FIELD)
	@Retention(RUNTIME)
	public @interface Synthetic {}

	/** For {@link String}; indicates that a text area should be used. */
	@Target(FIELD)
	@Retention(RUNTIME)
	public @interface Multiline {}

	/** For {@code float}; multiplies the UI input by 60. */
	@Target(FIELD)
	@Retention(RUNTIME)
	public @interface Second {}

	/** For {@code float} or similar data structures, such as {@link Vec2}; multiplies the UI input by {@link Vars#tilesize}. */
	@Target(FIELD)
	@Retention(RUNTIME)
	public @interface TilePos {}
}
