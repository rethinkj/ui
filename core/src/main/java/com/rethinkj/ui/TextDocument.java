package com.rethinkj.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared editing state of one text shown in several fields: the text as last answered by the
 * app, its revision, the undo and redo history, any edit waiting for the app's answer, and the
 * recent {@link TextChange}s so other views can map their caret and lines by the real edit.
 *
 * <p>Keep one in your app state and pass it to {@link FieldState#FieldState(TextDocument)} for each
 * field that shows the text (the app passes the same string to every view, as today):
 *
 * <pre>{@code
 * final TextDocument document = new TextDocument();
 * final FieldState left = new FieldState(document);
 * final FieldState right = new FieldState(document);
 * }</pre>
 *
 * <p>All views of one document share a single undo and redo history, while each view keeps its own
 * caret, selection, scroll offsets, folds, blocks and input-method composition. A field whose
 * {@link FieldState} was constructed without a document keeps a private one of its own.
 */
public final class TextDocument {

  /** The most answered changes kept for views that were out of the tree to catch up by. */
  static final int CHANGE_LIMIT = Editor.HISTORY * 2;

  /**
   * A step of the history: {@code removed} at {@code at} replaced by {@code inserted}, with the
   * caret and the anchor before and after.
   */
  record Step(
      int at,
      String removed,
      String inserted,
      int caretBefore,
      int anchorBefore,
      int caretAfter,
      int anchorAfter) {}

  /** One edit applied to produce the pending proposal. */
  record PendingEdit(int at, int removed, String inserted) {}

  /** One answered change to the document, tagged with the revision it produced. */
  record Change(long revision, TextChange range, String inserted, boolean external) {}

  // The text as last answered by the app; null until the first editor initializes it.
  String text;
  long revision;

  ArrayDeque<Step> undo = new ArrayDeque<>();
  ArrayDeque<Step> redo = new ArrayDeque<>();
  // Coalescing runs belong to one view so typing in view A then view B makes two undo steps.
  Editor runOwner;
  Editor.Run run;

  // The state before the first edit that no tree has answered yet, the history as it was then, and
  // what the last edit proposed.
  Editor proposer;
  String beforeText;
  int beforeCaret;
  int beforeAnchor;
  ArrayDeque<Step> undoBefore;
  ArrayDeque<Step> redoBefore;
  String proposed;
  List<PendingEdit> pendingEdits;

  private final ArrayDeque<Change> changes = new ArrayDeque<>();
  // False for the document of a field without one of the app's: it has no other view to log for.
  boolean shared = true;

  /** Creates a document with no text yet; the first field that attaches initializes it. */
  public TextDocument() {}

  /** Initializes the document from the first editor's text if it has none yet. */
  void initialize(String initial) {
    if (text == null) {
      text = initial;
      revision = 0;
    }
  }

  /** Whether an edit waits for the app's answer, the string of the next tree. */
  boolean answerPending() {
    return proposer != null;
  }

  /** Ends the coalescing run if {@code owner} owns it. */
  void clearRun(Editor owner) {
    if (runOwner == owner) {
      run = null;
      runOwner = null;
    }
  }

  /** Ends any coalescing run. */
  void clearRun() {
    run = null;
    runOwner = null;
  }

  /** Sets the active coalescing run for {@code owner}, or clears it when {@code kind} is null. */
  void setRun(Editor owner, Editor.Run kind) {
    run = kind;
    runOwner = kind == null ? null : owner;
  }

  /** Whether an edit of {@code kind} by {@code owner} can coalesce with the last undo step. */
  boolean canCoalesce(Editor owner, Editor.Run kind) {
    return kind != null && kind == run && runOwner == owner;
  }

  /**
   * Keeps the history as it is before the first edit, undo or redo that no tree has answered yet,
   * for the app to refuse: each of them changes the history before it proposes the string.
   */
  void keepHistory() {
    if (proposer == null) {
      undoBefore = new ArrayDeque<>(undo);
      redoBefore = new ArrayDeque<>(redo);
    }
  }

  /** Records the start of a proposal (or another edit before the build) from {@code editor}. */
  void beginProposal(
      Editor editor,
      String textBefore,
      int caretBefore,
      int anchorBefore,
      int at,
      int removed,
      String inserted) {
    if (proposer == null) {
      proposer = editor;
      beforeText = textBefore;
      beforeCaret = caretBefore;
      beforeAnchor = anchorBefore;
      pendingEdits = new ArrayList<>(1);
    }
    pendingEdits.add(new PendingEdit(at, removed, inserted));
  }

  /**
   * Resolves the app's string {@code value} against any pending proposal, or records an external
   * change when the app replaced the string on its own. May be called by whichever view of the
   * document attaches first in a build.
   */
  void resolve(String value) {
    if (proposer != null) {
      Editor p = proposer;
      String before = beforeText;
      int bc = beforeCaret;
      int ba = beforeAnchor;
      String prop = proposed;
      List<PendingEdit> edits = pendingEdits;
      ArrayDeque<Step> ub = undoBefore;
      ArrayDeque<Step> rb = redoBefore;
      clearProposal();
      // The answer is usually the proposal itself, which the first comparison finds at once.
      if (!value.equals(prop) && value.equals(before)) {
        // Refused: as if the edits had not happened.
        undo = ub;
        redo = rb;
        clearRun();
        text = value;
        p.restored(value, bc, ba, revision);
      } else if (value.equals(prop)) {
        // Accepted: record the real edits so every other view updates its caret and lines by them.
        recordEdits(edits);
        text = value;
        p.accepted(value, revision);
      } else {
        // Changed: one step from before the edits to what the app made of them.
        TextChange fromProposed = TextChange.between(prop, value);
        int caretAfter = fromProposed.map(p.caret());
        int anchorAfter = fromProposed.map(p.anchor());
        undo = ub;
        redo = new ArrayDeque<>();
        TextChange fromBefore = TextChange.between(before, value);
        String inserted = value.substring(fromBefore.start(), fromBefore.newEnd());
        push(
            undo,
            new Step(
                fromBefore.start(),
                before.substring(fromBefore.start(), fromBefore.oldEnd()),
                inserted,
                bc,
                ba,
                caretAfter,
                anchorAfter));
        clearRun();
        // The real edits, then what the app changed of them, for the other views to follow.
        recordEdits(edits);
        revision++;
        recordChange(new Change(
            revision,
            fromProposed,
            value.substring(fromProposed.start(), fromProposed.newEnd()),
            false));
        text = value;
        p.changed(value, caretAfter, anchorAfter, revision);
      }
    } else if (!value.equals(text)) {
      // Changed by the app on its own: the history no longer leads to it, for all views.
      TextChange change = TextChange.between(text, value);
      undo.clear();
      redo.clear();
      clearRun();
      revision++;
      // Views re-line after it, so it keeps no copy of the inserted text.
      recordChange(new Change(revision, change, null, true));
      text = value;
    }
  }

  /**
   * The answered changes after {@code sinceRevision} up to {@link #revision}, in order; empty when
   * up to date, or {@code null} if earlier changes were already evicted from the log.
   */
  List<Change> changesSince(long sinceRevision) {
    if (sinceRevision >= revision) {
      return List.of();
    }
    if (changes.isEmpty() || changes.peekFirst().revision() > sinceRevision + 1) {
      return null;
    }
    List<Change> out = new ArrayList<>();
    for (Change change : changes) {
      if (change.revision() > sinceRevision) {
        out.add(change);
      }
    }
    return out;
  }

  /** Ends a pending proposal or run owned by a dropped editor. */
  void drop(Editor editor) {
    if (proposer == editor) {
      clearProposal();
    }
    clearRun(editor);
  }

  static void push(ArrayDeque<Step> history, Step step) {
    history.push(step);
    while (history.size() > Editor.HISTORY) {
      history.removeLast();
    }
  }

  private void recordEdits(List<PendingEdit> edits) {
    for (PendingEdit edit : edits) {
      revision++;
      recordChange(new Change(
          revision,
          new TextChange(
              edit.at(), edit.at() + edit.removed(), edit.at() + edit.inserted().length()),
          edit.inserted(),
          false));
    }
  }

  private void recordChange(Change change) {
    if (!shared) {
      return; // one view, which never catches up by the log
    }
    changes.addLast(change);
    while (changes.size() > CHANGE_LIMIT) {
      changes.removeFirst();
    }
  }

  private void clearProposal() {
    proposer = null;
    beforeText = null;
    undoBefore = null;
    redoBefore = null;
    proposed = null;
    pendingEdits = null;
  }
}
