package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The kit's texts in German: all of them, also those written in a handler. */
class TextsTest {

  static final Texts GERMAN = new GermanTexts();

  @TempDir
  Path out;

  @Test
  void germanHasEveryText() {
    for (Method m : Texts.class.getDeclaredMethods()) {
      if (m.isDefault() && !m.getName().equals("keys")) { // keys joins key's names
        assertDoesNotThrow(
            () -> GermanTexts.class.getDeclaredMethod(m.getName(), m.getParameterTypes()),
            m.toString());
      }
    }
  }

  @Test
  void messagesOfHandlersAreInTheViewsTexts() throws IOException {
    TextLibraries.assumeLoaded();
    Ledger ledger = new Ledger(Theme.DARK, 40);
    App<Ledger> grid = ledger.app().provide(Texts.CURRENT, l -> GERMAN);
    Script.run(
        "size 640 260\nframe\nfocus #ledger\nframe\nkey End\nkey ArrowLeft\n"
            + "type \"2026-02-30\"\nkey Enter\nframe",
        grid,
        out);
    String dump = grid.tree().dump();
    assertTrue(dump.contains("label=\"Erwartet wird ein Datum wie 2026-11-13\""), dump);

    BigDecimal[] amount = {BigDecimal.ONE};
    ValueField.Draft draft = new ValueField.Draft();
    App<Object> field = new App<>(
            new Object(),
            x -> col()
                .child(ValueField.valueField(Format.DECIMAL, amount[0], draft, v -> {
                      amount[0] = v;
                    })
                    .key("amount")))
        .provide(Texts.CURRENT, x -> GERMAN);
    Script.run("size 300 100\nframe\nfocus #amount\ntype \"e\"\nframe", field, out);
    dump = field.tree().dump();
    assertTrue(dump.contains("error=\"Keine Zahl\""), dump);
  }
}
