package modtools.utils.ui;

import arc.func.*;
import arc.math.*;
import arc.util.Time;
import mindustry.graphics.Layer;
import modtools.struct.*;
import modtools.utils.Tools;
import modtools.utils.world.WorldDraw;

import static modtools.ui.IntUI.topGroup;

public class LerpFun {
	public Interp in, out;
	public float last;
	public LerpFun(Interp fun) {
		this(fun, fun);
	}

	public LerpFun(Interp in, Interp out) {
		this.in = in;
		this.out = out;
	}
	public float apply(float a) {
		return (last <= a ? in : out).apply(last = a);
	}
	public float apply(float from, float to, float a) {
		return (last <= a ? in : out).apply(from, to, last = a);
	}
	public float a, applyV;
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
	static WorldDraw worldDraw = new WorldDraw(Layer.overlayUI + 1f, "lerp");
	static TaskSet   uiDraw    = new TaskSet();
	static {
		topGroup.drawSeq.add(() -> {
			uiDraw.exec();
		});
	}
	MySet<Boolp> drawSeq;
	public LerpFun onWorld() {
		drawSeq = worldDraw.drawSeq;
		return this;
	}
	public LerpFun onUI() {
		drawSeq = uiDraw;
		return this;
	}
	/**
	 * 注册立即执行事件，到极点就销毁
	 *
	 * @param step 每帧递进的值
	 * @param floatc 形参就是经过{@link #in in}或{@link #out out}转换后的值
	 * @throws IllegalStateException 如果没有设置drawSeq
	 * @see #onUI()
	 * @see #onWorld()
	 */
	public void registerDispose(float step, Floatc floatc) {
		if (drawSeq == null) throw new IllegalStateException("You don't set the drawSeq");
		enabled = true;
		drawSeq.add(() -> {
			a = Mathf.clamp(a + step * Time.delta * (reverse ? -1 : 1));
			applyV = apply(a);
			if (floatc != null) floatc.get(applyV);
			return reverse ? a > 0 : a < 1;
		});
	}
	public LerpFun rev() {
		a = 1 - a;
		reverse = true;
		return this;
	}

	/**
	 * if true, a (1 -> 0)<br>
	 * else a (0 -> 1)
	 */
	public boolean reverse;
}
