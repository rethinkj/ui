package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Drawer}: modal and non-modal side sheets against a window edge, focus and Escape,
 * non-modal interaction with the main view, custom widths and scrolling content; stored images.
 */
class DrawerTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private static final class Demo {
    boolean open;
    boolean modal;
    Side side = Side.RIGHT;
    String name = "prod-eu";
    String host = "db.example.com";
    String note = "";
    int mainClicks;
    int dismissals;

    Demo(boolean open, boolean modal) {
      this.open = open;
      this.modal = modal;
    }

    El view() {
      Theme theme = Theme.CURRENT.get();
      El main = Card.card(
          Card.header("Connections", "Primary clusters and replicas"),
          El.row()
              .gap(theme.space().m())
              .children(
                  Button.button(Button.Variant.SECONDARY, "Open drawer", () -> open = true)
                      .key("open"),
                  Button.button(Button.Variant.SECONDARY, "Count", () -> mainClicks++)
                      .key("count")),
          TextField.textField(note, "Note", value -> note = value).key("note"));
      El drawerContent = modal
          ? Form.form(
              Form.Labels.ABOVE,
              Form.field(
                  "Name",
                  TextField.textField(name, "Name", value -> name = value).key("name")),
              Form.field(
                  "Host",
                  TextField.textField(host, "Host", value -> host = value).key("host")))
          : Properties.properties(
              List.of(
                  Properties.property("Order", "1042").mono(),
                  Properties.property("Customer", "Ada Lovelace"),
                  Properties.property("Email", "ada.lovelace@example.com").mono(),
                  Properties.property("Amount", "149.70").mono(),
                  Properties.property("Status", "paid")),
              null);
      Runnable dismiss = () -> {
        open = false;
        dismissals++;
      };
      El drawer = !open
          ? null
          : Drawer.drawer(
              modal ? "Edit connection" : "Order #1042",
              drawerContent,
              dismiss,
              Drawer.options().side(side).modal(modal));
      return El.col()
          .wFull()
          .hFull()
          .fill(theme.surface().window())
          .padding(theme.space().l())
          .child(main)
          .overlay(drawer);
    }

    App<Demo> app() {
      App<Demo> app = new App<>(this, Demo::view).provide(Theme.CURRENT, d -> Theme.DARK);
      app.textRendering(TextRendering.LINUX);
      return app;
    }
  }

  @Test
  void modalAndNonModalDrawersLookAsStored() throws IOException {
    App<Demo> modalApp = new Demo(true, true).app();
    String modalName = "drawer-modal-dark-2x";
    Script.run(
        "size 640 360 scale 2\nframe\nadvance 0.25\nframe\nsnap " + modalName + "\n",
        modalApp,
        out);
    String modalTree = Files.readString(out.resolve(modalName + ".tree.txt"));
    Golden.assertMatches(
        modalName, Png.decode(Files.readAllBytes(out.resolve(modalName + ".png"))), modalTree);
    assertTrue(modalTree.contains("\noverlay edge right stretch modal scrim on 0,0 640x360\n"));
    assertTrue(
        modalTree.contains(" #drawer role=dialog label=\"Edit connection\" 280,0 360x360\n"));
    assertEquals("name", modalApp.focused().key(), "focus opens in the first field");

    App<Demo> nonModalApp = new Demo(true, false).app();
    String nonModalName = "drawer-non-modal-dark-2x";
    Script.run(
        "size 640 360 scale 2\nframe\nadvance 0.25\nframe\nsnap " + nonModalName + "\n",
        nonModalApp,
        out);
    String nonModalTree = Files.readString(out.resolve(nonModalName + ".tree.txt"));
    Golden.assertMatches(
        nonModalName,
        Png.decode(Files.readAllBytes(out.resolve(nonModalName + ".png"))),
        nonModalTree);
    assertTrue(nonModalTree.contains("\noverlay edge right stretch on 0,0 640x360\n"));
    assertFalse(nonModalTree.contains("modal"), nonModalTree);
    assertFalse(nonModalTree.contains("scrim"), nonModalTree);
    assertTrue(nonModalTree.contains(" #drawer role=dialog label=\"Order #1042\" 280,0 360x360\n"));
    assertEquals("close", nonModalApp.focused().key(), "no tab stops in Properties: close button");
  }

  @Test
  void modalOpensWithFocusInsideDismissesOnEscapeAndScrimAndReturnsFocus() throws IOException {
    Demo demo = new Demo(false, true);
    App<Demo> app = demo.app();
    Script.run("""
        size 640 360 scale 2
        frame
        focus #open
        key Enter
        frame
        advance 0.25
        frame
        """, app, out);
    assertTrue(demo.open);
    assertEquals("name", app.focused().key(), "focus moved into the drawer");

    List<String> order = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      order.add(app.focused().key());
      Script.run("size 640 360 scale 2\nkey Tab\nframe\n", app, out);
    }
    assertEquals(List.of("name", "host", "close"), order);
    assertEquals("name", app.focused().key(), "Tab cycles inside the modal drawer");

    Script.run("size 640 360 scale 2\nkey Escape\nframe\n", app, out);
    assertFalse(demo.open, "Escape dismissed the modal drawer");
    assertEquals(1, demo.dismissals);
    assertEquals("open", app.focused().key(), "focus returned to the opener");

    // Reopen and press on the scrim: dismisses without pressing the main view underneath.
    Script.run("""
        size 640 360 scale 2
        key Enter
        frame
        advance 0.25
        frame
        press 40,40
        frame
        """, app, out);
    assertFalse(demo.open, "pressing the scrim dismissed the drawer");
    assertEquals(0, demo.mainClicks, "the main view did not receive the press");
    assertEquals("open", app.focused().key(), "focus returned to the opener");
  }

  @Test
  void nonModalLeavesMainViewUsableAndEscapeInsideDismisses() throws IOException {
    Demo demo = new Demo(false, false);
    App<Demo> app = demo.app();
    Script.run("""
        size 640 360 scale 2
        frame
        focus #open
        key Enter
        frame
        advance 0.25
        frame
        """, app, out);
    assertTrue(demo.open);
    assertEquals("close", app.focused().key(), "focus moved into the drawer");

    // Clicking a button in the main view works without closing the drawer.
    Script.run("size 640 360 scale 2\npress #count\nframe\n", app, out);
    assertTrue(demo.open, "non-modal drawer stayed open");
    assertEquals(1, demo.mainClicks, "main view took the press");

    // Clicking and typing in a field in the main view works; Escape there does not close the
    // drawer.
    Script.run("""
        size 640 360 scale 2
        press #note
        type "hello"
        key Escape
        frame
        """, app, out);
    assertTrue(demo.open, "Escape outside the non-modal drawer does not dismiss it");
    assertEquals("hello", demo.note);

    // Focus back inside the drawer and press Escape: dismisses and returns focus to the opener.
    Script.run("size 640 360 scale 2\nfocus #close\nkey Escape\nframe\n", app, out);
    assertFalse(demo.open, "Escape inside the non-modal drawer dismissed it");
    assertEquals("open", app.focused().key(), "focus returned to the opener");
  }
}
