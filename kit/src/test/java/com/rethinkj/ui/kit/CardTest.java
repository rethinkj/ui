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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A card's footer in a card too narrow for it. */
class CardTest {

  private static final String START = "\"About 1,000,000 rows, as the server estimates\"";
  private static final String READ = "\"Read 200 rows in 28 ms\"";
  private static final String ROWS = "\"Rows 1–7\"";

  private final Path out = Path.of("target/card-test");
  private El read;
  private El rows;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private El view(Theme theme) {
    Button.Options small = Button.options().size(Button.Size.SMALL);
    read = Label.label(Label.Variant.MUTED, "Read 200 rows in 28 ms");
    rows = Label.label(Label.Variant.MUTED, "Rows 1–7");
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .child(Card.flush(
                el().grow(1),
                Card.footer(
                    Label.label(
                            Label.Variant.MUTED, "About 1,000,000 rows, as the server estimates")
                        .ellipsis(),
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
    App<Object> app = new App<>(new Object(), s -> view(Theme.DARK));
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
    float start = rect(line(middle, START))[2];
    assertTrue(start > 0 && start < rect(line(wide, START))[2], middle);
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
}
