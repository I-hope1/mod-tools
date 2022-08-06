package modtools.utils;

import arc.Core;
import arc.Events;
import arc.func.Boolf;
import arc.func.Boolp;
import arc.graphics.*;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.gl.FrameBuffer;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import mindustry.game.EventType;
import mindustry.gen.EffectState;

/**
 * 世界渲染模块
 *
 * @author I hope...
 */
public class WorldDraw {
	// 玩家渲染区域
	public static final Rect rect = new Rect();
	// 存储渲染
	public final Seq<Boolp> drawSeq = new Seq<>() {
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
	};
	public static final Seq<MyEntity> entities = new Seq<>();
	public static final Vec2 center = new Vec2();
	public boolean hasChange = false;
	public float z;
	public MyEntity entity = new MyEntity();

	public WorldDraw(float z) {
		this.z = z;
		Events.run(EventType.WorldLoadEvent.class, () -> {
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
		});
	}

	static {
		Events.run(EventType.Trigger.draw, () -> {
			Vec2 v1 = Core.camera.unproject(0, 0).cpy();
			Vec2 v2 = Core.camera.unproject(Core.graphics.getWidth(), Core.graphics.getHeight());
			rect.set(v1.x, v1.y, v2.x - v1.x, v2.y - v1.y);
			rect.getCenter(center);
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
		// 绑定
		buffer.bind();
		// 清空
		Gl.clear(Gl.colorBufferBit);
		// buffer.begin(Color.clear);
		draw.run();
		// 结束
		buffer.end();
		// buffer.dispose();
		return buffer.getTexture();
	}


	public class MyEntity extends EffectState {
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
	}
}
