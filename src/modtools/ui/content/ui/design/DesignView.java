package modtools.ui.content.ui.design;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.input.GestureDetector.GestureListener;
import arc.math.Mathf;
import arc.math.geom.*;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.ui.layout.Scl;
import arc.util.*;
import mindustry.graphics.Pal;
import mindustry.input.Binding;
import mindustry.ui.GridImage;
import modtools.ui.control.HopeInput;

import static mindustry.Vars.*;

public class DesignView extends Element implements GestureListener {
	DesignTool tool = DesignTool.hand;
	private float offsetx, offsety;
	private float zoom = 1f;
	private boolean grid = false;
	private final GridImage image = new GridImage(0, 0);
	private final Vec2 vec = new Vec2();
	private final Rect rect = new Rect();
	// private Vec2[][] brushPolygons = new Vec2[MapEditor.brushSizes.length][0];

	boolean drawing;
	int lastx, lasty;
	int startx, starty;
	float mousex, mousey;
	DesignTool lastTool;

	public DesignView() {
        /*for(int i = 0; i < MapEditor.brushSizes.length; i++){
            float size = MapEditor.brushSizes[i];
            float mod = size % 1f;
            brushPolygons[i] = Geometry.pixelCircle(size, (index, x, y) -> Mathf.dst(x, y, index - mod, index - mod) <= size - 0.5f);
        }*/

		Core.input.getInputProcessors().insert(0, new GestureDetector(20, 0.5f, 2, 0.15f, this));
		this.touchable = Touchable.enabled;

		Point2 firstTouch = new Point2();

		addListener(new InputListener() {

			@Override
			public boolean mouseMoved(InputEvent event, float x, float y) {
				mousex = x;
				mousey = y;
				requestScroll();

				return false;
			}

			@Override
			public void enter(InputEvent event, float x, float y, int pointer, Element fromActor) {
				requestScroll();
			}

			@Override
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (pointer != 0) {
					return false;
				}

				if (!mobile && button != KeyCode.mouseLeft && button != KeyCode.mouseMiddle && button != KeyCode.mouseRight) {
					return true;
				}

                /*if(button == KeyCode.mouseRight){
                    lastTool = tool;
                    tool = DesignTool.eraser;
                }*/

                /*if(button == KeyCode.mouseMiddle){
                    lastTool = tool;
                    tool = DesignTool.zoom;
                }*/

				mousex = x;
				mousey = y;

				Point2 p = project(x, y);
				lastx = p.x;
				lasty = p.y;
				startx = p.x;
				starty = p.y;
				tool.touched(p.x, p.y);
				firstTouch.set(p);

				if (tool.edit) {
					ui.editor.resetSaved();
				}

				drawing = true;
				return true;
			}

			@Override
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (!mobile && button != KeyCode.mouseLeft && button != KeyCode.mouseMiddle && button != KeyCode.mouseRight) {
					return;
				}

				drawing = false;

				Point2 p = project(x, y);

				if (tool == DesignTool.pen) {
					ui.editor.resetSaved();
					tool.touchedLine(startx, starty, p.x, p.y);
				}

				editor.flushOp();

				if ((button == KeyCode.mouseMiddle || button == KeyCode.mouseRight) && lastTool != null) {
					tool = lastTool;
					lastTool = null;
				}

			}

			@Override
			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				mousex = x;
				mousey = y;

				Point2 p = project(x, y);

				if (drawing && tool.draggable && !(p.x == lastx && p.y == lasty)) {
					ui.editor.resetSaved();
					Bresenham2.line(lastx, lasty, p.x, p.y, (cx, cy) -> tool.touched(cx, cy));
				}

				if (tool == DesignTool.pen && tool.mode == 1) {
					if (Math.abs(p.x - firstTouch.x) > Math.abs(p.y - firstTouch.y)) {
						lastx = p.x;
						lasty = firstTouch.y;
					} else {
						lastx = firstTouch.x;
						lasty = p.y;
					}
				} else {
					lastx = p.x;
					lasty = p.y;
				}
			}
		});
	}

	public DesignTool getTool() {
		return tool;
	}

	public void setTool(DesignTool tool) {
		this.tool = tool;
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

		if (Core.input.keyRelease(KeyCode.shiftLeft) && lastTool != null) {
			tool = lastTool;
			lastTool = null;
		}

		if (Core.scene.getScrollFocus() != this) return;

		zoom += Core.input.axis(Binding.zoom) / 10f * zoom;
		clampZoom();
	}

	private void clampZoom() {
		zoom = Mathf.clamp(zoom, 0.2f, 20f);
	}

	Point2 project(float x, float y) {
		float ratio = 1f / ((float) editor.width() / editor.height());
		float size = Math.min(width, height);
		float sclwidth = size * zoom;
		float sclheight = size * zoom * ratio;
		x = (x - getWidth() / 2 + sclwidth / 2 - offsetx * zoom) / sclwidth * editor.width();
		y = (y - getHeight() / 2 + sclheight / 2 - offsety * zoom) / sclheight * editor.height();

		if (editor.drawBlock.size % 2 == 0 && tool != DesignTool.eraser) {
			return Tmp.p1.set((int) (x - 0.5f), (int) (y - 0.5f));
		} else {
			return Tmp.p1.set((int) x, (int) y);
		}
	}

	private Vec2 unproject(int x, int y) {
		float ratio = 1f / ((float) editor.width() / editor.height());
		float size      = Math.min(width, height);
		float sclWidth  = size * zoom;
		float sclHeight = size * zoom * ratio;
		float px        = ((float) x / editor.width()) * sclWidth + offsetx * zoom - sclWidth / 2 + getWidth() / 2;
		float py = ((float) (y) / editor.height()) * sclHeight
				+ offsety * zoom - sclHeight / 2 + getHeight() / 2;
		return vec.set(px, py);
	}

	@Override
	public void draw() {
		float ratio = 1f / ((float) editor.width() / editor.height());
		float size = Math.min(width, height);
		float sclWidth = size * zoom;
		float sclHeight = size * zoom * ratio;
		float centerX = x + width / 2 + offsetx * zoom;
		float centerY = y + height / 2 + offsety * zoom;

		image.setImageSize(editor.width(), editor.height());

		if (!ScissorStack.push(rect.set(x + Core.scene.marginLeft, y + Core.scene.marginBottom, width, height))) {
			return;
		}

		Draw.color(Pal.remove);
		Lines.stroke(2f);
		Lines.rect(centerX - sclWidth / 2 - 1, centerY - sclHeight / 2 - 1, sclWidth + 2, sclHeight + 2);
		editor.renderer.draw(centerX - sclWidth / 2 + Core.scene.marginLeft, centerY - sclHeight / 2 + Core.scene.marginBottom, sclWidth, sclHeight);
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
        /*for(int i = 0; i < MapEditor.brushSizes.length; i++){
            if(editor.brushSize == MapEditor.brushSizes[i]){
                index = i;
                break;
            }
        }*/

		float scaling = zoom * Math.min(width, height) / editor.width();

		Draw.color(Pal.accent);
		Lines.stroke(Scl.scl(2f));


		if ((tool.edit || tool == DesignTool.pen) && (!mobile || drawing)) {
			Point2 p = project(mousex, mousey);
			Vec2 v = unproject(p.x, p.y).add(x, y);
			Lines.square(
					v.x + scaling / 2f,
					v.y + scaling / 2f,
					scaling * editor.drawBlock.size / 2f);
		}


		Draw.color(Pal.accent);
		Lines.stroke(Scl.scl(3f));
		Lines.rect(x, y, width, height);
		Draw.reset();

		ScissorStack.pop();
	}

	private boolean active() {
		return Core.scene != null && Core.scene.getKeyboardFocus() != null
		       && Core.scene.getKeyboardFocus().isDescendantOf(ui.editor)
		       && ui.editor.isShown() && tool == DesignTool.hand &&
		       HopeInput.mouseHit() == this;
	}

	@Override
	public boolean pan(float x, float y, float deltaX, float deltaY) {
		if (!active()) return false;
		offsetx += deltaX / zoom;
		offsety += deltaY / zoom;
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
}
