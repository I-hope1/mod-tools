package modtools.content.ui.design;

import arc.Core;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.input.GestureDetector.GestureListener;
import arc.math.Mathf;
import arc.math.geom.*;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.ui.layout.Scl;
import arc.struct.Seq;
import arc.util.*;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.input.Binding;
import mindustry.ui.GridImage;
import modtools.editor.*;
import modtools.ui.control.HopeInput;
import modtools.ui.menu.*;
import modtools.utils.EventHelper;

import static mindustry.Vars.*;

public abstract class AbstractView extends Element implements GestureListener, HView {
	private float offsetx, offsety;
	private       float     zoom  = 1f;
	private       boolean   grid  = false;
	private final GridImage image = new GridImage(0, 0);
	private final Vec2      vec   = new Vec2();
	private final Rect      rect  = new Rect();

	final Seq<HItem> items = new Seq<>();
	float mousex, mousey;
	float mouseProjectX, mouseProjectY;
	Camera camera = new Camera();

	public AbstractView() {
		Core.input.getInputProcessors().insert(0, new GestureDetector(20, 0.5f, 2, 0.15f, this));
		this.touchable = Touchable.enabled;

		EventHelper.longPressOrRclick(this, HItem.class, item -> {
			MenuBuilder.showMenuList(menus(item));
		});
		addListener(new InputListener() {

			@Override
			public boolean mouseMoved(InputEvent event, float x, float y) {
				mousex = x;
				mousey = y;
				Vec2 point2 = project(mousex, mousey);
				mouseProjectX = point2.x;
				mouseProjectY = point2.y;
				requestScroll();

				return false;
			}

			@Override
			public void enter(InputEvent event, float x, float y, int pointer, Element fromActor) {
				requestScroll();
			}
		});
	}

	public void addItem(HItem item) {
		items.add(item);
	}
	public Seq<MenuItem> menus(HItem item) {
		if (item == null) {
			return Seq.with(
			 MenuItem.with("new", Icon.add, "New", () -> addItem(new MyHItem()))
			);
		}
		return new Seq<>();
	}
	public HItem getItem(Point2 point2) {
		return null;
	}
	public boolean isGrid() {
		return grid;
	}

	public void setGrid(boolean grid) {
		this.grid = grid;
	}

	public void center() {
		offsetx = offsety = 0;
	}

	@Override
	public void act(float delta) {
		super.act(delta);

		if (Core.scene.getKeyboardFocus() == null || !Core.scene.hasField() && !Core.input.keyDown(KeyCode.controlLeft)) {
			float ax = Core.input.axis(Binding.move_x);
			float ay = Core.input.axis(Binding.move_y);
			offsetx -= ax * 15 * Time.delta / zoom;
			offsety -= ay * 15 * Time.delta / zoom;
		}

		/*if (Core.input.keyTap(KeyCode.shiftLeft)) {
			lastTool = tool;
			tool = DesignTool.pick;
		}*/

		if (Core.scene.getScrollFocus() != this) return;

		zoom += Core.input.axis(Binding.zoom) / 10f * zoom;
		clampZoom();

		camera.resize(width / zoom, height / zoom);
		camera.update();
	}

	private void clampZoom() {
		zoom = Mathf.clamp(zoom, 0.2f, 20f);
	}

	Vec2 project(float screenX, float screenY) {
		return camera.project(screenX, screenY);
		// }
	}

	private Vec2 unproject(float worldX, float worldY) {
		return camera.unproject(worldX, worldY);
	}

	@Override
	public void draw() {
		float ratio     = 1f / ((float) editor.width() / editor.height());
		float size      = Math.min(width, height);
		float sclWidth  = size * zoom;
		float sclHeight = size * zoom * ratio;
		float centerX   = x + width / 2 + offsetx * zoom;
		float centerY   = y + height / 2 + offsety * zoom;

		if (!ScissorStack.push(rect.set(x + Core.scene.marginLeft, y + Core.scene.marginBottom, width, height))) {
			return;
		}

		camera.position.set(centerX, centerY);

		Draw.reset();

		if (grid) {
			Draw.color(Color.gray);
			image.setBounds(centerX - sclWidth / 2, centerY - sclHeight / 2, sclWidth, sclHeight);
			image.draw();

			Lines.stroke(2f);
			Draw.color(Pal.bulletYellowBack);
			Lines.line(centerX - sclWidth / 2f, centerY - sclHeight / 4f, centerX + sclWidth / 2f, centerY - sclHeight / 4f);
			Lines.line(centerX - sclWidth / 4f, centerY - sclHeight / 2f, centerX - sclWidth / 4f, centerY + sclHeight / 2f);
			Lines.line(centerX - sclWidth / 2f, centerY + sclHeight / 4f, centerX + sclWidth / 2f, centerY + sclHeight / 4f);
			Lines.line(centerX + sclWidth / 4f, centerY - sclHeight / 2f, centerX + sclWidth / 4f, centerY + sclHeight / 2f);

			Lines.stroke(3f);
			Draw.color(Pal.accent);
			Lines.line(centerX - sclWidth / 2f, centerY, centerX + sclWidth / 2f, centerY);
			Lines.line(centerX, centerY - sclHeight / 2f, centerX, centerY + sclHeight / 2f);

			Draw.reset();
		}

		int index = 0;

		float scaling = zoom * Math.min(width, height) / editor.width();

		Draw.color(Pal.accent);
		Lines.stroke(Scl.scl(2f));


		Draw.color(Pal.accent);
		Lines.stroke(Scl.scl(3f));
		Lines.rect(x, y, width, height);
		Draw.reset();

		Tmp.m1.set(Draw.proj());
		Draw.proj(camera);
		items.each(HItem::draw);
		Draw.proj(Tmp.m1);

		ScissorStack.pop();
	}

	public boolean active() {
		return Core.scene != null && Core.scene.getKeyboardFocus() != null &&
		       HopeInput.mouseHit() == this;
	}

	@Override
	public boolean pan(float x, float y, float deltaX, float deltaY) {
		if (!active()) return false;
		Vec2 project = project(1, 1);
		offsetx -= deltaX * project.x;
		offsety += deltaY * project.x;
		return false;
	}

	@Override
	public boolean zoom(float initialDistance, float distance) {
		if (!active()) return false;
		float nzoom = distance - initialDistance;
		zoom += nzoom / 10000f / Scl.scl(1f) * zoom;
		clampZoom();
		return false;
	}

	@Override
	public boolean pinch(Vec2 initialPointer1, Vec2 initialPointer2, Vec2 pointer1, Vec2 pointer2) {
		return false;
	}

	@Override
	public void pinchStop() {
	}
	private class MyHItem implements HItem {
		float x = mouseProjectX, y = mouseProjectY;
		public float x() {
			return x;
		}
		public float y() {
			return y;
		}
		public float width() {
			return 10;
		}
		public float height() {
			return 10;
		}
	}
}
