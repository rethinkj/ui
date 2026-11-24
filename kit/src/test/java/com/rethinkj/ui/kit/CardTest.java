package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.StructureTest.line;
import static com.rethinkj.ui.kit.StructureTest.rect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A card's footer and toolbar layout. */
class CardTest {

  private static final String START = "\"About 1,000,000 rows, as the server estimates\"";
  private static final String READ = "\"Read 200 rows in 28 ms\"";
  private static final String ROWS = "\"Rows 1–7\"";

  private final Path out = Path.of("target/card-test");
  private El start;
  private El read;
  private El rows;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private El view(Theme theme, Supplier<El> startFactory) {
    Button.Options small = Button.options().size(Button.Size.SMALL);
    start = startFactory.get();
    read = Label.label(Label.Variant.MUTED, "Read 200 rows in 28 ms");
    rows = Label.label(Label.Variant.MUTED, "Rows 1–7");
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .child(Card.flush(
                el().grow(1),
                Card.footer(
                    start,
                    read,
                    rows,
                    row()
                        .gap(theme.space().xs())
                        .child(Button.icon(
                            Button.Variant.GHOST, Symbol.ARROW_UP, "First page", () -> {}, small))
                        .child(Button.icon(
                            Button.Variant.GHOST,
                            Symbol.CHEVRON_UP,
                            "Previous page",
                            () -> {},
                            small))
                        .child(Button.icon(
                            Button.Variant.GHOST,
                            Symbol.CHEVRON_DOWN,
                            "Next page",
                            () -> {},
                            small))
                        .child(Button.icon(
                            Button.Variant.GHOST,
                            Symbol.ARROW_DOWN,
                            "Last page",
                            () -> {},
                            small))))
            .grow(1)
            .key("card"));
  }

  /** Runs the view in a window this wide and returns the tree, after a snap if one is named. */
  private String run(int width, String snap) throws IOException {
    return run(
        width,
        snap,
        () -> Label.label(Label.Variant.MUTED, "About 1,000,000 rows, as the server estimates")
            .ellipsis());
  }

  private String run(int width, String snap, Supplier<El> startFactory) throws IOException {
    App<Object> app = new App<>(new Object(), s -> view(Theme.DARK, startFactory));
    Script.run("size " + width + " 120\nframe" + (snap == null ? "" : "\nsnap " + snap), app, out);
    return app.tree().dump();
  }

  @Test
  void aNarrowCardShortensTheStartFirstThenTheTextsAtTheEndAndKeepsItsButtonsInside()
      throws IOException {
    String wide = run(1000, null);
    assertFalse(read.text().shortened() || rows.text().shortened(), wide);

    // The start gives way first, and the texts at the end keep their width while it is there.
    String middle = run(480, null);
    float startWidth = rect(line(middle, START))[2];
    assertTrue(startWidth > 0 && startWidth < rect(line(wide, START))[2], middle);
    assertEquals(rect(line(wide, READ))[2], rect(line(middle, READ))[2], middle);
    assertEquals(rect(line(wide, ROWS))[2], rect(line(middle, ROWS))[2], middle);
    assertFalse(read.text().shortened() || rows.text().shortened(), middle);

    // Then the texts are shortened, and the buttons stay inside the card.
    String image = "card-footer-narrow-dark-1x";
    String narrow = run(318, image);
    Golden.assertMatches(
        image,
        Png.decode(Files.readAllBytes(out.resolve(image + ".png"))),
        Files.readString(out.resolve(image + ".tree.txt")));
    assertEquals(0, rect(line(narrow, START))[2], narrow);
    assertTrue(read.text().shortened() && rows.text().shortened(), narrow);
    float[] card = rect(line(narrow, "#card"));
    float[] last = rect(line(narrow, "label=\"Last page\""));
    assertTrue(last[0] + last[2] <= card[0] + card[2], narrow);
  }

  @Test
  void aFooterStartWithAMinimumWidthKeepsItWhileTheEndTextsShorten() throws IOException {
    Supplier<El> minStart =
        () -> Label.label(Label.Variant.MUTED, "About 1,000,000 rows, as the server estimates")
            .minSize(160, 0);

    String wide = run(1000, null, minStart);
    float fullStart = rect(line(wide, START))[2];
    assertTrue(fullStart > 160, wide);
    assertFalse(
        start.text().shortened() || read.text().shortened() || rows.text().shortened(), wide);

    // While room remains above the minimum, start shrinks with an ellipsis and end texts stay full.
    String middle = run(560, null, minStart);
    float middleStart = rect(line(middle, START))[2];
    assertTrue(middleStart > 160 && middleStart < fullStart, middle);
    assertTrue(start.text().shortened(), middle);
    assertEquals(rect(line(wide, READ))[2], rect(line(middle, READ))[2], middle);
    assertEquals(rect(line(wide, ROWS))[2], rect(line(middle, ROWS))[2], middle);
    assertFalse(read.text().shortened() || rows.text().shortened(), middle);

    // Once start hits its minimum width, it holds there and the end texts shorten instead.
    String narrow = run(424, null, minStart);
    assertEquals(160, rect(line(narrow, START))[2], narrow);
    assertTrue(start.text().shortened(), narrow);
    assertTrue(read.text().shortened() && rows.text().shortened(), narrow);
    float[] card = rect(line(narrow, "#card"));
    float[] last = rect(line(narrow, "label=\"Last page\""));
    assertTrue(last[0] + last[2] <= card[0] + card[2], narrow);

    // Where the 160 px minimum plus the fixed controls no longer fit, start yields below 160 so
    // the controls still stay inside the card.
    String tighter = run(280, null, minStart);
    float tighterStart = rect(line(tighter, START))[2];
    assertTrue(tighterStart > 0 && tighterStart < 160, tighter);
    float[] tighterCard = rect(line(tighter, "#card"));
    float[] tighterLast = rect(line(tighter, "label=\"Last page\""));
    assertTrue(tighterLast[0] + tighterLast[2] <= tighterCard[0] + tighterCard[2], tighter);
  }

  private String layout(int width, Supplier<El> toolbar) throws IOException {
    App<Object> app = new App<>(
        new Object(),
        s -> col()
            .wFull()
            .hFull()
            .fill(Theme.DARK.surface().window())
            .child(Card.flush(toolbar.get().key("bar"), el().grow(1)).grow(1)));
    Script.run("size " + width + " 120\nframe", app, out);
    return app.tree().dump();
  }

  @Test
  void toolbarSpacerKeepsStartControlsInPlaceAndAllowsNullTitle() throws IOException {
    Supplier<El> switcher =
        () -> Segmented.segmented(List.of("Sessions", "Locks"), 0, i -> {}).key("switcher");
    Supplier<El> a =
        () -> Button.button(Button.Variant.GHOST, "Refresh", () -> {}).key("a");
    Supplier<El> b = () -> Button.button(Button.Variant.GHOST, "Filter", () -> {});
    Supplier<El> c = () -> Button.button(Button.Variant.SECONDARY, "Export", () -> {});

    String one =
        layout(700, () -> Card.toolbar("Monitor", null, switcher.get(), Card.spacer(), a.get()));
    String three = layout(
        700,
        () -> Card.toolbar(
            "Monitor", null, switcher.get(), Card.spacer(), a.get(), b.get(), c.get()));
    assertEquals(rect(line(one, "#switcher"))[0], rect(line(three, "#switcher"))[0], three);
    assertTrue(rect(line(three, "#a"))[0] < rect(line(one, "#a"))[0], three);

    String untitled =
        layout(700, () -> Card.toolbar(null, null, switcher.get(), Card.spacer(), a.get()));
    assertFalse(untitled.contains("role=heading"), untitled);
    assertEquals(
        rect(line(untitled, "#bar"))[0] + Theme.DARK.space().xl(),
        rect(line(untitled, "#switcher"))[0],
        untitled);
  }
}
