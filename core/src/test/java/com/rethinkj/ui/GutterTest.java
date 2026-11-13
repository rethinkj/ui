package com.rethinkj.ui;

import static com.rethinkj.ui.Fields.field;
import static com.rethinkj.ui.Fields.focus;
import static com.rethinkj.ui.Fields.key;
import static com.rethinkj.ui.Fields.show;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Fields.Model;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextLibraries;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gutter of a multi-line field ({@link El#gutter}): its width, its cells for the lines laid out
 * only, at each line's first row, scrolling with the text up and down and not sideways, the caret's
 * line, presses on cells, and 100 000 lines. {@link Areas} makes the field: 200 by 98 at 10,10,
 * its content from 14,14, five lines of 18 pixels of JetBrains Mono, whose characters advance 8.
 */
class GutterTest {

  private final List<String> pressed = new ArrayList<>();

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  /** Line numbers in JetBrains Mono, with a padding of 4 at the right, which tell presses. */
  private El.Gutter numbers() {
    return (line, caret) -> El.row().padding(0, 4, 0, 0).onPress(() -> pressed.add("" + line))
        .child(El.text((caret ? ">" : "") + (line + 1)).font(Font.mono()).fontSize(14));
  }

  private App<Model> area(String value, UnaryOperator<El> field) {
    App<Model> app = Areas.start(value, f -> field.apply(f.gutter(numbers())), 1);
    focus(app);
    return app;
  }

  private static String text(int lines) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < lines; i++) {
      out.append("line ").append(i).append(i + 1 < lines ? "\n" : "");
    }
    return out.toString();
  }

  private static El column(App<?> app) {
    return field(app).children().getFirst();
  }

  /** The lines of the dump inside the gutter, stripped. */
  private static List<String> cells(App<?> app) {
    List<String> out = new ArrayList<>();
    for (El cell : column(app).children()) {
      out.add(cell.key() + " " + cell.y() + " " + cell.children().getFirst().text().string());
    }
    return out;
  }

  @Test
  void theGutterIsAsWideAsTheLastLinesCellAndTheTextStartsAfterIt() {
    App<Model> app = area(text(12), f -> f);
    El column = column(app);
    // "12" in two characters of 8 and the padding of 4; the gutter after the field's padding.
    assertEquals(20, column.width());
    assertEquals(4, column.x(), "inside the padding of the field");
    assertEquals(4 + 20, field(app).style().paddingLeft.value(), "the text starts after it");
    assertEquals("gutter", column.key());
    app.pointerDown(10 + 4 + 20 + 8 * 3, 14 + 18 + 9);
    app.pointerUp(10 + 4 + 20 + 8 * 3, 14 + 18 + 9);
    app.frame();
    assertTrue(show(app).startsWith("line 0\nlin|e 1"), "presses in the text after it: " + show(app));
    assertTrue(app.tree().dump().contains("el #gutter"), app.tree().dump());
  }

  @Test
  void cellsAreBuiltForTheLinesLaidOutAtTheirFirstRows() {
    App<Model> app = area("aaaa bbbb cccc dddd eeee ffff gggg\nb\nc", f -> f.wrapText());
    // The first line wraps into two rows: the second line's cell stands at the third row.
    assertEquals(List.of("line-0 0.0 >1", "line-1 36.0 2", "line-2 54.0 3"), cells(app));
  }

  @Test
  void theGutterScrollsUpAndDownWithTheTextAndStaysInPlaceSideways() {
    App<Model> app = area(text(40) + "\n" + "x".repeat(80), f -> f);
    List<String> top = cells(app);
    assertEquals(8, top.size(), "five lines in view and three below: " + top);
    app.wheel(100, 40, 0, 36);
    app.frame();
    List<String> scrolled = cells(app);
    assertEquals("line-0 -36.0 >1", scrolled.getFirst(), "two lines up with the text");
    assertEquals(10, scrolled.size(), "the two above, five in view, three below: " + scrolled);
    key(app, "End", Key.CONTROL); // the long last line: the text scrolls sideways
    assertTrue(field(app).scrollX() > 0);
    assertEquals(4, column(app).x(), "the gutter does not move sideways");
    assertEquals(4, column(app).y(), "nor down with the scroll: it is the content box");
  }

  @Test
  void theCaretsLineIsToldAndPressesReachTheCells() {
    App<Model> app = area(text(5), f -> f);
    assertEquals(">1", cells(app).getFirst().split(" ")[2]);
    key(app, "ArrowDown");
    assertEquals(List.of("1", ">2"), cells(app).subList(0, 2).stream()
        .map(c -> c.split(" ")[2]).toList(), "built again for the caret's new line");
    int built = field(app).editor().lineIndex().gutterCells;
    app.frame();
    app.advance(0.6); // the caret blinks: the rows are laid out again, the cells stay
    app.frame();
    assertEquals(built, field(app).editor().lineIndex().gutterCells);
    app.pointerDown(16, 14 + 18 * 3 + 9);
    app.pointerUp(16, 14 + 18 * 3 + 9);
    app.frame();
    assertEquals(List.of("3"), pressed, "the press reached the cell of line 3");
  }

  @Test
  void a100000LineTextBuildsCellsForTheLinesInViewOnly() {
    App<Model> app = area(text(100_000), f -> f);
    Lines lines = field(app).editor().lineIndex();
    assertEquals(6 * 8 + 4, column(app).width(), "\"100000\" and the padding");
    int first = lines.gutterCells;
    assertTrue(first <= 2 * 8, "the lines laid out, built at most twice: " + first);
    key(app, "End", Key.CONTROL);
    List<String> end = cells(app);
    assertTrue(end.getLast().startsWith("line-99999 "), end.toString());
    assertTrue(lines.gutterCells - first <= 3 * 8, "only the lines at the end: "
        + (lines.gutterCells - first));
  }

  @Test
  void onlyAMultiLineFieldHasAGutter() {
    assertThrows(IllegalStateException.class,
        () -> El.text("x").editable(v -> {}).gutter((line, caret) -> El.el()));
  }
}
