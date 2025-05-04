package modtools.jsfunc;


import arc.Core;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.event.*;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import mindustry.gen.*;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.ui.IntUI;
import modtools.ui.comp.Window;
import modtools.ui.comp.Window.*;
import modtools.ui.comp.limit.*;
import modtools.ui.comp.utils.ArrayItemLabel;
import modtools.ui.control.HopeInput;
import modtools.utils.*;
import modtools.utils.JSFunc.JColor;
import modtools.utils.reflect.FieldUtils;
import modtools.utils.search.BindCell;
import modtools.utils.ui.ShowInfoWindow;
import modtools.utils.world.WorldDraw;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static modtools.ui.HopeStyles.*;
import static modtools.ui.IntUI.topGroup;
import static modtools.utils.ElementUtils.getAbsolutePos;

public interface INFO_DIALOG {
	float vsize = 42; // Size for control buttons

	//region Static Inner Class for Drag State
	/** Holds the state during a drag-and-drop operation for array items. */
	static class DragState {
		boolean isDragging                 = false;
		Table   dragRow                    = null;       // The actual row element being dragged
		Table   originalRowParent          = null; // Parent of the original row (to add/remove it)
		int     originalRowIndex           = -1; // Index in the original parent
		Element dragActor                  = null;     // Visual representation while dragging
		Table   dropPlaceholder            = null; // Visual indicator for drop position
		int     fromIndex                  = -1; // Logical index of the item being dragged
		int     potentialToIndex           = -1;  // Logical index where the drop *would* occur
		Vec2    dragStartOffset            = new Vec2(); // Offset from mouse click to top-left of dragRow
		Image   currentDropTargetSeparator = null; // The separator currently being hovered over

		/** Creates the placeholder element if it doesn't exist. */
		Table getOrCreatePlaceholder() {
			if (dropPlaceholder == null) {
				dropPlaceholder = new Table().background(Tex.selection);
				dropPlaceholder.setSize(dragRow.getWidth(), dragRow.getHeight()); // Match size of dragged row
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
				@Override
				public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
					if (dragState.isDragging) {
						Log.debug("Drag cancelled by touchUp outside drop target");
						cleanupDragState(dragState); // Clean up visuals and state
						// No rebuild needed as no change occurred
					}
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
			}).size(vsize).padRight(4);
			header.button(Icon.add, clearNonei, () -> {
				Class<?> componentType = clazz.getComponentType();
				Object   defaultValue  = FieldUtils.defaultValue(componentType);
				showArrayChangeWarning("Adding an element will create a new array instance.", () -> {
					arrayHolder.set(addArrayElement(arrayHolder.get(), defaultValue));
					rebuild.run();
				});
			}).size(vsize);

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
			dialog[0].shown(() -> Core.scene.addListener(globalListener));
			dialog[0].hidden(() -> {
				Core.scene.removeListener(globalListener);
				cleanupDragState(dragState); // Clean up if window is hidden during drag
				Seq<Disposable> disposables = dialog[0].userObject instanceof Seq<?> s ? (Seq<Disposable>) s : null;
				if (disposables != null) disposables.each(Disposable::dispose);
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

	/** Builds a single row with manual drag listener */
	private static void buildRowWithManualDrag(Table listTable, AtomicReference<Object> arrayHolderRef,
	                                           Class<?> componentType, final int index,
	                                           Cons<Disposable> registerDisposable, Runnable requestKeyboardFocus,
	                                           final DragState dragState,
	                                           final Runnable rebuildListAction) {

		Table rowTable = new LimitTable();
		// rowTable.background(Styles.black6); // Background for the row

		// --- Remove Button ---
		rowTable.button(Icon.cancel, clearNonei, () -> {
			showArrayChangeWarning("Removing...", () -> {
				arrayHolderRef.set(removeArrayElement(arrayHolderRef.get(), index));
				rebuildListAction.run();
			});
		}).size(vsize / 1.5f).padLeft(4).right();

		// --- Item Display ---
		var itemButton = new LimitTextButton("", cleart);
		itemButton.clearChildren();
		itemButton.add(index + ":", defaultLabel).padRight(8f).color(Color.lightGray);

		var label = new ArrayItemLabel<>(componentType, arrayHolderRef.get(), index);

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

		IntUI.addWatchButton(rowTable, arrayHolderRef.get() + "#" + index, () -> Array.get(arrayHolderRef.get(), index)).padLeft(4);

		// --- Manual Drag Listener for the Row ---
		rowTable.addListener(new InputListener() {
			final Vec2 localStart = new Vec2(); // Local tracking for drag start
			boolean canDrag       = false;
			float   dragThreshold = 10f; // Pixels to move before drag starts

			@Override
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (button != KeyCode.mouseLeft) return false;
				if (dragState.isDragging) return false; // Prevent starting new drag

				canDrag = true;
				localStart.set(x, y);
				return true; // Capture events
			}

			@Override
			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				if (!canDrag) return;

				if (!dragState.isDragging && localStart.dst(x, y) > dragThreshold) {
					// --- Start Drag ---
					dragState.isDragging = true;
					dragState.fromIndex = index;
					dragState.dragRow = rowTable; // The row being dragged
					// dragState.originalRowParent = rowTable.parent; // Store parent
					dragState.originalRowIndex = rowTable.parent.getChildren().indexOf(rowTable); // Store index
					dragState.dragStartOffset.set(x, y); // Offset within the row

					// Create visual actor for dragging (clone the row visually)
					// Cloning elements is complex. Let's make a simple visual clone.
					dragState.dragActor = new Label(index + ": " + label.getText().toString(), Styles.outlineLabel); // Simple actor
					dragState.dragActor.pack();
					topGroup.addChild(dragState.dragActor);

					// Create placeholder element and put it in the original row's place
					Table placeholder = dragState.getOrCreatePlaceholder();
					// ((Table) rowTable.parent).getCell(rowTable).clearElement(); // Temporarily remove original row
					// Re-add placeholder in the original position
					if (dragState.originalRowParent != null && dragState.originalRowIndex != -1) {
						dragState.originalRowParent.addChildAt(dragState.originalRowIndex, placeholder);
					}

					// Dim original row representation (actor)
					dragState.dragRow.setColor(Color.lightGray); // Use lightGray
				}

				if (dragState.isDragging) {
					// --- Update Drag Actor Position ---
					Vec2 stageCoords = rowTable.localToStageCoordinates(Tmp.v1.set(x, y));
					// Position actor's top-left based on stage coords minus the initial offset
					dragState.dragActor.setPosition(
					 stageCoords.x - dragState.dragStartOffset.x,
					 stageCoords.y - (dragState.dragActor.getHeight() - dragState.dragStartOffset.y), // Adjust Y based on actor height and offset
					 Align.topLeft);

					// Placeholder position is handled by the separator listener now
					// We don't need to update it here based on mouse, only the separator does.
				}
			}

			@Override
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (!canDrag) return;
				canDrag = false;
				// Don't handle drop here, the separator listener handles it.
				// cleanupDragState will be called by the separator or global listener.
			}
		});

		listTable.add(rowTable).growX().row();
	}

	/** Adds a separator image which acts as a drop target */
	private static void addSeparator(Table listTable, final int targetIndex, // Logical index after this separator
	                                 final DragState dragState,
	                                 final AtomicReference<Object> arrayHolderRef,
	                                 final Runnable rebuildListAction) {

		// The separator image itself
		Image separator = listTable.image().color(Tmp.c1.set(JColor.c_underline))
		 .growX().pad(0).height(3f).get();
		listTable.row(); // Ensure separator is in its own row

		separator.addListener(new InputListener() {
			@Override
			public void enter(InputEvent event, float x, float y, int pointer, Element fromActor) {
				if (dragState.isDragging && pointer == -1) { // pointer == -1 for mouse hover
					// Highlight this separator
					if (dragState.currentDropTargetSeparator != null) {
						dragState.currentDropTargetSeparator.setColor(Tmp.c1.set(JColor.c_underline));
					}
					separator.setColor(Color.cyan);
					dragState.potentialToIndex = targetIndex;
					dragState.currentDropTargetSeparator = separator;

					// Position placeholder over this separator's row
					Table placeholder = dragState.getOrCreatePlaceholder();
					positionPlaceholder(listTable, placeholder, targetIndex);
				}
			}

			@Override
			public void exit(InputEvent event, float x, float y, int pointer, Element toActor) {
				// If exiting the current target separator (and still dragging/hovering)
				if (dragState.isDragging && dragState.currentDropTargetSeparator == separator && pointer == -1) {
					separator.setColor(Tmp.c1.set(JColor.c_underline));
					dragState.potentialToIndex = -1;
					dragState.currentDropTargetSeparator = null;
					// Remove placeholder when exiting the target
					if (dragState.dropPlaceholder != null) dragState.dropPlaceholder.remove();
				}
			}

			@Override
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				// Allow touchDown to pass through
				return false;
			}

			@Override
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (dragState.isDragging && button == KeyCode.mouseLeft) {
					// --- Drop Occurred on this Separator ---
					int fromIndex = dragState.fromIndex;
					int toIndex   = targetIndex; // Drop is *before* the element at targetIndex

					Log.debug("Drop detected on separator: targetIndex = @", targetIndex);

					cleanupDragState(dragState); // Clean up visuals and state
					event.cancel(); // Prevent global listener

					// Adjust target index if moving item downwards
					if (fromIndex < toIndex) {
						toIndex--;
					}

					if (fromIndex != toIndex) {
						Log.debug("Final Move: from @ to @", fromIndex, toIndex);
						final int finalToIndex = toIndex;
						showArrayChangeWarning("Moving an element will create a new array instance.", () -> {
							arrayHolderRef.set(moveArrayElement(arrayHolderRef.get(), fromIndex, finalToIndex));
							rebuildListAction.run();
						});
					} else {
						Log.debug("Drop resulted in no index change.");
						// If no change, need to put the original row back
						if (dragState.dragRow != null && dragState.originalRowParent != null && dragState.originalRowIndex != -1) {
							// Re-add the original row where it was removed from
							dragState.originalRowParent.addChildAt(dragState.originalRowIndex, dragState.dragRow);
						}
					}
				}
			}
		});
	}

	/** Cleans up drag actor, resets original row appearance, and resets drag state */
	private static void cleanupDragState(DragState dragState) {
		if (dragState.isDragging) {
			if (dragState.dragActor != null) dragState.dragActor.remove();
			if (dragState.currentDropTargetSeparator != null) {
				dragState.currentDropTargetSeparator.setColor(Tmp.c1.set(JColor.c_underline));
			}
			if (dragState.dropPlaceholder != null) dragState.dropPlaceholder.remove();

			// Restore original row if it was removed
			if (dragState.dragRow != null && dragState.dragRow.parent == null && dragState.originalRowParent != null && dragState.originalRowIndex != -1) {
				// Add back to its original position
				dragState.originalRowParent.addChildAt(dragState.originalRowIndex, dragState.dragRow);
			}

			if (dragState.dragRow != null) {
				dragState.dragRow.touchable = Touchable.enabled;
				dragState.dragRow.actions(Actions.alpha(1f, 0.1f));
			}

			// Reset state fields
			dragState.isDragging = false;
			dragState.dragRow = null;
			dragState.originalRowParent = null;
			dragState.originalRowIndex = -1;
			dragState.dragActor = null;
			// dragState.dropPlaceholder = null; // Don't null, just remove from parent
			dragState.fromIndex = -1;
			dragState.potentialToIndex = -1;
			dragState.currentDropTargetSeparator = null;
		}
	}

	/** Calculates the target index for dropping based on Y coordinate */
	private static int calculateDropIndex(Table listTable, float stageY, AtomicReference<Object> arrayHolderRef) {
		// This method is less relevant now that separators handle the drop index.
		// The separator's touchUp listener will use its own targetIndex.
		// Keeping a simplified version for reference or potential other uses.
		Seq<Element> children     = listTable.getChildren();
		int          elementIndex = 0; // Tracks the logical index of the array element
		for (int i = 0; i < children.size; i++) {
			Element child = children.get(i);
			if (child instanceof Image) { // Separator
				// Do nothing for logical index, just skip visual element
			} else if (child instanceof LimitTable) { // Assuming rows are LimitTable
				Vec2 childPos = child.localToStageCoordinates(Tmp.v2.set(0, child.getHeight() / 2f));
				if (stageY > childPos.y) {
					return elementIndex; // Drop *before* this element
				}
				elementIndex++; // Increment logical index only for element rows
			}
		}
		// If below all rows, drop at the end
		return Array.getLength(arrayHolderRef.get());
	}


	/** Positions the placeholder visual */
	private static void positionPlaceholder(Table listTable, Table placeholder, int targetIndex) {
		int childIndex = targetIndex * 2; // Account for row + separator in the visual listTable
		if (placeholder.parent != listTable) {
			// Ensure index is valid before adding
			childIndex = Math.min(childIndex, listTable.getChildren().size);
			listTable.addChildAt(childIndex, placeholder);
		} else if (listTable.getChildren().indexOf(placeholder) != childIndex) {
			// If already added but position is wrong, move it
			placeholder.remove();
			childIndex = Math.min(childIndex, listTable.getChildren().size);
			listTable.addChildAt(childIndex, placeholder);
		}
		placeholder.toFront();
	}


	// --- Array Manipulation Helpers ---
	private static void showArrayChangeWarning(String message, Runnable confirmedAction) {
		IntUI.showConfirm("Warning", message + "\n\nThis action cannot be undone. Proceed?", confirmedAction);
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
		public boolean  autoDispose = false;
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