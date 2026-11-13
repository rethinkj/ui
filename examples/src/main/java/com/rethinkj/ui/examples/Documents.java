package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.examples.TitleBar.titleBar;
import static com.rethinkj.ui.kit.Toggle.toggle;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.EmptyState;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.kit.Tabs.Tab;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import com.rethinkj.ui.window.Windows;
import java.io.IOException;
import java.util.List;

/**
 * Documents in tabs that move and tear off, as in a browser or an editor ({@link TabGroups}):
 * drag a tab along the bar to move it, out of the bar to give it a window of its own that follows
 * the pointer, and over a bar, of this window or another, to put it there; a window with one tab
 * moves as its tab is dragged. Each tab's menu moves it to a new window or back without dragging.
 * Closing a document's window puts its tabs back into the first one. Every window shows the same
 * documents, and the title bar switches between the dark and the light theme.
 */
public final class Documents {

  static final int MARK = 0xFF2563EB;
  static final float WIDTH = 640;
  static final float HEIGHT = 360;
  static final float TAB_WIDTH = 480; // a window torn off
  static final float TAB_HEIGHT = 300;

  /** A document, a tab. */
  static final class Doc {
    final String title;
    String text;

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
    s.app = new App<>(s, d -> d.view(d.groups.main()));
    // A script's images are stored, so they must not depend on the platform.
    s.app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, s.app)) {
      return;
    }
    s.app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(Documents.class, "Documents", MARK,
        Symbol.DOCUMENT).frameless().minimumSize(360, 240);
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
  }

  /** Opens the window of a group of tabs, titled after its tab, a smaller copy of the first. */
  Windows.Handle open(TabGroups.Group<Doc> group, Window.Options options) {
    App<Documents> window = new App<>(this, s -> s.view(group));
    window.textRendering(app.textRendering());
    return app.windows().open(group.activeTab().title, TAB_WIDTH, TAB_HEIGHT,
        options.frameless().minimumSize(240, 160).icon(Identity.icon(MARK, Symbol.DOCUMENT)),
        window);
  }

  /** A window's view: the title bar over the group's tabs, a card on the window's background. */
  El view(TabGroups.Group<Doc> group) {
    Theme t = theme();
    El tabs = groups.tabs(t, group, d -> new Tab(d.title).icon(Symbol.DOCUMENT),
        d -> d == null
            ? EmptyState.emptyState(t, Symbol.DOCUMENT, "No documents",
                "Drag a tab here from another window.", null)
            : editor(t, d));
    return col().wFull().hFull().fill(t.surface().window())
        .onKey(tabs.onKey()) // the tabs' keys from anywhere in the window
        .child(titleBar(t, "Documents", MARK, toggle(t, dark, "Dark", () -> dark = !dark)))
        .child(col().key("tabs").grow(1).padding(0, t.space().m(), t.space().m(), t.space().m())
            .child(tabs.grow(1)));
  }

  static El editor(Theme t, Doc d) {
    El area = d.title.equals("Query")
        ? TextField.codeArea(t, d.text, "Empty", v -> d.text = v)
        : TextField.textArea(t, d.text, "Empty", v -> d.text = v);
    return col().grow(1).padding(t.space().l())
        .child(area.key("text").label(d.title).wFull().grow(1));
  }
}
