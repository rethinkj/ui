package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TitleBar.titleBar;
import static com.rethinkj.ui.kit.TitleBar.withMenus;
import static com.rethinkj.ui.kit.Toggle.toggle;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Drop;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FieldState;
import com.rethinkj.ui.FileDialog;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Find;
import com.rethinkj.ui.kit.Icon;
import com.rethinkj.ui.kit.Menu;
import com.rethinkj.ui.kit.MenuBar;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.kit.Tabs.Tab;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.Toast;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.SystemDialogs;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import com.rethinkj.ui.window.Windows;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Documents in tabs that move and tear off, as in a browser or an editor ({@link TabGroups}):
 * drag a tab along the bar to move it, out of the bar to give it a window of its own that follows
 * the pointer, and over a bar, of this window or another, to put it there; a window with one tab
 * moves as its tab is dragged. Each tab's menu moves it to a new window or back without dragging.
 * Closing a document's window puts its tabs back into the first one. Every window shows the same
 * documents, and the title bar switches between the dark and the light theme.
 *
 * <p>File › Open… opens text files of the system's file dialog in new tabs, read off the app's
 * thread, and File › Save As… writes the active document to a file the dialog chooses; files
 * dragged onto a window from a file manager open there too, while the tabs' card shows that it
 * takes them. A file that cannot be read, or is larger than {@link #LIMIT}, is said in a toast.
 */
public final class Documents {

  static final int MARK = Identity.BLUE;
  static final float WIDTH = 640;
  static final float HEIGHT = 360;
  static final float TAB_WIDTH = 480; // a window torn off
  static final float TAB_HEIGHT = 300;
  /** The largest file the example opens, in bytes. */
  static final long LIMIT = 1 << 20;

  /** A document, a tab. */
  static final class Doc {
    String title;
    String text;
    Path path; // where it was read from or saved to, or null
    final Find.State find = new Find.State(); // Control+F in its text
    // Its editor's caret, selection, scroll and history, which go with it to another tab and back.
    final FieldState field = new FieldState();

    Doc(String title, String text) {
      this.title = title;
      this.text = text;
    }

    @Override
    public String toString() {
      return title;
    }
  }

  App<Documents> app; // the first window's
  boolean dark = true;
  final MenuBar.State bar = new MenuBar.State();
  String toast; // a message at the bottom of the first window, or null
  TabGroups.Group<Doc> dropping; // the group whose window a file manager's drag is over
  final TabGroups<Doc> groups = new TabGroups<>(List.of(
      new Doc("Readme", "Tabs that move, tear off and dock.\nDrag one out of the bar."),
      new Doc("Notes", "Coffee, bread"),
      new Doc("Todo", "Write the release notes\nTry the tabs on Windows"),
      new Doc("Query", "SELECT id, name\nFROM customers\nWHERE active;"),
      new Doc("Ideas", "A window per group of tabs")),
      this::open);

  Theme theme() {
    return dark ? Theme.DARK : Theme.LIGHT;
  }

  public static void main(String[] args) throws IOException {
    Documents s = new Documents();
    s.app = new App<>(s, d -> d.view(d.groups.main(), d.app));
    // A script's images are stored, so they must not depend on the platform.
    s.app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, s.app)) {
      return;
    }
    s.app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(Documents.class, "Documents", MARK,
        Symbol.DOCUMENT).frameless().minimumSize(360, 240);
    // Without a GPU or a native library, a message box says why instead of nothing at all.
    SystemDialogs.reportFailure("Documents", () -> {
      try (Window window = Window.open("Documents", WIDTH, HEIGHT, options);
          GpuPresenter presenter = new GpuPresenter(window)) {
        WindowLoop loop = new WindowLoop(window, s.app, presenter);
        // Closing the first window closes the others too, and with them the loop.
        loop.windows().of(s.app).onCloseRequest(() -> {
          for (Windows.Handle open : loop.windows().all()) {
            open.close();
          }
        });
        loop.run();
      }
    });
  }

  /** Opens the window of a group of tabs, titled after its tab, a smaller copy of the first. */
  Windows.Handle open(TabGroups.Group<Doc> group, Window.Options options) {
    @SuppressWarnings("unchecked")
    App<Documents>[] window = new App[1];
    window[0] = new App<>(this, s -> s.view(group, window[0]));
    window[0].textRendering(app.textRendering());
    return app.windows().open(group.activeTab().title, TAB_WIDTH, TAB_HEIGHT,
        options.frameless().minimumSize(240, 160).icon(Identity.icon(MARK, Symbol.DOCUMENT)),
        window[0]);
  }

  /**
   * A window's view: the title bar over the group's tabs, a card on the window's background. The
   * first window's title bar has the menus; every window takes dropped files.
   *
   * @param window the window's app, which a document's find bar selects in
   */
  El view(TabGroups.Group<Doc> group, App<Documents> window) {
    Theme t = theme();
    El tabs = groups.tabs(t, group, d -> new Tab(d.title).icon(Symbol.DOCUMENT),
        d -> d == null
            ? EmptyState.emptyState(t, Symbol.DOCUMENT, "No documents",
                "Drag a tab here from another window.", null)
            : editor(t, d, window));
    if (dropping == group) { // the card's edge in the accent, as wide as before
      tabs.border(t.size().stroke(), t.accent().edge());
    }
    El dark = toggle(t, this.dark, "Dark", () -> this.dark = !this.dark);
    El root = col().wFull().hFull().fill(t.surface().window())
        .onFileDrop(d -> drop(group, d));
    if (group == groups.main()) {
      List<Menu.Item> menus = menus(group);
      root.onKey(MenuBar.keys(bar, menus).or(tabs.onKey())) // the tabs' keys from anywhere
          .onKeyUp(MenuBar.keysUp(bar, menus))
          .child(withMenus(t, "Documents", MARK, MenuBar.menuBar(t, bar, menus),
          Identity.maximized(window), dark));
    } else {
      root.onKey(tabs.onKey())
          .child(titleBar(t, "Documents", MARK, Identity.maximized(window), dark));
    }
    root.child(col().key("tabs").grow(1).padding(0, t.space().m(), t.space().m(), t.space().m())
        .child(tabs.grow(1)));
    if (dropping == group) {
      root.overlay(hint(t));
    } else if (toast != null && group == groups.main()) {
      root.overlay(Toast.toast(t, toast, null, null, () -> toast = null));
    }
    return root;
  }

  /** The menus of the first window's title bar. */
  List<Menu.Item> menus(TabGroups.Group<Doc> group) {
    Doc active = group.tabs().isEmpty() ? null : group.activeTab();
    return List.of(Menu.submenu("&File", List.of(
        Menu.item("Open…", () -> openFiles(group)).icon(Symbol.FOLDER_OPEN)
            .shortcut(Key.CONTROL, "O"),
        Menu.item("Save As…", active == null ? null : () -> saveAs(active)).icon(Symbol.SAVE)
            .shortcut(Key.CONTROL | Key.SHIFT, "S"))));
  }

  /** File › Open…: the system's dialog, then each file read off the app's thread. */
  void openFiles(TabGroups.Group<Doc> group) {
    app.fileDialog(FileDialog.open("Open").filter("Documents", "sql", "txt", "md")
            .filter("All files", "*").many(),
        choice -> read(group, choice.paths()),
        error -> toast = "No file dialog: " + error);
  }

  /** File › Save As…: the system's dialog, then the text written off the app's thread. */
  void saveAs(Doc d) {
    String name = d.path != null ? d.path.getFileName().toString()
        : d.title + (d.title.equals("Query") ? ".sql" : ".txt");
    // The document's own kind first: the dialog starts with the first filter.
    FileDialog dialog = name.toLowerCase(Locale.ROOT).endsWith(".sql")
        ? FileDialog.save("Save As").filter("SQL", "sql").filter("Text", "txt", "md")
        : FileDialog.save("Save As").filter("Text", "txt", "md").filter("SQL", "sql");
    dialog = dialog.fileName(name);
    if (d.path != null && d.path.toAbsolutePath().getParent() != null) {
      dialog = dialog.in(d.path.toAbsolutePath().getParent());
    }
    app.fileDialog(dialog, choice -> {
      if (choice.cancelled()) {
        return;
      }
      Path to = choice.path();
      String text = d.text; // the state stays on the app's thread
      app.background("save " + to.getFileName(), () -> Files.writeString(to, text), saved -> {
        d.path = to;
        d.title = to.getFileName().toString();
        toast = "Saved " + d.title;
      }, e -> toast = "Cannot save " + to.getFileName() + ": " + why(e));
    }, error -> toast = "No file dialog: " + error);
  }

  /** A drag from a file manager: the look while it is over, new tabs when it drops. */
  void drop(TabGroups.Group<Doc> group, Drop d) {
    switch (d.phase()) {
      case OVER -> dropping = group;
      case LEAVE -> dropping = null;
      case DROP -> {
        dropping = null;
        if (d.payload() instanceof List<?> files) {
          read(group, files.stream().map(Path.class::cast).toList());
        } else if (d.payload() instanceof String text) {
          groups.add(group, group.tabs().size(), new Doc("Dropped text", text));
        }
      }
    }
  }

  /**
   * Reads files off the app's thread, then puts each into a new tab of the group, in the order
   * given, or says in a toast why one could not be read. One piece of work for all, so that the
   * tabs come in the order of the files, not in the order their reads end.
   */
  void read(TabGroups.Group<Doc> group, List<Path> files) {
    if (files.isEmpty()) {
      return;
    }
    app.background("read " + files.size() + " files", () -> {
      List<Object> read = new ArrayList<>(); // the text, or what went wrong
      for (Path file : files) {
        try {
          if (Files.size(file) > LIMIT) {
            throw new IOException("it is larger than " + LIMIT / 1024 + " KB");
          }
          read.add(Files.readString(file));
        } catch (IOException e) {
          read.add(e);
        }
      }
      return read;
    }, read -> {
      TabGroups.Group<Doc> to = groups.groups().contains(group) ? group : groups.main();
      for (int i = 0; i < files.size(); i++) {
        Path file = files.get(i);
        String name = file.getFileName() == null ? file.toString()
            : file.getFileName().toString();
        if (read.get(i) instanceof String text) {
          Doc doc = new Doc(name, text);
          doc.path = file;
          groups.add(to, to.tabs().size(), doc);
        } else {
          toast = "Cannot open " + name + ": " + why((Exception) read.get(i));
        }
      }
    }, e -> toast = "Cannot open the files: " + why(e));
  }

  /** What went wrong with a file, in words. */
  static String why(Exception e) {
    return switch (e) {
      case NoSuchFileException n -> "it is not there";
      case AccessDeniedException a -> "it may not be read";
      case MalformedInputException m -> "it is not text in UTF-8";
      default -> e.getMessage() == null ? e.toString() : e.getMessage();
    };
  }

  /** What a window shows while a file manager's drag is over it. */
  static El hint(Theme t) {
    return row().key("drop-hint").role("status").label("Drop to open in a new tab")
        .atWindowEdge(Side.BOTTOM, Style.Align.CENTER, t.space().xl())
        .keepFocus()
        .padding(t.space().s(), t.space().l(), t.space().s(), t.space().l())
        .gap(t.space().s())
        .align(Style.Align.CENTER)
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.accent().edge())
        .shadow(t.elevation().popup())
        .child(Icon.icon(t, Symbol.DOCUMENT))
        .child(label(t, "Drop to open in a new tab"));
  }

  static El editor(Theme t, Doc d, App<Documents> app) {
    El area = d.title.equals("Query") || d.title.toLowerCase(Locale.ROOT).endsWith(".sql")
        ? TextField.codeArea(t, d.text, "Empty", v -> d.text = v)
        : TextField.textArea(t, d.text, "Empty", v -> d.text = v);
    area.fieldState(d.field);
    return col().grow(1).padding(t.space().l())
        .child(Find.find(t, app, area.key("text").label(d.title).wFull().grow(1), d.find));
  }
}
