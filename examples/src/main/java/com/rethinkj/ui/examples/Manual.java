package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.TitleBar.titleBar;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Image;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.AppIcon;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Divider;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Markdown;
import com.rethinkj.ui.kit.MarkdownDocument;
import com.rethinkj.ui.kit.NavList;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Toast;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A reader of Markdown documents in a frameless window: the documents in a navigation list at the
 * left with the headings of the chosen one below them, a press on one scrolls to it, and the
 * document in a card on the page at the right, which scrolls. The documents are a manual that
 * shows every form the kit's {@link Markdown} supports, the project's changelog, made from the
 * files under {@code changelog/unreleased} when that folder is there and from a copy that comes
 * with the example otherwise, and the Markdown files named on the command line. A link to a
 * heading scrolls there; other links open in the system's browser. The title bar switches
 * between the dark and the light theme. The state is this class.
 *
 * <p>Images are read off the app's thread ({@link App#background}) the first time the document
 * asks for one, and kept: the manual's from its resources, made for scale 2, a file's from the
 * folder of the file, one pixel to one logical pixel. Until an image is there, or where it cannot
 * be read, the document shows its placeholder.
 */
public final class Manual {

  /**
   * A document of the list, read once.
   *
   * @param folder where its images are, null for the manual's resources
   */
  record Doc(
      String title, Symbol icon, MarkdownDocument document, Markdown.State selection, Path folder) {
    Doc(String title, Symbol icon, String source, Path folder) {
      this(title, icon, Markdown.parse(source), new Markdown.State(), folder);
    }
  }

  final List<Doc> docs = new ArrayList<>();
  int doc;
  int heading = -1;
  boolean dark = true;
  String toast;
  /** The app that runs this state, for scrolling to a heading from a handler. */
  App<Manual> app;
  /** The images read so far, by document and address, and those asked for. */
  final Map<String, Image> images = new HashMap<>();

  private final Set<String> asked = new HashSet<>();

  Manual(List<Path> files) {
    docs.add(new Doc("Manual", Symbol.DOCUMENT, resource("manual.md"), null));
    docs.add(new Doc("Changelog", Symbol.HISTORY, changelog(), null));
    for (Path file : files) {
      try {
        docs.add(new Doc(
            file.getFileName().toString(),
            Symbol.DOCUMENT,
            Files.readString(file),
            file.toAbsolutePath().getParent()));
      } catch (IOException error) {
        throw new UncheckedIOException("Cannot read " + file, error);
      }
    }
  }

  Theme theme() {
    return dark ? Theme.DARK : Theme.LIGHT;
  }

  private static String resource(String name) {
    try (InputStream in = Manual.class.getResourceAsStream("manual/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  /**
   * The image at this address of the document, or null while it is being read or where it cannot
   * be: the first call starts reading it off the app's thread, and the view is built again when
   * it is there. Addresses with a scheme, such as {@code https:}, are not read.
   */
  // docs:start manual-images
  Image image(Doc doc, String source) {
    String key = doc.title() + "\n" + source;
    if (app != null && !source.contains(":") && asked.add(key)) {
      Path folder = doc.folder();
      app.background(
          "image " + source,
          () -> {
            if (folder != null) {
              return Image.decode(Files.readAllBytes(folder.resolve(source)));
            }
            try (InputStream in = Manual.class.getResourceAsStream("manual/" + source)) {
              if (in == null) {
                throw new IOException("No image " + source);
              }
              return Image.decode(in.readAllBytes(), 2);
            }
          },
          image -> images.put(key, image),
          failure -> {}); // the placeholder stays
    }
    return images.get(key);
  }
  // docs:end manual-images

  /**
   * The changelog: the entries under {@code changelog/unreleased}, newest first and grouped by
   * the word they start with, or the copy that comes with the example where the folder is not
   * there, as in a test.
   */
  static String changelog() {
    Path folder = Path.of("changelog", "unreleased");
    if (!Files.isDirectory(folder)) {
      return resource("changelog.md");
    }
    try (Stream<Path> files = Files.list(folder)) {
      List<Path> entries = files
          .filter(file -> file.toString().endsWith(".md"))
          .sorted()
          .toList()
          .reversed();
      return changelog(entries.stream()
          .map(file -> {
            try {
              return Files.readString(file);
            } catch (IOException error) {
              throw new UncheckedIOException(error);
            }
          })
          .toList());
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  /** The changelog of these entries, each a bullet such as {@code - Added: ...}. */
  static String changelog(List<String> entries) {
    StringBuilder out = new StringBuilder("# Changelog\n\nWhat changed since the last release,"
        + " for users of the library.\n\n## Unreleased\n");
    for (String kind : List.of("Added", "Changed", "Fixed", "Other")) {
      List<String> items = entries.stream()
          .map(String::strip)
          .filter(entry -> kind.equals(kindOf(entry)))
          .toList();
      if (items.isEmpty()) {
        continue;
      }
      out.append("\n### ").append(kind).append("\n\n");
      for (String item : items) {
        String text = item;
        if (item.startsWith("- " + kind + ":")) {
          String rest = item.substring(kind.length() + 3).strip();
          text = "- " + rest.substring(0, 1).toUpperCase(Locale.ROOT) + rest.substring(1);
        }
        out.append(text).append('\n');
      }
    }
    return out.toString();
  }

  private static String kindOf(String entry) {
    for (String kind : List.of("Added", "Changed", "Fixed")) {
      if (entry.startsWith("- " + kind + ":")) {
        return kind;
      }
    }
    return "Other";
  }

  /**
   * What a link does: a heading of the document comes into view, a web address opens in the
   * system's browser, and a toast says why any other does not.
   */
  // docs:start manual-links
  void link(String destination) {
    if (destination.startsWith("#")) {
      scrollTo(destination.substring(1));
      return;
    }
    try {
      app.openUri(new URI(destination), this::notOpened);
    } catch (URISyntaxException error) {
      notOpened(error.getMessage());
    }
  }

  void notOpened(String error) {
    toast = "Cannot open the link: " + error;
  }

  /** Scrolls the document so that the block of the heading with this anchor is at its top. */
  void scrollTo(String id) {
    if (app == null || app.tree() == null) {
      return;
    }
    List<El> path = new ArrayList<>();
    if (!find(app.tree(), "doc-" + doc, path)) {
      return;
    }
    El list = path.getLast();
    if (id == null) {
      app.scrollTo(list, 0, 0);
      return;
    }
    int block = Markdown.rowOf(docs.get(doc).document(), id);
    if (block >= 0) {
      app.scrollToRow(list, block);
    }
  }
  // docs:end manual-links

  /** The elements from {@code el} down to the one with this key, into {@code path}. */
  private static boolean find(El el, String key, List<El> path) {
    path.add(el);
    if (key.equals(el.key())) {
      return true;
    }
    for (El child : el.children()) {
      if (find(child, key, path)) {
        return true;
      }
    }
    path.removeLast();
    return false;
  }

  public static void main(String[] args) throws IOException {
    int script = Arrays.asList(args).indexOf("--script");
    List<Path> files = Arrays.stream(args, 0, script < 0 ? args.length : script)
        .filter(argument -> argument.toLowerCase(Locale.ROOT).endsWith(".md"))
        .map(Path::of)
        .toList();
    String[] rest = script < 0 ? new String[0] : Arrays.copyOfRange(args, script, args.length);
    Manual manual = new Manual(files);
    App<Manual> app = new App<>(manual, Manual::view).provide(Theme.CURRENT, Manual::theme);
    manual.app = app;
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(rest, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(
            Manual.class, "Manual", Identity.TEAL, Symbol.DOCUMENT)
        .frameless()
        .minimumSize(640, 420);
    try (Window window = Window.open("Manual", 1100, 760, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      new WindowLoop(window, app, presenter).run();
    }
  }

  static El view(Manual state) {
    Theme theme = Theme.CURRENT.get();
    Doc doc = state.docs.get(state.doc);
    List<NavList.Item> documents = state.docs.stream()
        .map(entry -> NavList.item(entry.icon(), entry.title()))
        .toList();
    List<MarkdownDocument.Heading> headings = doc.document().headings().stream()
        .filter(heading -> heading.level() <= 3)
        .toList();
    List<NavList.Item> outline = headings.stream()
        .map(heading -> NavList.item(MarkdownDocument.plainText(heading.content()))
            .indent(Math.max(0, heading.level() - 2)))
        .toList();
    El side = col()
        .grow(1)
        .minSize(0, 0)
        .children(
            NavList.navList(documents, state.doc, index -> {
                  state.doc = index;
                  state.heading = -1;
                  state.scrollTo(null);
                })
                .key("documents")
                .label("Documents"),
            Divider.divider().margin(theme.space().s(), 0, theme.space().s(), 0),
            Label.label(Label.Variant.MUTED, "On this page")
                .margin(
                    theme.space().xs(),
                    theme.space().m(),
                    theme.space().s(),
                    theme.space().m()),
            NavList.navList(outline, state.heading, index -> {
                  state.heading = index;
                  state.scrollTo(headings.get(index).id());
                })
                .key("outline")
                .label("On this page")
                .grow(1)
                .minSize(0, 0));
    // docs:start manual-markdown
    El document = Markdown.markdown(
            doc.document(),
            Markdown.options()
                .onLink(state::link)
                .copy(state.app == null ? null : state.app.clipboard())
                .state(doc.selection())
                .images(source -> state.image(doc, source)))
        .key("doc-" + state.doc)
        .grow(1)
        .minSize(0, 0);
    // docs:end manual-markdown
    El root = col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .child(titleBar(
            AppIcon.appIcon(16, Identity.TEAL, Symbol.DOCUMENT),
            "Manual",
            Button.icon(
                    Button.Variant.GHOST,
                    Symbol.WEATHER_MOON,
                    "Dark theme",
                    () -> state.dark = !state.dark)
                .checked(state.dark)))
        .child(row()
            .grow(1)
            .minSize(0, 0)
            .padding(0, theme.space().m(), theme.space().m(), theme.space().m())
            .gap(theme.space().m())
            .children(
                Card.flush(Card.inset(side)).w(240).shrink(0),
                Card.flush(document).grow(1).minSize(0, 0)));
    return root.overlay(
        state.toast == null
            ? null
            : Toast.toast(state.toast, null, null, () -> state.toast = null));
  }
}
