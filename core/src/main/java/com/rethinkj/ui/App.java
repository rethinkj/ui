package com.rethinkj.ui;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.render.CpuRenderer;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.text.Atlas;
import com.rethinkj.ui.text.TextRendering;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
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
 * those of presses, secondary presses (also from the Menu key), drags, keys, text and edits, and
 * the {@link El#onDismiss} handlers that a press or the wheel outside an overlay calls; all of
 * them run in the order of the input, each in the tree the one before left. The rows of the
 * virtual lists in such a tree, those inside overlays too, and its scrollbars are built before an
 * input that needs them: a press, a release, a secondary press, Tab, a focus or a scroll the app
 * asks for, and a key while the focus is in a row; the wheel and drags build them too, but not
 * the rows that their own scrolling brings into view. Other keys do with the rows that exist, so
 * a key handler there sees its list without rows. Input that no handler of the app took costs no
 * rebuild: a pointer move, a key that nobody handles or that only moves a field's caret, a focus
 * moved by Tab, a wheel that dismisses nothing. A key handler that returns false counts as having
 * changed nothing. The {@link El#onHoverFor} handlers are not input: the frame runs them after
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
 * handler takes them ({@link El#onKey}); text goes to it alone ({@link El#onText}). When the
 * keyboard or the app moved the focus, the scene draws a ring around the focused element, and
 * scroll areas around it scroll it into view.
 *
 * <p>The app also follows the pointer ({@link #pointerMove}, {@link #pointerLeave}): it knows the
 * element under it ({@link #hovered}), the one a press is on ({@link #pressed}) and the {@link
 * #cursor} they ask for. Elements change how they look in these states with {@link
 * El#whenHovered}, {@link El#whenFocused} and {@link El#whenPressed}: the view says how, and the
 * app applies the looks of the moment to each tree the view builds, before layout. A state that
 * changes for elements without such a look rebuilds nothing. A press that moves on an element
 * with {@link El#onDrag} becomes a drag, during which that element counts as the one under the
 * pointer; so does a text field while a press in it selects. A press that lands in a field selects
 * text and never drags an element around it.
 *
 * <p>A scroll area with {@link El#scrollbars} gets its bars from the app, which places them after
 * each frame from the area's size, content and offset, over the content. The bars come first for
 * the pointer: a press on one is the bar's, never a press, drag or selection of what lies under it
 * or around it, and it leaves the focus alone. Dragging a thumb scrolls from the first pixel, with
 * no threshold, and the area counts as the one under the pointer meanwhile; a press on the track
 * beside the thumb scrolls a page. The thumb under the pointer and the dragged one change colour,
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
 * virtual thread and posts its result or failure, unless it was cancelled; {@link #await} waits
 * for that work, for scripts and tests.
 *
 * @param <S> the type of the state
 */
public final class App<S> {

  /** The colour of the focus ring until {@link #focusRing} changes it, as 0xAARRGGBB. */
  public static final int FOCUS_RING = 0xFF93C5FD;

  /** How long the caret of a focused field is on, and then off, in seconds of the app's clock. */
  public static final double BLINK = 0.5;

  /**
   * Where the focused element that takes text is, in logical pixels of the window, for the
   * system's input method: it places its candidate window below this box, and its composition at
   * the caret.
   *
   * @param cursor the caret's distance from {@code x}
   * @param password whether the element is a {@link El#masked masked} field, whose text the system
   *     should neither show nor remember
   */
  public record InputArea(
      float x, float y, float width, float height, float cursor, boolean password) {}

  private final S state;
  private final Function<S, El> view;
  // Input in the order it came, dispatched by frame(). Each returns whether it may have changed
  // something that the next rebuild shows.
  private final ArrayDeque<BooleanSupplier> queued = new ArrayDeque<>();
  // What a handler queues while an input runs, such as a focus or a scroll: it runs next.
  private final List<BooleanSupplier> follow = new ArrayList<>();
  // What post() took on any thread, until the app's thread moves it into queued.
  private final ConcurrentLinkedQueue<BooleanSupplier> posted = new ConcurrentLinkedQueue<>();
  private final AtomicBoolean woken = new AtomicBoolean(); // wake ran for what posted holds
  private volatile Runnable wake;
  boolean holdPosts; // for Script: posted tasks join the queue only at await()
  private final Set<Work> running = new LinkedHashSet<>(); // of background(); guarded by itself
  private boolean dispatching;
  private boolean handled; // a handler of the app took input since the tree was built
  private boolean scrolled; // something scrolled since the rows and bars were placed
  private boolean rowsPending; // the tree was built between two inputs without its lists' rows
  private final Animator animator = new Animator();
  private double time;
  private float width;
  private float height;
  private float scale = 1;
  private boolean stale = true;
  private El root;
  private Object pressed; // the identity of the element a press is on, until the pointer is up
  private int pressClicks; // the presses in quick succession that the one on pressed completes
  private int pressModifiers; // the modifiers held when it went down
  private Dragging dragging; // a pointer down on an element with a drag handler, until it is up
  private Grab grab; // a press on the thumb of a scrollbar, until the pointer is up
  private El barArea; // the scroll area whose bar is under the pointer or dragged, in this tree
  private Hot hot; // the bar that the last frame shows hovered or dragged
  private boolean pointerIn;
  private float pointerX;
  private float pointerY;
  private List<El> under = List.of(); // the elements under the pointer in the current tree
  private El marked; // the one of them a press would go to, marked for the dump
  private Looks shown = new Looks(List.of(), List.of(), null, null, 0); // of the current tree
  private El focused; // in the current tree
  private Object focusId; // its identity, to find it in the next tree
  private boolean ring; // the keyboard or the app moved the focus: draw the ring
  private boolean reveal; // scroll the focused element into view after the next rebuild
  // The identity of the element that last had the focus in a virtual list, by the list's identity.
  // shortcut: lists that are gone stay in it, one small entry each; drop them in update() when
  // apps create lists by the thousand.
  private final Map<Object, Object> remembered = new HashMap<>();
  // The rows each list with an onRowsBuilt handler told last, first and last in one long, by the
  // list's identity. shortcut: lists that are gone stay in it, as in remembered.
  private final Map<Object, Long> toldRows = new HashMap<>();
  private int ringColour = FOCUS_RING;
  private final Atlas atlas = new Atlas();
  private TextRendering textRendering = TextRendering.platform();
  private boolean systemFonts;
  private final Map<Object, Editor> editors = new HashMap<>(); // the fields' state, by identity
  private Clipboard clipboard = Clipboard.inMemory();
  private Object selecting; // the identity of the field a press is selecting in, until pointer up
  private Editor blinking; // the editor of the focused field
  private double blinkStart; // when its caret last turned on
  private boolean caretOn;
  private boolean active = true; // the window has the system's keyboard focus
  private Object secondary; // the identity of the element a secondary press is on, until it is up
  // The overlays of the last tree the view built, in the order they opened, with where the focus
  // was before each took it; null for one that took none.
  private final Map<Object, Focus> opened = new LinkedHashMap<>();
  // Since when the elements under the pointer with whenHoveredFor or onHoverFor have been there.
  private final Map<Object, Rest> resting = new HashMap<>();
  private double restDue = Double.POSITIVE_INFINITY; // when the next of their looks or handlers is
  private int hoversRun; // onHoverFor handlers run so far

  /** Where the focus was, by identity, and whether it had a ring. */
  private record Focus(Object id, boolean ring) {}

  /** Since when the pointer has been over an element; whether its onHoverFor handler has run. */
  private static final class Rest {
    double since;
    boolean ran;

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
    enqueue(() -> move(x, y));
  }

  private boolean move(float x, float y) {
    pointerIn = true; // the frame looks at what is under the pointer anyway
    pointerX = x;
    pointerY = y;
    if (rowsPending && (selecting != null || grab != null || dragging != null)) {
      settle(); // the held element may be in a row; a scroll alone moved the rows that exist
    }
    if (selecting != null) {
      return drag(selecting, x, y);
    }
    Grab g = grab; // never at the same time as the others
    if (g != null) {
      El area = animator.element(g.id());
      scrolled |= area != null && area.dragThumb(g.vertical(), (g.vertical() ? y : x) - g.grip());
      return false;
    }
    Dragging d = dragging; // never at the same time as selecting: a press starts one or neither
    if (d != null) {
      float dx = x - d.x;
      float dy = y - d.y;
      if (d.started) {
        return step(d, Drag.Phase.MOVE, dx, dy);
      } else if (Math.max(Math.abs(dx), Math.abs(dy)) > Drag.THRESHOLD) {
        d.started = true;
        return step(d, Drag.Phase.START, dx, dy);
      }
    }
    return false;
  }

  /** The pointer left the window: from the next {@link #frame} on, nothing is hovered. */
  public void pointerLeave() {
    enqueue(() -> {
      pointerIn = false;
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
    enqueue(() -> down(x, y, clicks, modifiers));
  }

  private boolean down(float x, float y, int clicks, int modifiers) {
    boolean changed = false;
    if (dragging != null && dragging.started) { // the last release got lost
      changed = step(dragging, Drag.Phase.CANCEL, dragging.dx, dragging.dy);
    }
    dragging = null;
    selecting = null; // likewise: a selection whose release got lost ends where it is
    grab = null; // and a thumb's drag
    settle();
    restAfterPress();
    if (dismiss(x, y, true)) {
      pressed = null;
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
      pressed = null;
      if (onThumb(bar, vertical, x, y)) {
        grab = new Grab(id, vertical, along - start);
      } else {
        scrolled |= area.page(vertical, along < start ? -1 : 1);
      }
      return changed;
    }
    if (!hit.isEmpty() && hit.getLast().takesEdits()) {
      El field = hit.getLast(); // a leaf, so nothing inside it takes the press
      pressed = null;
      selecting = Animator.identities(hit).getLast();
      focus(field, false);
      float[] at = local(field, x, y);
      Editor editor = field.editor();
      editor.press(editor.fromShown(field.text().nearest(at[0], at[1])),
          editor.fromShown(field.text().hit(at[0], at[1])), clicks, (modifiers & Key.SHIFT) != 0);
      return true;
    }
    Target target = target(x, y);
    pressed = target == null ? null : target.id();
    pressClicks = clicks;
    pressModifiers = modifiers;
    dragging = target == null || target.el.onDrag() == null
        ? null
        : new Dragging(target.id(), target.el.onDrag(), x, y);
    pressFocus(x, y);
    Target down = target(x, y, e -> e.onPointerDown() != null);
    if (down != null) {
      down.el.onPointerDown().press(clicks, modifiers);
      handled = changed = true;
    }
    return changed;
  }

  /**
   * The button was released. A press counts when it began on the same element, which the app
   * recognises after rebuilds by the identity that matches elements across them, and did not
   * become a drag; a drag ends here, and so does a thumb's drag, which presses nothing.
   */
  public void pointerUp(float x, float y) {
    enqueue(() -> up(x, y));
  }

  private boolean up(float x, float y) {
    settle();
    if (grab != null) {
      grab = null;
      return false;
    }
    if (selecting != null) {
      Object field = selecting;
      selecting = null;
      return drag(field, x, y);
    }
    Dragging d = dragging;
    dragging = null;
    boolean changed = false;
    if (d != null && d.started) {
      changed = step(d, Drag.Phase.END, x - d.x, y - d.y);
    } else if (pressed != null) {
      Target target = target(x, y);
      if (target != null && target.id().equals(pressed) && target.el.onPress() != null) {
        target.el.onPress().press(pressClicks, pressModifiers);
        handled = changed = true;
      }
    }
    pressed = null;
    return changed;
  }

  /**
   * The secondary button, usually the right one, went down at this position, in logical pixels.
   * Like a press of the primary button, it dismisses the overlays it lands outside of, and then
   * gives the focus to the focusable element there, or clears it. It neither drags nor selects.
   */
  public void secondaryDown(float x, float y) {
    enqueue(() -> secondaryDownAt(x, y));
  }

  private boolean secondaryDownAt(float x, float y) {
    secondary = null;
    settle();
    restAfterPress();
    if (dismiss(x, y, true)) {
      return false;
    }
    Target target = target(x, y, el -> el.onSecondaryPress() != null);
    secondary = target == null ? null : target.id();
    pressFocus(x, y);
    return false;
  }

  /**
   * A press gives the focus to the focusable element it lands in, or clears it, unless an element
   * that {@link El#keepFocusOnPress keeps the focus} comes first.
   */
  private void pressFocus(float x, float y) {
    Target focus = target(x, y, el -> el.isFocusable() || el.keepsFocusOnPress());
    if (focus == null || !focus.el.keepsFocusOnPress()) {
      focus(focus == null ? null : focus.el, false);
    }
  }

  /**
   * The secondary button was released here. When it went down on the same element, the {@link
   * #frame} that dispatches this runs that element's {@link El#onSecondaryPress} handler, or its
   * nearest ancestor's, with this position.
   */
  public void secondaryUp(float x, float y) {
    enqueue(() -> {
      settle();
      Target target = target(x, y, el -> el.onSecondaryPress() != null);
      boolean ran = target != null && target.id().equals(secondary);
      secondary = null;
      if (ran) {
        target.el.onSecondaryPress().accept(x, y);
        handled = true;
      }
      return ran;
    });
  }

  /** A press keeps the looks and handlers of {@link El#whenHoveredFor} away until a new stay. */
  private void restAfterPress() {
    for (Rest rest : resting.values()) {
      rest.since = Double.POSITIVE_INFINITY;
    }
  }

  /**
   * For a press or the wheel at this point: runs the {@link El#onDismiss} handlers of the
   * overlays above the first one it lands in, the topmost first, all in the tree of the moment.
   * A modal overlay ends the search; the wheel calls no modal overlay's handler.
   *
   * @return whether the input goes no further: it landed outside a modal overlay, or a press
   *     landed outside all overlays and called a handler
   */
  private boolean dismiss(float x, float y, boolean press) {
    if (root == null) {
      return false;
    }
    boolean dismissed = false;
    for (El overlay : root.shownOverlays().reversed()) {
      if (overlay.isExiting()) {
        continue;
      }
      if (!hitFrom(overlay, x, y).isEmpty()) {
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
    return press && dismissed;
  }

  /** The topmost modal overlay that is open and not leaving, or null. */
  private El modal() {
    if (root != null) {
      for (El overlay : root.shownOverlays().reversed()) {
        if (overlay.isModal() && !overlay.isExiting()) {
          return overlay;
        }
      }
    }
    return null;
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
   * A press on the thumb of a scroll area's bar: the area's identity, which bar, and how far from
   * the thumb's start the pointer holds it along the bar.
   */
  private record Grab(Object id, boolean vertical, float grip) {}

  /** A bar that shows another colour than at rest. */
  private record Hot(El area, boolean vertical, int colour) {}

  /** Whether the point lies across the strip of a bar at the place of its thumb. */
  private static boolean onThumb(Scrollbars.Bar bar, boolean vertical, float x, float y) {
    float along = vertical ? y : x;
    float start = vertical ? bar.thumbY() : bar.thumbX();
    return along >= start && along <= start + (vertical ? bar.thumbHeight() : bar.thumbWidth());
  }

  /** A pointer down on an element with a drag handler, which becomes a drag once it moves. */
  private static final class Dragging {
    final Object id;
    final Consumer<Drag> handler; // the element's when the pointer went down
    final float x;
    final float y;
    boolean started; // moved beyond the threshold
    boolean over; // ended or cancelled, in the frames that ran its steps
    float dx; // the distance of the last step
    float dy;

    Dragging(Object id, Consumer<Drag> handler, float x, float y) {
      this.id = id;
      this.handler = handler;
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
    if (d.over) {
      return false;
    }
    El el = animator.element(d.id);
    boolean gone = el == null || el.isExiting();
    d.over = gone || phase == Drag.Phase.END || phase == Drag.Phase.CANCEL;
    double[] at = gone ? new double[] {Double.NaN, Double.NaN} : content(el, d.x + dx, d.y + dy);
    d.handler.accept(new Drag(gone ? Drag.Phase.CANCEL : phase, dx, dy, at[0], at[1]));
    handled = true;
    return true;
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
   * previous one, around at the ends. Unlike in a browser, a press handler on the focused element
   * takes Enter and Space before its ancestors' key handlers see them. The Menu key and Shift+F10
   * run the nearest {@link El#onSecondaryPress} handler at the focused element's bottom left.
   *
   * <p>In an overlay, keys go up from the focused element to the overlay's root and on to the
   * element it is attached to and that element's ancestors; in a {@link El#modal} one they end at
   * its root, which also gets them while nothing has the focus.
   */
  public void keyDown(Key key) {
    Objects.requireNonNull(key, "key");
    enqueue(() -> {
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
        if (el == focused && el.editor() != null && el.editor().key(key, clipboard)) {
          handled |= el.editor().answerPending(); // it handed an edit to the app
          return true;
        }
        if (el == focused
            && el.onPress() != null
            && key.modifiers() == 0
            && (key.name().equals("Enter") || key.name().equals("Space"))) {
          if (!key.repeat()) {
            el.onPress().press(1, 0);
            handled = true;
          }
          return true;
        }
      }
      if (key.name().equals("ContextMenu") && key.modifiers() == 0
          || key.name().equals("F10") && key.modifiers() == Key.SHIFT) {
        for (El el : path.reversed()) {
          if (el.onSecondaryPress() != null) {
            float x = 0;
            float y = 0;
            for (El on : fromOverlay(path)) {
              x += on.x();
              y += on.y();
            }
            el.onSecondaryPress().accept(x, y + path.getLast().height());
            handled = true;
            return true;
          }
        }
      }
      if (key.name().equals("Tab") && (key.modifiers() & ~Key.SHIFT) == 0) {
        settle();
        wake();
        List<El> order = focusables();
        if (!order.isEmpty()) {
          int step = key.modifiers() == Key.SHIFT ? -1 : 1;
          int at = order.indexOf(focused);
          int next = at >= 0 ? at + step : step > 0 ? 0 : -1;
          El el = enter(order.get(Math.floorMod(next, order.size())), step);
          if (el.editor() != null && !el.text().multiline()) {
            el.editor().selectAll(); // as browsers do when Tab enters a field, but not a text area
          }
          return focus(el, true);
        }
      }
      return called;
    });
  }

  /**
   * A key was released. The next {@link #frame} gives it to the {@link El#onKeyUp} handlers, in
   * the order of {@link #keyDown}; it does nothing else.
   */
  public void keyUp(Key key) {
    Objects.requireNonNull(key, "key");
    enqueue(() -> {
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
   * El#onText} handler of the focused element; without either it is dropped.
   */
  public void textInput(String text) {
    Objects.requireNonNull(text, "text");
    enqueue(() -> {
      if (focused != null && focused.editor() != null) {
        boolean changed = focused.editor().type(text);
        handled |= focused.editor().answerPending();
        return changed;
      }
      if (focused == null || focused.onText() == null) {
        return false;
      }
      focused.onText().accept(text);
      handled = true;
      return true;
    });
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
    enqueue(() -> focused != null && focused.editor() != null
        && focused.editor().compose(text, cursor));
  }

  /** Where cut and copied text go and paste takes it from; {@link Clipboard#inMemory} at first. */
  public Clipboard clipboard() {
    return clipboard;
  }

  /** Sets the clipboard, as a window does with the system's. */
  public void clipboard(Clipboard clipboard) {
    this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
  }

  /**
   * Where the focused element of the last frame that takes text is, for the system's input method:
   * an editable text field, or an element with an {@link El#onText} handler; null when there is
   * none, and then a window turns text input off.
   */
  public InputArea inputArea() {
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
    boolean password = editor != null && focused.text().masked();
    if (editor != null && focused.text().multiline()) { // the caret's row, inside the field
      Text t = focused.text();
      float row = t.lineHeight() / t.scale();
      float top = Math.clamp(contentTop(focused) + editor.caretTopOffset(), 0,
          Math.max(focused.height() - row, 0));
      return new InputArea(x, y + top, focused.width(), row, cursor, false);
    }
    return new InputArea(x, y, focused.width(), focused.height(), cursor, password);
  }

  /**
   * The time on the app's clock at which the last frame changes without input or movement: when
   * the caret of the focused field turns on or off, or when the pointer will have rested long
   * enough on an element for its {@link El#whenHoveredFor} look or {@link El#onHoverFor} handler.
   * Positive infinity when nothing will; then an idle window can wait for input alone.
   */
  public double nextChange() {
    if (!queued.isEmpty()) {
      return time; // handlers that a frame queued, such as El#onRowsBuilt
    }
    if (blinking == null || !active) {
      return restDue;
    }
    return Math.min(restDue, blinkStart + (Math.floor((time - blinkStart) / BLINK) + 1) * BLINK);
  }

  /**
   * The element of the last frame's {@link #tree} that has the keyboard focus, or null. Also null
   * while the focus is on a row of a virtual list that has scrolled out of the rows that exist:
   * the app keeps it, and the row is focused again when it comes back.
   */
  public El focused() {
    return focused;
  }

  /**
   * The topmost element of the last frame's {@link #tree} under the pointer that takes input, the
   * one a press there begins at; null when the pointer is outside or over nothing. Clips, opacity 0
   * and elements that are leaving count as for presses. During a {@link El#onDrag drag} it is the
   * dragged element, wherever the pointer is.
   */
  public El hovered() {
    return under.isEmpty() ? null : under.getLast();
  }

  /**
   * The element of the last frame's {@link #tree} that a press is on, between the pointer down
   * that went to it and the next pointer up, or null: an element with a press or drag handler, or
   * a text field that the press selects in.
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
    if (barArea != null) {
      return Cursor.DEFAULT;
    }
    for (El el : under.reversed()) {
      if (el.cursor() != null) {
        return el.cursor();
      }
    }
    return Cursor.DEFAULT;
  }

  /**
   * Gives the keyboard focus to an element of the last frame's {@link #tree} as Tab would, with a
   * ring and scrolled into view, or clears it with null. The next {@link #frame} shows it; a
   * handler may call this too, with an element of the tree it runs in, and the frame focuses right
   * after the handler, the element with the same identity in the tree built from what the handler
   * changed, if it is still there and can take the focus. The app keeps the focus on the element
   * of later trees that has the same identity, and clears it once there is none, or when that
   * element is no longer focusable or takes no input.
   *
   * @throws IllegalArgumentException if the element is not {@link El#focusable}, not in the last
   *     frame's tree, or takes no input there: it is leaving, the view made it or an ancestor
   *     invisible with opacity 0, or it is clipped away. An element that fades in can take the
   *     focus from the frame that adds it on.
   */
  public void focus(El el) {
    if (el != null && !focusables().contains(el)) {
      throw new IllegalArgumentException(
          "This element cannot take the focus: it is not focusable(), not in the last frame's tree"
              + " or takes no input there");
    }
    Object id = el == null ? null : animator.identity(el);
    enqueue(() -> {
      settle();
      El now = id == null ? null : animator.element(id);
      return (id == null || now != null && focusables().contains(now)) && focus(now, true);
    });
  }

  /**
   * Sets the colour of the focus ring, as 0xAARRGGBB, for elements that set none themselves ({@link
   * El#focusRing(int)}); 0 draws none. The ring is 2 pixels wide and lies just outside the
   * element's border box, with its corners.
   */
  public void focusRing(int argb) {
    ringColour = argb;
    stale = true;
  }

  /**
   * The window became active, with the system's keyboard focus, or inactive, as a window reports
   * it; an app is active until this says otherwise. From the next {@link #frame} on, the focused
   * text field of an inactive app shows no caret and {@link #nextChange} is infinite, so that an
   * idle inactive window draws nothing. The focus, the selection and the ring stay as they are.
   * When the app is active again, the caret is on and blinks from there.
   */
  public void windowActive(boolean active) {
    enqueue(() -> {
      this.active = active;
      blinkStart = time;
      return false;
    });
  }

  /**
   * Sets how text is rasterised and blended, {@link TextRendering#platform} until this is called.
   * Tests pin it, so that their images are the same on every platform.
   */
  public void textRendering(TextRendering rendering) {
    textRendering = Objects.requireNonNull(rendering, "rendering");
    stale = true;
  }

  /**
   * Sets whether characters that a text's font and its fallbacks lack come from the fonts installed
   * on the system ({@link com.rethinkj.ui.text.Font#withSystemFonts}). Off until this is called,
   * so that scripts and tests draw the same on every machine; a {@link
   * com.rethinkj.ui.window.WindowLoop} turns it on when it starts, unless told otherwise.
   */
  public void systemFonts(boolean on) {
    if (on != systemFonts) {
      systemFonts = on;
      stale = true;
    }
  }

  /** Whether text falls back to the system's fonts; see {@link #systemFonts(boolean)}. */
  public boolean systemFonts() {
    return systemFonts;
  }

  /**
   * Focuses the element of the current tree that {@link #focusables} has, or none.
   *
   * @param ring whether the keyboard or the app moved the focus, not the pointer
   * @return whether a rebuild is due even if the focus ends where it was: to scroll it into view
   */
  private boolean focus(El el, boolean ring) {
    if (focused != null) {
      focused.focused(false);
    }
    focused = el;
    focusId = el == null ? null : animator.identity(el);
    this.ring = ring && el != null;
    reveal = this.ring;
    if (el != null) {
      el.focused(true);
      Animator.InRow in = animator.inRow(focusId);
      if (in != null) {
        remembered.put(in.listId(), focusId);
      }
    }
    return ring;
  }

  /**
   * Whether the focus is on a row of a virtual list that the list could build but does not have
   * now, because it is out of the range that exists, or on an element inside such a row.
   */
  private boolean focusWaits() {
    Animator.InRow in = focusId == null ? null : animator.inRow(focusId);
    return in != null && in.row() < in.list().rowCount() && !in.list().hasRow(in.row());
  }

  /** Brings the row of a focus that {@link #focusWaits} into view, so that Tab goes on there. */
  private void wake() {
    if (focused == null && focusWaits()) {
      Animator.InRow in = animator.inRow(focusId);
      in.list().scrollTo(in.list().scrollX(), in.list().offsetShowing(in.row()));
      fillRows();
      refocus();
    }
  }

  /**
   * Where Tab goes instead of {@code next} when that is in a virtual list and the focus is not: to
   * the element that last had the focus in the list, else to the first focusable element of the
   * first row in view, or the last one of the last row in view for Shift+Tab, rather than to the
   * first row the list built. The row is built and scrolled into view if needed.
   */
  private El enter(El next, int step) {
    Animator.InRow in = animator.inRow(animator.identity(next));
    if (in == null || Animator.isBelow(focusId, in.listId())) {
      return next;
    }
    El list = in.list();
    Object last = remembered.get(in.listId());
    Animator.InRow was = last == null ? null : animator.inRow(last);
    int row = was != null && was.listId().equals(in.listId()) && was.row() < list.rowCount()
        ? was.row()
        : list.rowInView(step < 0);
    if (!list.hasRow(row)) {
      list.scrollTo(list.scrollX(), list.offsetShowing(row));
      fillRows();
      if (!list.hasRow(row)) {
        return next;
      }
    }
    Set<El> inside = Collections.newSetFromMap(new IdentityHashMap<>());
    collect(list.children().get(row - list.firstRow()), inside);
    List<El> order = focusables();
    El again = last == null ? null : animator.element(last);
    if (again != null && inside.contains(again) && order.contains(again)) {
      return again;
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
   * Finds the focused element in a new tree by its identity, or clears the focus when it is gone or
   * can no longer take it, and scrolls it into view if that is due.
   */
  private void refocus() {
    if (focusId == null) {
      return;
    }
    if (focused != null) {
      focused.focused(false);
    }
    El el = animator.element(focusId);
    if (el == null && focusWaits()) {
      focused = null; // kept by its identity until the row exists again
      reveal = false;
      return;
    }
    if (el == null || !focusables().contains(el)) {
      focused = null;
      focusId = null;
      ring = false;
      reveal = false;
      return;
    }
    focused = el;
    el.focused(true);
    if (reveal) {
      reveal = false;
      reveal(el);
      if (fillRows()) {
        refocus(); // its row was built again
      }
    }
  }

  /**
   * The focused element and its ancestors, from the root; only the root without focus. While the
   * focus is on a row out of range, the path ends at the row's list.
   */
  private List<El> focusPath() {
    List<El> path = new ArrayList<>();
    El end = focused != null ? focused : focusWaits() ? animator.inRow(focusId).list() : null;
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
   * border box whole inside theirs, or its start if it is larger.
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
      area.scrollTo(
          area.scrollX() + nearest(x, el.width(), areaX, area.width()),
          area.scrollY() + nearest(y, el.height(), areaY, area.height()));
    }
  }

  /** The least distance that moves a span into a view, or its start onto the view's start. */
  private static float nearest(float start, float size, float viewStart, float viewSize) {
    float before = start - viewStart;
    float after = start + size - (viewStart + viewSize);
    return before < 0 ? before : after > 0 ? Math.min(after, before) : 0;
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
      focusables(modal != null ? modal : root, 0, 0, -far, -far, far, far, root.shownOverlays(),
          found);
    }
    return found;
  }

  /** The overlays shown come right after their anchor and what is inside it. */
  private static void focusables(
      El el, float parentX, float parentY, float clipLeft, float clipTop, float clipRight,
      float clipBottom, List<El> shown, List<El> found) {
    if (!el.isPlaced() || el.targetOpacity() == 0 || el.isExiting()) {
      return;
    }
    float left = parentX + el.x();
    float top = parentY + el.y();
    float right = left + el.width();
    float bottom = top + el.height();
    if (el.isFocusable()
        && left < clipRight && right > clipLeft && top < clipBottom && bottom > clipTop) {
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
        focusables(child, left, top, clipLeft, clipTop, clipRight, clipBottom, shown, found);
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
    Target target = target(x, y, El::isFocusable);
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
   * tree; outside a {@link El#modal} one it does nothing.
   */
  public void wheel(float x, float y, float deltaX, float deltaY) {
    if (!Float.isFinite(deltaX) || !Float.isFinite(deltaY)) {
      throw new IllegalArgumentException("wheel deltas must be finite, were " + deltaX + ", "
          + deltaY);
    }
    enqueue(() -> {
      if (rowsPending) {
        settle(); // the rows and their overlays, which a rebuild between inputs left out
      }
      if (dismiss(x, y, false)) {
        return false; // outside a modal overlay
      }
      List<El> areas = hit(x, y).reversed();
      boolean sideways = deltaX == 0;
      scrolled |= scroll(areas, true, deltaX, false)
          | scroll(areas, false, deltaY, sideways); // not ||
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
      if (sideways && area.style().overflowX == Style.Overflow.SCROLL
          && area.style().overflowY != Style.Overflow.SCROLL && area.canScroll(true, delta)) {
        return area.scrollTo(area.scrollX() + delta, area.scrollY());
      }
    }
    return false;
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
      return false;
    });
  }

  /**
   * Scrolls a virtual list ({@link El#rows}) of the last frame's {@link #tree} so that the row
   * with this index is at the top of its content box, or as far as the end of the list allows: to
   * an offset of {@code row * rowHeight}. The next {@link #frame} shows it and builds the rows it
   * brings into view. A handler may call this too, as {@link #scrollTo}.
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
      scrolled |= el != null && el.scrollTo(el.scrollX(), row * (double) el.rowHeight());
      return false;
    });
  }

  /** Queues input for the next frame, or what a handler asks for right after that handler. */
  private void enqueue(BooleanSupplier input) {
    if (dispatching) {
      follow.add(input);
      return;
    }
    if (!holdPosts) {
      takePosted(); // what other threads posted before this input comes before it
    }
    queued.add(input);
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
    Objects.requireNonNull(task, "task");
    offer(() -> {
      handled = true; // before it runs: a task that throws may have changed something
      task.run();
      return true;
    });
  }

  private void offer(BooleanSupplier task) {
    posted.add(task);
    Runnable w = wake;
    if (w != null && woken.compareAndSet(false, true)) {
      w.run();
    }
  }

  /** Moves what other threads posted into the queue; on the app's thread. */
  private void takePosted() {
    woken.set(false); // before the tasks are taken: one posted after them wakes again
    for (BooleanSupplier task; (task = posted.poll()) != null; ) {
      queued.add(task);
    }
  }

  /**
   * Sets what {@link #post} calls, on the posting thread, when a task arrives and the app has not
   * taken the ones before it: a runner that waits for events ends its wait with it, as a {@link
   * com.rethinkj.ui.window.WindowLoop} does while it runs. A burst of tasks calls it once, and
   * again only for a task that arrives after the app took the burst. Null for nothing, as without
   * a window.
   */
  public void onPost(Runnable wake) {
    this.wake = wake;
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
   * @param name what the work does, for the thread and for {@link #await}'s message, such as
   *     {@code "rows of orders"}
   * @return the work, to cancel it
   */
  public <T> Work background(String name, Callable<? extends T> compute,
      Consumer<? super T> onResult, Consumer<? super Exception> onFailure) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(compute, "compute");
    Objects.requireNonNull(onResult, "onResult");
    Objects.requireNonNull(onFailure, "onFailure");
    Work work = new Work(name, this);
    synchronized (running) {
      running.add(work);
    }
    work.thread = Thread.ofVirtual().name(name).unstarted(() -> {
      T result = null;
      Throwable failure = null;
      try {
        result = compute.call();
      } catch (Throwable t) {
        failure = t;
      }
      T r = result;
      Throwable f = failure;
      try {
        offer(() -> {
          if (work.cancelled) {
            return false; // costs no rebuild
          }
          handled = true;
          if (f == null) {
            onResult.accept(r);
          } else if (f instanceof Exception e) {
            onFailure.accept(e);
          } else if (f instanceof Error e) {
            throw e;
          } else {
            throw new IllegalStateException(name + " threw", f);
          }
          return true;
        });
      } finally {
        finished(work); // after the offer: await() then finds its delivery posted
      }
    });
    work.thread.start();
    return work;
  }

  /** The work has posted its delivery or was cancelled: {@link #await} waits no longer for it. */
  void finished(Work work) {
    synchronized (running) {
      if (running.remove(work)) {
        running.notifyAll();
      }
    }
  }

  /**
   * Waits until all the work that {@link #background} started has finished or was cancelled, and
   * then queues what it delivered, and everything else posted so far, for the next {@link #frame},
   * in the order it was posted. Scripts and tests call it where they want the answers, between
   * frames, so that what they see does not depend on how fast the work was: {@code frame} after it
   * shows them. Work that a delivery starts is not waited for; call this again for it. Tasks that
   * other threads post on their own are not waited for either: they come with the first call after
   * they were posted.
   *
   * @throws IllegalStateException if work still runs after {@code timeout}, naming it, or if this
   *     is called in a handler, whose thread it would block
   * @throws InterruptedException if the thread is interrupted while it waits
   */
  public void await(Duration timeout) throws InterruptedException {
    if (dispatching) {
      throw new IllegalStateException("await blocks the app's thread: call it between frames");
    }
    long nanos = timeout.toNanos();
    long start = System.nanoTime();
    synchronized (running) {
      while (!running.isEmpty()) {
        long left = nanos - (System.nanoTime() - start);
        if (left <= 0) {
          List<String> names = running.stream().map(Work::name).toList();
          throw new IllegalStateException("After " + timeout.toMillis() + " ms, the app's work"
              + " still runs: " + String.join(", ", names) + ". Work that App.background started"
              + " ends when it has delivered or is cancelled.");
        }
        TimeUnit.NANOSECONDS.timedWait(running, left);
      }
    }
    takePosted();
  }

  /** For {@link Script}: the innermost virtual list at this point, or null. */
  El listAt(float x, float y) {
    Target target = target(x, y, El::isVirtualList);
    return target == null ? null : target.el;
  }

  /**
   * Moves the app's clock forward. A windowed runner calls this once per frame with the time since
   * the last one; tests step it by hand. Nothing else reads a clock.
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
   *       queue, after the input that came before them; those posted from here on wait for the
   *       next frame.
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
   *       built between inputs may lack the rows under the pointer.
   *   <li>If anything may have changed (one of the above, or the looks of the moment differ from
   *       those the tree has), the view is built again: it gets the looks of the moment ({@link
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
   *       gets its colour.
   * </ol>
   *
   * @return whether there is something new to draw: the tree was rebuilt, something moved or
   *     scrolled
   */
  public boolean frame() {
    Object wasFocused = focusId;
    boolean hadRing = ring;
    boolean drawn = false;
    if (!holdPosts) {
      takePosted();
    }
    while (!queued.isEmpty()) {
      if (handled) { // the next input goes to a tree built from the state as it is now
        rebuild(true);
        drawn = true;
      }
      dispatching = true;
      try {
        stale |= queued.poll().getAsBoolean();
      } finally {
        dispatching = false;
        for (int i = follow.size() - 1; i >= 0; i--) {
          queued.addFirst(follow.get(i));
        }
        follow.clear();
      }
    }
    stale |= handled || !Objects.equals(focusId, wasFocused) || ring != hadRing;
    stale |= blink(focused == null ? null : focused.editor());
    if (stale) { // the rebuild builds all rows anyway
      drawn |= scrolled;
      scrolled = false;
    } else {
      drawn |= settle(); // the rows that scrolling or a rebuild between inputs left to build
    }
    // What the pointer is over now, in the tree on screen, after the scrolls moved it. Not in a
    // tree built between inputs, which may lack the rows under the pointer and would end their
    // stays: the rebuild below asks again.
    if (!stale) {
      List<El> path = pointed();
      stale = !wanted(path, Animator.identities(path)).equals(shown);
    }
    boolean laidOut = false;
    if (stale) {
      // Twice when the first build moved other elements under the still pointer, as a key that
      // scrolls a list does, or an onHoverFor handler ran: the looks are those of what the
      // pointer is over in the tree on screen.
      for (int pass = 0; pass < 2 && stale; pass++) {
        rebuild(false);
        List<El> path = pointed();
        stale = !wanted(path, Animator.identities(path)).equals(shown);
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
    boolean on = active && blinking != null && Math.floor((time - blinkStart) / BLINK) % 2 == 0;
    if (on != caretOn) {
      caretOn = on;
      if (blinking != null) {
        blinking.place(true, on);
      }
      drawn = true;
    }
    if (drawn) {
      El.placeBars(root);
    }
    drawn |= hover();
    return drawn;
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
   */
  private void rebuild(boolean between) {
    List<Object> ids = Animator.identities(pointed());
    El built = view.apply(state);
    El.fix(built);
    shown = applyLooks(built, ids);
    root = built;
    attachEditors(root, new ArrayList<>()); // before layout, which shows a composition
    El.scaleText(root, scale, systemFonts);
    Flex.layout(root, width, height);
    Overlays.layout(root, width, height);
    animator.update(root, time);
    stale = false;
    handled = false;
    scrolled = false;
    rowsPending = between && (focusId == null || animator.inRow(focusId) == null);
    if (rowsPending) {
      Overlays.place(root, width, height); // which fillRows does otherwise: the focus may be in one
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
    if (!scrolled && !rowsPending) {
      return false;
    }
    boolean pending = rowsPending;
    scrolled = false;
    rowsPending = false;
    if (fillRows() | pending) { // not ||
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
    if (editing != null && (editing.takeChanged() | moved)) {
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
      Editor editor = editors.computeIfAbsent(Animator.identities(path).getLast(),
          id -> new Editor(el));
      editor.attach(el);
      el.editor(editor);
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
   * that exist stays, with the focus, for when the row comes back; the editors of other fields in
   * such rows go with them, as everything else the app keeps of a row. A field that fades out
   * ({@link El#isExiting}) has lost its editor, and with it its caret, selection and composition.
   */
  private void placeEditors() {
    List<Editor> shown = new ArrayList<>();
    collectEditors(root, shown);
    Set<Editor> kept = Collections.newSetFromMap(new IdentityHashMap<>());
    kept.addAll(shown);
    Editor waiting = focusWaits() ? editors.get(focusId) : null;
    editors.values().removeIf(editor -> {
      if (editor == waiting || kept.contains(editor)) {
        return false;
      }
      editor.leave(); // its element may be fading out
      return true;
    });
    blink(focused == null ? null : focused.editor());
    for (Editor editor : shown) {
      editor.place(editor == blinking, caretOn);
    }
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
    return editor.drag(editor.fromShown(el.text().nearest(at[0], at[1])),
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
    float top = px(field.style().paddingTop) + px(field.style().borderTop);
    for (El el : fromOverlay(path)) {
      left += el.x();
      top += el.y();
    }
    Text text = field.text();
    return new float[] {
      (x - left) * text.scale() + text.scroll(), (y - top) * text.scale() + text.scrollY()
    };
  }

  /** The distance from an element's border box to its content box at the top. */
  private static float contentTop(El el) {
    return px(el.style().paddingTop) + px(el.style().borderTop);
  }

  /** The distance from an element's border box to its content box on the left. */
  private static float contentLeft(El el) {
    // shortcut: padding and border in pixels only, as the scene draws text.
    return px(el.style().paddingLeft) + px(el.style().borderLeft);
  }

  private static float px(Len len) {
    return len.kind() == Len.Kind.PX ? len.value() : 0;
  }

  /**
   * The identities of elements with looks, by the state they are in; see {@link #wanted}. {@code
   * rested} are those with a {@link El#whenHoveredFor} look that is due, and {@code hoversRun}
   * counts the {@link El#onHoverFor} handlers run, so that one that ran builds the view again.
   */
  private record Looks(
      List<Object> hovered, List<Object> rested, Object focused, Object pressed, int hoversRun) {

    /** These looks without those of elements in the rows of a list, which were built anew. */
    Looks without(Object list) {
      List<Object> kept = new ArrayList<>(hovered);
      kept.removeIf(id -> Animator.isBelow(id, list));
      List<Object> keptRested = new ArrayList<>(rested);
      keptRested.removeIf(id -> Animator.isBelow(id, list));
      return new Looks(
          kept,
          keptRested,
          Animator.isBelow(focused, list) ? null : focused,
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
    float far = Float.POSITIVE_INFINITY;
    boolean built = fillRows(root, new ArrayList<>(), 0, 0, -far, far);
    // After scrolling, movement and new rows, the overlays follow their anchors.
    Overlays.place(root, width, height);
    return built;
  }

  private boolean fillRows(
      El el, List<El> path, float parentX, float parentY, float clipLeft, float clipRight) {
    if (!el.isPlaced() || el.isExiting()) {
      return false;
    }
    path.add(el);
    float left = parentX + el.x();
    float top = parentY + el.y();
    int[] range = el.wantedRows(left, top, clipLeft, clipRight, width, height);
    boolean built = range != null;
    if (built) {
      Object id = Animator.identities(path).getLast();
      el.makeRows(range[0], range[1]);
      tellRows(el, id, range[0], range[1] - 1);
      shown = shown.without(id);
      for (El row : el.children()) {
        attachEditors(row, path);
      }
      el.layoutRows(scale, systemFonts);
      rowLooks(el, id); // after layout, which says which row is under the pointer
      for (El row : el.children()) {
        Overlays.layout(row, width, height); // after the looks, which may attach overlays
      }
      animator.rows(el, id, time);
    }
    if (el.style().overflowX != Style.Overflow.VISIBLE) {
      clipLeft = Math.max(clipLeft, left);
      clipRight = Math.min(clipRight, left + el.width());
    }
    for (El child : el.children()) {
      built |= fillRows(child, path, left, top, clipLeft, clipRight);
    }
    float far = Float.POSITIVE_INFINITY;
    for (El overlay : el.overlays()) { // in window coordinates
      built |= fillRows(overlay, path, 0, 0, -far, far);
    }
    path.removeLast();
    return built;
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
      queued.add(() -> { // after the input of this frame, or in the next one
        handler.accept(first, last);
        handled = true;
        return true;
      });
    }
  }

  /**
   * Applies the looks of the moment to the rows a list has just built, as {@link #applyLooks} does
   * for the rest of the tree, and lays out again each row that took one. The rows are laid out
   * first, so that the pointer finds the row it is over now, after a scroll or a movement too.
   */
  private void rowLooks(El list, Object listId) {
    List<Object> under = pointedIds();
    List<Object> hovered = new ArrayList<>(shown.hovered());
    List<Object> rested = new ArrayList<>(shown.rested());
    Object focus = shown.focused();
    Object press = shown.pressed();
    for (int i = 0; i < list.children().size(); i++) {
      El row = list.children().get(i);
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
      if (Animator.isBelow(focusId, listId)) {
        El el = Animator.find(row, rowId, focusId);
        if (el != null && el.isFocusable() && look(el, El::focusLook, "whenFocused")) {
          focus = focusId;
          took = true;
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
    shown = new Looks(hovered, rested, focus, press, shown.hoversRun());
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
    El focus = focusId == null ? null : animator.element(focusId);
    Object pressId = pressedId();
    El press = pressId == null ? null : animator.element(pressId);
    return new Looks(
        hovered,
        rested,
        focus != null && focus.focusLook() != null ? focusId : null,
        press != null && press.pressLook() != null ? pressId : null,
        hoversRun);
  }

  /**
   * Follows how long the pointer has stayed on the elements along {@code path} that wait for it:
   * runs the {@link El#onHoverFor} handlers that are due, notes in {@link #restDue} when the next
   * look or handler is, and returns the identities of those whose {@link El#whenHoveredFor} look
   * is due.
   */
  private List<Object> rest(List<El> path, List<Object> ids) {
    resting.keySet().retainAll(ids); // a stay ends when the pointer leaves
    restDue = Double.POSITIVE_INFINITY;
    List<Object> rested = new ArrayList<>();
    for (int i = 0; i < path.size(); i++) {
      El el = path.get(i);
      if (el.restLook() == null && el.onHoverFor() == null) {
        continue;
      }
      Rest rest = resting.computeIfAbsent(ids.get(i), id -> new Rest(time));
      if (el.restLook() != null) {
        double due = rest.since + el.restDelay();
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
          el.onHoverFor().run();
        } else {
          restDue = Math.min(restDue, due);
        }
      }
    }
    return rested;
  }

  /** Applies {@code el}'s {@link El#whenHoveredFor} look if it is due; whether it did. */
  private boolean restLook(El el, Object id) {
    Rest rest = el == null || el.restLook() == null ? null : resting.get(id);
    if (rest == null || rest.since + el.restDelay() > time) {
      return false;
    }
    el.apply(el.restLook(), "whenHoveredFor");
    return true;
  }

  /**
   * Before layout of a tree the view has just built: each overlay it no longer has gives the
   * focus back to where it was before the overlay took it, the newest first, if the focus is
   * still inside the overlay or nowhere; then each overlay that opens moves the focus to its first
   * focusable element, without a ring. A modal overlay without one takes the focus away until it
   * closes. {@link #refocus} finds the element after layout.
   */
  private void openAndClose(El tree) {
    Map<Object, El> now = Animator.overlays(tree);
    List<Object> before = new ArrayList<>(opened.keySet());
    for (Object id : before.reversed()) {
      if (now.containsKey(id)) {
        continue;
      }
      Focus back = opened.remove(id);
      if (back != null
          && (focusId == null || focusId.equals(id) || Animator.isBelow(focusId, id))) {
        moveFocus(back.id(), back.ring());
      }
    }
    for (Map.Entry<Object, El> overlay : now.entrySet()) {
      if (opened.containsKey(overlay.getKey())) {
        continue;
      }
      Object first = Animator.firstFocusable(overlay.getValue(), overlay.getKey());
      boolean takes = first != null || overlay.getValue().isModal();
      opened.put(overlay.getKey(), takes ? new Focus(focusId, ring) : null);
      if (takes) {
        moveFocus(first, false);
      }
    }
  }

  /** Moves the focus to the element with this identity, which the next {@link #refocus} finds. */
  private void moveFocus(Object id, boolean ring) {
    if (focused != null) {
      focused.focused(false);
    }
    focused = null;
    focusId = id;
    this.ring = ring && id != null;
    reveal = this.ring;
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
    openAndClose(tree); // after the looks, which may attach overlays, and before the focus look
    El focus = focusId == null ? null : Animator.find(tree, focusId);
    boolean focusLook = focus != null && focus.isFocusable() // or it loses the focus
        && look(focus, El::focusLook, "whenFocused");
    Object pressId = pressedId();
    boolean pressLook =
        pressId != null && look(Animator.find(tree, pressId), El::pressLook, "whenPressed");
    return new Looks(hovered, rested, focusLook ? focusId : null, pressLook ? pressId : null,
        hoversRun);
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
   * gives the thumb under it or dragged its colour.
   *
   * @return whether a thumb changed its colour
   */
  private boolean hover() {
    under = pointed();
    if (marked != null) {
      marked.hovered(false);
    }
    marked = null;
    barArea = null;
    Hot now = null;
    El grabbed = grab == null ? null : animator.element(grab.id());
    El last = under.isEmpty() ? null : under.getLast();
    if (grabbed != null) {
      barArea = grabbed;
      now = new Hot(grabbed, grab.vertical(), grabbed.scrollbars() == null ? 0
          : grabbed.scrollbars().thumbDragged());
    } else if (pointerIn && last != null && selecting == null
        && (dragging == null || !dragging.started)) {
      Scrollbars.Bar bar = last.barAt(pointerX, pointerY);
      if (bar != null) {
        barArea = last;
        boolean vertical = bar == last.verticalBar();
        if (onThumb(bar, vertical, pointerX, pointerY)) {
          now = new Hot(last, vertical, last.scrollbars().thumbHovered());
        }
      }
    }
    if (hot != null && !hot.equals(now) && hot.area().scrollbars() != null) {
      hot.area().barColour(hot.vertical(), hot.area().scrollbars().thumb());
    }
    if (now != null) {
      now.area().barColour(now.vertical(), now.colour());
    }
    boolean changed = !Objects.equals(now, hot);
    hot = now;
    if (barArea != null) { // a press would go to its bar
      marked = barArea;
      barArea.hovered(true);
      return changed;
    }
    for (El el : under.reversed()) {
      if (takesPress(el) || el.takesEdits()) {
        marked = el;
        el.hovered(true);
        break;
      }
    }
    return changed;
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
    return pointerIn ? hit(pointerX, pointerY) : List.of();
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
    return pointerIn ? Animator.identities(hit(pointerX, pointerY)) : List.of();
  }

  /**
   * The identity of the element a press is on: the one with a press or drag handler it went to,
   * or the text field it selects in; null between presses.
   */
  private Object pressedId() {
    return pressed != null ? pressed : selecting;
  }

  /** The identity of the element that a drag, a selection or a thumb's drag holds, or null. */
  private Object held() {
    return selecting != null ? selecting
        : dragging != null && dragging.started ? dragging.id
        : grab != null ? grab.id()
        : null;
  }

  /**
   * Whether something had not reached its target in the last frame, so that the next frame draws
   * something new after {@link #advance} even without input. Once this is false the app is idle.
   */
  public boolean isMoving() {
    return animator.isMoving();
  }

  /**
   * The part of a self-drawn window frame at this position in logical pixels, which a window asks
   * while the pointer moves. The rule is the one presses follow: the topmost element under the
   * point decides, or its nearest ancestor that has a {@link El#windowPart} or a press handler. An
   * element with a handler and no part is content, so controls inside a title bar stay clickable.
   *
   * @return the part, {@link WindowPart#CONTENT} where nothing is marked
   */
  public WindowPart windowPart(float x, float y) {
    List<El> hit = hit(x, y);
    if (!hit.isEmpty() && hit.getLast().barAt(x, y) != null) {
      return WindowPart.CONTENT;
    }
    Target target = target(x, y,
        el -> el.windowPart() != null || takesPress(el) || el.takesEdits());
    if (target == null || target.el.windowPart() == null) {
      return WindowPart.CONTENT;
    }
    return target.el.windowPart();
  }

  /** The tree of the last frame. */
  public El tree() {
    return root;
  }

  /**
   * What the last frame draws, with the focus ring if there is one. Its glyphs are in the app's
   * atlas, which keeps them from one scene to the next; a scene's glyphs stay there until the
   * next call.
   */
  public Scene scene() {
    return Scene.of(root, ring ? focused : null, ringColour, atlas, textRendering);
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

  private static boolean takesPress(El el) {
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
    if (root != null) {
      for (El overlay : root.shownOverlays().reversed()) {
        List<El> hit = hitFrom(overlay, x, y);
        if (!hit.isEmpty() || overlay.isModal() && !overlay.isExiting()) {
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
        if (contains(children.get(i), left, top, x, y)) {
          next = children.get(i);
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
   * Whether the point lies inside the element's rounded rectangle and the element takes input:
   * neither invisible nor leaving.
   */
  private static boolean contains(El el, float parentX, float parentY, float x, float y) {
    if (!el.isPlaced() || el.opacity() == 0 || el.isExiting()) {
      return false;
    }
    float halfW = el.width() / 2;
    float halfH = el.height() / 2;
    float radius = Math.clamp(el.radius(), 0, Math.min(halfW, halfH));
    float qx = Math.abs(x - (parentX + el.x() + halfW)) - halfW + radius;
    float qy = Math.abs(y - (parentY + el.y() + halfH)) - halfH + radius;
    if (qx <= 0 || qy <= 0) {
      return Math.max(qx, qy) <= radius;
    }
    return qx * qx + qy * qy <= radius * radius;
  }
}
