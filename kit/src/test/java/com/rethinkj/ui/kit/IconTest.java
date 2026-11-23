package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Button.Variant.GHOST;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Icon.icon;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.Workbench.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Headless;
import com.rethinkj.ui.Image;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Icon.Variant;
import com.rethinkj.ui.render.Pixels;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.GlyphRun;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Icons: their box and place beside text, the drawing picked for a size, and a sheet of every
 * symbol with its name, at the theme's size and at 20, compared with the stored images.
 */
class IconTest {

  private static final Theme T = Theme.DARK;
  private static final int CELL = 200;
  private static final int COLUMNS = 4;
  private static final int ROW = 28;

  @Test
  void everySymbolIsAGlyphOfTheFontAsWideAsItsSize() {
    TextLibraries.assumeLoaded();
    for (Symbol symbol : Symbol.values()) {
      for (int size : new int[] {12, 16, 20, 24}) {
        GlyphRun run = Font.icons().shape(Character.toString(symbol.codePoint(size)), size, 1);
        assertEquals(1, run.count(), symbol + " at " + size);
        assertNotEquals(0, run.glyph(0), symbol + " at " + size);
        assertEquals(size, run.width(), symbol + " at " + size);
      }
    }
  }

  @Test
  void aSizeGetsItsOwnDrawingElseTheNearestTheSmallerOfTwo() {
    int[] sizes = {12, 16, 20, 24, 28, 32, 48};
    for (int i = 1; i < sizes.length; i++) {
      assertNotEquals(Symbol.ADD.codePoint(sizes[i - 1]), Symbol.ADD.codePoint(sizes[i]));
    }
    assertEquals(Symbol.ADD.codePoint(16), Symbol.ADD.codePoint(18));
    assertEquals(Symbol.ADD.codePoint(20), Symbol.ADD.codePoint(18.5f));
    assertEquals(Symbol.ADD.codePoint(48), Symbol.ADD.codePoint(100));
    // Drawn for 20 and up only.
    assertEquals(Symbol.MORE_HORIZONTAL.codePoint(20), Symbol.MORE_HORIZONTAL.codePoint(12));
  }

  /**
   * An icon is as wide and high as its size at every scale, also where the text before it ends
   * between two pixels, and centered on the line of the text beside it. Core's tests check that
   * the scene draws it on whole pixels.
   */
  @ParameterizedTest
  @CsvSource({"1", "1.25", "1.5", "2"})
  void anIconIsItsSizeOnWholePixelsAndLinesUpWithTheTextBesideIt(float scale) {
    TextLibraries.assumeLoaded();
    El before = label("orders_2024");
    El small = icon(Symbol.TABLE);
    El large = icon(Symbol.TABLE, Variant.NORMAL, 20);
    El after = label("orders");
    El line =
        row().gap(T.space().s()).align(Style.Align.CENTER).children(before, small, large, after);
    Headless.render(col().padding(3.25f).child(line), 300, 60, scale, TextRendering.LINUX);
    assertEquals(16, small.width());
    assertEquals(16, small.height());
    assertEquals(20, large.width());
    assertEquals(20, large.height());
    // Within the half pixel by which layout may round the text's box.
    assertEquals(small.y() + 8, after.y() + after.height() / 2, 0.5f, "centered");
  }

  @ParameterizedTest
  @CsvSource({"dark, 2", "light, 2"})
  void looksAsStored(String name, int scale) {
    TextLibraries.assumeLoaded();
    Theme t = name.equals("dark") ? Theme.DARK : Theme.LIGHT;
    int rows = 1 + (Symbol.values().length + COLUMNS - 1) / COLUMNS;
    Headless.Frame frame = Headless.render(
        Theme.CURRENT.with(t, () -> sheet(t)),
        CELL * COLUMNS,
        rows * ROW,
        scale,
        TextRendering.LINUX);
    Golden.assertMatches("icons-" + name + "-" + scale + "x", frame.pixels(), frame.tree());
  }

  /**
   * The color variants of one symbol, then every symbol at the theme's size and at 20 with its
   * name, row by row.
   */
  private static El sheet(Theme t) {
    El variants = cell(t).w(CELL * COLUMNS).child(label(Label.Variant.MUTED, "variants"));
    for (Variant variant : Variant.values()) {
      variants.children(
          icon(Symbol.WARNING, variant), label(variant.name().toLowerCase(Locale.ROOT)));
    }
    El sheet = row().wrap().wFull().hFull().fill(t.surface().window()).child(variants);
    for (Symbol symbol : Symbol.values()) {
      sheet.child(cell(t)
          .children(
              icon(symbol),
              icon(symbol, Variant.NORMAL, 20),
              label(symbol.name().toLowerCase(Locale.ROOT))));
    }
    return sheet;
  }

  /** A glyph of a text font fits the icon's square and lies in its middle. */
  @ParameterizedTest
  @CsvSource({"9632", "87"}) // a black square, W
  void aGlyphOfAnotherFontDrawsInsideTheSquareInItsMiddle(int codePoint) {
    TextLibraries.assumeLoaded();
    El glyph = Theme.CURRENT.with(
        T, () -> icon(Icon.glyph(Font.sans(), codePoint, "mark"), Variant.NORMAL, 16));
    Headless.Frame frame = Headless.render(
        col().padding(12).fill(0xFF000000).child(glyph), 40, 40, 2, TextRendering.LINUX);
    int left = 80, top = 80, right = -1, bottom = -1;
    for (int y = 0; y < 80; y++) {
      for (int x = 0; x < 80; x++) {
        if ((frame.pixels().argb(x, y) & 0xFFFFFF) != 0) {
          left = Math.min(left, x);
          top = Math.min(top, y);
          right = Math.max(right, x);
          bottom = Math.max(bottom, y);
        }
      }
    }
    // The square is 24 to 56 in physical pixels.
    assertTrue(left >= 24 && top >= 24 && right < 56 && bottom < 56, left + "," + top);
    assertEquals(40, (left + right + 1) / 2f, 2.5f, "centered across");
    if (codePoint == 9632) {
      assertEquals(40, (top + bottom + 1) / 2f, 2.5f, "centered down");
    }
  }

  @Test
  void glyphAndPictureWorkAcrossButtonsToolbarsAndMenus(@TempDir Path out) throws IOException {
    TextLibraries.assumeLoaded();
    GlyphRun square = Font.sans().shape("\u25A0", 16, 1);
    assertEquals(1, square.count());
    assertNotEquals(0, square.glyph(0), "Font.sans() has U+25A0 BLACK SQUARE");

    Icon glyph = Icon.glyph(Font.sans(), 0x25A0, "stop_square");
    Icon picture = Icon.image(samplePicture(), "brand_mark");

    class State {
      boolean busy;
      int presses;
      final Toolbar.State toolbar = new Toolbar.State();
      final Menu.State menu = new Menu.State();
    }
    App<State> app = new App<>(
        new State(),
        s -> col()
            .padding(8)
            .gap(8)
            .child(row()
                .gap(4)
                .child(Button.icon(
                        GHOST,
                        glyph,
                        "Stop",
                        () -> s.presses++,
                        Button.options().tone(Button.Tone.DANGER))
                    .key("glyph-on"))
                .child(Button.icon(
                        GHOST, glyph, "Stop off", null, Button.options().tone(Button.Tone.DANGER))
                    .key("glyph-off"))
                .child(Button.icon(GHOST, picture, "Brand", () -> s.presses++).key("pic-on"))
                .child(Button.icon(GHOST, picture, "Brand off", null).key("pic-off"))
                .child(Button.toggleIcon(picture, "Pin", true, on -> {}).key("pic-toggle")))
            .child(row()
                .gap(4)
                .child(Button.button(
                        SECONDARY,
                        "Run",
                        () -> s.presses++,
                        Button.options()
                            .icon(glyph)
                            .state(s.busy ? Button.State.BUSY : Button.State.READY)
                            .busyText("Running…"))
                    .key("busy-glyph"))
                .child(Button.button(
                        SECONDARY,
                        "Sync",
                        () -> s.presses++,
                        Button.options()
                            .icon(picture)
                            .state(s.busy ? Button.State.BUSY : Button.State.READY)
                            .busyText("Syncing…"))
                    .key("busy-pic")))
            // Narrow enough that the second button overflows into the More menu.
            .child(row()
                .w(48)
                .child(Toolbar.toolbar(
                        s.toolbar,
                        "Tools",
                        List.of(
                            Toolbar.button(glyph, "Stop", () -> s.presses++),
                            Toolbar.button(picture, "Brand", () -> s.presses++)))
                    .key("bar")))
            .child(Menu.dropdown(
                Button.button(SECONDARY, "Actions", s.menu::toggle).key("open-menu"),
                s.menu,
                List.of(
                    Menu.item("Stop now", () -> s.presses++).icon(glyph),
                    Menu.item("Stop disabled", null).icon(glyph),
                    Menu.item("Brand now", () -> s.presses++).icon(picture),
                    Menu.item("Brand disabled", null).icon(picture)))));
    app.textRendering(TextRendering.LINUX);

    // First frame learns toolbar item widths; second frame moves the overflowing item to More.
    Script.run("size 320 260\nframe\nframe", app, out);
    String dump = app.tree().dump();
    assertTrue(dump.contains("text label=\"stop_square\""), dump);
    assertTrue(dump.contains("image role=img label=\"brand_mark\""), dump);

    El glyphOn = find(app, "glyph-on").children().getFirst();
    El glyphOff = find(app, "glyph-off").children().getFirst();
    assertEquals(T.danger().text(), glyphOn.text().color());
    assertEquals(T.text().disabled(), glyphOff.text().color());
    assertEquals(T.size().icon(), glyphOn.width());
    assertEquals(T.size().icon(), glyphOn.height());

    El picOn = find(app, "pic-on").children().getFirst();
    El picOff = find(app, "pic-off").children().getFirst();
    assertNotNull(picOn.image());
    assertEquals(T.size().icon(), picOn.width());
    assertEquals(T.size().icon(), picOn.height());
    assertEquals(1f, picOn.opacity());
    assertEquals(0.4f, picOff.opacity());

    // Busy buttons rebuild their icon inside the hidden sizing row without sharing an element.
    app.state().busy = true;
    app.invalidate();
    Script.run("size 320 260\nframe", app, out);
    assertTrue(find(app, "busy-glyph").dump().contains("text label=\"stop_square\""));
    assertTrue(find(app, "busy-pic").dump().contains("image role=img label=\"brand_mark\""));

    // Toolbar overflow builds the overflowing item's icon in its More menu.
    Script.run("size 320 260\nadvance 1\nframe\npress #more\nframe\nadvance 1\nframe", app, out);
    String moreDump = app.tree().dump();
    assertTrue(moreDump.contains("role=menuitem label=\"Brand\""), moreDump);
    assertTrue(moreDump.contains("image role=img label=\"brand_mark\""), moreDump);
    Script.run("size 320 260\npress role=menuitem label=\"Brand\"\nframe", app, out);
    assertEquals(1, app.state().presses);

    // Menu items with glyph and picture, enabled and disabled.
    Script.run("size 320 260\npress #open-menu\nframe\nadvance 1\nframe", app, out);
    El menuEl = app.tree().shownOverlays().getFirst();
    String menuDump = menuEl.dump();
    assertTrue(menuDump.contains("text label=\"stop_square\""), menuDump);
    assertTrue(menuDump.contains("image role=img label=\"brand_mark\""), menuDump);
    El glyphItemOn = iconIn(menuEl.children().get(0));
    El glyphItemOff = iconIn(menuEl.children().get(1));
    El picItemOn = iconIn(menuEl.children().get(2));
    El picItemOff = iconIn(menuEl.children().get(3));
    assertEquals(T.text().primary(), glyphItemOn.text().color());
    assertEquals(T.text().disabled(), glyphItemOff.text().color());
    assertEquals(1f, picItemOn.opacity());
    assertEquals(0.4f, picItemOff.opacity());
  }

  @Test
  void aComboBoxThatIsOffShowsTheIconOfItsChoiceOff() {
    TextLibraries.assumeLoaded();
    Icon picture = Icon.image(samplePicture(), "brand_mark");
    El combo = Theme.CURRENT.with(
        T,
        () -> ComboBox.items(
            List.of(ComboBox.item("Brand").icon(picture)), 0, new ComboBox.State(), null));
    assertEquals(0.4f, imageIn(combo).opacity());
    El symbol = Theme.CURRENT.with(
        T,
        () -> ComboBox.items(
            List.of(ComboBox.item("Table").icon(Symbol.TABLE)), 0, new ComboBox.State(), null));
    assertTrue(symbol.dump().contains("label=\"table\""), symbol.dump());
    assertEquals(T.text().disabled(), labelled(symbol, "table").text().color());
  }

  /** The first element in the tree under {@code el} with this label, or null. */
  private static El labelled(El el, String label) {
    if (label.equals(el.label())) {
      return el;
    }
    for (El child : el.children()) {
      El found = labelled(child, label);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** The first image element in the tree under {@code el}, or null. */
  private static El imageIn(El el) {
    if (el.image() != null) {
      return el;
    }
    for (El child : el.children()) {
      El found = imageIn(child);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** Finds the leading icon element inside a menu item. */
  private static El iconIn(El menuItem) {
    return menuItem.children().getFirst().children().getFirst().children().getFirst();
  }

  /** A 32x32 colored diamond badge at scale 2 (16x16 logical pixels). */
  private static Image samplePicture() {
    int size = 32;
    byte[] rgba = new byte[size * size * 4];
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        int dx = Math.abs(x * 2 + 1 - size);
        int dy = Math.abs(y * 2 + 1 - size);
        if (dx + dy <= size - 4) {
          int at = (y * size + x) * 4;
          boolean top = y < size / 2;
          rgba[at] = (byte) (top ? 0x38 : 0x25);
          rgba[at + 1] = (byte) (top ? 0xBD : 0x63);
          rgba[at + 2] = (byte) (top ? 0xF8 : 0xEB);
          rgba[at + 3] = (byte) 0xFF;
        }
      }
    }
    return Image.of(new Pixels(size, size, rgba), 2);
  }

  private static El cell(Theme t) {
    return row()
        .w(CELL)
        .h(ROW)
        .padding(0, t.space().m())
        .gap(t.space().m())
        .align(Style.Align.CENTER);
  }
}
