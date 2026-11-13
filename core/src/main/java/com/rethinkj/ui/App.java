package com.rethinkj.ui;

import com.rethinkj.ui.layout.Flex;
import com.rethinkj.ui.render.CpuRenderer;
import com.rethinkj.ui.render.Scene;
import com.rethinkj.ui.text.Atlas;
import com.rethinkj.ui.text.TextRendering;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * <p>Each input is dispatched against a tree built from the state as it is when its turn comes.
 * When a handler of the app has taken an input and more input of the same frame waits, the frame
 * builds the view again first, gives it the looks of the moment and its fields their editing
 * state, and lays it out. The next input then finds its element in that tree and runs that
 * element's handler, a closure of the new view: two ArrowDown in one frame move a selection by two
 * rows, and a second edit of a field builds on what the app made of the first. The rows of the
 * virtual lists in such a tree, and its scrollbars, are built before an input that needs them: a
 * press, a release, Tab, a focus or a scroll the app asks for, and a key while the focus is in a
 * row. Other keys, the wheel and drags do with the rows that exist, so a key handler there sees
 * its list without rows. Input that no handler of the app took costs no rebuild: a pointer move, a
 * key that nobody handles or that only moves a field's caret, a focus moved by Tab, the wheel. A
 * key handler that returns false counts as having changed nothing.
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
 * when that is. Cut, copy and paste go through the app's {@link #clipboard}.
 *
 * <p>This class needs no window. A windowed runner feeds it the window's input and draws its
 * {@link #scene}; tests and agents feed it input themselves and look at {@link #render}.
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
   */
  public record InputArea(float x, float y, float width, float height, float cursor) {}

  private final S state;
  private final Function<S, El> view;
  // Input in the order it came, dispatched by frame(). Each returns whether it may have changed
  // something that the next rebuild shows.
  private final ArrayDeque<BooleanSupplier> queued = new ArrayDeque<>();
  // What a handler queues while an input runs, such as a focus or a scroll: it runs next.
  private final List<BooleanSupplier> follow = new ArrayList<>();
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
  private Dragging dragging; // a pointer down on an element with a drag handler, until it is up
  private Grab grab; // a press on the thumb of a scrollbar, until the pointer is up
  private El barArea; // the scroll area whose bar is under the pointer or dragged, in this tree
  private Hot hot; // the bar that the last frame shows hovered or dragged
  private boolean pointerIn;
  private float pointerX;
  private float pointerY;
  private List<El> under = List.of(); // the elements under the pointer in the current tree
  private El marked; // the one of them a press would go to, marked for the dump
  private Looks shown = new Looks(List.of(), null, null); // the looks the current tree has
  private El focused; // in the current tree
  private Object focusId; // its identity, to find it in the next tree
  private boolean ring; // the keyboard or the app moved the focus: draw the ring
  private boolean reveal; // scroll the focused element into view after the next rebuild
  // The identity of the element that last had the focus in a virtual list, by the list's identity.
  // shortcut: lists that are gone stay in it, one small entry each; drop them in update() when
  // apps create lists by the thousand.
  private final Map<Object, Object> remembered = new HashMap<>();
  private int ringColour = FOCUS_RING;
  private final Atlas atlas = new Atlas();
  private TextRendering textRendering = TextRendering.platform();
  private final Map<Object, Editor> editors = new HashMap<>(); // the fields' state, by identity
  private Clipboard clipboard = Clipboard.inMemory();
  private Object selecting; // the identity of the field a press is selecting in, until pointer up
  private Editor blinking; // the editor of the focused field
  private double blinkStart; // when its caret last turned on
  private boolean caretOn;

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
    dragging = target == null || target.el.onDrag() == null
        ? null
        : new Dragging(target.id(), target.el.onDrag(), x, y);
    Target focus = target(x, y, El::isFocusable);
    focus(focus == null ? null : focus.el, false);
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
        target.el.onPress().accept(pressClicks);
        handled = changed = true;
      }
    }
    pressed = null;
    return changed;
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
    d.handler.accept(new Drag(gone ? Drag.Phase.CANCEL : phase, dx, dy));
    handled = true;
    return true;
  }

  /**
   * A key went down or repeats. The next {@link #frame} gives it to the {@link El#onKey} handlers
   * of the focused element and then of its ancestors, until one returns true; without focus only
   * the root's handler sees it. If none does, Enter and Space without modifiers press the focused
   * element if it has an {@link El#onPress} handler, which takes their repeats without pressing
   * again, and Tab moves the focus to the next focusable element in tree order, Shift+Tab to the
   * previous one, around at the ends. Unlike in a browser, a press handler on the focused element
   * takes Enter and Space before its ancestors' key handlers see them.
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
            el.onPress().accept(1);
            handled = true;
          }
          return true;
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
          if (el.editor() != null) {
            el.editor().selectAll(); // as browsers do when Tab enters a field
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
    for (El el : path) {
      x += el.x();
      y += el.y();
    }
    float cursor = editor == null ? 0 : contentLeft(focused) + editor.caretOffset();
    return new InputArea(x, y, focused.width(), focused.height(), cursor);
  }

  /**
   * The time on the app's clock at which the last frame changes without input or movement: when
   * the caret of the focused field turns on or off. Positive infinity when nothing will; then an
   * idle window can wait for input alone.
   */
  public double nextChange() {
    if (blinking == null) {
      return Double.POSITIVE_INFINITY;
    }
    return blinkStart + (Math.floor((time - blinkStart) / BLINK) + 1) * BLINK;
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
   * Sets the colour of the focus ring, as 0xAARRGGBB; 0 draws none. The ring is 2 pixels wide and
   * lies just outside the element's border box, with its corners.
   */
  public void focusRing(int argb) {
    ringColour = argb;
    stale = true;
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
    path.removeLast();
    return false;
  }

  /**
   * Scrolls the scroll areas around {@code el}, the innermost first, just far enough to show its
   * border box whole inside theirs, or its start if it is larger.
   */
  private void reveal(El el) {
    List<El> path = new ArrayList<>();
    pathTo(root, el, path);
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
      focusables(root, 0, 0, -far, -far, far, far, found);
    }
    return found;
  }

  private static void focusables(
      El el, float parentX, float parentY, float clipLeft, float clipTop, float clipRight,
      float clipBottom, List<El> found) {
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
      if (!(clipRight > clipLeft && clipBottom > clipTop)) {
        return; // nothing below shows
      }
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
    for (El child : el.children()) {
      focusables(child, left, top, clipLeft, clipTop, clipRight, clipBottom, found);
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
   * to its content; when none can, nothing happens. The next {@link #frame} shows it.
   */
  public void wheel(float x, float y, float deltaX, float deltaY) {
    if (!Float.isFinite(deltaX) || !Float.isFinite(deltaY)) {
      throw new IllegalArgumentException("wheel deltas must be finite, were " + deltaX + ", "
          + deltaY);
    }
    enqueue(() -> {
      // The areas under the pointer are there without the rows a scroll or rebuild left to build.
      List<El> areas = hit(x, y).reversed();
      scrolled |= scroll(areas, true, deltaX) | scroll(areas, false, deltaY); // not ||
      return false;
    });
  }

  private static boolean scroll(List<El> areas, boolean horizontal, float delta) {
    for (El area : areas) {
      if (area.canScroll(horizontal, delta)) {
        return horizontal
            ? area.scrollTo(area.scrollX() + delta, area.scrollY())
            : area.scrollTo(area.scrollX(), area.scrollY() + delta);
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
    (dispatching ? follow : queued).add(input);
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
   *   <li>Each input in turn finds the element it goes to in the tree of the moment and runs its
   *       handler; a focus or scroll that the handler asks for runs right after it. Before an
   *       input that follows one a handler of the app took, the view is built again as in step 4,
   *       so that it sees what that handler changed, but the rows of its lists only if the focus
   *       is in one; a press, a release, Tab and a focus build the rows that this or a scroll
   *       left to build, and place the bars, before they look for their element.
   *   <li>Unless step 4 builds everything: the rows that scrolling brought into view, or that a
   *       rebuild between two inputs left out, are built, and the bars placed.
   *   <li>The pointer is followed into the tree as it is now: what it is over decides the looks.
   *   <li>If anything may have changed, the view is built again: it gets the looks of the moment
   *       ({@link El#whenHovered}) and its text fields their editing state, is laid out, takes
   *       over the values and offsets of the tree before, and its virtual lists build their rows,
   *       each row the same way; the focus is found again, and the bars are placed. Otherwise the
   *       elements that move get the values of the moment, and lists that moved build the rows
   *       they show now.
   *   <li>The caret and selection of each field are placed, the caret blinks, the bars are
   *       placed and the thumb under the pointer gets its colour.
   * </ol>
   *
   * @return whether there is something new to draw: the tree was rebuilt, something moved or
   *     scrolled
   */
  public boolean frame() {
    Object wasFocused = focusId;
    boolean hadRing = ring;
    boolean drawn = false;
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
    // What the pointer is over now, in the tree on screen, after the scrolls moved it.
    List<El> path = pointed();
    stale |= !wanted(path, Animator.identities(path)).equals(shown);
    boolean laidOut = false;
    if (stale) {
      rebuild(false);
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
    boolean on = blinking != null && Math.floor((time - blinkStart) / BLINK) % 2 == 0;
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
    shown = applyLooks(built, ids);
    root = built;
    attachEditors(root, new ArrayList<>()); // before layout, which shows a composition
    El.scaleText(root, scale);
    Flex.layout(root, width, height);
    animator.update(root, time);
    stale = false;
    handled = false;
    scrolled = false;
    rowsPending = between && (focusId == null || animator.inRow(focusId) == null);
    if (rowsPending) {
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
    for (El el : path) {
      left += el.x();
      top += el.y();
    }
    Text text = field.text();
    return new float[] {(x - left) * text.scale() + text.scroll(), (y - top) * text.scale()};
  }

  /** The distance from an element's border box to its content box on the left. */
  private static float contentLeft(El el) {
    // shortcut: padding and border in pixels only, as the scene draws text.
    return px(el.style().paddingLeft) + px(el.style().borderLeft);
  }

  private static float px(Len len) {
    return len.kind() == Len.Kind.PX ? len.value() : 0;
  }

  /** The identities of elements with looks, by the state they are in; see {@link #wanted}. */
  private record Looks(List<Object> hovered, Object focused, Object pressed) {

    /** These looks without those of elements in the rows of a list, which were built anew. */
    Looks without(Object list) {
      List<Object> kept = new ArrayList<>(hovered);
      kept.removeIf(id -> Animator.isBelow(id, list));
      return new Looks(
          kept,
          Animator.isBelow(focused, list) ? null : focused,
          Animator.isBelow(pressed, list) ? null : pressed);
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
    return root != null && fillRows(root, new ArrayList<>(), 0);
  }

  private boolean fillRows(El el, List<El> path, float parentY) {
    if (!el.isPlaced() || el.isExiting()) {
      return false;
    }
    path.add(el);
    float top = parentY + el.y();
    int[] range = el.wantedRows(top, height);
    boolean built = range != null;
    if (built) {
      Object id = Animator.identities(path).getLast();
      el.makeRows(range[0], range[1]);
      shown = shown.without(id);
      for (El row : el.children()) {
        attachEditors(row, path);
      }
      el.layoutRows(scale);
      rowLooks(el, id); // after layout, which says which row is under the pointer
      animator.rows(el, id, time);
    }
    for (El child : el.children()) {
      built |= fillRows(child, path, top);
    }
    path.removeLast();
    return built;
  }

  /**
   * Applies the looks of the moment to the rows a list has just built, as {@link #applyLooks} does
   * for the rest of the tree, and lays out again each row that took one. The rows are laid out
   * first, so that the pointer finds the row it is over now, after a scroll or a movement too.
   */
  private void rowLooks(El list, Object listId) {
    List<Object> under = pointedIds();
    List<Object> hovered = new ArrayList<>(shown.hovered());
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
        list.layoutRow(i, scale); // a look may change the layout
      }
    }
    shown = new Looks(hovered, focus, press);
  }

  /**
   * The looks that the elements of the current tree ask for in the states of the moment: those
   * along {@code path} under the pointer with a hover look, and the focused and the pressed element
   * if they have a look for that.
   */
  private Looks wanted(List<El> path, List<Object> ids) {
    List<Object> hovered = new ArrayList<>();
    for (int i = 0; i < path.size(); i++) {
      if (path.get(i).hoverLook() != null) {
        hovered.add(ids.get(i));
      }
    }
    El focus = focusId == null ? null : animator.element(focusId);
    Object pressId = pressedId();
    El press = pressId == null ? null : animator.element(pressId);
    return new Looks(
        hovered,
        focus != null && focus.focusLook() != null ? focusId : null,
        press != null && press.pressLook() != null ? pressId : null);
  }

  /**
   * Applies the looks of the moment to the tree the view has just built: the hover looks of the
   * elements with these identities, then the focus look, then the pressed one.
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
    El focus = focusId == null ? null : Animator.find(tree, focusId);
    boolean focusLook = focus != null && focus.isFocusable() // or it loses the focus
        && look(focus, El::focusLook, "whenFocused");
    Object pressId = pressedId();
    boolean pressLook =
        pressId != null && look(Animator.find(tree, pressId), El::pressLook, "whenPressed");
    return new Looks(hovered, focusLook ? focusId : null, pressLook ? pressId : null);
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
      return path;
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

  /** The elements under the point, from the root down to the topmost one; empty if none. */
  private List<El> hit(float x, float y) {
    List<El> hit = new ArrayList<>();
    if (root == null || !contains(root, 0, 0, x, y)) {
      return hit;
    }
    El el = root;
    float left = root.x();
    float top = root.y();
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
