package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.TabGroups;
import com.rethinkj.ui.kit.Tabs;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.Windows;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The example of the page about TabGroups: notes in tabs that move between windows. */
final class Notebook {

  // docs:start tab-groups
  /** A tab's document; TabGroups tells tabs apart by identity. */
  static final class Note {
    final String title;
    String text;

    Note(String title, String text) {
      this.title = title;
      this.text = text;
    }
  }

  App<Notebook> app; // the first window's app
  Theme theme = Theme.DARK; // what every window provides
  final TabGroups<Note> groups = new TabGroups<>(
      List.of(new Note("Groceries", "Milk"), new Note("Ideas", "A tree of tables")), this::open);

  /** Opens the window of a frame of tabs that left the first window. */
  Windows.Handle open(TabGroups.Frame<Note> frame, Window.Options options) {
    return app.windows()
        .open(
            frame.active().activeTab().title,
            420,
            260,
            options,
            // the same state: a change shows in all windows
            new App<>(this, state -> state.view(frame))
                .provide(Theme.CURRENT, state -> state.theme));
  }

  /** The view of a window: the first one shows groups.main(). */
  El view(TabGroups.Frame<Note> frame) {
    return col()
        .wFull()
        .hFull()
        .fill(theme.surface().window())
        .padding(theme.space().m())
        .child(groups
            .tabs(
                frame,
                note -> new Tabs.Tab(note.title, true, false).icon(Symbol.DOCUMENT),
                note -> note == null // a group without tabs
                    ? null
                    : col().padding(theme.space().l()).child(Label.label(note.text)))
            .grow(1));
  }
  // docs:end tab-groups

  // docs:start window-closed
  final Map<Windows.Handle, String> searches = new HashMap<>(); // what each window searches for

  void track(Windows.Handle window) {
    searches.put(window, "");
    window.onClosed(() -> closed(window));
  }

  void closed(Windows.Handle window) { // however it closed, in a window still open
    searches.remove(window);
  }
  // docs:end window-closed

  static App<Notebook> app() {
    Notebook state = new Notebook();
    state.app = new App<>(state, notebook -> notebook.view(notebook.groups.main()))
        .provide(Theme.CURRENT, notebook -> notebook.theme);
    return state.app;
  }
}
