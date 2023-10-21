package modtools.utils.world;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.math.Mat;
import arc.math.geom.*;
import arc.struct.ObjectSet;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import modtools.utils.array.MySet;


public class WorldDraw {
	// 玩家渲染区域
	public static final Rect CAMERA_RECT = new Rect();
	public static final Vec2 center      = new Vec2();

	public static ObjectSet<Runnable> tasks = new ObjectSet<>();

	public final MySet<Boolp> drawSeq = new MySet<>();
	public       float        alpha   = 1;

	public WorldDraw(float z) {
		this(z, null);
	}

	final String name;
	public WorldDraw(float z, String name) {
		this.name = name;
		tasks.add(() -> {
			if (Vars.state.isMenu()) return;
			Draw.reset();
			Draw.flush();
			Draw.z(z);
			Draw.alpha(alpha);
			drawSeq.filter(Boolp::get);
			Draw.reset();
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
		Events.run(Trigger.postDraw, () -> {
			Draw.reset();
			Draw.flush();
			CAMERA_RECT.setPosition(Core.camera.unproject(0, 0));
			Vec2 v2 = Core.camera.unproject(Core.graphics.getWidth(), Core.graphics.getHeight());
			CAMERA_RECT.setSize(v2.x - CAMERA_RECT.x, v2.y - CAMERA_RECT.y);
			CAMERA_RECT.getCenter(center);
			tasks.each(Runnable::run);
			Draw.reset();
		});
	}

	public static TextureRegion drawRegion(int width, int height, Runnable draw) {
		return new TextureRegion(drawTexture(width, height, draw));
	}

	public static TextureRegion drawRegion(FrameBuffer buffer, Runnable draw) {
		return new TextureRegion(drawTexture(buffer, draw));
	}

	public static Texture drawTexture(int width, int height, Runnable draw) {
		FrameBuffer buffer = new FrameBuffer(width, height, false);
		return drawTexture(buffer, draw);
	}
	public static Texture drawTexture(FrameBuffer buffer, int width, int height, Runnable draw) {
		buffer.resize(width, height);
		return drawTexture(buffer, draw);
	}

	public static Texture drawTexture(FrameBuffer buffer, Runnable draw) {
		Draw.reset();
		// 绑定
		buffer.bind();
		// 清空
		Gl.clearColor(0, 0, 0, 0);
		Gl.clear(Gl.colorBufferBit | Gl.depthBufferBit);
		draw.run();
		// 结束
		buffer.end();
		// buffer.dispose();
		// texture.bind(1);
		return buffer.getTexture();
	}
	public String toString() {
		return "WorldDraw{name='" + name + "'}";
	}

	/* static boolean $condition_test$(WorldDraw self) {
		return self.name.equals("tile");
	} */
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
