package modtools.utils.world;

import arc.*;
import arc.func.Boolp;
import arc.graphics.Texture;
import arc.graphics.g2d.*;
import arc.graphics.gl.FrameBuffer;
import arc.math.geom.*;
import arc.struct.*;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import modtools.utils.*;

import static modtools.ui.IntUI.topGroup;


public class WorldDraw {
	/** 玩家视野（渲染区域） */
	public static final Rect CAMERA_RECT = new Rect();
	/** 玩家视野的中心坐标 */
	public static final Vec2 center      = new Vec2();

	public static ObjectSet<Runnable> tasks = new ObjectSet<>();

	public final Seq<Boolp> drawSeq = new Seq<>();
	public       float      alpha   = 1;

	WorldDraw(float z) {
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
			drawSeq.removeAll(boolp -> !boolp.get());
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

	public static void registerEvent() {
		Events.run(Trigger.postDraw, Tools.delegate(() -> {
			Draw.reset();
			Draw.flush();
			CAMERA_RECT.setPosition(Core.camera.unproject(0, 0));
			Vec2 v2 = Core.camera.unproject(Core.graphics.getWidth(), Core.graphics.getHeight());
			CAMERA_RECT.setSize(v2.x - CAMERA_RECT.x, v2.y - CAMERA_RECT.y);
			CAMERA_RECT.getCenter(center);
			tasks.each(Runnable::run);
			Draw.reset();
		}, topGroup::isDisposed));
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
		ElementUtils.clearScreen();
		draw.run();
		// 结束
		buffer.end();
		// buffer.dispose();
		// texture.bind(1);
		return buffer.getTexture();
	}
	public String toString() {
		return STR."WorldDraw{name='\{name}'}";
	}
	public void submit(Runnable r) {
		drawSeq.add(() -> {
			r.run();
			return true;
		});
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
