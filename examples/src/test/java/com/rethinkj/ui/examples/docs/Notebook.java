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
import java.util.List;

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

  final Theme t = Theme.DARK;
  App<Notebook> app; // the first window's app
  final TabGroups<Note> groups = new TabGroups<>(
      List.of(new Note("Groceries", "Milk"), new Note("Ideas", "A tree of tables")),
      this::open);

  /** Opens the window of a group of tabs that left the first window. */
  Windows.Handle open(TabGroups.Group<Note> group, Window.Options options) {
    return app.windows().open(group.activeTab().title, 420, 260, options,
        new App<>(this, s -> s.view(group)));   // the same state: a change shows in all windows
  }

  /** The view of a window: the first one shows groups.main(). */
  El view(TabGroups.Group<Note> group) {
    return col().wFull().hFull().fill(t.surface().window()).padding(t.space().m())
        .child(groups.tabs(t, group,
            note -> new Tabs.Tab(note.title, true, false).icon(Symbol.DOCUMENT),
            note -> col().padding(t.space().l()).child(Label.label(t, note.text)))
            .grow(1));
  }
  // docs:end tab-groups

  static App<Notebook> app() {
    Notebook state = new Notebook();
    state.app = new App<>(state, s -> s.view(s.groups.main()));
    return state.app;
  }
}
