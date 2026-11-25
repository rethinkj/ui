package com.rethinkj.ui;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.render.CpuRenderer;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.text.Atlas;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Windows;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * A running app: a state object and a view function that builds the element tree from it.
 *
 * <p>The view only reads the state. Input waits in a queue, in the order it came, until {@link
 * #frame} dispatches it: each input finds the element it goes to and runs that element's handler,
 * which may change the state, and then the frame builds and lays out the view again. Without input
 * nothing changes but movements under way.
 *
 * <p>Each input is dispatched against a tree built from the state as it is when its turn comes:
 * it is hit tested, overlays first, and finds its element only then, not when it arrives. When a
 * handler of the app has taken an input and more input of the same frame waits, the frame builds
 * the view again first, gives it the looks of the moment and its fields their editing state, lets
 * the overlays that open and close move the focus, lays it out and places its overlays. The next
 * input then finds its element in that tree and runs that element's handler, a closure of the new
 * view: two ArrowDown in one frame move a selection by two rows, a key after the press that
 * opened a menu goes to the menu, a press after one that dismissed a menu finds no menu there, and
 * a second edit of a field builds on what the app made of the first. Handlers of the app are
 * those of presses, secondary presses (also from the Menu key), drags, keys, the wheel, text and
 * edits, and the {@link El#onDismiss} handlers that a press or the wheel outside an overlay calls; all of
 * them run in the order of the input, each in the tree the one before left. The rows of the
 * virtual lists in such a tree, those inside overlays too, and its scrollbars are built before an
 * input that needs them: a press, a release, a secondary press, Tab, a focus or a scroll the app
 * asks for, and a key while the focus is in a row; the wheel and drags build them too, but not
 * the rows that their own scrolling brings into view. Other keys do with the rows that exist, so
 * a key handler there sees its list without rows. Input that no handler of the app took costs no
 * rebuild: a pointer move, a key that nobody handles or that only moves a field's caret, a focus
 * moved by Tab, a wheel that dismisses nothing and that no wheel handler takes. A key or wheel
 * handler that returns false counts as having changed nothing. The {@link El#onHoverFor} handlers are not input: the frame runs them after
 * all input, once the pointer has rested long enough on the tree as it is then.
 *
 * <p>The view only states where things end up. When a rebuild gives an element with {@link
 * El#motion} new values, the app springs it there over the following frames, without running the
 * view again. Time is the app's own clock, which only {@link #advance} moves; the tree always holds
 * the values at that time, so drawing and hit testing see what is on screen.
 *
 * <p>The app also keeps the offset of each scroll area ({@link El#scroll}), by the same identity
 * that matches elements across rebuilds, and writes it into the positions of the area's children.
 * The wheel and {@link #scrollTo} change it.
 *
 * <p>A virtual list ({@link El#rows}) gets its rows from the app: after the view is built and laid
 * out, the app builds the rows the list shows and lays them out, and when scrolling shows a row
 * that does not exist yet, it builds the list's rows again without running the view.
 *
 * <p>Keys and text go to the element that has the keyboard focus, which the app keeps by the same
 * identity: an element marked {@link El#focusable} gets it from Tab and Shift+Tab, from a pointer
 * press on it or inside it, or from {@link #focus}. Keys then go up through its ancestors until a
 * handler takes them ({@link El#onKey}); text goes to it alone ({@link El#onText}, {@link
 * El#onTypeAhead}). When the keyboard or the app moved the focus, the scene draws a ring around
 * the focused element, and scroll areas around it scroll it into view. When a rebuild leaves the focused element out, the
 * focus goes to the nearest element around where it was that can take it, as {@link #focused}
 * says, so that keys keep reaching the handlers around it.
 *
 * <p>The app also follows the pointer ({@link #pointerMove}, {@link #pointerLeave}): it knows the
 * element under it ({@link #hovered}), the one a press is on ({@link #pressed}) and the {@link
 * #cursor} they ask for. Elements change how they look in these states with {@link
 * El#whenHovered}, {@link El#whenFocused}, {@link El#whenFocusWithin} and {@link El#whenPressed}:
 * the view says how, and the app applies the looks of the moment to each tree the view builds,
 * before layout. A state that
 * changes for elements without such a look rebuilds nothing. A press that moves on an element
 * with {@link El#onDrag} becomes a drag, during which that element counts as the one under the
 * pointer; so does a text field while a press in it selects. A press that lands in a field selects
 * text and never drags an element around it.
 *
 * <p>The pointer finds its element by descending from the root: at each element the last child
 * whose rounded border box holds the point, since later children are drawn on top, then its
 * children in the same way. A child that sticks out of its parent's box is therefore hit only
 * inside that box, also where the parent does not clip; such a child is drawn whole but takes
 * the pointer only where its parent does. A view that wants a part to take the pointer beyond its
 * neighbor's edge gives it a parent that covers that area, as the kit's split puts its divider's
 * strip and the second part in one box. This keeps hit testing a single walk down the tree,
 * in proportion to its depth and the children along the way, however large the tree.
 *
 * <p>A scroll area with {@link El#scrollbars} gets its bars from the app, which places them after
 * each frame from the area's size, content and offset, over the content. The bars come first for
 * the pointer: a press on one is the bar's, never a press, drag or selection of what lies under it
 * or around it, and it leaves the focus alone. Dragging a thumb scrolls from the first pixel, with
 * no threshold, and the area counts as the one under the pointer meanwhile; a press on the track
 * beside the thumb scrolls a page. The thumb under the pointer and the dragged one change color,
 * which draws a frame but rebuilds nothing.
 *
 * <p>A text field ({@link El#editable}) keeps its caret, selection, scroll, undo history and
 * composition here too, by the same identity. Its caret blinks on the app's clock while it has the
 * focus: {@link #frame} draws a new frame when it turns on or off, and {@link #nextChange} says
 * when that is. Cut, copy and paste go through the app's {@link #clipboard}. A field that loses
 * the focus to another element, or to none, shows the start of its text and no selection, as
 * browsers do, and forgets how far it was scrolled; when the focus comes back, the text scrolls
 * just far enough to show the caret. A {@link El#multiline multi-line} field is a scroll area and
 * keeps its scroll. Losing the focus of the window is not that: the field keeps
 * the app's focus, its scroll, caret and selection, and only stops drawing the caret until the
 * window has the focus again.
 *
 * <p>This class needs no window. A windowed runner feeds it the window's input and draws its
 * {@link #scene}; tests and agents feed it input themselves and look at {@link #render}.
 *
 * <p>An app is used by one thread, the one that runs it, and is not safe for others: the state,
 * the elements and every method but one belong to that thread. The one is {@link #post}, with
 * which any thread hands the app a task that runs on the app's thread at its turn in the queue,
 * as one more input whose handler is the app's: the way back for the result of a query that ran
 * elsewhere, so that the window does not stop for it. {@link #background} runs a computation on a
 * virtual thread and posts its result or failure, unless it was cancelled, and with a {@link
 * Work.Reporting} computation also its progress; {@link #await} waits for that work, and {@link
 * #awaitProgress} for its next report, for scripts and tests.
 *
 * @param <S> the type of the state
 */
public final class App<S> {

  /** The color of the focus ring until {@link #focusRing} changes it, as 0xAARRGGBB. */
  public static final int FOCUS_RING = Colors.rgb(0x93C5FD);

  /** How long the caret of a focused field is on, and then off, in seconds of the app's clock. */
  public static final double BLINK = 0.5;

  /**
   * How long, at least, the {@link El#whenPressed} look of an element that Enter or Space pressed
   * shows, in seconds of the app's clock from the key going down, when the key comes up in a later
   * frame than the one that pressed. A key held longer shows the look until it is up; one that goes
   * down and up in the same frame, as a script's {@code key} step, shows none.
   */
  public static final double KEY_PRESS = 0.1;

  /**
   * How long after a character typed into an element with an {@link El#onTypeAhead} handler the
   * next one still adds to the same text, in seconds of the app's clock.
   */
  public static final double TYPE_AHEAD = 1;

  // Edge auto-scroll while a selection drag is held near or past a scrolling scope's top/bottom
  // edge, in logical pixels and logical pixels per second.
  private static final float EDGE_SCROLL_MARGIN = 24;
  private static final float EDGE_SCROLL_MIN_SPEED = 120;
  private static final float EDGE_SCROLL_MAX_SPEED = 1600;
  private static final float EDGE_SCROLL_RAMP = 120;

  private boolean cycling; // whether the last frame showed an element with an El#cycle
  private boolean edgeScrolling; // whether a held selection drag is auto-scrolling its scope's list

  /**
   * Where the focused element that takes text is, in logical pixels of the window, for the
   * system's input method: it places its candidate window below this box, and its composition at
   * the caret.
   *
   * @param cursor the caret's distance from {@code x}
   * @param password whether the element is a {@link El#masked masked} field that is not {@link
   *     El#revealed revealed}, whose text the system should neither show nor remember
   */
  public record InputArea(
      float x, float y, float width, float height, float cursor, boolean password) {}

  /**
   * How long the parts of a {@link #frame} took, in nanoseconds of {@link System#nanoTime}; they
   * do not overlap and add up to the whole frame. A frame first handles the input and the tasks
   * that arrived, one after the other, and then makes what it shows of the state they left: what
   * the frame does before that line is input, what it does after it is layout, and the view and
   * row functions are build wherever they run.
   *
   * @param input handling the input and the tasks: their handlers, finding their elements, and
   *     what the next of them needs, as a tree laid out anew after a handler changed the state
   * @param build running the view function and the row functions of the virtual lists
   * @param layout the rest, after the input: laying out, the looks of the moment, motion,
   *     overlays, carets and bars, and the handlers the frame runs on its own, as {@link
   *     El#onResize}, {@link El#onShownFor} and {@link El#onHoverFor}
   */
  public record Timing(long input, long build, long layout) {}

  private final S state;
  private Function<S, El> view;
  // The queue of input, posted tasks, background work and timers.
  final Scheduler scheduler = new Scheduler(() -> handled = true);
  // The El#onResize handlers the last layout told a new size, by the element's identity, which
  // rebuild runs before the frame draws, and how many times a frame builds again for them. What
  // they ask for, such as a focus, runs first in the next frame, in the order asked (Scheduler).
  private final Map<Object, BooleanSupplier> resized = new LinkedHashMap<>();
  private static final int RESIZE_ROUNDS = 3;
  private int resizeRounds; // in this frame
  // A build before the frame's last gave other sizes, or a view did not settle: the next rebuild
  // tells them.
  private boolean resizeDue;
  private boolean handled; // a handler of the app took input since the tree was built
  private boolean keyUntaken; // nothing took the key that keyDownAt dispatched last
  private boolean keyScroll; // the input last run was a key-down: its reveals are the user's
  private boolean focusByKey; // a key moved the focus, or asked to: revealing it is the user's
  private final List<Object> revealedScrolls = new ArrayList<>(); // areas such a reveal moved
  // The focused element took text when the last key was dispatched: its text goes in (keyText).
  private boolean keyToText;
  private boolean scrolled; // something scrolled since the rows and bars were placed
  private boolean caretOverlays; // an overlay is placed beside a caret: see El#atCaret
  private boolean rowsPending; // the tree was built between two inputs without its lists' rows
  // The next build between inputs builds the rows too: a field, maybe in one, waits for the app's
  // answer to an edit an app asked for, or told its selection, after which actions may look for it.
  private boolean rowsKept;
  // Fields whose FieldState holds edits asked before their first build, in the order attached:
  // they go in once the tree that attached them is complete, before the next input (editFirst).
  private final List<FieldState> firstEdits = new ArrayList<>();
  // A list with measured row heights still wants rows after the passes of the last fillRows:
  // the next frames build them, and nextChange asks for those frames.
  private boolean rowsWanted;
  // The lines a frame may ask highlighters about, across its fields; a field left with plain lines
  // in view catches up in the next frames, which nextChange asks for.
  private final Lines.Budget highlightBudget = new Lines.Budget();
  private boolean rowKeyHooks; // the rows the lists built last have an El#onAnyKeyDown handler
  // The row each list was last asked to scroll to (scrollToRow), by identity, until the user's
  // next press, key or wheel: content that grows meanwhile takes the row to the top after all.
  private final Map<Object, RowGoal> rowGoals = new HashMap<>();

  /** A row {@link #scrollToRow} sent a list to, with the version and key of its keyed rows. */
  private record RowGoal(int row, int version, Object key, int field) {
    RowGoal(int row, int version, Object key) {
      this(row, version, key, -1);
    }
  }

  private boolean untimed; // the tree was built and timeOverlays has not looked at it yet
  private final Animator animator = new Animator();
  private double time;
  private float width;
  private float height;
  private float scale = 1;
  private boolean stale = true;
  private El root;
  private Capture capture = IDLE; // what the pointer holds between down and up
  private Object keyPressed; // the element Enter or Space pressed, while its pressed look shows
  private String keyPressKey; // the key that pressed it, until it is up
  private long keyPressFrame; // the frame that dispatched that key
  private double keyPressUntil; // once the key is up: when the look ends
  private long frames; // the frames run, for keyPressFrame
  private long buildNanos; // the view and row functions took during the frame that runs
  private Timing timing = new Timing(0, 0, 0);
  private final Hover hover = new Hover(); // where the pointer is and what it is over
  private Looks shown = new Looks(List.of(), List.of(), null, false, List.of(), null, 0); // of tree
  private final Set<Object> relooking = new HashSet<>(); // lists that build their rows anew
  private final Focus focus = new Focus(animator); // the keyboard focus
  private Object toldFocus; // the focus identity last told to El#onFocusIn handlers
  private FieldState toldState; // and its field's FieldState, which keeps it when the field moves
  private Object[] toldScope; // the scope, caret, anchor and text last told to its El#onSelect
  // The state changed outside the app's handlers (invalidate): build the view before the next
  // input.
  private boolean changedOutside;
  // The rows each list with an onRowsBuilt handler told last, first, last and their number, by the
  // list's identity, for the lists the last fillRows found; a list that comes back tells again.
  private final Map<Object, ToldRows> toldRows = new HashMap<>();
  private final Set<Object> rowLists = new HashSet<>(); // those it found, while it walks
  // Likewise the rows in view each list with an onRowsInView handler told last, and those lists
  // as the last walk of fillRows found them, to tell once the frame has settled.
  private final Map<Object, Long> toldInView = new HashMap<>();
  private final Map<Object, El> inViewLists = new LinkedHashMap<>();
  // The onRowsBuilt handlers due of the lists that also have an onRowsInView one, by identity: they
  // run with that one at the end of the frame, as one input with one rebuild.
  private final Map<Object, Runnable> heldRows = new HashMap<>();
  // The sizes the El#onResize handlers were told, by identity, of the elements of the last build.
  private Map<Object, Long> toldSizes = new HashMap<>();
  private final Atlas atlas = new Atlas();
  private TextRendering textRendering = TextRendering.platform();
  private Scene scene;
  private long imageMemory = Scene.DEFAULT_IMAGE_MEMORY;
  private boolean systemFonts;
  private final Map<Object, Editor> editors = new HashMap<>(); // the fields' state, by identity
  // Editors with a FieldState displaced from their identity during attachEditors; sweepEditors
  // leaves the ones whose field did not re-attach under another identity.
  private final Set<Editor> displaced = Collections.newSetFromMap(new IdentityHashMap<>());
  private Clipboard clipboard = Clipboard.inMemory();
  private Dialogs dialogs = Dialogs.NONE;
  private Browser browser = Browser.NONE;
  private Object fileOver; // the identity of the element the system's drag is over, or null
  private Consumer<Drop> fileOverHandler; // its onFileDrop, which gets the LEAVE
  private Windows windows = Windows.NONE;
  private boolean ran; // a handler ran in this frame; see ranHandlers()
  private Editor blinking; // the editor of the focused field
  private ScopeSelection scopeSelection; // active selection across read-only fields in a scope
  private ScopeSelection keptScope; // the scope's last selection once it ended; onSelect follows it
  private final Map<El, ScopeIndex> scopeIndexes = new IdentityHashMap<>(); // see scopeIndex
  // Cached field identity -> whole-width ordinal for rows of spans lists, cleared per build/frame.
  private final Map<El, Map<Integer, Map<Object, Integer>>> rowOrdinalCache =
      new IdentityHashMap<>();
  // By list in a row of a scope's list and row: its fields; see fieldsInRow. With a version, by
  // the list's heights and the row's key: the count and the version it is for.
  private final Map<El, Map<Integer, Integer>> unbuiltFieldCounts = new WeakHashMap<>();
  private final Map<RowHeights, Map<Object, Object[]>> keptFieldCounts = new WeakHashMap<>();
  private final Map<El, Boolean> prunedCounts = new WeakHashMap<>(); // lists whose gone keys went
  private final Map<RowHeights, Map<Integer, Integer>> buildFieldCounts = new IdentityHashMap<>();
  private long countsBuild = -1; // the build buildFieldCounts are for
  long builds; // the number of the view's last build, for the fields' FieldState; tests read it
  private El typingSoon; // a field of the last tree that a press not dispatched yet lands in
  private double blinkStart; // when its caret last turned on
  private boolean caretOn;
  private boolean active = true; // the window has the system's keyboard focus
  private boolean maximized; // the window is maximized
  private Object secondary; // the identity of the element a secondary press is on, until it is up
  private FieldMenu.Open fieldMenu; // the menu of a field that is open, El#fieldMenu
  // How long each shown element with an onShownFor handler has been shown, by identity.
  private final Map<Object, Shown> shownTimes = new HashMap<>();
  private double shownDue = Double.POSITIVE_INFINITY; // when the next of those handlers is

  /**
   * How long a shown element with an {@link El#onShownFor} handler has been shown, pausing while
   * hovered when {@link El#pauseShownOnHover} is set.
   */
  private static final class Shown {
    double elapsed; // seconds counted while not paused; POSITIVE_INFINITY once the handler ran
    double since; // time when the current unpaused stretch began, or NaN while paused

    Shown(double time) {
      this.since = time;
    }

    double due(double delay) {
      if (elapsed == Double.POSITIVE_INFINITY || Double.isNaN(since)) {
        return Double.POSITIVE_INFINITY;
      }
      return since + Math.max(0, delay - elapsed);
    }

    void pause(double time) {
      if (!Double.isNaN(since)) {
        if (elapsed < Double.POSITIVE_INFINITY) {
          elapsed += Math.max(0, time - since);
        }
        since = Double.NaN;
      }
    }

    void resume(double time) {
      if (Double.isNaN(since) && elapsed < Double.POSITIVE_INFINITY) {
        since = time;
      }
    }

    void done() {
      elapsed = Double.POSITIVE_INFINITY;
      since = Double.NaN;
    }
  }
  // Since when the elements under the pointer with whenHoveredFor or onHoverFor have been there.
  private final Map<Object, Rest> resting = new HashMap<>();
  private double restDue = Double.POSITIVE_INFINITY; // when the next of their looks or handlers is
  // When a whenHoveredFor look that attached overlays, such as a tooltip, last went because the
  // pointer left its element; a stay that begins within its delay of that shows its look at once.
  private double lookLeft = Double.NEGATIVE_INFINITY;
  private int hoversRun; // onHoverFor, onHover and text hover handlers run so far
  // Elements currently hovered that have an El#onHover handler, by identity.
  private final Set<Object> hoveredCallbacks = new HashSet<>();
  // Multi-line fields under the pointer with text pointer/hover handlers, by identity.
  private final Map<Object, TextHover> textHovered = new HashMap<>();

  private static final int NOT_PRESSED = Integer.MIN_VALUE;

  /** Per-field state for {@link El#onTextPointerMove} and {@link El#onTextHoverFor}. */
  private static final class TextHover {
    int position = -1;
    int modifiers;
    double since = Double.POSITIVE_INFINITY;
    boolean ran;
    // The position a press suppressed the hover at, -1 off the text, until the pointer leaves it.
    int pressedAt = NOT_PRESSED;
  }

  /**
   * Since when the pointer has been over an element; whether its onHoverFor handler has run;
   * whether its whenHoveredFor look shows at once, as another one's has just gone; the overlays
   * that look attached to the tree on screen.
   */
  private static final class Rest {
    double since;
    boolean ran;
    boolean warm;
    List<El> attached = List.of();

    Rest(double since) {
      this.since = since;
    }
  }

  public App(S state, Function<S, El> view) {
    this.state = state;
    this.view = view;
  }

  public S state() {
    return state;
  }

  /**
   * Gives the view a {@link Context}'s value, which it takes from the state each time it builds
   * the view: {@code app.provide(Theme.CURRENT, Settings::theme)} gives the kit's components the
   * theme the settings chose, and a new choice shows with the next frame, as any change of the
   * state does. Where the view gives the context another value itself ({@link Context#with}),
   * that holds, and of two calls for one context the first does, which lies nearer the view.
   *
   * @return this app
   */
  public <T> App<S> provide(Context<T> context, Function<? super S, ? extends T> value) {
    Function<S, El> inner = view;
    view = state -> context.with(value.apply(state), () -> inner.apply(state));
    return this;
  }

  /**
   * Sets the size of the window in logical pixels and its scale in physical pixels per logical
   * pixel.
   */
  public void resize(float width, float height, float scale) {
    this.width = width;
    this.height = height;
    this.scale = scale;
    stale = true;
  }

  /**
   * The pointer moved to this position over the window, in logical pixels. From the next {@link
   * #frame} on, the topmost element there that takes input is {@link #hovered}, and with it its
   * ancestors for {@link El#whenHovered}. Presses do not move the pointer. While the button is
   * held on an element with an {@link El#onDrag} handler, this also starts or moves the drag,
   * wherever the position is, outside the window too; while it is held on a text field, this
   * selects in the field, without a threshold; while it is held on a scrollbar's thumb, this drags
   * the thumb, from the first pixel.
   */
  public void pointerMove(float x, float y) {
    enqueue(() -> move(x, y, 0));
  }

  /**
   * Like {@link #pointerMove(float, float)}, with the modifiers held as the window reports them,
   * a combination of {@link Key#SHIFT}, {@link Key#CONTROL}, {@link Key#ALT} and {@link Key#META}.
   */
  public void pointerMove(float x, float y, int modifiers) {
    enqueue(() -> move(x, y, modifiers));
  }

  private boolean move(float x, float y, int modifiers) {
    hover.move(x, y, modifiers); // the frame looks at what is under the pointer anyway
    if (rowsPending
        && (dragging() != null || capture instanceof Selecting || capture instanceof Grab)) {
      settle(); // the held element may be in a row; a scroll alone moved the rows that exist
    }
    return switch (capture) {
      case Selecting s -> {
        s.x = x;
        s.y = y;
        s.moved = true;
        yield drag(s, x, y);
      }
      case Grab(Object id, boolean vertical, float grip, float last) -> {
        El area = animator.element(id);
        float start = (vertical ? y : x) - grip;
        capture = new Grab(id, vertical, grip, start);
        boolean moved = area != null && area.dragThumb(vertical, start, Math.signum(start - last));
        scrolled |= moved;
        yield moved && tellUserScroll(area);
      }
      case Press p -> p.drag() != null && pull(p.drag(), x, y);
      case ActiveDrag a -> pull(a.drag(), x, y);
      case Idle _ -> false;
    };
  }

  /** Moves a drag on to the pointer, or starts it once the pointer has moved beyond the threshold. */
  private boolean pull(Dragging d, float x, float y) {
    float dx = x - d.x;
    float dy = y - d.y;
    if (d.started) {
      return step(d, Drag.Phase.MOVE, dx, dy);
    } else if (Math.max(Math.abs(dx), Math.abs(dy)) > Drag.THRESHOLD) {
      d.started = true;
      return step(d, Drag.Phase.START, dx, dy);
    }
    return false;
  }

  /**
   * The pointer left the window: from the next {@link #frame} on, nothing is hovered, and the drop
   * handler of this window that a drag rests over gets {@link Drop.Phase#LEAVE}.
   */
  public void pointerLeave() {
    enqueue(() -> {
      hover.leave();
      Dragging d = dragging();
      if (resting(d) && d.overApp == this) { // no OVER again where the pointer was
        drop(d, Drag.Phase.MOVE, null, Float.NaN, Float.NaN);
      }
      return false;
    });
  }

  /**
   * The pointer's button went down at this position, in logical pixels. From the {@link #frame}
   * that dispatches it until the one that dispatches the next {@link #pointerUp}, the element the
   * press goes to is {@link #pressed}. This also gives the focus
   * to the element there, or to the nearest one around it, that is {@link El#focusable}, without a
   * ring; where there is none, it clears the focus. If the element the press goes to has an {@link
   * El#onDrag} handler, the pointer's movements from here may make a drag of it.
   */
  public void pointerDown(float x, float y) {
    pointerDown(x, y, 1, 0);
  }

  /**
   * Like {@link #pointerDown(float, float)}, as the window reports it: with the number of presses
   * in quick succession at about the same place, and the modifiers held. A press handler gets the
   * number ({@link El#onPress(IntConsumer)}). On a text field ({@link El#editable}) a second
   * press selects a word and a third the whole text, Shift extends the
   * selection, and moving the pointer until it is up selects. Such a press goes to the field
   * alone: it is no press and no {@link El#onDrag drag} of an element around it, and until it is
   * up the field counts as the element under the pointer. A press on a scrollbar ({@link
   * El#scrollbars}) goes to the bar alone, before anything under it, and leaves the focus where it
   * is: on the thumb, moving the pointer drags the thumb until it is up, and on the track beside
   * the thumb, the area scrolls one page towards the pointer.
   *
   * <p>Overlays ({@link El#overlay}) come first, the topmost first, and a press goes no further
   * than the overlay it lands in. A press outside the overlays above it calls their {@link
   * El#onDismiss} handlers when the frame dispatches it; when it lands outside all of them, or
   * outside a {@link El#modal} one, that is all it does, and the input after it finds the tree
   * those handlers left.
   *
   * @param clicks 1 for a single press, 2 for the second of a double press, and so on
   * @param modifiers a combination of {@link Key#SHIFT}, {@link Key#CONTROL}, {@link Key#ALT} and
   *     {@link Key#META}
   */
  public void pointerDown(float x, float y, int clicks, int modifiers) {
    List<El> at = root == null ? List.of() : hit(x, y);
    int fieldIndex = fieldPressIndex(at);
    if (fieldIndex >= 0 && at.get(fieldIndex).editor() != null) {
      typingSoon = at.get(fieldIndex); // see inputArea
    }
    enqueue(() -> {
      rowGoals.clear(); // the user goes on: no list follows a row any more
      return down(x, y, clicks, modifiers);
    });
  }

  private boolean down(float x, float y, int clicks, int modifiers) {
    typingSoon = null;
    boolean changed = anyPointerDown(x, y);
    Dragging lost = startedDrag();
    if (lost != null) { // the last release got lost
      changed = step(lost, Drag.Phase.CANCEL, lost.dx, lost.dy);
    }
    // Likewise a selection or a thumb's drag whose release got lost ends where it is; a press holds
    // until this one replaces it below.
    capture = capture instanceof Press p ? p.withoutDrag() : IDLE;
    settle();
    restAfterPress(x, y);
    if (framePartUnderModal(x, y) || dismiss(x, y, true)) {
      capture = IDLE;
      return changed;
    }
    List<El> hit = hit(x, y);
    Scrollbars.Bar bar = hit.isEmpty() ? null : hit.getLast().barAt(x, y);
    if (bar != null) { // hit stops at the area whose bar it is
      El area = hit.getLast();
      Object id = Animator.identities(hit).getLast();
      boolean vertical = bar == area.verticalBar();
      float along = vertical ? y : x;
      float start = vertical ? bar.thumbY() : bar.thumbX();
      if (onThumb(bar, vertical, x, y)) {
        capture = new Grab(id, vertical, along - start, start);
      } else {
        capture = IDLE;
        int mark = vertical ? area.tickAt(y) : -1;
        if (mark >= 0) {
          boolean moved = area.scrollTo(area.scrollX(), area.centerOffset(mark));
          area.editor().center(mark);
          scrolled = true; // a frame centers it as measured, also where the estimate did not move
          if (moved) {
            changed |= tellUserScroll(hit.reversed());
          }
        } else {
          boolean moved = area.page(vertical, along < start ? -1 : 1);
          scrolled |= moved;
          if (moved) {
            changed |= tellUserScroll(hit.reversed());
          }
        }
      }
      return changed;
    }
    int fieldIndex = fieldPressIndex(hit);
    if (fieldIndex >= 0) {
      El field = hit.get(fieldIndex);
      List<El> fieldPath = hit.subList(0, fieldIndex + 1);
      Object fieldId = Animator.identities(fieldPath).getLast();
      boolean shift = (modifiers & Key.SHIFT) != 0;
      El scope = scopeField(field) ? scopeOf(fieldPath) : null;
      Object scopeId = scope == null ? null : identityOn(fieldPath, scope);
      if (shift && scope != null && scopeId != null) {
        remapScopeSelection();
        El list = scopeList(scope);
        Object listId = list == null ? null : animator.identity(list);
        int rowsVersion = list == null ? 0 : list.rowsVersion();
        ScopePosition anchorPos = null;
        Object anchorRowKey = null;
        Object anchorFieldId = null;
        if (scopeSelection != null && scopeSelection.scopeId.equals(scopeId)) {
          anchorPos =
              scopeSelection.forward() ? scopeSelection.anchorFrom : scopeSelection.anchorTo;
          anchorRowKey = scopeSelection.anchorRowKey;
          anchorFieldId = scopeSelection.anchorFieldId;
        }
        if (anchorPos == null) {
          anchorPos = focusScopePosition(scope, scopeId, list, listId);
          if (anchorPos != null) {
            if (list != null && anchorPos.inRows()) {
              anchorRowKey = list.rowKey(anchorPos.row());
            } else if (!anchorPos.inRows() && focus.element() != null) {
              anchorFieldId = fieldKey(focus.element(), animator.identity(focus.element()));
            }
          }
        }
        if (anchorPos != null) {
          ScopeField targetSf = scopeFieldOf(scope, field);
          boolean different =
              targetSf == null || anchorPos.compareField(targetSf.row, targetSf.ordinal) != 0;
          if (different || (scopeSelection != null && scopeSelection.hasSelection())) {
            Selecting next = new Selecting(
                fieldId,
                scopeId,
                anchorPos,
                anchorPos,
                listId,
                rowsVersion,
                anchorRowKey,
                anchorFieldId,
                1,
                x,
                y);
            selectAcrossScope(scope, next, field, x, y);
            capture = next;
            if (field.onPointerDown() != null) {
              double[] pos = content(field, x, y);
              field.onPointerDown().press(clicks, modifiers, pos[0], pos[1]);
              handled = true;
            }
            return true;
          }
        }
      }
      clearScopeSelection(null);
      focus.set(field, false, time);
      float[] at = local(field, x, y);
      Editor editor = field.editor();
      Text.CaretPos caret = field.text().nearestCaret(at[0], at[1]);
      editor.press(
          editor.fromShown(caret.index()),
          caret.stop(),
          editor.fromShown(field.text().hit(at[0], at[1])),
          clicks,
          shift);
      if (scope != null && scopeId != null) {
        int unit = shift ? 1 : Math.clamp(clicks, 1, field.text().multiline() ? 4 : 3);
        int from = shift ? editor.anchor() : editor.start();
        int to = shift ? editor.anchor() : editor.end();
        ScopeField sf = scopeFieldOf(scope, field);
        if (sf != null) {
          El list = scopeList(scope);
          Object listId = list == null ? null : animator.identity(list);
          int rowsVersion = list == null ? 0 : list.rowsVersion();
          boolean inRows = ScopePosition.inRows(sf.row);
          Object rowKey = list != null && inRows ? list.rowKey(sf.row) : null;
          Object outsideId = inRows ? null : fieldKey(field, fieldId);
          ScopePosition anchorFrom = new ScopePosition(sf.row, sf.ordinal, from);
          ScopePosition anchorTo = new ScopePosition(sf.row, sf.ordinal, to);
          ScopePosition end = new ScopePosition(sf.row, sf.ordinal, editor.caret());
          scopeSelection = new ScopeSelection(
              scopeId,
              anchorFrom,
              anchorTo,
              end,
              listId,
              rowsVersion,
              rowKey,
              rowKey,
              outsideId,
              outsideId);
          keptScope = null;
          capture = new Selecting(
              fieldId,
              scopeId,
              anchorFrom,
              anchorTo,
              listId,
              rowsVersion,
              rowKey,
              outsideId,
              unit,
              x,
              y);
        } else {
          capture = new Selecting(fieldId);
        }
      } else {
        capture = new Selecting(fieldId);
      }
      if (field.onPointerDown() != null) {
        double[] pos = content(field, x, y);
        field.onPointerDown().press(clicks, modifiers, pos[0], pos[1]);
        handled = true;
      }
      return true;
    }
    changed |= clearScopeSelection(null);
    Target target = target(x, y);
    capture = target == null
        ? IDLE
        : new Press(
            target.id(),
            clicks,
            modifiers,
            target.el.onDrag() == null
                ? null
                : new Dragging(target.id(), target.el.onDrag(), target.el.dragPayload(), x, y));
    if (!keepsFocus(hit)) {
      Target focus = target(x, y, El::isFocusable);
      this.focus.set(focus == null ? null : focus.el, false, time);
    }
    Target down = target(x, y, e -> e.onPointerDown() != null);
    if (down != null) {
      double[] at = content(down.el, x, y);
      down.el.onPointerDown().press(clicks, modifiers, at[0], at[1]);
      handled = changed = true;
    }
    return changed;
  }

  /**
   * The button was released. A press counts when it began on the same element, which the app
   * recognizes after rebuilds by the identity that matches elements across them, and did not
   * become a drag; a drag ends here, and so does a thumb's drag, which presses nothing.
   */
  public void pointerUp(float x, float y) {
    enqueue(() -> up(x, y));
  }

  private boolean up(float x, float y) {
    settle();
    Capture held = capture;
    // A press holds while its handlers run, without its drag; all else ends before them.
    capture = held instanceof Press p ? p.withoutDrag() : IDLE;
    boolean changed = switch (held) {
      case Selecting s -> drag(s, x, y);
      case ActiveDrag(Dragging drag) -> step(drag, Drag.Phase.END, x - drag.x, y - drag.y);
      case Press(Object id, int clicks, int modifiers, Dragging drag) -> {
        if (drag != null && drag.started) {
          yield step(drag, Drag.Phase.END, x - drag.x, y - drag.y);
        }
        Target target = target(x, y);
        if (target != null && target.id().equals(id) && target.el.onPress() != null) {
          double[] at = content(target.el, x, y);
          target.el.onPress().press(clicks, modifiers, at[0], at[1]);
          handled = true;
          yield true;
        }
        yield false;
      }
      case Grab _, Idle _ -> false;
    };
    capture = IDLE;
    return changed;
  }

  /**
   * The secondary button, usually the right one, went down at this position, in logical pixels.
   * Like a press of the primary button, it dismisses the overlays it lands outside of, and then
   * gives the focus to the focusable element there, or clears it. It neither drags nor selects.
   */
  public void secondaryDown(float x, float y) {
    enqueue(() -> {
      rowGoals.clear();
      return secondaryDownAt(x, y);
    });
  }

  private boolean secondaryDownAt(float x, float y) {
    boolean any = anyPointerDown(x, y);
    secondary = null;
    settle();
    restAfterPress(x, y);
    if (framePartUnderModal(x, y) || dismiss(x, y, true)) {
      return any;
    }
    Target target = target(x, y, this::opensMenu);
    secondary = target == null ? null : target.id();
    List<El> hit = hit(x, y);
    if (!keepsFocus(hit)) {
      Target focus = target(x, y, El::isFocusable);
      this.focus.set(focus == null ? null : focus.el, false, time);
    }
    // A press in a field of a selection across a scope keeps it, for that field's menu: the
    // innermost field hit, as a field in another's line ends takes the press.
    Editor innermost = hit.reversed().stream()
        .map(El::editor)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
    if (!isScopeSelected(innermost)) {
      any |= clearScopeSelection(null);
    }
    return any;
  }

  /**
   * Runs the {@link El#onAnyPointerDown} handlers of the tree and its shown overlays, and the
   * {@link El#onPressOutside} handlers of those the press at this point is not on or inside.
   */
  private boolean anyPointerDown(float x, float y) {
    if (root == null) {
      return false;
    }
    settle(); // the rows a rebuild between inputs left out have handlers too, and are hit
    Set<El> hit = Collections.newSetFromMap(new IdentityHashMap<>());
    hit.addAll(hit(x, y));
    List<Runnable> handlers = new ArrayList<>();
    anyPointerDown(root, hit, handlers);
    for (El overlay : root.shownOverlays()) {
      anyPointerDown(overlay, hit, handlers);
    }
    handlers.forEach(Runnable::run);
    handled |= !handlers.isEmpty();
    return !handlers.isEmpty();
  }

  /** Runs the {@link El#onAnyKeyDown} handlers of the tree and its shown overlays. */
  private void anyKeyDown(Key key) {
    if (root == null) {
      return;
    }
    float far = Float.POSITIVE_INFINITY;
    if (rowsPending && (rowKeyHooks || rowKeyHooks(root, 0, 0, -far, far))) {
      settle(); // only then: most keys need no rows, and building them is most of a rebuild
    }
    List<Consumer<Key>> handlers = new ArrayList<>();
    anyKeyDown(root, handlers);
    for (El overlay : root.shownOverlays()) {
      anyKeyDown(overlay, handlers);
    }
    for (Consumer<Key> handler : handlers) {
      handler.accept(key);
    }
    handled |= !handlers.isEmpty();
  }

  /**
   * Whether the rows that the lists of a tree built between two inputs would build have an {@link
   * El#onAnyKeyDown} handler, as {@link #fillRows} would find them, from the row functions alone.
   *
   * <p>shortcut: a hook that only a look of a row attaches is not seen here; apply the rows' looks
   * too if a look ever needs to attach one.
   */
  private boolean rowKeyHooks(
      El el, float parentX, float parentY, float clipLeft, float clipRight) {
    if (!el.isPlaced() || el.isExiting()) {
      return false;
    }
    float left = parentX + el.x();
    float top = parentY + el.y();
    int[] range = el.wantedRows(left, top, clipLeft, clipRight, width, height);
    if (range.length != 0) {
      long start = System.nanoTime();
      boolean hooks = el.rowsHaveKeyHooks(range[0], range[1]); // runs the row function
      buildNanos += System.nanoTime() - start;
      if (hooks) {
        return true;
      }
    }
    if (el.style().overflowX != Style.Overflow.VISIBLE) {
      clipLeft = Math.max(clipLeft, left);
      clipRight = Math.min(clipRight, left + el.width());
    }
    for (El child : el.children()) {
      if (rowKeyHooks(child, left, top, clipLeft, clipRight)) {
        return true;
      }
    }
    float far = Float.POSITIVE_INFINITY;
    for (El overlay : el.overlays()) { // in window coordinates
      if (rowKeyHooks(overlay, 0, 0, -far, far)) {
        return true;
      }
    }
    return false;
  }

  private static void anyKeyDown(El el, List<Consumer<Key>> handlers) {
    if (el.isExiting() || el.isInert()) {
      return; // it takes no input
    }
    if (el.onAnyKeyDown() != null) {
      handlers.add(el.onAnyKeyDown());
    }
    for (El child : el.children()) {
      anyKeyDown(child, handlers);
    }
  }

  private static void anyPointerDown(El el, Set<El> hit, List<Runnable> handlers) {
    if (el.isExiting() || el.isInert()) {
      return; // it takes no input
    }
    if (el.onAnyPointerDown() != null) {
      handlers.add(el.onAnyPointerDown());
    }
    if (el.onPressOutside() != null && !hit.contains(el)) {
      handlers.add(el.onPressOutside());
    }
    for (El child : el.children()) {
      anyPointerDown(child, hit, handlers);
    }
  }

  /**
   * The secondary button was released here. When it went down on the same element, the {@link
   * #frame} that dispatches this runs that element's {@link El#onSecondaryPress} handler, or its
   * nearest ancestor's, with this position; a field with an {@link El#fieldMenu} on the way opens
   * that instead.
   */
  public void secondaryUp(float x, float y) {
    enqueue(() -> {
      settle();
      Target target = target(x, y, this::opensMenu);
      boolean ran = target != null && target.id().equals(secondary);
      secondary = null;
      if (ran) {
        openMenu(target.el, target.id(), x, y);
      }
      return ran;
    });
  }

  /**
   * Whether a secondary press on this element runs its handler or opens its field's menu.
   *
   * <p>shortcut: a field in a virtual list's rows, which are built after {@link #openFieldMenu},
   * opens no menu and leaves the press to the handlers around it; attach the menu in {@link
   * #rowLooks} if rows with fields need one.
   */
  private boolean opensMenu(El el) {
    Object id = animator.identity(el);
    return el.onSecondaryPress() != null
        || el.fieldMenu() != null
            && el.takesEdits()
            && animator.inRow(id) == null
            && !Animator.inFieldPart(id);
  }

  /** Runs the element's {@link El#onSecondaryPress} handler, or else opens its field's menu. */
  private void openMenu(El el, Object id, float x, float y) {
    if (el.onSecondaryPress() != null) {
      el.onSecondaryPress().accept(x, y);
    } else {
      fieldMenu = new FieldMenu.Open(this, id, x, y, !clipboard.read().isEmpty());
    }
    handled = true;
  }

  /**
   * A press at this point keeps the looks and handlers of {@link El#whenHoveredFor} and {@link
   * El#onHoverFor} away until a new stay: of the stays there are, and of the elements there whose
   * stay has not begun yet, as when the pointer came in the same frame.
   */
  private void restAfterPress(float x, float y) {
    List<El> path = root == null ? List.of() : hit(x, y);
    List<Object> ids = Animator.identities(path);
    for (int i = 0; i < path.size(); i++) {
      if (path.get(i).restLook() != null || path.get(i).onHoverFor() != null) {
        resting.computeIfAbsent(ids.get(i), id -> new Rest(time));
      }
    }
    for (Rest rest : resting.values()) {
      rest.since = Double.POSITIVE_INFINITY;
      rest.attached = List.of();
    }
    for (TextHover th : textHovered.values()) {
      th.since = Double.POSITIVE_INFINITY;
      th.pressedAt = th.position;
    }
    if (!path.isEmpty()) {
      El leaf = path.getLast();
      if (leaf.text() != null
          && leaf.text().multiline()
          && (leaf.onTextPointerMove() != null || leaf.onTextHoverFor() != null)) {
        TextHover th = textHovered.computeIfAbsent(ids.getLast(), id -> new TextHover());
        th.since = Double.POSITIVE_INFINITY;
        th.pressedAt = textPositionAt(path, x, y);
      }
    }
    lookLeft = Double.NEGATIVE_INFINITY;
  }

  /**
   * Whether {@link El#keepFocus} keeps the focus where it is for a press along this path: not for
   * a press in a text field, which takes the focus.
   */
  private static boolean keepsFocus(List<El> hit) {
    if (!hit.isEmpty() && hit.getLast().takesEdits()) {
      return false;
    }
    for (El el : hit) {
      if (el.keepsFocus()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a press at this point lands on a part of the window's own frame ({@link
   * El#windowPart}) outside a modal overlay: the window moves, minimizes, maximizes or closes, and
   * the press neither dismisses the overlay nor reaches anything else.
   */
  private boolean framePartUnderModal(float x, float y) {
    return modal() != null && hit(x, y).isEmpty() && windowPart(x, y) != WindowPart.CONTENT;
  }

  /**
   * For a press or the wheel at this point: runs the {@link El#onDismiss} handlers of the
   * overlays above the first one it lands in, the topmost first, all in the tree of the moment.
   * One whose {@link El#dismissGroup} is that of the element under the point counts as one it
   * lands in. A modal overlay ends the search; the wheel calls no modal overlay's handler. A press
   * outside all overlays then dismisses the elements that {@link El#holdFocus hold the focus} and
   * that it lands outside of.
   *
   * @return whether the input goes no further: it landed outside a modal overlay, or a press
   *     landed outside all overlays and called a handler
   */
  private boolean dismiss(float x, float y, boolean press) {
    if (root == null) {
      return false;
    }
    List<El> under = hit(x, y);
    Object group = null;
    for (int i = under.size() - 1; i >= 0 && group == null; i--) {
      group = under.get(i).dismissGroup();
    }
    boolean dismissed = false;
    for (El overlay : root.shownOverlays().reversed()) {
      if (overlay.isExiting() || overlay.isInert()) {
        continue;
      }
      if (!hitFrom(overlay, x, y).isEmpty()
          || group != null && group.equals(overlay.dismissGroup())) {
        return false;
      }
      Runnable handler = overlay.onDismiss();
      if (handler != null && (press || !overlay.isModal())) {
        handler.run();
        handled = dismissed = true;
      }
      if (overlay.isModal()) {
        return true;
      }
    }
    if (press) {
      for (Object id : focus.opened()) {
        El holder = animator.element(id);
        if (holder != null
            && holder.holdsFocus()
            && holder.onDismiss() != null
            && !under.contains(holder)) {
          holder.onDismiss().run();
          handled = dismissed = true;
        }
      }
    }
    return press && dismissed;
  }

  /**
   * For Tab moving the focus from the element with identity {@code from} to the one with {@code
   * to}: runs the {@link El#onDismiss} handlers of the overlays it leaves, the topmost first, as a
   * press outside them would. A modal overlay keeps the focus, so Tab never leaves one.
   */
  private void leaveOverlays(Object from, Object to) {
    for (El overlay : root.shownOverlays().reversed()) {
      Object id = animator.identity(overlay);
      if (within(from, id)
          && !within(to, id)
          && overlay.onDismiss() != null
          && !overlay.isModal()
          && !overlay.isExiting()
          && !overlay.isInert()) {
        overlay.onDismiss().run();
        handled = true;
      }
    }
  }

  /** Whether the element with identity {@code id} is the one with {@code scope} or inside it. */
  private static boolean within(Object id, Object scope) {
    return id != null && (id.equals(scope) || Animator.isBelow(id, scope));
  }

  /** Whether {@code id} is {@code scope} or inside it, in the scope's own overlay layer. */
  private static boolean inScope(Object id, Object scope) {
    return id != null && (id.equals(scope) || Animator.isBelowInLayer(id, scope));
  }

  /** The topmost modal overlay that is open and not leaving, or null. */
  private El modal() {
    if (root != null) {
      for (El overlay : root.shownOverlays().reversed()) {
        if (overlay.isModal() && !overlay.isExiting() && !overlay.isInert()) {
          return overlay;
        }
      }
    }
    return null;
  }

  /**
   * Where the Menu key opens a menu for the focused element at the end of {@code path}: at the
   * bottom left of its cursor, the first element inside it that is {@link El#current}, else the
   * first that is {@link El#selected} (a grid's cursor cell, a tree's selected row), kept inside
   * the focused element's box; at the focused element's bottom left when it has neither.
   */
  private static float[] menuPoint(List<El> path) {
    List<El> to = new ArrayList<>(fromOverlay(path));
    float left = 0;
    float top = 0;
    for (El on : to) {
      left += on.x();
      top += on.y();
    }
    El focused = to.getLast();
    float right = left + focused.width();
    float bottom = top + focused.height();
    List<El> cursor = new ArrayList<>();
    if (!firstPath(focused, El::isCurrent, cursor) && !firstPath(focused, El::isSelected, cursor)) {
      return new float[] {left, bottom};
    }
    float x = left;
    float y = top;
    for (El on : cursor) {
      x += on.x();
      y += on.y();
    }
    return new float[] {
      Math.clamp(x, left, right), Math.clamp(y + cursor.getLast().height(), top, bottom)
    };
  }

  /**
   * Adds to {@code path} the elements below {@code from} down to the first one in tree order that
   * is {@code wanted} and placed, without overlays; whether there is one.
   */
  private static boolean firstPath(El from, Predicate<El> wanted, List<El> path) {
    for (El child : from.children()) {
      if (!child.isPlaced()) {
        continue;
      }
      path.add(child);
      if (wanted.test(child) || firstPath(child, wanted, path)) {
        return true;
      }
      path.removeLast();
    }
    return false;
  }

  /**
   * The part of a path from the root that begins at the innermost overlay in it, whose position
   * is in window coordinates; all of it when it has no overlay.
   */
  private static List<El> fromOverlay(List<El> path) {
    for (int i = path.size() - 1; i > 0; i--) {
      if (path.get(i - 1).hasOverlay(path.get(i))) {
        return path.subList(i, path.size());
      }
    }
    return path;
  }

  /**
   * What the pointer holds from a press of its primary button until the button is up, one at a
   * time; {@link #endPresses} ends it once its element leaves the tree.
   */
  private sealed interface Capture {}

  private static final Capture IDLE = new Idle();

  /** Nothing: between presses, or after one that went to no element. */
  private record Idle() implements Capture {}

  /**
   * A press on an element: its identity, the presses in quick succession it completes, the
   * modifiers held when it went down, and the drag it may become, null for an element without a
   * drag handler.
   */
  private record Press(Object id, int clicks, int modifiers, Dragging drag) implements Capture {
    Press withoutDrag() {
      return new Press(id, clicks, modifiers, null);
    }
  }

  /** A drag that has started and goes on after its element has left the tree. */
  private record ActiveDrag(Dragging drag) implements Capture {}

  /**
   * A press selecting in a text field: the pressed field's identity, and when inside a {@link
   * El#selectionScope}, the scope's identity, the anchor's initial position range (and row key for
   * a keyed virtual list, or field identity when outside the list's rows), the selection unit (1
   * graphemes, 2 words, 3 lines/all, 4 all), and the last pointer position for edge auto-scroll.
   */
  private static final class Selecting implements Capture {
    final Object id;
    final Object scopeId;
    ScopePosition anchorFrom;
    ScopePosition anchorTo;
    Object listId; // rebound to a new list while the anchor is outside the rows
    int rowsVersion;
    final Object anchorRowKey;
    final Object anchorFieldId;
    final int unit;
    float x;
    float y;
    boolean moved;
    double scrollTime = Double.NaN;

    Selecting(Object id) {
      this(id, null, null, null, null, 0, null, null, 1, 0, 0);
    }

    Selecting(
        Object id,
        Object scopeId,
        ScopePosition anchorFrom,
        ScopePosition anchorTo,
        Object listId,
        int rowsVersion,
        Object anchorRowKey,
        Object anchorFieldId,
        int unit,
        float x,
        float y) {
      this.id = id;
      this.scopeId = scopeId;
      this.anchorFrom = anchorFrom;
      this.anchorTo = anchorTo;
      this.listId = listId;
      this.rowsVersion = rowsVersion;
      this.anchorRowKey = anchorRowKey;
      this.anchorFieldId = anchorFieldId;
      this.unit = unit;
      this.x = x;
      this.y = y;
    }

    Object id() {
      return id;
    }

    Object scopeId() {
      return scopeId;
    }
  }

  /**
   * A selection inside one {@link El#selectionScope}, stored as {@link ScopePosition}s so it
   * survives virtual list rows that leave and return and tracks outside-row fields by identity.
   */
  private static final class ScopeSelection {
    final Object scopeId;
    ScopePosition anchorFrom;
    ScopePosition anchorTo;
    ScopePosition end;
    Object listId;
    int rowsVersion;
    final Object anchorRowKey;
    final Object endRowKey;
    final Object anchorFieldId;
    final Object endFieldId;
    // The selected text, its occurrence in its row, the row's number of fields and the text of the
    // field it starts in, recorded when first told (new endpoints make a new selection) so a row
    // whose fields reshape (a paragraph becoming a table) still finds the same text; "" when
    // skipped.
    String text;
    int occurrence;
    int fields;
    String field;

    ScopeSelection(
        Object scopeId,
        ScopePosition anchorFrom,
        ScopePosition anchorTo,
        ScopePosition end,
        Object listId,
        int rowsVersion,
        Object anchorRowKey,
        Object endRowKey,
        Object anchorFieldId,
        Object endFieldId) {
      this.scopeId = scopeId;
      this.anchorFrom = anchorFrom;
      this.anchorTo = anchorTo;
      this.end = end;
      this.listId = listId;
      this.rowsVersion = rowsVersion;
      this.anchorRowKey = anchorRowKey;
      this.endRowKey = endRowKey;
      this.anchorFieldId = anchorFieldId;
      this.endFieldId = endFieldId;
    }

    ScopePosition start() {
      return end.compareTo(anchorFrom) < 0 ? end : anchorFrom;
    }

    ScopePosition finish() {
      return end.compareTo(anchorTo) > 0 ? end : anchorTo;
    }

    boolean forward() {
      return end.compareTo(anchorFrom) >= 0;
    }

    boolean hasSelection() {
      return start().compareTo(finish()) < 0;
    }

    boolean covers(int row, int field) {
      if (field < 0 || !hasSelection()) {
        return false;
      }
      return start().compareField(row, field) >= 0 && finish().compareField(row, field) <= 0;
    }

    /** Returns {@code {anchor, caret}} for a field in this selection, or null when outside it. */
    int[] editorRangeFor(int row, int field, int length) {
      if (field < 0 || !hasSelection()) {
        return null;
      }
      ScopePosition s = start();
      ScopePosition f = finish();
      int cmpStart = s.compareField(row, field);
      int cmpFinish = f.compareField(row, field);
      if (cmpStart < 0 || cmpFinish > 0) {
        return null;
      }
      int from = cmpStart == 0 ? Math.clamp(s.offset(), 0, length) : 0;
      int to = cmpFinish == 0 ? Math.clamp(f.offset(), 0, length) : length;
      if (to < from) {
        to = from;
      }
      return forward() ? new int[] {from, to} : new int[] {to, from};
    }

    /** Returns {@code {from, to}} ordered character offsets for copying, or null when outside. */
    int[] sliceFor(int row, int field, int length) {
      if (field < 0 || !hasSelection()) {
        return null;
      }
      ScopePosition s = start();
      ScopePosition f = finish();
      int cmpStart = s.compareField(row, field);
      int cmpFinish = f.compareField(row, field);
      if (cmpStart < 0 || cmpFinish > 0) {
        return null;
      }
      int from = cmpStart == 0 ? Math.clamp(s.offset(), 0, length) : 0;
      int to = cmpFinish == 0 ? Math.clamp(f.offset(), 0, length) : length;
      return to > from ? new int[] {from, to} : null;
    }

    boolean sameEndpoints(ScopeSelection other) {
      return other != null
          && scopeId.equals(other.scopeId)
          && anchorFrom.equals(other.anchorFrom)
          && anchorTo.equals(other.anchorTo)
          && end.equals(other.end)
          && Objects.equals(anchorFieldId, other.anchorFieldId)
          && Objects.equals(endFieldId, other.endFieldId);
    }
  }

  /** One read-only field in a {@link El#selectionScope}, with its position and window bounds. */
  private record ScopeField(
      El el,
      int row,
      int ordinal,
      Object id,
      float left,
      float top,
      float right,
      float bottom,
      float clipLeft,
      float clipTop,
      float clipRight,
      float clipBottom) {
    boolean visible() {
      return clipLeft < clipRight && clipTop < clipBottom;
    }
  }

  /**
   * A press on the thumb of a scroll area's bar: the area's identity, which bar, and how far from
   * the thumb's start the pointer holds it along the bar.
   */
  /** A thumb held {@code grip} from its start, which the pointer put at {@code last}. */
  private record Grab(Object id, boolean vertical, float grip, float last) implements Capture {}

  /** The drag the pointer holds, started or not, or null. */
  private Dragging dragging() {
    return switch (capture) {
      case Press p -> p.drag();
      case ActiveDrag a -> a.drag();
      case Idle _, Selecting _, Grab _ -> null;
    };
  }

  /** The drag the pointer holds once it has started, or null. */
  private Dragging startedDrag() {
    Dragging d = dragging();
    return d != null && d.started ? d : null;
  }

  /** Whether the point lies across the strip of a bar at the place of its thumb. */
  static boolean onThumb(Scrollbars.Bar bar, boolean vertical, float x, float y) {
    float along = vertical ? y : x;
    float start = vertical ? bar.thumbY() : bar.thumbX();
    return along >= start && along <= start + (vertical ? bar.thumbHeight() : bar.thumbWidth());
  }

  /** A pointer down on an element with a drag handler, which becomes a drag once it moves. */
  private static final class Dragging {
    final Object id;
    final Consumer<Drag> handler; // the element's when the pointer went down
    final Object payload; // likewise; null for a drag that reaches no drop handler
    App<?> overApp; // the app whose element's drop handler the last step was over, or null
    Object overId; // that element's identity there
    Consumer<Drop> overHandler; // and its handler, which gets the LEAVE
    final float x;
    final float y;
    boolean started; // moved beyond the threshold
    boolean over; // ended or cancelled, in the frames that ran its steps
    double at; // the time of its last step, or of the last OVER repeated while it rests
    float dx; // the distance of the last step
    float dy;

    Dragging(Object id, Consumer<Drag> handler, Object payload, float x, float y) {
      this.id = id;
      this.handler = handler;
      this.payload = payload;
      this.x = x;
      this.y = y;
    }
  }

  /**
   * Gives a step of a drag to its handler. If the drag's element has left the tree, the handler
   * gets a cancel instead, and nothing after that.
   *
   * @return whether the handler ran
   */
  private boolean step(Dragging d, Drag.Phase phase, float dx, float dy) {
    d.dx = dx;
    d.dy = dy;
    d.at = time;
    if (d.over) {
      return false;
    }
    El el = animator.liveElement(d.id);
    // A drag that carries a payload goes on without its element: what moves is the payload, as a
    // tab torn out of its bar.
    boolean left = el == null;
    boolean gone = left && d.payload == null;
    d.over = gone || phase == Drag.Phase.END || phase == Drag.Phase.CANCEL;
    double[] at = left ? new double[] {Double.NaN, Double.NaN} : content(el, d.x + dx, d.y + dy);
    Drag.Phase now = gone ? Drag.Phase.CANCEL : phase;
    float px = d.x + dx;
    float py = d.y + dy;
    boolean outside = !(px >= 0 && py >= 0 && px < width && py < height);
    Windows.Place place = place(px, py, outside);
    if (d.payload != null) {
      drop(d, now, over(place, outside), place.x(), place.y());
    }
    d.handler.accept(new Drag(
        now,
        dx,
        dy,
        at[0],
        at[1],
        place.screenX(),
        place.screenY(),
        outside,
        place.window(),
        place.window() == null ? Float.NaN : place.x(),
        place.window() == null ? Float.NaN : place.y(),
        place.outsideAll()));
    handled = true;
    return true;
  }

  /** Where a point of this window lies among the runner's windows. */
  private Windows.Place place(float px, float py, boolean outside) {
    Windows.Place place = windows.place(this, px, py);
    if (place == null) { // no runner: this window alone, at the origin of the screen
      place = new Windows.Place(
          px, py, null, outside ? Float.NaN : px, outside ? Float.NaN : py, false);
    }
    return place;
  }

  /** The app of the window under a drag's pointer, or null. */
  private App<?> over(Windows.Place place, boolean outside) {
    return place.window() != null
        ? place.window().app()
        : windows.of(this) == null && !outside ? this : null;
  }

  /**
   * Whether a drag rests over a drop handler, with its next {@link Drop.Phase#OVER} due at {@code
   * at} plus {@link Drop#REPEAT}.
   */
  private static boolean resting(Dragging d) {
    return d != null && d.started && !d.over && d.overApp != null;
  }

  /** Tells the drop handler a drag rests over that the pointer is still there, if it is due. */
  private boolean repeat(Dragging d) {
    if (d != dragging() || !resting(d) || time < d.at + Drop.REPEAT) {
      return false; // it moved, ended or left since
    }
    d.at = time;
    float px = d.x + d.dx;
    float py = d.y + d.dy;
    boolean outside = !(px >= 0 && py >= 0 && px < width && py < height);
    Windows.Place place = place(px, py, outside);
    drop(d, Drag.Phase.MOVE, over(place, outside), place.x(), place.y());
    handled = true; // as after a step: the handler, maybe another window's, may change the state
    return false;
  }

  /**
   * Tells the drop handlers of a drag's step: the one it left, and the innermost one under the
   * pointer in {@code over}'s window, hit tested in the tree that window shows.
   *
   * @param over the app of the window under the pointer, or null
   * @param x the pointer in that window
   */
  private static void drop(Dragging d, Drag.Phase phase, App<?> over, float x, float y) {
    Target target = null;
    if (over != null && over.root != null && phase != Drag.Phase.CANCEL) {
      over.settle(); // the rows of its lists, where a scroll left them to build
      target = over.target(x, y, el -> el.onDrop() != null);
    }
    Object id = target == null ? null : target.id();
    if (d.overApp != null && (d.overApp != over || !d.overId.equals(id))) {
      Consumer<Drop> left = d.overHandler;
      App<?> from = d.overApp;
      d.overApp = null;
      d.overId = null;
      d.overHandler = null;
      left.accept(new Drop(Drop.Phase.LEAVE, d.payload, Double.NaN, Double.NaN, from.time));
      from.touched();
    }
    if (target != null) {
      double[] at = over.content(target.el, x, y);
      boolean end = phase == Drag.Phase.END;
      Consumer<Drop> handler = target.el.onDrop();
      if (!end) {
        d.overApp = over;
        d.overId = id;
        d.overHandler = handler;
      }
      handler.accept(
          new Drop(end ? Drop.Phase.DROP : Drop.Phase.OVER, d.payload, at[0], at[1], over.time));
      over.touched();
    }
  }

  /** A drop handler of this app ran, maybe in another app's frame: build the view again. */
  private void touched() {
    if (scheduler.dispatching()) {
      handled = true;
    } else {
      stale = true;
    }
  }

  /**
   * A point of the window in the coordinates of an element's content in the current tree: from
   * the top left of its border box as shown, moved by its own scroll offset.
   */
  private double[] content(El el, float x, float y) {
    List<El> path = new ArrayList<>();
    pathTo(root, el, path);
    double left = 0;
    double top = 0;
    for (El on : fromOverlay(path)) {
      left += on.x();
      top += on.y();
    }
    return new double[] {x - left + el.scrollX(), y - top + el.scrollY()};
  }

  /**
   * A key went down or repeats. The next {@link #frame} gives it to the {@link El#onKey} handlers
   * of the focused element and then of its ancestors, until one returns true; without focus only
   * the root's handler sees it. If none does, Enter and Space without modifiers press the focused
   * element if it has an {@link El#onPress} handler, which takes their repeats without pressing
   * again, and Tab moves the focus to the next focusable element in tree order, Shift+Tab to the
   * previous one, around at the ends, and Page Up and Page Down without modifiers scroll the
   * innermost scroll area around the focused element that can still move that way by its height,
   * as in a browser, and Home and End, alone or with Control, scroll it to its start or its end,
   * and in a read-only multi-line text field the arrow keys without modifiers scroll by a line,
   * or without one the first that can in the overlay around the focused element,
   * else in a {@link El#modal} overlay: a dialog's or a drawer's content while a button outside
   * it has the focus. Inside an overlay they never scroll the view it is attached to. Unlike in a browser, a press handler
   * on the focused element takes Enter and Space before its ancestors' key handlers see them. The
   * Menu key and Shift+F10 run the nearest {@link El#onSecondaryPress} handler, or open a field's
   * {@link El#fieldMenu} that is nearer, at the bottom left of the focused element's cursor: the first element inside it that is {@link El#current}, else
   * the first that is {@link El#selected}, else the focused element itself.
   *
   * <p>In an overlay, keys go up from the focused element to the overlay's root and on to the
   * element it is attached to and that element's ancestors; in a {@link El#modal} one they end at
   * its root, which also gets them while nothing has the focus.
   */
  public void keyDown(Key key) {
    keyDown(key, null);
  }

  /**
   * A key went down, as {@link #keyDown(Key)}, and {@code untaken} runs in the frame that
   * dispatches it if nothing took it: no key handler, field, press or Tab. A runner hands a key on
   * with it for what the system does with a key no app wants, as {@link
   * com.rethinkj.ui.window.WindowLoop} opens the window menu on Alt+Space.
   *
   * @param untaken runs on the app's thread, during {@link #frame}; null for nothing
   */
  public void keyDown(Key key, Runnable untaken) {
    Objects.requireNonNull(key, "key");
    enqueue(() -> {
      keyScroll = true; // also for what its handlers ask, as App.focus, and the field it wakes
      hover.keyDown(key);
      rowGoals.clear();
      if (edits(key)) {
        wakeField();
      } else if (key.name().startsWith("Arrow")
          && key.modifiers() == Key.SHIFT
          && focus.element() == null
          && focusedEditor() != null
          && !focusedEditor().editable()
          && focusedEditor().element().text().multiline()) {
        rowGoals.clear();
        wake(); // the selection starts at the caret its row kept
      }
      if (key.name().equals("Escape")) {
        focus.clearTypeAhead();
      }
      keyToText = focus.element() != null
              && (focus.element().editor() != null
                  || focus.element().onText() != null
                  || focus.element().onTypeAhead() != null)
          || fieldWaits();
      Object before = focus.id();
      keyUntaken = false;
      anyKeyDown(key);
      boolean took = keyDownAt(key);
      if (keyUntaken && untaken != null) {
        untaken.run();
      }
      if (focus.keyed(before)) {
        handled = true;
      }
      focusByKey |= !Objects.equals(before, focus.id());
      return took;
    });
  }

  private boolean keyDownAt(Key key) {
    Dragging drag = startedDrag();
    if (drag != null && key.name().equals("Escape")) {
      // Escape gives up a drag, as in browsers and editors; the release that follows presses
      // nothing.
      boolean changed = step(drag, Drag.Phase.CANCEL, drag.dx, drag.dy);
      capture = IDLE;
      return changed;
    }
    El focused = focus.element();
    if (focused != null
        && focused.editor() != null
        && !focused.editor().composition().isEmpty() // the input method has the keys
        && focused.editor().key(key, clipboard)) {
      handled |= focused.editor().answerPending();
      return true;
    }
    // The focused field of a selection across a scope waits for its row, as after a scroll, or
    // nothing has focus after App#select(El, int, int) on a scope.
    if (isCopy(key)
        && focus.element() == null
        && (focus.waits()
            ? isScopeSelectedId(focus.id())
            : scopeSelection != null && scopeSelection.hasSelection())) {
      copyScopeSelection();
      handled = true;
      return true;
    }
    List<El> path = focusPath();
    boolean called = false;
    for (El el : path.reversed()) {
      if (el.onKey() != null) {
        called = true;
        if (el.onKey().test(key)) {
          if (key.modifiers() == Key.CONTROL && key.name().equals("A")) {
            clearScopeSelection(null);
          }
          handled = true;
          return true;
        }
      }
      if (el == focus.element() && el.editor() != null && handleFieldKey(el, key)) {
        handled |= el.editor().answerPending(); // it handed an edit to the app
        return true;
      }
      if (el == focus.element()
          && el.onPress() != null
          && key.modifiers() == 0
          && (key.name().equals("Enter") || key.name().equals("Space"))) {
        if (!key.repeat()) {
          keyPressed = animator.identity(el); // shows pressed until the key is up; see KEY_PRESS
          keyPressKey = key.name();
          keyPressFrame = frames;
          keyPressUntil = time + KEY_PRESS;
          el.onPress()
              .press(1, 0, el.width() / 2.0 + el.scrollX(), el.height() / 2.0 + el.scrollY());
          handled = true;
        }
        return true;
      }
    }
    if (isCopy(key) && isScopeSelectedId(focus.id())) {
      copyScopeSelection();
      handled = true;
      return true;
    }
    if (key.name().equals("ContextMenu") && key.modifiers() == 0
        || key.name().equals("F10") && key.modifiers() == Key.SHIFT) {
      for (El el : path.reversed()) {
        if (opensMenu(el)) {
          float[] at = menuPoint(path);
          openMenu(el, animator.identity(el), at[0], at[1]);
          return true;
        }
      }
    }
    if (key.name().equals("Tab") && (key.modifiers() & ~Key.SHIFT) == 0) {
      settle();
      wake();
      Object from = focus.id(); // by identity: entering a list may build rows anew
      List<El> order = focusables();
      order.removeIf(el -> el.skipsTab() && el != focus.element());
      if (!order.isEmpty()) {
        int step = key.modifiers() == Key.SHIFT ? -1 : 1;
        int at = order.indexOf(focus.element());
        int next = at >= 0 ? at + step : step > 0 ? 0 : -1;
        El el = enter(order.get(Math.floorMod(next, order.size())), step);
        if (animator.identity(el) == null) {
          return true; // an edit that went in on the way removed it: the focus stays
        }
        clearScopeSelection(null);
        if (el.editor() != null && !el.text().multiline()) {
          el.editor().selectAll(); // as browsers do when Tab enters a field, but not a text area
        }
        leaveOverlays(from, animator.identity(el));
        return focus.set(el, true, time);
      }
    }
    boolean byPage =
        (key.name().equals("PageUp") || key.name().equals("PageDown")) && key.modifiers() == 0;
    // Home and End, with or without Control, scroll to the start or the end, as in a browser.
    boolean edge = (key.name().equals("Home") || key.name().equals("End"))
        && (key.modifiers() & ~Key.CONTROL) == 0;
    // The arrow keys that a read-only multi-line field leaves (it shows no caret) scroll by a line,
    // also while its row is out of range.
    Editor reader = focusedEditor();
    Text text = reader == null ? null : reader.element().text();
    boolean byLine = key.name().startsWith("Arrow")
        && key.modifiers() == 0
        && reader != null
        && !reader.editable()
        && text.multiline();
    boolean horizontal = key.name().equals("ArrowLeft") || key.name().equals("ArrowRight");
    if (byPage || edge || byLine) {
      int direction = switch (key.name()) {
        case "PageUp", "Home", "ArrowUp", "ArrowLeft" -> -1;
        default -> 1;
      };
      El area = null;
      // An overlay is a page of its own, as a dialog or a drawer: not its anchor's view.
      List<El> page = fromOverlay(path);
      for (El el : page.reversed()) { // the innermost area around the focus that can move so
        if (el.isScrollArea() && el.canScroll(byLine && horizontal, direction)) {
          area = el;
          break;
        }
      }
      if (area == null && !byLine) { // a line is the focus's own: not another area's
        El overlay = page != path ? page.getFirst() : modal();
        area = overlay == null ? null : pageArea(overlay, direction);
      }
      if (area != null) {
        double line = byLine ? direction * text.lineHeight() / text.scale() : 0;
        boolean moved = byLine
            ? horizontal
                ? area.scrollTo(area.scrollX() + line, area.scrollY())
                : area.scrollTo(area.scrollX(), area.scrollY() + line)
            : byPage
                ? area.page(true, direction)
                : area.userScrollTo(area.scrollX(), direction * Double.POSITIVE_INFINITY);
        scrolled |= moved;
        if (moved) {
          tellUserScroll(area);
        }
        return true;
      }
    }
    keyUntaken = true;
    return called;
  }

  /**
   * A key was released. The next {@link #frame} gives it to the {@link El#onKeyUp} handlers, in
   * the order of {@link #keyDown}; it does nothing else.
   */
  public void keyUp(Key key) {
    Objects.requireNonNull(key, "key");
    enqueue(() -> {
      hover.keyUp(key);
      if (key.name().equals(keyPressKey)) {
        keyPressKey = null;
        if (frames == keyPressFrame || time >= keyPressUntil) {
          keyPressed = null; // in the frame that pressed, the look never showed
        }
      }
      boolean called = false;
      for (El el : focusPath().reversed()) {
        if (el.onKeyUp() != null) {
          called = true;
          if (el.onKeyUp().test(key)) {
            handled = true;
            return true;
          }
        }
      }
      return called;
    });
  }

  /**
   * Text was typed or composed with an input method. The next {@link #frame} puts it into the
   * focused text field ({@link El#editable}) in place of its selection, or gives it to the {@link
   * El#onText} handler of the focused element; without either it is dropped. A focused editable
   * field in a row of a list that is scrolled out of the rows that exist comes back into view and
   * takes it, as it does for Backspace, Delete and the keys that paste, cut and undo.
   */
  public void textInput(String text) {
    Objects.requireNonNull(text, "text");
    enqueue(() -> {
      wakeField();
      if (focus.element() != null && focus.element().editor() != null) {
        boolean changed = focus.element().editor().type(text);
        handled |= focus.element().editor().answerPending();
        return changed;
      }
      return focus.element() != null && giveText(text);
    });
  }

  /**
   * Text that the system made of the last key while the window took no text, which a runner hands
   * on where the system would have dropped it ({@link com.rethinkj.ui.window.WindowEvent.KeyText}):
   * a click into a field, Tab into one or a key that opens a dialog with a field that takes the
   * focus, and the keys typed right after it, may all arrive before the window turns its text
   * input on for the field. The next {@link #frame} gives it to the focused element as {@link
   * #textInput} does if the key went to an element that takes text, as those after the click or
   * Tab do; the text of a key that went elsewhere, as Space that presses a button and opens a
   * dialog, is dropped, as it would have been.
   */
  public void keyText(String text) {
    Objects.requireNonNull(text, "text");
    enqueue(() -> {
      if (keyToText) {
        wakeField();
      }
      if (!keyToText || focus.element() == null) {
        return false;
      }
      if (focus.element().editor() != null) {
        boolean changed = focus.element().editor().type(text);
        handled |= focus.element().editor().answerPending();
        return changed;
      }
      return giveText(text);
    });
  }

  /** Gives text to the focused element's {@link El#onText} or {@link El#onTypeAhead} handler. */
  private boolean giveText(String text) {
    if (focus.element().onText() != null) {
      focus.element().onText().accept(text);
    } else if (focus.element().onTypeAhead() != null) {
      String typed = focus.typeAhead(text, time);
      if (typed == null) {
        return false;
      }
      focus.element().onTypeAhead().accept(typed);
    } else {
      return false;
    }
    handled = true;
    return true;
  }

  /**
   * An input method composes text: the next {@link #frame} shows it in the focused text field
   * ({@link El#editable}), underlined in place of the selection, without changing the field's
   * string. The text it commits comes with {@link #textInput}; empty text ends the composition.
   *
   * @param cursor where the input method's cursor is in {@code text}, in UTF-16 units; -1 for its
   *     end
   */
  public void textComposition(String text, int cursor) {
    Objects.requireNonNull(text, "text");
    enqueue(() -> {
      if (focus.element() == null || focus.element().editor() == null) {
        return false;
      }
      Editor editor = focus.element().editor();
      boolean composed = editor.compose(text, cursor);
      handled |= editor.takeUnfolded();
      return composed;
    });
  }

  /** Where cut and copied text go and paste takes it from; {@link Clipboard#inMemory} at first. */
  public Clipboard clipboard() {
    return clipboard;
  }

  /** Sets the clipboard, as a window does with the system's. */
  public void clipboard(Clipboard clipboard) {
    this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
  }

  // ---- The system's dialogs, and what the system drops on the window

  /** What shows the system's dialogs: the runner's ({@link Dialogs}), {@link Dialogs#NONE} at first. */
  public Dialogs dialogs() {
    return dialogs;
  }

  /** Sets what shows the system's dialogs, as a runner does for its windows and a test may. */
  public void dialogs(Dialogs dialogs) {
    this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
  }

  /**
   * Shows a file dialog of the system, belonging to the app's window (modal to it where the
   * system can), and hands the user's choice to {@code onChoice}, or what went wrong to {@code
   * onError}, as a {@link #post posted} task: on the app's thread, with a frame after it. A
   * cancelled dialog is a {@link FileDialog.Choice} without paths. Call it on the app's thread,
   * usually in a handler; it returns at once, and the window goes on drawing while the dialog is
   * open. Under a {@link Script} no dialog opens: its {@code dialog} step answers, and the answer
   * arrives with {@code await}, as posted tasks do there.
   *
   * <pre>{@code
   * app.fileDialog(FileDialog.save("Save as").filter("CSV", "csv").fileName("orders.csv"),
   *     choice -> { if (!choice.cancelled()) export(choice.path()); },
   *     error -> status = "No dialog: " + error);
   * }</pre>
   */
  public void fileDialog(
      FileDialog dialog, Consumer<FileDialog.Choice> onChoice, Consumer<String> onError) {
    Objects.requireNonNull(dialog, "dialog");
    Objects.requireNonNull(onChoice, "onChoice");
    Objects.requireNonNull(onError, "onError");
    AtomicBoolean answered = new AtomicBoolean(); // once, whatever the implementation does
    dialogs.file(
        dialog,
        choice -> {
          if (answered.compareAndSet(false, true)) {
            post(() -> onChoice.accept(choice));
          }
        },
        error -> {
          if (answered.compareAndSet(false, true)) {
            post(() -> onError.accept(error));
          }
        });
  }

  /**
   * Shows a message box of the system and hands the index of the button pressed to {@code
   * onButton}, or what went wrong to {@code onError}, as a {@link #post posted} task. In a {@link
   * com.rethinkj.ui.window.WindowLoop} the box blocks the windows' thread until it closes, as the
   * systems' message boxes do: no window draws meanwhile, and tasks wait. It is for what cannot be
   * shown in the window; the kit's {@code Dialog} asks inside it. Under a {@link Script} no box
   * opens: its {@code dialog} step answers, as for {@link #fileDialog}.
   */
  public void messageBox(MessageBox box, IntConsumer onButton, Consumer<String> onError) {
    Objects.requireNonNull(box, "box");
    Objects.requireNonNull(onButton, "onButton");
    Objects.requireNonNull(onError, "onError");
    AtomicBoolean answered = new AtomicBoolean();
    dialogs.message(
        box,
        button -> {
          if (answered.compareAndSet(false, true)) {
            post(() -> onButton.accept(button));
          }
        },
        error -> {
          if (answered.compareAndSet(false, true)) {
            post(() -> onError.accept(error));
          }
        });
  }

  /** What opens web addresses: the runner's ({@link Browser}), {@link Browser#NONE} at first. */
  public Browser browser() {
    return browser;
  }

  /** Sets what opens web addresses, as a runner does for its windows and a test may. */
  public void browser(Browser browser) {
    this.browser = Objects.requireNonNull(browser, "browser");
  }

  /**
   * Opens a web address in the system's default browser, and hands what went wrong to {@code
   * onError} as a {@link #post posted} task. Only an absolute {@code http} or {@code https}
   * address opens; any other, such as one a document links to, goes to {@code onError}. Call it
   * on the app's thread, usually in a handler; it returns once it has handed the address to the
   * system, and {@code onError} hears only what the system reports then, not whether a browser
   * started or the page loaded.
   * Under a {@link Script} nothing opens: a {@code snap} writes the addresses opened so far.
   *
   * <pre>{@code
   * app.openUri(URI.create("https://ui.rethinkj.com"), error -> status = "No browser: " + error);
   * }</pre>
   */
  public void openUri(URI uri, Consumer<String> onError) {
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(onError, "onError");
    Consumer<String> failed = error -> post(() -> onError.accept(error));
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    // The authority, not getHost(), which is null for a host such as bücher.example.
    if (!(scheme.equals("http") || scheme.equals("https")) || uri.getRawAuthority() == null) {
      failed.accept(uri + " is not a web address: only http and https addresses open.");
      return;
    }
    browser.open(uri, failed);
  }

  /**
   * Something that another application drags, files from a file manager or text, is over the
   * window at this point: the {@link El#onFileDrop} handler there gets {@link Drop.Phase#OVER}
   * at the next frame, and the one it was over before {@link Drop.Phase#LEAVE}.
   */
  public void fileDragOver(float x, float y) {
    enqueue(() -> fileStep(x, y, null, null));
  }

  /** What another application dragged left the window without a drop: see {@link #fileDragOver}. */
  public void fileDragLeave() {
    enqueue(this::fileLeave);
  }

  /**
   * Another application dropped files or text at this point of the window. Text that lands on a
   * text field that takes edits goes into it as a paste at the point, with the focus; anything
   * else goes to the innermost {@link El#onFileDrop} handler there as {@link Drop.Phase#DROP},
   * with the files as a {@code List<Path>} or the text as a {@code String} for its payload.
   *
   * @param files the files dropped, empty for text
   * @param text the text dropped, or null for files
   */
  public void fileDrop(float x, float y, List<java.nio.file.Path> files, String text) {
    List<java.nio.file.Path> paths = List.copyOf(files);
    enqueue(() -> fileStep(x, y, paths, text));
  }

  /** A step of the system's drag; {@code files} is null while it is only over the window. */
  private boolean fileStep(float x, float y, List<java.nio.file.Path> files, String text) {
    settle();
    boolean end = files != null;
    if (end && text != null && files.isEmpty()) {
      List<El> hit = hit(x, y);
      El field = hit.isEmpty() ? null : hit.getLast();
      if (field != null
          && field.takesEdits()
          && field.editor() != null
          && field.editor().editable()) {
        fileLeave();
        focus.set(field, false, time);
        float[] at = local(field, x, y);
        Editor editor = field.editor();
        editor.press(
            editor.fromShown(field.text().nearest(at[0], at[1])),
            editor.fromShown(field.text().hit(at[0], at[1])),
            1,
            false);
        Clipboard dropped = Clipboard.inMemory();
        dropped.write(text);
        editor.paste(dropped);
        handled |= editor.answerPending();
        return true;
      }
    }
    Target target = target(x, y, el -> el.onFileDrop() != null);
    Object id = target == null ? null : target.id();
    boolean changed = !Objects.equals(fileOver, id) && fileLeave();
    if (target == null) {
      return changed;
    }
    double[] at = content(target.el, x, y);
    fileOver = end ? null : id;
    fileOverHandler = end ? null : target.el.onFileDrop();
    Object payload = !end ? null : files.isEmpty() ? text : files;
    target
        .el
        .onFileDrop()
        .accept(new Drop(end ? Drop.Phase.DROP : Drop.Phase.OVER, payload, at[0], at[1], time));
    handled = true;
    return true;
  }

  /** Tells the handler the system's drag was over that it left; whether there was one. */
  private boolean fileLeave() {
    Consumer<Drop> left = fileOverHandler;
    fileOver = null;
    fileOverHandler = null;
    if (left == null) {
      return false;
    }
    left.accept(new Drop(Drop.Phase.LEAVE, null, Double.NaN, Double.NaN, time));
    handled = true;
    return true;
  }

  /**
   * The windows of the runner that runs the app, with which a handler opens and closes windows
   * ({@link Windows}): a {@link com.rethinkj.ui.window.WindowLoop}'s or a {@link Script}'s. Until
   * a runner sets them, opening a window throws.
   */
  public Windows windows() {
    return windows;
  }

  /** Sets the runner's windows, as a {@link com.rethinkj.ui.window.WindowLoop} and a script do. */
  public void windows(Windows windows) {
    this.windows = Objects.requireNonNull(windows, "windows");
  }

  /**
   * The state changed outside the app's own handlers: the next {@link #frame} builds the view
   * again, as after a handler, before the input queued for it, which then goes to the new tree. A
   * runner calls it on its other apps of the same state object after a handler of one ran ({@link
   * #ranHandlers}); it is not input, and wakes nothing by itself.
   */
  public void invalidate() {
    stale = true;
    changedOutside = true;
  }

  /**
   * Whether the last {@link #frame} ran a handler of the app, a {@link #post posted} task or an
   * action of {@link #after}: what may have changed the state, which other apps of the same state
   * then show ({@link #invalidate}).
   */
  public boolean ranHandlers() {
    return ran;
  }

  /**
   * Where the focused element of the last frame that takes text is, for the system's input method:
   * an editable text field, or an element with an {@link El#onText} handler; null when there is
   * none, and then a window turns text input off. An element with only an {@link El#onTypeAhead}
   * handler has none: it gets the characters of keys, but nothing an input method composes.
   */
  public InputArea inputArea() {
    // A press on a field that no frame has dispatched yet: text typed right after it reaches the
    // app only while the window's text input is on, so it goes on at the press.
    El focused = typingSoon != null ? typingSoon : focus.element();
    if (focused == null) {
      return null;
    }
    Editor editor = focused.editor();
    if (editor == null ? focused.onText() == null : !editor.editable()) {
      return null;
    }
    List<El> path = new ArrayList<>();
    pathTo(root, focused, path);
    float x = 0;
    float y = 0;
    for (El el : fromOverlay(path)) {
      x += el.x();
      y += el.y();
    }
    float cursor = editor == null ? 0 : contentLeft(focused) + editor.caretOffset();
    boolean password = editor != null && focused.text().hidden();
    if (editor != null && focused.text().multiline()) { // the caret's row, inside the field
      Text t = focused.text();
      float row = t.lineHeight() / t.scale();
      float top = Math.clamp(
          contentTop(focused) + editor.caretTopOffset(), 0, Math.max(focused.height() - row, 0));
      return new InputArea(x, y + top, focused.width(), row, cursor, false);
    }
    return new InputArea(x, y, focused.width(), focused.height(), cursor, password);
  }

  /**
   * Whether the next {@link #frame} has handlers to run at once that the last one queued, such as
   * the {@link El#onRowsBuilt} handler of a list that built other rows, the {@link El#onResize}
   * handlers of a view that did not settle in its frame, or input and tasks that arrived since. A
   * runner that draws a single frame when the system asks for one, as a {@link
   * com.rethinkj.ui.window.WindowLoop} does during a live resize, draws again while this holds (a
   * few times at most), so that what they change shows in the same step instead of one step late.
   */
  public boolean handlersWaiting() {
    return scheduler.waiting() || resizeDue;
  }

  /**
   * The time on the app's clock at which the last frame changes without input or movement: when
   * the caret of the focused field turns on or off, when the pointer will have rested long enough
   * on an element for its {@link El#whenHoveredFor} look or {@link El#onHoverFor} handler, when
   * an action asked for with {@link #after} is due, or when a drag that rests over a drop handler
   * tells it again ({@link Drop#REPEAT}); the current time while the last frame showed
   * an element with an {@link El#cycle}, left rows to build that the measured heights of a list
   * ({@link El#rows(int, RowHeights, java.util.function.IntFunction)}) show, or left lines in view
   * waiting for their {@link El#highlight highlighting} to catch up. Positive infinity when nothing
   * will; then an idle window can wait for input alone.
   */
  public double nextChange() {
    if (scheduler.waiting()
        || resizeDue
        || focusMoved()
        || !firstEdits.isEmpty()
        || cycling
        || edgeScrolling
        || rowsWanted
        || hasPendingHighlights()
        || bracketsDue()) {
      // handlers that a frame queued, such as El#onRowsBuilt or the El#onFocusIn of a focus that
      // the last rebuild moved, an El#cycle, rows that the measured heights of a list show and the
      // last frame had no passes left for, or highlighting that hit its per-frame line budget
      return time;
    }
    double next = Math.min(restDue, shownDue);
    if (keyPressed != null && keyPressKey == null) {
      next = Math.min(next, keyPressUntil);
    }
    next = Math.min(next, scheduler.nextDue());
    Dragging drag = dragging();
    if (resting(drag)) {
      next = Math.min(next, drag.at + Drop.REPEAT);
    }
    if (blinking == null || !active || !blinking.showsCaret(true)) {
      return next;
    }
    return Math.min(next, blinkStart + (Math.floor((time - blinkStart) / BLINK) + 1) * BLINK);
  }

  /**
   * Runs {@code action} once the app's clock has moved on by {@code seconds}, for what happens
   * later without input, such as a delay or a timeout. It is not work on another thread ({@link
   * #background}): nothing runs meanwhile, and the clock alone decides when.
   *
   * <p>The action runs on the app's thread as a {@link #post posted} task does, as the handler of
   * an input: the first {@link #frame} that begins at or after its time queues it after the input
   * and the tasks that came before that frame, the actions due then in the order of their times
   * and then in the order they were asked for. It runs against the tree of the moment, the view is
   * built again before the next input, and the frame draws. One asked for while a frame runs, by
   * a handler or an action, waits for the next frame, even with a delay of 0. {@link #nextChange}
   * reports the next one, so that a window wakes for it; a script runs it with {@code advance} and
   * {@code frame}, and {@link #await} does not wait for it, since only the clock brings it. An
   * action that throws ends the frame as a task does. Call this on the app's thread, in a handler,
   * a task or an action, or before the first frame.
   *
   * @param seconds from the app's {@link #time}, 0 or more
   */
  public void after(double seconds, Runnable action) {
    if (!(seconds >= 0 && seconds < Double.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException(
          "seconds must be 0 or a positive finite number, was " + seconds);
    }
    scheduler.after(time + seconds, action);
  }

  /**
   * Queues what other threads posted, unless a script holds it, the actions of {@link #after}
   * that are due, as tasks, after what is queued, and the repeated {@link Drop.Phase#OVER} of a
   * drag that rests over a drop handler.
   */
  private void takeDue() {
    scheduler.takeArrived(time);
    Dragging d = dragging();
    if (resting(d) && time >= d.at + Drop.REPEAT) {
      scheduler.add(() -> repeat(d)); // after the input, which may move it on
    }
  }

  /**
   * The element of the last frame's {@link #tree} that has the keyboard focus, or null. Also null
   * while the focus is on a row of a virtual list that has scrolled out of the rows that exist:
   * the app keeps it, and the row is focused again when it comes back.
   *
   * <p>When a rebuild leaves out the focused element, or it can no longer take the focus (it is
   * no longer focusable, takes no input or is clipped away), the focus does not vanish: it goes to
   * the nearest element along the old element's identity path that is still there, below the
   * root, and either takes the focus itself or holds an element that does, then to that element:
   * the one inside it that had the focus last, else its {@link El#defaultFocus} one, else its
   * {@link El#autofocus} one, else the first in tree order, and then, as after {@link #focus(El)}
   * into a container, a {@link El#defaultFocus} element that appears there later takes it. This
   * returns that element from the same frame on. Closing the active tab from inside its content
   * puts the focus into the content of the tab that takes over, and an editor laid over a grid's
   * cell gives it back to the grid when it goes. Only when no ancestor below the root holds a
   * focusable element is the focus cleared, and this returns null. The ring stays as it was: where
   * a key moved the focus away, the new place shows it and scrolls into view; where a press did,
   * it does not.
   */
  public El focused() {
    return focus.element();
  }

  /**
   * The topmost element of the last frame's {@link #tree} under the pointer that takes input, the
   * one a press there begins at; null when the pointer is outside or over nothing. Clips, opacity 0
   * and elements that are leaving count as for presses. During a {@link El#onDrag drag} it is the
   * dragged element, wherever the pointer is.
   */
  public El hovered() {
    return hover.hovered();
  }

  /**
   * The element of the last frame's {@link #tree} that a press is on, between the pointer down
   * that went to it and the next pointer up, or null: an element with a press or drag handler, or
   * a text field that the press selects in. Also the element that Enter or Space pressed while it
   * has the focus, from the key going down until it is up and at least {@link #KEY_PRESS} after it
   * went down, unless it came up in the same frame.
   */
  public El pressed() {
    Object id = pressedId();
    return id == null ? null : animator.element(id);
  }

  /**
   * The shape of the pointer that the element under it asks for with {@link El#cursor}, or its
   * nearest ancestor that does; {@link Cursor#DEFAULT} where none does or the pointer is outside.
   * During a {@link El#onDrag drag} the dragged element decides, wherever the pointer is.
   */
  public Cursor cursor() {
    return hover.cursor();
  }

  /**
   * Gives the keyboard focus to an element of the last frame's {@link #tree} as Tab would, with a
   * ring and scrolled into view, or clears it with null. The next {@link #frame} shows it; a
   * handler may call this too, with an element of the tree it runs in, and the frame focuses right
   * after the handler, the element with the same identity in the tree built from what the handler
   * changed, if it is still there and can take the focus. The app keeps the focus on the element
   * of later trees that has the same identity; when there is none, or that element is no longer
   * focusable or takes no input, the focus goes where {@link #focused} says.
   *
   * <p>An element that is not focusable, such as a sidebar or the content of a tab, moves the
   * focus into it: to the element inside it that had the focus last, if it can still take it,
   * else to its {@link El#defaultFocus} element, else to the first one inside it that can, as the
   * focus finds a place when its element goes. In that last case, a {@link El#defaultFocus}
   * element that a later tree adds inside it takes the focus, as long as the focus has not moved;
   * until then, the element it is on does not count as the one inside that had it last.
   * The element inside is looked for in the tree built after the handler, so the handler can
   * change what the element holds and focus it at once; when nothing inside can take the focus,
   * the focus stays where it was.
   *
   * @throws IllegalArgumentException if the element is not in the last frame's tree, or is {@link
   *     El#focusable} and takes no input there: it is leaving, the view made it or an ancestor
   *     invisible with opacity 0, or it is clipped away. An element that fades in can take the
   *     focus from the frame that adds it on.
   */
  public void focus(El element) {
    if (element != null
        && !(element.isFocusable()
            ? focusables().contains(element)
            : root != null && pathTo(root, element, new ArrayList<>()))) {
      throw new IllegalArgumentException(
          "This element cannot take the focus: it is not in the last frame's tree, or it is"
              + " focusable() and takes no input there");
    }
    Object id = element == null ? null : animator.identity(element);
    boolean into = element != null && !element.isFocusable();
    boolean byKey = keyScroll; // asked by a key's handler: its reveal is the user's
    enqueue(() -> {
      keyScroll = byKey;
      focusByKey |= byKey;
      settle();
      El now = id == null ? null : animator.element(id);
      List<El> order = focusables();
      if (into) {
        now = now == null ? null : into(id, now, order);
        if (now == null) {
          return false;
        }
        boolean fallback = !now.isDefaultFocus() && focus.lastInside(id, order) == null;
        boolean due = focus.set(now, true, time);
        if (due && !isScopeSelected(now.editor())) {
          clearScopeSelection(null);
        }
        if (fallback) {
          focus.awaitInside(id);
        }
        return due;
      }
      boolean due =
          (id == null || now != null && order.contains(now)) && focus.set(now, true, time);
      if (due && (now == null || !isScopeSelected(now.editor()))) {
        clearScopeSelection(null);
      }
      return due;
    });
  }

  /**
   * Where {@link #focus(El)} puts the focus inside the element {@code scope} with identity {@code
   * id}: the element there that had it last and can still take it, else the first that can.
   */
  private El into(Object id, El scope, List<El> order) {
    El last = focus.lastInside(id, order);
    if (last != null) {
      return last;
    }
    El first = Focus.firstInside(scope, order);
    if (first == null) {
      return null;
    }
    El entered = enter(first, 0); // in a list, its row that had the focus last
    if (Animator.isBelow(animator.identity(entered), id)) {
      return entered;
    }
    return animator.identity(first) != null ? first : null; // enter's new rows may have dropped it
  }

  /**
   * Sets the color of the focus ring, as 0xAARRGGBB, for elements that set none themselves ({@link
   * El#focusRing(int)}); 0 draws none. The ring is 2 pixels wide and lies just outside the
   * element's border box, or its {@link El#focusRingGap} beyond it, with its corners, or just
   * inside it where the clips around the element or the window's edge would cut it ({@link
   * Scene}).
   */
  public void focusRing(int argb) {
    focus.ringColor(argb);
    stale = true;
  }

  /**
   * The window became active, with the system's keyboard focus, or inactive, as a window reports
   * it; an app is active until this says otherwise. From the next {@link #frame} on, the focused
   * text field of an inactive app shows no caret and {@link #nextChange} is infinite, so that an
   * idle inactive window draws nothing. The focus, the selection and the ring stay as they are.
   * When the app is active again, the caret is on and blinks from there. A change builds the view
   * again, in which {@link WindowState#CURRENT} says it, so that a title bar of the app's own looks
   * quieter while the window is inactive.
   */
  public void windowActive(boolean active) {
    enqueue(() -> {
      boolean changed = this.active != active;
      this.active = active;
      blinkStart = time;
      if (!active) { // its key comes up elsewhere, if at all
        if (capture instanceof Selecting) {
          capture = IDLE; // its release may come up elsewhere; an edge scroll would go on
        }
        keyPressed = null;
        keyPressKey = null;
        hover.clearModifiers();
      }
      return changed;
    });
  }

  /**
   * The window became maximized or was restored, as a window reports it ({@link
   * com.rethinkj.ui.window.WindowLoop} passes it on); the view is built again in the next {@link
   * #frame}, in which {@link WindowState#CURRENT} says it, so that a title bar of the app's own
   * shows Restore in place of Maximize.
   */
  public void windowMaximized(boolean maximized) {
    enqueue(() -> {
      if (this.maximized == maximized) {
        return false;
      }
      this.maximized = maximized;
      return true;
    });
  }

  /**
   * Whether the window that runs this app is maximized, as it last said ({@link
   * #windowMaximized(boolean)}); false without a window, as in a script.
   */
  public boolean windowMaximized() {
    return maximized;
  }

  /**
   * Sets how text is rasterized and blended, {@link TextRendering#platform} until this is called.
   * Tests pin it, so that their images are the same on every platform.
   */
  public void textRendering(TextRendering rendering) {
    textRendering = Objects.requireNonNull(rendering, "rendering");
    scene = null;
    stale = true;
  }
  /** The rendering of {@link #textRendering(TextRendering)}. */
  public TextRendering textRendering() {
    return textRendering;
  }

  /**
   * Sets the bytes of GPU memory the window's renderer keeps images in, {@link
   * Scene#DEFAULT_IMAGE_MEMORY} (256 MB) until this is called: beyond it, the images drawn least
   * recently are given up and uploaded again when they show again; the images of one frame are
   * always kept. See {@link Scene#imageMemory}.
   */
  public void imageMemory(long bytes) {
    if (bytes < 0) {
      throw new IllegalArgumentException(
          "The image memory is " + bytes + " bytes; it must be 0" + " or more.");
    }
    imageMemory = bytes;
  }

  /** The bytes of {@link #imageMemory(long)}. */
  public long imageMemory() {
    return imageMemory;
  }

  /** For {@link Script}: runs {@code handler} as the handler of an input, at its turn. */
  void request(Runnable handler) {
    enqueue(() -> {
      handled = true;
      handler.run();
      return true;
    });
  }

  /**
   * Sets whether characters that a text's font and its fallbacks lack come from the fonts installed
   * on the system ({@link Font#withSystemFonts}). Off until this is called, so that scripts and
   * tests draw the same on every machine; a {@link com.rethinkj.ui.window.WindowLoop} turns it on
   * when it starts, unless told otherwise. Turning it on starts reading the system's fonts on
   * another thread ({@link Font#prepareSystemFonts}), so that the first character that needs one
   * does not wait for that in its frame.
   */
  public void systemFonts(boolean on) {
    if (on) {
      Font.prepareSystemFonts();
    }
    if (on != systemFonts) {
      systemFonts = on;
      stale = true;
    }
  }

  /** Whether text falls back to the system's fonts; see {@link #systemFonts(boolean)}. */
  public boolean systemFonts() {
    return systemFonts;
  }

  /** Whether the focus {@link Focus#waits} on an editable text field, whose editor stays. */
  private boolean fieldWaits() {
    Editor editor = focusedEditor();
    return editor != null && focus.element() == null && editor.editable();
  }

  /** The focused field's editor, also while its row {@link Focus#waits}; null without one. */
  private Editor focusedEditor() {
    return focus.element() != null
        ? focus.element().editor()
        : focus.waits() ? editors.get(focus.id()) : null;
  }

  /**
   * Brings back the row of an editable field that has the focus and {@link Focus#waits}, so that
   * text and the keys that edit go into it, as an editor brings its caret back into view on
   * typing.
   */
  private void wakeField() {
    if (fieldWaits()) {
      rowGoals.clear(); // input of the user's, as a key: the list no longer follows a row
      wake();
    }
  }

  /** Whether a field takes this key to edit its string: deleting, pasting, cutting, undoing. */
  private static boolean edits(Key key) {
    int mods = key.modifiers();
    return switch (key.name()) {
      case "Backspace", "Delete" -> true;
      case "V", "X", "Z", "Y" -> (mods & Key.CONTROL) != 0;
      case "Insert" -> mods == Key.SHIFT;
      default -> false;
    };
  }

  /** Brings the row of a focus that {@link Focus#waits} into view, so that Tab goes on there. */
  private void wake() {
    if (focus.element() == null && focus.waits()) {
      Animator.InRow in = animator.inRow(focus.id());
      El list = in.list();
      keyRevealed(in.listId(), list.scrollTo(list.scrollX(), list.offsetShowing(in.row())));
      // A list that the areas around it scrolled out of the window builds no rows: show the row.
      float[] row = list.rowBox(in.row());
      reveal(list, 0, row[0], list.width(), row[1], false, null, 0);
      fillRows();
      placeEditors(false, false); // the parts of its fields, where the focus may be
      refocus();
      if (editFirst(true, false, toldSizes)) { // before the field takes the input; refocuses
        handled = true;
      }
    }
  }

  /**
   * Where Tab goes instead of {@code next} when that is in a virtual list and the focus is not: to
   * the element that last had the focus in the list, else to the first focusable element of the
   * first row in view, or the last one of the last row in view for Shift+Tab, rather than to the
   * first row the list built. The row is built and scrolled into view if needed. A {@code step} of
   * 0 focuses into the list as {@link #focus(El)} does: to the element of the row that {@link
   * Focus#firstInside} picks.
   */
  private El enter(El next, int step) {
    Object nextId = animator.identity(next);
    Animator.InRow in = animator.inRow(nextId);
    if (in == null || Animator.isBelow(focus.id(), in.listId())) {
      return next;
    }
    El list = in.list();
    Object last = focus.lastInList(in.listId());
    Animator.InRow was = last == null ? null : animator.inRow(last);
    int row = was != null && was.listId().equals(in.listId()) && was.row() < list.rowCount()
        ? was.row()
        : list.rowInView(step < 0);
    if (!list.hasRow(row)) {
      boolean moved = list.scrollTo(list.scrollX(), list.offsetShowing(row));
      scrolled |= moved;
      keyRevealed(in.listId(), moved);
      fillRows();
      placeEditors(false, false); // the parts of its fields, which Tab may enter
      if (editFirst(true, false, toldSizes)) { // before Tab selects in the field: a tree anew
        handled = true;
        list = animator.element(in.listId());
        next = Objects.requireNonNullElse(animator.element(nextId), next);
      }
      if (list == null || !list.hasRow(row)) {
        return next;
      }
    }
    El rowEl = list.children().get(row - list.firstRow());
    Set<El> inside = Collections.newSetFromMap(new IdentityHashMap<>());
    collect(rowEl, inside);
    List<El> order = focusables();
    if (step != 0) {
      order.removeIf(El::skipsTab); // as for the order outside: the focus is not in the list
    }
    El again = last == null ? null : animator.element(last);
    if (again != null && inside.contains(again) && order.contains(again)) {
      return again;
    }
    if (step == 0) {
      El chosen = Focus.firstInside(rowEl, order);
      return chosen != null ? chosen : next;
    }
    El found = null;
    for (El el : order) {
      if (inside.contains(el) && (found == null || step < 0)) {
        found = el;
      }
    }
    return found != null ? found : next;
  }

  private static void collect(El el, Set<El> into) {
    into.add(el);
    for (El child : el.children()) {
      collect(child, into);
    }
  }

  /**
   * Finds the focused element in a new tree by its identity, or where the focus goes when it is
   * gone or can no longer take it ({@link Focus#refocus}), and scrolls it into view if that is due.
   */
  private void refocus() {
    El shown = focus.refocus(this::focusables, time);
    if (shown != null) {
      reveal(shown);
      focusByKey = false;
      boolean rebuilt = fillRows();
      rebuilt |= placeEditors(false, false);
      if (rebuilt) {
        refocus(); // its row or field part was built again
      }
    }
  }

  /** The first scroll area in tree order inside {@code el} that can move up or down so. */
  private static El pageArea(El el, int direction) {
    if (el.isScrollArea() && el.canScroll(false, direction)) {
      return el;
    }
    for (El child : el.children()) {
      El found = pageArea(child, direction);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /**
   * The focused element and its ancestors, from the root; only the root without focus. While the
   * focus is on a row out of range, the path ends at the row's list.
   */
  private List<El> focusPath() {
    List<El> path = new ArrayList<>();
    El end = focus.element() != null
        ? focus.element()
        : focus.waits() ? animator.inRow(focus.id()).list() : null;
    if (root != null && (end == null || !pathTo(root, end, path))) {
      path.add(root);
    }
    El modal = modal();
    if (modal != null) { // keys end at it
      int at = path.indexOf(modal);
      return at >= 0 ? path.subList(at, path.size()) : new ArrayList<>(List.of(modal));
    }
    return path;
  }

  /** Appends the path from {@code el} down to {@code target} if {@code target} is below it. */
  private static boolean pathTo(El el, El target, List<El> path) {
    path.add(el);
    if (el == target) {
      return true;
    }
    for (El child : el.children()) {
      if (pathTo(child, target, path)) {
        return true;
      }
    }
    for (El overlay : el.overlays()) {
      if (pathTo(overlay, target, path)) {
        return true;
      }
    }
    path.removeLast();
    return false;
  }

  /**
   * Scrolls the scroll areas around {@code el}, the innermost first, just far enough to show its
   * border box whole inside theirs and clear of the bars over their ends, or its start if it is
   * larger; with its focus ring as far as an area's padding has room for it.
   */
  private void reveal(El el) {
    reveal(el, 0, 0, el.width(), el.height(), true, null, 0);
  }

  /**
   * Scrolls the scroll areas around {@code el}, the innermost first, just far enough to show the
   * rectangle {@code (rx, ry, rw, rh)} relative to {@code el}'s border box whole inside theirs,
   * below the top {@code covered} of {@code cover} on the path where that lies over an area.
   */
  private boolean reveal(
      El el, float rx, float ry, float rw, float rh, boolean withRing, El cover, float covered) {
    List<El> full = new ArrayList<>();
    if (root == null || !pathTo(root, el, full)) {
      return false;
    }
    List<El> path = fromOverlay(full); // the areas around an overlay stay where they are
    int coverAt = -1;
    float coverY = 0;
    for (int j = 0; cover != null && covered > 0 && j < path.size(); j++) {
      coverY += path.get(j).y();
      if (path.get(j) == cover) {
        coverAt = j;
        break;
      }
    }
    boolean moved = false;
    for (int i = path.size() - 2; i >= 0; i--) {
      El area = path.get(i);
      if (!area.isScrollArea()) {
        continue;
      }
      // The bar lies at the cover's top, which the areas inside it leave in place; an area around
      // it moves both, and the bar stops at the window's top, so it is to clear that.
      float coveredTo =
          coverAt < 0 ? Float.NEGATIVE_INFINITY : i >= coverAt ? coverY + covered : covered;
      float areaX = 0;
      float areaY = 0;
      for (int j = 0; j <= i; j++) {
        areaX += path.get(j).x();
        areaY += path.get(j).y();
      }
      float x = areaX + rx;
      float y = areaY + ry;
      for (int j = i + 1; j < path.size(); j++) {
        x += path.get(j).x();
        y += path.get(j).y();
      }
      El next = path.get(i + 1);
      if (area.isFieldPart(next) || area.isGutterColumn(next)) {
        float viewX = areaX + area.border(Side.LEFT) + area.padding(Side.LEFT);
        float viewY = areaY + area.border(Side.TOP) + area.padding(Side.TOP);
        float viewW = Math.max(
            0,
            area.width()
                - area.border(Side.LEFT)
                - area.padding(Side.LEFT)
                - area.border(Side.RIGHT)
                - area.padding(Side.RIGHT)
                - area.coverX());
        float viewH = Math.max(
            0,
            area.height()
                - area.border(Side.TOP)
                - area.padding(Side.TOP)
                - area.border(Side.BOTTOM)
                - area.padding(Side.BOTTOM)
                - area.coverY());
        double nextX = area.isFieldPart(next)
            ? area.scrollX() + revealDelta(x, rw, viewX, viewW)
            : area.scrollX();
        float shown = coveredTo > viewY && coveredTo < viewY + viewH ? coveredTo - viewY : 0;
        if (area.scrollTo(
            nextX, area.scrollY() + revealDelta(y, rh, viewY + shown, viewH - shown))) {
          moved = true;
          if ((keyScroll || focusByKey) && animator.wasSeen(area)) {
            revealedScrolls.add(animator.identity(area));
          }
        }
        continue;
      }
      // With its ring as far as the area's padding has room for it, so that the clip cuts none
      // of it; rows that fill a list edge to edge come flush, their rings drawn inside.
      float ring = withRing ? Scene.RING + el.focusRingGap() : 0;
      float left = Math.min(ring, area.padding(Side.LEFT));
      float right = Math.min(ring, area.padding(Side.RIGHT));
      float up = Math.min(ring, area.padding(Side.TOP));
      float down = Math.min(ring, area.padding(Side.BOTTOM));
      float viewH = area.height() - area.coverY();
      float shown = coveredTo > areaY && coveredTo < areaY + viewH ? coveredTo - areaY : 0;
      if (area.scrollTo(
          area.scrollX()
              + revealDelta(x - left, rw + left + right, areaX, area.width() - area.coverX()),
          area.scrollY() + revealDelta(y - up, rh + up + down, areaY + shown, viewH - shown))) {
        moved = true;
        if ((keyScroll || focusByKey) && animator.wasSeen(area)) {
          revealedScrolls.add(animator.identity(area));
        }
      }
    }
    return moved;
  }

  /** Compares both edges in window coordinates, at the precision used to place the focus ring. */
  private static double revealDelta(float start, float size, float viewStart, float viewSize) {
    return ScrollState.nearestEdge(start - viewStart, start + size - (viewStart + viewSize));
  }

  /**
   * The elements Tab moves through, in tree order: those that are focusable, take input and are not
   * clipped away for good. An element that fades in counts from the frame that adds it, whatever
   * its opacity on the way, and one the view makes invisible, or that is leaving, does not. Along
   * an axis an ancestor scrolls, an element outside its visible part counts, since focusing it
   * scrolls it into view.
   */
  List<El> focusables() {
    List<El> found = new ArrayList<>();
    if (root != null) {
      float far = Float.POSITIVE_INFINITY;
      El modal = modal(); // the focus stays inside it
      focusables(
          modal != null ? modal : root, 0, 0, -far, -far, far, far, root.shownOverlays(), found);
    }
    return found;
  }

  /** The overlays shown come right after their anchor and what is inside it. */
  private static void focusables(
      El el,
      float parentX,
      float parentY,
      float clipLeft,
      float clipTop,
      float clipRight,
      float clipBottom,
      List<El> shown,
      List<El> found) {
    if (!el.isPlaced() || el.targetOpacity() == 0 || el.isExiting() || el.isInert()) {
      return;
    }
    float left = parentX + el.x();
    float top = parentY + el.y();
    float right = left + el.width();
    float bottom = top + el.height();
    if (el.isFocusable()
        && left < clipRight
        && right > clipLeft
        && top < clipBottom
        && bottom > clipTop) {
      found.add(el);
    }
    Style s = el.style();
    if (s.overflowX != Style.Overflow.VISIBLE || s.overflowY != Style.Overflow.VISIBLE) {
      clipLeft = Math.max(left, clipLeft);
      clipTop = Math.max(top, clipTop);
      clipRight = Math.min(right, clipRight);
      clipBottom = Math.min(bottom, clipBottom);
      float far = Float.POSITIVE_INFINITY;
      if (s.overflowX == Style.Overflow.SCROLL) {
        clipLeft = -far;
        clipRight = far;
      }
      if (s.overflowY == Style.Overflow.SCROLL) {
        clipTop = -far;
        clipBottom = far;
      }
    }
    if (clipRight > clipLeft && clipBottom > clipTop) { // else nothing below shows
      for (El child : el.children()) {
        float l = clipLeft;
        float r = clipRight;
        if (el.isFieldPart(child)) { // cut off at the content box, as far as the field scrolls
          l = Math.max(
              l, left + el.border(Side.LEFT) + el.padding(Side.LEFT) - (float) el.scrollX());
          r = Math.min(
              r,
              right
                  - el.border(Side.RIGHT)
                  - el.padding(Side.RIGHT)
                  - el.coverX()
                  + (float) (el.maxScrollX() - el.scrollX()));
        }
        if (r > l) {
          focusables(child, left, top, l, clipTop, r, clipBottom, shown, found);
        }
      }
    }
    float far = Float.POSITIVE_INFINITY;
    for (El overlay : el.overlays()) {
      if (shown.contains(overlay)) {
        focusables(overlay, 0, 0, -far, -far, far, far, shown, found);
      }
    }
  }

  /**
   * For {@link Script}: focuses as {@link #focus} does the element at this point, or the nearest
   * focusable one around it, and returns it; null if there is none.
   */
  El focusAt(float x, float y) {
    List<El> focusables = focusables();
    Target target = target(x, y, el -> el.isFocusable() && focusables.contains(el));
    if (target == null) {
      return null;
    }
    focus(target.el);
    return target.el;
  }

  /**
   * The wheel turned with the pointer at this position, by these distances in logical pixels.
   * Positive values scroll towards the end, so that the content moves up or to the left, as {@code
   * deltaY} of a browser's wheel event; SDL counts the other way. Along each axis the innermost
   * scroll area under the pointer that can still move that way takes the whole distance, clamped
   * to its content; when none can, nothing happens. A wheel that turns only up and down, as a
   * mouse's does, moves an area that scrolls only sideways ({@code overflow-x: scroll} without
   * {@code overflow-y: scroll}, such as a row of tabs) sideways, positive to the right. The next
   * {@link #frame} shows it. Outside the overlays that are open, the wheel calls the {@link
   * El#onDismiss} handlers of those above the pointer and scrolls what is under it in the same
   * tree; outside a {@link El#modal} one it does nothing. The same as {@link #wheel(float, float,
   * float, float, int)} without modifiers.
   */
  public void wheel(float x, float y, float deltaX, float deltaY) {
    wheel(x, y, deltaX, deltaY, 0);
  }

  /**
   * Like {@link #wheel(float, float, float, float)}, with these modifiers held. Before anything
   * scrolls, the {@link El#onWheel} handlers under the pointer are asked, the innermost first,
   * each before its own scrolling and only while no scroll area inside it can move with the
   * wheel; the first that takes the wheel ends it, and when none does, it scrolls as without them.
   *
   * @param modifiers a combination of {@link Key#SHIFT}, {@link Key#CONTROL}, {@link Key#ALT} and
   *     {@link Key#META}
   */
  public void wheel(float x, float y, float deltaX, float deltaY, int modifiers) {
    if (!Float.isFinite(deltaX) || !Float.isFinite(deltaY)) {
      throw new IllegalArgumentException(
          "wheel deltas must be finite, were " + deltaX + ", " + deltaY);
    }
    enqueue(() -> {
      rowGoals.clear();
      if (rowsPending) {
        settle(); // the rows and their overlays, which a rebuild between inputs left out
      }
      if (dismiss(x, y, false)) {
        return false; // outside a modal overlay
      }
      List<El> areas = hit(x, y).reversed();
      boolean sideways = deltaX == 0;
      for (El el : areas) {
        if (el.onWheel() != null) {
          double[] at = content(el, x, y);
          if (el.onWheel().wheel(deltaX, deltaY, modifiers, at[0], at[1])) {
            handled = true;
            return true;
          }
        }
        if (el.canScroll(true, deltaX)
            || el.canScroll(false, deltaY)
            || sideways && scrollsSideways(el, deltaY)) {
          break; // it scrolls: the handlers around it are not asked
        }
      }
      El movedX = scroll(areas, true, deltaX, false);
      El movedY = scroll(areas, false, deltaY, sideways);
      if (movedX != null || movedY != null) {
        scrolled = true;
        boolean changed = false;
        if (capture instanceof Selecting s && s.scopeId() != null) {
          El scope = animator.liveElement(s.scopeId());
          El area = scope == null ? null : scopeScrollArea(scope);
          if (area != null && (movedY == area || movedX == area)) {
            changed = settle(); // it takes the scroll the frame would draw
            changed |= drag(s, s.x, s.y);
          }
        }
        int from = movedX == null
            ? areas.indexOf(movedY)
            : movedY == null
                ? areas.indexOf(movedX)
                : Math.min(areas.indexOf(movedX), areas.indexOf(movedY));
        return changed | tellUserScroll(areas.subList(from, areas.size()));
      }
      return false;
    });
  }

  /** @param sideways whether an area that scrolls only sideways takes a vertical delta sideways */
  private static El scroll(List<El> areas, boolean horizontal, float delta, boolean sideways) {
    for (El area : areas) {
      if (area.canScroll(horizontal, delta)) {
        boolean moved = horizontal
            ? area.scrollTo(area.scrollX() + delta, area.scrollY())
            : area.userScrollTo(area.scrollX(), area.orEnd(area.scrollY() + delta));
        return moved ? area : null;
      }
      if (sideways && scrollsSideways(area, delta)) {
        return area.scrollTo(area.scrollX() + delta, area.scrollY()) ? area : null;
      }
    }
    return null;
  }

  /** Whether an area that scrolls only sideways can move by this vertical delta sideways. */
  private static boolean scrollsSideways(El area, float delta) {
    return area.style().overflowX == Style.Overflow.SCROLL
        && area.style().overflowY != Style.Overflow.SCROLL
        && area.canScroll(true, delta);
  }

  /**
   * Scrolls a scroll area of the last frame's {@link #tree} to this offset from its start, in
   * logical pixels: to the top with 0, 0, for example after new data arrived, or to the end with
   * {@link Double#POSITIVE_INFINITY}. The next {@link #frame} shows it, clamped to the content;
   * until then {@link El#scrollX} and {@link El#scrollY} keep the old offset. A handler may call
   * this too, with an area of the tree it runs in; the frame scrolls right after the handler, the
   * area with the same identity in the tree built from what the handler changed.
   */
  public void scrollTo(El area, double x, double y) {
    if (Double.isNaN(x) || Double.isNaN(y)) {
      throw new IllegalArgumentException("scroll offset is NaN");
    }
    Object id = animator.identity(area);
    enqueue(() -> {
      settle();
      El el = id == null ? null : animator.element(id);
      scrolled |= el != null && el.scrollTo(x, y);
      rowGoals.remove(id);
      return false;
    });
  }

  /**
   * Scrolls a virtual list ({@link El#rows}) of the last frame's {@link #tree} so that the row
   * with this index is at the top of its content box, or as far as the end of the list allows: to
   * an offset of {@code row * rowHeight}. The next {@link #frame} shows it and builds the rows it
   * brings into view. A handler may call this too, as {@link #scrollTo}. Where the list is too
   * short for that yet, or the rows before it turn out another height, as while a document's
   * images are still on their way, the list goes there once it can, until the user's next press,
   * key or turn of the wheel, or until the list no longer has the row.
   *
   * @param row the index of the row, from 0; one beyond the last scrolls to the end
   * @throws IllegalArgumentException if {@code list} is not a virtual list or {@code row} is
   *     negative
   */
  public void scrollToRow(El list, int row) {
    if (!list.isVirtualList()) {
      throw new IllegalArgumentException(
          "This element is not a list of rows; El.rows(count, rowHeight, row) makes one.");
    }
    if (row < 0) {
      throw new IllegalArgumentException("row must be 0 or more, was " + row);
    }
    Object id = animator.identity(list);
    enqueue(() -> {
      settle();
      El el = id == null ? null : animator.element(id);
      scrolled |= el != null && el.scrollTo(el.scrollX(), el.rowOffset(row));
      if (el != null) {
        el.toEnd(false); // the later row wins over a pending end
        rowGoals.put(id, new RowGoal(row, el.rowsVersion(), el.rowKey(row)));
      }
      return false;
    });
  }

  /** Takes each list that {@link #scrollToRow} sent to a row there again; whether one moved. */
  private boolean followRowGoals() {
    boolean moved = false;
    for (Iterator<Map.Entry<Object, RowGoal>> i = rowGoals.entrySet().iterator(); i.hasNext(); ) {
      Map.Entry<Object, RowGoal> goal = i.next();
      El el = animator.element(goal.getKey());
      int row = el == null || !el.isVirtualList() ? -1 : goal.getValue().row();
      int field = goal.getValue().field();
      if (row >= 0 && el.rowsVersion() != goal.getValue().version()) { // rows came or went
        row = el.rowWithKey(goal.getValue().key());
        goal.setValue(new RowGoal(row, el.rowsVersion(), goal.getValue().key(), field));
      }
      if (row < 0 || row > el.rowCount()) {
        i.remove(); // the list or the row is gone: one that comes back is not followed
      } else if (field >= 0) {
        moved |= revealField(el, row, field);
      } else {
        moved |= el.scrollTo(el.scrollX(), el.rowOffset(row));
      }
    }
    return moved;
  }

  /**
   * Scrolls {@code list} so that the field with ordinal {@code field} in row {@code row} shows: the
   * row, the row of a list in it that holds the field, or the field once it is built.
   *
   * @return whether that scrolled
   */
  private boolean revealField(El list, int row, int field) {
    double y = list.scrollY();
    if (!list.hasRow(row)) {
      list.scrollTo(list.scrollX(), list.offsetShowing(row));
    } else {
      El target = builtScopeRowField(list, row, field);
      Object[] inner =
          target != null ? null : unbuiltRowOf(list.children().get(row - list.firstRow()), field);
      float[] caret =
          target == null || target.editor() == null ? null : target.editor().caretBox();
      if (caret != null) { // where the selection's caret is, below a find bar over the scope
        List<El> path = new ArrayList<>();
        El scope = root != null && pathTo(root, target, path) ? scopeOf(path) : null;
        reveal(
            target,
            caret[0],
            caret[1],
            caret[2],
            caret[3],
            false,
            scope,
            scope == null ? 0 : scope.scopeCoveredTop());
      } else if (target != null) {
        reveal(target, 0, 0, target.width(), target.height(), false, null, 0);
      } else if (inner != null) {
        El nested = (El) inner[0];
        double[] span = nested.rowSpan((Integer) inner[1]);
        reveal(nested, 0, (float) span[0], nested.width(), (float) span[1], false, null, 0);
      }
    }
    return list.scrollY() != y;
  }

  /** Queues input for the next frame, or what a handler asks for right after that handler. */
  private void enqueue(BooleanSupplier input) {
    scheduler.enqueue(input);
  }

  // ---- Work off the app's thread

  /**
   * Hands the app a task to run on its thread, at its turn among the input: the one method of an
   * app that may be called on any thread. It never blocks. The task joins the queue of input after
   * the input that came before it, and the tasks of one thread keep their order; {@link #frame}
   * runs it like the handler of an input that a handler took, against the tree of the moment, so
   * that the view is built again before the next input and the frame draws. A task posted while a
   * frame runs, by a handler too, waits for the next frame. Only the task touches the state, on the
   * app's thread; the thread that posts it hands over what it computed, as in
   *
   * <pre>{@code
   * Thread.ofVirtual().start(() -> {
   *   List<Row> rows = db.query(sql); // not the state: the app's thread may be reading it
   *   app.post(() -> state.rows = rows);
   * });
   * }</pre>
   *
   * <p>{@link #background} does that with a failure and cancellation. A runner that waits for
   * events, such as a {@link com.rethinkj.ui.window.WindowLoop}, wakes up for a task ({@link
   * #onPost}); without one, the next frame runs it. In a {@link Script}, tasks wait for its {@code
   * await} step.
   *
   * <p>A task that throws ends the frame there, as a handler does: the exception comes out of
   * {@link #frame}, the input after the task stays queued, a {@code WindowLoop} ends with it and
   * a script fails at its {@code frame} step. Once the app's window has closed, tasks still queue
   * and wait: they run if the app runs frames again, and are dropped with the app otherwise.
   */
  public void post(Runnable task) {
    scheduler.post(task);
  }

  /**
   * Sets what {@link #post} calls, on the posting thread, when a task arrives and the app has not
   * taken the ones before it: a runner that waits for events ends its wait with it, as a {@link
   * com.rethinkj.ui.window.WindowLoop} does while it runs. A burst of tasks calls it once, and
   * again only for a task that arrives after the app took the burst. Null for nothing, as without
   * a window.
   */
  public void onPost(Runnable wake) {
    scheduler.onPost(wake);
  }

  /**
   * Runs {@code compute} on a virtual thread of its own and hands its result to {@code onResult},
   * or what it throws to {@code onFailure}, on the app's thread as a {@link #post posted} task. Call
   * it on the app's thread, usually in a handler, and keep the {@link Work} to {@link Work#cancel}
   * it: once cancelled, neither handler runs, so that the answer of a request the user closed or
   * asked again never arrives.
   *
   * <p>The computation gets only what the handler gave it, in its closure: values it copied from
   * the state, a connection, an SQL string. It must not touch the app's state or its elements, nor
   * call the app but {@link #post}: they belong to the app's thread, which reads them while it
   * builds the view, and nothing guards them from a second thread. Only the handlers change the
   * state, on the app's thread, in the order of the input.
   *
   * <pre>{@code
   * void open(Table table) { // a handler, on the app's thread
   *   if (loading != null) {
   *     loading.cancel(); // the rows asked for before are not wanted any more
   *   }
   *   String sql = "SELECT * FROM " + table.name();
   *   loading = app.background("rows of " + table.name(), () -> db.query(sql),
   *       rows -> { this.rows = rows; loading = null; },
   *       error -> { this.error = error.getMessage(); loading = null; });
   * }
   * }</pre>
   *
   * <p>An {@link Error} the computation throws, such as running out of memory, is thrown on the
   * app's thread when its turn comes, as from a task. Virtual threads do not keep the JVM alive:
   * work that runs when {@code main} returns is dropped. {@link #await} waits for the work, which
   * makes scripts and tests deterministic.
   *
   * <p>A cancelled computation runs on until it returns, which the work's {@link Work#onEnd} tells
   * the app. Work that must not overlap, as the calls on one connection, goes on a {@link #lane},
   * which begins each piece once the one before it has ended.
   *
   * @param name what the work does, for the thread and for {@link #await}'s message, such as
   *     {@code "rows of orders"}
   * @return the work, to cancel it
   */
  public <T> Work background(
      String name,
      Callable<? extends T> compute,
      Consumer<? super T> onResult,
      Consumer<? super Exception> onFailure) {
    Objects.requireNonNull(compute, "compute");
    return scheduler.start(null, name, work -> compute, onResult, onFailure);
  }

  /**
   * Runs {@code compute} as {@link #background(String, Callable, Consumer, Consumer)} does, and
   * hands it a {@link Work.Progress} on which it reports on its way, from its own thread: each
   * report runs {@code onProgress} on the app's thread as a {@link #post posted} task does, and
   * the reports end with the work. Once the work is {@linkplain Work#cancel cancelled}, or its
   * result or failure has reached its handler, no {@code onProgress} runs any more, also for
   * reports that wait in the queue, and a report made after that is dropped where it is made.
   * Reports that wait one after the other are coalesced into the newest ({@link
   * Work.Progress#report} says when), so a value that stands for the whole progress suits them.
   *
   * <pre>{@code
   * download = app.background("download of " + driver.name(),
   *     (Work.Progress<Long> progress) -> {
   *       try (InputStream in = driver.open(); OutputStream out = Files.newOutputStream(file)) {
   *         byte[] buffer = new byte[8192];
   *         long bytes = 0;
   *         for (int n; (n = in.read(buffer)) > 0; ) { // cancel() interrupts the read
   *           out.write(buffer, 0, n);
   *           progress.report(bytes += n);
   *         }
   *         return file;
   *       }
   *     },
   *     bytes -> this.received = bytes,
   *     file -> { this.driver = file; download = null; },
   *     error -> { this.error = error.getMessage(); download = null; });
   * }</pre>
   *
   * <p>Type the computation's parameter, as {@code Work.Progress<Long>} above, where the handlers
   * do not say what a report is. A script lets the reports in with {@code await progress} and
   * tests with {@link #awaitProgress}, while the work still runs.
   *
   * @param name what the work does, for the thread and the messages of {@link #await} and {@link
   *     #awaitProgress}
   * @param <P> the type of a report
   * @return the work, to cancel it
   */
  public <T, P> Work background(
      String name,
      Work.Reporting<? extends T, P> compute,
      Consumer<? super P> onProgress,
      Consumer<? super T> onResult,
      Consumer<? super Exception> onFailure) {
    return scheduler.start(null, name, compute, onProgress, onResult, onFailure);
  }

  /**
   * Makes a {@link Lane}, on which work runs one piece after another: each begins once the work
   * started before it on the lane has ended, also work that was cancelled while it ran. Keep it in
   * the state, one per resource that one thread at a time may use, such as a connection.
   *
   * @param name what the lane stands for, such as the connection's name
   */
  public Lane lane(String name) {
    return new Lane(scheduler, name);
  }

  /**
   * Waits until all the work that {@link #background} started has finished or was cancelled, and
   * then queues what it delivered, the {@link Work#onEnd} handlers of what ended, and everything else posted so far, for the next {@link #frame},
   * in the order it was posted. Scripts and tests call it where they want the answers, between
   * frames, so that what they see does not depend on how fast the work was: {@code frame} after it
   * shows them. Work that a delivery starts is not waited for; call this again for it. Tasks that
   * other threads post on their own are not waited for either: they come with the first call after
   * they were posted. Work on a {@link Lane} that has not begun yet is waited for too, and so is
   * cancelled work with an {@link Work#onEnd} handler, until its computation has returned; other
   * cancelled work is not.
   *
   * @throws IllegalStateException if work still runs after {@code timeout}, naming it, or if this
   *     is called in a handler, whose thread it would block
   * @throws InterruptedException if the thread is interrupted while it waits
   */
  public void await(Duration timeout) throws InterruptedException {
    scheduler.await(work -> true, timeout);
  }

  /**
   * Waits as {@link #await(Duration)} does, but only for the work of this {@code name} that {@link
   * #background} started, and then queues what was posted so far for the next {@link #frame}:
   * what that work delivered, and what other work delivered or reported by then. Other work runs
   * on, such as work a test holds at a gate to show what the app shows meanwhile, together with
   * the result of a short piece of work beside it. Returns at once when no work of the name runs.
   *
   * @param name the name the work was started with, such as {@code "save of the history"}
   * @throws IllegalStateException if work of the name still runs after {@code timeout}, or if this
   *     is called in a handler, whose thread it would block
   * @throws InterruptedException if the thread is interrupted while it waits
   */
  public void await(String name, Duration timeout) throws InterruptedException {
    Objects.requireNonNull(name, "name");
    scheduler.await(work -> work.name().equals(name), timeout);
  }

  /**
   * Waits until work that {@link #background(String, Work.Reporting, Consumer, Consumer, Consumer)
   * reports} has reported something that has not been let in yet, without waiting for the work to
   * end, and then queues the reports, and everything else posted so far, for the next {@link
   * #frame}, as {@link #await} does. A test or script shows work half done with it: the work
   * reports and then waits for something the test controls, such as a latch it opens or an action
   * of {@link #after} that the next {@link #advance} runs, so that what the frame shows does not
   * depend on how fast the work was. A report that waits already, also of work that has ended
   * since, lets this return at once; the result of work that ended comes with it, after its
   * reports.
   *
   * @throws IllegalStateException if nothing reported within {@code timeout}, naming the work that
   *     runs; at once if no work runs and nothing it reported waits; or if this is called in a
   *     handler, whose thread it would block
   * @throws InterruptedException if the thread is interrupted while it waits
   */
  public void awaitProgress(Duration timeout) throws InterruptedException {
    Scheduler.awaitProgress(List.of(scheduler), timeout);
  }

  /** For {@link Script}: the innermost virtual list at this point, or null. */
  El listAt(float x, float y) {
    Target target = target(x, y, El::isVirtualList);
    return target == null ? null : target.el;
  }

  /**
   * Moves the app's clock forward. A windowed runner calls this once per frame with the time since
   * the last one; tests step it by hand. Nothing else reads a clock, except to tell how long a
   * frame took ({@link #timing}).
   */
  public void advance(double seconds) {
    if (!(seconds >= 0 && seconds < Double.POSITIVE_INFINITY)) {
      throw new IllegalArgumentException(
          "seconds must be 0 or a positive finite number, was " + seconds);
    }
    time += seconds;
  }

  /** The app's clock in seconds, 0 at the start. */
  public double time() {
    return time;
  }

  /**
   * Runs a frame: dispatches the input that came since the last one, in its order, then builds the
   * tree if anything may have changed and moves what is moving.
   *
   * <ol>
   *   <li>The tasks other threads {@link #post posted} since the app last took them join the
   *       queue, after the input that came before them, and then the actions of {@link #after}
   *       that are due; those posted or asked for from here on wait for the next frame.
   *   <li>Each input in turn finds the element it goes to in the tree of the moment, overlays
   *       first, and runs its handler, and each posted task runs as the handler of an input; a
   *       press or the wheel outside overlays first runs their dismiss handlers, and a focus or
   *       scroll that a handler or task asks for runs right after it. Before an input that follows
   *       one a handler of the app or a task took, the view is built again as in step 5, so that
   *       it sees what that handler changed, but the rows of its lists only if the focus is in
   *       one. A press, a release, a secondary press, Tab, a focus and a scroll
   *       the app asks for build the rows that this or a scroll left to build, the wheel and a
   *       drag those that this left, and they place the overlays and the bars, before they look
   *       for their element.
   *   <li>Unless step 5 builds everything: the rows that scrolling brought into view, or that a
   *       rebuild between two inputs left out, are built, and the overlays and the bars placed.
   *   <li>Unless a handler of the app ran, the focus or its ring moved or a field gained or lost
   *       the focus, so that the view is built again anyway: the pointer is followed into the
   *       tree as it is now, what it is over decides the looks, and the {@link El#onHoverFor}
   *       handlers whose delay has passed run. Otherwise this waits for the new tree, since one
   *       built between inputs may lack the rows under the pointer. Where those looks differ from
   *       the ones the tree has only in hover looks of elements in rows of virtual lists, and no
   *       such handler ran, those lists build the rows that have a look or should have one anew,
   *       as scrolling does, and the pointer is followed into them again.
   *   <li>If anything may have changed (one of the above, or the looks of the moment still differ
   *       from those the tree has), the view is built again: it gets the looks of the moment ({@link
   *       El#whenHovered}, {@link El#whenHoveredFor}) and its text fields their editing state, the
   *       overlays that open take the focus and those that close give it back, it is laid out with
   *       its overlays, takes over the values and offsets of the tree before, and its virtual lists
   *       build their rows, each row the same way; the overlays are placed beside their anchors,
   *       the focus is found again, the carets and the bars are placed. Then the pointer is
   *       followed into it as in step 4; if that tree puts other elements under the still pointer
   *       than the one before, or an {@link El#onHoverFor} handler ran, the view is built once more
   *       with the looks of the moment. Otherwise the elements that move get the values of the
   *       moment, lists that moved build the rows they show now, and the overlays follow.
   *   <li>The caret and selection of each field are placed, the caret blinks while the window is
   *       active ({@link #windowActive}), the bars are placed and the thumb under the pointer
   *       gets its color.
   * </ol>
   *
   * @return whether there is something new to draw: the tree was rebuilt, something moved or
   *     scrolled
   */
  public boolean frame() {
    long start = System.nanoTime();
    buildNanos = 0;
    frames++;
    resizeRounds = 0;
    highlightBudget.reset();
    rowOrdinalCache.clear();
    if (keyPressed != null && keyPressKey == null && time >= keyPressUntil) {
      keyPressed = null; // the look of a key that is up ends; wanted() then differs from shown
    }
    ran = false;
    keyScroll = false;
    revealedScrolls.clear();
    Object wasFocused = focus.id();
    boolean hadRing = focus.ring();
    boolean drawn = false;
    takeDue();
    while (scheduler.waiting()) {
      if (handled || changedOutside) { // the next input goes to a tree built from the state now
        build(handled, false); // after invalidate alone with its rows: an action may look for them
        drawn = true;
      }
      if (focus.waits()) {
        drawn |= settle(); // the focused row that a scroll of this frame brought back takes input
      }
      if (!firstEdits.isEmpty() && editFirst(true, false, toldSizes)) {
        drawn = handled = true; // of rows that motion built: before the input, after it a last tree
      }
      if (untold() || hasPendingUnfolds() || focusMoved() || !revealedScrolls.isEmpty()) {
        // A selection, focus move or key-driven reveal a build applied, also of rows that
        // scrolling filled: a FieldState's, an autofocus, or the app's answer to an edit. It
        // reaches onSelect, onFocusIn and onUserScroll as a handler's input would, before the next
        // input: what the handler asks for and changes comes first.
        scheduler.addFirst(() -> {
          boolean told = tellUnfolds() | tellSelections() | tellFocusIn() | tellRevealedScrolls();
          rowsKept |= told;
          return told;
        });
      }
      scheduler.beginHandler();
      keyScroll = false; // the build above was the last input's; keyDown sets it for its own
      try {
        stale |= scheduler.poll().getAsBoolean();
        stale |= tellUnfolds() | tellSelections() | tellFocusIn() | tellRevealedScrolls();
      } catch (RuntimeException | Error e) {
        handled = true; // it may have changed the state: the next input gets a tree built anew
        throw e;
      } finally {
        scheduler.endHandler();
      }
    }
    long inputEnd = System.nanoTime();
    long inputBuild = buildNanos;
    if (edgeScrolling && (handled || changedOutside)) {
      build(handled, false); // the edge scroll goes on in the view built from the state now
      drawn = true;
    }
    if (edgeScroll()) {
      drawn = true;
    }
    stale |= shownFor();
    ran |= handled;
    stale |= handled || !Objects.equals(focus.id(), wasFocused) || focus.ring() != hadRing;
    stale |= resizeDue; // sizes changed in the input, or a view has not settled
    if (bracketsDue()) {
      stale = true;
      for (Editor editor : editors.values()) {
        if (editor.lineIndex() != null) {
          editor.lineIndex().unread = -1;
        }
      }
    }
    stale |= blink(focus.element() == null ? null : focus.element().editor());
    if (stale) { // the rebuild builds all rows anyway
      drawn |= scrolled;
      scrolled = false;
    } else {
      drawn |= settle(); // the rows that scrolling or a rebuild between inputs left to build
      stale |= endsMoved(root)
          | tellUnfolds()
          | tellSelections() // of fields in those rows, which the rebuild shows
          | tellFocusIn()
          | tellRevealedScrolls()
          | !firstEdits.isEmpty(); // of fields in rows that an earlier frame built
    }
    // What the pointer is over now, in the tree on screen, after the scrolls moved it. Not in a
    // tree built between inputs, which may lack the rows under the pointer and would end their
    // stays: the rebuild below asks again.
    if (!stale) {
      List<El> path = pointed();
      Looks looks = wanted(path, Animator.identities(path));
      if (!looks.equals(shown) && relookRows(looks)) {
        drawn = true;
        path = pointed();
        looks = wanted(path, Animator.identities(path));
      }
      stale = !looks.equals(shown);
    }
    boolean laidOut = false;
    if (stale) {
      // Twice when the first build moved other elements under the still pointer, as a key that
      // scrolls a list does, or an onHoverFor handler ran: the looks are those of what the
      // pointer is over in the tree on screen.
      try {
        for (int pass = 0; pass < 2 && stale; pass++) {
          rebuild();
          // A caret that the app's string moved, or of a new field, or a focus move: told once.
          if (tellUnfolds()
              | tellSelections()
              | tellFocusIn()
              | tellRevealedScrolls()
              | pendingBracketFolds()) {
            rebuild();
          }
          List<El> path = pointed();
          stale = !wanted(path, Animator.identities(path)).equals(shown);
        }
      } finally {
        keyScroll = false;
        revealedScrolls.clear();
        scheduler.takeAsked();
      }
      stale = false;
      drawn = true;
    } else if (animator.isMoving()) {
      drawn = true;
      animator.present(time);
      if (fillRows()) { // a list that moves may show other rows
        placeEditors(false, false);
        focus.openAndCloseParts(root, time);
        refocus();
        laidOut = true;
      }
    }
    keyScroll = focusByKey = false;
    revealedScrolls.clear();
    if (laidOut) {
      placeEditors(true, false);
    }
    if (root != null && hasPendingHighlights()) {
      drawn |= catchUpHighlights();
    }
    boolean on = active
        && blinking != null
        && blinking.showsCaret(true)
        && Math.floor((time - blinkStart) / BLINK) % 2 == 0;
    if (on != caretOn) {
      caretOn = on;
      if (blinking != null) {
        int asking = El.autofocuses();
        blinking.place(true, on);
        El field = blinking.element();
        if (field.takePartsBuilt()) { // a block a look took was built anew
          finishBuiltParts(field, animator.identity(field), asking);
          if (!displaced.isEmpty()) {
            sweepEditors(); // a part's field took another's identity after the frame's sweep
          }
        }
      }
      drawn = true;
    }
    if (drawn) {
      El.placeBars(root);
    }
    drawn |= updateHover();
    if ((drawn || cycling) && root != null) { // El#cycle: after everything that places elements
      cycling = El.cycles(root, time, width, height, true);
      drawn |= cycling;
    }
    edgeScrolling = updateEdgeScrollState();
    tellInView(); // the rows as the frame shows them, after everything that places elements
    rowOrdinalCache.clear();
    long input = inputEnd - start - inputBuild;
    timing = new Timing(input, buildNanos, System.nanoTime() - start - input - buildNanos);
    return drawn;
  }

  /**
   * Builds the view at the end of a frame, after its input, as {@link #build} does; then runs the
   * {@link El#onResize} handlers that layout told a new size, as handlers of input, and builds
   * again from what they changed, until no size changes or after {@link #RESIZE_ROUNDS} rounds in
   * the frame: a toolbar shows what fits a new width in the frame that has it, and input goes to
   * the tree as it was shown.
   */
  private void rebuild() {
    resizeDue = false;
    try {
      build(false, true);
      while (resizeRounds < RESIZE_ROUNDS && (!resized.isEmpty() || endsMoved(root))) {
        resizeRounds++;
        keyScroll = false; // what onResize handlers change is not the key's
        tellResized();
        build(false, true);
      }
      // An answer of atEnd that the last round made wrong: the next frame builds the view again.
      resizeDue |= resizeRounds >= RESIZE_ROUNDS && endsMoved(root);
    } finally {
      // What is left, of a view that has not settled or after a throw: the next frame's last
      // build tells it again, from its own tree.
      if (!resized.isEmpty()) {
        toldSizes.keySet().removeAll(resized.keySet());
        resized.clear();
        resizeDue = true;
      }
    }
  }

  /** Runs the handlers in {@link #resized} as handlers of input; what they ask for waits. */
  private void tellResized() {
    scheduler.beginHandler();
    try {
      for (Object id : List.copyOf(resized.keySet())) {
        resized.get(id).getAsBoolean();
        resized.remove(id); // told: a throw leaves only it and those after it
      }
    } catch (RuntimeException | Error e) {
      handled = true; // as in frame: the next input gets a tree built anew
      throw e;
    } finally {
      scheduler.endResizeHandler();
    }
  }

  /**
   * How long the parts of the last {@link #frame} took, for an app that keeps to a budget; all 0
   * before the first. A {@link com.rethinkj.ui.window.WindowLoop} adds drawing and presenting
   * ({@link com.rethinkj.ui.window.WindowLoop#frames}).
   */
  public Timing timing() {
    return timing;
  }

  /**
   * Builds the view from the state, gives it the looks of the moment and its fields their editing
   * state, lays it out, hands it to the animator and builds the rows of its virtual lists; then
   * finds the focus in it again and places the fields' carets and the scrollbars.
   *
   * @param between whether more input of this frame waits for the tree. Unless the focus is in a
   *     row, the rows, the carets and the bars are then left to {@link #settle}, before the next
   *     input that needs them: most keys need only the elements around the focus, and the rows
   *     are most of what a rebuild of a data grid costs.
   * @param last whether this is the frame's last build, after its input, which tells the {@link
   *     El#onResize} handlers; another only notes that sizes changed, for the last to tell
   */
  private void build(boolean between, boolean last) {
    Map<Object, Long> told = toldSizes;
    buildTree(between, last);
    if (editFirst(between, last, told) && !last) {
      handled = true; // the frame builds its last tree for what the field's handler changed
    }
  }

  private void buildTree(boolean between, boolean last) {
    rowOrdinalCache.clear();
    List<Object> ids = Animator.identities(pointed());
    long start = System.nanoTime();
    scopeIndexes.clear(); // the view's rows may say something else now
    El built = WindowState.CURRENT.with(
        new WindowState(active, maximized, scale), () -> view.apply(state));
    buildNanos += System.nanoTime() - start;
    El.fix(built);
    shown = applyLooks(built, ids);
    root = built;
    builds++;
    remapScopeOutsideFields(root); // before attachEditors so attached fields see updated ordinals
    attachEditors(root, new ArrayList<>()); // before layout, which shows a composition
    El.scaleText(root, scale, systemFonts);
    Flex.layout(root, width, height);
    Overlays.layout(root, width, height);
    // The sizes layout gave, before motion takes them along; an element that left is forgotten.
    Map<Object, Long> sizes = new HashMap<>();
    tellSizes(root, new ArrayList<>(), sizes);
    if (!last) { // the input after it may change the tree: the last build tells from its own
      resizeDue |= !sizes.equals(toldSizes); // also an element that left, to be forgotten
      resized.clear();
    } else {
      toldSizes = sizes;
    }
    animator.update(root, time, keyScroll ? revealedScrolls : null);
    remapScopeSelection();
    untimed = true;
    stale = false;
    ran |= handled;
    handled = false;
    changedOutside = false;
    scrolled = false;
    rowsPending = between
        && !rowsKept
        && !focus.claimed() // what the rows ask for waits for no later build, which has no claim
        && (focus.id() == null
            || animator.inRow(focus.id()) == null && !Animator.inFieldPart(focus.id()));
    rowsKept = false;
    endPresses(false);
    if (rowsPending) {
      caretOverlays =
          Overlays.place(root, width, height); // as fillRows does: the focus may be in one
      timeOverlays(true);
      refocus();
      return;
    }
    fillRows();
    placeEditors(false, false);
    focus.openAndCloseParts(root, time);
    refocus();
    placeEditors(true, false);
    focus.endClaim(); // rows that scrolling builds later ask as usual
    El.placeBars(root);
  }

  /**
   * Builds what the tree lacks for an input that hit tests, moves the focus or scrolls, and for
   * the end of a frame: the rows of its virtual lists that a {@link #rebuild} between two inputs
   * left out or that scrolling brought into view, the carets and selections of the fields, and the
   * bars where the offsets put them.
   *
   * @return whether there was anything to build or place
   */
  private boolean settle() {
    if (!scrolled && !rowsPending && !rowsWanted) {
      return false;
    }
    boolean pending = rowsPending;
    scrolled = false;
    rowsPending = false;
    boolean built = fillRows();
    boolean parts = placeEditors(false, false);
    if (built || pending || parts) {
      focus.openAndCloseParts(root, time);
      refocus();
    }
    placeEditors(true, false);
    El.placeBars(root);
    if (editFirst(true, false, toldSizes)) {
      handled = true; // the frame builds its last tree for what the field's handler changed
    }
    return true;
  }

  /**
   * Runs the edits that {@link FieldState#replace} and {@link FieldState#replaceSelection} kept
   * for fields not yet built, now that the tree that attached them is complete and before they
   * take input, one at a time, building the view again for the app's answer to each.
   *
   * @return whether an edit went to its field's handler
   */
  private boolean editFirst(boolean between, boolean last, Map<Object, Long> told) {
    if (firstEdits.isEmpty()) {
      return false;
    }
    boolean edited = false;
    boolean outside = !scheduler.dispatching();
    if (outside) {
      scheduler.beginHandler(); // what the fields' handlers ask for runs before the next input
    }
    try {
      while (!firstEdits.isEmpty()) {
        FieldState state = firstEdits.removeFirst();
        El now = state.app == this && !gone(state.id, true) ? animator.element(state.id) : null;
        if (state.pending == null
            || now == null
            || now.fieldState() != state
            || state.editor.answerPending()) {
          continue; // the next build that attaches the field takes them up again
        }
        Consumer<Editor> step = state.pending.removeFirst();
        if (state.pending.isEmpty()) {
          state.pending = null;
        }
        handled = true; // a handler that throws may have changed the state
        step.accept(state.editor);
        if (state.editor.answerPending()) {
          edited = true;
          rowsKept = true; // the field may be in a row
          toldSizes = told; // the sizes are told from the tree that answers
          resized.clear();
          buildTree(between, last); // attaches the field again if more edits wait
        } else if (state.pending != null) {
          firstEdits.add(state); // refused before the handler, as by a read-only field
        }
      }
    } finally {
      if (outside) {
        scheduler.endHandler();
      }
    }
    return edited;
  }

  /**
   * Follows the focused field's editor: one that loses the focus ends its composition, and one
   * that gains it or changes turns its caret on for a while.
   *
   * @return whether a field lost or gained the focus
   */
  private boolean blink(Editor editing) {
    boolean moved = editing != blinking;
    if (moved) {
      if (blinking != null) {
        blinking.blur();
      }
      blinking = editing;
      if (editing != null) {
        editing.focusGained();
      }
    }
    if (editing != null && (editing.takeChanged() || moved)) {
      blinkStart = time;
    }
    return moved;
  }

  /**
   * Gives each field below {@code el} its editor, by its identity, before layout: the fields of a
   * tree the view has just built, and those of rows a virtual list has just built. {@code path}
   * leads from the root to the parent of {@code el}. {@link #placeEditors} forgets the editors of
   * fields that are gone.
   */
  private boolean attachEditors(El el, List<El> path) {
    path.add(el);
    boolean attached = false;
    if (el.isEditable() && el.editor() == null) {
      attached = true;
      Object id = Animator.identities(path).getLast();
      FieldState state = el.fieldState();
      Editor editor = state == null ? editors.get(id) : null;
      if (state == null && (editor == null || editor.element().fieldState() != null)) {
        if (capture instanceof Selecting s && s.id().equals(id) && s.scopeId() == null) {
          capture = IDLE; // a press on a field that moved on, which the new editor never had
        }
        if (editor != null) {
          displaced.add(editor);
        }
        editor = new Editor(el); // not the editor of a field with a FieldState that moved on
        editors.put(id, editor);
      }
      if (state != null) {
        if (state.app == this && state.build == builds && !state.id.equals(id)) {
          throw new IllegalStateException("Two text fields have the same FieldState. Give each"
              + " field a FieldState of its own, kept in the app's state with what it edits.");
        }
        if (state.app != this && state.app != null && state.editor != null) {
          state.editor.leave(); // from another app's tree, as a tab to another window: once more
        }
        if (state.id != null && !state.id.equals(id) && editors.get(state.id) == state.editor) {
          editors.remove(state.id); // the field moved
        }
        state.app = this;
        state.build = builds;
        state.id = id;
        if (state.editor == null) {
          state.editor = new Editor(el, state.document);
        }
        editor = state.editor;
        displaced.remove(editor);
        Editor was = editors.put(id, editor);
        if (was != editor) { // back in the tree, maybe where another field was
          // A scope's drag holds positions, not this field.
          if (capture instanceof Selecting s && s.id().equals(id) && s.scopeId() == null) {
            capture = IDLE;
          }
          if (was != null) {
            if (was.element().fieldState() == null) {
              was.drop();
            } else {
              displaced.add(was);
            }
          }
          editor.returned();
        }
      }
      editor.highlightBudget(highlightBudget);
      editor.attach(el);
      if (state != null && state.caret >= 0) {
        if (capture instanceof Selecting s && s.id().equals(id)) {
          capture = IDLE;
        }
        editor.select(state.caret, state.anchor);
        state.caret = -1;
      }
      if (state != null && state.pending != null && !firstEdits.contains(state)) {
        firstEdits.add(state);
      }
      el.editor(editor);
      // El#ellipsis shortens it without the focus, but not while it shows a part of a scope's
      // selection, which Editor#place keeps in view.
      boolean inScopeSelection = applyScopeSelectionOnAttach(el, path, id, editor);
      el.text().unfocused(!hasFocus(id, state) && !inScopeSelection);
    }
    for (El child : el.children()) {
      attached |= attachEditors(child, path);
    }
    for (El overlay : el.overlays()) {
      attached |= attachEditors(overlay, path);
    }
    path.removeLast();
    return attached;
  }

  /**
   * Whether the field with identity {@code id} and {@code state} has the focus, also when it moved
   * since the focus found it: {@link Focus#refocus} follows a field by its FieldState.
   */
  private boolean hasFocus(Object id, FieldState state) {
    FieldState focused = focus.element() == null ? null : focus.element().fieldState();
    return focused != null ? focused == state : id.equals(focus.id());
  }

  /**
   * Places the caret, selection, scroll and post-layout parts of every field in the tree. When
   * {@code sweep} is true, updates the blinking editor from the current focus and forgets editors
   * of fields that left the tree, only after all field parts are built and registered so fields
   * inside rebuilt parts keep their editors across scrolling and rebuilds.
   *
   * @return whether any field built or updated its post-layout parts
   */
  private boolean placeEditors(boolean sweep, boolean force) {
    Editor prevBlinking = blinking;
    boolean blinkMoved = false;
    if (sweep) {
      blinkMoved = blink(focus.element() == null ? null : focus.element().editor());
    }
    // By element: a field rebuilt in a part keeps its editor and must be placed again.
    Set<El> placed = Collections.newSetFromMap(new IdentityHashMap<>());
    boolean partsBuilt = false;
    boolean needOverlays = caretOverlays;
    for (int round = 0; round < 4; round++) {
      Set<Editor> scopeSelected = Collections.newSetFromMap(new IdentityHashMap<>());
      Set<Editor> scopeChanged = Collections.newSetFromMap(new IdentityHashMap<>());
      syncScopeSelection(scopeSelected, scopeChanged);
      List<Editor> shown = new ArrayList<>();
      collectEditors(root, shown);
      List<Editor> toPlace = new ArrayList<>();
      for (Editor editor : shown) {
        if (placed.contains(editor.element())) {
          continue;
        }
        boolean needs = force
            || !sweep
            || round > 0
            || scopeChanged.contains(editor)
            || (blinkMoved && (editor == blinking || editor == prevBlinking))
            || (editor.element().text().multiline() && editor.element().text().viewStale());
        if (needs) {
          toPlace.add(editor);
        }
      }
      if (toPlace.isEmpty()) {
        break;
      }
      int asking = El.autofocuses();
      for (Editor editor : toPlace) {
        boolean focused = editor == blinking
            && (sweep
                || hasFocus(
                    animator.identity(editor.element()), editor.element().fieldState()));
        editor.place(focused, focused || scopeSelected.contains(editor), caretOn);
        placed.add(editor.element());
      }
      // A coupled field follows its leader again with what layout measured, a few times at most;
      // one still moving then follows on at the next placing.
      boolean moved = true;
      for (int pass = 0; pass < 3 && moved; pass++) {
        moved = false;
        for (Editor editor : shown) {
          ScrollState scroll = editor.element().scrollState();
          moved |= scroll != null && scroll.refollow(editor.element());
        }
        for (Editor editor : shown) {
          Text text = editor.element().text();
          if (text.multiline() && text.viewStale()) { // a single-line field's flag stays set
            boolean focused = editor == blinking
                && (sweep
                    || hasFocus(
                        animator.identity(editor.element()), editor.element().fieldState()));
            editor.place(focused, focused || scopeSelected.contains(editor), caretOn);
            placed.add(editor.element());
            moved = true; // what it measured now, such as a long line's width, may let it follow on
          }
        }
      }
      for (Editor editor : shown) {
        ScrollState scroll = editor.element().scrollState();
        if (scroll != null && !moved) { // settled
          scroll.followX = false;
          scroll.followY = false;
        }
      }
      if (caretOverlays) {
        needOverlays = true;
      }
      List<El> builtFields = new ArrayList<>();
      for (Editor editor : shown) {
        El field = editor.element();
        if (field.takePartsBuilt()) {
          Object fieldId = animator.identity(field);
          if (fieldId != null) {
            if (finishParts(field, fieldId, asking)) {
              placed.remove(field); // a block a look made higher: its rows make room next round
            }
            builtFields.add(field);
          }
        }
      }
      if (builtFields.isEmpty()) {
        break;
      }
      partsBuilt = true;
      // Place overlays of the newly built parts before filling any virtual lists inside them.
      caretOverlays = Overlays.place(root, width, height);
      needOverlays = false;
      if (hover.in()) {
        // An overlay just placed on a part may now be under the pointer; apply its hover looks.
        List<Object> under = pointedIds();
        boolean reapplied = false;
        for (El field : builtFields) {
          Object fieldId = animator.identity(field);
          if (fieldId == null) {
            continue;
          }
          for (Object id : under) {
            if (Animator.isInFieldPart(id, fieldId) && !this.shown.hovered().contains(id)) {
              if (finishParts(field, fieldId, asking)) {
                placed.remove(field);
              }
              reapplied = true;
              break;
            }
          }
        }
        if (reapplied) {
          caretOverlays = Overlays.place(root, width, height);
        }
      }
      if (fillRows()) {
        needOverlays = false;
      }
    }
    if (partsBuilt) {
      endPresses(true); // of field parts that left the tree
      timeOverlays();
    } else if (needOverlays) {
      Overlays.place(root, width, height);
    }
    if (sweep) {
      if (partsBuilt) {
        refocusPart();
      }
      sweepEditors();
    }
    return partsBuilt;
  }

  /**
   * Forgets the editors of fields that are gone from the current tree. The editor of a focused
   * field whose row scrolled out of the rows that exist stays, with the focus, for when the row
   * comes back, and so does one whose selection is pending ({@link El#isSelectionPending}) while
   * its row is out, both of a list that is not leaving.
   */
  private void sweepEditors() {
    List<Editor> shown = new ArrayList<>();
    collectEditors(root, shown);
    Set<Editor> kept = Collections.newSetFromMap(new IdentityHashMap<>());
    kept.addAll(shown);
    // The focused field's editor waits for its row, but not in a list that is leaving or gone;
    // false: a list in a row that is out of an outer list's rows comes back
    Editor waiting = focus.waits() && !gone(animator.inRow(focus.id()).listId(), false)
        ? editors.get(focus.id())
        : null;
    editors.entrySet().removeIf(entry -> {
      Editor editor = entry.getValue();
      FieldState state = editor.element().fieldState();
      if (editor == waiting
          || kept.contains(editor)
          || (state == null
              && editor.selectionPending()
              && focus.waits(entry.getKey())
              && !gone(animator.inRow(entry.getKey()).listId(), true))) {
        return false; // an edit's answer, or onSelect's hearing of it, waits for its row
      }
      if (state == null) {
        editor.drop(); // gone for good; its element may be fading out
      } else if (state.app == this) { // not taken by another app's tree meanwhile
        editor.leave(); // its element may be fading out
      }
      return true;
    });
    for (Editor editor : displaced) {
      if (editor != waiting
          && !kept.contains(editor)
          && editor.element().fieldState().app == this) {
        editor.leave(); // its field left the tree, not moved to another identity
      }
    }
    displaced.clear();
  }

  /**
   * Finishes parts a field just built outside {@link #placeEditors} (such as on a caret blink):
   * applies looks, attaches editors, registers identities, lays out and places overlays, and fills
   * any nested virtual lists.
   */
  private void finishBuiltParts(El field, Object fieldId, int asking) {
    if (fieldId == null) {
      return;
    }
    boolean again = finishParts(field, fieldId, asking);
    caretOverlays = Overlays.place(root, width, height);
    if (fillRows() || again) {
      placeEditors(false, false);
    } else {
      List<Editor> nested = new ArrayList<>();
      for (El part : field.children()) {
        if (part.fieldPartKey() != null) {
          collectEditors(part, nested);
        }
      }
      if (!nested.isEmpty()) {
        for (Editor editor : nested) {
          boolean focused = editor == blinking;
          editor.place(focused, focused || isScopeSelected(editor), caretOn);
        }
        for (Editor editor : nested) {
          El sub = editor.element();
          if (sub.takePartsBuilt()) {
            finishBuiltParts(sub, animator.identity(sub), asking);
          }
        }
        caretOverlays = Overlays.place(root, width, height);
      }
    }
    timeOverlays();
    refocusPart();
  }

  /** Follows the focus to the part built anew with its identity, as a placement after a rebuild does. */
  private void refocusPart() {
    // Also after an autofocus in a part asked for it, which leaves no element until refocus.
    if (focus.id() != null && !focus.waits() && focus.element() != animator.element(focus.id())) {
      refocus();
    }
  }

  /**
   * Runs the post-build steps for a field whose post-layout parts were built or updated: applies
   * looks, attaches editors inside parts and their overlays, re-layouts parts whose looks or
   * editors changed them, lays out overlays of every built part, records autofocuses, and
   * registers the parts' identities in the animator.
   *
   * @return whether a block a look made higher wants the field placed again
   */
  private boolean finishParts(El field, Object fieldId, int asking) {
    Set<El> looked = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<El> stale = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<El> relooked = gutterLooks(field, fieldId, looked, stale);
    List<El> path = new ArrayList<>();
    boolean hasPath = pathTo(root, field, path);
    boolean again = false;
    for (El part : stale) {
      again |= field.forgetBlock(part);
    }
    Set<El> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    // Twice: a look that changes a line-end element's width places its line again, and may bring
    // elements into view.
    for (int pass = 0; pass < 2; pass++) {
      for (El part : List.copyOf(field.children())) {
        if (part.fieldPartKey() == null || !seen.add(part)) {
          continue;
        }
        boolean attached = hasPath && attachEditors(part, path);
        if (relooked.contains(part) || attached) {
          again |= field.relayoutPart(part, looked, scale, systemFonts);
        }
        Overlays.layout(part, width, height);
      }
    }
    if (!relooked.isEmpty()) {
      field.dropPartCaches(again); // parts keep no look a later scroll of this tree would show
    }
    if (El.autofocuses() != asking) {
      focus.ask(animator.autofocusInFieldParts(field, fieldId), time);
    }
    animator.fieldParts(field, fieldId, relooked, time);
    return again;
  }

  /**
   * Advances highlighting for the fields in the tree that were left with plain lines in view, and
   * places editors again if any laid-out line received its spans.
   */
  private boolean catchUpHighlights() {
    List<Editor> shown = new ArrayList<>();
    collectEditors(root, shown);
    boolean gained = false;
    for (Editor editor : shown) {
      gained |= editor.element().text().advanceHighlight();
    }
    if (gained) {
      placeEditors(true, true);
    }
    return gained;
  }

  /** Whether any field in the tree still has laid-out lines waiting for highlighting. */
  private boolean hasPendingHighlights() {
    for (Map.Entry<Object, Editor> entry : editors.entrySet()) {
      if (inTree(entry) && entry.getValue().element().text().highlightPending()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a bracket lookup of a field in the tree ({@link El#matchingBracket}) met a line whose
   * state was not known, or ran out of the frame's budget there, and the state is known now: the
   * view builds again, so that it reads the pair the highlighter marks.
   */
  private boolean bracketsDue() {
    for (Map.Entry<Object, Editor> entry : editors.entrySet()) {
      Lines lines = entry.getValue().lineIndex();
      if (lines != null && lines.unreadKnown() && inTree(entry)) {
        return true;
      }
    }
    return false;
  }

  /** Whether a field asked for {@link El#bracketFolds} before its lines were attached. */
  private boolean pendingBracketFolds() {
    boolean pending = false;
    for (Map.Entry<Object, Editor> entry : editors.entrySet()) {
      if (inTree(entry) && entry.getValue().element().takeBracketFoldsPending()) {
        pending = true;
      }
    }
    return pending;
  }

  /**
   * Applies the looks of the moment to the post-layout parts a field has built (gutter cells,
   * fold marks, line-end elements, blocks and inline hints), as {@link #rowLooks} does to a list's
   * rows: hover, hover-rest, focus, focus-rest, focus-within and pressed looks; those the old
   * parts had go with them.
   *
   * @param looked receives every element that took a new look in this call
   * @param stale receives the blocks cached with a look that no longer applies
   * @return the direct built parts of {@code field} containing an element in {@code looked}
   */
  private Set<El> gutterLooks(El field, Object fieldId, Set<El> looked, Set<El> stale) {
    Set<El> relooked = Collections.newSetFromMap(new IdentityHashMap<>());
    List<Object> hovered = new ArrayList<>(shown.hovered());
    List<Object> rested = new ArrayList<>(shown.rested());
    List<Object> within = new ArrayList<>(shown.within());
    hovered.removeIf(id -> Animator.isInFieldPart(id, fieldId));
    rested.removeIf(id -> Animator.isInFieldPart(id, fieldId));
    within.removeIf(id -> Animator.isInFieldPart(id, fieldId));
    Object focused = Animator.isInFieldPart(shown.focused(), fieldId) ? null : shown.focused();
    boolean focusRested =
        Animator.isInFieldPart(this.focus.id(), fieldId) ? false : shown.focusRested();
    Object press = Animator.isInFieldPart(shown.pressed(), fieldId) ? null : shown.pressed();
    for (Object id : pointedIds()) {
      if (Animator.isInFieldPart(id, fieldId)) {
        El el = Animator.find(field, fieldId, id);
        // A part kept from an earlier pass keeps the looks it took there.
        if (el != null && el.hoverLook() != null) {
          hovered.add(id);
          if (field.partLooks(el).add("whenHovered") && look(el, El::hoverLook, "whenHovered")) {
            looked.add(el);
          }
        }
        if (el != null && el.restLook() != null) {
          if (field.partLooks(el).contains("whenHoveredFor")) {
            rested.add(id);
          } else if (restLook(el, id)) {
            field.partLooks(el).add("whenHoveredFor");
            rested.add(id);
            looked.add(el);
          }
        }
      }
    }
    if (Animator.isInFieldPart(this.focus.id(), fieldId)) {
      El el = Animator.find(field, fieldId, this.focus.id());
      if (el != null && el.isFocusable()) {
        if (el.focusLook() != null) {
          focused = this.focus.id();
          if (field.partLooks(el).add("whenFocused") && look(el, El::focusLook, "whenFocused")) {
            looked.add(el);
          }
        }
        if (el.focusRestLook() != null) {
          if (field.partLooks(el).contains("whenFocusVisibleFor")) {
            focusRested = true;
          } else if (focusRested(el)) {
            delayedLook(el, el.focusRestLook(), "whenFocusVisibleFor");
            field.partLooks(el).add("whenFocusVisibleFor");
            focusRested = true;
            looked.add(el);
          }
        }
      }
      for (Object id : Animator.lineage(this.focus.id())) {
        if (Animator.isInFieldPart(id, fieldId)) {
          El anc = Animator.find(field, fieldId, id);
          if (anc != null && anc.focusWithinLook() != null) {
            within.add(id);
            if (field.partLooks(anc).add("whenFocusWithin")
                && look(anc, El::focusWithinLook, "whenFocusWithin")) {
              looked.add(anc);
            }
          }
        }
      }
    }
    Object pressId = pressedId();
    if (Animator.isInFieldPart(pressId, fieldId)) {
      El pressed = Animator.find(field, fieldId, pressId);
      if (pressed != null && pressed.pressLook() != null) {
        press = pressId;
        if (field.partLooks(pressed).add("whenPressed")
            && look(pressed, El::pressLook, "whenPressed")) {
          looked.add(pressed);
        }
      }
    }
    if (!looked.isEmpty()) {
      for (El part : field.children()) {
        if (part.fieldPartKey() != null && containsAny(part, looked)) {
          relooked.add(part);
        }
      }
    }
    List<Object> lost = new ArrayList<>(); // by look: one may end while another stays
    for (Object id : shown.hovered()) {
      if (!hovered.contains(id)) {
        lost.add(id);
      }
    }
    for (Object id : shown.rested()) {
      if (!rested.contains(id)) {
        lost.add(id);
      }
    }
    for (Object id : shown.within()) {
      if (!within.contains(id)) {
        lost.add(id);
      }
    }
    if (!Objects.equals(shown.focused(), focused)) {
      lost.add(shown.focused());
    }
    if (!Objects.equals(shown.pressed(), press)) {
      lost.add(shown.pressed());
    }
    for (Object id : lost) {
      El el = id != null && Animator.isInFieldPart(id, fieldId)
          ? Animator.find(field, fieldId, id)
          : null;
      for (El part : field.children()) {
        if (el != null && field.isFieldBlock(part) && containsAny(part, Set.of(el))) {
          stale.add(part); // a block keeps its look in the cache: built anew without it
        }
      }
    }
    shown = new Looks(hovered, rested, focused, focusRested, within, press, shown.hoversRun());
    return relooked;
  }

  private static boolean containsAny(El el, Set<El> targets) {
    if (targets.contains(el)) {
      return true;
    }
    for (El child : el.children()) {
      if (containsAny(child, targets)) {
        return true;
      }
    }
    for (El overlay : el.overlays()) {
      if (containsAny(overlay, targets)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Tells the {@link El#onSelect} handlers of the fields whose caret or selection changed. An
   * editor kept while its field is not in the tree, as the focused one whose row {@link
   * Focus#waits}, is told once its field is back.
   *
   * @return whether a handler was told, which the next input should find in the view
   */
  private boolean tellSelections() {
    boolean told = tellScopeSelection();
    for (Map.Entry<Object, Editor> entry : List.copyOf(editors.entrySet())) {
      told |= inTree(entry) && entry.getValue().tell();
    }
    handled |= told;
    return told;
  }

  /** Tells a selection scope's {@link El#onSelect} handler where its selection is now. */
  private boolean tellScopeSelection() {
    Object[] at = scopeSelectionAt();
    if (at == null || Arrays.equals(at, toldScope)) {
      toldScope = at;
      return false;
    }
    toldScope = at;
    animator.liveElement(at[0]).onSelect().select((int) at[1], (int) at[2]);
    return true;
  }

  /**
   * The scope, caret, anchor and scope text that {@link #tellScopeSelection} tells, or null for a
   * selection scope without a handler or no tracked selection. The text is there so that a change
   * of the text tells the offsets again, also when they stay the same.
   */
  private Object[] scopeSelectionAt() {
    if (scopeSelection == null && keptScope != null && !remap(keptScope, true)) {
      keptScope = null; // remapped here too: its scope may have been out of the tree at the remap
    }
    ScopeSelection s = scopeSelection != null ? scopeSelection : keptScope;
    El scope = s == null ? null : animator.liveElement(s.scopeId);
    if (scope == null || scope.onSelect() == null) {
      return null;
    }
    ScopeIndex index = scopeIndex(scope);
    ScopePosition anchor = s.end.compareTo(s.anchorFrom) < 0 ? s.anchorTo : s.anchorFrom;
    int caret = index.offset(s.end);
    int from = index.offset(anchor);
    int[] tracked = trackInRow(s, index, caret, from);
    if (tracked != null) {
      caret = tracked[0];
      from = tracked[1];
      stale |= s == scopeSelection; // its fields' ranges come from the next build
    }
    return new Object[] {s.scopeId, caret, from, index.text()};
  }

  /**
   * Keeps a scope selection inside one keyed row on its text when the row's fields reshape: while
   * the row has as many fields as it had or the field it starts in is as it was, records the
   * selected text and its occurrence in the row; once both changed, moves {@code s} onto that
   * occurrence. Returns {@code {caret, from}} when moved, else null. shortcut: a reshape is told by
   * the row's number of fields and the field's text, so one that keeps either keeps the offsets;
   * compare the field's identity within its row when that shows.
   */
  private static int[] trackInRow(ScopeSelection s, ScopeIndex index, int caret, int from) {
    if (!s.anchorFrom.inRows() || s.anchorFrom.row() != s.end.row() || s.anchorRowKey == null) {
      if (s.text == null) {
        s.text = "";
      }
      return null;
    }
    if ("".equals(s.text)) {
      return null;
    }
    int start = Math.min(from, caret);
    int finish = Math.max(from, caret);
    String text = index.text();
    int[] span = index.rowSpan(s.anchorFrom.row());
    if (span == null) {
      return null;
    }
    String field = index.fieldText(s.start());
    if (s.text == null || span[2] == s.fields || (field != null && field.equals(s.field))) {
      if (s.text == null) {
        if (start >= finish || start < span[0] || finish > span[1]) {
          s.text = "";
          return null;
        }
        s.text = text.substring(start, finish);
      }
      s.fields = span[2];
      s.field = field;
      s.occurrence = 0;
      for (int i = text.indexOf(s.text, span[0]);
          i >= 0 && i < start;
          i = text.indexOf(s.text, i + 1)) {
        s.occurrence++;
      }
      return null;
    }
    int found = -1;
    int at = span[0];
    for (int n = 0; n <= s.occurrence; n++) {
      at = text.indexOf(s.text, at);
      if (at < 0 || at + s.text.length() > span[1]) {
        return null;
      }
      found = at;
      at++;
    }
    int foundEnd = found + s.text.length();
    ScopePosition startPos = index.position(index.fieldAt(found), found);
    ScopePosition endPos = index.position(index.fieldAt(foundEnd), foundEnd);
    s.fields = span[2];
    s.field = index.fieldText(startPos);
    if (s.forward()) {
      s.anchorFrom = startPos;
      s.anchorTo = startPos;
      s.end = endPos;
      return new int[] {index.offset(endPos), index.offset(startPos)};
    }
    s.anchorFrom = endPos;
    s.anchorTo = endPos;
    s.end = startPos;
    return new int[] {index.offset(startPos), index.offset(endPos)};
  }

  /** Whether a field of the tree has a selection that {@link #tellSelections} would tell. */
  private boolean untold() {
    if (scopeSelection != null || keptScope != null) {
      Object[] at = scopeSelectionAt();
      if (at != null && !Arrays.equals(at, toldScope)) {
        return true;
      }
    }
    for (Map.Entry<Object, Editor> entry : editors.entrySet()) {
      if (inTree(entry) && entry.getValue().untold()) {
        return true;
      }
    }
    return false;
  }

  /** Whether the focus identity changed since {@link #tellFocusIn} last ran. */
  private boolean focusMoved() {
    return !Objects.equals(focus.id(), toldFocus);
  }

  /**
   * Tells the {@link El#onFocusIn} handlers of the elements whose subtree the focus just moved into
   * from outside, when the focused identity changed.
   *
   * @return whether a handler ran, which the next input or rebuild should show
   */
  private boolean tellFocusIn() {
    Object now = focus.id();
    FieldState wasState = toldState;
    toldState = focus.element() == null ? null : focus.element().fieldState();
    if (Objects.equals(now, toldFocus)) {
      return false;
    }
    Object was = toldFocus;
    toldFocus = now;
    if (now == null) {
      return false;
    }
    boolean told = false;
    for (Object id : Animator.lineage(now)) {
      if (within(was, id) || id.equals(now) && toldState != null && toldState == wasState) {
        continue; // the field itself moved with its FieldState, not the focus
      }
      El el = animator.element(id);
      if (el != null && !el.isExiting() && !el.isInert() && el.onFocusIn() != null) {
        handled = told = true;
        el.onFocusIn().run();
      }
    }
    return told;
  }

  /** Records an area that a key's focus move scrolled, for {@link #tellRevealedScrolls}. */
  private void keyRevealed(Object area, boolean moved) {
    if (moved && (keyScroll || focusByKey)) {
      revealedScrolls.add(area);
    }
  }

  /** Runs the {@link El#onUserScroll} handlers of {@code area} and around it, innermost first. */
  private boolean tellUserScroll(El area) {
    return tellUserScroll(around(area));
  }

  /** {@code area} and the elements around it up to the root or its overlay, innermost first. */
  private List<El> around(El area) {
    List<El> path = new ArrayList<>();
    return root != null && pathTo(root, area, path) ? fromOverlay(path).reversed() : List.of();
  }

  /**
   * Runs the {@link El#onUserScroll} handlers along {@code path}, innermost first.
   *
   * @return whether a handler ran
   */
  private boolean tellUserScroll(List<El> path) {
    boolean told = false;
    for (El el : path) {
      if (el.onUserScroll() != null) {
        handled = told = true;
        el.onUserScroll().run();
      }
    }
    return told;
  }

  /** Runs the {@link El#onUserScroll} handlers around the areas a key's reveal moved, once each. */
  private boolean tellRevealedScrolls() {
    if (revealedScrolls.isEmpty()) {
      return false;
    }
    Map<El, Integer> depths = new HashMap<>(); // of each element around them, from the root
    for (Object id : revealedScrolls) {
      El area = animator.element(id);
      List<El> path = area == null ? List.of() : around(area);
      for (int i = 0; i < path.size(); i++) {
        depths.put(path.get(i), path.size() - 1 - i);
      }
    }
    revealedScrolls.clear();
    List<El> path = new ArrayList<>(depths.keySet());
    path.sort((a, b) -> depths.get(b) - depths.get(a)); // innermost first
    return tellUserScroll(path);
  }

  /** Asks the unfold handlers of fields whose pending selection or undo landed on a hidden line. */
  private boolean tellUnfolds() {
    boolean told = false;
    for (Map.Entry<Object, Editor> entry : List.copyOf(editors.entrySet())) {
      told |= inTree(entry) && entry.getValue().unfoldPending();
    }
    handled |= told;
    return told;
  }

  /** Whether a field of the tree has a pending selection or undo to check for hidden folds. */
  private boolean hasPendingUnfolds() {
    for (Map.Entry<Object, Editor> entry : editors.entrySet()) {
      if (inTree(entry) && entry.getValue().hasPendingUnfold()) {
        return true;
      }
    }
    return false;
  }

  /** Whether the field of an editor is the element of the tree for its identity. */
  private boolean inTree(Map.Entry<Object, Editor> entry) {
    return animator.element(entry.getKey()) == entry.getValue().element();
  }

  /**
   * Replaces characters {@code from} to {@code to} of a text field's string by {@code text} and
   * puts the caret at {@code caret} of the new string, as one step of its undo history: the edit
   * goes to the field's {@link El#editable onEdit} handler as typed text does, so the app can refuse
   * or change it, and the field's limits apply, {@link El#maxLength} and its line breaks among
   * them. For completions, a matching bracket and a replace in a find. A handler may call this,
   * with a field of the tree it runs in; the frame edits right after the handler, in the field with
   * the same identity in the tree built from what the handler changed. A read-only or disabled
   * field, and one no longer there, are left alone.
   *
   * @param from the first position, an index into the string from 0
   * @param to the position after the last, from {@code from} to the string's length
   * @param caret the caret's position in the string after the edit
   */
  public void edit(El field, int from, int to, String text, int caret) {
    edit(field, from, to, text, caret, caret, false, null);
  }

  /**
   * {@link #edit(El, int, int, String, int)} that selects from {@code anchor} to {@code caret} of
   * the new string after the edit and scrolls it into view as {@link #select} does. A refused edit
   * leaves the selection as it was, with the string: for a command that changes lines and keeps
   * them selected.
   *
   * @param anchor the anchor's position in the string after the edit
   */
  public void edit(El field, int from, int to, String text, int caret, int anchor) {
    edit(field, from, to, text, caret, anchor, true, null);
  }

  /**
   * {@link #edit(El, int, int, String, int, int)} that runs only when the field's string is still
   * {@code expected} when the edit runs: for an edit whose positions were computed for an earlier
   * string, such as a quick fix.
   *
   * @param expected the string the field must hold for the edit to apply
   */
  public void edit(
      El field, int from, int to, String text, int caret, int anchor, String expected) {
    Objects.requireNonNull(expected, "expected");
    edit(field, from, to, text, caret, anchor, true, expected);
  }

  /**
   * Applies {@code edits} to a text field's string and puts the caret at {@code caret} of the new
   * string, as one step of its undo history: each edit's offsets are in the current string, in
   * ascending order without overlapping, and are logged individually for {@link
   * FieldState#editsSince} and shared views (such as Replace All).
   *
   * @param edits replacements in ascending order without overlapping; empty does nothing
   * @param caret the caret's position in the string after the edits
   * @throws IllegalArgumentException if {@code edits} are out of order or overlap
   */
  public void edit(El field, List<TextEdit> edits, int caret) {
    edit(field, edits, caret, caret, false);
  }

  /**
   * {@link #edit(El, List, int)} that selects from {@code anchor} to {@code caret} of the new
   * string after the edits and scrolls it into view as {@link #select} does.
   *
   * @param edits replacements in ascending order without overlapping; empty does nothing
   * @param caret the caret's position in the string after the edits
   * @param anchor the anchor's position in the string after the edits
   * @throws IllegalArgumentException if {@code edits} are out of order or overlap
   */
  public void edit(El field, List<TextEdit> edits, int caret, int anchor) {
    edit(field, edits, caret, anchor, true);
  }

  private void edit(
      El field,
      int from,
      int to,
      String text,
      int caret,
      int anchor,
      boolean ask,
      String expected) {
    Objects.requireNonNull(text, "text");
    if (from < 0 || to < from) {
      throw new IllegalArgumentException(
          "from must be 0 or more and to at least from, were " + from + " and " + to);
    }
    Object id = fieldId(field, "edit");
    enqueue(() -> {
      settle();
      El now = animator.element(id);
      if (now == null || now.editor() == null) {
        return false;
      }
      if (expected != null && !expected.equals(now.editor().text())) {
        return false;
      }
      now.editor().request(from, to, text, caret, anchor, ask);
      handled |= now.editor().answerPending();
      rowsKept |= now.editor().answerPending();
      return true;
    });
  }

  private void edit(El field, List<TextEdit> edits, int caret, int anchor, boolean ask) {
    Objects.requireNonNull(edits, "edits");
    List<TextEdit> copy = List.copyOf(edits);
    int prevTo = 0;
    for (TextEdit edit : copy) {
      if (edit.from() < prevTo) {
        throw new IllegalArgumentException(
            "edits must be in ascending order without overlapping, got " + edit);
      }
      prevTo = edit.to();
    }
    Object id = fieldId(field, "edit");
    if (copy.isEmpty()) {
      return;
    }
    enqueue(() -> {
      settle();
      El now = animator.element(id);
      if (now == null || now.editor() == null) {
        return false;
      }
      now.editor().request(copy, caret, anchor, ask);
      handled |= now.editor().answerPending();
      rowsKept |= now.editor().answerPending();
      return true;
    });
  }

  /**
   * The text of all read-only, unmasked fields inside a {@link El#selectionScope selection scope},
   * in tree order and joined with {@code "\n"}—the same text Control+C copies for a whole-scope
   * selection. For a scope that contains a {@link El#rows virtual list}, rows that are not built yet
   * are built for this call without being kept, unless the list's {@link RowHeights} has a row
   * version ({@link RowHeights#RowHeights(java.util.function.IntToDoubleFunction,
   * java.util.function.IntFunction, java.util.function.IntFunction)}) that has not changed since the
   * row was last read.
   *
   * @param scope an element marked with {@link El#selectionScope()}
   * @throws IllegalArgumentException if {@code scope} is null or not a selection scope
   */
  public String scopeText(El scope) {
    if (scope == null || !scope.isSelectionScope()) {
      throw new IllegalArgumentException(
          "scopeText is for an element marked with El.selectionScope().");
    }
    return scopeIndex(scope).text();
  }

  /**
   * Selects from {@code anchor} to {@code caret} in a text field or across a {@link
   * El#selectionScope selection scope}, or puts the caret there when they are equal, and scrolls it
   * into view as a key does, a multi-line field or selection scope also while it does not have the
   * focus: a match that a find bar found while the focus is in the bar. For a selection scope, the
   * positions are indexes into {@link #scopeText(El)}, scrolling the target row of any virtual list
   * and the target field into view so Control+C copies the selection. The positions are moved to
   * the grapheme boundary at or before them. A handler may call this as {@link #edit}.
   */
  public void select(El field, int caret, int anchor) {
    if (field != null && field.isSelectionScope()) {
      selectScope(field, caret, anchor);
      return;
    }
    Object id = fieldId(field, "select");
    enqueue(() -> {
      settle();
      El now = animator.element(id);
      if (now == null || now.editor() == null) {
        return false;
      }
      if (capture instanceof Selecting s
          && (s.id().equals(id) || s.scopeId() != null && inScope(id, s.scopeId()))) {
        capture = IDLE;
      }
      if (scopeSelection != null && inScope(id, scopeSelection.scopeId)) {
        clearScopeSelection(now.editor());
      }
      now.editor().select(caret, anchor);
      handled |= now.editor().unfoldPending();
      scrolled = true; // its jump is placed before the next input hit tests, as a scroll's is
      return true;
    });
  }

  /** {@link #select(El, int, int)} for an {@link El#selectionScope} element. */
  private void selectScope(El scope, int caret, int anchor) {
    Object scopeId = animator.identity(scope);
    if (scopeId == null) {
      throw new IllegalArgumentException(
          "select is for a text field or selection scope of the last frame's tree");
    }
    enqueue(() -> {
      settle();
      El now = animator.liveElement(scopeId);
      if (now == null || !now.isSelectionScope()) {
        return false;
      }
      if (capture instanceof Selecting s
          && (scopeId.equals(s.scopeId()) || inScope(s.id(), scopeId))) {
        capture = IDLE;
      }
      ScopeIndex index = scopeIndex(now);
      if (index.starts().length == 0) {
        clearScopeSelection(null);
        keptScope = null;
        return false;
      }
      int anchorAt = index.fieldAt(anchor);
      int caretAt = index.fieldAt(caret);
      ScopePosition anchorPos = index.position(anchorAt, anchor);
      ScopePosition caretPos = index.position(caretAt, caret);
      El anchorField = index.fields()[anchorAt];
      El caretField = index.fields()[caretAt];
      El list = scopeList(now);
      Object listId = list == null ? null : animator.identity(list);
      int rowsVersion = list == null ? 0 : list.rowsVersion();
      Object anchorRowKey =
          list != null && anchorPos.inRows() ? list.rowKey(anchorPos.row()) : null;
      Object endRowKey = list != null && caretPos.inRows() ? list.rowKey(caretPos.row()) : null;
      Object anchorFieldId =
          anchorPos.inRows() ? null : fieldKey(anchorField, animator.identity(anchorField));
      Object endFieldId =
          caretPos.inRows() ? null : fieldKey(caretField, animator.identity(caretField));
      clearScopeSelection(null);
      ScopeSelection next = new ScopeSelection(
          scopeId,
          anchorPos,
          anchorPos,
          caretPos,
          listId,
          rowsVersion,
          anchorRowKey,
          endRowKey,
          anchorFieldId,
          endFieldId);
      scopeSelection = next;
      keptScope = null;
      if (list != null && caretPos.inRows()) {
        if (listId != null) {
          rowGoals.remove(listId);
        }
        list.toEnd(false);
        int row = caretPos.row();
        for (int pass = 0; pass < 3; pass++) {
          if (!list.scrollTo(list.scrollX(), list.offsetShowing(row))) {
            if (pass == 0 && !list.hasRow(row)) {
              fillRows();
              continue;
            }
            break;
          }
          scrolled = true;
          fillRows();
        }
      }
      El target = caretPos.inRows()
          ? builtScopeRowField(list, caretPos.row(), caretPos.field())
          : caretField;
      if (target == null
          && list != null
          && caretPos.inRows()
          && list.spansRows()
          && list.hasRow(caretPos.row())) { // its cell lies sideways out of the part built
        El shown = list.children().get(caretPos.row() - list.firstRow());
        El whole = list.rowElement(caretPos.row());
        if (whole != null) {
          List<El> rowFields = new ArrayList<>();
          collectRowFields(whole, true, rowFields, true);
          El wholeTarget = caretPos.field() >= 0 && caretPos.field() < rowFields.size()
              ? rowFields.get(caretPos.field())
              : null;
          if (wholeTarget != null) {
            list.layoutRowElement(whole, scale, systemFonts);
            List<El> rowPath = new ArrayList<>();
            if (pathTo(whole, wholeTarget, rowPath)) {
              float rx = -(float) list.scrollX();
              for (El step : rowPath) {
                rx += step.x();
              }
              if (reveal(
                  list, rx, shown.y(), wholeTarget.width(), shown.height(), false, null, 0)) {
                scrolled = true;
                fillRows();
                target = builtScopeRowField(list, caretPos.row(), caretPos.field());
              }
            }
          }
        }
      }
      // In a row not built of a list in the row, as high as its rows: that row scrolls into view,
      // and again once the rows above it are measured.
      boolean nested = false;
      for (int pass = 0; target == null && list != null && caretPos.inRows() && pass < 8; pass++) {
        if (list.hasRow(caretPos.row())
            && unbuiltRowOf(list.children().get(caretPos.row() - list.firstRow()), caretPos.field())
                == null) {
          break;
        }
        nested = true;
        revealField(list, caretPos.row(), caretPos.field());
        scrolled = true;
        fillRows();
        target = builtScopeRowField(list, caretPos.row(), caretPos.field());
      }
      if (nested && listId != null) { // the rows measured from now on move it: it is followed
        rowGoals.put(
            listId,
            new RowGoal(
                caretPos.row(), list.rowsVersion(), list.rowKey(caretPos.row()), caretPos.field()));
      }
      if (target != null && target.editor() != null) {
        Editor editor = target.editor();
        int[] range =
            next.editorRangeFor(caretPos.row(), caretPos.field(), editor.text().length());
        if (range != null) {
          editor.select(range[1], range[0]);
        } else {
          editor.select(caretPos.offset(), caretPos.offset());
        }
        target.text().unfocused(false);
        reveal(target);
        if (target.text().multiline() && target.text().laidOut()) {
          editor.layOutLines(false);
        }
        float[] box = editor.caretBox();
        if (box != null) {
          reveal(target, box[0], box[1], box[2], box[3], false, now, now.scopeCoveredTop());
        }
      }
      scrolled = true;
      return true;
    });
  }

  /**
   * Takes back the last step of a text field's history, as Control+Z in it does: an Edit menu's
   * Undo. Nothing happens when there is no step. A handler may call this as {@link #edit}.
   */
  public void undo(El field) {
    step(fieldId(field, "undo"), Editor::undo);
  }

  /** Does the step again that {@link #undo} or Control+Z took back last, as Control+Y does. */
  public void redo(El field) {
    step(fieldId(field, "redo"), Editor::redo);
  }

  /** Runs a command of a field's editor right after the handler, as {@link #undo} does. */
  void step(Object id, Consumer<Editor> step) {
    enqueue(() -> {
      settle();
      El now = animator.element(id);
      if (now == null || now.editor() == null) {
        return false;
      }
      step.accept(now.editor());
      handled |= now.editor().answerPending();
      rowsKept |= now.editor().answerPending();
      return true;
    });
  }

  /**
   * {@link FieldState#replaceSelection} and {@link FieldState#replace}: runs {@code edit} on the
   * editor of the field that has {@code state} right after the handler, as {@link #edit} does.
   */
  void edit(FieldState state, Consumer<Editor> edit) {
    enqueue(() -> {
      settle();
      // By the state, not the identity: another field may stand there now, or this one fade out.
      El now = state.app == this && !gone(state.id, true) ? animator.element(state.id) : null;
      if (now == null || now.fieldState() != state) {
        return false;
      }
      edit.accept(state.editor);
      handled |= state.editor.answerPending();
      rowsKept |= state.editor.answerPending();
      return true;
    });
  }

  private Object fieldId(El field, String call) {
    Object id = field == null || !field.isEditable() ? null : animator.identity(field);
    if (id == null) {
      throw new IllegalArgumentException(call + " is for a text field of the last frame's tree,"
          + " which El.text(...).editable(...) makes");
    }
    return id;
  }

  /** The editors of the fields of the current tree, without those that are leaving. */
  private static void collectEditors(El el, List<Editor> into) {
    if (el.isExiting()) {
      return;
    }
    if (el.editor() != null) {
      into.add(el.editor());
    }
    for (El child : el.children()) {
      collectEditors(child, into);
    }
    for (El overlay : el.overlays()) {
      collectEditors(overlay, into);
    }
  }

  /** The pointer moved while a press selects in a field, or across fields of a {@link El#selectionScope}. */
  private boolean drag(Selecting s, float x, float y) {
    if (s.scopeId() != null) {
      boolean held = capture == s; // a release has ended the capture already
      remapScopeSelection();
      if (held && capture != s) {
        return false; // the keyed anchor row left the list
      }
      El scope = animator.liveElement(s.scopeId());
      if (scope != null) {
        List<El> hit = hit(x, y);
        int under = fieldPressIndex(hit); // on the edge two fields share, the one hit tests find
        El target = under < 0 ? null : hit.get(under);
        return selectAcrossScope(scope, s, target, x, y);
      }
    }
    El el = animator.element(s.id());
    if (el == null || el.editor() == null || el.text() == null) {
      return false;
    }
    float[] at = local(el, x, y);
    Editor editor = el.editor();
    return editor.drag(
        editor.fromShown(el.text().nearest(at[0], at[1])),
        editor.fromShown(el.text().hit(at[0], at[1])));
  }

  /** Whether {@code el} is a read-only, unmasked text field that can join a selection scope. */
  private static boolean isScopeFieldCandidate(El el) {
    return el.isEditable() && el.isReadOnly() && el.text() != null && !el.text().masked();
  }

  /** Whether {@code el} is a built read-only, unmasked text field with an attached editor. */
  private static boolean scopeField(El el) {
    return isScopeFieldCandidate(el) && el.editor() != null;
  }

  /** The normalized text of a read-only field, matching what its {@link Editor} holds. */
  private static String fieldText(El field) {
    if (field.editor() != null) {
      return field.editor().text();
    }
    Text t = field.text();
    return t.multiline() ? Editor.multiLine(t.value()) : t.value();
  }

  /** The outermost {@link El#selectionScope} on {@code path} inside its overlay layer, or null. */
  private static El scopeOf(List<El> path) {
    for (El el : fromOverlay(path)) {
      if (el.isSelectionScope()) {
        return el;
      }
    }
    return null;
  }

  /** The identity of {@code target}, an element on {@code path}. */
  private static Object identityOn(List<El> path, El target) {
    List<El> layer = fromOverlay(path);
    int index = layer.indexOf(target);
    return index < 0 ? null : Animator.identities(layer.subList(0, index + 1)).getLast();
  }

  /** The first virtual list in {@code scope} (or {@code scope} itself), or null if none. */
  private static El scopeList(El el) {
    if (el == null
        || el.isExiting()
        || el.opacity() == 0
        || el.style().display == Style.Display.NONE
        || el.isInert()) {
      return null;
    }
    if (el.isVirtualList()) {
      return el;
    }
    for (El child : el.children()) {
      El found = scopeList(child);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** The vertical scroll area inside {@code scope} for edge auto-scroll, or null if none. */
  private static El scopeScrollArea(El scope) {
    El list = scopeList(scope);
    if (list != null) {
      return list;
    }
    return scope != null && scope.isScrollArea() && scope.style().overflowY == Style.Overflow.SCROLL
        ? scope
        : null;
  }

  /**
   * Moves the endpoints of {@link #scopeSelection}, {@link #keptScope} and of a scope {@link
   * Selecting} that lie outside the list's rows to where their fields are in a freshly built tree,
   * before {@link #attachEditors} hands out ranges; one whose field is gone ends the selection.
   */
  private void remapScopeOutsideFields(El tree) {
    if (scopeSelection != null && !remapOutsideFields(tree, scopeSelection)) {
      clearScopeSelection(null);
    }
    if (keptScope != null && !remapOutsideFields(tree, keptScope)) {
      keptScope = null;
    }
    if (capture instanceof Selecting s && s.scopeId() != null) {
      ScopePosition from = follow(tree, s.scopeId(), s.anchorFieldId, s.anchorFrom);
      ScopePosition to = follow(tree, s.scopeId(), s.anchorFieldId, s.anchorTo);
      if (from == null || to == null) {
        capture = IDLE;
      } else {
        s.anchorFrom = from;
        s.anchorTo = to;
      }
    }
  }

  /** Moves outside-row endpoints on {@code s} to where their fields are in {@code tree}. */
  private static boolean remapOutsideFields(El tree, ScopeSelection s) {
    Object scopeId = s.scopeId;
    ScopePosition from = follow(tree, scopeId, s.anchorFieldId, s.anchorFrom);
    ScopePosition to = follow(tree, scopeId, s.anchorFieldId, s.anchorTo);
    ScopePosition end = follow(tree, scopeId, s.endFieldId, s.end);
    if (from == null || to == null || end == null) {
      return false;
    }
    s.anchorFrom = from;
    s.anchorTo = to;
    s.end = end;
    return true;
  }

  /**
   * {@code at} moved to where the field {@code fieldId} is now in scope {@code scopeId} of {@code
   * tree}: unchanged for no field id, a field part or a scope that is gone, null if the field is
   * gone.
   */
  private static ScopePosition follow(El tree, Object scopeId, Object fieldId, ScopePosition at) {
    // A field part is built only after layout, so a fresh tree cannot place one.
    El scope =
        fieldId == null || (!(fieldId instanceof FieldState) && Animator.inFieldPart(fieldId))
            ? null
            : Animator.find(tree, scopeId);
    if (scope == null) {
      return at;
    }
    El el = fieldId instanceof FieldState state
        ? withState(scope, state)
        : Animator.find(scope, scopeId, fieldId);
    int[] pos = el != null && isScopeFieldCandidate(el)
        ? outsidePositionIn(scope, el, scopeList(scope))
        : null;
    return pos == null ? null : new ScopePosition(pos[0], pos[1], at.offset());
  }

  /**
   * What a scope selection follows a field outside the rows by: its {@link FieldState}, which keeps
   * its editor wherever the field moves, or else its identity {@code id}.
   */
  private static Object fieldKey(El field, Object id) {
    return field.fieldState() != null ? field.fieldState() : id;
  }

  /** The element below {@code el} whose field has {@code state}, or null. */
  private static El withState(El el, FieldState state) {
    if (el.fieldState() == state) {
      return el;
    }
    for (El child : el.children()) {
      El found = withState(child, state);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /**
   * Remaps keyed row indices on {@link #scopeSelection}, {@link #keptScope} and any scope {@link
   * Selecting} capture.
   */
  private void remapScopeSelection() {
    if (scopeSelection != null && !remap(scopeSelection, false)) {
      clearScopeSelection(null); // its ranges go with it
    }
    if (keptScope != null && !remap(keptScope, true)) {
      keptScope = null;
    }
    if (capture instanceof Selecting s && s.scopeId() != null) {
      El scope = animator.liveElement(s.scopeId());
      if (scope != null) {
        El currentList = scopeList(scope);
        Object currentListId = currentList == null ? null : animator.identity(currentList);
        if (s.listId != null) {
          El list = s.listId.equals(currentListId) ? currentList : null;
          if (list == null) {
            if (s.anchorFrom.inRows()) {
              capture = IDLE;
              return;
            }
            // An anchor outside the rows extends into whatever list stands in the scope now.
            s.listId = currentListId;
            s.rowsVersion = currentList == null ? 0 : currentList.rowsVersion();
          } else {
            int version = list.rowsVersion();
            if (version != s.rowsVersion) {
              int anchorRow = remapRow(list, s.anchorFrom.row(), s.anchorRowKey);
              if (anchorRow == Integer.MIN_VALUE) {
                capture = IDLE;
                return;
              }
              s.anchorFrom = s.anchorFrom.inRow(anchorRow);
              s.anchorTo = s.anchorTo.inRow(anchorRow);
              s.rowsVersion = version;
            }
            if (s.anchorFrom.inRows() && s.anchorFrom.row() >= list.rowCount()) {
              capture = IDLE;
              return;
            }
          }
        } else if (currentListId != null) {
          s.listId = currentListId;
          s.rowsVersion = currentList.rowsVersion();
        }
      }
    }
  }

  /**
   * Remaps keyed row indices on {@code s}; returns whether {@code s} survives. With {@code byKeys},
   * one in rows without keys ends: an index would follow the wrong row when rows come and go.
   */
  private boolean remap(ScopeSelection s, boolean byKeys) {
    if (byKeys
        && ((s.anchorFrom.inRows() && s.anchorRowKey == null)
            || (s.end.inRows() && s.endRowKey == null))) {
      return false;
    }
    El scope = animator.liveElement(s.scopeId);
    if (scope == null) {
      return true;
    }
    El currentList = scopeList(scope);
    Object currentListId = currentList == null ? null : animator.identity(currentList);
    if (s.listId != null) {
      El list = s.listId.equals(currentListId) ? currentList : null;
      if (list == null) {
        if (s.anchorFrom.inRows() || s.end.inRows()) {
          return false;
        }
        s.listId = currentListId;
        s.rowsVersion = currentList == null ? 0 : currentList.rowsVersion();
      } else {
        int version = list.rowsVersion();
        if (version != s.rowsVersion) {
          int anchorRow = remapRow(list, s.anchorFrom.row(), s.anchorRowKey);
          int endRow = remapRow(list, s.end.row(), s.endRowKey);
          if (anchorRow == Integer.MIN_VALUE || endRow == Integer.MIN_VALUE) {
            return false;
          }
          s.anchorFrom = s.anchorFrom.inRow(anchorRow);
          s.anchorTo = s.anchorTo.inRow(anchorRow);
          s.end = s.end.inRow(endRow);
          s.rowsVersion = version;
        }
        if ((s.anchorFrom.inRows() && s.anchorFrom.row() >= list.rowCount())
            || (s.end.inRows() && s.end.row() >= list.rowCount())) {
          return false;
        }
      }
    } else if (currentListId != null) {
      s.listId = currentListId;
      s.rowsVersion = currentList.rowsVersion();
    }
    return true;
  }

  /**
   * Maps a stored row index after a keyed {@link RowHeights} update: returns the unchanged index
   * when outside rows or for an unkeyed list, the new row index when the key still exists, or
   * {@link Integer#MIN_VALUE} when the keyed row was removed.
   */
  private static int remapRow(El list, int row, Object rowKey) {
    if (!ScopePosition.inRows(row) || rowKey == null) {
      return row;
    }
    int moved = list.rowWithKey(rowKey);
    return moved >= 0 ? moved : Integer.MIN_VALUE;
  }

  /**
   * Applies the active {@link #scopeSelection} to a newly attached field before layout so that
   * rebuilt virtual rows immediately keep their selection and suppress ellipsis.
   */
  private boolean applyScopeSelectionOnAttach(El el, List<El> path, Object id, Editor editor) {
    if (scopeSelection == null
        || !scopeSelection.hasSelection()
        || !isScopeFieldCandidate(el)
        || !inScope(id, scopeSelection.scopeId)) {
      return false;
    }
    int row = scopeSelection.listId == null ? -1 : Animator.rowIn(id, scopeSelection.listId);
    int ordinal = 0;
    if (row < 0) {
      El scope = scopeOf(path);
      if (scope == null) {
        return false;
      }
      int[] pos = outsidePositionIn(scope, el, scopeList(scope));
      if (pos == null) {
        return false;
      }
      row = pos[0];
      ordinal = pos[1];
    } else {
      ScopePosition s = scopeSelection.start();
      ScopePosition f = scopeSelection.finish();
      if (row < s.row() || row > f.row()) {
        return false;
      }
      if (row == s.row() || row == f.row()) {
        El list = animator.liveElement(scopeSelection.listId);
        if (list != null && list.spansRows()) {
          ordinal = rowFieldOrdinal(list, scopeSelection.listId, row, null, el, id);
        } else {
          El container = rowRootOn(path, scopeSelection.listId);
          if (container == null) {
            return false;
          }
          ordinal = fieldOrdinalIn(container, el);
        }
        if (ordinal < 0) {
          return false;
        }
      }
    }
    int[] range = scopeSelection.editorRangeFor(row, ordinal, editor.text().length());
    if (range == null) {
      return false;
    }
    editor.setRange(range[0], range[1], false);
    return true;
  }

  /** The row element on {@code path} directly below the list with identity {@code listId}. */
  private static El rowRootOn(List<El> path, Object listId) {
    List<Object> ids = Animator.identities(path);
    for (int i = 0; i + 1 < path.size(); i++) {
      if (listId.equals(ids.get(i))) {
        return path.get(i + 1);
      }
    }
    return rowRootOn(path);
  }

  /** The row element on {@code path} directly below a virtual list, or null. */
  private static El rowRootOn(List<El> path) {
    for (int i = path.size() - 2; i >= 0; i--) {
      if (path.get(i).isVirtualList()) {
        return path.get(i + 1);
      }
    }
    return null;
  }

  /**
   * Derives every built field's range in the active {@link #scopeSelection} from its positions,
   * recording which built editors are covered and which ones changed range.
   */
  private void syncScopeSelection(Set<Editor> selected, Set<Editor> changed) {
    if (scopeSelection == null) {
      return;
    }
    remapScopeSelection();
    if (scopeSelection == null || !scopeSelection.hasSelection()) {
      return;
    }
    El scope = animator.liveElement(scopeSelection.scopeId);
    if (scope == null) {
      return;
    }
    ScopePosition s = scopeSelection.start();
    ScopePosition f = scopeSelection.finish();
    for (ScopeField sf : scopeFields(scope, r -> r == s.row() || r == f.row())) {
      Editor editor = sf.el.editor();
      int[] range =
          scopeSelection.editorRangeFor(sf.row, sf.ordinal, editor.text().length());
      if (range != null) {
        selected.add(editor);
        if (editor.setRange(range[0], range[1], false)) {
          changed.add(editor);
        }
        sf.el.text().unfocused(false);
      } else if (editor.collapse()) {
        changed.add(editor);
      }
    }
  }

  /** Whether {@code editor} belongs to the active scope selection, derived from its positions. */
  private boolean isScopeSelected(Editor editor) {
    if (editor == null || scopeSelection == null || !scopeSelection.hasSelection()) {
      return false;
    }
    El el = editor.element();
    if (el == null || !isScopeFieldCandidate(el)) {
      return false;
    }
    Object id = animator.identity(el);
    if (id == null || !inScope(id, scopeSelection.scopeId)) {
      return false;
    }
    remapScopeSelection();
    if (scopeSelection == null || !scopeSelection.hasSelection()) {
      return false;
    }
    El scope = animator.liveElement(scopeSelection.scopeId);
    if (scope == null) {
      return false;
    }
    int row = scopeSelection.listId == null ? -1 : Animator.rowIn(id, scopeSelection.listId);
    if (row < 0) {
      int[] pos = outsidePositionIn(scope, el, scopeList(scope));
      return pos != null && scopeSelection.covers(pos[0], pos[1]);
    }
    ScopePosition s = scopeSelection.start();
    ScopePosition f = scopeSelection.finish();
    if (row < s.row() || row > f.row()) {
      return false;
    }
    if (row > s.row() && row < f.row()) {
      return true;
    }
    El list = scopeList(scope);
    if (list == null || !list.hasRow(row)) {
      return false;
    }
    El container = list.children().get(row - list.firstRow());
    int ordinal = rowFieldOrdinal(list, scopeSelection.listId, row, container, el, id);
    return ordinal >= 0 && scopeSelection.covers(row, ordinal);
  }

  /** Whether a waiting focused identity belongs to an active non-empty scope selection. */
  private boolean isScopeSelectedId(Object id) {
    if (id == null || scopeSelection == null || !scopeSelection.hasSelection()) {
      return false;
    }
    if (!inScope(id, scopeSelection.scopeId)) {
      return false;
    }
    remapScopeSelection();
    return scopeSelection != null && scopeSelection.hasSelection();
  }

  /** Collapses every built field in the active scope selection except {@code keep}, and ends it. */
  private boolean clearScopeSelection(Editor keep) {
    if (scopeSelection == null) {
      return false;
    }
    ScopeSelection old = scopeSelection;
    scopeSelection = null;
    keptScope = old; // a remap that ended it drops this too
    if (!old.hasSelection()) {
      return false; // it gave no field a range, and others may have one of their own
    }
    // Every editor of the scope, also of rows not built yet, whose editors a rebuild reuses.
    for (Map.Entry<Object, Editor> entry : editors.entrySet()) {
      Editor editor = entry.getValue();
      El el = editor.element();
      if (editor != keep
          && el != null
          && isScopeFieldCandidate(el)
          && inScope(entry.getKey(), old.scopeId)) {
        editor.collapse();
      }
    }
    return true;
  }

  /** Handles a key on the focused field; one that moves the caret ends a cross-field selection. */
  private boolean handleFieldKey(El el, Key key) {
    // By the scope, not the field: the focus stays at its row index when keyed rows move.
    if (isCopy(key) && isScopeFieldCandidate(el) && isScopeSelectedId(animator.identity(el))) {
      copyScopeSelection();
      return true;
    }
    boolean took = el.editor().key(key, clipboard);
    if (took && scopeSelection != null && clearsScopeSelection(key)) {
      clearScopeSelection(el.editor());
    }
    return took;
  }

  /**
   * Copies the selected text across the active {@link #scopeSelection} in tree order, including
   * virtual list rows that are not currently built, joined with {@code "\n"}.
   */
  private void copyScopeSelection() {
    remapScopeSelection();
    if (scopeSelection == null || !scopeSelection.hasSelection()) {
      return;
    }
    El scope = animator.liveElement(scopeSelection.scopeId);
    if (scope == null) {
      return;
    }
    El list = scopeList(scope);
    ScopePosition start = scopeSelection.start();
    ScopePosition finish = scopeSelection.finish();
    List<ScopeField> outside =
        start.row() == ScopePosition.BEFORE || finish.row() == ScopePosition.AFTER
            ? scopeFields(scope, r -> false)
            : List.of();
    StringBuilder out = new StringBuilder();
    if (start.row() == ScopePosition.BEFORE) {
      for (ScopeField field : outside) {
        if (field.row == ScopePosition.BEFORE) {
          appendScopeSlice(out, fieldText(field.el), ScopePosition.BEFORE, field.ordinal);
        }
      }
    }
    if (list != null
        && list.rowCount() > 0
        && start.row() != ScopePosition.AFTER
        && finish.row() != ScopePosition.BEFORE) {
      int fromRow = start.inRows() ? start.row() : 0;
      int toRow =
          finish.inRows() ? Math.min(list.rowCount() - 1, finish.row()) : list.rowCount() - 1;
      List<El> rowFields = new ArrayList<>();
      for (int row = fromRow; row <= toRow; row++) {
        El rowEl = list.rowElement(row);
        if (rowEl == null) {
          continue;
        }
        rowFields.clear();
        collectRowFields(rowEl, true, rowFields, true);
        for (int ord = 0; ord < rowFields.size(); ord++) {
          El field = rowFields.get(ord);
          if (field != null) {
            appendScopeSlice(out, fieldText(field), row, ord);
          }
        }
      }
    }
    if (finish.row() == ScopePosition.AFTER) {
      for (ScopeField field : outside) {
        if (field.row == ScopePosition.AFTER) {
          appendScopeSlice(out, fieldText(field.el), ScopePosition.AFTER, field.ordinal);
        }
      }
    }
    if (!out.isEmpty()) {
      clipboard.write(out.toString());
    }
  }

  private void appendScopeSlice(StringBuilder out, String text, int row, int ordinal) {
    int[] slice = scopeSelection.sliceFor(row, ordinal, text.length());
    if (slice == null) {
      return;
    }
    // On the boundaries Editor#setRange snaps the highlight to.
    String part = text.substring(Editor.snap(text, slice[0]), Editor.snap(text, slice[1]));
    if (!part.isEmpty()) {
      if (!out.isEmpty()) {
        out.append('\n');
      }
      out.append(part);
    }
  }

  private static boolean isCopy(Key key) {
    return key.modifiers() == Key.CONTROL
        && (key.name().equals("C") || key.name().equals("Insert"));
  }

  private static boolean clearsScopeSelection(Key key) {
    String name = key.name();
    if (key.modifiers() == Key.CONTROL && name.equals("A")) {
      return true;
    }
    return switch (name) {
      case "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Home", "End", "PageUp", "PageDown" ->
        true;
      default -> false;
    };
  }

  /**
   * The anchor {@link ScopePosition} of the currently focused read-only field inside {@code scope}
   * (also when its row scrolled out and {@link Focus#waits}), or null if focus is elsewhere.
   */
  private ScopePosition focusScopePosition(El scope, Object scopeId, El list, Object listId) {
    if (focus.element() != null && scopeField(focus.element())) {
      List<El> focusPath = new ArrayList<>();
      if (pathTo(root, focus.element(), focusPath) && scopeOf(focusPath) == scope) {
        ScopeField sf = scopeFieldOf(scope, focus.element());
        if (sf != null) {
          return new ScopePosition(sf.row, sf.ordinal, focus.element().editor().anchor());
        }
      }
      return null;
    }
    if (focus.element() == null
        && focus.waits()
        && list != null
        && listId != null
        && inScope(focus.id(), scopeId)) {
      Editor waiting = editors.get(focus.id());
      if (waiting == null || !isScopeFieldCandidate(waiting.element())) {
        return null;
      }
      int row = Animator.rowIn(focus.id(), listId);
      if (row < 0 || row >= list.rowCount()) {
        return null;
      }
      int ordinal;
      if (list.spansRows()) {
        ordinal = rowOrdinals(list, listId, row).getOrDefault(focus.id(), -1);
      } else {
        El rowEl = list.rowElement(row);
        if (rowEl == null) {
          return null;
        }
        ordinal = ordinalIn(rowEl, Animator.row(listId, row), focus.id(), new int[] {0});
      }
      return ordinal < 0 ? null : new ScopePosition(row, ordinal, waiting.anchor());
    }
    return null;
  }

  /** Finds the {@link ScopeField} entry for a built field inside {@code scope}, or null. */
  private ScopeField scopeFieldOf(El scope, El field) {
    El list = scopeList(scope);
    int targetRow = -1;
    if (list != null && list.spansRows()) {
      Object listId = animator.identity(list);
      Object fieldId = animator.identity(field);
      if (listId != null && fieldId != null) {
        targetRow = Animator.rowIn(fieldId, listId);
      }
    }
    int resolve = targetRow;
    for (ScopeField sf : scopeFields(scope, r -> r == resolve)) {
      if (sf.el == field) {
        return sf.ordinal < 0 ? null : sf;
      }
    }
    return null;
  }

  /**
   * Maps each read-only unmasked field's identity in row {@code row} of spans list {@code list}
   * (with identity {@code listId}) to its ordinal in that row built for the list's whole content
   * width, cached per row for the current build/frame.
   */
  private Map<Object, Integer> rowOrdinals(El list, Object listId, int row) {
    Map<Integer, Map<Object, Integer>> byRow =
        rowOrdinalCache.computeIfAbsent(list, ignored -> new HashMap<>());
    Map<Object, Integer> cached = byRow.get(row);
    if (cached != null) {
      return cached;
    }
    El whole = list.rowElement(row);
    Map<Object, Integer> ordinals;
    if (whole == null) {
      ordinals = Map.of();
    } else {
      ordinals = new HashMap<>();
      collectRowOrdinals(whole, Animator.row(listId, row), new int[] {0}, ordinals);
    }
    byRow.put(row, ordinals);
    return ordinals;
  }

  private static void collectRowOrdinals(
      El el, Object id, int[] ordinal, Map<Object, Integer> out) {
    if (!numbered(el)) {
      return;
    }
    if (isScopeFieldCandidate(el)) {
      out.putIfAbsent(id, ordinal[0]++);
    }
    List<El> children = el.children();
    Object[] childIds = Animator.childIds(el, id);
    for (int i = 0; i < children.size(); i++) {
      collectRowOrdinals(children.get(i), childIds[i], ordinal, out);
    }
  }

  /**
   * The ordinal of built field {@code field} (with identity {@code fieldId}) in row {@code row} of
   * {@code list} (with identity {@code listId}), or -1 if unmatched.
   */
  private int rowFieldOrdinal(
      El list, Object listId, int row, El container, El field, Object fieldId) {
    if (list != null && list.spansRows()) {
      if (listId == null || fieldId == null) {
        return -1;
      }
      return rowOrdinals(list, listId, row).getOrDefault(fieldId, -1);
    }
    return container == null ? -1 : fieldOrdinalIn(container, field);
  }

  /**
   * Whether the fields inside {@code el} count for a scope's positions. Only what a row as built
   * and the same row as its function builds it anew agree on counts: not placement or opacity,
   * which looks such as hovering change.
   */
  private static boolean numbered(El el) {
    return !el.isExiting() && el.style().display != Style.Display.NONE && !el.isInert();
  }

  /** Visits the non-empty shown read-only unmasked fields of a selection scope in tree order. */
  @FunctionalInterface
  private interface ScopeFieldVisitor {
    void visit(int row, int ordinal, El field, String text);
  }

  /**
   * Walks the fields of {@code scope} as {@link #scopeFields} numbers them: with {@code unbuilt}
   * every row of its virtual list, building those that are not, else only the built rows, whose
   * ordinals in a list that builds the columns in view are those of the columns built.
   */
  private void walkScopeFields(El scope, boolean unbuilt, ScopeFieldVisitor visitor) {
    if (numbered(scope) && scope.opacity() != 0) {
      walkScope(
          scope, scopeList(scope), new int[] {ScopePosition.BEFORE, 0}, true, unbuilt, visitor);
    }
  }

  private void walkScope(
      El el, El list, int[] pos, boolean shown, boolean unbuilt, ScopeFieldVisitor visitor) {
    if (!numbered(el)) {
      return;
    }
    shown &= el.opacity() != 0; // a hidden field keeps its ordinal
    if (el == list) {
      int first = unbuilt ? 0 : el.firstRow();
      int last =
          !shown ? first : unbuilt ? el.rowCount() : first + el.children().size();
      // Rows not built keep their texts while their key and version stay; see RowHeights.
      RowHeights heights = unbuilt && shown ? el.rowHeights() : null;
      Object[] versions = heights == null ? null : el.rowVersions();
      boolean keep = versions != null;
      Map<Object, RowHeights.RowTexts> kept = keep ? HashMap.newHashMap(last - first) : null;
      List<El> rowFields = new ArrayList<>();
      for (int row = first; row < last; row++) {
        Object key = keep ? heights.key(row) : null;
        Object version = key == null ? null : versions[row];
        RowHeights.RowTexts texts = key == null ? null : heights.rowTexts().get(key);
        if (texts == null || el.hasRow(row) || !Objects.equals(texts.version(), version)) {
          El rowEl = unbuilt ? el.rowElement(row) : el.children().get(row - first);
          if (rowEl == null) {
            continue;
          }
          rowFields.clear();
          collectRowFields(rowEl, true, rowFields, unbuilt);
          String[] read = new String[rowFields.size()];
          for (int ord = 0; ord < read.length; ord++) {
            El field = rowFields.get(ord);
            read[ord] = field == null ? "" : fieldText(field);
            if (!read[ord].isEmpty()) {
              visitor.visit(row, ord, field, read[ord]);
            }
          }
          texts = new RowHeights.RowTexts(version, List.of(read));
        } else {
          for (int ord = 0; ord < texts.texts().size(); ord++) {
            if (!texts.texts().get(ord).isEmpty()) {
              visitor.visit(row, ord, null, texts.texts().get(ord));
            }
          }
        }
        if (key != null) {
          kept.put(key, texts);
        }
      }
      if (keep) {
        heights.rowTexts(kept); // drops the rows that are gone
      }
      pos[0] = ScopePosition.AFTER;
      pos[1] = 0;
      return;
    }
    if (isScopeFieldCandidate(el)) {
      int ord = pos[1]++;
      String text = shown ? fieldText(el) : "";
      if (!text.isEmpty()) {
        visitor.visit(pos[0], ord, el, text);
      }
    }
    for (El child : el.children()) {
      walkScope(child, list, pos, shown, unbuilt, visitor);
    }
  }

  /**
   * A scope's fields as one text ({@link #scopeText}): the text and, per field in tree order, its
   * row and ordinal, where it starts, how many lines come before it, and the field itself outside
   * the rows of a virtual list.
   */
  private record ScopeIndex(
      String text, int[] rows, int[] ordinals, int[] starts, int[] lines, El[] fields) {

    private int end(int i) {
      return i + 1 < starts.length ? starts[i + 1] - 1 : text.length();
    }

    /** The first field that ends at or after {@code offset}, else the last. */
    int fieldAt(int offset) {
      int lo = 0;
      int hi = starts.length - 1;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (end(mid) < offset) {
          lo = mid + 1;
        } else {
          hi = mid;
        }
      }
      return lo;
    }

    /** {@code offset} as a position in field {@code i}, on a grapheme boundary. */
    ScopePosition position(int i, int offset) {
      String field = text.substring(starts[i], end(i));
      int local = Math.clamp((long) offset - starts[i], 0, field.length());
      return new ScopePosition(rows[i], ordinals[i], Editor.snap(field, local));
    }

    /**
     * {@code position} as an index into the text, or where its field would sit if it is empty or
     * gone from its row.
     */
    int offset(ScopePosition position) {
      int i = slot(position.row(), position.field());
      if (i >= 0) {
        return starts[i] + Math.min(position.offset(), end(i) - starts[i]);
      }
      int at = ~i;
      return at < starts.length ? starts[at] : text.length();
    }

    /** The field at {@code (row, ordinal)}, or -1. */
    int indexOf(int row, int ordinal) {
      int i = slot(row, ordinal);
      return i >= 0 ? i : -1;
    }

    /** The text of the field {@code position} is in, or null if it is not in the index. */
    String fieldText(ScopePosition position) {
      int i = indexOf(position.row(), position.field());
      return i < 0 ? null : text.substring(starts[i], end(i));
    }

    /**
     * {@code {start, end, fields}} of row {@code row}: its text in {@link #text} and its number of
     * fields, or null if it has none.
     */
    int[] rowSpan(int row) {
      int first = slot(row, 0);
      if (first < 0) {
        first = ~first;
      }
      if (first >= starts.length || rows[first] != row) {
        return null;
      }
      int after = slot(row + 1, 0);
      if (after < 0) {
        after = ~after;
      }
      return new int[] {starts[first], end(after - 1), after - first};
    }

    /** The field at {@code (row, ordinal)}, or {@code ~insertionPoint} if it is not in the index. */
    private int slot(int row, int ordinal) {
      int lo = 0;
      int hi = starts.length - 1;
      while (lo <= hi) {
        int mid = (lo + hi) >>> 1;
        int cmp = rows[mid] != row ? Integer.compare(rows[mid], row) : ordinals[mid] - ordinal;
        if (cmp == 0) {
          return mid;
        }
        if (cmp < 0) {
          lo = mid + 1;
        } else {
          hi = mid - 1;
        }
      }
      return ~lo;
    }
  }

  /**
   * The index of a scope, kept until the view builds again: until then its virtual list builds
   * its rows with the same function, so the next match need not read every row again.
   */
  private ScopeIndex scopeIndex(El scope) {
    return scopeIndexes.computeIfAbsent(scope, this::indexScope);
  }

  private ScopeIndex indexScope(El scope) {
    StringBuilder text = new StringBuilder();
    List<int[]> places = new ArrayList<>();
    List<El> fields = new ArrayList<>();
    int[] lines = {0};
    walkScopeFields(scope, true, (row, ordinal, field, part) -> {
      if (!places.isEmpty()) {
        text.append('\n');
        lines[0]++;
      }
      places.add(new int[] {row, ordinal, text.length(), lines[0]});
      fields.add(ScopePosition.inRows(row) ? null : field);
      text.append(part);
      for (int i = part.indexOf('\n'); i >= 0; i = part.indexOf('\n', i + 1)) {
        lines[0]++;
      }
    });
    int n = places.size();
    int[][] columns = new int[4][n];
    for (int i = 0; i < n; i++) {
      for (int c = 0; c < 4; c++) {
        columns[c][i] = places.get(i)[c];
      }
    }
    return new ScopeIndex(
        text.toString(), columns[0], columns[1], columns[2], columns[3], fields.toArray(El[]::new));
  }

  /** Returns the built read-only field at {@code (row, ordinal)} inside {@code list}, or null. */
  private El builtScopeRowField(El list, int row, int ordinal) {
    if (list == null || !list.hasRow(row)) {
      return null;
    }
    El rowEl = list.children().get(row - list.firstRow());
    List<El> rowFields = new ArrayList<>();
    collectRowFields(rowEl, true, rowFields, false);
    if (list.spansRows()) { // the ordinal counts the columns of the whole width
      Object listId = animator.identity(list);
      for (El field : rowFields) {
        if (field != null
            && rowFieldOrdinal(list, listId, row, null, field, animator.identity(field))
                == ordinal) {
          return field;
        }
      }
      return null;
    }
    El field = ordinal >= 0 && ordinal < rowFields.size() ? rowFields.get(ordinal) : null;
    return field != null && field.editor() != null ? field : null; // not one of a row not built
  }

  /**
   * The virtual list inside built row {@code rowEl} of a scope's list and its row not built that
   * hold the field with ordinal {@code ordinal}, or null when that field is not in such a row.
   */
  private Object[] unbuiltRowOf(El rowEl, int ordinal) {
    return unbuiltRowOf(rowEl, new int[] {ordinal});
  }

  private Object[] unbuiltRowOf(El el, int[] ordinal) {
    if (!numbered(el)) {
      return null;
    }
    if (isScopeFieldCandidate(el) && ordinal[0]-- == 0) {
      return null; // a field built with the row
    }
    if (!el.isVirtualList()) {
      for (El child : el.children()) {
        Object[] found = ordinal[0] < 0 ? null : unbuiltRowOf(child, ordinal);
        if (found != null || ordinal[0] < 0) {
          return found;
        }
      }
      return null;
    }
    for (int row = 0; row < el.rowCount() && ordinal[0] >= 0; row++) {
      if (el.hasRow(row)) {
        Object[] found = unbuiltRowOf(el.rowElement(row), ordinal);
        if (found != null || ordinal[0] < 0) {
          return found;
        }
        continue;
      }
      int count = fieldsInRow(el, row);
      if (ordinal[0] < count) {
        return new Object[] {el, row};
      }
      ordinal[0] -= count;
    }
    return null;
  }

  /** Translates a scope decorator's line and character offsets to one built field of the scope. */
  private record ScopeFieldDecorator(Decorator decorator, int lineOffset, int charOffset)
      implements Decorator {
    @Override
    public void line(int line, int start, String text, Spans out) {
      decorator.line(lineOffset + line, charOffset + start, text, out);
    }
  }

  /** Hands each built field of every decorated selection scope its part of the decorator. */
  private void syncScopeDecorators(El el) {
    if (el.isExiting()) {
      return;
    }
    if (el.isSelectionScope() && el.decorator() != null) {
      Decorator decorator = el.decorator();
      ScopeIndex index = scopeIndex(el);
      El list = scopeList(el);
      Object listId = list == null ? null : animator.identity(list);
      walkScopeFields(el, false, (row, ordinal, field, text) -> {
        int whole = ScopePosition.inRows(row) && list.spansRows()
            ? rowFieldOrdinal(list, listId, row, null, field, animator.identity(field))
            : ordinal;
        int i = whole < 0 ? -1 : index.indexOf(row, whole);
        if (field.text() != null) {
          field
              .text()
              .scopeDecorator(
                  i < 0
                      ? null
                      : new ScopeFieldDecorator(
                          decorator, index.lines()[i], index.starts()[i]));
        }
      });
    } else {
      for (El child : el.children()) {
        syncScopeDecorators(child);
      }
    }
    for (El overlay : el.overlays()) {
      syncScopeDecorators(overlay);
    }
  }

  /**
   * Collects the read-only, unmasked fields inside one virtual list row in tree order, null for
   * one under {@code opacity(0)}, which keeps its ordinal but copies nothing.
   */
  /**
   * Collects the read-only fields inside {@code el} in order, null for a hidden one. A list in a
   * row adds the fields of all its rows: those not built as its row function builds them with
   * {@code build}, else as nulls, as many as they have.
   */
  private void collectRowFields(El el, boolean shown, List<El> out, boolean build) {
    if (!numbered(el)) {
      return;
    }
    shown &= el.opacity() != 0;
    if (isScopeFieldCandidate(el)) {
      out.add(shown ? el : null);
    }
    if (el.isVirtualList()) {
      for (int row = 0; row < el.rowCount(); row++) {
        if (build || el.hasRow(row)) {
          El rowEl = el.rowElement(row);
          int before = out.size();
          if (rowEl != null) {
            collectRowFields(rowEl, shown, out, build);
          }
          if (!el.hasRow(row)) {
            keepCount(el, row, out.size() - before); // for fieldsInRow, which then builds none
          }
        } else {
          out.addAll(Collections.nCopies(fieldsInRow(el, row), null));
        }
      }
      return;
    }
    for (El child : el.children()) {
      collectRowFields(child, shown, out, build);
    }
  }

  /** The ordinal of {@code target} among read-only unmasked fields in {@code rowEl}, or -1. */
  private int fieldOrdinalIn(El rowEl, El target) {
    int[] pos = {ScopePosition.BEFORE, 0};
    return findInRow(rowEl, target, pos) ? pos[1] : -1;
  }

  /**
   * As {@link #findOutsidePosition} inside a row of a scope's list, where a virtual list counts
   * the fields of all its rows, those not built as its row function builds them.
   */
  private boolean findInRow(El el, El target, int[] pos) {
    if (!numbered(el)) {
      return false;
    }
    if (el == target) {
      return true;
    }
    if (isScopeFieldCandidate(el)) {
      pos[1]++;
    }
    if (!el.isVirtualList()) {
      for (El child : el.children()) {
        if (findInRow(child, target, pos)) {
          return true;
        }
      }
      return false;
    }
    for (int row = 0; row < el.rowCount(); row++) {
      if (!el.hasRow(row)) {
        pos[1] += fieldsInRow(el, row);
      } else if (findInRow(el.rowElement(row), target, pos)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The number of read-only fields in row {@code row} of a virtual list inside a row of a scope's
   * list, as its row function builds it: counted once per tree.
   */
  private int fieldsInRow(El list, int row) {
    Integer kept = keptCount(list, row);
    if (kept != null) {
      return kept;
    }
    int count = countFields(list, row);
    keepCount(list, row, count);
    return count;
  }

  /** The fields of a row as counted before, or null. */
  private Integer keptCount(El list, int row) {
    RowHeights heights = list.rowHeights();
    Object[] versions = list.rowVersions();
    if (heights != null && versions != null && row < versions.length) {
      Object[] kept = keptByKey(list, heights).get(heights.key(row));
      return kept != null && Objects.equals(kept[0], versions[row]) ? (Integer) kept[1] : null;
    }
    Map<Integer, Integer> counts = unversionedCounts(list, false);
    return counts == null ? null : counts.get(row);
  }

  /**
   * The counts of a list without versions: by its heights for the view's build, which a list built
   * again for reading shares with the one in the tree, else by its element.
   */
  private Map<Integer, Integer> unversionedCounts(El list, boolean make) {
    RowHeights heights = list.rowHeights();
    if (heights == null) {
      return make
          ? unbuiltFieldCounts.computeIfAbsent(list, ignored -> new HashMap<>())
          : unbuiltFieldCounts.get(list);
    }
    if (countsBuild != builds) {
      buildFieldCounts.clear();
      countsBuild = builds;
    }
    return make
        ? buildFieldCounts.computeIfAbsent(heights, ignored -> new HashMap<>())
        : buildFieldCounts.get(heights);
  }

  /**
   * Keeps the fields of a row: across trees while the row's key and version stay, as its texts
   * are (see {@link RowHeights}), else for the life of the list's element.
   */
  private void keepCount(El list, int row, int count) {
    RowHeights heights = list.rowHeights();
    Object[] versions = list.rowVersions();
    if (heights != null && versions != null && row < versions.length) {
      keptByKey(list, heights).put(heights.key(row), new Object[] {versions[row], count});
    } else {
      unversionedCounts(list, true).put(row, count);
    }
  }

  /** The counts by key of a list's heights, without the keys of rows gone, once per element. */
  private Map<Object, Object[]> keptByKey(El list, RowHeights heights) {
    Map<Object, Object[]> byKey = keptFieldCounts.computeIfAbsent(heights, h -> new HashMap<>());
    if (prunedCounts.put(list, Boolean.TRUE) == null && !byKey.isEmpty()) {
      Set<Object> keys = new HashSet<>();
      for (int row = 0; row < list.rowCount(); row++) {
        keys.add(heights.key(row));
      }
      byKey.keySet().retainAll(keys);
    }
    return byKey;
  }

  private int countFields(El list, int row) {
    List<El> fields = new ArrayList<>();
    El rowEl = list.rowElement(row);
    if (rowEl != null) {
      collectRowFields(rowEl, true, fields, true);
    }
    return fields.size();
  }

  /**
   * The ordinal of the field with identity {@code target} among the read-only fields inside row
   * element {@code el} with identity {@code id}, counting the rows not built of the lists in it,
   * or -1 if it is not inside: also for a field in such a row.
   */
  private int ordinalIn(El el, Object id, Object target, int[] ordinal) {
    if (!numbered(el)) {
      return -1;
    }
    if (id.equals(target)) {
      return ordinal[0];
    }
    if (isScopeFieldCandidate(el)) {
      ordinal[0]++;
    }
    if (el.isVirtualList()) {
      int in = Animator.rowIn(target, id);
      for (int row = 0; row < el.rowCount(); row++) {
        if (row == in) {
          El rowEl = el.rowElement(row);
          return rowEl == null ? -1 : ordinalIn(rowEl, Animator.row(id, row), target, ordinal);
        }
        ordinal[0] += el.hasRow(row) ? builtFields(el.rowElement(row)) : fieldsInRow(el, row);
      }
      return -1;
    }
    List<El> children = el.children();
    Object[] childIds = Animator.childIds(el, id);
    for (int i = 0; i < children.size(); i++) {
      int found = ordinalIn(children.get(i), childIds[i], target, ordinal);
      if (found >= 0) {
        return found;
      }
    }
    return -1;
  }

  private int builtFields(El rowEl) {
    List<El> fields = new ArrayList<>();
    collectRowFields(rowEl, true, fields, false);
    return fields.size();
  }

  /**
   * The {@code {row, ordinal}} of {@code target} outside {@code skipList} inside {@code scope},
   * with row {@link ScopePosition#BEFORE} or {@link ScopePosition#AFTER}, or null if not found.
   */
  private static int[] outsidePositionIn(El scope, El target, El skipList) {
    int[] pos = {ScopePosition.BEFORE, 0};
    return findOutsidePosition(scope, target, skipList, pos) ? pos : null;
  }

  private static boolean findOutsidePosition(El el, El target, El skipList, int[] pos) {
    if (el == skipList) {
      pos[0] = ScopePosition.AFTER;
      pos[1] = 0;
      return false;
    }
    if (!numbered(el)) {
      return false;
    }
    if (el == target) {
      return true;
    }
    if (isScopeFieldCandidate(el)) {
      pos[1]++;
    }
    for (El child : el.children()) {
      if (findOutsidePosition(child, target, skipList, pos)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Collects the built read-only, unmasked fields inside {@code scope} in tree order. For a virtual
   * list that builds only the columns in view sideways, rows matching {@code resolveRow} get their
   * whole-width ordinals immediately; other rows still carry their field identities on {@link
   * ScopeField#id} for on-demand lookup.
   */
  private List<ScopeField> scopeFields(El scope, IntPredicate resolveRow) {
    List<El> path = new ArrayList<>();
    if (scope == null || root == null || !pathTo(root, scope, path)) {
      return List.of();
    }
    List<El> layer = fromOverlay(path);
    float originX = 0;
    float originY = 0;
    float clipL = Float.NEGATIVE_INFINITY;
    float clipT = Float.NEGATIVE_INFINITY;
    float clipR = Float.POSITIVE_INFINITY;
    float clipB = Float.POSITIVE_INFINITY;
    for (int i = 0; i < layer.size() - 1; i++) {
      El el = layer.get(i);
      originX += el.x();
      originY += el.y();
      if (el.style().overflowX != Style.Overflow.VISIBLE) {
        clipL = Math.max(clipL, originX);
        clipR = Math.min(clipR, originX + el.width());
      }
      if (el.style().overflowY != Style.Overflow.VISIBLE) {
        clipT = Math.max(clipT, originY);
        clipB = Math.min(clipB, originY + el.height());
      }
    }
    List<ScopeField> out = new ArrayList<>();
    El list = scopeList(scope);
    Object listId = list != null && list.spansRows() ? animator.identity(list) : null;
    collectScopeFields(
        scope,
        list,
        listId,
        resolveRow,
        null,
        null,
        new int[] {ScopePosition.BEFORE, 0},
        true,
        originX,
        originY,
        clipL,
        clipT,
        clipR,
        clipB,
        out);
    return out;
  }

  private void collectScopeFields(
      El el,
      El list,
      Object listId,
      IntPredicate resolveRow,
      Object elId,
      Map<Object, Integer> rowOrdinals,
      int[] pos,
      boolean shown,
      float parentX,
      float parentY,
      float clipL,
      float clipT,
      float clipR,
      float clipB,
      List<ScopeField> out) {
    if (!numbered(el)) {
      return;
    }
    shown &= el.isPlaced() && el.opacity() != 0; // a hidden field keeps its ordinal
    float left = parentX + el.x();
    float top = parentY + el.y();
    float right = left + el.width();
    float bottom = top + el.height();
    float nextClipL = clipL;
    float nextClipT = clipT;
    float nextClipR = clipR;
    float nextClipB = clipB;
    if (el.style().overflowX != Style.Overflow.VISIBLE) {
      nextClipL = Math.max(clipL, left);
      nextClipR = Math.min(clipR, right);
    }
    if (el.style().overflowY != Style.Overflow.VISIBLE) {
      nextClipT = Math.max(clipT, top);
      nextClipB = Math.min(clipB, bottom);
    }
    if (el == list) {
      List<El> children = el.children();
      boolean spans = el.spansRows() && listId != null;
      for (int i = 0; i < children.size(); i++) {
        int row = el.firstRow() + i;
        Object rowId = spans ? Animator.row(listId, row) : null;
        Map<Object, Integer> ordinals =
            spans && resolveRow.test(row) ? rowOrdinals(el, listId, row) : null;
        collectScopeFields(
            children.get(i),
            null,
            null,
            resolveRow,
            rowId,
            ordinals,
            new int[] {row, 0},
            shown,
            left,
            top,
            nextClipL,
            nextClipT,
            nextClipR,
            nextClipB,
            out);
      }
      pos[0] = ScopePosition.AFTER;
      pos[1] = 0;
      return;
    }
    if (isScopeFieldCandidate(el)) {
      int index = rowOrdinals != null ? rowOrdinals.getOrDefault(elId, -1) : pos[1]++;
      if (shown && el.editor() != null) {
        out.add(new ScopeField(
            el,
            pos[0],
            index,
            elId,
            left,
            top,
            right,
            bottom,
            Math.max(left, clipL),
            Math.max(top, clipT),
            Math.min(right, clipR),
            Math.min(bottom, clipB)));
      }
    }
    List<El> children = el.children();
    // A list in a row: the fields of its rows not built count too, as its row function builds them.
    boolean nested = el.isVirtualList() && ScopePosition.inRows(pos[0]);
    for (int row = 0; nested && row < el.firstRow(); row++) {
      pos[1] += fieldsInRow(el, row);
    }
    Object[] childIds = elId == null ? null : Animator.childIds(el, elId);
    for (int i = 0; i < children.size(); i++) {
      collectScopeFields(
          children.get(i),
          list,
          listId,
          resolveRow,
          childIds == null ? null : childIds[i],
          rowOrdinals,
          pos,
          shown,
          left,
          top,
          nextClipL,
          nextClipT,
          nextClipR,
          nextClipB,
          out);
    }
    for (int row = el.firstRow() + children.size(); nested && row < el.rowCount(); row++) {
      pos[1] += fieldsInRow(el, row);
    }
  }

  /**
   * The index of the field nearest to {@code (x, y)}, preferring vertical proximity over horizontal
   * proximity so moving past the right end of a short line stays on that line.
   */
  private static int nearestScopeField(List<ScopeField> fields, float x, float y) {
    int best = -1;
    float bestDy = Float.POSITIVE_INFINITY;
    float bestDx = Float.POSITIVE_INFINITY;
    for (boolean clippedOnly : new boolean[] {true, false}) {
      for (int i = 0; i < fields.size(); i++) {
        ScopeField f = fields.get(i);
        if (clippedOnly && !f.visible()) {
          continue;
        }
        float l = clippedOnly ? f.clipLeft : f.left;
        float t = clippedOnly ? f.clipTop : f.top;
        float r = clippedOnly ? f.clipRight : f.right;
        float b = clippedOnly ? f.clipBottom : f.bottom;
        float dx = x < l ? l - x : x > r ? x - r : 0;
        float dy = y < t ? t - y : y > b ? y - b : 0;
        if (dy < bestDy || (dy == bestDy && dx < bestDx)) {
          bestDy = dy;
          bestDx = dx;
          best = i;
        }
      }
      if (best >= 0) {
        return best;
      }
    }
    return -1;
  }

  /**
   * Updates selections across {@code scope} from the anchor position in {@code s} to {@code
   * targetField} (or the nearest field to {@code (x, y)} when null), collapsing all other built
   * fields of the scope.
   */
  private boolean selectAcrossScope(El scope, Selecting s, El targetField, float x, float y) {
    List<ScopeField> fields = scopeFields(scope, r -> false);
    int targetIdx = -1;
    for (int i = 0; i < fields.size(); i++) {
      if (fields.get(i).el == targetField) {
        targetIdx = i;
        break;
      }
    }
    if (targetIdx < 0) {
      targetIdx = nearestScopeField(fields, x, y);
    }
    if (targetIdx < 0) {
      return false;
    }
    ScopeField targetSf = fields.get(targetIdx);
    El list = scopeList(scope);
    boolean spans = list != null && list.spansRows();
    Object listId = spans ? animator.identity(list) : null;
    int targetOrdinal = spans && ScopePosition.inRows(targetSf.row)
        ? listId == null || targetSf.id == null
            ? -1
            : rowOrdinals(list, listId, targetSf.row).getOrDefault(targetSf.id, -1)
        : targetSf.ordinal;
    if (targetOrdinal < 0) {
      return false;
    }
    El target = targetSf.el;
    float[] at = local(target, x, y);
    Editor targetEditor = target.editor();
    int boundary = targetEditor.fromShown(target.text().nearest(at[0], at[1]));
    int hit = targetEditor.fromShown(target.text().hit(at[0], at[1]));
    int fieldCmp = s.anchorFrom.compareField(targetSf.row, targetOrdinal);
    int rowsVersion = list == null ? 0 : list.rowsVersion();
    boolean endInRows = ScopePosition.inRows(targetSf.row);
    Object endRowKey = list != null && endInRows ? list.rowKey(targetSf.row) : null;
    Object endFieldId = endInRows ? null : fieldKey(target, animator.identity(target));
    boolean changed = false;
    if (fieldCmp == 0) {
      changed |=
          targetEditor.dragFrom(s.anchorFrom.offset(), s.anchorTo.offset(), s.unit, boundary, hit);
      for (int i = 0; i < fields.size(); i++) {
        if (i != targetIdx) {
          changed |= fields.get(i).el.editor().collapse();
        }
      }
      ScopePosition endPos = new ScopePosition(targetSf.row, targetOrdinal, targetEditor.caret());
      ScopeSelection next = new ScopeSelection(
          s.scopeId(),
          s.anchorFrom,
          s.anchorTo,
          endPos,
          s.listId,
          rowsVersion,
          s.anchorRowKey,
          endRowKey,
          s.anchorFieldId,
          endFieldId);
      changed |= !next.sameEndpoints(scopeSelection);
      scopeSelection = next;
      keptScope = null;
      changed |= focus.set(target, false, time);
      return changed;
    }
    boolean forward = fieldCmp > 0;
    int endOffset = targetEditor.dragEnd(boundary, hit, s.unit, forward);
    ScopePosition endPos = new ScopePosition(targetSf.row, targetOrdinal, endOffset);
    ScopeSelection next = new ScopeSelection(
        s.scopeId(),
        s.anchorFrom,
        s.anchorTo,
        endPos,
        s.listId,
        rowsVersion,
        s.anchorRowKey,
        endRowKey,
        s.anchorFieldId,
        endFieldId);
    boolean scopeChanged = !next.sameEndpoints(scopeSelection);
    scopeSelection = next;
    keptScope = null;
    ScopePosition start = scopeSelection.start();
    ScopePosition finish = scopeSelection.finish();
    for (ScopeField sf : fields) {
      int ordinal =
          spans && ScopePosition.inRows(sf.row) && (sf.row == start.row() || sf.row == finish.row())
              ? listId == null || sf.id == null
                  ? -1
                  : rowOrdinals(list, listId, sf.row).getOrDefault(sf.id, -1)
              : sf.ordinal;
      Editor editor = sf.el.editor();
      int[] range = scopeSelection.editorRangeFor(sf.row, ordinal, editor.text().length());
      if (range != null) {
        changed |= editor.setRange(range[0], range[1], sf.el == target);
        sf.el.text().unfocused(false);
      } else {
        changed |= editor.collapse();
      }
    }
    changed |= scopeChanged;
    changed |= focus.set(target, false, time);
    return changed;
  }

  /**
   * While a scope selection drag is held near or past the top or bottom edge of its scrolling
   * list, scrolls the list by a speed growing with the distance and extends the selection.
   */
  private boolean edgeScroll() {
    if (!(capture instanceof Selecting s)
        || s.scopeId() == null
        || !s.moved
        || Double.isNaN(s.scrollTime)) {
      return false;
    }
    double dt = time - s.scrollTime;
    if (dt <= 0) {
      return false;
    }
    settle();
    El scope = animator.liveElement(s.scopeId());
    El area = scope == null ? null : scopeScrollArea(scope);
    float[] box = area == null ? null : visibleBounds(area);
    if (box == null) {
      s.scrollTime = Double.NaN;
      return false;
    }
    float velocity = edgeScrollVelocity(box[1], box[3], s.y);
    if (velocity == 0 || !area.canScroll(false, velocity)) {
      s.scrollTime = Double.NaN;
      return false;
    }
    s.scrollTime = time;
    boolean moved = area.scrollTo(area.scrollX(), area.scrollY() + velocity * dt);
    if (!moved) {
      return false;
    }
    scrolled = true;
    settle(); // before onUserScroll, which may change what the row function reads
    stale |= drag(s, s.x, s.y);
    stale |= tellUserScroll(area);
    return true;
  }

  /** Updates whether a held scope drag is currently in an active edge-scroll zone. */
  private boolean updateEdgeScrollState() {
    if (!(capture instanceof Selecting s) || s.scopeId() == null || !s.moved || root == null) {
      if (capture instanceof Selecting s) {
        s.scrollTime = Double.NaN;
      }
      return false;
    }
    El scope = animator.liveElement(s.scopeId());
    El area = scope == null ? null : scopeScrollArea(scope);
    float[] box = area == null ? null : visibleBounds(area);
    float velocity = box == null ? 0 : edgeScrollVelocity(box[1], box[3], s.y);
    if (velocity == 0 || !area.canScroll(false, velocity)) {
      s.scrollTime = Double.NaN;
      return false;
    }
    if (Double.isNaN(s.scrollTime)) {
      s.scrollTime = time;
    }
    return true;
  }

  /** Signed vertical scroll velocity in logical px/s when {@code y} is in or past the edge margin. */
  private static float edgeScrollVelocity(float top, float bottom, float y) {
    float height = bottom - top;
    if (height <= 0) {
      return 0;
    }
    float margin = Math.min(EDGE_SCROLL_MARGIN, height / 4f);
    float depth;
    int dir;
    if (y < top + margin) {
      depth = (top + margin) - y;
      dir = -1;
    } else if (y > bottom - margin) {
      depth = y - (bottom - margin);
      dir = 1;
    } else {
      return 0;
    }
    float t = Math.min(1f, depth / EDGE_SCROLL_RAMP);
    float speed = EDGE_SCROLL_MIN_SPEED + (EDGE_SCROLL_MAX_SPEED - EDGE_SCROLL_MIN_SPEED) * t;
    return dir * speed;
  }

  /** The clipped window bounds {@code {left, top, right, bottom}} of {@code el}, or null if hidden. */
  private float[] visibleBounds(El el) {
    List<El> path = new ArrayList<>();
    if (el == null || root == null || !pathTo(root, el, path)) {
      return null;
    }
    List<El> layer = fromOverlay(path);
    float left = 0;
    float top = 0;
    float clipL = 0;
    float clipT = 0;
    float clipR = width;
    float clipB = height;
    for (int i = 0; i < layer.size(); i++) {
      El on = layer.get(i);
      left += on.x();
      top += on.y();
      if (i < layer.size() - 1) {
        if (on.style().overflowX != Style.Overflow.VISIBLE) {
          clipL = Math.max(clipL, left);
          clipR = Math.min(clipR, left + on.width());
        }
        if (on.style().overflowY != Style.Overflow.VISIBLE) {
          clipT = Math.max(clipT, top);
          clipB = Math.min(clipB, top + on.height());
        }
      }
    }
    float l = Math.max(left, clipL);
    float t = Math.max(top, clipT);
    float r = Math.min(left + el.width(), clipR);
    float b = Math.min(top + el.height(), clipB);
    return l < r && t < b ? new float[] {l, t, r, b} : null;
  }

  /**
   * A point of the window in the coordinates of a field's glyphs: physical pixels from the top
   * left of its content box, moved by its scroll.
   */
  private float[] local(El field, float x, float y) {
    List<El> path = new ArrayList<>();
    pathTo(root, field, path);
    float left = contentLeft(field);
    float top = contentTop(field);
    for (El el : fromOverlay(path)) {
      left += el.x();
      top += el.y();
    }
    Text text = field.text();
    float along = x - left;
    if (along < 0 && along >= -contentLeft(field)) {
      along = 0; // in the left padding, such as on an icon: the start of the text in view
    }
    return new float[] {
      along * text.scale() + text.scroll(), (y - top) * text.scale() + text.scrollY()
    };
  }

  /** The distance from an element's border box to its content box at the top. */
  private static float contentTop(El el) {
    return el.padding(Side.TOP) + el.border(Side.TOP);
  }

  /** The distance from an element's border box to its content box on the left. */
  private static float contentLeft(El el) {
    return el.padding(Side.LEFT) + el.border(Side.LEFT);
  }

  /**
   * The identities of elements with looks, by the state they are in; see {@link #wanted}. {@code
   * rested} are those with a {@link El#whenHoveredFor} look that is due, and {@code hoversRun}
   * counts the {@link El#onHoverFor} handlers run, so that one that ran builds the view again.
   */
  private record Looks(
      List<Object> hovered,
      List<Object> rested,
      Object focused,
      boolean focusRested,
      List<Object> within,
      Object pressed,
      int hoversRun) {

    /** These looks without those of elements in the rows of a list, which were built anew. */
    Looks without(Object list) {
      List<Object> kept = new ArrayList<>(hovered);
      kept.removeIf(id -> Animator.isBelow(id, list));
      List<Object> keptRested = new ArrayList<>(rested);
      keptRested.removeIf(id -> Animator.isBelow(id, list));
      List<Object> keptWithin = new ArrayList<>(within);
      keptWithin.removeIf(id -> Animator.isBelow(id, list));
      return new Looks(
          kept,
          keptRested,
          Animator.isBelow(focused, list) ? null : focused,
          focusRested,
          keptWithin,
          Animator.isBelow(pressed, list) ? null : pressed,
          hoversRun);
    }
  }

  /**
   * Builds the rows of the virtual lists in the tree that lack rows they show: all of them after a
   * rebuild of the view, and after scrolling those that show a row that does not exist yet. Each
   * gets the looks of the moment, however it came to be built.
   *
   * @return whether a list built its rows
   */
  // Lists in rows of another, as high as their rows, that grew since layout, for the row around
  // them to lay out again (fillChildren); and how often that, or the rows it showed, waited for
  // the next frame.
  private final List<El> regrownLists = new ArrayList<>();

  private int rowsDeferred;

  private boolean fillRows() {
    if (root == null) {
      return false;
    }
    if (hover.in()) {
      // The rows' looks follow what the pointer is over, and a modal overlay or a bar above a row
      // takes it: those of a tree just built are not placed yet.
      Overlays.place(root, width, height);
      El.placeBars(root);
    }
    float far = Float.POSITIVE_INFINITY;
    rowsWanted = false;
    rowKeyHooks = false;
    regrownLists.clear();
    inViewLists.clear();
    boolean built = fillRows(root, new ArrayList<>(), 0, 0, -far, far, -far, far, false);
    if (!rowGoals.isEmpty() && followRowGoals()) {
      inViewLists.clear();
      built |=
          fillRows(root, new ArrayList<>(), 0, 0, -far, far, -far, far, false); // the rows there
    }
    toldRows.keySet().retainAll(rowLists);
    rowLists.clear();
    // After scrolling, movement and new rows, the overlays follow their anchors.
    caretOverlays = Overlays.place(root, width, height);
    if (built) {
      endPresses(true); // of the rows that are gone
    }
    if (built || untimed) {
      timeOverlays();
    }
    if (root != null) {
      syncScopeDecorators(root);
    }
    return built;
  }

  /**
   * Follows which shown elements with an {@link El#onShownFor} handler are in the tree as it is
   * now, its overlays, its rows and the overlays that delayed looks attach included: each that
   * appears starts its time, and each that closes, leaves, loses its handler or is made invisible
   * by the view forgets it.
   */
  private void timeOverlays() {
    timeOverlays(false);
  }

  /**
   * @param rowsPending whether the rows of the tree are still to be built ({@link #rowsPending}):
   *     those of its rows' elements are kept, and the tree is timed again once they are there. A
   *     rebuild between two inputs of one frame times the tree too, so that an element that one of
   *     them closes and the next opens again is a new opening.
   */
  private void timeOverlays(boolean rowsPending) {
    untimed = rowsPending;
    Set<Object> open = new HashSet<>();
    shownDue = Double.POSITIVE_INFINITY;
    timeOverlays(root, open);
    shownTimes
        .keySet()
        .removeIf(id -> !open.contains(id)
            && !(rowsPending && (animator.inRow(id) != null || Animator.inFieldPart(id))));
  }

  private void timeOverlays(El el, Set<Object> open) {
    if (hidden(el)) {
      return;
    }
    if (el.onShownFor() != null) {
      Object id = el.placement() != null && el.placement().id != null
          ? el.placement().id
          : animator.identity(el);
      if (id != null) {
        open.add(id);
        Shown s = shownTimes.computeIfAbsent(id, opened -> new Shown(time));
        shownDue = Math.min(shownDue, s.due(el.shownDelay()));
      }
    }
    for (El child : el.children()) {
      timeOverlays(child, open);
    }
    for (El overlay : el.overlays()) {
      timeOverlays(overlay, open);
    }
  }

  /** Whether an element and all inside it are not shown: leaving, or made invisible by the view. */
  private static boolean hidden(El el) {
    return el.isExiting() || el.targetOpacity() == 0 || el.style().display == Style.Display.NONE;
  }

  /**
   * Ends what the pointer or a key holds whose element has left the tree or is leaving: a press,
   * a secondary press, the pressed look of Enter or Space, a selection by dragging and a thumb's
   * drag end with their element's life, so that one built again with the same identity takes
   * none of them over. A drag that has started goes on ({@link #step}).
   *
   * @param rowsBuilt whether the lists have built their rows: a row that its list does not have
   *     then has left the tree, while before that it may only be still to come
   */
  private void endPresses(boolean rowsBuilt) {
    Object held = switch (capture) {
      case Press p -> p.id();
      case Selecting s -> s.scopeId() != null ? s.scopeId() : s.id();
      case Grab g -> g.id();
      case Idle _, ActiveDrag _ -> null;
    };
    if (gone(held, rowsBuilt)) {
      Dragging drag = startedDrag();
      capture = drag == null ? IDLE : new ActiveDrag(drag);
    }
    if (scopeSelection != null && gone(scopeSelection.scopeId, rowsBuilt)) {
      scopeSelection = null;
    }
    if (keptScope != null && gone(keptScope.scopeId, rowsBuilt)) {
      keptScope = null;
    }
    if (gone(secondary, rowsBuilt)) {
      secondary = null;
    }
    if (gone(keyPressed, rowsBuilt)) {
      keyPressed = null;
      keyPressKey = null;
    }
  }

  /**
   * Whether the element with this identity is not in the current tree, or it or an element above
   * it is leaving; false for null, before the lists have built their rows for one in a row that
   * its list could build but does not have yet, and for one in the gutter of a field there, whose
   * cells are built after layout: the release finds the cell or not.
   */
  private boolean gone(Object id, boolean rowsBuilt) {
    if (id == null) {
      return false;
    }
    Animator.InRow in = animator.inRow(id);
    if (in != null && !in.list().hasRow(in.row())) {
      return rowsBuilt || in.row() >= in.list().rowCount();
    }
    return animator.liveElement(id) == null;
  }

  /** @param inRows whether {@code el} is in a row of a virtual list */
  private boolean fillRows(
      El el,
      List<El> path,
      float parentX,
      float parentY,
      float clipLeft,
      float clipRight,
      float clipTop,
      float clipBottom,
      boolean inRows) {
    if (!el.isPlaced() || el.isExiting()) {
      return false;
    }
    rowKeyHooks |= inRows && el.onAnyKeyDown() != null;
    path.add(el);
    float left = parentX + el.x();
    float top = parentY + el.y();
    boolean relook = !relooking.isEmpty()
        && el.isVirtualList()
        && relooking.contains(Animator.identities(path).getLast());
    int[] range =
        el.wantedRows(left, top, clipLeft, clipRight, clipTop, clipBottom, width, height, relook);
    boolean built = range.length != 0;
    if (el.onRowsBuilt() != null) {
      rowLists.add(Animator.identities(path).getLast());
    }
    if (el.onRowsInView() != null) {
      inViewLists.put(Animator.identities(path).getLast(), el);
    }
    range = buildRows(el, path, range, left, top, clipLeft, clipRight, clipTop, clipBottom);
    float wantedTop = clipTop;
    float wantedBottom = clipBottom;
    if (el.style().overflowX != Style.Overflow.VISIBLE) {
      clipLeft = Math.max(clipLeft, left);
      clipRight = Math.min(clipRight, left + el.width());
    }
    if (el.style().overflowY != Style.Overflow.VISIBLE) {
      clipTop = Math.max(clipTop, top);
      clipBottom = Math.min(clipBottom, top + el.height());
    }
    int pending = rowsDeferred;
    built |= fillChildren(
        el,
        path,
        left,
        top,
        clipLeft,
        clipRight,
        clipTop,
        clipBottom,
        wantedTop,
        wantedBottom,
        inRows);
    if (rowsDeferred != pending) {
      range = el.wantedRows(
          left, top, clipLeft, clipRight, wantedTop, wantedBottom, width, height, false);
    }
    if (inRows && el.rowsRegrown()) { // after its rows, whose lists may have grown them
      regrownLists.add(el);
    }
    rowsWanted |= range.length != 0; // beyond the passes of one frame: the next frame goes on
    boolean tail = !el.isVirtualList()
        || !el.measuresRows()
        || el.rowCount() == 0
        || el.hasRow(el.toEnd() ? el.endRow() : el.rowCount() - 1);
    // A field's editor lays out its rows after this and ends it then.
    if (el.toEnd() && (el.text() == null || !el.text().multiline())) {
      rowGoals.remove(Animator.identities(path).getLast()); // the later end wins
      // done once its last rows are measured, with the lists in them
      el.toEnd(range.length != 0 || !tail || rowsDeferred != pending);
    }
    float far = Float.POSITIVE_INFINITY;
    for (El overlay : el.overlays()) { // in window coordinates
      built |= fillRows(overlay, path, 0, 0, -far, far, -far, far, inRows);
    }
    path.removeLast();
    return built;
  }

  /**
   * Builds and lays out the rows {@code range} of virtual list {@code el}, then those that the
   * heights they measured show, a few times at most.
   *
   * @return the rows still wanted after those passes, which the next frame builds
   */
  private int[] buildRows(
      El el,
      List<El> path,
      int[] range,
      float left,
      float top,
      float clipLeft,
      float clipRight,
      float clipTop,
      float clipBottom) {
    // Rows as high as their content may cover less or more than estimated: a second pass, or a
    // third, builds the rows the measured heights show.
    for (int pass = 0; range.length != 0 && pass < 3; pass++) {
      Object id = Animator.identities(path).getLast();
      int asking = El.autofocuses();
      long start = System.nanoTime();
      el.makeRows(range[0], range[1], lookedRows(id)::contains);
      buildNanos += System.nanoTime() - start;
      tellRows(el, id, range[0], range[1] - 1);
      shown = shown.without(id);
      remapScopeOutsideFields(root); // a scope inside a row is built only now
      for (El row : el.children()) {
        if (!row.isKept()) { // a kept row has its editors
          attachEditors(row, path);
        }
      }
      el.layoutRows(scale, systemFonts);
      // The row under the pointer is found after layout, as rowLooks finds it, and built anew.
      for (Object at : pointedIds()) {
        int row = Animator.rowIn(at, id);
        int index = row - el.firstRow();
        if (el.hasRow(row) && el.children().get(index).isKept()) {
          long remade = System.nanoTime();
          el.remakeRow(index);
          buildNanos += System.nanoTime() - remade;
          attachEditors(el.children().get(index), path);
          el.layoutRow(index, scale, systemFonts);
        }
      }
      rowLooks(el, id); // after layout, which says which row is under the pointer
      for (El row : el.children()) {
        if (!row.isKept()) {
          Overlays.layout(row, width, height); // after the looks, which may attach overlays
        }
      }
      // As openAndClose for the rest, where a row asked for the focus.
      if (El.autofocuses() != asking) {
        focus.ask(animator.autofocusInRows(el, id), time);
      }
      animator.rows(el, id, time);
      range = el.measuresRows()
          ? el.wantedRows(left, top, clipLeft, clipRight, clipTop, clipBottom, width, height, false)
          : El.NO_ROWS;
    }
    rowsWanted |= range.length != 0; // beyond the passes of one frame: the next frame goes on
    return range;
  }

  /**
   * Fills the rows of the lists below {@code el}. In a virtual list as high as its rows' content,
   * a row whose own lists, as high as their rows, grew with rows they measured is laid out again:
   * the rows below it move, and the list builds the rows that come into view then and fills them,
   * a few times at most; what is left waits for the next frame.
   */
  private boolean fillChildren(
      El el,
      List<El> path,
      float left,
      float top,
      float clipLeft,
      float clipRight,
      float clipTop,
      float clipBottom,
      float wantedTop,
      float wantedBottom,
      boolean inRows) {
    boolean built = false;
    boolean list = el.isVirtualList();
    for (int round = 0; ; round++) {
      boolean relaid = false;
      List<El> children = el.children();
      for (int i = 0; i < children.size(); i++) {
        int mark = regrownLists.size();
        built |= fillRows(
            children.get(i),
            path,
            left,
            top,
            clipLeft,
            clipRight,
            clipTop,
            clipBottom,
            inRows || list);
        if (!list || regrownLists.size() == mark) {
          continue;
        }
        List<El> grown = regrownLists.subList(mark, regrownLists.size());
        if (el.measuresRows() && round < 3) {
          el.layoutRow(i, scale, systemFonts); // which measures its lists again
          el.remeasureRow(i);
          grown.forEach(El::rowsSettled);
          relaid = true;
        } else {
          rowsDeferred++; // still to lay out: the lists stay grown for the next frame
          rowsWanted |= el.measuresRows();
        }
        grown.clear(); // a list takes its rows' growth
      }
      if (!relaid) {
        return built;
      }
      built = true;
      int[] range = el.wantedRows(
          left, top, clipLeft, clipRight, wantedTop, wantedBottom, width, height, false);
      if (range.length != 0 // rows below the grown ones moved, others came into view
          && buildRows(el, path, range, left, top, clipLeft, clipRight, wantedTop, wantedBottom)
                  .length
              != 0) {
        rowsDeferred++; // the next frame goes on
      }
    }
  }

  /**
   * Whether layout or scrolling made an answer of {@link ScrollState#atEnd} wrong that an area at or
   * below {@code el} gave the view; forgets the answers of those, so that a view that does not ask
   * again is not built again for them.
   */
  private static boolean endsMoved(El el) {
    boolean moved = false;
    ScrollState state = el.scrollState();
    if (state != null && state.el == el && (state.atEnd ? state.toldAway : state.toldEnd)) {
      state.toldEnd = false;
      state.toldAway = false;
      moved = true;
    }
    for (El child : el.children()) {
      moved |= endsMoved(child);
    }
    for (El overlay : el.overlays()) {
      moved |= endsMoved(overlay);
    }
    return moved;
  }

  /** Collects the {@link El#onResize} handlers below {@code el} whose element has a new size. */
  private void tellSizes(El el, List<El> path, Map<Object, Long> sizes) {
    path.add(el);
    El.SizeHandler handler = el.onResize();
    if (handler != null) {
      float w = el.width();
      float h = el.height();
      long size = (long) Float.floatToIntBits(w) << 32 | Float.floatToIntBits(h) & 0xFFFFFFFFL;
      Object id = Animator.identities(path).getLast();
      sizes.put(id, size);
      Long before = toldSizes.get(id);
      if (before == null || before != size) {
        resized.put(id, () -> {
          handler.accept(w, h);
          handled = true;
          return true;
        });
      }
    }
    for (El child : el.children()) {
      tellSizes(child, path, sizes);
    }
    for (El overlay : el.overlays()) {
      tellSizes(overlay, path, sizes);
    }
    path.removeLast();
  }

  /**
   * The rows of the list with identity {@code list} that a look shown, the focus or a press is
   * in. The list builds them anew instead of keeping them, so that they show the looks of the
   * moment, from the targets their row function gives; the row under the pointer too, once the
   * rows are laid out.
   */
  private Set<Integer> lookedRows(Object list) {
    List<Object> ids = new ArrayList<>(shown.hovered());
    ids.addAll(shown.rested());
    ids.addAll(shown.within());
    ids.add(shown.focused());
    ids.add(shown.pressed());
    ids.add(focus.id());
    ids.add(pressedId());
    Set<Integer> rows = new HashSet<>();
    for (Object id : ids) {
      int row = Animator.rowIn(id, list);
      if (row >= 0) {
        rows.add(row);
      }
    }
    return rows;
  }

  /** The rows a list told its {@link El#onRowsBuilt} handler, out of how many it had. */
  private record ToldRows(int first, int last, int count) {}

  /** Queues the {@link El#onRowsBuilt} handler of a list that built other rows than it told. */
  private void tellRows(El list, Object id, int first, int last) {
    El.RowsHandler handler = list.onRowsBuilt();
    if (handler == null || last < first) {
      return;
    }
    ToldRows rows = new ToldRows(first, last, list.rowCount());
    if (!rows.equals(toldRows.put(id, rows))) {
      if (list.onRowsInView() != null) {
        heldRows.put(id, () -> handler.accept(first, last)); // see tellInView
        return;
      }
      heldRows.remove(id); // of a handler since gone: these rows are newer
      scheduler.add(
          () -> { // after the input of this frame, or in the next one
            handler.accept(first, last);
            handled = true;
            return true;
          });
    }
  }

  /**
   * Queues the {@link El#onRowsInView} handlers of the lists that show other rows than they told,
   * at the end of a frame: not the rows of a layout between its inputs that it does not show. The
   * {@link El#onRowsBuilt} handler held for such a list runs with it, as one input.
   */
  private void tellInView() {
    toldInView.keySet().retainAll(inViewLists.keySet());
    inViewLists.forEach((id, list) -> {
      El.RowsHandler handler = list.onRowsInView();
      Runnable built = heldRows.remove(id);
      long rows = list.rowsInView();
      Long before = toldInView.put(id, rows);
      boolean moved = before == null || before != rows;
      if (built != null || moved) {
        scheduler.add(() -> {
          handled = true;
          try {
            if (built != null) {
              built.run();
            }
          } finally {
            if (moved) {
              handler.accept((int) (rows >> 32), (int) rows);
            }
          }
          return true;
        });
      }
    });
    for (Runnable built : heldRows.values()) { // of a list gone since
      scheduler.add(() -> {
        built.run();
        handled = true;
        return true;
      });
    }
    heldRows.clear();
  }

  /**
   * Applies the looks of the moment to the rows a list has just built, as {@link #applyLooks} does
   * for the rest of the tree, and lays out again each row that took one. The rows are laid out
   * first, so that the pointer finds the row it is over now, after a scroll or a movement too. A
   * row the list kept has none of these looks ({@link #lookedRows}).
   */
  private void rowLooks(El list, Object listId) {
    List<Object> under = pointedIds();
    List<Object> hovered = new ArrayList<>(shown.hovered());
    List<Object> rested = new ArrayList<>(shown.rested());
    Object focus = shown.focused();
    List<Object> within = new ArrayList<>(shown.within());
    Object press = shown.pressed();
    for (int i = 0; i < list.children().size(); i++) {
      El row = list.children().get(i);
      if (row.isKept()) {
        continue;
      }
      Object rowId = Animator.row(listId, list.firstRow() + i);
      boolean took = false;
      for (Object id : under) {
        if (Animator.isBelow(id, listId)
            && look(Animator.find(row, rowId, id), El::hoverLook, "whenHovered")) {
          hovered.add(id);
          took = true;
        }
      }
      for (Object id : under) {
        if (Animator.isBelow(id, listId) && restLook(Animator.find(row, rowId, id), id)) {
          rested.add(id);
          took = true;
        }
      }
      if (Animator.isBelow(this.focus.id(), listId)) {
        El el = Animator.find(row, rowId, this.focus.id());
        if (el != null && el.isFocusable() && look(el, El::focusLook, "whenFocused")) {
          focus = this.focus.id();
          took = true;
        }
        for (Object id : Animator.lineage(this.focus.id())) {
          if (Animator.isBelow(id, listId)
              && look(Animator.find(row, rowId, id), El::focusWithinLook, "whenFocusWithin")) {
            within.add(id);
            took = true;
          }
        }
      }
      Object pressId = pressedId();
      if (Animator.isBelow(pressId, listId)
          && look(Animator.find(row, rowId, pressId), El::pressLook, "whenPressed")) {
        press = pressId;
        took = true;
      }
      if (took) {
        list.layoutRow(i, scale, systemFonts); // a look may change the layout
      }
    }
    shown =
        new Looks(hovered, rested, focus, shown.focusRested(), within, press, shown.hoversRun());
  }

  /**
   * When the looks of the moment differ from those the tree has only in hover looks ({@link
   * El#whenHovered}, {@link El#whenHoveredFor}) of elements in rows of virtual lists, builds the
   * rows of those lists that have a look or should have one anew, as scrolling does, instead of
   * the view. An {@link El#onHoverFor} handler that ran, or any other look, leaves it to a rebuild
   * of the view, which shows what the handler changed.
   *
   * @return whether it built rows
   */
  private boolean relookRows(Looks wanted) {
    if (!Objects.equals(wanted.focused(), shown.focused())
        || wanted.focusRested() != shown.focusRested()
        || !wanted.within().equals(shown.within())
        || !Objects.equals(wanted.pressed(), shown.pressed())
        || wanted.hoversRun() != shown.hoversRun()) {
      return false;
    }
    Set<Object> differ = new HashSet<>();
    differing(wanted.hovered(), shown.hovered(), differ);
    differing(wanted.rested(), shown.rested(), differ);
    if (differ.isEmpty()) {
      return false; // the same looks in another order
    }
    Set<Object> lists = new HashSet<>();
    for (Object id : differ) {
      Animator.InRow in = animator.inRow(id);
      if (in == null || !in.list().hasRow(in.row())) {
        return false;
      }
      lists.add(in.listId());
    }
    animator.present(time); // the sizes of the moment, which a rebuild's update gives
    relooking.addAll(lists);
    boolean built;
    try {
      built = fillRows();
    } finally {
      relooking.clear();
    }
    if (built) {
      placeEditors(false, false);
      focus.openAndCloseParts(root, time);
      refocus();
      placeEditors(true, false);
    }
    return built;
  }

  /** Adds to {@code into} the identities that are in one of the two lists and not in the other. */
  private static void differing(List<Object> a, List<Object> b, Set<Object> into) {
    for (Object id : a) {
      if (!b.contains(id)) {
        into.add(id);
      }
    }
    for (Object id : b) {
      if (!a.contains(id)) {
        into.add(id);
      }
    }
  }

  /**
   * The looks that the elements of the current tree ask for in the states of the moment: those
   * along {@code path} under the pointer with a hover look, those there with a {@link
   * El#whenHoveredFor} look that is due, and the focused and the pressed element if they have a
   * look for that. This also runs the {@link El#onHoverFor} handlers that are due.
   */
  private Looks wanted(List<El> path, List<Object> ids) {
    List<Object> hovered = new ArrayList<>();
    for (int i = 0; i < path.size(); i++) {
      if (path.get(i).hoverLook() != null) {
        hovered.add(ids.get(i));
      }
    }
    List<Object> rested = rest(path, ids);
    El focus = this.focus.id() == null ? null : animator.element(this.focus.id());
    List<Object> within = new ArrayList<>();
    if (this.focus.id() != null) {
      for (Object id : Animator.lineage(this.focus.id())) {
        El el = animator.element(id);
        if (el != null && el.focusWithinLook() != null) {
          within.add(id);
        }
      }
    }
    Object pressId = pressedId();
    El press = pressId == null ? null : animator.element(pressId);
    return new Looks(
        hovered,
        rested,
        focus != null && focus.focusLook() != null ? this.focus.id() : null,
        focusRested(focus),
        within,
        press != null && press.pressLook() != null ? pressId : null,
        hoversRun);
  }

  /**
   * Whether the {@link El#whenFocusVisibleFor} look of the focused element is due; when it will
   * be, notes that in {@link #restDue}, after {@link #rest} has set it.
   */
  private boolean focusRested(El focus) {
    double due = this.focus.visibleAt(focus);
    if (Double.isNaN(due)) {
      return false;
    }
    if (due > time) {
      restDue = Math.min(restDue, due);
      return false;
    }
    return true;
  }

  /**
   * Follows how long the pointer has stayed on the elements along {@code path} that wait for it:
   * runs the {@link El#onHoverFor} handlers that are due, notes in {@link #restDue} when the next
   * look or handler is, and returns the identities of those whose {@link El#whenHoveredFor} look
   * is due.
   */
  private List<Object> rest(List<El> path, List<Object> ids) {
    resting
        .entrySet()
        .removeIf(
            stay -> { // a stay ends when the pointer leaves
              boolean left = !ids.contains(stay.getKey());
              // Only one that showed: a cut text's tooltip shows only while the text is cut.
              if (left
                  && root != null
                  && !Collections.disjoint(root.shownOverlays(), stay.getValue().attached)) {
                lookLeft = time;
              }
              return left;
            });
    restDue = Double.POSITIVE_INFINITY;
    List<Object> rested = new ArrayList<>();
    for (int i = 0; i < path.size(); i++) {
      El el = path.get(i);
      if (el.restLook() == null && el.onHoverFor() == null) {
        continue;
      }
      Rest rest = resting.computeIfAbsent(ids.get(i), id -> {
        Rest stay = new Rest(time);
        stay.warm = el.restLook() != null && time - lookLeft <= el.restDelay();
        return stay;
      });
      if (el.restLook() != null) {
        double due = lookDue(rest, el);
        if (due <= time) {
          rested.add(ids.get(i));
        } else {
          restDue = Math.min(restDue, due);
        }
      }
      if (el.onHoverFor() != null && !rest.ran) {
        double due = rest.since + el.hoverDelay();
        if (due <= time) {
          rest.ran = true;
          hoversRun++;
          ran = true; // the handler may change the state that other apps show
          el.onHoverFor().run();
        } else {
          restDue = Math.min(restDue, due);
        }
      }
    }
    updateHoverCallbacks(path, ids);
    updateTextHover(path, ids);
    return rested;
  }

  /** Fires {@link El#onHover} enter/leave handlers for elements along {@code path}. */
  private void updateHoverCallbacks(List<El> path, List<Object> ids) {
    if (!hoveredCallbacks.isEmpty()) {
      for (Iterator<Object> it = hoveredCallbacks.iterator(); it.hasNext(); ) {
        Object id = it.next();
        if (!ids.contains(id)) {
          it.remove();
          El left = animator.element(id);
          if (left != null && !left.isExiting() && left.onHover() != null) {
            hoversRun++;
            ran = true;
            left.onHover().accept(false);
          }
        }
      }
    }
    for (int i = 0; i < path.size(); i++) {
      El el = path.get(i);
      if (el.onHover() != null && hoveredCallbacks.add(ids.get(i))) {
        hoversRun++;
        ran = true;
        el.onHover().accept(true);
      }
    }
  }

  /**
   * Follows the character position under the pointer in multi-line fields with {@link
   * El#onTextPointerMove} or {@link El#onTextHoverFor}, updating {@link #restDue} when a delayed
   * hover is waiting.
   */
  private void updateTextHover(List<El> path, List<Object> ids) {
    if (held() != null) {
      return; // a press or a drag keeps what it suppressed until the pointer moves on after it
    }
    El activeField = null;
    Object activeId = null;
    int activePos = -1;
    if (hover.in() && !path.isEmpty()) {
      El leaf = path.getLast();
      if (leaf.text() != null
          && leaf.text().multiline()
          && (leaf.onTextPointerMove() != null || leaf.onTextHoverFor() != null)) {
        activeField = leaf;
        activeId = ids.getLast();
        activePos = textPositionAt(path, hover.x(), hover.y());
      }
    }
    int mods = hover.modifiers();
    if (!textHovered.isEmpty()) {
      for (Iterator<Map.Entry<Object, TextHover>> it = textHovered.entrySet().iterator();
          it.hasNext(); ) {
        Map.Entry<Object, TextHover> entry = it.next();
        if (!entry.getKey().equals(activeId)) {
          int prevPos = entry.getValue().position;
          it.remove();
          if (prevPos >= 0) {
            El left = animator.element(entry.getKey());
            if (left != null && !left.isExiting() && left.onTextPointerMove() != null) {
              hoversRun++;
              ran = true;
              left.onTextPointerMove().accept(-1, mods);
            }
          }
        }
      }
    }
    if (activeField == null) {
      return;
    }
    TextHover th = textHovered.computeIfAbsent(activeId, id -> new TextHover());
    boolean posChanged = activePos != th.position;
    boolean modsChanged = activePos >= 0 && mods != th.modifiers;
    if (posChanged || (th.pressedAt != NOT_PRESSED && activePos != th.pressedAt)) {
      th.since = activePos >= 0 ? time : Double.POSITIVE_INFINITY;
      th.ran = false;
      if (activePos != th.pressedAt) {
        th.pressedAt = NOT_PRESSED;
      }
    }
    if (posChanged) {
      th.position = activePos;
      th.modifiers = mods;
    } else if (modsChanged) {
      th.modifiers = mods;
    }
    if ((posChanged || modsChanged) && activeField.onTextPointerMove() != null) {
      hoversRun++;
      ran = true;
      activeField.onTextPointerMove().accept(activePos, mods);
    }
    if (activeField.onTextHoverFor() != null
        && activePos >= 0
        && !th.ran
        && th.pressedAt == NOT_PRESSED) {
      double due = th.since + activeField.textHoverDelay();
      if (due <= time) {
        th.ran = true;
        hoversRun++;
        ran = true;
        activeField.onTextHoverFor().accept(activePos, mods);
      } else {
        restDue = Math.min(restDue, due);
      }
    }
  }

  /**
   * The character position under window coordinates {@code (x, y)} in the multi-line field at the
   * end of {@code pathToField}, or -1 if outside its text or on a scrollbar.
   */
  private static int textPositionAt(List<El> pathToField, float x, float y) {
    El field = pathToField.getLast();
    if (field.text() == null || !field.text().multiline() || field.barAt(x, y) != null) {
      return -1;
    }
    float left = 0;
    float top = 0;
    for (El el : fromOverlay(pathToField)) {
      left += el.x();
      top += el.y();
    }
    return field.textPositionAt(x - left, y - top);
  }

  /** Applies {@code el}'s {@link El#whenHoveredFor} look if it is due; whether it did. */
  private boolean restLook(El el, Object id) {
    Rest rest = el == null || el.restLook() == null ? null : resting.get(id);
    if (rest == null || lookDue(rest, el) > time) {
      return false;
    }
    rest.attached = delayedLook(el, el.restLook(), "whenHoveredFor");
    return true;
  }

  /** When the {@link El#whenHoveredFor} look of a stay is due. */
  private static double lookDue(Rest rest, El el) {
    return rest.warm ? rest.since : rest.since + el.restDelay();
  }

  /**
   * Applies a look that waited for a stay; the overlays it attaches, such as a tooltip, avoid
   * covering their anchor, under the pointer that would leave the anchor for them and hide them.
   *
   * @return the overlays it attached
   */
  private static List<El> delayedLook(El el, Consumer<El> look, String name) {
    int before = el.overlays().size();
    el.apply(look, name);
    List<El> attached = List.copyOf(el.overlays().subList(before, el.overlays().size()));
    for (El overlay : attached) {
      overlay.placement().avoidsAnchor = true;
    }
    return attached;
  }

  /**
   * Pauses timed elements under the pointer that asked for {@link El#pauseShownOnHover}, resumes
   * those the pointer left, and updates {@link #shownDue}.
   */
  private void syncShown(List<Object> under) {
    shownDue = Double.POSITIVE_INFINITY;
    for (Map.Entry<Object, Shown> entry : shownTimes.entrySet()) {
      El el = animator.element(entry.getKey());
      if (el == null || el.onShownFor() == null) {
        continue;
      }
      Shown s = entry.getValue();
      if (el.pausesShownOnHover() && under.contains(entry.getKey())) {
        s.pause(time);
      } else {
        s.resume(time);
      }
      shownDue = Math.min(shownDue, s.due(el.shownDelay()));
    }
  }

  /**
   * Runs the {@link El#onShownFor} handlers of the shown elements whose time has come, and notes
   * in {@link #shownDue} when the next one's will.
   *
   * @return whether one ran
   */
  private boolean shownFor() {
    if (shownTimes.isEmpty()) {
      shownDue = Double.POSITIVE_INFINITY;
      return false;
    }
    syncShown(Animator.identities(pointed())); // as updateHover sees it, also while a drag holds
    shownDue = Double.POSITIVE_INFINITY;
    boolean ran = false;
    for (Object id : List.copyOf(shownTimes.keySet())) {
      El el = animator.element(id);
      if (el == null || el.onShownFor() == null) {
        continue;
      }
      Shown s = shownTimes.get(id);
      if (s == null) {
        continue;
      }
      double due = s.due(el.shownDelay()); // infinite while paused or once it ran
      if (due <= time) {
        s.done(); // once per appearance
        handled = ran = true; // before it runs, as for a task
        el.onShownFor().run();
      } else {
        shownDue = Math.min(shownDue, due);
      }
    }
    return ran;
  }

  /**
   * Applies the looks of the moment to the tree the view has just built: the hover looks of the
   * elements with these identities, then those of {@link El#whenHoveredFor} that are due, then,
   * after the focus has followed the overlays that open and close, the focus look, then the
   * pressed one.
   *
   * @return the identities of the elements that took a look
   */
  private Looks applyLooks(El tree, List<Object> hoveredIds) {
    List<Object> hovered = new ArrayList<>();
    for (Object id : hoveredIds) {
      if (look(Animator.find(tree, id), El::hoverLook, "whenHovered")) {
        hovered.add(id);
      }
    }
    List<Object> rested = new ArrayList<>();
    for (Object id : hoveredIds) {
      if (restLook(Animator.find(tree, id), id)) {
        rested.add(id);
      }
    }
    openFieldMenu(tree);
    // After the looks, which may attach overlays, and before the focus look.
    focus.openAndClose(tree, time);
    El focus = this.focus.id() == null ? null : Animator.find(tree, this.focus.id());
    boolean focusLook = focus != null
        && focus.isFocusable() // or it loses the focus
        && look(focus, El::focusLook, "whenFocused");
    boolean focusRested = focus != null && focus.isFocusable() && focusRested(focus);
    if (focusRested) {
      delayedLook(focus, focus.focusRestLook(), "whenFocusVisibleFor");
    }
    List<Object> within = new ArrayList<>();
    if (this.focus.id() != null) {
      for (Object id : Animator.lineage(this.focus.id())) {
        if (look(Animator.find(tree, id), El::focusWithinLook, "whenFocusWithin")) {
          within.add(id);
        }
      }
    }
    Object pressId = pressedId();
    boolean pressLook =
        pressId != null && look(Animator.find(tree, pressId), El::pressLook, "whenPressed");
    return new Looks(
        hovered,
        rested,
        focusLook ? this.focus.id() : null,
        focusRested,
        within,
        pressLook ? pressId : null,
        hoversRun);
  }

  /**
   * Attaches the open {@link El#fieldMenu} to its field of a tree the view has just built, or
   * forgets it when it closed or the field no longer takes input.
   */
  private void openFieldMenu(El tree) {
    if (fieldMenu == null) {
      return;
    }
    El field = Animator.find(tree, fieldMenu.field());
    Editor editor = editors.get(fieldMenu.field());
    El menu = null;
    if (field != null && editor != null && field.fieldMenu() != null && field.takesEdits()) {
      fieldMenu.update(field, editor);
      menu = field.fieldMenu().build(field, fieldMenu);
    }
    if (menu == null) {
      fieldMenu = null;
    } else {
      field.overlay(menu);
    }
  }

  /** Applies {@code el}'s look of one state, if it has one; false if not. */
  private static boolean look(El el, Function<El, Consumer<El>> which, String name) {
    Consumer<El> look = el == null ? null : which.apply(el);
    if (look != null) {
      el.apply(look, name);
    }
    return look != null;
  }

  /**
   * Finds what is under the pointer in the tree of this frame, for the getters and the dump, and
   * gives the thumb under it or dragged its color.
   *
   * @return whether a thumb changed its color
   */
  private boolean updateHover() {
    List<El> under = pointed();
    if (!shownTimes.isEmpty()) {
      syncShown(Animator.identities(under));
    }
    Grab grab = capture instanceof Grab g ? g : null;
    El grabbed = grab == null ? null : animator.element(grab.id());
    boolean free = !(capture instanceof Selecting) && startedDrag() == null;
    return hover.update(under, grabbed, grab != null && grab.vertical(), free);
  }

  /**
   * The elements under the pointer, from the root down; during a drag, a selection in a field or a
   * thumb's drag, wherever the pointer is, the dragged element, the field or the scroll area and
   * their ancestors, so that they alone look hovered and set the cursor.
   */
  private List<El> pointed() {
    Object held = held();
    El el = held == null ? null : animator.liveElement(held);
    List<El> path = new ArrayList<>();
    if (el != null && pathTo(root, el, path)) {
      return fromOverlay(path);
    }
    return hover.in() ? hit(hover.x(), hover.y()) : List.of();
  }

  /**
   * The identities of {@link #pointed}, also for rows a list is building, which the animator does
   * not know yet.
   */
  private List<Object> pointedIds() {
    Object held = held();
    if (held != null) {
      return Animator.lineage(held);
    }
    return hover.in() ? Animator.identities(hit(hover.x(), hover.y())) : List.of();
  }

  /**
   * The identity of the element a press is on: the one with a press or drag handler it went to,
   * the text field it selects in, or the one Enter or Space pressed (see {@link #KEY_PRESS}); null
   * between presses.
   */
  private Object pressedId() {
    return switch (capture) {
      case Press p -> p.id();
      case Selecting s -> s.id();
      case Idle _, ActiveDrag _, Grab _ -> keyPressed;
    };
  }

  /** The identity of the element that a drag, a selection or a thumb's drag holds, or null. */
  private Object held() {
    return switch (capture) {
      case Selecting s ->
        s.scopeId() != null && animator.liveElement(s.id()) == null
            ? (focus.element() != null ? focus.id() : s.scopeId())
            : s.id();
      case Grab g -> g.id();
      case Press p -> p.drag() != null && p.drag().started ? p.drag().id : null;
      case ActiveDrag a -> a.drag().id;
      case Idle _ -> null;
    };
  }

  /**
   * Whether something had not reached its target in the last frame, the frame showed an element
   * with an {@link El#cycle}, or a held selection drag is auto-scrolling its list, so that the
   * next frame draws something new after {@link #advance} even without input. Once this is false
   * the app is idle.
   */
  public boolean isMoving() {
    return animator.isMoving() || cycling || edgeScrolling;
  }

  /**
   * The part of a self-drawn window frame at this position in logical pixels, which a window asks
   * while the pointer moves. The rule is the one presses follow: the topmost element under the
   * point decides, or its nearest ancestor that has a {@link El#windowPart} or a press handler. An
   * element with a handler and no part, a text field and a scrollbar are {@link
   * WindowPart#CONTROL}, so controls inside a title bar stay clickable and the resize margin gives
   * way to them. Outside a {@link El#modal} overlay, which takes all other pointer input, the parts
   * of the tree below it still count: the window can be moved, minimized, maximized and closed
   * while a dialog is open, and a press on such a part neither dismisses the dialog nor reaches
   * anything else; the controls below it take no press then and are content.
   *
   * @return the part, {@link WindowPart#CONTENT} where nothing is marked
   */
  public WindowPart windowPart(float x, float y) {
    List<El> hit = hit(x, y);
    boolean below = false;
    if (hit.isEmpty() && modal() != null) {
      hit = hitFrom(root, x, y); // the frame's own parts stay usable under a modal overlay
      below = true;
    }
    if (!below && !hit.isEmpty() && hit.getLast().barAt(x, y) != null) {
      return WindowPart.CONTROL;
    }
    for (int i = hit.size() - 1; i >= 0; i--) {
      El el = hit.get(i);
      boolean takes = takesPress(el) || el.takesEdits();
      if (el.windowPart() != null || takes) {
        WindowPart part = el.windowPart();
        if (part != null && part != WindowPart.CONTENT) {
          return part;
        }
        return takes && !below ? WindowPart.CONTROL : WindowPart.CONTENT;
      }
    }
    return WindowPart.CONTENT;
  }

  /** The tree of the last frame. */
  public El tree() {
    return root;
  }

  /**
   * What the last frame draws, with the focus ring if there is one. The app keeps one scene and
   * {@linkplain Scene#refill refills} it on every call of this method and of {@link #render}, so
   * a returned scene, and what its {@link Scene#data()}, {@link Scene#clips()} and {@link
   * Scene#images()} returned, is valid until the next call. Its glyphs are in the app's atlas,
   * which keeps them from one scene to the next.
   */
  public Scene scene() {
    El ring = focus.ring() ? focus.element() : null;
    int ringColor = focus.ringColor();
    scene = scene == null
        ? Scene.of(root, ring, ringColor, atlas, textRendering, scale)
        : scene.refill(root, ring, ringColor, scale);
    return scene.imageMemory(imageMemory);
  }

  /** The last frame drawn on the CPU, with its tree as text. */
  public Headless.Frame render() {
    return new Headless.Frame(
        CpuRenderer.render(scene(), Math.round(width * scale), Math.round(height * scale), scale),
        root.dump());
  }

  /** An element under a point, with the path to it from the root. */
  private record Target(El el, List<El> path) {
    /** The identity that finds the element again after a rebuild. */
    Object id() {
      return Animator.identities(path).getLast();
    }
  }

  /**
   * The element a press at this position goes to: the topmost element under the point, or its
   * nearest ancestor with a press or drag handler.
   */
  private Target target(float x, float y) {
    return target(x, y, App::takesPress);
  }

  static boolean takesPress(El el) {
    return el.onPress() != null || el.onDrag() != null;
  }

  /** The topmost element under the point, or its nearest ancestor that is {@code wanted}. */
  private Target target(float x, float y, Predicate<El> wanted) {
    List<El> hit = hit(x, y);
    for (int i = hit.size() - 1; i >= 0; i--) {
      if (wanted.test(hit.get(i))) {
        return new Target(hit.get(i), hit.subList(0, i + 1));
      }
    }
    return null;
  }

  /**
   * The elements under the point, from the root or from the root of the topmost overlay there
   * down to the topmost one; empty if none, and outside a modal overlay.
   */
  private List<El> hit(float x, float y) {
    return hit(root, x, y);
  }

  /** As {@link #hit(float, float)}, for the tree of {@code root} and its overlays. */
  static List<El> hit(El root, float x, float y) {
    if (root != null) {
      for (El overlay : root.shownOverlays().reversed()) {
        List<El> hit = hitFrom(overlay, x, y);
        if (!hit.isEmpty() || overlay.isModal() && !overlay.isExiting() && !overlay.isInert()) {
          return hit;
        }
      }
    }
    return hitFrom(root, x, y);
  }

  /** The elements under the point from {@code from} down, which is in window coordinates. */
  private static List<El> hitFrom(El from, float x, float y) {
    List<El> hit = new ArrayList<>();
    if (from == null || !contains(from, 0, 0, x, y)) {
      return hit;
    }
    El el = from;
    float left = from.x();
    float top = from.y();
    while (true) {
      hit.add(el);
      if (el.barAt(x, y) != null) {
        return hit; // a bar lies over the content
      }
      // shortcut: a child that sticks out of its parent is not hit outside the parent's box. That
      // is also what clipping needs, so keep it for elements that clip when this changes.
      El next = null;
      List<El> children = el.children();
      // Later children are drawn on top, so they are asked first.
      for (int i = children.size() - 1; i >= 0 && next == null; i--) {
        El child = children.get(i);
        if (el.isFieldPart(child) && !inFieldContent(el, left, top, x, y)) {
          continue;
        }
        if (contains(child, left, top, x, y)) {
          next = child;
        }
      }
      if (next == null) {
        return hit;
      }
      left += next.x();
      top += next.y();
      el = next;
    }
  }

  /**
   * The index in {@code hit} of the editable field that takes a press, or -1: the last element if
   * it takes edits; else inside a line block or inline hint, the field it is in if that field
   * takes edits and nothing in the block or hint takes presses, drags or pointer-down.
   */
  private static int fieldPressIndex(List<El> hit) {
    if (!hit.isEmpty() && hit.getLast().takesEdits()) {
      return hit.size() - 1;
    }
    for (int i = hit.size() - 2; i >= 0; i--) {
      El el = hit.get(i);
      if (el.isFieldBlock(hit.get(i + 1)) || el.isFieldHint(hit.get(i + 1))) {
        for (int j = i + 1; j < hit.size(); j++) {
          El part = hit.get(j);
          if (takesPress(part) || part.onPointerDown() != null) {
            return -1;
          }
        }
        return el.takesEdits() ? i : -1;
      }
      if (el.isFieldPart(hit.get(i + 1))) {
        return -1;
      }
    }
    return -1;
  }

  /** Whether {@code (x, y)} lies inside a multi-line field's content box (where its blocks and line-end elements clip). */
  private static boolean inFieldContent(El field, float left, float top, float x, float y) {
    float l = left + field.border(Side.LEFT) + field.padding(Side.LEFT);
    float t = top + field.border(Side.TOP) + field.padding(Side.TOP);
    float r = left + field.width() - field.border(Side.RIGHT) - field.padding(Side.RIGHT);
    float b = top + field.height() - field.border(Side.BOTTOM) - field.padding(Side.BOTTOM);
    return x >= l && x < r && y >= t && y < b;
  }

  /**
   * Whether the point lies inside the element's rounded rectangle and the element takes input:
   * neither invisible nor leaving nor {@link El#inert}.
   */
  private static boolean contains(El el, float parentX, float parentY, float x, float y) {
    if (!el.isPlaced() || el.opacity() == 0 || el.isExiting() || el.isInert()) {
      return false;
    }
    List<Text.Box> rows = el.rangeBoxes();
    if (rows != null) { // over a range of its parent's text: only its rows
      float rx = x - parentX - el.x();
      float ry = y - parentY - el.y();
      return rows.stream()
          .anyMatch(
              r -> rx >= r.x() && rx < r.x() + r.width() && ry >= r.y() && ry < r.y() + r.height());
    }
    float halfW = el.width() / 2;
    float halfH = el.height() / 2;
    float dx = x - (parentX + el.x() + halfW);
    float dy = y - (parentY + el.y() + halfH);
    // The corner on the point's side: top left, top right, bottom right, bottom left.
    float[] radii = el.radii();
    float radius =
        Math.clamp(radii[dx > 0 ? dy > 0 ? 2 : 1 : dy > 0 ? 3 : 0], 0, Math.min(halfW, halfH));
    float qx = Math.abs(dx) - halfW + radius;
    float qy = Math.abs(dy) - halfH + radius;
    if (qx <= 0 || qy <= 0) {
      return Math.max(qx, qy) <= radius;
    }
    return qx * qx + qy * qy <= radius * radius;
  }
}
