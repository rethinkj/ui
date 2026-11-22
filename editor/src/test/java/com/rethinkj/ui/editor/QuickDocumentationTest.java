package com.rethinkj.ui.editor;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.editor.CodeEditor.codeEditor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.CodeWords;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.kit.GermanTexts;
import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.Texts;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.text.TextRendering;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Hover and keyboard ({@link CodeEditor.Command#QUICK_DOCUMENTATION}) quick documentation popovers
 * in {@link CodeEditor}.
 */
class QuickDocumentationTest {

  private static final String SAMPLE = "SELECT id, total\nFROM orders\nWHERE status = 'paid';";

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  static final class Doc {
    String text = SAMPLE;
    final CodeEditor.State editor = new CodeEditor.State();
    boolean readOnly;
    App<Doc> app;
    int parentCtrlQ;

    Doc(boolean withProvider) {
      if (withProvider) {
        editor.documentation(this::documentationAt);
      }
    }

    MarkdownDocument documentationAt(int offset) {
      int from = CodeWords.start(text, offset);
      int to = CodeWords.end(text, offset);
      return switch (text.substring(from, to)) {
        case "orders" -> Markdown.parse("**Table `orders`**\n\nCustomer orders.");
        case "total" -> Markdown.parse("Order total in cents.");
        default -> null;
      };
    }
  }

  private static App<Doc> app(Doc doc) {
    App<Doc> app = new App<>(
        doc,
        state -> col()
            .wFull()
            .hFull()
            .padding(12)
            .onKey(k -> {
              if (k.name().equals("Q") && k.modifiers() == Key.CONTROL) {
                state.parentCtrlQ++;
                return true;
              }
              return false;
            })
            .child(codeEditor(
                    state.app,
                    state.text,
                    state.editor,
                    null,
                    state.readOnly ? null : value -> state.text = value)
                .key("code")
                .lines(6)
                .wFull()));
    doc.app = app;
    app.textRendering(TextRendering.LINUX);
    app.resize(480, 260, 1);
    app.frame();
    return app;
  }

  private static El findByKey(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = findByKey(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static El findOverlay(App<?> app, String key) {
    for (El overlay : app.tree().shownOverlays()) {
      if (key.equals(overlay.key())) {
        return overlay;
      }
    }
    return null;
  }

  private static float[] wordPoint(App<?> app, String text, String word) {
    El editor = findByKey(app.tree(), "code");
    assertNotNull(editor);
    int offset = text.indexOf(word) + 1;
    // Use the word's bounding box via textPositionAt scan along the editor's rows.
    for (float y = 4; y < editor.height() - 4; y += 6) {
      for (float x = 4; x < editor.width() - 4; x += 6) {
        int pos = editor.textPositionAt(x, y);
        if (pos >= text.indexOf(word) && pos < text.indexOf(word) + word.length()) {
          return new float[] {editor.x() + x, editor.y() + y};
        }
      }
    }
    throw new AssertionError("word not found in editor: " + word + " at " + offset);
  }

  @Test
  void hoverShowsPopoverStaysOverPopoverAndDismissesOnLeavePressEscapeTypeAndCaretMove() {
    Doc doc = new Doc(true);
    App<Doc> app = app(doc);
    double delay = Theme.DARK.motion().tooltipDelay();

    float[] orders = wordPoint(app, doc.text, "orders");
    app.pointerMove(orders[0], orders[1]);
    app.frame();
    assertNull(findOverlay(app, "documentation"), "not shown before tooltipDelay");

    app.advance(delay + 0.05);
    app.frame();
    app.advance(0.3);
    app.frame();
    El pop = findOverlay(app, "documentation");
    assertNotNull(pop, "shown after tooltipDelay");
    assertEquals("dialog", pop.role());
    assertEquals("Documentation", pop.label());
    assertTrue(pop.y() > orders[1], "placed below hovered word");

    // Moving onto the popover keeps it open.
    app.pointerMove(pop.x() + 12, pop.y() + 12);
    app.frame();
    assertNotNull(findOverlay(app, "documentation"), "stays open while pointer is over popover");

    // Moving away from both the word and the popover closes it.
    app.pointerMove(2, 2);
    app.frame();
    app.advance(0.3);
    app.frame();
    assertNull(findOverlay(app, "documentation"), "closes when leaving word and popover");

    // Re-open by hover, then press inside editor: closes popover AND moves caret.
    app.pointerMove(orders[0], orders[1]);
    app.frame();
    app.advance(delay + 0.3);
    app.frame();
    assertNotNull(findOverlay(app, "documentation"));
    float[] total = wordPoint(app, doc.text, "total");
    app.pointerMove(total[0], total[1]); // a press comes where the pointer moved to
    app.pointerDown(total[0], total[1], 1, 0);
    app.pointerUp(total[0], total[1]);
    app.frame();
    app.advance(0.3);
    app.frame();
    assertNull(findOverlay(app, "documentation"), "closed on press outside");
    assertTrue(doc.editor.caret() > 0, "outside press still acted on editor");

    // Re-open by hover while editor has focus, then Escape closes it.
    app.pointerMove(orders[0], orders[1]);
    app.frame();
    app.advance(delay + 0.3);
    app.frame();
    assertNotNull(findOverlay(app, "documentation"));
    app.keyDown(new Key("Escape", 0, false));
    app.keyUp(new Key("Escape", 0, false));
    app.frame();
    app.advance(0.3);
    app.frame();
    assertNull(findOverlay(app, "documentation"), "closed on Escape");

    // Re-open by hover, then typing closes it.
    app.pointerMove(total[0], total[1]);
    app.frame();
    app.advance(delay + 0.3);
    app.frame();
    assertNotNull(findOverlay(app, "documentation"));
    app.textInput("x");
    app.frame();
    app.advance(0.3);
    app.frame();
    assertNull(findOverlay(app, "documentation"), "closed on typing");
  }

  @Test
  void controlQOpensAtCaretAlsoReadOnlyPassesThroughWithoutProviderAndUsesGermanTexts() {
    // Read-only editor with German texts.
    Doc doc = new Doc(true);
    doc.readOnly = true;
    doc.editor.readOnly(true);
    Texts german = new GermanTexts();
    App<Doc> app = app(doc).provide(Texts.CURRENT, s -> german);
    app.frame();

    El editor = findByKey(app.tree(), "code");
    app.focus(editor);
    int ordersAt = doc.text.indexOf("orders") + 2;
    app.select(editor, ordersAt, ordersAt);
    app.frame();

    app.keyDown(new Key("Q", Key.CONTROL, false));
    app.keyUp(new Key("Q", Key.CONTROL, false));
    app.frame();
    app.advance(0.3);
    app.frame();
    El pop = findOverlay(app, "documentation");
    assertNotNull(pop, "Control+Q opens popover in read-only editor");
    assertEquals("Dokumentation", pop.label());
    assertEquals(0, doc.parentCtrlQ, "Control+Q consumed when provider is set");

    // Moving caret with an arrow key closes the popover.
    app.keyDown(new Key("ArrowRight", 0, false));
    app.keyUp(new Key("ArrowRight", 0, false));
    app.frame();
    app.advance(0.3);
    app.frame();
    assertNull(findOverlay(app, "documentation"), "closed when caret moves");

    // Without a documentation provider, Control+Q bubbles to the parent.
    Doc plain = new Doc(false);
    App<Doc> plainApp = app(plain);
    plainApp.focus(findByKey(plainApp.tree(), "code"));
    plainApp.frame();
    plainApp.keyDown(new Key("Q", Key.CONTROL, false));
    plainApp.keyUp(new Key("Q", Key.CONTROL, false));
    plainApp.frame();
    assertEquals(1, plain.parentCtrlQ, "Control+Q bubbles when no provider is configured");
    assertFalse(
        plainApp.tree().shownOverlays().stream().anyMatch(o -> "documentation".equals(o.key())));
  }
}
