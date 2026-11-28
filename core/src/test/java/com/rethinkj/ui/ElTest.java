package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.layout.Flex;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ElTest {

  @Test
  void dumpsTheTreeOneElementPerLine() {
    assertEquals("""
        el
          el #switch role=switch label="Lights" checked
            el #knob
        """, Examples.switchAlone(true).dump());
  }

  @Test
  void dumpsRectanglesInWindowCoordinatesAfterLayout() {
    El child = el().key("child");
    El root = el().child(child);
    root.place(10, 20, 200, 100.5f);
    child.place(1.25f, -2, 50, 30);

    assertEquals("""
        el 10,20 200x100.5
          el #child 11.25,18 50x30
        """, root.dump());
  }

  @Test
  void flexLayoutPlacesTheTree() {
    El root = Examples.switchAlone(true);
    Flex.layout(root, 200, 100);

    assertEquals("""
        el 0,0 200x100
          el #switch role=switch label="Lights" 81,39 38x22 checked
            el #knob 99,41 18x18
        """, root.dump());
  }

  @Test
  void opacityIsFromZeroToOne() {
    assertEquals(1, el().opacity());
    assertEquals(0.25f, el().opacity(0.25f).opacity());
    for (float wrong : new float[] {-0.01f, 1.01f, Float.NaN}) {
      assertThrows(IllegalArgumentException.class, () -> el().opacity(wrong));
    }
  }

  @Test
  void dumpsStatesAfterTheRectangleInAFixedOrder() {
    El node = el().key("node")
        .role("treeitem")
        .label("public")
        .value("2 tables")
        .tone("accent")
        .sortDescending()
        .position(1, 2)
        .level(3)
        .expanded(true)
        .checkedMixed()
        .selected(true)
        .disabled(true)
        .busy(true);
    node.place(0, 66, 400, 22);
    assertEquals(
        "el #node role=treeitem label=\"public\" 0,66 400x22 disabled busy selected checked=mixed"
            + " expanded level=3 pos=1/2 sort=descending value=\"2 tables\" tone=accent\n",
        node.dump());
    assertEquals(
        "el checked=false expanded=false sort=ascending\n",
        el().checked(false).expanded(false).sortAscending().dump());
    assertEquals(
        "el checked\n",
        el().selected(true)
            .selected(false)
            .disabled(true)
            .disabled(false)
            .checked(true)
            .dump(),
        "the last call wins");
  }

  @Test
  void aTextFieldIsDisabledOrReadOnlyByWhatMakesItSo() {
    assertEquals(
        "text role=textbox \"a\" disabled\n", El.text("a").editable(null).dump());
    String readOnly = "text role=textbox \"a\" readonly\n";
    assertEquals(readOnly, El.text("a").editable(null).readOnly().dump());
    assertEquals(readOnly, El.text("a").editable(s -> {}).readOnly().dump());
    assertEquals("text role=textbox \"a\"\n", El.text("a").editable(s -> {}).dump());
  }

  @Test
  void levelsAndPositionsCountFromOne() {
    assertThrows(IllegalArgumentException.class, () -> el().level(0));
    assertThrows(IllegalArgumentException.class, () -> el().position(0, 3));
    assertThrows(IllegalArgumentException.class, () -> el().position(4, 3));
  }

  @Test
  void escapesQuotesAndCarriageReturnsInLabels() {
    assertEquals("el label=\"say \\\"hi\\\"\\r\"\n", el().label("say \"hi\"\r").dump());
  }

  @Test
  void omitsValueWhenItEqualsLabelInDump() {
    assertEquals("el label=\"same\"\n", el().label("same").value("same").dump());
    assertEquals("el value=\"\"\n", el().value("").dump(), "empty value still dumps");
    assertEquals(
        "el label=\"label\" value=\"value\"\n",
        el().label("label").value("value").dump(),
        "distinct value dumps");
    assertEquals("el\n", el().value(null).dump(), "null value omits value");
  }

  @Test
  void aNullChildIsLeftOut() {
    El a = El.el().key("a");
    El b = El.el().key("b");
    El parent = El.col().child(null).children(a, null, b).child(null);
    assertEquals(java.util.List.of(a, b), parent.children());
  }

  @Test
  void anAddedKeyHandlerIsAskedFirstAndTheOwnGetsWhatItDeclines() {
    StringBuilder asked = new StringBuilder();
    Predicate<Key> own = key -> asked.append("own ") != null;
    assertSame(own, el().onKeyFirst(own).onKey(), "with none, the added one is the handler");
    El field = el().onKey(own);
    field.onKeyFirst(key -> asked.append("added ") != null && key.name().equals("Enter"));
    assertTrue(field.onKey().test(new Key("Enter", Key.CONTROL, false)));
    assertTrue(field.onKey().test(new Key("A", 0, false)));
    assertEquals("added added own ", asked.toString());
  }

  @Test
  void anAddedDecoratorMarksAfterTheOwnSoItsMarksWin() {
    El field = El.text("abc").editable(value -> {}).multiline();
    field.alsoDecorate((line, start, text, out) -> out.background(0, 3, 0xFFFF0000));
    field.alsoDecorate((line, start, text, out) -> out.background(1, 2, 0xFF0000FF));
    Spans spans = new Spans(3);
    field.decorator().line(0, 0, "abc", spans);
    assertEquals("background 0-3 FFFF0000\nbackground 1-2 FF0000FF\n", spans.toString());
  }
}
