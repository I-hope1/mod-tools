package modtools.jsfunc;


import arc.Core;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Timer.Task;
import mindustry.gen.*;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.ui.IntUI;
import modtools.ui.comp.*;
import modtools.ui.comp.Window.*;
import modtools.ui.comp.limit.*;
import modtools.ui.comp.utils.ArrayItemLabel;
import modtools.ui.control.HopeInput;
import modtools.utils.*;
import modtools.utils.reflect.FieldUtils;
import modtools.utils.search.BindCell;
import modtools.utils.ui.ShowInfoWindow;
import modtools.utils.world.WorldDraw;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static modtools.ui.HopeStyles.*;
import static modtools.utils.ElementUtils.getAbsolutePos;

public interface INFO_DIALOG {
	float btn_size = 42; // Size for control buttons

	//region Static Inner Class for Drag State
	/** Holds the state during a drag-and-drop operation for array items. */
	class DragState {
		BindCell bindCell        = BindCell.ofConst(BindCell.UNSET_CELL);
		boolean  isDragging      = false;
		RowTable dragRow         = null; // The actual row element being dragged
		Element  dropPlaceholder = null; // Visual indicator for drop position
		Vec2     dragStartOffset = new Vec2();

		/** Creates the placeholder element if it doesn't exist. */
		Element getOrCreatePlaceholder() {
			if (dropPlaceholder == null) {
				dropPlaceholder = new Image(Tex.whitePane);
				// dropPlaceholder.setSize(dragRow.getWidth(), dragRow.getHeight()); // Match size of dragged row
			}
			return dropPlaceholder;
		}
	}
	//endregion

	//region Static Show Methods (no change)
	static Window showInfo(Object o) { return showInfo(o, o == null ? Object.class : o.getClass()); }
	static Window showInfo(Class<?> clazz) { return showInfo(null, clazz); }
	//endregion

	static Window showInfo(Object o, Class<?> clazz) {
		Objects.requireNonNull(clazz, "'clazz' cannot be null.");

		if (clazz.isArray()) {
			var      arrayHolder = new AtomicReference<>(o);
			Window[] dialog      = {null};

			if (arrayHolder.get() != null && !arrayHolder.get().getClass().isArray()) { /* Error handling */
				return new DisWindow("Error");
			}
			if (arrayHolder.get() == null) {
				try { arrayHolder.set(Array.newInstance(clazz.getComponentType(), 0)); } catch (
				 Throwable e) { /* Error handling */ return new DisWindow("Error"); }
			}

			Table mainCont  = new LimitTable();
			Table header    = new LimitTable();
			Table arrayCont = new LimitTable();

			DragState dragState = new DragState();

			// --- Global TouchUp Listener (for cancelling drags) ---
			InputListener globalListener = new InputListener() {
				public static final float durationSeconds = 300f;
				RowTable rowTableR;
				Task     task;
				public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
					if (taskIsScheduled()) return false;

					RowTable rowTable = ElementUtils.findParent(event.targetActor, RowTable.class);
					if (rowTable != null) {
						rowTableR = rowTable;
						task = Time.runTask(60f / 1000f * durationSeconds, () -> setDragState(rowTable, x, y));
					}
					return true;
				}
				private boolean taskIsScheduled() {
					return task != null && task.isScheduled();
				}
				private void setDragState(RowTable rowTable, float x, float y) {
					if (dragState.isDragging) { return; }

					dragState.isDragging = true;
					dragState.dragRow = rowTable;
					Table table = (Table) rowTable.parent;
					dragState.bindCell.setCell(table.getCell(rowTable));
					Element placeholder = dragState.getOrCreatePlaceholder();
					dragState.bindCell.replace(placeholder, true);
					SnapshotSeq<Element> children = table.getChildren();
					children.begin();
					for (Element child : children) {
						if (child instanceof RowTable r) r.cancelClick = true;
					}
					children.end();
					table.addChild(rowTable);
					mainCont.localToDescendantCoordinates(rowTable, dragState.dragStartOffset.set(x, y));
				}
				public void touchDragged(InputEvent event, float x, float y, int pointer) {
					if (taskIsScheduled() && !ElementUtils.checkIn(rowTableR, mainCont, x, y)) {
						task.cancel();
					}
					if (dragState.isDragging) {
						dragState.dragRow.touchable = Touchable.disabled;
						dragState.dragRow.setPosition(-dragState.dragStartOffset.x + x, -dragState.dragStartOffset.y + y);
						event.stop();
					}
					super.touchDragged(event, x, y, pointer);
				}
				public void enter(InputEvent event, float x, float y, int pointer, Element fromActor) {
					if (!dragState.isDragging) return;

					event.stop();

					RowTable rowTable = ElementUtils.findParent(event.targetActor, RowTable.class);
					if (rowTable != null) {
						Table     table  = (Table) rowTable.parent;
						Seq<Cell> cells  = table.getCells();
						int       first  = cells.indexOf(dragState.bindCell.cell);
						int       second = cells.indexOf(c -> c.get() == rowTable);
						if (first == -1 || second == -1) return;
						// Log.info("first=" + first + "; second=" + second);
						// 交换cell
						cells.swap(first, second);
						// 交换rowTable的index
						int    i1  = dragState.dragRow.index;
						int    i2  = rowTable.index;
						Object iv1 = Array.get(arrayHolder.get(), i1);
						Object iv2 = Array.get(arrayHolder.get(), i2);
						Array.set(arrayHolder.get(), i1, iv2);
						Array.set(arrayHolder.get(), i2, iv1);
						int index = rowTable.index;
						rowTable.index = dragState.dragRow.index;
						dragState.dragRow.index = index;

						// 重新布局
						table.layout();
					}
				}
				@Override
				public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
					if (taskIsScheduled()) task.cancel();
					if (!dragState.isDragging) { return; }

					event.stop();

					clearDragState();
					event.stop();
					// cleanupDragState(dragState); // Clean up visuals and state
					// No rebuild needed as no change occurred
				}
				private void clearDragState() {
					if (!dragState.isDragging) { return; }

					Table dragRow = dragState.dragRow;
					Core.app.post(() -> {
						dragRow.touchable = Touchable.enabled;
						SnapshotSeq<Element> children = dragRow.parent.getChildren();
						children.begin();
						for (Element child : children) {
							if (child instanceof RowTable r) r.cancelClick = false;
						}
						children.end();
					});

					dragState.bindCell.replace(dragState.dragRow);
					dragState.bindCell.unsetSize();

					dragState.isDragging = false;
					dragState.dragRow = null;
				}
			};

			Runnable rebuild = new Runnable() {
				public void run() {
					Seq<Disposable> currentDisposables = new Seq<>();
					arrayCont.clearChildren(); // Clear previous items AND separators
					buildArrayCont(arrayHolder, clazz, arrayCont, currentDisposables::add, dialog[0]::requestKeyboard, dragState, this);

					if (dialog[0] != null) {
						Seq<Disposable> oldDisposables = dialog[0].userObject instanceof Seq<?> s ? (Seq<Disposable>) s : null;
						if (oldDisposables != null) oldDisposables.each(Disposable::dispose);
						dialog[0].userObject = currentDisposables;
					}
				}
			};

			// --- Header Setup ---
			header.label(() -> "Length: " + (arrayHolder.get() == null ? "null" : Array.getLength(arrayHolder.get()))).padRight(8);
			header.button(Icon.refresh, clearNonei, () -> {
				Vec2            pos         = getAbsolutePos(dialog[0]);
				Seq<Disposable> disposables = dialog[0].userObject instanceof Seq<?> s ? (Seq<Disposable>) s : null;
				if (disposables != null) disposables.each(Disposable::dispose);
				rebuild.run();
				Time.runTask(1f, () -> dialog[0].setPosition(pos));
			}).size(btn_size).padRight(4);
			header.button(Icon.add, clearNonei, () -> {
				Class<?> componentType = clazz.getComponentType();
				Object   defaultValue  = FieldUtils.defaultValue(componentType);
				showArrayChangeWarning("Adding an element will create a new array instance.", () -> {
					arrayHolder.set(addArrayElement(arrayHolder.get(), defaultValue));
					rebuild.run();
				});
			}).size(btn_size);

			// --- Layout ---
			mainCont.add(header).growX().row();
			ScrollPane arrayPane = new ScrollPane(arrayCont, Styles.smallPane);
			arrayPane.setScrollingDisabledX(true);
			arrayPane.setOverscroll(false, false);
			mainCont.add(arrayPane).grow().row();

			// --- Window Creation ---
			dialog[0] = new DisWindow(clazz.getSimpleName(), 200, 200, true);
			dialog[0].cont.add(mainCont).grow();

			// Add global listener when shown, remove when hidden
			dialog[0].shown(() -> mainCont.addCaptureListener(globalListener));
			dialog[0].hidden(() -> {
				mainCont.removeCaptureListener(globalListener);

				Seq<Disposable> disposables = dialog[0].userObject instanceof Seq<?> s ? (Seq<Disposable>) s : null;
				if (disposables != null) disposables.each(Disposable::dispose);
				dialog[0] = null;
			});


			rebuild.run(); // Initial build

			dialog[0].show();
			return dialog[0];
		} else {
			var dialog = new ShowInfoWindow(o, clazz);
			dialog.show();
			return dialog;
		}
	}

	/** Builds the scrollable content for the array view with manual drag */
	private static void buildArrayCont(AtomicReference<Object> arrayHolderRef, Class<?> arrayClass, Table cont,
	                                   Cons<Disposable> registerDisposable, Runnable requestKeyboardFocus,
	                                   DragState dragState,
	                                   Runnable rebuildAction) {
		Object array = arrayHolderRef.get();
		if (array == null || !array.getClass().isArray()) {
			cont.add("Error").row();
			return;
		}

		Class<?> componentType = arrayClass.getComponentType();
		int      length        = Array.getLength(array);

		// Separator before the first element (potential drop target 0)
		addSeparator(cont, 0, dragState, arrayHolderRef, rebuildAction);

		for (int i = 0; i < length; i++) {
			// Build the actual row
			buildRowWithManualDrag(cont, arrayHolderRef, componentType, i, registerDisposable, requestKeyboardFocus, dragState, rebuildAction);
			// Separator after the element (potential drop target i+1)
			addSeparator(cont, i + 1, dragState, arrayHolderRef, rebuildAction);
		}
	}
	class RowTable extends LimitTable {
		public int     index;
		public boolean cancelClick = false;
		public RowTable(int index) {
			this.index = index;
			addCaptureListener(new InputListener() {
				public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
					if (cancelClick) event.stop();
					return true;
				}
				public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
					if (cancelClick) event.stop();
				}
			});
		}
	}

	/** Builds a single row with manual drag listener */
	private static void buildRowWithManualDrag(Table listTable, AtomicReference<Object> arrayHolderRef,
	                                           Class<?> componentType, final int index,
	                                           Cons<Disposable> registerDisposable, Runnable requestKeyboardFocus,
	                                           final DragState dragState,
	                                           final Runnable rebuildListAction) {

		var rowTable = new RowTable(index);
		// rowTable.background(Styles.black6); // Background for the row

		// --- Remove Button ---
		rowTable.button(Icon.cancel, clearNonei, () -> {
			showArrayChangeWarning("Removing...", () -> {
				arrayHolderRef.set(removeArrayElement(arrayHolderRef.get(), index));
				rebuildListAction.run();
			});
		}).size(btn_size / 1.5f).padLeft(4).right();

		// --- Item Display ---
		var itemButton = new LimitTextButton("", cleart);
		itemButton.clearChildren();
		itemButton.label(() -> rowTable.index + ":").style(defaultLabel).padRight(8f).color(Color.lightGray);

		var label = new ArrayItemLabel<>(componentType, arrayHolderRef.get(), index);
		itemButton.update(() -> label.i = rowTable.index);

		itemButton.clicked(() -> {
			Object itemValue = label.val;
			if (itemValue != null) {
				IntVars.postToMain(Tools.runT0(() -> showInfo(itemValue).setPosition(getAbsolutePos(itemButton))));
			} else { IntUI.showInfoFade("Element is null (" + componentType.getSimpleName() + ")"); }
		});

		var cell = BindCell.of(ShowInfoWindow.extentCell(itemButton, componentType == Object.class && label.val != null ? label.val.getClass() : componentType, () -> label));
		ShowInfoWindow.buildExtendingField(cell, label);
		registerDisposable.get(cell::clear);

		itemButton.add(label).growX().left();
		rowTable.add(itemButton).growX().minHeight(40);

		IntUI.addWatchButton(rowTable, () -> arrayHolderRef.get() + "#" + rowTable.index, () -> Array.get(arrayHolderRef.get(), index)).padLeft(4);

		listTable.add(rowTable).growX().row();
	}

	/** Adds a separator image which acts as a drop target */
	private static void addSeparator(Table listTable, final int targetIndex, // Logical index after this separator
	                                 final DragState dragState,
	                                 final AtomicReference<Object> arrayHolderRef,
	                                 final Runnable rebuildListAction) {

		// The separator image itself
		Image separator = Underline.of(listTable, 2).get();
		listTable.row(); // Ensure separator is in its own row
	}

	// --- Array Manipulation Helpers ---
	private static void showArrayChangeWarning(String message, Runnable confirmedAction) {
		IntUI.showConfirm("Warning", message + "\n\nContinue?", confirmedAction);
	}

	/** Moves an element within the array. Returns a new array instance. */
	private static Object moveArrayElement(Object array, int fromIndex, int toIndex) {
		int length = Array.getLength(array);
		// Corrected boundary check for toIndex: should be <= length for inserting at the end
		if (fromIndex < 0 || fromIndex >= length || toIndex < 0 || toIndex > length || fromIndex == toIndex) {
			Log.debug("Move ignored: from=@, to=@, length=@", fromIndex, toIndex, length);
			return array;
		}
		Log.debug("Executing move: from=@, to=@", fromIndex, toIndex);

		Object   element       = Array.get(array, fromIndex);
		Class<?> componentType = array.getClass().getComponentType();
		// Use ArrayList for easier manipulation
		List<Object> list = new ArrayList<>(length);
		for (int i = 0; i < length; i++) {
			list.add(Array.get(array, i));
		}

		// Remove the element from its original position
		list.remove(fromIndex);

		// Add the element at the target position (clamped)
		int insertPos = Math.max(0, Math.min(toIndex, list.size()));
		list.add(insertPos, element);

		// Create new array and copy back from the list
		Object newArray = Array.newInstance(componentType, length);
		for (int i = 0; i < length; i++) {
			Array.set(newArray, i, list.get(i));
		}

		return newArray;
	}


	/** Removes an element and returns a NEW array instance. */
	private static Object removeArrayElement(Object array, int index) { /* ... (implementation unchanged) ... */
		int length = Array.getLength(array);
		if (index < 0 || index >= length) {
			throw new IndexOutOfBoundsException("Index: " + index + ", Length: " + length);
		}
		Object newArray = Array.newInstance(array.getClass().getComponentType(), length - 1);
		System.arraycopy(array, 0, newArray, 0, index);
		if (index < length - 1) { System.arraycopy(array, index + 1, newArray, index, length - index - 1); }
		return newArray;
	}

	/** Adds an element to the end and returns a NEW array instance. */
	private static Object addArrayElement(Object array,
	                                      Object element) { /* ... (implementation unchanged, uses isInstance) ... */
		int      length        = Array.getLength(array);
		Class<?> componentType = array.getClass().getComponentType();
		if (element != null && !componentType.isInstance(element)) {
			Log.err("Cannot add element of type @ to array of type @", element.getClass().getName(), componentType.getName());
			IntUI.showException("Type Mismatch", new IllegalArgumentException("Cannot add element of type " + element.getClass().getSimpleName() + " to array of " + componentType.getSimpleName()));
			return array;
		}
		Object newArray = Array.newInstance(componentType, length + 1);
		System.arraycopy(array, 0, newArray, 0, length);
		try { Array.set(newArray, length, element); } catch (IllegalArgumentException e) {
			Log.err("Failed to set element in new array", e);
			IntUI.showException("Error Adding Element", e);
			return array;
		}
		return newArray;
	}

	/** Sets an element at a specific index. Returns the original array for simplicity. */
	private static Object setArrayElement(Object array, int index, Object element) {
		try {
			Array.set(array, index, element);
		} catch (IllegalArgumentException e) {
			Log.err("Failed to set element at index @", index, e);
			IntUI.showException("Error Setting Element", e);
		}
		return array;
	}

	// --- Remaining INFO_DIALOG methods ---
	static JSWindow window(final Cons<Window> cons) { return new JSWindow(cons); }
	static JSWindow btn(String text, Runnable run) { return dialog(t -> t.button(text, flatt, run).size(64, 45)); }
	static JSWindow testDraw(Runnable draw) { return testDraw0(_ -> draw.run()); }
	static JSWindow testDraw0(Cons<Group> draw) {
		return dialog(new Group() {
			{ transform = true; }

			public void drawChildren() { draw.get(this); }
		});
	}
	static JSWindow testShader(Shader shader, Runnable draw) {
		return testDraw0(t -> {
			FrameBuffer buffer  = new FrameBuffer(Core.graphics.getWidth(), Core.graphics.getHeight());
			Texture     texture = WorldDraw.drawTexture(buffer, draw);
			Draw.blit(texture, shader);
			buffer.dispose();
		});
	}
	static JSWindow dialog(Element element) { return window(d -> d.cont.pane(element).grow()); }
	static JSWindow dialogText(String text) { return dialog(new Label(text, defaultLabel)); }
	static JSWindow dialog(TextureRegion region) { return dialog(new Image(region)); }
	static JSWindow dialog(Texture texture) { return dialog(new TextureRegion(texture)); }
	static JSWindow dialogd(Drawable drawable) { return dialog(new Image(drawable)); }
	static JSWindow dialog(Color color) { return dialog(new ColorImage(color)); }
	static JSWindow pixmap(int size, Cons<Pixmap> cons) { return pixmap(size, size, cons); }
	static JSWindow pixmap(int width, int height, Cons<Pixmap> cons) {
		Pixmap pixmap = new Pixmap(width, height);
		cons.get(pixmap);
		JSWindow dialog = dialog(new TextureRegion(new Texture(pixmap)));
		dialog.hidden(pixmap::dispose);
		return dialog;
	}
	static JSWindow dialog(Cons<Table> cons) { return dialog(new Table(cons)); }
	static void dialog(Element element, boolean disposable) { dialog(element).autoDispose = disposable; }
	static void dialogText(String text, boolean disposable) { dialogText(text).autoDispose = disposable; }
	static void dialog(TextureRegion region, boolean disposable) { dialog(region).autoDispose = disposable; }
	static void dialog(Texture texture, boolean disposable) { dialog(texture).autoDispose = disposable; }
	static void dialog(Drawable drawable, boolean disposable) { dialogd(drawable).autoDispose = disposable; }
	static void dialog(Color color, boolean disposable) { dialog(color).autoDispose = disposable; }
	static void dialog(Pixmap pixmap,
	                   boolean disposable) { dialog(new TextureRegion(new Texture(pixmap))).autoDispose = disposable; }

	class $ {
		@SuppressWarnings("rawtypes")
		public static void buildLongPress(ImageButton button,
		                                  Prov o) { EventHelper.longPress0(button, () -> INFO_DIALOG.showInfo(o)); }
	}

	class JSWindow extends HiddenTopWindow implements IDisposable {
		Cons<Window> cons;
		public boolean autoDispose = false;
		Runnable autoDisposeRun;
		private void dispose() { if (autoDispose && !HopeInput.pressed.isEmpty()) { hide(); } }
		public void act(float delta) {
			super.act(delta);
			if (autoDispose) titleTable.remove();
			if (autoDisposeRun != null) autoDisposeRun.run();
		}
		public JSWindow(Cons<Window> cons) {
			super("TEST", 64, 64);
			this.cons = cons;
			show();
			title.setFontScale(0.7f);
			for (Cell<?> child : titleTable.getCells()) { if (child.get() instanceof ImageButton) { child.size(24); } }
			titleHeight = 28;
			((Table) titleTable.parent).getCell(titleTable).height(titleHeight);
			cons.get(this);
			moveToMouse();
			Time.runTask(40, () -> { if (autoDispose) autoDisposeRun = this::dispose; });
		}
	}
}