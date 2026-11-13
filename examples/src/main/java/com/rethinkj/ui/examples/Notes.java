package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.kit.Card.card;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TextField.textArea;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.Drag;
import com.rethinkj.ui.Drop;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import com.rethinkj.ui.window.Windows;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Notes as cards in a row, which tear off into windows of their own and dock back: drag a card's
 * title out of the window and the note becomes a window under the pointer that follows it until
 * the button is released, while the row keeps a muted card in its place; drag a title back over
 * the row, where a marker shows where it goes, and the note docks there; drag a title along the
 * row to reorder; close a note's window to dock the note where its muted card is. Every window
 * shows the same notes: an edit in a note's window shows in its muted card at once, since all
 * apps share one state ({@link Windows}).
 */
public final class Notes {

  static final Theme THEME = Theme.DARK;
  static final float CARD = 180; // a card's width
  static final float NOTE_WIDTH = 260; // a note's window
  static final float NOTE_HEIGHT = 200;

  /** A note, docked in the row or in a window of its own. */
  static final class Note {
    final String title;
    String text;
    Windows.Handle window; // null while it is docked

    Note(String title, String text) {
      this.title = title;
      this.text = text;
    }
  }

  App<Notes> app; // the main window's
  final List<Note> row = new ArrayList<>(); // every note in order, docked or in its window
  int marker = -1; // where a drag over the row would put its note, or -1
  float grabX; // where the pointer holds the note's title, from the card's top left
  float grabY;

  Notes() {
    for (Note note : List.of(
        new Note("Ideas", "Tabs that tear off\nand dock back"),
        new Note("Groceries", "Coffee, bread"),
        new Note("Todo", "Write the release notes"))) {
      row.add(note);
    }
  }

  public static void main(String[] args) throws IOException {
    Notes notes = new Notes();
    notes.app = new App<>(notes, Notes::view);
    // A script's images are stored, so they must not depend on the platform.
    notes.app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, notes.app)) {
      return;
    }
    notes.app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(Notes.class, "Notes", 0xFF7C3AED, Symbol.EDIT);
    try (Window window = Window.open("Notes", 640, 300, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop loop = new WindowLoop(window, notes.app, presenter);
      // Closing the row's window closes the notes' windows too, and with them the loop.
      loop.windows().of(notes.app).onCloseRequest(() -> {
        for (Windows.Handle open : loop.windows().all()) {
          open.close();
        }
      });
      loop.run();
    }
  }

  // ---- The row

  static El view(Notes s) {
    Theme t = THEME;
    El cards = row().key("row").grow(1).gap(t.space().m()).padding(t.space().l())
        .onDrop(d -> s.overRow(d));
    for (int i = 0; i <= s.row.size(); i++) {
      if (i == s.marker) {
        cards.child(el().key("marker").w(t.size().stroke() * 3).hFull()
            .radius(t.radius().small()).fill(t.accent().color()));
      }
      if (i < s.row.size()) {
        Note note = s.row.get(i);
        El card = note.window != null
            ? card(t, Card.Variant.FILLED, // in its own window: shown, not edited, here
                label(t, Label.Variant.MUTED, note.title),
                label(t, Label.Variant.MUTED, note.text).key("text-" + note.title).wrapText())
            : card(t, handle(t, note, s), textArea(t, note.text, "Empty", text -> note.text = text)
                .key("text-" + note.title).wFull().grow(1));
        cards.child(card.key("card-" + note.title).w(CARD).shrink(0));
      }
    }
    return col().wFull().hFull().fill(t.surface().window())
        .child(row().padding(t.space().l(), t.space().l(), 0, t.space().l())
            .child(label(t, Label.Variant.HEADING, "Notes")))
        .child(cards);
  }

  /** A note's title, which drags the note: along the row, out of the window, back. */
  static El handle(Theme t, Note note, Notes s) {
    return row().key("handle-" + note.title).cursor(Cursor.POINTER)
        .child(label(t, Label.Variant.STRONG, note.title))
        .dragPayload(note)
        .onDrag(d -> s.drag(note, d));
  }

  /** The drop handler of the row: the marker follows the pointer, a drop puts the note there. */
  void overRow(Drop d) {
    if (!(d.payload() instanceof Note note)) {
      return;
    }
    switch (d.phase()) {
      case OVER -> marker = slot(d.x());
      case LEAVE -> marker = -1;
      case DROP -> {
        int at = slot(d.x());
        marker = -1;
        int from = row.indexOf(note);
        row.remove(from);
        row.add(from < at ? at - 1 : at, note);
        if (note.window != null) { // it came out of its window, which goes
          note.window.close();
          note.window = null;
        }
      }
    }
  }

  /** The index in the row before which a note dropped at {@code x} would go. */
  int slot(double x) {
    Theme t = THEME;
    float left = t.space().l();
    int at = 0;
    for (int i = 0; i < row.size(); i++) {
      float centre = left + i * (CARD + t.space().m()) + CARD / 2;
      if (x > centre) {
        at = i + 1;
      }
    }
    return at;
  }

  /**
   * A step of a drag of a note's title. Out of the window, the note leaves the row and opens as a
   * window under the pointer, held where the title was, which follows each step; the drag goes on
   * without the card, since it carries the note.
   */
  void drag(Note note, Drag d) {
    Theme t = THEME;
    boolean moving = d.phase() == Drag.Phase.START || d.phase() == Drag.Phase.MOVE;
    if (d.phase() == Drag.Phase.START) {
      // The title lies the card's padding inside the card, as in the note's own window.
      grabX = (float) d.x() - d.dx() + t.space().l();
      grabY = (float) d.y() - d.dy() + t.space().l();
    }
    if (moving && d.outside() && note.window == null) {
      tearOff(note, d);
    } else if (moving && note.window != null) {
      follow(note.window, d);
    } else if (d.phase() == Drag.Phase.END && note.window != null) {
      note.window.dropTarget(true); // it stays where it was let go
    } else if (d.phase() == Drag.Phase.CANCEL) {
      marker = -1;
    }
  }

  void tearOff(Note note, Drag d) {
    App<Notes> noteApp = new App<>(this, s -> s.noteView(note));
    noteApp.textRendering(app.textRendering());
    float scale = app.windows().of(app).screenScale(); // the new window's, unless it differs
    note.window = app.windows().open(note.title, NOTE_WIDTH, NOTE_HEIGHT,
        Identity.options(Notes.class, "Notes", 0xFF7C3AED, Symbol.EDIT)
            .position(Math.round(d.screenX() - grabX * scale),
                Math.round(d.screenY() - grabY * scale)),
        noteApp);
    note.window.dropTarget(false); // it lies under the pointer, over the row it may go back to
    note.window.onCloseRequest(() -> { // closing it docks the note where its muted card is
      if (note.window != null) {
        note.window.close();
        note.window = null;
      }
    });
  }

  void follow(Windows.Handle window, Drag d) {
    float scale = window.screenScale();
    window.moveTo(Math.round(d.screenX() - grabX * scale), Math.round(d.screenY() - grabY * scale));
  }

  // ---- A note's window

  El noteView(Note note) {
    Theme t = THEME;
    return col().wFull().hFull().fill(t.surface().window()).padding(t.space().l())
        .gap(t.space().l())
        .child(label(t, Label.Variant.STRONG, note.title).key("title"))
        .child(textArea(t, note.text, "Empty", text -> note.text = text).key("text").wFull()
            .grow(1));
  }
}
