package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Examples.Names;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Text fields in the rows of virtual lists and in scroll areas, where both lines of work meet. */
class FieldRowsTest {

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  /** {@link Examples#fieldRows} at 280 by 200: the filter, and rows 0 to 7 exist. */
  private static App<Names> start() {
    App<Names> app = new App<>(new Names(), Examples::fieldRows);
    app.textRendering(TextRendering.LINUX);
    app.resize(280, 200, 1);
    app.frame();
    return app;
  }

  /** The element with this key in the last frame, or null. */
  private static El find(App<?> app, String key) {
    return find(app.tree(), key);
  }

  private static El find(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static El list(App<?> app) {
    return find(app, "rows");
  }

  /** Presses the middle of the element with this key. */
  private static void press(App<?> app, String key) {
    float[] at = centre(app, key);
    app.pointerDown(at[0], at[1]);
    app.pointerUp(at[0], at[1]);
    app.frame();
  }

  private static float[] centre(App<?> app, String key) {
    El target = find(app, key);
    List<El> path = new ArrayList<>();
    pathTo(app.tree(), target, path);
    float x = 0;
    float y = 0;
    for (El el : path) {
      x += el.x();
      y += el.y();
    }
    return new float[] {x + target.width() / 2, y + target.height() / 2};
  }

  private static boolean pathTo(El el, El target, List<El> path) {
    path.add(el);
    if (el == target) {
      return true;
    }
    for (El child : el.children()) {
      if (pathTo(child, target, path)) {
        return true;
      }
    }
    path.removeLast();
    return false;
  }

  private static void key(App<?> app, String name, int modifiers) {
    app.keyDown(new Key(name, modifiers, false));
    app.keyUp(new Key(name, modifiers, false));
    app.frame();
  }

  private static void type(App<?> app, String text) {
    app.textInput(text);
    app.frame();
  }

  private static void scrollToRow(App<?> app, int row) {
    app.scrollToRow(list(app), row);
    app.frame();
  }

  /** The line of the dump that has this key. */
  private static String line(App<?> app, String key) {
    return app.tree()
        .dump()
        .lines()
        .filter(l -> l.contains(" #" + key + " "))
        .findFirst()
        .orElseThrow()
        .strip();
  }

  // Fields in the rows of a virtual list

  @Test
  void typingInAFieldOfARowEditsThatRowAndTheDumpShowsIt() {
    App<Names> app = start();
    press(app, "name-1");
    assertSame(find(app, "name-1"), app.focused());
    key(app, "Home", 0);
    type(app, "old_");
    assertEquals("old_addresses", app.state().name(1));
    assertEquals(1, app.state().edited.size(), "the other rows are as they were");
    assertEquals(
        "text #name-1 role=textbox label=\"Name of row 1\" \"old_addresses\" 60,80"
            + " 200x24 focused caret 4",
        line(app, "name-1"));
    assertTrue(line(app, "rows").endsWith(" rows 0-7/1000"), line(app, "rows"));

    key(app, "Z", Key.CONTROL);
    assertEquals("addresses", app.state().name(1), "the field's history survived the rebuilds");
  }

  @Test
  void aFocusedFieldKeepsItsEditingStateWhileItsRowIsOutAndOthersDropTheirs() {
    App<Names> app = start();
    press(app, "name-0");
    type(app, "_x");
    press(app, "name-2");
    key(app, "Home", 0);
    key(app, "ArrowRight", Key.SHIFT);
    key(app, "ArrowRight", Key.SHIFT);
    Editor zero = find(app, "name-0").editor();
    Editor two = find(app, "name-2").editor();
    assertEquals(2, two.caret());
    assertEquals(0, two.anchor());

    scrollToRow(app, 500);
    assertNull(app.focused(), "the focused row does not exist now");
    assertNull(find(app, "name-2"));
    assertFalse(app.tree().dump().contains("focused"));

    scrollToRow(app, 0);
    El field = find(app, "name-2");
    assertSame(field, app.focused(), "the focus came back with the row");
    assertSame(two, field.editor(), "and with it the field's editor");
    assertTrue(line(app, "name-2").endsWith(" focused caret 2 anchor 0"), line(app, "name-2"));
    assertNotSame(
        zero,
        find(app, "name-0").editor(),
        "an unfocused field's state went with its row, as everything else the app keeps of it");
    assertNotNull(field.text().caret(), "the caret shows at once");

    type(app, "AU");
    assertEquals("AUdit_log", app.state().name(2), "typing replaces the selection it had");

    scrollToRow(app, 500);
    assertNull(app.focused());
    type(app, "-");
    assertSame(find(app, "name-2"), app.focused(), "text brings the field's row back");
    assertEquals("AU-dit_log", app.state().name(2), "and goes in at its caret");
    scrollToRow(app, 500);
    key(app, "Backspace", 0);
    assertEquals("AUdit_log", app.state().name(2), "as Backspace does");
    assertSame(find(app, "name-2"), app.focused());
  }

  @Test
  void aFocusedFieldInRowsTheListBuildsAnewShowsItsCaretAndSelectionAtOnce() {
    // Rows that are fields, without looks, so that scrolling builds rows without the view.
    int[] views = {0};
    App<Names> app = new App<>(new Names(), n -> {
      views[0]++;
      return col()
          .key("rows")
          .wFull()
          .hFull()
          .rows(
              1000,
              32,
              i -> El.text(n.name(i))
                  .editable(s -> n.edited.put(i, s))
                  .key("name-" + i)
                  .padding(0, 4));
    });
    app.textRendering(TextRendering.LINUX);
    app.resize(280, 200, 1);
    app.frame();
    press(app, "name-3");
    key(app, "Home", 0);
    key(app, "ArrowRight", Key.SHIFT | Key.CONTROL);
    El before = find(app, "name-3");
    assertEquals(1, before.text().selection().size());
    int built = views[0];

    app.wheel(140, 100, 0, 130); // the list needs row 10, which it lacks, and keeps row 3
    assertTrue(app.frame());
    El field = find(app, "name-3");
    assertNotSame(before, field, "the list built its rows anew");
    assertEquals(built, views[0], "without the view");
    assertSame(field, app.focused());
    assertTrue(line(app, "name-3").endsWith(" focused caret 5 anchor 0"), line(app, "name-3"));
    assertNotNull(field.text().caret(), "the new element shows the caret");
    assertEquals(before.text().selection(), field.text().selection(), "and the selection");
  }

  @Test
  void aCompositionEndsWhenItsRowScrollsOut() {
    App<Names> app = start();
    press(app, "name-3");
    app.textComposition("ni", -1);
    app.frame();
    assertTrue(line(app, "name-3").endsWith(" focused caret 5 composing \"ni\""));

    scrollToRow(app, 300);
    scrollToRow(app, 0);
    assertTrue(line(app, "name-3").endsWith(" focused caret 5"), line(app, "name-3"));
    assertEquals("carts", app.state().name(3), "the composition was not committed");
  }

  @Test
  void tabEntersAFieldInARowSelectingItsTextAndComesBackToIt() {
    App<Names> app = start();
    press(app, "filter");
    key(app, "Tab", 0);
    assertSame(find(app, "name-0"), app.focused());
    assertTrue(line(app, "name-0").endsWith(" focused caret 8 anchor 0"), "all selected");

    key(app, "Tab", Key.SHIFT);
    assertSame(find(app, "filter"), app.focused());
    scrollToRow(app, 600);
    assertNull(find(app, "name-0"));
    key(app, "Tab", 0);
    assertSame(find(app, "name-0"), app.focused(), "where the focus was in the list");
    assertEquals(0, list(app).scrollY(), "its row scrolled back into view");
    assertTrue(line(app, "name-0").endsWith(" focused caret 8 anchor 0"), line(app, "name-0"));
  }
}
