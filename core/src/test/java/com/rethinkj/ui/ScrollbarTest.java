package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Scrollbars: where the bars and thumbs are, dragging a thumb to both ends of a million rows,
 * paging on the track, both bars at once, bars of areas inside others, and a press on a bar that
 * reaches nothing else.
 */
class ScrollbarTest {

  private static final int REST = 0xFF505050;
  private static final int HOVERED = 0xFF707070;
  private static final int DRAGGED = 0xFF909090;
  /** 6 thick, 2 from the edges, thumbs at least 24 long. */
  private static final Scrollbars LOOK = new Scrollbars(6, 2, 3, 24, REST, HOVERED, DRAGGED);

  /** What the handlers were told. */
  static final class Log {
    final List<String> log = new ArrayList<>();
  }

  /**
   * A million rows of 20 pixels in a list over a window of 200 by 100, each row pressable. Its
   * vertical track runs from 192,2 down 96 pixels, and its thumb is 24 long.
   */
  private static App<Log> million() {
    App<Log> app = new App<>(new Log(), l -> col().key("list").wFull().hFull().scrollbars(LOOK)
        .onPress(() -> l.log.add("list"))
        .rows(1_000_000, 20, i -> el().key("row-" + i).onPress(() -> l.log.add("row " + i))));
    app.resize(200, 100, 1);
    app.frame();
    return app;
  }

  @Test
  void draggingTheThumbReachesTheLastRowAndTheFirstExactly() {
    App<Log> app = million();
    String line = app.tree().dump().lines().findFirst().orElseThrow();
    assertEquals("el #list 0,0 200x100 rows 0-7/1000000 thumb-y 192,2 6x24", line);

    app.pointerDown(195, 12); // 10 below the thumb's top
    app.pointerMove(195, 48); // the thumb's top at 38, half way along the 72 pixels it can go
    app.frame();
    assertEquals(9_999_950, app.tree().scrollY(), "half of 20,000,000 - 100");

    app.pointerMove(195, 500); // far beyond the end of the track and the window
    app.frame();
    String dump = app.tree().dump();
    assertTrue(dump.startsWith(
        "el #list 0,0 200x100 rows 999992-999999/1000000 scroll 0,19999900 thumb-y 192,74 6x24"
            + " hovered\n"), dump);
    assertTrue(dump.contains("\n  el #row-999999 0,80 200x20 pos=1000000/1000000\n"),
        "the last row at the bottom");
    app.pointerUp(195, 500);
    app.frame();

    app.pointerDown(195, 80);
    app.pointerMove(195, -300);
    app.pointerUp(195, -300);
    app.frame();
    dump = app.tree().dump();
    assertTrue(dump.startsWith("el #list 0,0 200x100 rows 0-7/1000000 thumb-y 192,2 6x24\n"), dump);
    assertTrue(dump.contains("\n  el #row-0 0,0 200x20 pos=1/1000000\n"), "row 0 at the top");
    assertEquals(List.of(), app.state().log, "no press of a row or of the list");
  }

  @Test
  void aPressOnTheTrackPagesByTheHeightTowardsThePointer() {
    App<Log> app = million();
    app.pointerDown(195, 60); // below the thumb
    app.pointerUp(195, 60);
    app.frame();
    assertEquals(100, app.tree().scrollY());
    app.pointerDown(195, 60, 2, 0); // the second press of a double press is one more page
    app.pointerUp(195, 60);
    app.frame();
    assertEquals(200, app.tree().scrollY());
    app.pointerDown(195, 1); // above the thumb, in the margin over the track
    app.pointerUp(195, 1);
    app.frame();
    assertEquals(100, app.tree().scrollY());
    assertEquals(List.of(), app.state().log);
  }

  /** A scroll area of 100 by 100 with one child of this height, and the look's minimum. */
  private static App<Object> area(float content, float minThumb) {
    Scrollbars look = new Scrollbars(6, 2, 3, minThumb, REST, HOVERED, DRAGGED);
    App<Object> app = new App<>(new Object(), s -> col().key("area").size(100, 100).scroll()
        .scrollbars(look).child(el().h(content)));
    app.resize(100, 100, 1);
    app.frame();
    return app;
  }

  @ParameterizedTest(name = "content {0}, minimum {1}: thumb {2} long")
  @CsvSource({
    "200, 24, 48",  // half in view: half the track of 96
    "400, 24, 24",  // a quarter: 24, the minimum
    "1000, 24, 24", // a tenth would be 9.6
    "1000, 0, 9.6",
    "200, 500, 96", // a minimum beyond the track: the whole track
  })
  void theThumbIsTheShareInViewAndMovesWithTheOffset(float content, float min, float length) {
    App<Object> app = area(content, min);
    Scrollbars.Bar bar = app.tree().verticalBar();
    assertEquals(new Scrollbars.Bar(92, 2, 6, 96, 92, 2, 6, length, REST), bar);
    assertNull(app.tree().horizontalBar());

    app.wheel(50, 50, 0, 10_000); // to the end
    app.frame();
    bar = app.tree().verticalBar();
    assertEquals(2 + 96 - length, bar.thumbY(), 1e-4, "at the end of the track");
    if (length < 96) {
      app.scrollTo(app.tree(), 0, (content - 100) / 4);
      app.frame();
      assertEquals(2 + (96 - length) / 4, app.tree().verticalBar().thumbY(), 1e-4, "a quarter");
    }
  }

  @Test
  void anAxisWhoseContentFitsHasNoBar() {
    for (float content : new float[] {50, 99.5f, 100}) {
      App<Object> app = area(content, 24);
      assertNull(app.tree().verticalBar(), "content " + content);
      assertFalse(app.tree().dump().contains("thumb"), app.tree().dump());
    }
    App<Object> app = new App<>(new Object(), s -> col().size(100, 100).scrollBoth()
        .scrollbars(LOOK).child(el().size(300, 50)));
    app.resize(100, 100, 1);
    app.frame();
    assertNull(app.tree().verticalBar(), "wider, not taller");
    assertEquals(new Scrollbars.Bar(2, 92, 96, 6, 2, 92, 32, 6, REST), app.tree().horizontalBar(),
        "the whole width without a vertical bar");
  }

  /**
   * A scroll area of 100 by 100 along both axes over content of 300 by 200 that takes presses,
   * at 0,0 in a window of the same size.
   */
  private static App<Log> both() {
    App<Log> app = new App<>(new Log(), l -> col().key("area").size(100, 100).scrollBoth()
        .scrollbars(LOOK)
        .child(el().key("content").size(300, 200).onPress(() -> l.log.add("content"))));
    app.resize(100, 100, 1);
    app.frame();
    return app;
  }

  @Test
  void bothBarsStopShortOfTheCornerWherePressesReachTheContent() {
    App<Log> app = both();
    El area = app.tree();
    // 88 long each: 100 less the margins at both ends and the other bar with its margin.
    assertEquals(new Scrollbars.Bar(92, 2, 6, 88, 92, 2, 6, 44, REST), area.verticalBar());
    assertEquals(new Scrollbars.Bar(2, 92, 88, 6, 2, 92, 88 / 3f, 6, REST), area.horizontalBar());
    assertTrue(area.dump().startsWith("el #area 0,0 100x100 thumb-x 2,92 29.33x6 thumb-y 92,2 6x44"),
        area.dump());

    app.pointerDown(96, 96); // the corner
    app.pointerUp(96, 96);
    app.pointerDown(95, 70); // the vertical track below its thumb
    app.pointerUp(95, 70);
    app.pointerDown(70, 95); // the horizontal track beside its thumb
    app.pointerUp(70, 95);
    app.frame();
    assertEquals(List.of("content"), app.state().log);
    area = app.tree(); // the press on the content rebuilt the view
    assertEquals(100, area.scrollY(), "a page of the height, as far as the end");
    assertEquals(100, area.scrollX(), "a page of the width");
    assertEquals(new Scrollbars.Bar(92, 2, 6, 88, 92, 46, 6, 44, REST), area.verticalBar());

    app.pointerDown(10, 95); // the track before the horizontal thumb, now at 31.33
    app.pointerUp(10, 95);
    app.pointerDown(20, 95); // the thumb where that page put it, at 2: no page, a drag
    app.pointerMove(300, 95);
    app.pointerUp(300, 95);
    app.frame();
    assertEquals(200, app.tree().scrollX(), "dragged to the end");
    assertEquals(100, app.tree().scrollY(), "the other axis stays");
  }

  @Test
  void theBarOfAnAreaInsideAnotherStaysInItsVisiblePartAndOutOfTheOuterBar() {
    // As the data grid: the rows scroll up and down inside content that scrolls sideways.
    App<Object> app = new App<>(new Object(), s -> row().key("outer").size(100, 100).scrollBoth()
        .scrollbars(LOOK)
        .child(col().w(300).child(
            col().key("inner").grow(1).scrollbars(LOOK).rows(100, 20, i -> el()))));
    app.resize(100, 100, 1);
    app.frame();
    El outer = app.tree();
    El inner = outer.children().getFirst().children().getFirst();
    assertNull(outer.verticalBar());
    assertEquals(new Scrollbars.Bar(2, 92, 96, 6, 2, 92, 32, 6, REST), outer.horizontalBar());
    // At the right edge of what shows of the inner area, 300 wide, and above the outer strip.
    assertEquals(new Scrollbars.Bar(92, 2, 6, 86, 92, 2, 6, 24, REST), inner.verticalBar());

    app.wheel(50, 50, 120, 0); // sideways: the inner bar stays at the edge
    app.frame();
    assertEquals(120, outer.scrollX());
    assertEquals(92, inner.verticalBar().thumbX());

    app.pointerDown(95, 10); // the inner thumb
    app.pointerMove(95, 200);
    app.frame();
    assertEquals(100 * 20 - 100, inner.scrollY());
    assertEquals(120, outer.scrollX());
  }

  @Test
  void aThumbChangesColourUnderThePointerAndWhileDraggedWithoutARebuild() {
    int[] views = {0};
    App<Object> app = new App<>(new Object(), s -> {
      views[0]++;
      return col().key("list").wFull().hFull().scrollbars(LOOK).cursor(Cursor.POINTER)
          .rows(1000, 20, i -> el());
    });
    app.resize(200, 100, 1);
    app.frame();
    assertEquals(REST, app.tree().verticalBar().color());

    app.pointerMove(195, 60); // the track below the thumb: the bar's, but not the thumb
    assertTrue(!app.frame() || app.tree().verticalBar().color() == REST);
    assertEquals(REST, app.tree().verticalBar().color());
    assertEquals(Cursor.DEFAULT, app.cursor(), "the arrow over a bar, not the area's cursor");
    assertTrue(app.tree().dump().startsWith("el #list 0,0 200x100 rows 0-7/1000 thumb-y 192,2 6x24"
        + " hovered\n"), "a press would go to the area's bar");

    app.pointerMove(195, 10);
    assertTrue(app.frame(), "a new colour to draw");
    assertEquals(HOVERED, app.tree().verticalBar().color());
    assertFalse(app.frame(), "idle");

    app.pointerDown(195, 10);
    assertTrue(app.frame());
    assertEquals(DRAGGED, app.tree().verticalBar().color());
    app.pointerMove(60, 10); // off the bar, but the thumb is still held
    app.frame();
    assertEquals(DRAGGED, app.tree().verticalBar().color());
    assertSame(app.tree(), app.hovered(), "the area, wherever the pointer is");
    assertEquals(Cursor.DEFAULT, app.cursor());

    app.pointerUp(60, 10);
    assertTrue(app.frame());
    assertEquals(REST, app.tree().verticalBar().color());
    assertEquals(Cursor.POINTER, app.cursor());
    assertEquals(1, views[0], "colours and offsets rebuild nothing");
  }

  @Test
  void aPressOnABarReachesNoRowAncestorOrDragAndKeepsTheFocus() {
    Log state = new Log();
    App<Log> app = new App<>(state, l -> col().wFull().hFull()
        .onPress(() -> l.log.add("root")).onDrag(d -> l.log.add("drag " + d.phase()))
        .children(
            el().key("button").h(10).focusable().onPress(() -> l.log.add("button")),
            col().key("list").grow(1).scrollbars(LOOK).rows(1000, 20, i -> el().key("row-" + i)
                .focusable().whenHovered(e -> e.fill(0xFF0000FF))
                .onPress(() -> l.log.add("row " + i)))));
    app.resize(200, 110, 1);
    app.frame();
    El button = app.tree().children().getFirst();
    app.focus(button);
    app.frame();

    app.pointerMove(195, 20);
    app.pointerDown(195, 20); // on the thumb, over row 0
    app.pointerMove(150, 30); // onto the rows, beyond any drag threshold
    app.frame();
    assertNull(app.pressed());
    assertTrue(app.tree().children().get(1).children().stream().allMatch(r -> r.fill() == 0),
        "no row is hovered meanwhile");
    app.pointerUp(150, 30);
    app.frame();
    app.pointerDown(195, 100); // the track
    app.pointerUp(195, 100);
    app.frame();
    assertEquals(List.of(), state.log);
    assertSame(find(app.tree(), "button"), app.focused(), "the focus stays");
    assertTrue(app.tree().children().get(1).scrollY() > 0);

    app.pointerDown(150, 30); // beside the bar: the row's, as before
    app.pointerUp(150, 30);
    app.frame();
    assertEquals(1, state.log.size());
    assertTrue(state.log.getFirst().startsWith("row "), state.log.toString());
  }

  @Test
  void aPressOnABarOverATextFieldSelectsNothing() {
    TextLibraries.assumeLoaded();
    String[] value = {"hello world"};
    App<Object> app = new App<>(new Object(), s -> col().key("area").size(100, 60).scroll()
        .scrollbars(LOOK).children(
            El.text(value[0]).editable(v -> value[0] = v).key("field").w(100).h(24),
            el().h(200)));
    app.textRendering(TextRendering.LINUX);
    app.resize(100, 60, 1);
    app.frame();
    app.pointerDown(95, 10); // the thumb, over the end of the field
    app.pointerMove(95, 12); // 2 of the 32 pixels the thumb can go: 10.25 down
    app.pointerUp(95, 12);
    app.frame();
    assertNull(app.focused(), "no caret, no selection");
    assertTrue(app.tree().scrollY() > 0);

    app.pointerDown(50, 5); // in the field, which scrolled up by less than its height
    app.pointerUp(50, 5);
    app.frame();
    assertEquals("field", app.focused().key());
  }

  @Test
  void theWheelMovesTheThumbAsBefore() {
    App<Log> app = million();
    app.wheel(195, 50, 0, 100); // over the bar too
    app.frame();
    assertEquals(100, app.tree().scrollY());
    assertEquals(2 + 72 * 100 / 19_999_900.0, app.tree().verticalBar().thumbY(), 1e-5);
  }

  @Test
  void headlessDrawsTheBarsAtTheStart() {
    El view = col().key("area").size(100, 100).scrollBoth().scrollbars(LOOK)
        .child(el().size(300, 200));
    Headless.Frame frame = Headless.render(view, 100, 100, 1);
    assertTrue(frame.tree().startsWith("el #area 0,0 100x100 thumb-x 2,92 29.33x6 thumb-y 92,2 6x44"),
        frame.tree());
    assertEquals(0xFF505050, frame.pixels().argb(95, 20), "the vertical thumb");
    assertEquals(0, frame.pixels().argb(95, 60), "the track has no fill");
  }

  @Test
  void anAreaWithoutTheLookHasNoBars() {
    App<Object> app = new App<>(new Object(), s -> col().size(100, 100).scrollBoth()
        .child(el().size(300, 200).onPress(() -> {})));
    app.resize(100, 100, 1);
    app.frame();
    assertNull(app.tree().verticalBar());
    assertNull(app.tree().horizontalBar());
    assertEquals("el 0,0 100x100\n  el 0,0 300x200\n", app.tree().dump());
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
}
