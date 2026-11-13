package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.TitleBar.titleBar;
import static com.rethinkj.ui.kit.Toggle.toggle;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Image;
import com.rethinkj.ui.Script;
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
 * heading scrolls there; other links show their address in a toast. The title bar switches
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
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read " + file, e);
      }
    }
  }

  Theme theme() {
    return dark ? Theme.DARK : Theme.LIGHT;
  }

  private static String resource(String name) {
    try (InputStream in = Manual.class.getResourceAsStream("manual/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The image at this address of the document, or null while it is being read or where it cannot
   * be: the first call starts reading it off the app's thread, and the view is built again when
   * it is there. Addresses with a scheme, such as {@code https:}, are not read.
   */
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
      List<Path> entries =
          files.filter(f -> f.toString().endsWith(".md")).sorted().toList().reversed();
      return changelog(entries.stream()
          .map(f -> {
            try {
              return Files.readString(f);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          })
          .toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The changelog of these entries, each a bullet such as {@code - Added: ...}. */
  static String changelog(List<String> entries) {
    StringBuilder out = new StringBuilder("# Changelog\n\nWhat changed since the last release,"
        + " for users of the library.\n\n## Unreleased\n");
    for (String kind : List.of("Added", "Changed", "Fixed", "Other")) {
      List<String> items = entries.stream()
          .map(String::strip)
          .filter(e -> kind.equals(kindOf(e)))
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

  /** What a link does: a heading of the document comes into view, any other shows a toast. */
  void link(String destination) {
    if (destination.startsWith("#")) {
      scrollTo(destination.substring(1));
      return;
    }
    toast = "Opens " + destination;
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
        .filter(a -> a.toLowerCase(Locale.ROOT).endsWith(".md"))
        .map(Path::of)
        .toList();
    String[] rest = script < 0 ? new String[0] : Arrays.copyOfRange(args, script, args.length);
    Manual manual = new Manual(files);
    App<Manual> app = new App<>(manual, Manual::view);
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

  static El view(Manual s) {
    Theme t = s.theme();
    Doc doc = s.docs.get(s.doc);
    List<NavList.Item> documents =
        s.docs.stream().map(d -> NavList.item(d.icon(), d.title())).toList();
    List<MarkdownDocument.Heading> headings =
        doc.document().headings().stream().filter(h -> h.level() <= 3).toList();
    List<NavList.Item> outline = headings.stream()
        .map(h -> NavList.item(
            ("  ".repeat(Math.max(0, h.level() - 2))) + MarkdownDocument.plainText(h.content())))
        .toList();
    El side = col()
        .grow(1)
        .minSize(0, 0)
        .children(
            NavList.navList(t, documents, s.doc, i -> {
                  s.doc = i;
                  s.heading = -1;
                  s.scrollTo(null);
                })
                .key("documents")
                .label("Documents"),
            Divider.divider(t).margin(t.space().s(), 0, t.space().s(), 0),
            Label.label(t, Label.Variant.MUTED, "On this page")
                .margin(t.space().xs(), t.space().m(), t.space().s(), t.space().m()),
            NavList.navList(t, outline, s.heading, i -> {
                  s.heading = i;
                  s.scrollTo(headings.get(i).id());
                })
                .key("outline")
                .label("On this page")
                .grow(1)
                .minSize(0, 0));
    El document = Markdown.markdown(
            t,
            doc.document(),
            Markdown.options()
                .onLink(s::link)
                .copy(s.app == null ? null : s.app.clipboard())
                .state(doc.selection())
                .images(source -> s.image(doc, source)))
        .key("doc-" + s.doc)
        .grow(1)
        .minSize(0, 0);
    El root = col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .child(titleBar(
            t,
            "Manual",
            t.accent().color(),
            Identity.maximized(s.app),
            toggle(t, s.dark, "Dark", () -> s.dark = !s.dark)))
        .child(row()
            .grow(1)
            .minSize(0, 0)
            .padding(0, t.space().m(), t.space().m(), t.space().m())
            .gap(t.space().m())
            .children(
                Card.flush(t, Card.inset(t, side)).w(240).shrink(0),
                Card.flush(t, document).grow(1).minSize(0, 0)));
    return root.overlay(
        s.toast == null ? null : Toast.toast(t, s.toast, null, null, () -> s.toast = null));
  }
}
