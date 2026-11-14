package com.rethinkj.ui.examples.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the examples of the documentation site (docs/), whose code the pages show: each on the
 * script {@code src/test/resources/docs/<name>.txt}, or a frame of its view where it has none, comparing the trees of its snaps with
 * {@code src/test/resources/golden/docs/<name>}, which the pages show too. With {@code
 * -Dgolden.update=true} it stores them anew.
 */
class DocsExamplesTest {

  private static final Path SCRIPTS = Path.of("src/test/resources/docs");
  private static final Path STORED = Path.of("src/test/resources/golden/docs");

  /** An example: its app and the size of its window for the default script. */
  record Example(Supplier<App<?>> app, int width, int height) {}

  /** Each example by the name of its script, or of its snap where it has no script. */
  static Map<String, Example> examples() {
    Map<String, Example> all = new LinkedHashMap<>();
    all.put("lights", new Example(() -> new App<>(new Lights(), Lights::view), 360, 240));
    controls(all, "buttons", Controls::buttons, 520, 120);
    controls(all, "button-options", Controls::buttonOptions, 420, 120);
    controls(all, "toggles", Controls::toggles, 240, 110);
    controls(all, "choices", Controls::choices, 320, 260);
    controls(all, "swatches", Controls::swatches, 460, 140);
    controls(all, "combos", Controls::combos, 360, 240);
    controls(all, "typed", Controls::typed, 360, 420);
    controls(all, "fields", Controls::fields, 360, 400);
    controls(all, "parts", Controls::parts, 400, 200);
    controls(all, "values", Controls::values, 360, 240);
    layouts(all, "cards", Layouts::cards, 420, 360);
    layouts(all, "groups", Layouts::groups, 460, 200);
    layouts(all, "sections", Layouts::sections, 520, 400);
    layouts(all, "form", Layouts::form, 480, 260);
    layouts(all, "dividers", Layouts::dividers, 280, 140);
    layouts(all, "navigation", Layouts::navigation, 260, 220);
    layouts(all, "navigation-typed", Layouts::typedNavigation, 260, 220);
    layouts(all, "split", Layouts::split, 520, 240);
    layouts(all, "tabs", Layouts::tabs, 480, 220);
    layouts(all, "status", Layouts::status, 460, 420);
    layouts(all, "bars", Layouts::bars, 480, 160);
    layouts(all, "details", Layouts::details, 520, 280);
    overlays(all, "menu", Overlays::menus, 360, 260);
    overlays(all, "context-menu", Overlays::menus, 360, 260);
    overlays(all, "menu-bar", Overlays::menuBar, 360, 200);
    overlays(all, "popover", Overlays::popover, 420, 260);
    overlays(all, "tooltip", Overlays::tooltip, 320, 120);
    overlays(all, "dialog", Overlays::dialog, 420, 260);
    overlays(all, "toast", Overlays::toast, 420, 200);
    overlays(all, "command-palette", Overlays::palette, 640, 360);
    grids(all, "grid", Grids::basic, 520, 300);
    grids(all, "grid-sort", Grids::sorted, 520, 300);
    grids(all, "grid-edit", Grids::edited, 520, 300);
    grids(all, "grid-filter", Grids::filtered, 520, 300);
    grids(all, "grid-typed", Grids::typed, 520, 300);
    grids(all, "tree", Grids::tree, 320, 260);
    all.put("grid-commit", new Example(() -> new App<>(new Commits(), Commits::view), 520, 300));
    editors(all, "code-editor", Editors::codeEditor, 520, 260);
    editors(all, "find", Editors::find, 440, 220);
    all.put(
        "highlighter",
        new Example(
            () -> {
              Highlighters state = new Highlighters();
              App<Highlighters> app = new App<>(state, Highlighters::view);
              state.app = app;
              return app;
            },
            440,
            200));
    editors(all, "markdown", Editors::markdown, 520, 320);
    editors(all, "image", Editors::image, 240, 180);
    concepts(all, "layout", Concepts::layout, 480, 200);
    all.put("styles", new Example(() -> new App<>(new Styles(), Styles::view), 360, 400));
    concepts(all, "text", Concepts::texts, 320, 200);
    concepts(all, "input", Concepts::input, 280, 140);
    concepts(all, "states", Concepts::states, 240, 80);
    concepts(all, "motion", Concepts::motion, 320, 160);
    concepts(all, "overlay", Concepts::overlay, 280, 160);
    concepts(all, "rows", Concepts::rows, 280, 200);
    all.put("row-heights", new Example(() -> new App<>(new Messages(), Messages::view), 320, 240));
    concepts(all, "field", Concepts::fields, 320, 200);
    all.put("range", new Example(() -> new App<>(new Paragraphs(), Paragraphs::view), 300, 140));
    concepts(all, "background", Concepts::background, 420, 140);
    concepts(all, "progress", Concepts::progress, 280, 120);
    all.put(
        "lanes",
        new Example(
            () -> {
              Concepts state = new Concepts();
              App<Concepts> app = new App<>(state, Concepts::lanes);
              state.app = app;
              state.connection = app.lane("staging");
              state.db = Concepts.Connection.madeUp(app);
              return app;
            },
            440,
            150));
    concepts(all, "images", Concepts::images, 280, 120);
    concepts(all, "theme", Concepts::theme, 200, 80);
    all.put("tab-groups", new Example(Notebook::app, 420, 260));
    all.put("editing", new Example(() -> new App<>(new Editings(), Editings::record), 380, 260));
    all.put("editing-pojo", new Example(() -> new App<>(new Editings(), Editings::pojo), 360, 160));
    return all;
  }

  private static void controls(
      Map<String, Example> all, String name, Function<Controls, El> view, int width, int height) {
    all.put(name, new Example(() -> new App<>(new Controls(), view), width, height));
  }

  private static void layouts(
      Map<String, Example> all, String name, Function<Layouts, El> view, int width, int height) {
    all.put(name, new Example(() -> new App<>(new Layouts(), view), width, height));
  }

  private static void overlays(
      Map<String, Example> all, String name, Function<Overlays, El> view, int width, int height) {
    all.put(name, new Example(() -> new App<>(new Overlays(), view), width, height));
  }

  private static void grids(
      Map<String, Example> all, String name, Function<Grids, El> view, int width, int height) {
    all.put(
        name,
        new Example(
            () -> {
              Grids state = new Grids();
              App<Grids> app = new App<>(state, view);
              state.app = app;
              return app;
            },
            width,
            height));
  }

  private static void editors(
      Map<String, Example> all, String name, Function<Editors, El> view, int width, int height) {
    all.put(
        name,
        new Example(
            () -> {
              Editors state = new Editors();
              App<Editors> app = new App<>(state, view);
              state.app = app;
              return app;
            },
            width,
            height));
  }

  private static void concepts(
      Map<String, Example> all, String name, Function<Concepts, El> view, int width, int height) {
    all.put(
        name,
        new Example(
            () -> {
              Concepts state = new Concepts();
              App<Concepts> app = new App<>(state, view);
              state.app = app;
              return app;
            },
            width,
            height));
  }

  @TestFactory
  Stream<DynamicTest> eachExampleRunsAndShowsTheStoredTrees() throws IOException {
    try (var files = Files.list(SCRIPTS)) {
      for (Path script : files.toList()) {
        assertTrue(
            examples().containsKey(script.getFileName().toString().replace(".txt", "")),
            script + " has no example");
      }
    }
    return examples().entrySet().stream()
        .map(e -> DynamicTest.dynamicTest(e.getKey(), () -> {
          assumeText();
          Example example = e.getValue();
          App<?> app = example.app().get();
          app.textRendering(TextRendering.LINUX); // the same trees on every platform
          Path out = Path.of("target/docs", e.getKey());
          Path file = SCRIPTS.resolve(e.getKey() + ".txt");
          String script = Files.exists(file)
              ? Files.readString(file)
              : "size " + example.width() + " " + example.height() + "\nframe\nsnap view\n";
          Script.run(script, app, out);
          compare(out, STORED.resolve(e.getKey()));
        }));
  }

  private static void compare(Path out, Path stored) throws IOException {
    try (var files = Files.list(out)) {
      for (Path written :
          files.filter(p -> p.toString().endsWith(".tree.txt")).sorted().toList()) {
        Path kept = stored.resolve(written.getFileName());
        if (Boolean.getBoolean("golden.update")) {
          Files.createDirectories(stored);
          Files.copy(written, kept, StandardCopyOption.REPLACE_EXISTING);
          continue;
        }
        assertTrue(Files.exists(kept), "No stored " + kept + "; run with -Dgolden.update=true");
        assertEquals(
            Files.readString(kept),
            Files.readString(written),
            written.toAbsolutePath() + " differs from " + kept);
      }
    }
  }

  private static void assumeText() {
    try {
      Font.sans();
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: FreeType and HarfBuzz cannot be loaded. " + e.getMessage());
    }
  }
}
