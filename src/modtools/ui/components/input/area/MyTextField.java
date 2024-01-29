package modtools.ui.components.input.area;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.graphics.g2d.Font.FontData;
import arc.graphics.g2d.GlyphLayout.GlyphRun;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Scene;
import arc.scene.event.ChangeListener.ChangeEvent;
import arc.scene.event.*;
import arc.scene.style.Drawable;
import arc.scene.ui.TextField;
import arc.scene.utils.Disableable;
import arc.struct.FloatSeq;
import arc.util.*;
import arc.util.Timer.Task;
import arc.util.pooling.Pools;

import static arc.Core.*;

/**
 * A single-line text input field.
 * <p>
 * The preferred height of a text field is the height of the {@link TextFieldStyle#font} and {@link TextFieldStyle#background}.
 * The preferred width of a text field is 150, a relatively arbitrary size.
 * <p>
 * The text field will copy the currently selected text when ctrl+c is pressed, and paste any text in the clipboard when ctrl+v is
 * pressed. Clipboard functionality is provided via the Clipboard interface. Currently there are two standard
 * implementations, one for the desktop and one for Android. The Android clipboard is a stub, as copy & pasting on Android is not
 * supported yet.
 * <p>
 * <p>
 * <i>I hope</i>重写了一些逻辑
 * <p>
 *
 * @author mzechner
 * @author Nathan Sweet
 * @author i hope
 */
// 安卓上不行
@SuppressWarnings("SizeReplaceableByIsEmpty")
public class MyTextField extends TextField implements Disableable {

	public StringBuilder text;

	public    StringBuilder undoText      = new StringBuilder();
	public    CharSequence  displayText   = new StringBuilder();
	protected KeyRepeatTask keyRepeatTask = new KeyRepeatTask();
	public    StringBuilder passwordBuffer;

	public MyTextField() {
		super("");
	}

	public MyTextField(String text) {
		super(text);
	}

	public MyTextField(String text, TextFieldStyle style) {
		super(text, style);
	}

	protected InputListener createInputListener() {
		return new TextFieldClickListener();
	}

	protected int letterUnderCursor(float x) {
		if (visibleTextStart >= glyphPositions.size) {
			return text.length();
		}
		x -= textOffset + fontOffset - style.font.getData().cursorX - glyphPositions.get(visibleTextStart);
		Drawable background = getBackgroundDrawable();
		if (background != null) x -= style.background.getLeftWidth();
		int     n              = this.glyphPositions.size;
		float[] glyphPositions = this.glyphPositions.items;
		for (int i = 1; i < n; i++) {
			if (glyphPositions[i] > x) {
				if (glyphPositions[i] - x <= x - glyphPositions[i - 1]) return i;
				return i - 1;
			}
		}

		return n - 1;
	}

	protected int[] wordUnderCursor(int at) {
		StringBuilder text  = this.text;
		int           right = text.length(), left = 0, index = at;
		if (at >= text.length()) {
			left = text.length();
			right = 0;
		} else {
			for (; index < right; index++) {
				if (!isWordCharacter(text.charAt(index))) {
					right = index;
					break;
				}
			}
			for (index = at - 1; index > -1; index--) {
				if (!isWordCharacter(text.charAt(index))) {
					left = index + 1;
					break;
				}
			}
		}
		return new int[]{left, right};
	}

	int[] wordUnderCursor(float x) {
		return wordUnderCursor(letterUnderCursor(x));
	}

	public void clearText() {
		text.setLength(0);
	}
	protected void calculateOffsets() {
		float    visibleWidth = getWidth();
		Drawable background   = getBackgroundDrawable();
		if (background != null) visibleWidth -= background.getLeftWidth() + background.getRightWidth();

		int     glyphCount     = glyphPositions.size;
		float[] glyphPositions = this.glyphPositions.items;

		// Check if the cursor has gone out the left or right side of the visible area and adjust renderOffset.
		cursor = Mathf.clamp(cursor, 0, glyphPositions.length - 1);
		float distance = glyphPositions[Math.max(0, cursor - 1)] + renderOffset;
		if (distance <= 0)
			renderOffset -= distance;
		else {
			int   index = Math.min(glyphCount - 1, cursor + 1);
			float minX  = glyphPositions[index] - visibleWidth;
			if (-renderOffset < minX) renderOffset = -minX;
		}

		// Prevent renderOffset from starting too close to the end, eg after text was deleted.
		float maxOffset = 0;
		float width     = glyphPositions[Mathf.clamp(glyphCount - 1, 0, glyphPositions.length - 1)];
		for (int i = glyphCount - 2; i >= 0; i--) {
			float x = glyphPositions[i];
			if (width - x > visibleWidth) break;
			maxOffset = x;
		}
		if (-renderOffset > maxOffset) renderOffset = -maxOffset;

		// calculate first visible char based on render offset
		visibleTextStart = 0;
		float startX = 0;
		for (int i = 0; i < glyphCount; i++) {
			if (glyphPositions[i] >= -renderOffset) {
				visibleTextStart = i;
				startX = glyphPositions[i];
				break;
			}
		}

		// calculate last visible char based on visible width and render offset
		int length = Math.min(displayText.length(), glyphPositions.length - 1);
		visibleTextEnd = Math.min(length, cursor + 1);
		for (; visibleTextEnd <= length; visibleTextEnd++)
			if (glyphPositions[visibleTextEnd] > startX + visibleWidth) break;
		visibleTextEnd = Math.max(0, visibleTextEnd - 1);

		if ((textHAlign & Align.left) == 0) {
			textOffset = visibleWidth - (glyphPositions[visibleTextEnd] - startX);
			if ((textHAlign & Align.center) != 0) textOffset = Math.round(textOffset * 0.5f);
		} else
			textOffset = startX + renderOffset;

		// calculate selection x position and width
		if (hasSelection) {
			int   minIndex = Math.min(cursor, selectionStart);
			int   maxIndex = Math.max(cursor, selectionStart);
			float minX     = Math.max(glyphPositions[minIndex] - glyphPositions[visibleTextStart], -textOffset);
			float maxX     = Math.min(glyphPositions[maxIndex] - glyphPositions[visibleTextStart], visibleWidth - textOffset);
			selectionX = minX;
			selectionWidth = maxX - minX - style.font.getData().cursorX;
		}
	}

	private Drawable getBackgroundDrawable() {
		Scene   stage   = getScene();
		boolean focused = stage != null && stage.getKeyboardFocus() == this;
		return (disabled && style.disabledBackground != null) ? style.disabledBackground
		 : (!isValid() && style.invalidBackground != null) ? style.invalidBackground
		 : ((focused && style.focusedBackground != null) ? style.focusedBackground : style.background);
	}


	public void draw() {
		Scene   stage   = getScene();
		boolean focused = stage != null && stage.getKeyboardFocus() == this;
		if (!focused) keyRepeatTask.cancel();

		final Font font = style.font;
		final Color fontColor = (disabled && style.disabledFontColor != null) ? style.disabledFontColor
		 : ((focused && style.focusedFontColor != null) ? style.focusedFontColor : style.fontColor);
		final Drawable selection   = style.selection;
		final Drawable cursorPatch = style.cursor;
		final Drawable background  = getBackgroundDrawable();

		Color color  = this.color;
		float x      = this.x;
		float y      = this.y;
		float width  = getWidth();
		float height = getHeight();

		Draw.color(color.r, color.g, color.b, color.a * parentAlpha);
		float bgLeftWidth = 0, bgRightWidth = 0;
		if (background != null) {
			background.draw(x, y, width, height);
			bgLeftWidth = background.getLeftWidth();
			bgRightWidth = background.getRightWidth();
		}

		float textY = getTextY(font, background);
		calculateOffsets();

		if (focused && hasSelection && selection != null) {
			drawSelection(selection, font, x + bgLeftWidth, y + textY);
		}

		float yOffset = font.isFlipped() ? -textHeight : 0;
		if (displayText.length() == 0) {
			if (!focused && messageText != null) {
				Font messageFont = style.messageFont != null ? style.messageFont : font;
				messageFont.getColor().write(Tmp.c1);

				if (style.messageFontColor != null) {
					messageFont.setColor(style.messageFontColor.r, style.messageFontColor.g, style.messageFontColor.b,
					 style.messageFontColor.a * color.a * parentAlpha);
				} else {
					messageFont.setColor(0.7f, 0.7f, 0.7f, color.a * parentAlpha);
				}

				boolean had = messageFont.getData().markupEnabled;
				messageFont.getData().markupEnabled = false;
				messageFont.draw(messageText, x + bgLeftWidth, y + textY + yOffset, 0, messageText.length(),
				 width - bgLeftWidth - bgRightWidth, textHAlign, false, "...");
				messageFont.getData().markupEnabled = had;
				messageFont.setColor(Tmp.c1);
			}
		} else {
			font.setColor(fontColor.r, fontColor.g, fontColor.b, fontColor.a * color.a * parentAlpha);
			drawText(font, x + bgLeftWidth, y + textY + yOffset);
		}
		if (focused && !disabled) {
			blink();
			if (cursorOn && cursorPatch != null) {
				drawCursor(cursorPatch, font, x + bgLeftWidth, y + textY);
			}
		}
	}

	public boolean isValid() {
		return validator == null || validator.valid(String.valueOf(text));
	}

	/** Draws selection rectangle **/
	protected void drawSelection(Drawable selection, Font font, float x, float y) {
		selection.draw(x + textOffset + selectionX + fontOffset, y - textHeight - font.getDescent(), selectionWidth, textHeight);
	}

	protected void drawText(Font font, float x, float y) {
		boolean had = font.getData().markupEnabled;
		font.getData().markupEnabled = false;
		// Log.info(displayText);
		font.draw(displayText, x + textOffset, y, visibleTextStart, visibleTextEnd, 0, Align.left, false);
		font.getData().markupEnabled = had;
	}

	protected void drawCursor(Drawable cursorPatch, Font font, float x, float y) {
		cursorPatch.draw(
		 x + textOffset + glyphPositions.get(cursor) - glyphPositions.get(visibleTextStart) + fontOffset + font.getData().cursorX,
		 y - textHeight - font.getDescent(), cursorPatch.getMinWidth(), textHeight);
	}
	public int displayTextStart0() {
		return 0;
	}
	public int displayTextEnd0() {
		return text.length();
	}
	protected void updateDisplayText() {
		Font          font = style.font;
		FontData      data = font.getData();
		StringBuilder text = this.text;

		int textLength = Math.min(text.length(), displayTextEnd0());

		StringBuilder newDisplayText = (StringBuilder) displayText;
		newDisplayText.setLength(0);
		for (int i = displayTextStart0(); i < textLength; i++) {
			char c = text.charAt(i);
			newDisplayText.append(data.hasGlyph(c) ? c : ' ');
		}
		if (newDisplayText.length() == 0) newDisplayText.append(' ');
		// Log.info(newDisplayText);

		if (passwordMode && data.hasGlyph(passwordCharacter)) {
			if (passwordBuffer == null) passwordBuffer = new StringBuilder(newDisplayText.length());
			if (passwordBuffer.length() > textLength)
				passwordBuffer.setLength(textLength);
			else {
				for (int i = passwordBuffer.length(); i < textLength; i++)
					passwordBuffer.append(passwordCharacter);
			}
			displayText = passwordBuffer;
		} else
			displayText = newDisplayText;

		layout.setText(font, text.toString().replace('\n', ' ').replace('\r', ' '));
		glyphPositions.clear();
		float x = 0;
		if (layout.runs.size > 0) {
			GlyphRun run       = layout.runs.first();
			FloatSeq xAdvances = run.xAdvances;
			fontOffset = xAdvances.first();
			for (int i = 1, n = xAdvances.size; i < n; i++) {
				glyphPositions.add(x);
				x += xAdvances.get(i);
			}
		} else {
			fontOffset = 0;
		}
		glyphPositions.add(x);

		visibleTextStart = Math.min(visibleTextStart, glyphPositions.size);
		visibleTextEnd = Mathf.clamp(visibleTextEnd, visibleTextStart, glyphPositions.size);

		if (selectionStart > newDisplayText.length()) selectionStart = textLength;
	}

	private void blink() {
		if (!Core.graphics.isContinuousRendering()) {
			cursorOn = true;
			return;
		}
		long time = Time.nanos();
		if ((time - lastBlink) / 1000000000.0f > blinkTime) {
			cursorOn = !cursorOn;
			lastBlink = time;
		}
	}

	/** Copies the contents of this TextField to the lipboard implementation set on this TextField. */
	public void copy() {
		if (hasSelection && !passwordMode) {
			Core.app.setClipboardText(text.substring(Math.min(cursor, selectionStart), Math.max(cursor, selectionStart)));
		}
	}

	/**
	 * Copies the selected contents of this TextField to the Clipboard implementation set on this TextField, then removes
	 * it.
	 */
	public void cut() {
		cut(programmaticChangeEvents);
	}

	void cut(boolean fireChangeEvent) {
		if (hasSelection && !passwordMode) {
			copy();
			cursor = delete(fireChangeEvent);
			updateDisplayText();
		}
	}

	public void paste(String content, boolean fireChangeEvent) {
		if (content != null && content.isEmpty()) return;
		paste(content == null ? null : new StringBuilder(content), fireChangeEvent);
	}

	public void paste(StringBuilder content, boolean fireChangeEvent) {
		if (content == null || (content.length() == 0 && text.length() == 0)) return;

		StringBuilder buffer     = new StringBuilder();
		int           textLength = text.length();
		if (hasSelection) textLength -= Math.abs(cursor - selectionStart);
		FontData data = style.font.getData();
		for (int i = 0, n = content.length(); i < n; i++) {
			if (!withinMaxLength(textLength + buffer.length())) break;
			char c = content.charAt(i);
			if (c == '\r') continue;
			if (!(writeEnters && (c == '\n'))) {
				if (c == '\n') continue;
				if (onlyFontChars && !data.hasGlyph(c)) continue;
				if (filter != null && !filter.acceptChar(this, c)) continue;
			}
			buffer.append(c);
		}
		content = buffer;

		if (hasSelection) cursor = delete(fireChangeEvent);
		if (fireChangeEvent) {
			text = insert(cursor, content, text);
			changeText();
		} else
			text = insert(cursor, content, text);
		updateDisplayText();
		cursor += content.length();
	}

	public StringBuilder insert(int position, CharSequence text, StringBuilder to) {
		if (to.length() == 0) {
			if (text instanceof StringBuilder) return (StringBuilder) text;
			return to.append(text);
		}
		return to.insert(position, text);
	}
	public StringBuilder insert(int position, char c, StringBuilder to) {
		return to.insert(position, c);
	}

	int delete(boolean fireChangeEvent) {
		int from     = selectionStart;
		int to       = cursor;
		int minIndex = Math.min(from, to);
		int maxIndex = Math.max(from, to);

		text.delete(Math.max(0, minIndex), Math.min(maxIndex, text.length()));
		if (fireChangeEvent) {
			changeText();
		}
		clearSelection();
		return minIndex;
	}

	/** @return May be null. */
	public String getMessageText() {
		return messageText;
	}

	/**
	 * Sets the text that will be drawn in the text field if no text has been entered.
	 *
	 * @param messageText may be null.
	 */
	public void setMessageText(String messageText) {
		if (messageText != null && (messageText.startsWith("$") || messageText.startsWith("@")) && bundle != null && bundle.has(messageText.substring(1))) {
			this.messageText = bundle.get(messageText.substring(1));
		} else {
			this.messageText = messageText;
		}
	}

	/** @param str If null, "" is used. */
	public void appendText(String str) {
		if (str == null) str = "";

		clearSelection();
		cursor = text.length();
		paste(str, programmaticChangeEvents);
	}

	/** @return Never null, might be an empty string. */
	public StringBuilder getText0() {
		return text;
	}
	public String getText() {
		return text.toString();
	}


	/** @param str If null, "" is used. */
	public void setText0(StringBuilder str) {
		if (str == null) str = new StringBuilder();
		// if (str.equals(text)) return;

		clearSelection();
		text = new StringBuilder();
		paste(str, false);
		if (programmaticChangeEvents) {
			changeText();
		}
		cursor = 0;
	}
	public void setText(String str) {
		if (str == null) str = "";
		checkText();
		if (str.contentEquals(text)) return;

		clearSelection();
		text.setLength(0);
		paste(str, false);
		if (programmaticChangeEvents) {
			changeText();
		}
		cursor = 0;
	}
	private void checkText() {
		if (text == null) text = new StringBuilder();
	}
	/**
	 * @param oldText May be null.
	 *
	 * @return True if the text was changed.
	 */
	boolean changeText(StringBuilder oldText, StringBuilder newText) {
		if (newText.toString().contentEquals(oldText)) return false;
		text = newText;
		ChangeEvent changeEvent = Pools.obtain(ChangeEvent.class, ChangeEvent::new);
		boolean     cancelled   = fire(changeEvent);
		text = cancelled ? oldText : newText;
		Pools.free(changeEvent);
		return !cancelled;
	}
	/* 无条件更新 */
	boolean changeText() {
		ChangeEvent changeEvent = Pools.obtain(ChangeEvent.class, ChangeEvent::new);
		fire(changeEvent);
		Pools.free(changeEvent);
		return true;
	}

	public String getSelection() {
		return hasSelection ? text.substring(Math.min(selectionStart, cursor), Math.max(selectionStart, cursor)) : "";
	}

	/** Sets the selected text. */
	public void setSelection(int selectionStart, int selectionEnd) {
		if (selectionStart < 0) throw new IllegalArgumentException("selectionStart must be >= 0");
		if (selectionEnd < 0) throw new IllegalArgumentException("selectionEnd must be >= 0");
		selectionStart = Math.min(text.length(), selectionStart);
		selectionEnd = Math.min(text.length(), selectionEnd);
		if (selectionEnd == selectionStart) {
			clearSelection();
			return;
		}
		if (selectionEnd < selectionStart) {
			int temp = selectionEnd;
			selectionEnd = selectionStart;
			selectionStart = temp;
		}

		hasSelection = true;
		this.selectionStart = selectionStart;
		cursor = selectionEnd;
	}

	public void selectAll() {
		setSelection(0, text.length());
	}

	/** Sets the cursor position and clears any selection. */
	public void setCursorPosition(int cursorPosition) {
		if (cursorPosition < 0) throw new IllegalArgumentException("cursorPosition must be >= 0");
		clearSelection();
		cursor = Math.min(cursorPosition, text.length());
	}

	/**
	 * Sets text horizontal alignment (left, center or right).
	 *
	 * @see Align
	 */
	public void setAlignment(int alignment) {
		this.textHAlign = alignment;
	}

	/**
	 * If true, the text in this text field will be shown as bullet characters.
	 *
	 * @see #setPasswordCharacter(char)
	 */
	public void setPasswordMode(boolean passwordMode) {
		this.passwordMode = passwordMode;
		updateDisplayText();
	}

	/**
	 * Sets the password character for the text field. The character must be present in the {@link Font}. Default is 149
	 * (bullet).
	 */
	public void setPasswordCharacter(char passwordCharacter) {
		this.passwordCharacter = passwordCharacter;
		if (passwordMode) updateDisplayText();
	}
	protected void moveCursor(boolean forward, boolean jump) {
		int limit      = forward ? text.length() : 0;
		int charOffset = forward ? 0 : -1;
		int index      = cursor + charOffset;
		if (forward == (index >= limit)) return;
		char    c      = text.charAt(index);
		boolean isWord = isWordCharacter(c);
		while ((forward ? ++cursor < limit : --cursor > limit) && jump) {
			c = text.charAt(index);
			if (c == '\n' || c == '\r') break;
			if (isWord != continueCursor(cursor, charOffset)) break;
		}
	}
	protected boolean continueCursor(int index, int offset) {
		char c = text.charAt(index + offset);
		return isWordCharacter(c);
	}
	public boolean withinMaxLength(int size) {
		return maxLength <= 0 || size < maxLength;
	}


	protected class KeyRepeatTask extends Task {
		KeyCode keycode;

		public void run() {
			inputListener.keyDown(null, keycode);
		}
	}
	/** Basic input listener for the text field */
	public class TextFieldClickListener extends ClickListener {

		public void clicked(InputEvent event, float x, float y) {
			if (imeData != null) return;
			int count = getTapCount() % 4;
			if (count == 0) clearSelection();
			if (count == 2) {
				int[] array = wordUnderCursor(x);
				setSelection(array[0], array[1]);
			}
			if (count == 3) selectAll();
		}


		public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
			if (!super.touchDown(event, x, y, pointer, button)) return false;
			if (pointer == 0 && button != KeyCode.mouseLeft) return false;
			if (disabled || imeData != null) return true;
			setCursorPosition(x, y);
			selectionStart = cursor;
			Scene stage = getScene();
			if (stage != null) stage.setKeyboardFocus(MyTextField.this);
			if (!hasInputDialog) {
				input.setOnscreenKeyboardVisible(true);
			}
			hasSelection = true;
			return true;
		}


		public void touchDragged(InputEvent event, float x, float y, int pointer) {
			super.touchDragged(event, x, y, pointer);
			setCursorPosition(x, y);
		}


		public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
			if (selectionStart == cursor) hasSelection = false;
			super.touchUp(event, x, y, pointer, button);
		}

		protected void setCursorPosition(float x, float y) {
			lastBlink = 0;
			cursorOn = false;
			cursor = letterUnderCursor(x);
		}

		protected void goHome(boolean jump) {
			cursor = 0;
		}

		protected void goEnd(boolean jump) {
			cursor = text.length();
		}


		public boolean keyDown(InputEvent event, KeyCode keycode) {
			if (disabled) return false;
			if (imeData != null) return true;

			lastBlink = 0;
			cursorOn = false;

			Scene stage = getScene();
			if (stage == null || stage.getKeyboardFocus() != MyTextField.this) return false;

			boolean repeat = false;
			boolean ctrl   = Core.input.ctrl() && !Core.input.alt();
			boolean jump   = ctrl && !passwordMode;

			if (ctrl) {
				if (keycode == KeyCode.v) {
					paste(Core.app.getClipboardText(), true);
					repeat = true;
				}
				if (keycode == KeyCode.c || keycode == KeyCode.insert) {
					copy();
					return true;
				}
				if (keycode == KeyCode.x) {
					cut(true);
					return true;
				}
				if (keycode == KeyCode.a) {
					selectAll();
					return true;
				}
				if (keycode == KeyCode.z) {
					StringBuilder oldText = text;
					setText0(undoText);
					undoText = oldText;
					updateDisplayText();
					return true;
				}
			}

			if (Core.input.shift()) {
				if (keycode == KeyCode.insert) paste(Core.app.getClipboardText(), true);
				if (keycode == KeyCode.forwardDel) cut(true);
				selection:
				{
					int temp = cursor;
					keys:
					{
						if (keycode == KeyCode.left) {
							moveCursor(false, jump);
							repeat = true;
							break keys;
						}
						if (keycode == KeyCode.right) {
							moveCursor(true, jump);
							repeat = true;
							break keys;
						}
						if (keycode == KeyCode.home) {
							goHome(jump);
							break keys;
						}
						if (keycode == KeyCode.end) {
							goEnd(jump);
							break keys;
						}
						break selection;
					}
					if (!hasSelection) {
						selectionStart = temp;
						hasSelection = true;
					}
				}
			} else {
				// Cursor movement or other keys (kills selection).
				if (keycode == KeyCode.left) {
					moveCursor(false, jump);
					clearSelection();
					repeat = true;
				}
				if (keycode == KeyCode.right) {
					moveCursor(true, jump);
					clearSelection();
					repeat = true;
				}
				if (keycode == KeyCode.home) {
					goHome(jump);
					clearSelection();
				}
				if (keycode == KeyCode.end) {
					goEnd(jump);
					clearSelection();
				}
			}
			cursor = Mathf.clamp(cursor, 0, text.length());

			if (repeat) {
				scheduleKeyRepeatTask(keycode);
			}
			return true;
		}

		protected void scheduleKeyRepeatTask(KeyCode keycode) {
			if (!keyRepeatTask.isScheduled() || keyRepeatTask.keycode != keycode) {
				keyRepeatTask.keycode = keycode;
				keyRepeatTask.cancel();
				Timer.schedule(keyRepeatTask, keyRepeatInitialTime, keyRepeatTime);
			}
		}


		public boolean keyUp(InputEvent event, KeyCode keycode) {
			if (disabled) return false;
			if (imeData != null) return true;
			keyRepeatTask.cancel();
			return true;
		}

		protected boolean checkFocusTraverse(char character) {
			return focusTraversal && (character == TAB || ((character == '\r' || character == '\n') && Core.app.isMobile()));
		}


		public boolean keyTyped(InputEvent event, char character) {
			if (disabled) return false;

			// Disallow "typing" most ASCII control characters, which would show up as a space when onlyFontChars is true.
			switch (character) {
				case DELETE:
				case BACKSPACE:
				case TAB:
				case '\r':
				case '\n':
					if (imeData != null) return true;
					break;
				default:
					if (character < 32) return false;
			}

			Scene stage = getScene();
			if (stage == null || stage.getKeyboardFocus() != MyTextField.this) return false;

			if (OS.isMac && Core.input.keyDown(KeyCode.sym)) return true;

			if (checkFocusTraverse(character)) {
				next(Core.input.shift());
			} else {
				boolean delete    = character == DELETE;
				boolean backspace = character == BACKSPACE;
				boolean enter     = character == '\n' || character == '\r';
				boolean add       = enter ? writeEnters : (!onlyFontChars || style.font.getData().hasGlyph(character));
				boolean remove    = backspace || delete;
				boolean jump      = input.ctrl();
				if (add || remove) {
					StringBuilder oldText   = text;
					int           oldCursor = cursor;
					if (hasSelection) cursor = delete(false);
					else {
						if (backspace && cursor > 0) {
							int start = cursor - 1, lastCursor = cursor;
							if (jump) {
								moveCursor(false, true);
								start = cursor;
							} else cursor--;
							text.delete(start, lastCursor);
							renderOffset = 0;
						}
						if (delete && cursor < text.length()) {
							text.delete(cursor, cursor + 1);
						}
					}
					if (add && !remove) {
						// Character may be added to the text.
						if (filter != null && !filter.acceptChar(MyTextField.this, character)) return true;
						if (!withinMaxLength(text.length())) return true;
						char insertion = enter ? '\n' : character;
						text.insert(cursor++, insertion);
					}
					if (changeText()) {
						long time = System.currentTimeMillis();
						if (time - 750 > lastChangeTime) undoText = oldText;
						lastChangeTime = time;
					} else
						cursor = oldCursor;
					updateDisplayText();
				}
			}
			if (listener != null) listener.keyTyped(MyTextField.this, character);
			return true;
		}
	}
}