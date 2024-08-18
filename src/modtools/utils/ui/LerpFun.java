package modtools.utils.ui;

import arc.Core;
import arc.func.*;
import arc.graphics.g2d.Draw;
import arc.math.*;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.struct.Seq;
import arc.util.*;
import arc.util.pooling.*;
import arc.util.pooling.Pool.Poolable;
import mindustry.graphics.Layer;
import modtools.struct.TaskSet;
import modtools.utils.*;
import modtools.utils.world.WorldDraw;

import static modtools.ui.IntUI.topGroup;

public class LerpFun implements Poolable {
	public Interp in, out;
	public float last;
	private LerpFun() {
	}
	public static LerpFun obtain(Interp fun) {
		return obtain(fun ,fun);
	}
	public static LerpFun obtain(Interp in, Interp out) {
		LerpFun lerpFun = Pools.obtain(LerpFun.class, LerpFun::new);
		lerpFun.in = in;
		lerpFun.out = out;
		return lerpFun;
	}
	public void reset() {
		in = null;
		out = null;
		a = 0;
		applyV = 0;
		enabled = false;
		drawSeq = null;
		reverse = false;
		transElement = null;
		onDispose = null;
	}
	public float apply(float a) {
		return (last <= a ? in : out).apply(last = a);
	}
	public float apply(float from, float to, float a) {
		return (last <= a ? in : out).apply(from, to, last = a);
	}
	public float a = 0, applyV;
	public boolean enabled;
	public void register(float step) {
		register(step, null);
	}

	public void register(float step, Floatc floatc) {
		Tools.TASKS.add(() -> {
			if (!enabled) return;
			a = Mathf.clamp(a + step * (reverse ? -1 : 1));
			applyV = apply(a);
			if (floatc != null) floatc.get(applyV);
		});
	}

	public static WorldDraw worldDraw = new WorldDraw(Layer.overlayUI + 2f, "lerp");
	public static TaskSet   uiDraw    = new TaskSet();

	static {
		topGroup.drawSeq.add(() -> {
			uiDraw.exec();
		});
	}

	Seq<Boolp> drawSeq;
	public LerpFun onWorld() {
		drawSeq = worldDraw.drawSeq;
		return this;
	}
	public LerpFun onUI() {
		drawSeq = uiDraw;
		return this;
	}

	public Element transElement;
	public LerpFun transform(Element element) {
		this.transElement = element;
		return this;
	}
	public LerpFun on(TaskSet taskSet) {
		drawSeq = taskSet;
		return this;
	}

	public static Mat mat = new Mat();
	/**
	 * 注册立即执行事件，到极点就销毁
	 * @param step   每帧递进的值
	 * @param floatc 形参就是经过{@link #in in}或{@link #out out}转换后的值
	 * @throws IllegalStateException 如果没有设置drawSeq
	 * @see #onUI()
	 * @see #onWorld()
	 */
	public LerpFun registerDispose(float step, Floatc floatc) {
		if (drawSeq == null) throw new IllegalStateException("You don't set the drawSeq");
		enabled = true;
		drawSeq.add(() -> {
			if (transElement != null) {
				Tmp.m1.set(Draw.proj());
				Vec2 pos = ElementUtils.getAbsolutePos(transElement);
				Draw.proj(mat.setOrtho(-pos.x, -pos.y, Core.graphics.getWidth(), Core.graphics.getHeight()));
			}
			a = Mathf.clamp(a + step * Time.delta * (reverse ? -1 : 1));
			applyV = apply(a);
			if (floatc != null) floatc.get(applyV);
			if (transElement != null) {
				Draw.proj(Tmp.m1);
			}
			if (reverse ? a > 0 : a < 1) {
				return true;
			}
			if (onDispose != null) onDispose.run();
			Pools.free(this);
			return false;
		});
		return this;
	}
	Runnable onDispose;
	public LerpFun onDispose(Runnable r) {
		onDispose = r;
		return this;
	}
	public LerpFun rev() {
		a = 1 - a;
		reverse = !reverse;
		return this;
	}

	public LerpFun back(float to) {
		// reverse ? 1 -> 0 : 0 -> 1
		if (reverse ? a < to : a > to) a = to;
		return this;
	}

	/**
	 * if true, a (1 -> 0)<br>
	 * else a (0 -> 1)
	 */
	public boolean reverse;

	public interface DrawExecutor {
		TaskSet drawTaskSet();
	}
}
