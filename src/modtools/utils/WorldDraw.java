package modtools.utils;

import arc.Core;
import arc.Events;
import arc.func.Boolp;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.FrameBuffer;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.struct.*;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;

/**
 * 世界渲染模块
 *
 * @author I hope...
 */
public class WorldDraw {
	// 玩家渲染区域
	public static final Rect rect = new Rect();
	// 存储渲染
	public final MySet<Boolp> drawSeq = new MySet<>();/* {
		@Override
		public Seq<Boolp> add(Boolp value) {
			hasChange = true;
			return super.add(value);
		}

		@Override
		public boolean remove(Boolf value) {
			hasChange = true;
			return super.remove(value);
		}

		@Override
		public Seq<Boolp> clear() {
			hasChange = true;
			return super.clear();
		}
	};*/
	// public static final Seq<MyEntity> entities = new Seq<>();
	public static final Vec2 center = new Vec2();
	// public boolean hasChange = false;
	// public MyEntity entity = new MyEntity();
	public static ObjectSet<Runnable> tasks = new ObjectSet<>();

	public WorldDraw(float z) {
		tasks.add(() -> {
			if (Vars.state.isMenu()) return;
			Color last = Draw.getColor();
			Draw.z(z);
			drawSeq.filter(Boolp::get);
			Draw.color(last);
		});
		/*Events.run(EventType.WorldLoadEvent.class, () -> {
			if (entity != null) {
				entity.remove();
				entities.remove(entity);
				entity = null;
			}
		});

		//		int lastChange = Vars.world.tileChanges;
		Events.run(EventType.Trigger.draw, () -> {
			if (entity == null) {
				entity = new MyEntity();
				entity.init();
				entities.add(entity);
			}
			entity.set(center.x, center.y);

			//			if (lastChange == Vars.world.tileChanges) return;
			if (hasChange) {
				hasChange = false;
			}
		});*/
	}

	static {
		Events.run(Trigger.draw, () -> {
			Draw.reset();
			Vec2 v1 = Core.camera.unproject(0, 0).cpy();
			Vec2 v2 = Core.camera.unproject(Core.graphics.getWidth(), Core.graphics.getHeight());
			rect.set(v1.x, v1.y, v2.x - v1.x, v2.y - v1.y);
			rect.getCenter(center);
			tasks.each(Runnable::run);
		});
	}

	public static TextureRegion drawRegion(int width, int height, Runnable draw) {
		return new TextureRegion(drawTexture(width, height, draw));
	}

	public static TextureRegion drawRegion(FrameBuffer buffer, Runnable draw) {
		return new TextureRegion(drawTexture(buffer, draw));
	}

	public static Texture drawTexture(int width, int height, Runnable draw) {
		return drawTexture(new FrameBuffer(width, height, false), draw);
	}

	public static Texture drawTexture(FrameBuffer buffer, Runnable draw) {
		Draw.flush();
		Draw.reset();
		// 绑定
		// buffer.begin(Color.clear);
		buffer.bind();
		// 清空
		Gl.clearColor(0, 0, 0, 0);
		Gl.clear(Gl.colorBufferBit | Gl.depthBufferBit);
		draw.run();
		// 结束
		buffer.end();
		// buffer.dispose();
		Texture texture = buffer.getTexture();
		// texture.bind(1);
		return texture;
	}


	/*public class MyEntity extends EffectState {
		@Override
		public void update() {
		}

		@Override
		public void draw() {
			Draw.z(z);
			drawSeq.filter(Boolp::get);
		}

		public void init() {
			rotation = 0;
			lifetime = Float.POSITIVE_INFINITY;
			data = null;
			color.set(Color.white);

			add();
		}

		@Override
		public float clipSize() {
			return 0;
		}
	}*/
}
