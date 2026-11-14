package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.render.Scene;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A dialog higher than the window: its head and its buttons stay in view while its content
 * scrolls between them, with the themed bar, and the focus rings at the content's edges show
 * whole; a dialog that fits has no bar.
 */
class DialogScrollTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private static final class Saving {
    final List<String> log = new ArrayList<>();
  }

  /** The smallest case: a content 800 high, a Save button that is the default. */
  private static El tall(Theme t, Saving s) {
    El save = Button.button(t, Button.Variant.PRIMARY, "Save", () -> s.log.add("save"))
        .key("save");
    return El.col()
        .wFull()
        .hFull()
        .overlay(Dialog.dialog(
            t,
            "x",
            El.col()
                .children(
                    TextField.textField(t, "", "Top", v -> {}).key("top"),
                    El.col().h(800),
                    TextField.textField(t, "", "Bottom", v -> {}).key("bottom")),
            List.of(save),
            () -> s.log.add("dismiss"),
            Dialog.options().defaultButton(save)));
  }

  private static float[] rect(String dump, String key) {
    return StructureTest.rect(StructureTest.line(dump, "#" + key + " "));
  }

  /** The vertical scroll offset in the dump's line of the dialog's content. */
  private static float scrollY(String dump) {
    String line = StructureTest.line(dump, "#dialog-content ");
    var m = java.util.regex.Pattern.compile(" scroll (-?[\\d.]+),(-?[\\d.]+)").matcher(line);
    return m.find() ? Float.parseFloat(m.group(2)) : 0;
  }

  @ParameterizedTest(name = "{0} high")
  @CsvSource({"600", "300"})
  void theHeadAndTheButtonsStayInViewWhileTheContentScrolls(int height) throws IOException {
    Theme t = Theme.DARK;
    Saving s = new Saving();
    App<Saving> app = new App<>(s, v -> tall(t, v));
    Script.run("size 480 " + height + "\nframe\nadvance 0.25\nframe\n", app, out);
    String dump = app.tree().dump();
    float[] dialog = rect(dump, "dialog");
    float[] save = rect(dump, "save");
    float margin = t.space().xl();
    assertEquals(margin, dialog[1], 0.5, "off the window's top edge: " + dump);
    assertEquals(height - margin, dialog[1] + dialog[3], 0.5, "and off its bottom: " + dump);
    assertTrue(save[1] + save[3] <= height - margin, "Save in the window: " + dump);
    assertTrue(StructureTest.line(dump, "#dialog-content ").contains(" thumb-y "), dump);
    assertTrue(StructureTest.line(dump, "role=heading").contains("label=\"x\""), dump);
    assertEquals("top", app.focused().key(), "it opens in its content");

    Script.run("wheel #dialog-content 0 100\nframe\n", app, out);
    dump = app.tree().dump();
    assertEquals(100, scrollY(dump), 0.01, dump);
    assertEquals(save[1], rect(dump, "save")[1], 0.01, "the buttons stay where they were");

    Script.run("key Tab\nframe\n", app, out);
    dump = app.tree().dump();
    float[] bottom = rect(dump, "bottom");
    float[] area = rect(dump, "dialog-content");
    assertEquals("bottom", app.focused().key());
    assertTrue(bottom[1] + bottom[3] <= area[1] + area[3] && bottom[1] >= area[1], dump);

    Script.run("key Enter\nframe\n", app, out);
    assertEquals(List.of("save"), s.log, "Enter in a field still presses the default button");

    float before = scrollY(dump);
    Script.run("focus #save\nkey PageUp\nframe\n", app, out);
    dump = app.tree().dump();
    assertEquals(Math.max(0, before - area[3]), scrollY(dump), 0.01, "a page up: " + dump);
    Script.run("key PageUp\nkey PageUp\nkey PageUp\nframe\n", app, out);
    assertEquals(0, scrollY(app.tree().dump()), 0.01);
    Script.run("key PageDown\nframe\n", app, out);
    assertTrue(scrollY(app.tree().dump()) >= Math.min(before, area[3]), "and Page Down down");
    assertEquals(List.of("save"), s.log, "nothing pressed meanwhile");
  }

  @Test
  void aDialogThatFitsHasNoBarAndItsContentKeepsItsPlace() throws IOException {
    Theme t = Theme.DARK;
    App<Object> app = new App<>(
        new Object(),
        s -> El.col()
            .wFull()
            .hFull()
            .overlay(
                Dialog.dialog(t, "Note", El.el().key("body").size(120, 40), List.of(), () -> {})));
    Script.run("size 360 240\nframe\nadvance 0.25\nframe\nwheel #body 0 50\nframe\n", app, out);
    String dump = app.tree().dump();
    String area = StructureTest.line(dump, "#dialog-content ");
    assertFalse(area.contains("thumb"), area);
    assertFalse(area.contains(" scroll "), "nothing to scroll: " + area);
    float[] dialog = rect(dump, "dialog");
    float[] body = rect(dump, "body");
    float inset = t.space().xl() + t.size().stroke();
    assertEquals(dialog[0] + inset, body[0], 0.01, dump);
    assertEquals(120 + 2 * inset, dialog[2], 0.01, "as wide as its content: " + dump);
  }

  @Test
  void aDialogWiderThanTheWindowScrollsSideways() throws IOException {
    Theme t = Theme.DARK;
    App<Object> app = new App<>(
        new Object(),
        s -> El.col()
            .wFull()
            .hFull()
            .overlay(
                Dialog.dialog(t, "Wide", El.el().key("body").size(900, 40), List.of(), () -> {})));
    Script.run("size 480 300\nframe\nadvance 0.25\nframe\n", app, out);
    String dump = app.tree().dump();
    float[] dialog = rect(dump, "dialog");
    assertEquals(t.space().xl(), dialog[0], 0.5, dump);
    assertEquals(480 - 2 * t.space().xl(), dialog[2], 0.5, dump);
    assertTrue(StructureTest.line(dump, "#dialog-content ").contains(" thumb-x "), dump);
    Script.run("wheel #dialog-content 100 0\nframe\n", app, out);
    assertTrue(StructureTest.line(app.tree().dump(), "#dialog-content ").contains(" scroll 100,"));
  }

  /** A dialog of twelve fields at the left of its content, in a window too low for them. */
  private static El fields(Theme t) {
    El form = El.col().gap(t.space().m());
    for (int i = 0; i < 12; i++) {
      form.child(TextField.textField(t, "", "Field " + i, v -> {}).key("f" + i));
    }
    El ok = Button.button(t, Button.Variant.PRIMARY, "OK", () -> {}).key("ok");
    return El.col()
        .wFull()
        .hFull()
        .overlay(Dialog.dialog(
            t,
            "Columns",
            form,
            List.of(Button.button(t, Button.Variant.SECONDARY, "Cancel", () -> {}), ok),
            () -> {},
            Dialog.options().defaultButton(ok)));
  }

  /**
   * The physical pixels just outside a focused field's border box at its left, top or bottom,
   * where its ring lies: the ring's colour, not the surface's, so the area's clip cut nothing.
   */
  private static void assertRing(
      Pixels image, Theme t, float[] field, double scale, String where, String name) {
    int x = (int) Math.round(field[0] * scale) - 2;
    int y = switch (where) {
      case "top" -> (int) Math.round(field[1] * scale) - 2;
      case "bottom" -> (int) Math.round((field[1] + field[3]) * scale) + 1;
      default -> (int) Math.round((field[1] + field[3] / 2) * scale);
    };
    if (!where.equals("left")) {
      x = (int) Math.round((field[0] + field[2] / 2) * scale);
    }
    int ring = t.accent().edge();
    int i = (y * image.width() + x) * 4;
    byte[] p = image.rgba();
    int r = p[i] & 0xFF;
    int g = p[i + 1] & 0xFF;
    int b = p[i + 2] & 0xFF;
    int far = Math.max(
        Math.abs(r - Colors.red(ring)),
        Math.max(Math.abs(g - Colors.green(ring)), Math.abs(b - Colors.blue(ring))));
    assertTrue(
        far <= 8,
        name + ": the ring's pixel at the " + where + " edge, " + x + "," + y + ", was "
            + Integer.toHexString(r << 16 | g << 8 | b) + ", not "
            + Integer.toHexString(ring & 0xFFFFFF));
  }

  @ParameterizedTest(name = "{0} at {1}x")
  @CsvSource({"dark, 1", "dark, 1.5", "dark, 2", "light, 1", "light, 2"})
  void ringsAtTheContentsEdgesShowWhole(String theme, double scale) throws IOException {
    Theme t = theme.equals("dark") ? Theme.DARK : Theme.LIGHT;
    App<Object> app = new App<>(new Object(), s -> fields(t));
    String name = "dialog-scroll-" + theme + "-" + (scale == 1.5 ? "1_5" : (int) scale) + "x";
    boolean stored = scale != 1.5;
    Script.run(
        "size 360 300 scale " + scale + "\nframe\nadvance 0.25\nframe\nsnap top\n"
            + "focus #f0\nframe\nsnap left\n" // at the top left of the content
            + "key Tab\n".repeat(11) // to the last field, scrolled into view at the bottom
            + "frame\nsnap bottom\n"
            + "key Tab shift\n".repeat(7) // back to the fifth, scrolled into view at the top
            + "frame\nsnap scrolled\n",
        app,
        out);
    for (String snap : List.of("top", "left", "bottom", "scrolled")) {
      Pixels image = Png.decode(Files.readAllBytes(out.resolve(snap + ".png")));
      String tree = Files.readString(out.resolve(snap + ".tree.txt"));
      switch (snap) {
        case "left" -> {
          float[] f = rect(tree, "f0");
          assertRing(image, t, f, scale, "left", name);
          assertRing(image, t, f, scale, "top", name);
          assertEquals(0, scrollY(tree), 0.01, tree);
        }
        case "bottom" -> {
          assertTrue(scrollY(tree) > 0, tree);
          assertRing(image, t, rect(tree, "f11"), scale, "bottom", name);
        }
        case "scrolled" -> {
          assertTrue(scrollY(tree) > 0, "still scrolled: " + tree);
          float[] f = rect(tree, "f4");
          // Just far enough for its ring, which lies in the area's padding.
          assertEquals(rect(tree, "dialog-content")[1] + Scene.RING, f[1], 0.5, tree);
          assertRing(image, t, f, scale, "top", name);
          assertRing(image, t, f, scale, "left", name);
        }
        default -> assertTrue(tree.contains(" thumb-y "), tree);
      }
      if (stored && !snap.equals("left")) {
        Golden.assertMatches(name + "-" + snap, image, tree);
      }
    }
  }

  @Test
  void aPopoversFormScrollsItsContentToo() throws IOException {
    Theme t = Theme.DARK;
    Popover.State state = new Popover.State();
    state.open();
    App<Popover.State> app = new App<>(
        state,
        st -> El.col()
            .wFull()
            .hFull()
            .child(Popover.form(
                t,
                Button.button(t, Button.Variant.SECONDARY, "Filter", st::toggle).key("anchor"),
                st,
                "Filter",
                El.col().h(800).key("tall"),
                List.of(
                    Button.button(t, Button.Variant.PRIMARY, "Apply", () -> {}).key("apply")))));
    Script.run("size 480 300\nframe\nadvance 0.25\nframe\n", app, out);
    String dump = app.tree().dump();
    float[] apply = rect(dump, "apply");
    assertTrue(apply[1] + apply[3] <= 300, "Apply in the window: " + dump);
    assertTrue(dump.contains(" thumb-y "), dump);
  }
}
