package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Examples.Lights;
import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Keyboard focus, keys and text: who gets them, the order of Tab, and the ring. */
class FocusTest {

  private static <S> App<S> start(App<S> app, float width, float height) {
    app.resize(width, height, 1);
    app.frame();
    return app;
  }

  private static void key(App<?> app, String name, int modifiers) {
    app.keyDown(new Key(name, modifiers, false));
    app.keyUp(new Key(name, modifiers, false));
    app.frame();
  }

  private static void tab(App<?> app) {
    key(app, "Tab", 0);
  }

  private static void press(App<?> app, float x, float y) {
    app.pointerDown(x, y);
    app.pointerUp(x, y);
    app.frame();
  }

  private static String focusedKey(App<?> app) {
    return app.focused() == null ? null : app.focused().key();
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

  // Tab order

  @Test
  void tabGoesThroughNestedElementsInTreeOrderAndWrapsAround() {
    App<Object> app = start(
        new App<>(
            new Object(),
            s -> col()
                .children(
                    el().key("a")
                        .focusable()
                        .size(40, 10)
                        .child(el().key("b").focusable().size(10, 10)),
                    el().key("plain")
                        .size(40, 10)
                        .child(el().key("c").focusable().size(10, 10)),
                    el().key("d").focusable().size(40, 10))),
        100,
        100);
    List<String> order = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      tab(app);
      order.add(focusedKey(app));
    }
    assertEquals(List.of("a", "b", "c", "d", "a"), order);

    key(app, "Tab", Key.SHIFT);
    assertEquals("d", focusedKey(app), "Shift+Tab wraps back to the end");
    key(app, "Tab", Key.SHIFT);
    assertEquals("c", focusedKey(app));
    key(app, "Tab", Key.CONTROL);
    assertEquals("c", focusedKey(app), "Control+Tab is left to the app");
  }

  @Test
  void shiftTabWithoutFocusStartsAtTheEnd() {
    App<Object> app = start(
        new App<>(
            new Object(),
            s -> col()
                .children(
                    el().key("a").focusable().size(10, 10),
                    el().key("b").focusable().size(10, 10))),
        100,
        100);
    key(app, "Tab", Key.SHIFT);
    assertEquals("b", focusedKey(app));
  }

  @Test
  void tabSkipsWhatTakesNoInputButNotWhatIsScrolledOutOfView() {
    boolean[] leaving = {true};
    App<boolean[]> app = new App<>(leaving, l -> {
      El root = col()
          .children(
              el().key("first").focusable().size(10, 10),
              el().key("hidden").focusable().size(10, 10).opacity(0),
              el().h(0).clip().child(el().key("collapsed").focusable().size(10, 10)),
              el().key("nested").opacity(0).child(el().key("inside").focusable().size(5, 5)),
              col()
                  .key("list")
                  .scroll()
                  .size(50, 20)
                  .children(
                      el().key("row0").focusable().size(50, 20),
                      el().key("row1").focusable().size(50, 20)));
      if (!l[0]) {
        root.child(el().key("leaving").focusable().size(10, 10).motion(Spring.SNAP));
      }
      root.child(el().key("last").focusable().size(10, 10));
      return root;
    });
    start(app, 100, 200);
    leaving[0] = false;
    app.resize(100, 200, 1); // rebuilds: "leaving" is there now
    app.frame();
    leaving[0] = true;
    app.advance(0.05); // it has faded in a little
    app.resize(100, 201, 1); // and now it fades out
    app.frame();
    assertTrue(find(app.tree(), "leaving").isExiting());

    List<String> order = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      tab(app);
      order.add(focusedKey(app));
    }
    assertEquals(List.of("first", "row0", "row1", "last", "first"), order);
  }

  // The pointer

  @Test
  void aPressFocusesTheFocusableElementItLandsInAndAPressElsewhereClearsIt() {
    App<Lights> app = start(new App<>(new Lights(), Examples::switchAlone), 200, 120);
    press(app, 90, 60); // on the knob, inside the switch
    assertEquals("switch", focusedKey(app));
    assertTrue(app.state().on, "and the press still presses");
    app.advance(1);
    app.frame(); // the knob comes to rest

    app.pointerDown(10, 10);
    assertTrue(app.frame(), "the focus went away");
    assertNull(app.focused());
    app.pointerUp(10, 10);
    assertFalse(app.frame(), "nothing else happened");
  }

  @Test
  void anElementThatFadesInTakesTheFocusFromTheFrameThatAddsIt() {
    boolean[] shown = {false};
    List<String> keys = new ArrayList<>();
    App<Object> app = start(
        new App<>(new Object(), s -> {
          El root = col()
              .wFull()
              .hFull()
              .child(el().key("hidden").size(20, 20).focusable().opacity(0));
          return shown[0]
              ? root.child(el().key("new")
                  .size(20, 20)
                  .focusable()
                  .motion(Spring.SNAP)
                  .onKey(k -> keys.add(k.name())))
              : root;
        }),
        100,
        100);
    shown[0] = true;
    app.resize(100, 100, 1); // a rebuild, as after a handler that opens it
    app.frame();
    El fresh = find(app.tree(), "new");
    assertEquals(0, fresh.opacity(), "on its way in");
    app.focus(fresh);
    app.frame();
    assertSame(find(app.tree(), "new"), app.focused());
    key(app, "A", 0);
    assertEquals(List.of("A"), keys);

    app.focus(null);
    app.frame();
    key(app, "Tab", 0);
    assertSame(find(app.tree(), "new"), app.focused(), "Tab reaches it, and not the invisible one");
    assertThrows(IllegalArgumentException.class, () -> app.focus(find(app.tree(), "hidden")));
  }

  // Identity

  @Test
  void theFocusFollowsItsKeyAcrossRebuildsAndIsClearedWhenTheElementGoes() {
    List<String> items = new ArrayList<>(List.of("a", "b", "c"));
    App<List<String>> app = start(
        new App<>(items, list -> {
          El root = row();
          for (String item : list) {
            root.child(el().key(item).focusable().size(20, 20));
          }
          return root;
        }),
        100,
        20);
    El b = find(app.tree(), "b");
    app.focus(b);
    app.frame();
    assertEquals(20, app.focused().x());

    items.remove("a");
    app.resize(100, 20, 1);
    app.frame();
    assertNotEquals(b, app.focused(), "a new tree");
    assertEquals("b", focusedKey(app), "the same identity");
    assertEquals(0, app.focused().x(), "where it moved to");
    assertTrue(
        app.tree().dump().contains("el #b 0,0 20x20 focused\n"), app.tree().dump());

    items.remove("b");
    app.resize(100, 20, 1);
    app.frame();
    assertNull(app.focused());
    assertFalse(app.tree().dump().contains("focused"));
  }

  @Test
  void aFieldWithAFieldStateKeepsTheFocusWhenSiblingsAreAddedOrRemovedBeforeIt() {
    TextLibraries.assumeLoaded();
    FieldState target = new FieldState();
    int[] entered = {0};
    record Entry(String[] text, FieldState state) {}
    List<Entry> entries = new ArrayList<>(List.of(
        new Entry(new String[] {"first"}, null), new Entry(new String[] {"second"}, target)));
    App<List<Entry>> app = new App<>(entries, list -> {
      El root = col().wFull().hFull().gap(4);
      for (Entry entry : list) {
        El field =
            El.text(entry.text()[0]).editable(v -> entry.text()[0] = v).w(120).h(24);
        root.child(
            entry.state() == null
                ? field
                : field.fieldState(entry.state()).onFocusIn(() -> entered[0]++));
      }
      return root;
    });
    app.textRendering(TextRendering.LINUX);
    start(app, 200, 120);

    app.focus(app.tree().children().get(1));
    app.frame();
    assertSame(target, app.focused().fieldState());
    assertNotNull(app.focused().text().caret(), "caret shown before insert");

    // Insert before it: index 1 is now "first", but focus follows target to index 2.
    entries.add(0, new Entry(new String[] {"zero"}, null));
    app.invalidate();
    app.frame();
    assertNotNull(app.focused());
    assertSame(target, app.focused().fieldState(), "focus follows FieldState across insert");
    assertSame(app.tree().children().get(2), app.focused());
    assertNotNull(app.focused().text().caret(), "caret stays visible after insert");
    assertNotSame(app.focused().editor(), app.tree().children().get(1).editor(), "its old index");
    app.textInput("!");
    app.frame();
    assertEquals("zero", entries.get(0).text()[0]);
    assertEquals("first", entries.get(1).text()[0]);
    assertEquals("second!", entries.get(2).text()[0], "typing still goes to the FieldState field");

    // Remove both siblings before it: old index 2 is gone, focus follows target to index 0.
    entries.remove(0);
    entries.remove(0);
    app.invalidate();
    app.frame();
    assertNotNull(app.focused());
    assertSame(target, app.focused().fieldState(), "focus follows FieldState across remove");
    assertSame(app.tree().children().getFirst(), app.focused());
    assertNotNull(app.focused().text().caret(), "caret stays visible after remove");
    app.textInput("?");
    app.frame();
    assertEquals("second!?", entries.getFirst().text()[0]);
    assertEquals(1, entered[0], "onFocusIn runs once, not when the field moves");
  }

  @Test
  void aRootSelectionScopeCopiesAfterASiblingIsAddedBeforeAFocusedFieldState() {
    TextLibraries.assumeLoaded();
    FieldState beta = new FieldState();
    record Item(String key, String text) {}
    List<Item> items = new ArrayList<>(List.of(
        new Item("a", "Alpha one"), new Item("b", "Beta two"), new Item("c", "Gamma three")));
    App<List<Item>> app = new App<>(items, list -> {
      El root = col().wFull().hFull().selectionScope().gap(8);
      for (Item item : list) {
        El field =
            El.text(item.text()).editable(v -> {}).readOnly().w(180).h(24).padding(0, 4);
        root.child(item.key().equals("b") ? field.fieldState(beta) : field.key(item.key()));
      }
      return root;
    });
    app.textRendering(TextRendering.LINUX);
    start(app, 220, 200);

    // Drag from "a" (y 0..24) into unkeyed "b" (y 32..56): focus lands on "b".
    app.pointerDown(32, 12);
    app.pointerMove(36, 44);
    app.pointerUp(36, 44);
    app.frame();
    assertNotNull(app.focused());
    assertSame(beta, app.focused().fieldState());
    key(app, "C", Key.CONTROL);
    String expected = app.clipboard().read();
    assertTrue(expected.contains("\n"), "cross-field selection copied: " + expected);

    // Insert a keyed sibling before "a": index 1 is now keyed "a", so old index 1 has no element.
    items.add(0, new Item("pre", "Inserted zero"));
    app.invalidate();
    app.frame();
    assertNotNull(app.focused(), "focus is not lost in the root selection scope");
    assertSame(beta, app.focused().fieldState());
    app.clipboard().write("stale");
    key(app, "C", Key.CONTROL);
    assertEquals(expected, app.clipboard().read(), "Control+C copies the selection after insert");

    // Remove the inserted sibling before "a": focus follows beta back to index 1.
    items.remove(0);
    app.invalidate();
    app.frame();
    assertNotNull(app.focused());
    assertSame(beta, app.focused().fieldState());
    app.clipboard().write("stale");
    key(app, "C", Key.CONTROL);
    assertEquals(expected, app.clipboard().read(), "Control+C copies the selection after remove");
  }

  /** Two pages of a panel, each with its own fields, as two tabs' contents; a key flips them. */
  private static App<int[]> pages(List<String> keys) {
    return start(
        new App<>(new int[] {0}, page -> {
          El content = col().key("page-" + page[0]);
          for (int i = 0; i < (page[0] == 0 ? 2 : 1); i++) {
            content.child(el().key("field-" + page[0] + "-" + i).focusable().size(40, 10));
          }
          return col()
              .children(
                  el().key("bar").focusable().size(40, 10),
                  col()
                      .key("panel")
                      .onKey(k -> {
                        keys.add(k.name());
                        if (k.name().equals("PageDown")) {
                          page[0] = 1 - page[0];
                          return true;
                        }
                        return false;
                      })
                      .child(content));
        }),
        100,
        100);
  }

  @Test
  void whenTheFocusedElementGoesTheFocusGoesIntoItsNearestAncestorAsFocusingItWould() {
    List<String> keys = new ArrayList<>();
    App<int[]> app = pages(keys);
    tab(app);
    tab(app);
    tab(app);
    assertEquals("field-0-1", focusedKey(app));
    key(app, "PageDown", 0); // the other page, without the focused field
    assertEquals("field-1-0", focusedKey(app), "into the new content, not nowhere");
    assertTrue(app.tree().dump().contains("el #field-1-0 0,10 40x10 focused\n"));
    assertTrue(ring(app.scene()) >= 0, "a key moved it: the ring stays");
    key(app, "PageDown", 0);
    assertEquals("field-0-1", focusedKey(app), "back on the first page, where it was");
    assertEquals(
        List.of("Tab", "PageDown", "PageDown"),
        keys,
        "the panel sees the keys from wherever the focus is inside it");
  }

  @Test
  void aFocusableAncestorTakesTheFocusBackAndWithoutAnyBelowTheRootItIsCleared() {
    boolean[] inner = {true};
    App<boolean[]> app = start(
        new App<>(inner, f -> {
          El list = col().key("list").focusable().size(40, 40);
          if (f[0]) {
            list.child(el().key("item").focusable().size(40, 10));
          }
          return col().children(list, el().key("loose").focusable().size(40, 10));
        }),
        100,
        100);
    tab(app);
    tab(app);
    assertEquals("item", focusedKey(app));
    inner[0] = false;
    app.resize(100, 100, 1);
    app.frame();
    assertEquals("list", focusedKey(app), "the item's own list");

    // A child of the root that goes: nothing around it below the root, so nothing.
    tab(app);
    assertEquals("loose", focusedKey(app));
    App<boolean[]> two = start(
        new App<>(
            new boolean[] {true},
            f -> f[0]
                ? row()
                    .children(
                        el().key("a").focusable().size(10, 10), el().key("b").size(10, 10))
                : row().child(el().key("b").focusable().size(10, 10))),
        100,
        100);
    tab(two);
    two.state()[0] = false;
    two.resize(100, 100, 1);
    two.frame();
    assertNull(two.focused(), "not to b, which is no ancestor of a");
  }

  // Autofocus

  /** A grid with an editor that Enter opens over it and Escape closes, as a cell editor. */
  private static App<boolean[]> editorOverAGrid() {
    return start(
        new App<>(new boolean[] {false}, open -> {
          El area = col()
              .key("area")
              .child(el().key("grid")
                  .focusable()
                  .size(80, 40)
                  .onKey(k -> k.name().equals("Enter") && !open[0] && (open[0] = true)));
          if (open[0]) {
            area.child(el().key("editor")
                .focusable()
                .autofocus()
                .absolute(0, 10)
                .size(80, 10)
                .onKey(k -> k.name().equals("Escape") && !(open[0] = false)));
          }
          return col().children(el().key("before").focusable().size(10, 10), area);
        }),
        100,
        100);
  }

  @Test
  void anElementThatAHandlerAddsWithAutofocusTakesTheFocusAndGivesItBackWhenItGoes() {
    App<boolean[]> app = editorOverAGrid();
    tab(app);
    tab(app);
    assertEquals("grid", focusedKey(app));
    app.keyDown(new Key("Enter", 0, false));
    app.keyDown(new Key("X", 0, false)); // in the same frame: the editor has it already
    app.frame();
    assertEquals("editor", focusedKey(app));
    assertTrue(ring(app.scene()) >= 0, "the keyboard opened it, so it has the ring");

    tab(app); // around to the first element
    assertEquals("before", focusedKey(app));
    app.resize(100, 100, 1);
    app.frame();
    assertEquals("before", focusedKey(app), "only in the build that adds it");

    app.focus(find(app.tree(), "editor"));
    app.frame();
    key(app, "Escape", 0);
    assertNull(find(app.tree(), "editor"));
    assertEquals("grid", focusedKey(app), "the first focusable element of the editor's area");
  }

  @Test
  void theFirstAutofocusElementTakesTheFocusAtTheStartAndAKeyTakesItAgain() {
    int[] saves = {0};
    App<int[]> app = start(
        new App<>(
            saves,
            n -> col()
                .children(
                    el().key("name").focusable().size(40, 10),
                    el().key("port").focusable().size(40, 10).autofocus(n[0] == 0 ? null : n[0]),
                    el().key("start").focusable().size(40, 10).autofocus(),
                    el().key("save").focusable().size(40, 10).onPress(() -> n[0]++))),
        100,
        100);
    assertEquals("start", focusedKey(app), "the first one that asks, at the start");
    assertEquals(-1, ring(app.scene()), "nothing moved it with a key");
    press(app, 5, 35); // Save: port has an error now
    assertEquals("port", focusedKey(app));
    press(app, 5, 5);
    assertEquals("name", focusedKey(app));
    app.resize(100, 100, 1);
    app.frame();
    assertEquals("name", focusedKey(app), "the same key asks for nothing");
    press(app, 5, 35);
    assertEquals("port", focusedKey(app), "another key asks again");
  }

  @Test
  void focusRefusesWhatCannotTakeIt() {
    App<Lights> app = start(new App<>(new Lights(), Examples::switchAlone), 200, 120);
    El stray = el().key("stray").focusable();
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> app.focus(stray));
    assertTrue(thrown.getMessage().contains("not in the last frame's tree"), thrown.getMessage());
    app.focus(find(app.tree(), "switch"));
    assertNull(app.focused(), "the next frame focuses");
    app.frame();
    assertEquals("switch", focusedKey(app));
    app.focus(null);
    app.frame();
    assertNull(app.focused());
  }

  @Test
  void focusingAContainerGoesToWhatHadTheFocusThereLastElseToItsFirst() {
    int[] page = {0};
    AtomicReference<App<int[]>> self = new AtomicReference<>();
    App<int[]> app = start(
        new App<>(
            page,
            p -> row()
                .onKey(key -> {
                  if (!key.name().equals("F6")) {
                    return false;
                  }
                  p[0]++; // a handler shows other content and focuses into it at once
                  self.get().focus(find(self.get().tree(), "content"));
                  return true;
                })
                .children(
                    col()
                        .key("side")
                        .children(
                            el().key("a").focusable().size(10, 10),
                            el().key("b").focusable().size(10, 10)),
                    col()
                        .key("content")
                        .children(
                            el().key("note").size(10, 10),
                            el().key("c" + p[0]).focusable().size(10, 10),
                            el().key("d" + p[0]).focusable().size(10, 10)))),
        100,
        100);
    self.set(app);
    app.focus(find(app.tree(), "side"));
    app.frame();
    assertEquals("a", focusedKey(app), "the first inside");
    tab(app);
    app.focus(find(app.tree(), "content"));
    app.frame();
    assertEquals("c0", focusedKey(app));
    app.focus(find(app.tree(), "side"));
    app.frame();
    assertEquals("b", focusedKey(app), "where the focus was last inside");
    app.focus(find(app.tree(), "note"));
    app.frame();
    assertEquals("b", focusedKey(app), "nothing inside takes it: the focus stays");

    key(app, "F6", 0);
    assertEquals("c1", focusedKey(app), "the content the handler built");

    page[0]++; // as another window of the same state does
    app.invalidate();
    boolean[] fresh = {false};
    app.after(0, () -> fresh[0] = find(app.tree(), "c2") != null);
    app.frame();
    assertTrue(fresh[0], "input after an invalidate goes to the tree built anew");
  }

  @Test
  void focusingAContainerPrefersItsDefaultFocusElementWhichTakesNoFocusByItself() {
    boolean[] shown = {false};
    App<boolean[]> app = start(
        new App<>(
            shown,
            s -> row()
                .children(
                    el().key("outside").focusable().size(10, 10),
                    col()
                        .key("page")
                        .children(
                            el().key("refresh").focusable().size(10, 10),
                            s[0]
                                ? col()
                                    .key("side")
                                    .children(
                                        el().key("a").focusable().size(10, 10),
                                        el().key("list")
                                            .focusable()
                                            .defaultFocus()
                                            .size(10, 10))
                                : el(),
                            s[0] ? el().key("grid").focusable().defaultFocus().size(10, 10) : el()),
                    el().key("table")
                        .size(40, 40)
                        .rows(
                            5,
                            20,
                            i -> row()
                                .children(
                                    el().key("a" + i).focusable().size(10, 10),
                                    row()
                                        .child(el().key("w" + i)
                                            .focusable()
                                            .defaultFocus()
                                            .size(10, 10)),
                                    el().key("b" + i)
                                        .focusable()
                                        .skipTab()
                                        .defaultFocus()
                                        .size(10, 10))))),
        100,
        100);
    app.focus(find(app.tree(), "outside"));
    app.frame();
    shown[0] = true;
    app.invalidate();
    app.frame();
    assertEquals("outside", focusedKey(app), "it does not take the focus when it appears");
    app.focus(find(app.tree(), "page"));
    app.frame();
    assertEquals("grid", focusedKey(app), "the one nearest the page, not the first");
    app.focus(find(app.tree(), "side"));
    app.frame();
    assertEquals("list", focusedKey(app));
    app.focus(find(app.tree(), "table"));
    app.frame();
    assertEquals("b0", focusedKey(app), "in a virtual list, the row's nearest");
    app.focus(find(app.tree(), "a1"));
    app.frame();
    app.focus(find(app.tree(), "page"));
    app.frame();
    app.focus(find(app.tree(), "table"));
    app.frame();
    assertEquals("a1", focusedKey(app), "where the focus was last wins");
  }

  @Test
  void aDefaultFocusThatAppearsLaterTakesTheFocusUntilItMoves() {
    for (boolean moved : new boolean[] {false, true}) {
      boolean[] loaded = {false};
      App<boolean[]> app = start(
          new App<>(
              loaded,
              s -> row()
                  .children(
                      el().key("outside").focusable().size(10, 10),
                      col()
                          .key("page")
                          .children(
                              el().key("refresh").focusable().size(10, 10),
                              s[0]
                                  ? el().key("grid").focusable().defaultFocus().size(10, 10)
                                  : el()))),
          100,
          100);
      app.focus(find(app.tree(), "page"));
      app.frame();
      assertEquals("refresh", focusedKey(app), "no default focus yet");
      if (moved) {
        key(app, "Tab", Key.SHIFT);
      }
      loaded[0] = true;
      app.invalidate();
      app.frame();
      assertEquals(moved ? "outside" : "grid", focusedKey(app), "unless the focus moved first");
    }
  }

  @Test
  void aDefaultFocusThatAppearsAfterTheFocusFellIntoAContainerTakesIt() {
    int[] state = {0, 0}; // the content shown, and whether the second one's grid is there
    App<int[]> app = start(
        new App<>(
            state,
            s -> row()
                .children(col()
                    .key("page")
                    .children(col()
                        .key("content-" + s[0])
                        .children(
                            el().key("refresh").focusable().size(10, 10),
                            s[0] == 0 || s[1] == 1
                                ? el().key("grid").focusable().defaultFocus().size(10, 10)
                                : el())))),
        100,
        100);
    app.focus(find(app.tree(), "grid"));
    app.frame();
    state[0] = 1;
    app.invalidate();
    app.frame();
    assertEquals("refresh", focusedKey(app), "the grid went: the focus fell to the new refresh");
    for (int i = 0; i < 2; i++) {
      app.focus(find(app.tree(), "page"));
      app.frame();
      assertEquals("refresh", focusedKey(app), "where it fell does not count as where it was");
    }
    state[1] = 1;
    app.invalidate();
    app.frame();
    assertEquals("grid", focusedKey(app), "the grid that appears takes it");
  }

  @Test
  void aFocusWithinLookShowsWhileTheFocusIsOnTheElementOrInsideIt() {
    App<Object> app = start(
        new App<>(
            new Object(),
            s -> col()
                .children(
                    row()
                        .key("frame")
                        .fill(0xFF000000)
                        .whenFocusWithin(e -> e.fill(0xFFFFFFFF))
                        .children(
                            el().key("field").focusable().size(40, 10),
                            el().key("button").focusable().size(10, 10)),
                    el().key("outside").focusable().size(40, 10))),
        100,
        100);
    assertEquals(0xFF000000, find(app.tree(), "frame").fill());
    tab(app);
    assertEquals(0xFFFFFFFF, find(app.tree(), "frame").fill(), "on the field inside");
    tab(app);
    assertEquals(0xFFFFFFFF, find(app.tree(), "frame").fill(), "on the button inside");
    tab(app);
    assertEquals(0xFF000000, find(app.tree(), "frame").fill(), "the focus left it");
    app.focus(null);
    app.frame();
    press(app, 5, 5);
    assertEquals(0xFFFFFFFF, find(app.tree(), "frame").fill(), "a press focuses too");
  }

  @Test
  void onFocusInRunsOnTabAndPointerPressIntoATextField() {
    TextLibraries.assumeLoaded();
    int[] entered = {0};
    App<int[]> app = start(
        new App<>(
            entered,
            n -> col()
                .children(
                    el().key("outside").focusable().size(40, 20),
                    col()
                        .key("pane")
                        .fill(n[0] > 0 ? 0xFFFFFFFF : 0xFF000000)
                        .onFocusIn(() -> n[0]++)
                        .children(
                            El.text("abc").key("field").editable(v -> {}).size(60, 20),
                            el().key("button").focusable().size(40, 20)))),
        100,
        100);
    tab(app);
    assertEquals("outside", focusedKey(app));
    assertEquals(0, entered[0]);

    tab(app);
    assertEquals("field", focusedKey(app));
    assertEquals(1, entered[0], "Tab into the text field runs onFocusIn once");
    assertEquals(0xFFFFFFFF, find(app.tree(), "pane").fill(), "and the view shows what it changed");

    tab(app);
    assertEquals("button", focusedKey(app));
    assertEquals(1, entered[0], "moving within the subtree does not run it again");

    tab(app);
    assertEquals("outside", focusedKey(app));
    press(app, 10, 30); // into the text field
    assertEquals("field", focusedKey(app));
    assertEquals(2, entered[0], "a pointer press into the text field runs it once more");

    app.focus(find(app.tree(), "outside"));
    app.frame();
    app.focus(find(app.tree(), "button"));
    app.frame();
    assertEquals(3, entered[0], "so does App.focus");
  }

  // Keys

  @Test
  void keysGoFromTheFocusedElementUpToTheRootUntilOneTakesThem() {
    List<String> log = new ArrayList<>();
    App<List<String>> app = start(
        new App<>(
            log,
            l -> el().key("root")
                .onKey(k -> l.add("root " + k.name()))
                .child(el().key("parent")
                    .onKey(k -> l.add("parent " + k.name()) && false)
                    .child(el().key("child")
                        .focusable()
                        .size(10, 10)
                        .onKey(k -> l.add("child " + k.name()) && k.name().equals("A"))))),
        100,
        100);
    key(app, "B", 0);
    assertEquals(List.of("root B"), log, "without focus only the root sees the key");

    log.clear();
    app.focus(find(app.tree(), "child"));
    app.frame();
    key(app, "A", 0);
    assertEquals(List.of("child A"), log, "the child took it");

    log.clear();
    key(app, "B", 0);
    assertEquals(List.of("child B", "parent B", "root B"), log);

    log.clear();
    key(app, "Tab", 0);
    assertEquals(List.of("child Tab", "parent Tab", "root Tab"), log);
    assertEquals("child", focusedKey(app), "the root took Tab, so the focus stays");
  }

  @Test
  void spaceAndEnterPressTheFocusedSwitchButRepeatsDoNot() {
    App<Lights> app = start(new App<>(new Lights(), Examples::switchAlone), 200, 120);
    tab(app);
    key(app, "Space", 0);
    assertTrue(app.state().on);
    key(app, "Enter", 0);
    assertFalse(app.state().on);
    app.keyDown(new Key("Enter", 0, true));
    app.frame();
    assertFalse(app.state().on, "a repeat does not press again");
    key(app, "Enter", Key.CONTROL);
    assertFalse(app.state().on, "Control+Enter is left to the app");
  }

  @Test
  void aKeyHandlerOnTheFocusedElementComesBeforeItsPress() {
    List<String> log = new ArrayList<>();
    App<List<String>> app = start(
        new App<>(
            log,
            l -> el().onKey(k -> l.add("root " + k.name()) && false)
                .child(el().focusable()
                    .size(10, 10)
                    .onPress(() -> l.add("press"))
                    .onKey(k -> k.name().equals("Space") && l.add("handled Space")))),
        100,
        100);
    tab(app);
    log.clear();
    key(app, "Space", 0);
    assertEquals(List.of("handled Space"), log);
    log.clear();
    key(app, "Enter", 0);
    assertEquals(List.of("press"), log, "the press takes Enter before the root sees it");
    log.clear();
    app.keyDown(new Key("Enter", 0, true));
    app.frame();
    assertEquals(List.of(), log, "and its repeats");
  }

  @Test
  void textGoesToTheFocusedElementOnly() {
    List<String> log = new ArrayList<>();
    App<List<String>> app = start(
        new App<>(
            log,
            l -> el().onText(t -> l.add("root " + t))
                .child(el().key("field").focusable().size(10, 10).onText(t -> l.add(t)))),
        100,
        100);
    app.textInput("lost");
    app.frame();
    assertEquals(List.of(), log, "without focus text is dropped");
    tab(app);
    app.textInput("ü€");
    app.frame();
    assertEquals(List.of("ü€"), log);
  }

  // The ring

  /** The instance that draws the ring, the last one with a border and no fill; -1 if none. */
  private static int ring(Scene scene) {
    for (int i = scene.count() - 1; i >= 0; i--) {
      if (scene.get(i, Scene.BORDER_WIDTH) == Scene.RING && scene.get(i, Scene.FILL + 12) == 0) {
        return i;
      }
    }
    return -1;
  }

  @Test
  void tabDrawsARingAroundTheSwitchAndAPressDoesNot() {
    App<Lights> app = start(new App<>(new Lights(), Examples::switchAlone), 200, 120);
    int plain = app.scene().count();
    tab(app);
    Scene scene = app.scene();
    assertEquals(plain + 1, scene.count());
    int ring = ring(scene);
    assertEquals(scene.count() - 1, ring, "after the switch and its knob");
    assertEquals(79, scene.get(ring, Scene.X)); // the switch is 38x22 at 81,49
    assertEquals(47, scene.get(ring, Scene.Y));
    assertEquals(42, scene.get(ring, Scene.WIDTH));
    assertEquals(26, scene.get(ring, Scene.HEIGHT));
    assertEquals(13, scene.get(ring, Scene.RADIUS), "the switch's radius and the ring's width");
    assertEquals(0, scene.clipOf(ring));

    press(app, 90, 60);
    assertEquals("switch", focusedKey(app));
    assertEquals(plain, app.scene().count(), "focused by the pointer: no ring");

    app.focusRing(0xFFFF0000);
    tab(app);
    assertEquals(1, app.scene().get(ring(app.scene()), Scene.BORDER), "red, linear");
    app.focusRing(0);
    app.frame();
    assertEquals(plain, app.scene().count(), "0 draws none");
  }

  @Test
  void anElementsOwnRingColorWinsOverTheAppsAndZeroDrawsNone() {
    App<Object> app = start(
        new App<>(
            new Object(),
            s -> row()
                .wFull()
                .hFull()
                .center()
                .gap(20)
                .children(
                    el().key("own").size(30, 20).focusable().focusRing(0xFFFF0000),
                    el().key("app").size(30, 20).focusable(),
                    el().key("none").size(30, 20).focusable().focusRing(0))),
        200,
        120);
    int plain = app.scene().count();
    app.focusRing(0xFF00FF00);
    tab(app);
    assertEquals("own", focusedKey(app));
    Scene scene = app.scene();
    assertEquals(1, scene.get(ring(scene), Scene.BORDER), "red, the element's own");
    assertEquals(0, scene.get(ring(scene), Scene.BORDER + 4));
    tab(app);
    assertEquals("app", focusedKey(app));
    scene = app.scene();
    assertEquals(0, scene.get(ring(scene), Scene.BORDER));
    assertEquals(1, scene.get(ring(scene), Scene.BORDER + 4), "green, the app's");
    tab(app);
    assertEquals("none", focusedKey(app));
    assertEquals(plain, app.scene().count(), "0 draws none");
  }

  @Test
  void focusingARowOutsideTheListScrollsItIntoViewAndTheRingLiesInsideTheRowAtTheListsEdge() {
    App<Object> app = start(
        new App<>(new Object(), s -> {
          El list = col().key("list").scroll().size(100, 50).fill(0xFF000000);
          for (int i = 0; i < 10; i++) {
            list.child(el().key("row" + i).focusable().h(20).fill(0xFFFFFFFF));
          }
          return col().padding(10).child(list);
        }),
        120,
        70);
    tab(app);
    tab(app);
    assertEquals(0, find(app.tree(), "list").scrollY(), "rows 0 and 1 are in view");
    tab(app);
    assertEquals(10, find(app.tree(), "list").scrollY(), "just far enough for row 2");

    app.focus(find(app.tree(), "row9"));
    app.frame();
    assertEquals(150, find(app.tree(), "list").scrollY());
    key(app, "Tab", 0);
    assertEquals("row0", focusedKey(app));
    assertEquals(0, find(app.tree(), "list").scrollY(), "back to the top");

    Scene scene = app.scene();
    int ring = ring(scene);
    int row = ring - 1;
    // Outside, the list would cut off its top: it lies just inside the row instead, whole.
    assertEquals(10, scene.get(ring, Scene.Y), "row 0's top, where the list begins");
    assertEquals(20, scene.get(ring, Scene.HEIGHT), "the row's height");
    assertEquals(10, scene.get(ring, Scene.X));
    assertEquals(100, scene.get(ring, Scene.WIDTH));
    assertNotEquals(0, scene.clipOf(ring));
    assertEquals(scene.clipOf(row), scene.clipOf(ring), "clipped like the row");
    assertEquals(10, scene.clipGet(scene.clipOf(ring), Scene.Y), "by the list");
  }

  @Test
  void pageKeysScrollTheAreaAroundTheFocusOrInAModalOverlayItsFirst() {
    App<Object> app = start(
        new App<>(new Object(), s -> {
          El list = col().key("list").scroll().size(100, 50);
          for (int i = 0; i < 10; i++) {
            list.child(el().key("row" + i).focusable().h(20));
          }
          El area = col().key("area").scroll().minSize(0, 0).child(el().h(500));
          return col()
              .padding(10)
              .child(list)
              .overlay(col()
                  .key("modal")
                  .centerInWindow()
                  .modal()
                  .margin(10)
                  .children(area, el().key("button").focusable().size(20, 10).shrink(0)));
        }),
        200,
        120);
    El modal = app.tree().overlays().getFirst();
    assertEquals(100, modal.height(), 0.01, "the window less its margins");
    assertEquals("button", focusedKey(app));
    key(app, "PageDown", 0);
    El area = find(app.tree().overlays().getFirst(), "area");
    assertEquals(90, area.scrollY(), 0.01, "a page, its height, though the focus is outside it");
    key(app, "PageUp", 0);
    assertEquals(0, find(app.tree().overlays().getFirst(), "area").scrollY(), 0.01);
    assertEquals(0, find(app.tree(), "list").scrollY(), "the list under it stays");
  }

  @Test
  void revealingFocusFromAFractionalOffsetLeavesRoomForTheOutsideRing() {
    ScrollState scroll = new ScrollState();
    scroll.scrollTo(0, 0.02);
    App<ScrollState> app = new App<>(
        scroll,
        state -> col()
            .padding(10)
            .child(col()
                .size(100, 121)
                .padding(4)
                .scroll()
                .scrollState(state)
                .children(
                    el().h(106).shrink(0),
                    el().key("button").focusable().focusRingGap(2).size(40, 20).shrink(0),
                    el().h(100).shrink(0))));
    app.resize(140, 160, 1.5f);
    app.frame();
    app.focus(find(app.tree(), "button"));
    app.frame();
    Scene scene = app.scene();
    assertEquals(48, scene.get(ring(scene), Scene.WIDTH));
  }

  @Test
  void aRingThatFitsStaysOutsideAndOneAtTheWindowsEdgeLiesInside() {
    App<Object> app = start(
        new App<>(
            new Object(),
            s -> col()
                .wFull()
                .hFull()
                .children(
                    el().key("edge").focusable().size(40, 10),
                    el().key("inside").focusable().size(40, 10).margin(10))),
        100,
        100);
    tab(app);
    Scene scene = app.scene();
    int ring = ring(scene);
    assertEquals(0, scene.get(ring, Scene.X), "at the window's top left: inside the element");
    assertEquals(0, scene.get(ring, Scene.Y));
    assertEquals(40, scene.get(ring, Scene.WIDTH));
    tab(app);
    scene = app.scene();
    ring = ring(scene);
    assertEquals(8, scene.get(ring, Scene.X), "with room around it: 2 pixels outside, as ever");
    assertEquals(18, scene.get(ring, Scene.Y));
    assertEquals(44, scene.get(ring, Scene.WIDTH));
    assertEquals(14, scene.get(ring, Scene.HEIGHT));
  }

  @Test
  void aPressDoesNotScrollAndTheWheelKeepsTheFocus() {
    App<Object> app = start(
        new App<>(new Object(), s -> {
          El list = col().key("list").scroll().size(100, 50);
          for (int i = 0; i < 10; i++) {
            list.child(el().key("row" + i).focusable().h(20));
          }
          return list;
        }),
        100,
        50);
    press(app, 50, 45); // on row 2, half of which is in view
    assertEquals("row2", focusedKey(app));
    assertEquals(0, find(app.tree(), "list").scrollY());
    app.wheel(50, 25, 0, 150);
    app.frame();
    assertEquals("row2", focusedKey(app), "scrolled out of view, still focused");
    app.resize(100, 50, 1);
    app.frame();
    assertSame(find(app.tree(), "row2"), app.focused(), "also after a rebuild");
  }
}
