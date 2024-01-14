package modtools.ui.windows.bytecode;

import arc.Core;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.event.*;
import arc.scene.ui.Button;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.ui.IntUI;
import modtools.ui.IntUI.Tooltip;
import modtools.ui.components.Window;
import modtools.ui.components.Window.FollowWindow;
import modtools.ui.windows.bytecode.BytecodeCanvas.ObjectiveTilemap.ObjectiveTile.Connector;
import modtools.ui.windows.bytecode.BytecodeObjectives.BytecodeObjective;

import static mindustry.Vars.mobile;

@SuppressWarnings("unchecked")
public class BytecodeCanvas extends WidgetGroup {
	public static final int
	 objWidth = 5, objHeight = 2,
	 bounds   = 100;

	public final float unitSize = Scl.scl(48f);

	public Seq<BytecodeObjective> objectives = new Seq<>();
	public ObjectiveTilemap       tilemap;

	protected BytecodeObjective query;

	private boolean pressed;
	private long    visualPressed;
	private int     queryX = -objWidth, queryY = -objHeight;

	public BytecodeCanvas() {
		setFillParent(true);
		addChild(tilemap = new ObjectiveTilemap());

		addCaptureListener(new InputListener() {
			@Override
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (query != null && button == KeyCode.mouseRight) {
					stopQuery();

					event.stop();
					return true;
				} else {
					return false;
				}
			}
		});

		addCaptureListener(new ElementGestureListener() {
			int pressPointer = -1;

			@Override
			public void pan(InputEvent event, float x, float y, float deltaX, float deltaY) {
				if (tilemap.moving != null || tilemap.connecting != null) return;
				tilemap.x = Mathf.clamp(tilemap.x + deltaX, -bounds * unitSize + width, bounds * unitSize);
				tilemap.y = Mathf.clamp(tilemap.y + deltaY, -bounds * unitSize + height, bounds * unitSize);
			}

			@Override
			public void tap(InputEvent event, float x, float y, int count, KeyCode button) {
				if (query == null) return;

				Vec2 pos = localToDescendantCoordinates(tilemap, Tmp.v1.set(x, y));
				queryX = Mathf.round((pos.x - query.getObjWidth() * unitSize / 2f) / unitSize);
				queryY = Mathf.floor((pos.y - unitSize) / unitSize);

				// In mobile, placing the query is done in a separate button.
				if (!mobile) placeQuery();
			}

			@Override
			public void touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (pressPointer != -1) return;
				pressPointer = pointer;
				pressed = true;
				visualPressed = Time.millis() + 100;
			}

			@Override
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (pointer == pressPointer) {
					pressPointer = -1;
					pressed = false;
				}
			}
		});
	}

	public void clearObjectives() {
		stopQuery();
		tilemap.clearTiles();
	}

	protected void stopQuery() {
		if (query == null) return;
		query = null;

		Core.graphics.restoreCursor();
	}

	public void query(BytecodeObjective obj) {
		stopQuery();
		query = obj;
	}

	public void placeQuery() {
		if (isQuerying() && tilemap.createTile(queryX, queryY, query)) {
			objectives.add(query);
			stopQuery();
		}
	}

	public boolean isQuerying() {
		return query != null;
	}

	public boolean isVisualPressed() {
		return pressed || visualPressed > Time.millis();
	}

	public class ObjectiveTilemap extends WidgetGroup {

		/** The connector button that is being pressed. */
		protected @Nullable Connector     connecting;
		/** The current tile that is being moved. */
		protected @Nullable ObjectiveTile moving;

		public ObjectiveTilemap() {
			setTransform(false);
			setSize(getPrefWidth(), getPrefHeight());
			touchable(() -> isQuerying() ? Touchable.disabled : Touchable.childrenOnly);
		}

		@Override
		public void draw() {
			validate();
			int minX = Math.max(Mathf.floor((x - width - 1f) / unitSize), -bounds), minY = Math.max(Mathf.floor((y - height - 1f) / unitSize), -bounds),
			 maxX = Math.min(Mathf.ceil((x + width + 1f) / unitSize), bounds), maxY = Math.min(Mathf.ceil((y + height + 1f) / unitSize), bounds);
			float progX = x % unitSize, progY = y % unitSize;

			Lines.stroke(3f);
			Draw.color(Pal.darkestGray, parentAlpha);

			for (int x = minX; x <= maxX; x++)
				Lines.line(progX + x * unitSize, minY * unitSize, progX + x * unitSize, maxY * unitSize);
			for (int y = minY; y <= maxY; y++)
				Lines.line(minX * unitSize, progY + y * unitSize, maxX * unitSize, progY + y * unitSize);

			if (isQuerying()) {
				int tx, ty;
				if (mobile) {
					tx = queryX;
					ty = queryY;
				} else {
					Vec2 pos = screenToLocalCoordinates(Core.input.mouse());
					tx = Mathf.round((pos.x - query.getObjWidth() * unitSize / 2f) / unitSize);
					ty = Mathf.floor((pos.y - unitSize) / unitSize);
				}

				Lines.stroke(4f);
				Draw.color(
				 isVisualPressed() ? Pal.metalGrayDark : validPlace(tx, ty, null, query) ? Pal.accent : Pal.remove,
				 parentAlpha
				);

				Lines.rect(x + tx * unitSize, y + ty * unitSize, query.getObjWidth() * unitSize, query.getObjHeight() * unitSize);
			}

			if (moving != null) {
				int   tx, ty;
				float x = this.x + (tx = Mathf.round(moving.x / unitSize)) * unitSize;
				float y = this.y + (ty = Mathf.round(moving.y / unitSize)) * unitSize;

				Draw.color(
				 validPlace(tx, ty, moving, moving.obj) ? Pal.accent : Pal.remove,
				 0.5f * parentAlpha
				);

				Fill.crect(x, y, moving.getWidth(), moving.getHeight());
			}

			Draw.reset();
			super.draw();

			Draw.reset();
			Seq<ObjectiveTile> tiles = getChildren().as();

			Connector conTarget = null;
			if (connecting != null) {
				Vec2 pos = connecting.localToAscendantCoordinates(this, Tmp.v1.set(connecting.pointX, connecting.pointY));
				if (hit(pos.x, pos.y, true) instanceof Connector con && connecting.canConnectTo(con)) conTarget = con;
			}

			boolean removing = false;
			for (var tile : tiles) {
				for (var parent : tile.obj.inputs) {
					var parentTile = tiles.find(t -> t.obj == parent);

					if (parentTile == null) continue;
					if (tile.conInputs.isEmpty()) continue;

					Connector
					 conFrom = parentTile.conOutput,
					 conTo = conFrom.to;
					if (conTo == null) continue;

					if (conTarget != null && (
					 (connecting.findInput && connecting == conTo && conTarget == conFrom) ||
					 (!connecting.findInput && connecting == conFrom && conTarget == conTo)
					)) {
						removing = true;
						continue;
					}

					Vec2
					 from = conFrom.localToAscendantCoordinates(this, Tmp.v1.set(conFrom.getWidth() / 2f, conFrom.getHeight() / 2f)).add(x, y),
					 to = conTo.localToAscendantCoordinates(this, Tmp.v2.set(conTo.getWidth() / 2f, conTo.getHeight() / 2f)).add(x, y);

					drawCurve(false, from.x, from.y, to.x, to.y);
				}
			}

			if (connecting != null) {
				Vec2
				 mouse = (conTarget == null
				 ? connecting.localToAscendantCoordinates(this, Tmp.v1.set(connecting.pointX, connecting.pointY))
				 : conTarget.localToAscendantCoordinates(this, Tmp.v1.set(conTarget.getWidth() / 2f, conTarget.getHeight() / 2f))
				).add(x, y),
				 anchor = connecting.localToAscendantCoordinates(this, Tmp.v2.set(connecting.getWidth() / 2f, connecting.getHeight() / 2f)).add(x, y);

				Vec2
				 from = connecting.findInput ? mouse : anchor,
				 to = connecting.findInput ? anchor : mouse;

				drawCurve(removing, from.x, from.y, to.x, to.y);
			}

			Draw.reset();
		}

		protected void drawCurve(boolean remove, float x1, float y1, float x2, float y2) {
			Lines.stroke(4f);
			Draw.color(remove ? Pal.remove : Pal.accent, parentAlpha);

			Fill.square(x1, y1, 8f, 45f);
			Fill.square(x2, y2, 8f, 45f);

			float dist = Math.abs(x1 - x2) / 2f;
			float cx1  = x1 + dist;
			float cx2  = x2 - dist;
			Lines.curve(x1, y1, cx1, y1, cx2, y2, x2, y2, Math.max(4, (int) (Mathf.dst(x1, y1, x2, y2) / 4f)));

			float progress = (Time.time % (60 * 4)) / (60 * 4);

			float t2  = progress * progress;
			float t3  = progress * t2;
			float t1  = 1 - progress;
			float t13 = t1 * t1 * t1;
			float kx1 = t13 * x1 + 3 * progress * t1 * t1 * cx1 + 3 * t2 * t1 * cx2 + t3 * x2;
			float ky1 = t13 * y1 + 3 * progress * t1 * t1 * y1 + 3 * t2 * t1 * y2 + t3 * y2;

			Fill.circle(kx1, ky1, 6f);

			Draw.reset();
		}

		public boolean validPlace(int x, int y, ObjectiveTile ignore, BytecodeObjective obj) {
			Tmp.r1.set(x, y, obj.getObjWidth(), obj.getObjHeight()).grow(-0.001f);

			if (!Tmp.r2.setCentered(0, 0, bounds * 2, bounds * 2).contains(Tmp.r1)) {
				return false;
			}

			for (var other : children) {
				if (other instanceof ObjectiveTile tile && tile != ignore
						&& Tmp.r2.set(tile.tx, tile.ty, tile.obj.getObjWidth(), tile.obj.getObjHeight())
						 .overlaps(Tmp.r1)) {
					return false;
				}
			}

			return true;
		}

		public boolean createTile(BytecodeObjective obj) {
			return createTile(obj.editorX, obj.editorY, obj);
		}

		public boolean createTile(int x, int y, BytecodeObjective obj) {
			if (!validPlace(x, y, null, obj)) return false;

			ObjectiveTile tile = new ObjectiveTile(obj, x, y);
			tile.pack();

			addChild(tile);

			return true;
		}

		public boolean moveTile(ObjectiveTile tile, int newX, int newY) {
			if (!validPlace(newX, newY, tile, tile.obj)) return false;

			tile.pos(newX, newY);

			return true;
		}

		public void removeTile(ObjectiveTile tile) {
			if (!tile.isDescendantOf(this)) return;
			tile.remove();
		}

		public void clearTiles() {
			clearChildren();
		}

		@Override
		public float getPrefWidth() {
			return bounds * unitSize;
		}

		@Override
		public float getPrefHeight() {
			return bounds * unitSize;
		}

		public class ObjectiveTile extends Table {
			public final BytecodeObjective obj;
			public       int               tx, ty;

			public final Mover          mover;
			/** 输入 */
			public final Seq<Connector> conInputs = new Seq<>();
			public final Connector      conOutput;

			public Table inputTable;
			public void addInput() {
				inputTable.table(t -> {
					Connector connector = new Connector(true);
					t.button(Icon.trash, Styles.flati, () -> {
						t.remove();
						conInputs.remove(connector);
					});
					conInputs.add(t.add(connector).growX().get());
					t.update(() -> connector.index = t.getZIndex() - 1); // add按钮占了1
				}).growX().row();
			}
			public ObjectiveTile(BytecodeObjective obj, int x, int y) {
				this.obj = obj;
				obj.tile = this;
				setTransform(false);
				setClip(false);

				float width1  = unitSize / Scl.scl(1f);
				float height1 = width1 * 2f;
				if (obj.needInput) {
					inputTable = table(Tex.whiteui, input -> {
						input.setColor(Pal.gray);
					}).name("input").size(width1 * 1.3f, height1).get();
					addInput();
				}
				table(t -> {
					float pad = (width1 - 32f) / 2f - 4f;
					t.margin(pad);
					t.touchable(() -> Touchable.enabled);
					Tooltip tooltip = new Tooltip(t0 ->
					 t0.background(Tex.button)
						.label(() -> obj.tooltipText)
					);
					t.update(() -> {
						obj.update();
						t.setBackground(IntUI.whiteui.tint(obj.status.color));
						if (obj.tooltipText != null) t.addListener(tooltip);
						else t.removeListener(tooltip);
					});

					t.labelWrap(obj.typeName())
					 .style(Styles.outlineLabel)
					 .left().grow().get()
					 .setAlignment(Align.left);

					t.row();

					t.table(b -> {
						b.left().defaults().size(40f);

						if (obj.needInput) b.button(Icon.addSmall, this::addInput);
						b.button(Icon.pencilSmall, () -> {
							Window dialog = new FollowWindow("@editor.objectives");
							dialog.cont.pane(Styles.noBarPane, list -> list.top().table(e -> {
								e.margin(0f);
								JavaBytecodeWindow.getInterpreter((Class<BytecodeObjective>) obj.getClass()).build(
								 e, obj.typeName(), new JavaBytecodeWindow.TypeInfo(obj.getClass()),
								 null, null, null,
								 () -> obj,
								 res -> {}
								);
							}).width(400f).fillY()).grow();

							dialog.closeOnBack();
							dialog.show();
						});
						b.button(Icon.trashSmall, () -> removeTile(this));
					}).left().grow();
				}).growX().height(height1)
				 .get().addCaptureListener(mover = new Mover());
				add(conOutput = new Connector(false))
				 .size(width1, height1);

				setSize(getPrefWidth(), getPrefHeight());
				pos(x, y);
			}

			public void pos(int x, int y) {
				tx = obj.editorX = x;
				ty = obj.editorY = y;
				this.x = x * unitSize;
				this.y = y * unitSize;
			}

			@Override
			public float getPrefWidth() {
				return obj.getObjWidth() * unitSize;
			}

			@Override
			public float getPrefHeight() {
				return obj.getObjHeight() * unitSize;
			}

			@Override
			public boolean remove() {
				if (super.remove()) {
					obj.inputs.clear();

					var it = objectives.iterator();
					while (it.hasNext()) {
						var next = it.next();
						if (next == obj) {
							it.remove();
						} else {
							next.inputs.remove(obj);
						}
					}

					return true;
				} else {
					return false;
				}
			}

			public class Mover extends InputListener {
				public int prevX, prevY;
				public float lastX, lastY;

				@Override
				public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
					if (moving != null) return false;
					moving = ObjectiveTile.this;
					moving.toFront();

					prevX = moving.tx;
					prevY = moving.ty;

					// Convert to world pos first because the button gets dragged too.
					Vec2 pos = event.listenerActor.localToStageCoordinates(Tmp.v1.set(x, y));
					lastX = pos.x;
					lastY = pos.y;
					return true;
				}

				@Override
				public void touchDragged(InputEvent event, float x, float y, int pointer) {
					Vec2 pos = event.listenerActor.localToStageCoordinates(Tmp.v1.set(x, y));

					moving.moveBy(pos.x - lastX, pos.y - lastY);
					lastX = pos.x;
					lastY = pos.y;
				}

				@Override
				public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
					if (!moveTile(moving,
					 Mathf.round(moving.x / unitSize),
					 Mathf.round(moving.y / unitSize)
					)) moving.pos(prevX, prevY);
					moving = null;
				}
			}

			public class Connector extends Button {
				public float pointX, pointY;
				/* 寻找输入自己的 */
				public final boolean   findInput;
				public       int       index = -1;
				public       Connector to;

				public Connector(boolean findInput) {
					super(new ButtonStyle() {{
						down = findInput ? Tex.buttonSideLeftDown : Tex.buttonSideRightDown;
						up = findInput ? Tex.buttonSideLeft : Tex.buttonSideRight;
						over = findInput ? Tex.buttonSideLeftOver : Tex.buttonSideRightOver;
					}});

					this.findInput = findInput;

					clearChildren();

					addCaptureListener(new InputListener() {
						int conPointer = -1;

						@Override
						public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
							if (conPointer != -1) return false;
							conPointer = pointer;

							if (connecting != null) return false;
							connecting = Connector.this;

							pointX = x;
							pointY = y;
							return true;
						}

						@Override
						public void touchDragged(InputEvent event, float x, float y, int pointer) {
							if (conPointer != pointer) return;
							pointX = x;
							pointY = y;
						}

						@Override
						public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
							if (conPointer != pointer || connecting != Connector.this) return;
							conPointer = -1;

							Vec2 pos = Connector.this.localToAscendantCoordinates(ObjectiveTilemap.this, Tmp.v1.set(x, y));
							if (ObjectiveTilemap.this.hit(pos.x, pos.y, true) instanceof Connector con && con.canConnectTo(Connector.this)) {
								BytecodeObjective obj2 = con.tile().obj;
								if (findInput) {
									if (!obj.inputs.remove(obj2)) {
										obj.inputs.add(obj2);
										con.to = Connector.this;
									}
								} else {
									if (obj2.inputs.contains(obj)) {
										if (to != con) {
											to = con;
										} else {
											obj2.inputs.remove(obj);
											to = null;
										}
									} else {
										to = con;
										obj2.inputs.add(obj);
									}
								}
							}

							connecting = null;
						}
					});
				}

				public boolean canConnectTo(Connector other) {
					return
					 findInput != other.findInput &&
					 tile() != other.tile();
				}

				@Override
				public void draw() {
					super.draw();
					float cx = x + width / 2f;
					float cy = y + height / 2f;

					// these are all magic numbers tweaked until they looked good in-game, don't mind them.
					Lines.stroke(3f, Pal.accent);
					if (findInput) {
						Lines.line(cx, cy + 9f, cx + 9f, cy);
						Lines.line(cx + 9f, cy, cx, cy - 9f);
					} else {
						Lines.square(cx, cy, 9f, 45f);
					}
				}

				public ObjectiveTile tile() {
					return ObjectiveTile.this;
				}

				@Override
				public boolean isPressed() {
					return super.isPressed() || connecting == this;
				}

				@Override
				public boolean isOver() {
					return super.isOver() && (connecting == null || connecting.canConnectTo(this));
				}
			}
		}
	}
}
