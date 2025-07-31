package modtools.ui.comp.completion;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.ui.HopeStyles;

public class CompletionPopup extends Table {
	private final TextField       area;
	private final ScrollPane      scrollPane;
	private final Table           listTable;
	private final Seq<String>     allSuggestions    = new Seq<>();
	private final Seq<TextButton> suggestionButtons = new Seq<>();
	private       Cons<String>    onSelect;
	private       int    selectedIndex     = -1;
	public        int    cursorBeforeDot;
	private       String currentPrefix     = "";
	private       boolean         shown             = false;

	public CompletionPopup(TextField area) {
		super(Tex.pane);
		this.area = area;
		touchable = Touchable.enabled;
		visible = false;

		listTable = new Table();
		scrollPane = new ScrollPane(listTable, Styles.smallPane);

		scrollPane.setFadeScrollBars(false);
		scrollPane.setOverscroll(false, false);
		// Prevent scrollpane from stealing scroll focus from main text area
		scrollPane.setCancelTouchFocus(false);


		add(scrollPane).grow().maxHeight(Core.graphics.getHeight() / Scl.scl() * 0.9f);

		update(this::keepInStage);
		// Listener for global clicks to hide the popup
		Core.scene.addCaptureListener(new InputListener() {
			@Override
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (shown && event.targetActor != CompletionPopup.this && (event.targetActor == null || !event.targetActor.isDescendantOf(CompletionPopup.this))) {
					hide();
				}
				return false;
			}
		});
	}

	public void show(Seq<String> suggestions, String prefix, Cons<String> onSelect, float preferredX, float preferredY) {
		if (suggestions.isEmpty()) {
			hide();
			return;
		}

		this.allSuggestions.clear().addAll(suggestions);
		// Sort suggestions alphabetically, case-insensitive
		this.allSuggestions.sort(String.CASE_INSENSITIVE_ORDER);

		this.currentPrefix = prefix;
		this.onSelect = onSelect;
		this.selectedIndex = -1; // Reset selection

		rebuildList(); // This will also set selectedIndex if list is not empty
		pack();

		// Position below the preferredY (which should be the baseline of the current text line)
		float popupY = preferredY - getHeight() - area.getStyle().font.getLineHeight() * 0.2f; // Try to position above the line
		if (popupY < 0) { // If it goes off top, position below the line
			popupY = preferredY + area.getStyle().font.getLineHeight();
		}

		float popupX = preferredX;

		// Clamp to screen
		if (popupX + getWidth() > Core.graphics.getWidth()) {
			popupX = Core.graphics.getWidth() - getWidth();
		}
		popupX = Math.max(0, popupX);

		if (popupY + getHeight() > Core.graphics.getHeight()) {
			popupY = Core.graphics.getHeight() - getHeight();
		}
		popupY = Math.max(0, popupY);

		setPosition(popupX, popupY);

		visible = true;
		shown = true;
		toFront();
		// Do not steal focus: area.getScene().setKeyboardFocus(this);
	}


	private void rebuildList() {
		listTable.clearChildren(); // Clear actors and cells
		suggestionButtons.clear();

		for (String suggestion : allSuggestions) {
			// Filter by prefix client-side, even though it might be pre-filtered
			if (suggestion.toLowerCase().startsWith(currentPrefix.toLowerCase())) {
				TextButton button = new TextButton(suggestion, HopeStyles.cleart);
				button.getLabelCell().labelAlign(Align.left).padLeft(4).padRight(4);
				// Log.info(button.getLabel());
				button.clicked(() -> select(suggestion));
				listTable.add(button).wrapLabel(false).growX().height(24f * Scl.scl()).row();
				suggestionButtons.add(button);
			}
		}

		if (suggestionButtons.isEmpty()) {
			hide();
		} else {
			if (selectedIndex == -1 || selectedIndex >= suggestionButtons.size) {
				selectedIndex = 0; // Default to first item
			}
			highlightSelection();
			scrollToSelection(); // Ensure selection is visible
		}
		listTable.layout();
		listTable.invalidateHierarchy();
		pack();
	}

	public void filter(String prefix) {
		if (!shown) return;

		if (this.currentPrefix.equals(prefix)) return;
		this.currentPrefix = prefix;
		rebuildList();
	}


	private void selectCurrent() {
		if (selectedIndex >= 0 && selectedIndex < suggestionButtons.size) {
			select(suggestionButtons.get(selectedIndex).getText().toString());
		} else {
			// If nothing is selected but Enter/Tab is pressed, consider it as no-op for completion
			// but still hide the popup if it's an explicit selection action.
			hide();
		}
	}

	private void select(String suggestion) {
		if (onSelect != null) {
			onSelect.get(suggestion);
		}
		hide();
	}

	public void hide() {
		if (!shown) return;
		visible = false;
		shown = false;
		// If this popup was added by a Hitter, the hitter would manage removal.
		// If added directly to scene/stage, remove() might be needed if it's not reused.
		// For now, just set invisible.
	}

	public boolean isShown() {
		return shown;
	}

	public boolean handleKeyDown(KeyCode keycode) {
		if (!shown || suggestionButtons.isEmpty()) return false;

		if (keycode == KeyCode.escape) {
			hide();
			return true;
		}
		if (keycode == KeyCode.enter || keycode == KeyCode.tab) {
			selectCurrent();
			return true;
		}
		if (keycode == KeyCode.up) {
			selectedIndex = Mathf.clamp(selectedIndex - 1, 0, suggestionButtons.size - 1);
			highlightSelection();
			scrollToSelection();
			return true;
		}
		if (keycode == KeyCode.down) {
			selectedIndex = Mathf.clamp(selectedIndex + 1, 0, suggestionButtons.size - 1);
			highlightSelection();
			scrollToSelection();
			return true;
		}
		return false;
	}

	private void highlightSelection() {
		for (int i = 0; i < suggestionButtons.size; i++) {
			suggestionButtons.get(i).getLabel().setColor(i == selectedIndex ? Pal.accent : Color.white);
		}
	}

	private void scrollToSelection() {
		if (selectedIndex >= 0 && selectedIndex < suggestionButtons.size && scrollPane.getHeight() > 0) {
			Core.app.post(() -> { // Defer scroll to next frame to ensure layout is correct
				if (selectedIndex >= 0 && selectedIndex < suggestionButtons.size) {
					Element selectedButton = suggestionButtons.get(selectedIndex);
					scrollPane.scrollTo(0, selectedButton.y, selectedButton.getWidth(), selectedButton.getHeight(), false, true);
				}
			});
		}
	}
}