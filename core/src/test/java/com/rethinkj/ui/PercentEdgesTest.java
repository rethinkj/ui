package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.rethinkj.ui.text.TextLibraries;
import com.rethinkj.ui.text.TextRendering;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drawing, hit testing and scrolling of elements whose padding, border or margin is given in
 * percent ({@link Len#percent}).
 */
class PercentEdgesTest {

  @BeforeEach
  void needsText() {
    TextLibraries.assumeLoaded();
  }

  @Test
  void percentEdgesAreDrawnHitTestedAndScrolledAsLaidOut() {
    String[] value = {"hello world"};
    App<String[]> app = new App<>(value, s -> {
      El field = El.text(s[0])
          .editable(edit -> s[0] = edit)
          .key("f")
          .wFull()
          .h(32)
          .fill(0xFF2B2D30)
          .color(0xFFE8E8E8)
          .caretColor(0xFFFFFFFF)
          .border(0, 0xFF6B7280);
      field.style().borderTop = Len.percent(0.01f);
      field.style().borderBottom = Len.percent(0.01f);
      field.style().borderLeft = Len.percent(0.02f);
      field.style().borderRight = Len.percent(0.02f);
      field.style().paddingTop = Len.percent(0.02f);
      field.style().paddingBottom = Len.percent(0.02f);
      field.style().paddingLeft = Len.percent(0.10f);
      field.style().paddingRight = Len.percent(0.05f);

      El item = el().key("item").wFull().h(30).fill(0xFF3B82F6);
      item.style().marginBottom = Len.percent(0.10f);

      El list = col()
          .key("list")
          .scroll()
          .wFull()
          .h(40)
          .fill(0xFF2B2D30)
          .border(0, 0xFF6B7280)
          .child(item);
      list.style().borderTop = Len.percent(0.01f);
      list.style().borderBottom = Len.percent(0.02f);
      list.style().borderLeft = Len.percent(0.01f);
      list.style().borderRight = Len.percent(0.01f);
      list.style().paddingTop = Len.percent(0.02f);
      list.style().paddingBottom = Len.percent(0.05f);
      list.style().paddingLeft = Len.percent(0.04f);
      list.style().paddingRight = Len.percent(0.04f);

      return col().wFull().hFull().padding(16, 20).gap(12).fill(0xFF1E1F22).children(field, list);
    });
    app.textRendering(TextRendering.LINUX);
    app.resize(240, 116, 1);
    app.frame();

    // Parent inner width is 200: field left border is 4, left padding is 20, content starts at 44.
    El field = app.tree().children().get(0);
    assertEquals(4, field.border(Side.LEFT));
    assertEquals(20, field.padding(Side.LEFT));
    float clickX = 20 + 4 + 20 + field.text().x(5);
    app.pointerDown(clickX, 32, 1, 0);
    app.pointerUp(clickX, 32);
    app.frame();

    El focused = app.tree().children().get(0);
    assertEquals(5, focused.editor().caret(), "press lands after 'hello'");
    assertEquals(
        24 + focused.text().x(5), focused.editor().caretBox()[0], 0.5f, "caret box includes edges");

    Headless.Frame frame = app.render();
    Golden.assertMatches("percent-edges", frame.pixels(), frame.tree());

    // List inner width is 200 - 4 (borders) - 16 (padding) = 180, so item marginBottom is 18.
    // Content bottom is 2 + 4 + 30 + 18 + 10 + 4 = 68 in a 40-tall list: maxScrollY is 28.
    app.wheel(100, 80, 0, 100);
    app.frame();
    El list = app.tree().children().get(1);
    assertEquals(28.0, list.scrollY(), "scroll extent includes percent margin, padding and border");
  }

  @Test
  void aFractionalPercentPaddingLeavesNothingToScroll() {
    App<String> app = new App<>("", s -> {
      El list = col().key("list").scroll().w(104).h(60).child(el().hFull());
      list.style().paddingBottom = Len.percent(0.0625f); // 6.5 of the window's 104
      return list;
    });
    app.resize(104, 60, 1);
    app.frame();
    assertFalse(app.tree().canScroll(false, 1), "the child fills the list exactly");
  }
}
