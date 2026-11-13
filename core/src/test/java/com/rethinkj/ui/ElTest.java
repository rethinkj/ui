package com.rethinkj.ui;

import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rethinkj.ui.layout.Flex;
import org.junit.jupiter.api.Test;

class ElTest {

  @Test
  void dumpsTheTreeOneElementPerLine() {
    assertEquals(
        """
        el
          el #switch role=switch label="Lights"
            el #knob
        """,
        Examples.lightSwitch(true).dump());
  }

  @Test
  void dumpsRectanglesInWindowCoordinatesAfterLayout() {
    El child = el().key("child");
    El root = el().child(child);
    root.place(10, 20, 200, 100.5f);
    child.place(1.25f, -2, 50, 30);

    assertEquals(
        """
        el 10,20 200x100.5
          el #child 11.25,18 50x30
        """,
        root.dump());
  }

  @Test
  void flexLayoutPlacesTheTree() {
    El root = Examples.lightSwitch(true);
    Flex.layout(root, 200, 100);

    assertEquals(
        """
        el 0,0 200x100
          el #switch role=switch label="Lights" 81,39 38x22
            el #knob 99,41 18x18
        """,
        root.dump());
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
  void escapesQuotesInLabels() {
    assertEquals("el label=\"say \\\"hi\\\"\"\n", el().label("say \"hi\"").dump());
  }
}
