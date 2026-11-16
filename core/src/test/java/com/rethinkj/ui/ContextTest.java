package com.rethinkj.ui;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A context's value: from the app's state, from a part of the view, and in rows and looks. */
class ContextTest {

  private static final Context<String> NAME = new Context<>("fallback");

  @Test
  void theViewAndWhatTheAppBuildsFromItLaterSeeTheValuesThatHeldWhereTheyWereGiven() {
    List<String> seen = new ArrayList<>();
    App<String> app = new App<>("from the state", state -> {
          seen.add(NAME.get());
          return NAME.with(
              "from the part",
              () -> col().key("list").wFull().hFull().rows(1000, 20, index -> {
                seen.add(index + " " + NAME.get());
                return el().key("row-" + index).whenHovered(row -> seen.add("look " + NAME.get()));
              }));
        })
        .provide(NAME, state -> state);
    app.resize(100, 100, 1);
    app.frame();
    assertEquals("from the state", seen.getFirst());
    assertEquals("0 from the part", seen.get(1));

    seen.clear();
    app.scrollToRow(app.tree(), 500);
    app.frame();
    assertTrue(seen.contains("500 from the part"), "rows built while scrolling: " + seen);
    assertTrue(seen.stream().allMatch(row -> row.endsWith(" from the part")), seen.toString());

    seen.clear();
    app.pointerMove(10, 10);
    app.frame();
    assertTrue(seen.contains("look from the part"), "a look applied after the view: " + seen);
    assertEquals("fallback", NAME.get());
  }
}
