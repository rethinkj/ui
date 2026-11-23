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
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
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

  private boolean cycling; // whether the last frame showed an element with an El#cycle

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
  // The focused element took text when the last key was dispatched: its text goes in (keyText).
  private boolean keyToText;
  private boolean scrolled; // something scrolled since the rows and bars were placed
  private boolean caretOverlays; // an overlay is placed beside a caret: see El#atCaret
  private boolean rowsPending; // the tree was built between two inputs without its lists' rows
  // The next build between inputs builds the rows too: a field, maybe in one, waits for the app's
  // answer to an edit an app asked for, or told its selection, after which actions may look for it.
  private boolean rowsKept;
  // A list with measured row heights still wants rows after the passes of the last fillRows:
  // the next frames build them, and nextChange asks for those frames.
  private boolean rowsWanted;
  // The lines a frame may ask highlighters about, across its fields; a field left with plain lines
  // in view catches up in the next frames, which nextChange asks for.
  private final Lines.Budget highlightBudget = new Lines.Budget();
  private boolean rowKeyHooks; // the rows the lists built last have an El#onAnyKeyDown handler
  // The row each list was last asked to scroll to (scrollToRow), by identity, until the user's
  // next press, key or wheel: content that grows meanwhile takes the row to the top after all.
  private final Map<Object, Integer> rowGoals = new HashMap<>();
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
  // The state changed outside the app's handlers (invalidate): build the view before the next
  // input.
  private boolean changedOutside;
  // The rows each list with an onRowsBuilt handler told last, first and last in one long, by the
  // list's identity, for the lists the last fillRows found; a list that comes back tells again.
  private final Map<Object, Long> toldRows = new HashMap<>();
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
  private Clipboard clipboard = Clipboard.inMemory();
  private Dialogs dialogs = Dialogs.NONE;
  private Browser browser = Browser.NONE;
  private Object fileOver; // the identity of the element the system's drag is over, or null
  private Consumer<Drop> fileOverHandler; // its onFileDrop, which gets the LEAVE
  private Windows windows = Windows.NONE;
  private boolean ran; // a handler ran in this frame; see ranHandlers()
  private Editor blinking; // the editor of the focused field
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
      case Selecting(Object field) -> drag(field, x, y);
      case Grab(Object id, boolean vertical, float grip) -> {
        El area = animator.element(id);
        scrolled |= area != null && area.dragThumb(vertical, (vertical ? y : x) - grip);
        yield false;
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
        capture = new Grab(id, vertical, along - start);
      } else {
        capture = IDLE;
        int mark = vertical ? area.tickAt(y) : -1;
        if (mark >= 0) {
          area.scrollTo(area.scrollX(), area.centerOffset(mark));
          area.editor().center(mark);
          scrolled = true; // a frame centers it as measured, also where the estimate did not move
        } else {
          scrolled |= area.page(vertical, along < start ? -1 : 1);
        }
      }
      return changed;
    }
    int fieldIndex = fieldPressIndex(hit);
    if (fieldIndex >= 0) {
      El field = hit.get(fieldIndex);
      capture =
          new Selecting(Animator.identities(hit.subList(0, fieldIndex + 1)).getLast());
      focus.set(field, false, time);
      float[] at = local(field, x, y);
      Editor editor = field.editor();
      Text.CaretPos caret = field.text().nearestCaret(at[0], at[1]);
      editor.press(
          editor.fromShown(caret.index()),
          caret.stop(),
          editor.fromShown(field.text().hit(at[0], at[1])),
          clicks,
          (modifiers & Key.SHIFT) != 0);
      if (field.onPointerDown() != null) {
        double[] pos = content(field, x, y);
        field.onPointerDown().press(clicks, modifiers, pos[0], pos[1]);
        handled = true;
      }
      return true;
    }
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
      case Selecting(Object field) -> drag(field, x, y);
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
    if (!keepsFocus(hit(x, y))) {
      Target focus = target(x, y, El::isFocusable);
      this.focus.set(focus == null ? null : focus.el, false, time);
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
    return el.onSecondaryPress() != null
        || el.fieldMenu() != null
            && el.takesEdits()
            && animator.inRow(animator.identity(el)) == null;
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
    for (int i = 0; i + 1 < hit.size(); i++) {
      if (hit.get(i).isFieldPart(hit.get(i + 1))) {
        return true; // built after layout, nothing in a field part has an identity to focus
      }
    }
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

  /** A press selecting in the text field with this identity. */
  private record Selecting(Object id) implements Capture {}

  /**
   * A press on the thumb of a scroll area's bar: the area's identity, which bar, and how far from
   * the thumb's start the pointer holds it along the bar.
   */
  private record Grab(Object id, boolean vertical, float grip) implements Capture {}

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
    El el = animator.element(d.id);
    // A drag that carries a payload goes on without its element: what moves is the payload, as a
    // tab torn out of its bar.
    boolean left = el == null || el.isExiting();
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
        place.window() == null ? Float.NaN : place.y()));
    handled = true;
    return true;
  }

  /** Where a point of this window lies among the runner's windows. */
  private Windows.Place place(float px, float py, boolean outside) {
    Windows.Place place = windows.place(this, px, py);
    if (place == null) { // no runner: this window alone, at the origin of the screen
      place = new Windows.Place(px, py, null, outside ? Float.NaN : px, outside ? Float.NaN : py);
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
   * as in a browser, or without one the first that can in the overlay around the focused element,
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
      hover.keyDown(key);
      rowGoals.clear();
      if (edits(key)) {
        wakeField();
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
    List<El> path = focusPath();
    boolean called = false;
    for (El el : path.reversed()) {
      if (el.onKey() != null) {
        called = true;
        if (el.onKey().test(key)) {
          handled = true;
          return true;
        }
      }
      if (el == focus.element() && el.editor() != null && el.editor().key(key, clipboard)) {
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
        if (el.editor() != null && !el.text().multiline()) {
          el.editor().selectAll(); // as browsers do when Tab enters a field, but not a text area
        }
        leaveOverlays(from, animator.identity(el));
        return focus.set(el, true, time);
      }
    }
    if ((key.name().equals("PageUp") || key.name().equals("PageDown")) && key.modifiers() == 0) {
      int direction = key.name().equals("PageUp") ? -1 : 1;
      El area = null;
      // An overlay is a page of its own, as a dialog or a drawer: not its anchor's view.
      List<El> page = fromOverlay(path);
      for (El el : page.reversed()) { // the innermost area around the focus that can move so
        if (el.isScrollArea() && el.canScroll(false, direction)) {
          area = el;
          break;
        }
      }
      if (area == null) {
        El overlay = page != path ? page.getFirst() : modal();
        area = overlay == null ? null : pageArea(overlay, direction);
      }
      if (area != null) {
        scrolled |= area.page(true, direction);
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
        || cycling
        || rowsWanted
        || hasPendingHighlights()
        || bracketsDue()) {
      // handlers that a frame queued, such as El#onRowsBuilt, an El#cycle, rows that the
      // measured heights of a list show and the last frame had no passes left for, or
      // highlighting that hit its per-frame line budget
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
    if (blinking == null || !active) {
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
    enqueue(() -> {
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
        if (fallback) {
          focus.awaitInside(id);
        }
        return due;
      }
      return (id == null || now != null && order.contains(now)) && focus.set(now, true, time);
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
    if (focus.element() != null || !focus.waits()) {
      return false;
    }
    Editor editor = editors.get(focus.id());
    return editor != null && editor.editable();
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
      in.list().scrollTo(in.list().scrollX(), in.list().offsetShowing(in.row()));
      fillRows();
      refocus();
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
    Animator.InRow in = animator.inRow(animator.identity(next));
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
      scrolled |= list.scrollTo(list.scrollX(), list.offsetShowing(row));
      fillRows();
      if (!list.hasRow(row)) {
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
      if (fillRows()) {
        refocus(); // its row was built again
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
    List<El> full = new ArrayList<>();
    pathTo(root, el, full);
    List<El> path = fromOverlay(full); // the areas around an overlay stay where they are
    for (int i = path.size() - 2; i >= 0; i--) {
      El area = path.get(i);
      if (!area.isScrollArea()) {
        continue;
      }
      float areaX = 0;
      float areaY = 0;
      for (int j = 0; j <= i; j++) {
        areaX += path.get(j).x();
        areaY += path.get(j).y();
      }
      float x = areaX;
      float y = areaY;
      for (int j = i + 1; j < path.size(); j++) {
        x += path.get(j).x();
        y += path.get(j).y();
      }
      // With its ring as far as the area's padding has room for it, so that the clip cuts none
      // of it; rows that fill a list edge to edge come flush, their rings drawn inside.
      float ring = Scene.RING + el.focusRingGap();
      float left = Math.min(ring, area.padding(Side.LEFT));
      float right = Math.min(ring, area.padding(Side.RIGHT));
      float up = Math.min(ring, area.padding(Side.TOP));
      float down = Math.min(ring, area.padding(Side.BOTTOM));
      area.scrollTo(
          area.scrollX()
              + revealDelta(
                  x - left, el.width() + left + right, areaX, area.width() - area.coverX()),
          area.scrollY()
              + revealDelta(y - up, el.height() + up + down, areaY, area.height() - area.coverY()));
    }
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
        if (!el.isFieldPart(child)) { // built after layout, a field part has no identity to focus
          focusables(child, left, top, clipLeft, clipTop, clipRight, clipBottom, shown, found);
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
      scrolled |=
          scroll(areas, true, deltaX, false) | scroll(areas, false, deltaY, sideways); // not ||
      return false;
    });
  }

  /** @param sideways whether an area that scrolls only sideways takes a vertical delta sideways */
  private static boolean scroll(List<El> areas, boolean horizontal, float delta, boolean sideways) {
    for (El area : areas) {
      if (area.canScroll(horizontal, delta)) {
        return horizontal
            ? area.scrollTo(area.scrollX() + delta, area.scrollY())
            : area.scrollTo(area.scrollX(), area.scrollY() + delta);
      }
      if (sideways && scrollsSideways(area, delta)) {
        return area.scrollTo(area.scrollX() + delta, area.scrollY());
      }
    }
    return false;
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
        rowGoals.put(id, row);
      }
      return false;
    });
  }

  /** Takes each list that {@link #scrollToRow} sent to a row there again; whether one moved. */
  private boolean followRowGoals() {
    boolean moved = false;
    for (Iterator<Map.Entry<Object, Integer>> i = rowGoals.entrySet().iterator(); i.hasNext(); ) {
      Map.Entry<Object, Integer> goal = i.next();
      El el = animator.element(goal.getKey());
      if (el == null || !el.isVirtualList() || goal.getValue() > el.rowCount()) {
        i.remove(); // the list or the row is gone: one that comes back is not followed
      } else {
        moved |= el.scrollTo(el.scrollX(), el.rowOffset(goal.getValue()));
      }
    }
    return moved;
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
    if (keyPressed != null && keyPressKey == null && time >= keyPressUntil) {
      keyPressed = null; // the look of a key that is up ends; wanted() then differs from shown
    }
    ran = false;
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
      if (untold() || hasPendingUnfolds()) {
        // A selection a build applied or moved, also one of rows that scrolling filled: a
        // FieldState's, or the app's answer to an edit. It reaches onSelect as a handler's input
        // would, before the next input: what the handler asks for and changes comes first.
        scheduler.addFirst(() -> {
          boolean told = tellUnfolds() | tellSelections();
          rowsKept |= told;
          return told;
        });
      }
      scheduler.beginHandler();
      try {
        stale |= scheduler.poll().getAsBoolean();
        stale |= tellUnfolds() | tellSelections();
      } catch (RuntimeException | Error e) {
        handled = true; // it may have changed the state: the next input gets a tree built anew
        throw e;
      } finally {
        scheduler.endHandler();
      }
    }
    long inputEnd = System.nanoTime();
    long inputBuild = buildNanos;
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
      stale |=
          tellUnfolds() | tellSelections(); // of fields in those rows, which the rebuild then shows
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
          // A caret that the app's string moved, or of a new field: its handler is told, once.
          if (tellUnfolds() | tellSelections() | pendingBracketFolds()) {
            rebuild();
          }
          List<El> path = pointed();
          stale = !wanted(path, Animator.identities(path)).equals(shown);
        }
      } finally {
        scheduler.takeAsked();
      }
      stale = false;
      drawn = true;
    } else if (animator.isMoving()) {
      drawn = true;
      animator.present(time);
      if (fillRows()) { // a list that moves may show other rows
        refocus();
        laidOut = true;
      }
    }
    if (laidOut) {
      placeEditors();
    }
    if (root != null && hasPendingHighlights()) {
      drawn |= catchUpHighlights();
    }
    boolean on = active && blinking != null && Math.floor((time - blinkStart) / BLINK) % 2 == 0;
    if (on != caretOn) {
      caretOn = on;
      if (blinking != null) {
        blinking.place(true, on);
        El field = blinking.element();
        if (field.takePartsBuilt()) { // a block a look took was built anew
          gutterLooks(field, animator.identity(field));
          Overlays.place(root, width, height);
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
    tellInView(); // the rows as the frame shows them, after everything that places elements
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
      while (resizeRounds < RESIZE_ROUNDS && !resized.isEmpty()) {
        resizeRounds++;
        tellResized();
        build(false, true);
      }
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
    List<Object> ids = Animator.identities(pointed());
    long start = System.nanoTime();
    El built = WindowState.CURRENT.with(
        new WindowState(active, maximized, scale), () -> view.apply(state));
    buildNanos += System.nanoTime() - start;
    El.fix(built);
    shown = applyLooks(built, ids);
    root = built;
    builds++;
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
    animator.update(root, time);
    untimed = true;
    stale = false;
    ran |= handled;
    handled = false;
    changedOutside = false;
    scrolled = false;
    rowsPending =
        between && !rowsKept && (focus.id() == null || animator.inRow(focus.id()) == null);
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
    refocus();
    placeEditors();
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
    if (fillRows() || pending) {
      refocus();
      placeEditors();
    } else {
      placeEditors(); // a multi-line field that scrolled lays out the rows it shows now
    }
    El.placeBars(root);
    return true;
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
  private void attachEditors(El el, List<El> path) {
    path.add(el);
    if (el.isEditable()) {
      Object id = Animator.identities(path).getLast();
      FieldState state = el.fieldState();
      Editor editor = state == null ? editors.computeIfAbsent(id, i -> new Editor(el)) : null;
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
        Editor was = editors.put(id, editor);
        if (was != editor) { // back in the tree, maybe where another field was
          if (capture instanceof Selecting s && s.id().equals(id)) {
            capture = IDLE;
          }
          if (was != null) {
            if (was.element().fieldState() == null) {
              was.drop();
            } else {
              was.leave();
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
      el.editor(editor);
      el.text().unfocused(!id.equals(focus.id())); // El#ellipsis shortens it without the focus
    }
    for (El child : el.children()) {
      attachEditors(child, path);
    }
    for (El overlay : el.overlays()) {
      attachEditors(overlay, path);
    }
    path.removeLast();
  }

  /**
   * After a rebuild, or after a virtual list built rows: forgets the editors of fields that are
   * gone, follows the focus with the caret, and places the caret, selection and scroll of every
   * field for its new layout. The editor of a focused field whose row scrolled out of the rows
   * that exist stays, with the focus, for when the row comes back, and so does one whose
   * selection is pending ({@link El#isSelectionPending}) while its row is out, both of a list that
   * is not leaving; the editors of other fields in such rows go with them, as
   * everything else the app keeps of a row. An editor without a {@link FieldState} that is
   * forgotten here ends its pending selection ({@link Editor#drop}), as it cannot come back. A
   * field that fades out ({@link El#isExiting}) has lost its editor, and with it its caret,
   * selection and composition.
   */
  private void placeEditors() {
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
    blink(focus.element() == null ? null : focus.element().editor());
    for (Editor editor : shown) {
      editor.place(editor == blinking, caretOn);
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
          editor.place(editor == blinking, caretOn);
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
    boolean placeOverlays = caretOverlays;
    for (Map.Entry<Object, Editor> entry : editors.entrySet()) {
      El field = entry.getValue().element();
      if (kept.contains(entry.getValue()) && field.takePartsBuilt()) {
        gutterLooks(field, entry.getKey());
        placeOverlays = true; // those of the parts just built, which the tree's pass did not see
      }
    }
    // Placed beside carets, which are placed now, or of field parts just built.
    if (placeOverlays) {
      Overlays.place(root, width, height);
    }
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
      placeEditors();
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
   * Applies the looks of the moment to the parts a field has just built after layout (gutter
   * cells, line-end elements and blocks), as {@link #rowLooks} does to a list's rows: those under
   * the pointer take their hover look and their {@link El#whenHoveredFor} look once it is due,
   * such as a marker's tooltip, and the one held down its press look; those the old parts had go
   * with them. Lays out the overlays of the parts, also those the view attached.
   */
  private void gutterLooks(El field, Object fieldId) {
    boolean took = false;
    List<Object> hovered = new ArrayList<>(shown.hovered());
    List<Object> rested = new ArrayList<>(shown.rested());
    hovered.removeIf(id -> Animator.isBelow(id, fieldId));
    rested.removeIf(id -> Animator.isBelow(id, fieldId));
    Object press = shown.pressed();
    if (Animator.isBelow(press, fieldId) && animator.element(press) == null) {
      press = null; // a part's, built anew; a text range keeps the look the build gave it
    }
    for (Object id : pointedIds()) {
      if (Animator.isBelow(id, fieldId)) {
        El el = Animator.find(field, fieldId, id);
        // A part kept from an earlier pass keeps the looks it took there.
        if (el != null && el.hoverLook() != null) {
          hovered.add(id);
          took |= field.partLooks(el).add("whenHovered") && look(el, El::hoverLook, "whenHovered");
        }
        if (el != null && el.restLook() != null) {
          if (field.partLooks(el).contains("whenHoveredFor")) {
            rested.add(id);
          } else if (restLook(el, id)) {
            field.partLooks(el).add("whenHoveredFor");
            rested.add(id);
            took = true;
          }
        }
      }
    }
    Object pressId = pressedId();
    El pressed = Animator.isBelow(pressId, fieldId)
            && animator.element(pressId) == null // a text range of the field took it in the build
        ? Animator.find(field, fieldId, pressId)
        : null;
    if (pressed != null && pressed.pressLook() != null) {
      press = pressId;
      took |= field.partLooks(pressed).add("whenPressed")
          && look(pressed, El::pressLook, "whenPressed");
    }
    for (El part : field.children()) {
      if (took) {
        El.scaleText(
            part, scale, systemFonts, field.resolvedLineHeight); // the overlays the looks attached
      }
      if (took || field.isFieldPart(part)) { // a block's or line end's, which the view attached
        Overlays.layout(part, width, height);
      }
    }
    if (took) {
      field.dropBlockCache(); // a block keeps no look a later scroll of this tree would show
    }
    shown = new Looks(
        hovered,
        rested,
        shown.focused(),
        shown.focusRested(),
        shown.within(),
        press,
        shown.hoversRun());
  }

  /**
   * Tells the {@link El#onSelect} handlers of the fields whose caret or selection changed. An
   * editor kept while its field is not in the tree, as the focused one whose row {@link
   * Focus#waits}, is told once its field is back.
   *
   * @return whether a handler was told, which the next input should find in the view
   */
  private boolean tellSelections() {
    boolean told = false;
    for (Map.Entry<Object, Editor> entry : List.copyOf(editors.entrySet())) {
      told |= inTree(entry) && entry.getValue().tell();
    }
    handled |= told;
    return told;
  }

  /** Whether a field of the tree has a selection that {@link #tellSelections} would tell. */
  private boolean untold() {
    for (Map.Entry<Object, Editor> entry : editors.entrySet()) {
      if (inTree(entry) && entry.getValue().untold()) {
        return true;
      }
    }
    return false;
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
   * Selects from {@code anchor} to {@code caret} in a text field, or puts the caret there when they
   * are equal, and scrolls it into view as a key does, a multi-line field also while it does not
   * have the focus: a match that a find bar found while the focus is in the bar. The positions
   * are indexes into the string, moved to the grapheme boundary at or before them. A handler may
   * call this as {@link #edit}.
   */
  public void select(El field, int caret, int anchor) {
    Object id = fieldId(field, "select");
    enqueue(() -> {
      settle();
      El now = animator.element(id);
      if (now == null || now.editor() == null) {
        return false;
      }
      if (capture instanceof Selecting s && s.id().equals(id)) {
        capture = IDLE;
      }
      now.editor().select(caret, anchor);
      handled |= now.editor().unfoldPending();
      scrolled = true; // its jump is placed before the next input hit tests, as a scroll's is
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

  /** The pointer moved while a press selects in a field. */
  private boolean drag(Object field, float x, float y) {
    El el = animator.element(field);
    if (el == null || el.editor() == null || el.text() == null) {
      return false;
    }
    float[] at = local(el, x, y);
    Editor editor = el.editor();
    return editor.drag(
        editor.fromShown(el.text().nearest(at[0], at[1])),
        editor.fromShown(el.text().hit(at[0], at[1])));
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
    inViewLists.clear();
    boolean built = fillRows(root, new ArrayList<>(), 0, 0, -far, far, false);
    if (!rowGoals.isEmpty() && followRowGoals()) {
      inViewLists.clear();
      built |= fillRows(root, new ArrayList<>(), 0, 0, -far, far, false); // the rows there
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
        .removeIf(id -> !open.contains(id) && !(rowsPending && animator.inRow(id) != null));
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
      case Selecting s -> s.id();
      case Grab g -> g.id();
      case Idle _, ActiveDrag _ -> null;
    };
    if (gone(held, rowsBuilt)) {
      Dragging drag = startedDrag();
      capture = drag == null ? IDLE : new ActiveDrag(drag);
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
    El above = null;
    for (Object at : Animator.lineage(id)) {
      El el = animator.element(at);
      if (el == null) {
        return above == null || !above.hasFieldParts();
      }
      if (el.isExiting()) {
        return true;
      }
      above = el;
    }
    return false;
  }

  /** @param inRows whether {@code el} is in a row of a virtual list */
  private boolean fillRows(
      El el,
      List<El> path,
      float parentX,
      float parentY,
      float clipLeft,
      float clipRight,
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
    int[] range = el.wantedRows(left, top, clipLeft, clipRight, width, height, relook);
    boolean built = range.length != 0;
    if (el.onRowsBuilt() != null) {
      rowLists.add(Animator.identities(path).getLast());
    }
    if (el.onRowsInView() != null) {
      inViewLists.put(Animator.identities(path).getLast(), el);
    }
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
          ? el.wantedRows(left, top, clipLeft, clipRight, width, height)
          : El.NO_ROWS;
    }
    rowsWanted |= range.length != 0; // beyond the passes of one frame: the next frame goes on
    if (el.style().overflowX != Style.Overflow.VISIBLE) {
      clipLeft = Math.max(clipLeft, left);
      clipRight = Math.min(clipRight, left + el.width());
    }
    for (El child : el.children()) {
      built |= fillRows(child, path, left, top, clipLeft, clipRight, inRows || el.isVirtualList());
    }
    float far = Float.POSITIVE_INFINITY;
    for (El overlay : el.overlays()) { // in window coordinates
      built |= fillRows(overlay, path, 0, 0, -far, far, inRows);
    }
    path.removeLast();
    return built;
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

  /** Queues the {@link El#onRowsBuilt} handler of a list that built other rows than it told. */
  private void tellRows(El list, Object id, int first, int last) {
    El.RowsHandler handler = list.onRowsBuilt();
    if (handler == null || last < first) {
      return;
    }
    long rows = (long) first << 32 | last & 0xFFFFFFFFL;
    Long before = toldRows.put(id, rows);
    if (before == null || before != rows) {
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
      refocus();
      placeEditors();
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
    if (press == null && pressId != null) { // a field's part, built after layout: below its field
      Object field = null;
      for (Object at : Animator.lineage(pressId)) {
        if (animator.element(at) == null) {
          break;
        }
        field = at;
      }
      press = field == null ? null : Animator.find(animator.element(field), field, pressId);
    }
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
    El el = held == null ? null : animator.element(held);
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
      case Selecting s -> s.id();
      case Grab g -> g.id();
      case Press p -> p.drag() != null && p.drag().started ? p.drag().id : null;
      case ActiveDrag a -> a.drag().id;
      case Idle _ -> null;
    };
  }

  /**
   * Whether something had not reached its target in the last frame, or the frame showed an
   * element with an {@link El#cycle}, so that the next frame draws something new after {@link
   * #advance} even without input. Once this is false the app is idle.
   */
  public boolean isMoving() {
    return animator.isMoving() || cycling;
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
   * The index in {@code hit} of the editable field that takes a press, or -1: inside a line block,
   * the field the block is in if it takes edits and nothing in the block takes presses, drags or
   * pointer-down (also not a text field: a block takes no text input); else the last element if it
   * takes edits.
   */
  private static int fieldPressIndex(List<El> hit) {
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
        return -1; // nothing in a line-end element takes text input
      }
    }
    return !hit.isEmpty() && hit.getLast().takesEdits() ? hit.size() - 1 : -1;
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
