package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behavior and stored look of {@link Chat#composer}. */
class ChatComposerTest {

  @TempDir
  Path out;

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  static final class Model {
    final Theme theme;
    String text;
    final List<String> attachments = new ArrayList<>();
    final List<String> sent = new ArrayList<>();
    final List<Path> dropped = new ArrayList<>();
    final Menu.State modelMenu = new Menu.State();
    int modelIndex;
    boolean working;
    int stops;
    int attaches;

    Model(Theme theme, String text, List<String> attachments) {
      this.theme = theme;
      this.text = text;
      this.attachments.addAll(attachments);
    }

    void send() {
      sent.add(text);
      text = "";
    }

    void stop() {
      stops++;
      working = false;
    }

    void removeAttachment(int index) {
      attachments.remove(index);
    }

    void addFiles(List<Path> files) {
      dropped.addAll(files);
      for (Path path : files) {
        attachments.add(path.getFileName().toString());
      }
    }

    El view() {
      return col()
          .wFull()
          .hFull()
          .padding(theme.space().l())
          .fill(theme.surface().window())
          .child(Chat.composer(
                  text,
                  value -> text = value,
                  this::send,
                  Chat.composerOptions()
                      .attachments(attachments, this::removeAttachment)
                      .working(working, this::stop)
                      .start(Button.icon(
                              Button.Variant.GHOST, Symbol.ADD, "Attach file", () -> attaches++)
                          .key("attach"))
                      .end(Select.select(
                              List.of("Sonnet", "Opus"), modelIndex, modelMenu, i -> modelIndex = i)
                          .label("Model")
                          .w(120)
                          .key("model"))
                      .onFiles(this::addFiles))
              .key("composer"));
    }

    App<Model> app() {
      App<Model> app = new App<>(this, Model::view).provide(Theme.CURRENT, m -> m.theme);
      app.focusRing(theme.accent().edge());
      app.textRendering(TextRendering.LINUX);
      return app;
    }
  }

  @Test
  void composerMatchesStoredImage() throws IOException {
    App<Model> app = new Model(
            Theme.DARK,
            "Explain the sequential scan on orders\nand suggest a composite index.",
            List.of(
                "orders.sql",
                "explain-analyze-of-the-orders-query-with-buffers-timing-and-settings-on-replica.json"))
        .app();
    String script = Files.readString(Path.of("src/test/resources/scripts/chat-composer.txt"));
    List<Path> files = Script.run(script, app, out.resolve("dark2"));
    Golden.assertSnaps("chat-composer-dark-2x-", files, "view");
  }

  @Test
  void enterSendsShiftEnterAddsLineAndComposingDoesNotSend() throws IOException {
    Model model = new Model(Theme.DARK, "", List.of());
    App<Model> app = model.app();

    Script.run("size 520 180 scale 2\nframe", app, out);
    // Blank text: send is disabled and plain Enter does neither send nor insert a newline.
    assertTrue(
        StructureTest.line(app.tree().dump(), "label=\"Send\"").contains("disabled"),
        app.tree().dump());
    Script.run("focus #field\nkey Enter\nframe", app, out);
    assertEquals(List.of(), model.sent);
    assertEquals("", model.text);

    // Whitespace-only text still disables send and does not send on Enter.
    Script.run("type \"   \"\nframe\nkey Enter\nframe", app, out);
    assertTrue(
        StructureTest.line(app.tree().dump(), "label=\"Send\"").contains("disabled"),
        app.tree().dump());
    assertEquals(List.of(), model.sent);
    assertEquals("   ", model.text);

    // Replace with real text, Shift+Enter inserts a line break and grows the field.
    Script.run("key A control\ntype \"Hello\"\nframe", app, out);
    float singleLineHeight = field(app).height();
    assertFalse(
        StructureTest.line(app.tree().dump(), "label=\"Send\"").contains("disabled"),
        app.tree().dump());

    Script.run("key Enter shift\ntype \"world\"\nframe\nadvance 1\nframe", app, out);
    assertEquals(List.of(), model.sent);
    assertEquals("Hello\nworld", model.text);
    assertTrue(field(app).height() > singleLineHeight, "growing field expands on Shift+Enter");

    // Enter while an input method composes must NOT send.
    Script.run("compose \"ni\"\nframe\nkey Enter\nframe", app, out);
    assertEquals(List.of(), model.sent, "Enter during IME composition must not send");

    // Once composition ends, plain Enter sends.
    Script.run("compose \"\"\nframe\nkey Enter\nframe", app, out);
    assertEquals(List.of("Hello\nworld"), model.sent);
    assertEquals("", model.text);
  }

  @Test
  void attachmentsDropAndWorkingStop() throws IOException {
    Model model = new Model(Theme.DARK, "Check query", List.of("orders.sql", "schema.sql"));
    App<Model> app = model.app();
    Script.run("size 520 180 scale 2\nframe", app, out);

    // Removing the first attachment chip passes its index to onRemove.
    Script.run("press role=button label=\"Remove orders.sql\"\nframe", app, out);
    assertEquals(List.of("schema.sql"), model.attachments);

    // Dropping files onto the composer hands them to onFiles.
    Script.run("drop #composer \"queries/orders.sql\" \"explain.json\"\nframe", app, out);
    assertEquals(List.of(Path.of("queries/orders.sql"), Path.of("explain.json")), model.dropped);
    assertEquals(List.of("schema.sql", "orders.sql", "explain.json"), model.attachments);

    // While working, Send becomes Stop, Enter does not send, and pressing Stop runs onStop.
    model.working = true;
    app.invalidate();
    Script.run("frame", app, out);
    String dump = app.tree().dump();
    assertFalse(dump.contains("label=\"Send\""), dump);
    assertTrue(dump.contains("label=\"Stop\""), dump);

    Script.run("focus #field\nkey Enter\nframe", app, out);
    assertEquals(List.of(), model.sent, "Enter does not send while working");

    Script.run("press role=button label=\"Stop\"\nframe", app, out);
    assertEquals(1, model.stops);
    assertFalse(model.working);
    assertTrue(app.tree().dump().contains("label=\"Send\""), app.tree().dump());
  }

  private static El field(App<?> app) {
    El composer = app.tree().children().getFirst();
    return composer.children().getFirst();
  }
}
