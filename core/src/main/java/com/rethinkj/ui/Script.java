package com.rethinkj.ui;

import com.rethinkj.ui.render.Png;
import com.rethinkj.ui.window.Windows;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives an {@link App} without a window from a script, so that an agent can try an app without
 * writing Java: steps go in, images and trees come out. One step per line:
 *
 * <pre>
 * size 200 120 scale 2               # the window in logical pixels, and its scale
 * frame
 * press role=switch label="Lights"   # pointer down and up on the centre of that element
 * frame                              # runs the handler and rebuilds the view
 * move #switch                       # the pointer over the switch, which looks hovered
 * leave                              # the pointer out of the window
 * advance 0.04
 * frame
 * snap moving                        # writes moving.png and moving.tree.txt
 * wheel #list 0 40                   # the wheel, 40 pixels down, over the centre of #list
 * scroll #orders row 5000            # a list made with El.rows: row 5000 at its top
 * key Tab shift                      # Shift+Tab, down and up: the focus moves back
 * type "hello"                       # text for the focused element
 * focus #name                        # gives #name the focus, as Tab would
 * press #name clicks 2               # a double press, which selects a word in a field
 * press #name shift                  # with Shift held, which extends a field's selection
 * press #row-3 right                 # the secondary button, which opens a context menu
 * compose "ni"                       # an input method's composition in the focused field
 * clipboard "SELECT 1"               # puts text on the app's clipboard
 * down #resize-1                     # pointer down; a move before the up drags an El.onDrag
 * move 230,40                        # element once it is beyond 4 pixels, or selects in a
 * up 230,40                          # text field from the first pixel
 * await                              # waits for the work the app started on other threads
 * await progress                     # waits for a report of that work, not for its end
 * </pre>
 *
 * <p>{@code snap} also writes {@code <name>.clipboard.txt} with the text on the app's clipboard,
 * when there is any. A {@code press} and a {@code wheel} act at their target's position without
 * moving the pointer: what looks hovered stays where {@code move} left it.
 *
 * <p>Only {@code frame} runs a frame. As in a window, input and {@code advance} between frames
 * change nothing on screen until the next one, so {@code snap} refuses to write a frame that does
 * not show the steps before it. What other threads {@link App#post post} waits until an {@code
 * await} step, which waits for the work the app started ({@link App#background}) and queues what
 * it delivered for the next frame, so that a script shows the same however fast the work is;
 * {@code await progress} lets in what running work has reported ({@link App#awaitProgress}).
 * AGENTS.md describes the format.
 */
public final class Script {

  /** An error in a script or its arguments, with the line and what to do about it. */
  public static final class Failure extends RuntimeException {
    Failure(String message, Throwable cause) {
      // No stack trace: the message is for whoever wrote the script, not the code.
      super(message, cause, false, false);
    }
  }

  private static final String USAGE =
      "Run a script with --script <file> --out <folder>: it runs the steps in the file and writes"
          + " the snaps into the folder.";
  private static final String TARGET =
      "a position x,y in logical pixels such as 90,60, or a selector of #key, role=<role>,"
          + " label=\"<text>\", text=\"<string>\" and states as a .tree.txt writes them (selected,"
          + " checked=false, expanded, level=2, value=\"<text>\") such as role=switch"
          + " label=\"Lights\"";
  /** A state of a selector, as {@link El#dump} writes it, but the value; =true may be added. */
  private static final Pattern STATE = Pattern.compile(
      "disabled|readonly|password|invalid|busy|selected|current|grabbed|checked(=true|=false|=mixed)?|expanded(=true|=false)?"
          + "|level=[1-9]\\d{0,9}|pos=[1-9]\\d{0,9}/[1-9]\\d{0,9}|sort=(ascending|descending)");
  /** How many seconds {@code await} waits for the app's work unless it says otherwise. */
  private static final double AWAIT = 10;

  private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?");
  private static final Pattern INDEX = Pattern.compile("\\d{1,10}");
  private static final Pattern CLICKS = Pattern.compile("[1-9]\\d{0,2}");
  private static final Pattern SIGNED = Pattern.compile("-?\\d+(\\.\\d+)?");
  private static final Pattern POSITION =
      Pattern.compile("(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)");

  private Script() {}

  /**
   * Gives an app's {@code main} the command line of a script: with {@code --script <file> --out
   * <folder>} it runs the file against {@code app}, prints each file it wrote and returns true.
   * Without either argument it does nothing and returns false, and {@code main} goes on to open
   * its window.
   *
   * @throws Failure if the arguments or the script are wrong; uncaught, it exits with status 1
   */
  public static boolean handle(String[] args, App<?> app) throws IOException {
    List<String> list = List.of(args);
    if (!list.contains("--script") && !list.contains("--out")) {
      return false;
    }
    String script = null;
    String out = null;
    for (int i = 0; i < args.length; i += 2) {
      String value = i + 1 < args.length ? args[i + 1] : null;
      switch (args[i]) {
        case "--script" -> script = value;
        case "--out" -> out = value;
        default -> throw new Failure("Unknown argument " + args[i] + ". " + USAGE, null);
      }
    }
    if (script == null || out == null) {
      throw new Failure(USAGE, null);
    }
    String text;
    try {
      text = Files.readString(Path.of(script));
    } catch (NoSuchFileException e) {
      throw new Failure("There is no script " + script + ". " + USAGE, null);
    }
    for (Path file : run(text, app, Path.of(out))) {
      System.out.println(file);
    }
    return true;
  }

  /**
   * Runs the steps of {@code script} against {@code app}, one per line.
   *
   * @param out the folder {@code snap} writes into; it is created if needed
   * @return the files written, a PNG image and a tree as text for each {@code snap}
   * @throws Failure at the first line that is wrong or cannot run, naming the line
   */
  public static List<Path> run(String script, App<?> app, Path out) throws IOException {
    boolean held = app.holdPosts;
    app.holdPosts = true; // what other threads post arrives where the script awaits it
    // A script that goes on where one before it ended finds its windows, which stay the app's.
    Desk desk = app.windows() instanceof Desk d && d.first.app == app ? d : new Desk(app);
    try {
      return steps(script, desk, out);
    } finally {
      app.holdPosts = held;
    }
  }

  private static List<Path> steps(String script, Desk desk, Path out) throws IOException {
    Path folder = out.toAbsolutePath().normalize();
    List<Path> written = new ArrayList<>();
    Map<Path, Integer> snapped = new HashMap<>();
    Line pending = null; // the first step since the last frame, which that frame does not show
    List<String> lines = script.lines().toList();
    for (int i = 0; i < lines.size(); i++) {
      Line line = new Line(i + 1, lines.get(i).strip());
      List<String> words = line.words();
      if (words.isEmpty()) {
        continue;
      }
      String step = words.getFirst();
      List<String> args = words.subList(1, words.size());
      Win win = desk.current();
      App<?> app = win.app;
      switch (step) {
        case "size" -> {
          List<String> size = new ArrayList<>(args);
          int[] at = null;
          int where = size.indexOf("at");
          if (where >= 2) {
            Matcher position =
                where + 1 < size.size() ? POSITION.matcher(size.get(where + 1)) : null;
            if (position == null || !position.matches()) {
              throw line.usage(step);
            }
            at = new int[] {
              Math.round(Float.parseFloat(position.group(1))),
              Math.round(Float.parseFloat(position.group(2)))
            };
            size.subList(where, where + 2).clear();
          }
          boolean scaled = size.size() == 4 && size.get(2).equals("scale");
          if (size.size() != 2 && !scaled) {
            throw line.usage(step);
          }
          win.resize(
              (float) line.number(size.get(0), true, step),
              (float) line.number(size.get(1), true, step),
              scaled ? (float) line.number(size.get(3), true, step) : 1);
          if (at != null) {
            win.moveTo(at[0], at[1]);
          }
        }
        case "window" -> {
          if (args.size() != 1) {
            throw line.usage(step);
          }
          desk.select(line, unquote(args.getFirst()));
        }
        case "close" -> {
          if (!args.isEmpty()) {
            throw line.usage(step);
          }
          win.request();
        }
        case "press", "down", "up" -> {
          // press and down: the target, then clicks <n>, the modifiers held and right, in any
          // order; up: the target and right
          List<String> target = new ArrayList<>(args);
          int clicks = 1;
          int modifiers = 0;
          boolean right = false;
          while (!target.isEmpty()) {
            int modifier = step.equals("up") ? 0 : modifier(target.getLast());
            if (target.getLast().equals("right") && !right) {
              right = true;
              target.removeLast();
            } else if (modifier != 0) {
              modifiers |= modifier;
              target.removeLast();
            } else if (!step.equals("up")
                && target.size() > 1
                && target.get(target.size() - 2).equals("clicks")) {
              if (!CLICKS.matcher(target.getLast()).matches()) {
                throw line.usage(step);
              }
              clicks = Integer.parseInt(target.getLast());
              target.subList(target.size() - 2, target.size()).clear();
            } else {
              break;
            }
          }
          if (target.isEmpty() || right && (clicks != 1 || modifiers != 0)) {
            throw line.usage(step);
          }
          Aim aim = desk.aim(line, target, "there is nothing to press");
          // The window a press began in has the pointer until its release, as SDL captures it.
          Win to = step.equals("press") || desk.held == null ? aim.win : desk.held;
          float[] at = aim.in(to);
          desk.pointerTo(to);
          if (!step.equals("up")) {
            desk.held = step.equals("down") ? to : null;
            if (right) {
              to.app.secondaryDown(at[0], at[1]);
            } else {
              to.app.pointerDown(at[0], at[1], clicks, modifiers);
            }
          }
          if (!step.equals("down")) {
            desk.held = null;
            if (right) {
              to.app.secondaryUp(at[0], at[1]);
            } else {
              to.app.pointerUp(at[0], at[1]);
            }
          }
        }
        case "move" -> {
          if (args.isEmpty()) {
            throw line.usage(step);
          }
          Aim aim = desk.aim(line, args, "there is nothing to move over");
          Win to = desk.held == null ? aim.win : desk.held;
          float[] at = aim.in(to);
          desk.pointerTo(to);
          to.app.pointerMove(at[0], at[1]);
        }
        case "leave" -> {
          if (!args.isEmpty()) {
            throw line.usage(step);
          }
          (desk.pointer == null ? win : desk.pointer).app.pointerLeave();
          desk.pointer = null;
        }
        case "wheel" -> {
          if (args.size() < 3
              || !SIGNED.matcher(args.get(args.size() - 2)).matches()
              || !SIGNED.matcher(args.getLast()).matches()) {
            throw line.usage(step);
          }
          Aim aim = desk.aim(line, args.subList(0, args.size() - 2), "there is nothing to scroll");
          float[] at = aim.in(aim.win);
          aim.win.app.wheel(
              at[0],
              at[1],
              Float.parseFloat(args.get(args.size() - 2)),
              Float.parseFloat(args.getLast()));
        }
        case "scroll" -> {
          if (args.size() < 3
              || !args.get(args.size() - 2).equals("row")
              || !INDEX.matcher(args.getLast()).matches()) {
            throw line.usage(step);
          }
          needsFrame(line, app, "there is nothing to scroll");
          List<String> target = args.subList(0, args.size() - 2);
          float[] at = line.point(target, app.tree());
          El list = app.listAt(at[0], at[1]);
          if (list == null) {
            throw line.fail(
                "There is no list of rows at " + String.join(" ", target) + ". `scroll` scrolls"
                    + " an element made with El.rows(...), or the one around the target; a"
                    + " .tree.txt shows one with `rows` in its line. Scroll other areas with"
                    + " `wheel`.");
          }
          long row = Long.parseLong(args.getLast());
          if (row >= list.rowCount()) {
            throw line.fail("The list has " + list.rowCount() + " rows, "
                + (list.rowCount() == 0
                    ? "so none to scroll to."
                    : "from 0 to " + (list.rowCount() - 1) + ". Write a row in that range."));
          }
          app.scrollToRow(list, (int) row);
        }
        case "key" -> {
          if (args.isEmpty()) {
            throw line.usage(step);
          }
          String name = args.getFirst();
          if (!Key.isName(name)) {
            throw line.fail(unknownKey(name));
          }
          int modifiers = 0;
          String half = null; // down or up: one half of the key's press, to hold it between frames
          for (String word : args.subList(1, args.size())) {
            if ((word.equals("down") || word.equals("up")) && half == null) {
              half = word;
              continue;
            }
            int modifier = modifier(word);
            if (modifier == 0) {
              throw line.usage(step);
            }
            modifiers |= modifier;
          }
          needsFrame(line, app, "nothing can take the key");
          Key key = new Key(name, modifiers, false);
          if (!"up".equals(half)) {
            app.keyDown(key);
          }
          if (!"down".equals(half)) {
            app.keyUp(key);
          }
        }
        case "type", "compose", "clipboard" -> {
          String word = args.size() == 1 ? args.getFirst() : "";
          // Only type needs text: compose "" ends a composition, clipboard "" empties it.
          if (word.length() < (step.equals("type") ? 3 : 2)
              || !word.startsWith("\"")
              || !word.endsWith("\"")) {
            throw line.usage(step);
          }
          String text = unescape(word.substring(1, word.length() - 1));
          switch (step) {
            case "type" -> {
              needsFrame(line, app, "nothing can take the text");
              app.textInput(text);
            }
            case "compose" -> {
              needsFrame(line, app, "nothing can take the composition");
              app.textComposition(text, -1);
            }
            default -> app.clipboard().write(text);
          }
        }
        case "focus" -> {
          if (args.isEmpty()) {
            throw line.usage(step);
          }
          needsFrame(line, app, "there is nothing to focus");
          float[] at = line.point(args, app.tree());
          if (app.focusAt(at[0], at[1]) == null) {
            List<Found> all = new ArrayList<>();
            walk(app.tree(), all, new ArrayList<>());
            StringBuilder rows = new StringBuilder();
            for (Found found : all) {
              if (found.el.isFocusable()) {
                found.el.describe(rows.append("    "), found.left, found.top);
              }
            }
            throw line.fail(
                "Neither the element at " + String.join(" ", args) + " nor one around it is"
                    + " focusable, so the focus cannot go there. "
                    + (rows.isEmpty()
                        ? "No element of the last frame is; El.focusable() marks one."
                        : "These elements of the last frame are:\n" + rows + "  Select one of"
                            + " them."));
          }
        }
        case "advance" -> {
          if (args.size() != 1) {
            throw line.usage(step);
          }
          double seconds = line.number(args.getFirst(), false, step);
          for (Win each : desk.open()) {
            each.app.advance(seconds);
          }
        }
        case "await" -> {
          boolean progress = !args.isEmpty() && args.getFirst().equals("progress");
          List<String> rest = progress ? args.subList(1, args.size()) : args;
          if (rest.size() > 1) {
            throw line.usage(step);
          }
          double seconds = rest.isEmpty() ? AWAIT : line.number(rest.getFirst(), true, step);
          Duration timeout = Duration.ofNanos((long) Math.min(seconds * 1e9, Long.MAX_VALUE));
          try {
            if (progress) {
              App.awaitProgress(desk.open().stream().map(each -> each.app).toList(), timeout);
            } else {
              for (Win each : desk.open()) {
                each.app.await(timeout);
              }
            }
          } catch (IllegalStateException e) {
            String example = progress ? "`await progress 30`" : "`await 30`";
            throw line.fail(e.getMessage()
                + (e.getMessage().startsWith("After") // a timeout, not a mistake of the script
                    ? " If it needs longer, write a timeout in seconds, as in " + example
                        + "; the default is " + (int) AWAIT + "."
                    : ""));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw line.fail("The script was interrupted while it waited for the app's work.");
          }
        }
        case "dialog" -> desk.answers.add(answer(line, args));
        case "drop" -> {
          if (args.size() == 1 && args.getFirst().equals("leave")) {
            (desk.dropping == null ? win : desk.dropping).app.fileDragLeave();
            desk.dropping = null;
          } else {
            boolean over = !args.isEmpty() && args.getFirst().equals("over");
            List<String> target = new ArrayList<>(over ? args.subList(1, args.size()) : args);
            String text = null;
            List<Path> files = new ArrayList<>();
            if (!over
                && target.size() >= 3
                && target.get(target.size() - 2).equals("text")
                && quoted(target.getLast())) {
              text = unescape(strip(target.removeLast()));
              target.removeLast();
            } else {
              while (!over && !target.isEmpty() && quoted(target.getLast())) {
                files.addFirst(path(line, strip(target.removeLast())));
              }
            }
            if (target.isEmpty() || !over && text == null && files.isEmpty()) {
              throw line.usage(step);
            }
            Aim aim = desk.aim(line, target, "there is nothing to drop on");
            float[] at = aim.in(aim.win);
            if (desk.dropping != null && desk.dropping != aim.win && desk.dropping.open) {
              desk.dropping.app.fileDragLeave();
            }
            aim.win.app.fileDragOver(at[0], at[1]);
            desk.dropping = over ? aim.win : null;
            if (!over) {
              aim.win.app.fileDrop(at[0], at[1], files, text);
            }
          }
        }
        case "frame" -> {
          if (!args.isEmpty()) {
            throw line.usage(step);
          }
          if (!desk.first.sized) {
            throw line.fail(
                "The window has no size yet. Add `size <width> <height>` before this line, for"
                    + " example `size 200 120`.");
          }
          try {
            desk.frame();
          } catch (RuntimeException e) {
            throw line.fail("The app threw " + e + " in this frame; its stack trace follows.", e);
          }
          desk.answered(line);
        }
        case "snap" -> {
          if (args.size() != 1) {
            throw line.usage(step);
          }
          if (pending != null && desk.first.app.tree() != null) {
            throw line.fail(
                "The last frame does not show line " + pending.number + " (" + pending.text
                    + ") yet. Add `frame` before this line: only `frame` runs the handlers,"
                    + " rebuilds the view and moves what is animated.");
          }
          // Each open window, the first under the name and the others under theirs before it.
          for (Win each : desk.open()) {
            String name = (each == desk.first ? "" : slug(each.title) + "-") + args.getFirst();
            Path png;
            Path tree;
            try {
              png = folder.resolve(name + ".png").normalize();
              tree = folder.resolve(name + ".tree.txt").normalize();
            } catch (InvalidPathException e) {
              throw line.fail(name + " is not a file name: " + e.getReason() + ".");
            }
            if (!png.startsWith(folder)) {
              throw line.fail(
                  name + " leads out of the output folder. Use a name inside it, such as"
                      + " after-press or press/after.");
            }
            Integer earlier = snapped.putIfAbsent(png, line.number);
            if (earlier != null) {
              throw line.fail(
                  "The snap on line " + earlier + " wrote these files. Use another name.");
            }
            if (each.app.tree() == null) {
              throw line.fail("No frame has run yet, so there is nothing to snap. Add `frame`"
                  + " before this line.");
            }
            Headless.Frame frame = each.app.render();
            Files.createDirectories(png.getParent());
            Files.write(png, Png.encode(frame.pixels()));
            Files.writeString(tree, frame.tree());
            written.add(png);
            written.add(tree);
            String clipboard = each.app.clipboard().read();
            if (each == desk.first && !clipboard.isEmpty()) {
              Path copied = folder.resolve(name + ".clipboard.txt").normalize();
              Files.writeString(copied, clipboard);
              written.add(copied);
            }
          }
        }
        default ->
          throw line.fail("There is no step " + step
              + ". The steps are size, window, close, press, down, up, move, leave,"
              + " wheel, scroll, key, type, compose, clipboard, dialog, drop, focus, advance,"
              + " await, frame and snap.");
      }
      if (step.equals("frame")) {
        pending = null;
      } else if (!step.equals("snap")
          && !step.equals("clipboard")
          && !step.equals("window")
          && !step.equals("dialog")
          && pending == null) {
        pending = line;
      }
    }
    Answer unused = desk.answers.peek();
    if (unused != null) {
      throw unused.line.fail(
          "No dialog opened after this answer, so it answered nothing. A `dialog`"
              + " step answers the next dialog the app opens, in a frame after it: write it before the"
              + " step whose handler opens the dialog, as before `press #open` and its `frame`.");
    }
    return written;
  }

  /**
   * A {@code dialog} step's answer to the next dialog: files, a cancel, an error, or a message
   * box's button.
   */
  private record Answer(Line line, List<Path> paths, int filter, String error, String button) {
    boolean cancels() {
      return paths.isEmpty() && error == null && button == null;
    }
  }

  private static Answer answer(Line line, List<String> args) {
    if (args.size() == 1 && args.getFirst().equals("cancel")) {
      return new Answer(line, List.of(), -1, null, null);
    }
    if (args.size() == 2
        && (args.getFirst().equals("error") || args.getFirst().equals("button"))
        && quoted(args.getLast())) {
      String text = unescape(strip(args.getLast()));
      return args.getFirst().equals("error")
          ? new Answer(line, List.of(), -1, text, null)
          : new Answer(line, List.of(), -1, null, text);
    }
    List<String> rest = new ArrayList<>(args);
    int filter = -1;
    if (rest.size() >= 3 && rest.get(rest.size() - 2).equals("filter")) {
      if (!INDEX.matcher(rest.getLast()).matches()) {
        throw line.usage("dialog");
      }
      filter = Integer.parseInt(rest.getLast());
      rest.subList(rest.size() - 2, rest.size()).clear();
    }
    List<Path> paths = new ArrayList<>();
    for (String word : rest) {
      if (!quoted(word)) {
        throw line.usage("dialog");
      }
      paths.add(path(line, strip(word)));
    }
    if (paths.isEmpty()) {
      throw line.usage("dialog");
    }
    return new Answer(line, paths, filter, null, null);
  }

  private static boolean quoted(String word) {
    return word.length() >= 3 && word.startsWith("\"") && word.endsWith("\"");
  }

  private static String strip(String quoted) {
    return quoted.substring(1, quoted.length() - 1);
  }

  private static Path path(Line line, String text) {
    try {
      return Path.of(unescape(text));
    } catch (InvalidPathException e) {
      throw line.fail(text + " is not a path: " + e.getReason() + ".");
    }
  }

  private static void needsFrame(Line line, App<?> app, String consequence) {
    if (app.tree() == null) {
      throw line.fail(
          "No frame has run yet, so " + consequence + ". Add `frame` before this line.");
    }
  }

  /** The bit of a modifier named in a script, or 0 if the word names none. */
  private static int modifier(String word) {
    return switch (word) {
      case "shift" -> Key.SHIFT;
      case "control" -> Key.CONTROL;
      case "alt" -> Key.ALT;
      case "meta" -> Key.META;
      default -> 0;
    };
  }

  /** What to say about a key name that {@link Key#isName} refuses. */
  private static String unknownKey(String name) {
    String upper = name.toUpperCase(Locale.ROOT);
    for (String named : Key.NAMED) {
      if (named.equalsIgnoreCase(name)) {
        return "There is no key " + name + ". Write " + named + ".";
      }
    }
    if (Key.isName(upper)) {
      return "There is no key " + name + ". Keys that type a character are named by it in upper"
          + " case, with or without shift: write " + upper + ".";
    }
    return "There is no key " + name + ". Keys have the names of the web's key values, such as"
        + " Enter, Tab, Space, Escape, Backspace, ArrowDown, PageUp, Home, F5 and Numpad1, and"
        + " keys that type a character are named by it in upper case, such as A, 1 and -. The"
        + " Javadoc of com.rethinkj.ui.Key lists them all.";
  }

  /**
   * An element of the last frame with the window position of its border box, and the centre of
   * the part of it that its clips leave.
   */
  private record Found(El el, float left, float top, float x, float y) {}

  /** A target's point in logical pixels of the window it was found in. */
  private record Aim(Win win, float x, float y) {
    /** The point in logical pixels of {@code to}, through the screen if it is another window. */
    float[] in(Win to) {
      if (to == win) {
        return new float[] {x, y};
      }
      return new float[] {
        (win.x + x * win.scale - to.x) / to.scale, (win.y + y * win.scale - to.y) / to.scale
      };
    }
  }

  /** A window of a script: its app and its place on the script's screen, in physical pixels. */
  private static final class Win implements Windows.Handle {
    final Desk desk;
    final App<?> app;
    final String title;
    int x;
    int y;
    float width;
    float height;
    float scale = 1;
    boolean sized;
    boolean open = true;
    boolean fresh; // opened since the last frame step
    boolean dropTarget = true;
    Runnable onClose;

    Win(Desk desk, App<?> app, String title) {
      this.desk = desk;
      this.app = app;
      this.title = title;
    }

    void resize(float width, float height, float scale) {
      this.width = width;
      this.height = height;
      this.scale = scale;
      sized = true;
      app.resize(width, height, scale);
    }

    /** A request to close, as from the close button. */
    void request() {
      if (onClose != null) {
        app.request(onClose);
      } else {
        close();
      }
    }

    @Override
    public App<?> app() {
      return app;
    }

    @Override
    public String title() {
      return title;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
      desk.front.remove(this);
    }

    @Override
    public void raise() {
      if (open) {
        desk.front.remove(this);
        desk.front.addFirst(this);
      }
    }

    @Override
    public void moveTo(int x, int y) {
      this.x = x;
      this.y = y;
    }

    @Override
    public int x() {
      return x;
    }

    @Override
    public int y() {
      return y;
    }

    @Override
    public float width() {
      return width;
    }

    @Override
    public float height() {
      return height;
    }

    @Override
    public float screenScale() {
      return scale;
    }

    @Override
    public void onCloseRequest(Runnable handler) {
      onClose = handler;
    }

    @Override
    public void dropTarget(boolean on) {
      dropTarget = on;
    }

    @Override
    public com.rethinkj.ui.window.Window window() {
      return null;
    }
  }

  /**
   * The windows of a script, which its apps get as {@link App#windows()}: the app the script runs,
   * named {@code main}, and those its handlers open, named by their titles. The steps go to the
   * window that {@code window} chose, the first one at first and again when the chosen one
   * closes; the pointer's steps between {@code down} and {@code up} go to the window the press
   * began in, as the system's capture of the pointer has it.
   */
  private static final class Desk implements Windows {
    final List<Win> wins = new ArrayList<>(); // in the order they opened
    final List<Win> front = new ArrayList<>(); // front first
    final Win first;
    Win chosen;
    Win held; // the window a press began in, until its release
    Win pointer; // the window the pointer was last moved in
    Win dropping; // the window another application's drag is over, from `drop over`
    final ArrayDeque<Answer> answers = new ArrayDeque<>(); // of the steps `dialog`, in order
    final List<String> unanswered = new ArrayList<>(); // dialogs that found no answer
    String wrong; // what was wrong with an answer, for the frame step to say

    /** The dialogs of the script's windows: each takes the next answer of a `dialog` step. */
    final Dialogs dialogs = new Dialogs() {
      @Override
      public void file(
          FileDialog dialog, Consumer<FileDialog.Choice> chosen, Consumer<String> failed) {
        Answer answer = answers.poll();
        if (answer == null) {
          unanswered.add(dialog.toString());
        } else if (answer.error != null) {
          failed.accept(answer.error);
        } else if (answer.button != null) {
          wrong = answer.line.number + " answers with a message box's button, but the app opened "
              + dialog + ". Write `dialog \"<path>\"` or `dialog cancel` for it.";
        } else if (answer.paths.size() > 1 && !dialog.multiple()) {
          wrong = answer.line.number + " chooses " + answer.paths.size() + " files, but the app"
              + " opened " + dialog + ", which chooses one. Write one path.";
        } else if (answer.filter >= dialog.filters().size()) {
          wrong = answer.line.number + " chooses filter " + answer.filter + ", but the app opened "
              + dialog + ", which has " + dialog.filters().size() + ". Count them from 0.";
        } else {
          chosen.accept(new FileDialog.Choice(answer.paths, answer.filter));
        }
      }

      @Override
      public void message(MessageBox box, IntConsumer pressed, Consumer<String> failed) {
        Answer answer = answers.poll();
        if (answer == null) {
          unanswered.add(box.toString());
        } else if (answer.error != null) {
          failed.accept(answer.error);
        } else if (answer.cancels()) {
          pressed.accept(box.cancelButton());
        } else if (answer.button == null || !box.buttons().contains(answer.button)) {
          wrong = answer.line.number + " does not answer " + box + ". Write `dialog button"
              + " \"<text>\"` with one of them, or `dialog cancel`.";
        } else {
          pressed.accept(box.buttons().indexOf(answer.button));
        }
      }
    };

    /** Fails at the frame step if a dialog of its frame found no answer, or a wrong one. */
    void answered(Line line) {
      if (wrong != null) {
        String message = "The answer on line " + wrong;
        wrong = null;
        unanswered.clear();
        throw line.fail(message);
      }
      if (!unanswered.isEmpty()) {
        String opened = String.join("; then ", unanswered);
        unanswered.clear();
        throw line.fail("The app opened " + opened + " in this frame, and no `dialog` step"
            + " answered it. A script opens no dialog of the system: write `dialog \"<path>\"`,"
            + " `dialog cancel`, `dialog error \"<text>\"` or, for a message box, `dialog button"
            + " \"<text>\"` before the step that opens it. The answer arrives as a posted task:"
            + " `await` and `frame` after this frame show it.");
      }
    }

    Desk(App<?> app) {
      first = new Win(this, app, "main");
      wins.add(first);
      front.add(first);
      chosen = first;
      app.windows(this);
      app.dialogs(dialogs);
    }

    Win current() {
      if (!chosen.open) {
        chosen = open().isEmpty() ? first : open().getFirst();
      }
      return chosen;
    }

    List<Win> open() {
      return wins.stream().filter(w -> w.open).toList();
    }

    void select(Line line, String name) {
      for (Win w : open()) {
        if (w.title.equals(name)) {
          chosen = w;
          return;
        }
      }
      List<String> names = open().stream().map(w -> "\"" + w.title + "\"").toList();
      throw line.fail("There is no open window " + name + ". The open windows are "
          + String.join(", ", names) + "; `main` is the first.");
    }

    /**
     * Where a target of a pointer's step is: in the chosen window, in the window that {@code in
     * <window>} names, or at a point of the screen ({@code screen <x>,<y>}).
     */
    Aim aim(Line line, List<String> target, String consequence) {
      if (target.size() == 2 && target.getFirst().equals("screen")) {
        Matcher position = POSITION.matcher(target.getLast());
        if (!position.matches()) {
          throw line.fail("Write `screen <x>,<y>`, a point of the screen in physical pixels.");
        }
        float sx = Float.parseFloat(position.group(1));
        float sy = Float.parseFloat(position.group(2));
        Win at = held != null ? held : under(sx, sy, false);
        if (at == null) {
          throw line.fail(
              "No window lies at " + target.getLast() + " on the screen, so " + consequence + ".");
        }
        return new Aim(at, (sx - at.x) / at.scale, (sy - at.y) / at.scale);
      }
      Win in = current();
      List<String> rest = target;
      if (target.size() > 2 && target.getFirst().equals("in")) {
        Win before = chosen;
        select(line, unquote(target.get(1)));
        in = chosen;
        chosen = before;
        rest = target.subList(2, target.size());
      }
      if (in.app.tree() == null) {
        throw line.fail(
            "No frame has run yet, so " + consequence + ". Add `frame` before this" + " line.");
      }
      float[] at = line.point(rest, in.app.tree());
      return new Aim(in, at[0], at[1]);
    }

    /** The pointer moves into {@code to}: the window it was in before sees it leave. */
    void pointerTo(Win to) {
      if (pointer != null && pointer != to && pointer.open) {
        pointer.app.pointerLeave();
      }
      pointer = to;
    }

    /** The topmost open window whose content holds a point of the screen, or null. */
    Win under(float sx, float sy, boolean drops) {
      for (Win w : front) {
        float x = (sx - w.x) / w.scale;
        float y = (sy - w.y) / w.scale;
        if (w.open && (!drops || w.dropTarget) && x >= 0 && y >= 0 && x < w.width && y < w.height) {
          return w;
        }
      }
      return null;
    }

    /**
     * Runs a frame in every open window, those that open meanwhile included, and again in those
     * that the handlers of another window of the same state invalidated after their frame, for up
     * to three rounds; see
     * {@link com.rethinkj.ui.window.WindowLoop#run}.
     */
    void frame() {
      Set<Win> due = new HashSet<>(open());
      for (int round = 0; round < 3 && !due.isEmpty(); round++) {
        Set<Win> again = new HashSet<>();
        for (int i = 0; i < wins.size(); i++) {
          Win w = wins.get(i);
          if (!w.open || !due.contains(w) && !w.fresh) {
            continue;
          }
          w.fresh = false;
          w.app.frame();
          if (w.app.ranHandlers()) {
            for (int j = 0; j < wins.size(); j++) {
              Win other = wins.get(j);
              if (other != w && other.open && !other.fresh && other.app.state() == w.app.state()) {
                other.app.invalidate();
                (j < i ? again : due).add(other);
              }
            }
          }
        }
        due = again;
      }
    }

    @Override
    public Handle open(
        String title,
        float width,
        float height,
        com.rethinkj.ui.window.Window.Options options,
        App<?> app) {
      Objects.requireNonNull(title, "title");
      if (of(app) != null) {
        throw new IllegalArgumentException("The app runs in a window already");
      }
      Win w = new Win(this, app, title);
      // A script's images do not depend on the platform: its windows draw text as the first.
      app.textRendering(first.app.textRendering());
      app.clipboard(first.app.clipboard());
      app.holdPosts = true;
      app.windows(this);
      app.dialogs(dialogs);
      w.resize(width, height, first.scale);
      int n = wins.size();
      if (options.isPositioned()) {
        w.moveTo(options.x(), options.y());
      } else {
        w.moveTo(first.x + 32 * n, first.y + 32 * n);
      }
      w.fresh = true;
      wins.add(w);
      front.addFirst(w);
      return w;
    }

    @Override
    public List<Handle> all() {
      return List.copyOf(open());
    }

    @Override
    public Handle of(App<?> app) {
      for (Win w : wins) {
        if (w.app == app && w.open) {
          return w;
        }
      }
      return null;
    }

    @Override
    public Place place(App<?> app, float x, float y) {
      Win from = (Win) of(app);
      if (from == null) {
        return null;
      }
      float sx = from.x + x * from.scale;
      float sy = from.y + y * from.scale;
      Win at = under(sx, sy, true);
      return at == null
          ? new Place(sx, sy, null, Float.NaN, Float.NaN)
          : new Place(sx, sy, at, (sx - at.x) / at.scale, (sy - at.y) / at.scale);
    }
  }

  /** A window's name without the quotes around it, if it has them. */
  private static String unquote(String word) {
    return word.length() > 1 && word.startsWith("\"") && word.endsWith("\"")
        ? unescape(word.substring(1, word.length() - 1))
        : word;
  }

  /** A title as a part of a file name: lower case, words joined by hyphens. */
  static String slug(String title) {
    String slug =
        title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    return slug.isEmpty() ? "window" : slug;
  }

  /** One line of a script, without the space around it. */
  private record Line(int number, String text) {

    Failure fail(String message) {
      return fail(message, null);
    }

    Failure fail(String message, Throwable cause) {
      return new Failure("line " + number + ": " + text + "\n  " + message, cause);
    }

    Failure usage(String step) {
      return fail("Write "
          + switch (step) {
            case "size" ->
              "`size <width> <height>` or `size <width> <height> scale <factor>`, with"
                  + " numbers above 0, for example `size 200 120 scale 2`, and `at <x>,<y>`"
                  + " after it for the window's place on the screen, in physical pixels.";
            case "window" ->
              "`window <name>`: `main` for the first window, else a window's title, quoted"
                  + " if it has spaces, as in `window \"Note 2\"`.";
            case "close" -> "`close` with nothing after it.";
            case "await" ->
              "`await`, or `await <seconds>` with a number above 0 to wait longer than "
                  + (int) AWAIT + " seconds, for example `await 30`; `await progress` or `await"
                  + " progress <seconds>` to wait for a report of work that still runs.";
            case "advance" ->
              "`advance <seconds>`, with a number of 0 or more, for example `advance 0.04`.";
            case "frame", "leave" -> "`" + step + "` with nothing after it.";
            case "key" ->
              "`key <name>`, followed by any of shift, control, alt and meta that are held,"
                  + " for example `key Enter` or `key Tab shift` for Shift+Tab, and `down` or"
                  + " `up` for one half of the press, as `key Space down` to hold it.";
            case "type" ->
              "`type \"<text>\"`, with \\\" for a quote and \\\\ for a backslash in the"
                  + " text, for example `type \"hello\"`.";
            case "compose" ->
              "`compose \"<text>\"`, the composition of an input method, quoted as for"
                  + " `type`, for example `compose \"ni\"`; `compose \"\"` ends it.";
            case "clipboard" ->
              "`clipboard \"<text>\"`, the text to put on the clipboard, quoted as for"
                  + " `type`, for example `clipboard \"SELECT 1\"`.";
            case "press", "down" ->
              "`" + step + " <target>`, then `clicks <n>` for the second or third press in a"
                  + " row and any of shift, control, alt and meta that are held, for example"
                  + " `" + step + " #name clicks 2` or `" + step + " #name shift`, or `"
                  + step + " <target> right` for the secondary button, without them. A"
                  + " target is " + TARGET + ".";
            case "up" ->
              "`up <target>`, or `up <target> right` for the secondary button. A target is "
                  + TARGET + ".";
            case "wheel" ->
              "`wheel <target> <deltaX> <deltaY>`, with distances in logical pixels that are"
                  + " positive to scroll down or right, for example `wheel #list 0 40`. A"
                  + " target is " + TARGET + ".";
            case "scroll" ->
              "`scroll <target> row <index>`, which scrolls the list of rows at the target so"
                  + " that the row with that index, counted from 0, is at its top, for"
                  + " example `scroll #list row 5000`. A target is " + TARGET + ".";
            case "dialog" ->
              "`dialog \"<path>\"` with one or more quoted paths, and `filter <n>` after"
                  + " them for the filter chosen, counted from 0, to answer the next file"
                  + " dialog the app opens; `dialog cancel` to cancel it or close a message"
                  + " box; `dialog button \"<text>\"` to press a message box's button;"
                  + " `dialog error \"<text>\"` for a dialog that fails. Write it before the"
                  + " step that opens the dialog.";
            case "drop" ->
              "`drop <target> \"<path>\"` with one or more quoted paths, the files another"
                  + " application drops there; `drop <target> text \"<text>\"` for text;"
                  + " `drop over <target>` for such a drag over the target, not yet released;"
                  + " `drop leave` for one that leaves. A target is " + TARGET + ".";
            case "snap" ->
              "`snap <name>`, for example `snap after-press`, to write after-press.png and"
                  + " after-press.tree.txt into the output folder.";
            default ->
              "`" + step + " <target>`. A target is " + TARGET + ", or in another"
                  + " window `in <window> <target>`, or a point of the screen `screen <x>,<y>`.";
          });
    }

    /**
     * The words of the line. Quotes group a label with spaces. A {@code #} starts a comment at the
     * start of the line or before a space; elsewhere it starts a key, as in {@code #switch}.
     */
    List<String> words() {
      List<String> words = new ArrayList<>();
      int i = 0;
      while (true) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
          i++;
        }
        if (i == text.length()
            || text.charAt(i) == '#'
                && (words.isEmpty()
                    || i + 1 == text.length()
                    || Character.isWhitespace(text.charAt(i + 1)))) {
          return words;
        }
        int start = i;
        boolean quoted = false;
        for (; i < text.length() && (quoted || !Character.isWhitespace(text.charAt(i))); i++) {
          if (text.charAt(i) == '"') {
            quoted = !quoted;
          } else if (text.charAt(i) == '\\' && quoted) {
            i++;
          }
        }
        if (quoted) {
          throw fail(
              "A quote is not closed. Write a label as label=\"<text>\", with \\\" for a quote and"
                  + " \\\\ for a backslash in the text.");
        }
        words.add(text.substring(start, i));
      }
    }

    double number(String word, boolean aboveZero, String step) {
      if (!NUMBER.matcher(word).matches() || aboveZero && Double.parseDouble(word) == 0) {
        throw usage(step);
      }
      return Double.parseDouble(word);
    }

    /** The point a target stands for, in logical pixels: a position, or an element's centre. */
    float[] point(List<String> target, El root) {
      String text = String.join(" ", target);
      Matcher position = POSITION.matcher(text);
      if (position.matches()) {
        float x = Float.parseFloat(position.group(1));
        return new float[] {x, Float.parseFloat(position.group(2))};
      }
      List<Predicate<El>> tests = new ArrayList<>();
      List<Predicate<El>> stateTests = new ArrayList<>();
      List<String> others = new ArrayList<>(); // the words that are not states
      for (String word : target) {
        if (STATE.matcher(word).matches()) {
          String state = " " + word.replace("=true", "") + " ";
          stateTests.add(el -> states(el).contains(state));
          continue;
        }
        if (word.length() > 7 && word.startsWith("value=\"") && word.endsWith("\"")) {
          String value = unescape(word.substring(7, word.length() - 1));
          stateTests.add(el -> value.equals(el.value()));
          continue;
        }
        others.add(word);
        if (word.length() > 1 && word.startsWith("#")) {
          String key = word.substring(1);
          tests.add(el -> key.equals(el.key()));
        } else if (word.length() > 5 && word.startsWith("role=")) {
          String role = word.substring(5);
          tests.add(el -> role.equals(el.role()));
        } else if (word.length() > 7 && word.startsWith("label=\"") && word.endsWith("\"")) {
          String label = unescape(word.substring(7, word.length() - 1));
          tests.add(el -> label.equals(el.label()));
        } else if (word.length() > 6 && word.startsWith("text=\"") && word.endsWith("\"")) {
          String string = unescape(word.substring(6, word.length() - 1));
          tests.add(el -> el.text() != null && string.equals(el.text().string()));
        } else {
          throw fail(word + " is not a target. A target is " + TARGET + ".");
        }
      }
      List<Predicate<El>> rest = List.copyOf(tests);
      tests.addAll(stateTests);
      List<Found> all = new ArrayList<>();
      List<Found> clipped = new ArrayList<>();
      walk(root, all, clipped);
      Predicate<Found> matching = found -> tests.stream().allMatch(t -> t.test(found.el));
      List<Found> matches = all.stream().filter(matching).toList();
      if (matches.size() == 1) {
        return new float[] {matches.getFirst().x, matches.getFirst().y};
      }
      if (matches.isEmpty() && clipped.stream().anyMatch(matching)) {
        throw fail(
            "What matches " + text + " is clipped away in the last frame, for example scrolled"
                + " out of its scroll area, so it cannot be pressed or scrolled. Bring it into view"
                + " first, for example with `wheel <target> <deltaX> <deltaY>` over its scroll"
                + " area." + lists(all));
      }
      if (matches.isEmpty() && !stateTests.isEmpty() && !others.isEmpty()) {
        // Without its states the selector matches: those elements show which states they have.
        List<Found> near = all.stream()
            .filter(found -> rest.stream().allMatch(t -> t.test(found.el)))
            .toList();
        if (!near.isEmpty()) {
          StringBuilder lines = new StringBuilder();
          for (Found found : near.subList(0, Math.min(near.size(), 20))) {
            found.el.describe(lines.append("    "), found.left, found.top);
          }
          throw fail(near.size() + " elements of the last frame match " + String.join(" ", others)
              + ", but none has the states of " + text + ":\n" + lines
              + (near.size() > 20 ? "    and " + (near.size() - 20) + " more\n" : "")
              + "  Their lines show the states they have after the rectangle." + lists(all));
        }
      }
      if (matches.isEmpty()) {
        Set<String> keys = new TreeSet<>();
        Set<String> roles = new TreeSet<>();
        Set<String> labels = new TreeSet<>();
        Set<String> texts = new TreeSet<>();
        Set<String> states = new TreeSet<>();
        for (Found found : all) {
          if (found.el.key() != null) {
            keys.add("#" + found.el.key());
          }
          if (found.el.role() != null) {
            roles.add("role=" + found.el.role());
          }
          if (found.el.label() != null) {
            labels.add("label=" + El.quote(found.el.label()));
          }
          if (found.el.text() != null) {
            texts.add("text=" + El.quoteText(found.el.text().string()));
          }
          for (String state : states(found.el).strip().split(" ")) {
            if (!state.isEmpty() && !state.startsWith("pos=")) { // a position per row says little
              states.add(state);
            }
          }
        }
        throw fail("No element of the last frame matches " + text + ". It has\n"
            + "    keys:   " + listing(keys) + "\n"
            + "    roles:  " + listing(roles) + "\n"
            + "    labels: " + listing(labels) + "\n"
            + (texts.isEmpty() ? "" : "    texts:  " + listing(texts) + "\n")
            + (states.isEmpty() ? "" : "    states: " + listing(states) + "\n")
            + "  Select with these, or press a position x,y. The .tree.txt of a snap shows"
            + " every element with its rectangle." + lists(all));
      }
      StringBuilder rows = new StringBuilder();
      for (Found found : matches) {
        found.el.describe(rows.append("    "), found.left, found.top);
      }
      throw fail(matches.size() + " elements match " + text + ":\n" + rows
          + "  Add #key, role=, label= or a state to the selector until it matches one, or press"
          + " a position x,y inside one of these rectangles.");
    }
  }

  /** As the next method, for a tree and then each overlay shown above it, free of other clips. */
  private static void walk(El root, List<Found> all, List<Found> clipped) {
    float far = Float.POSITIVE_INFINITY;
    walk(root, 0, 0, -far, -far, far, far, all, clipped);
    for (El overlay : root.shownOverlays()) {
      walk(overlay, 0, 0, -far, -far, far, far, all, clipped);
    }
  }

  /**
   * Collects the elements that take input into {@code all}, and those that their ancestors clip
   * away entirely into {@code clipped}; one that is not placed, invisible or leaving hides its
   * children too. The clip is a rectangle in window coordinates.
   */
  private static void walk(
      El el,
      float parentX,
      float parentY,
      float clipLeft,
      float clipTop,
      float clipRight,
      float clipBottom,
      List<Found> all,
      List<Found> clipped) {
    if (!el.isPlaced() || el.opacity() == 0 || el.isExiting()) {
      return;
    }
    float left = parentX + el.x();
    float top = parentY + el.y();
    float right = left + el.width();
    float bottom = top + el.height();
    // shortcut: the visible part is taken as a rectangle, without the rounded corners of the clips,
    // so an element that only shows in such a corner may be missed; follow the curves if it shows.
    float visibleLeft = Math.max(left, clipLeft);
    float visibleTop = Math.max(top, clipTop);
    float visibleRight = Math.min(right, clipRight);
    float visibleBottom = Math.min(bottom, clipBottom);
    Found found = new Found(
        el, left, top, (visibleLeft + visibleRight) / 2, (visibleTop + visibleBottom) / 2);
    List<Text.Box> rows = el.rangeBoxes();
    if (rows != null && !rows.isEmpty()) { // a range: the middle of its first row
      Text.Box row = rows.getFirst();
      found = new Found(
          el, left, top, left + row.x() + row.width() / 2, top + row.y() + row.height() / 2);
    }
    boolean visible = left < clipRight && right > clipLeft && top < clipBottom && bottom > clipTop;
    (visible ? all : clipped).add(found);
    Style s = el.style();
    if (s.overflowX != Style.Overflow.VISIBLE || s.overflowY != Style.Overflow.VISIBLE) {
      boolean empty = !(visibleRight > visibleLeft && visibleBottom > visibleTop);
      float far = Float.POSITIVE_INFINITY;
      clipLeft = empty ? far : visibleLeft; // an empty clip leaves nothing below visible
      clipTop = empty ? far : visibleTop;
      clipRight = empty ? -far : visibleRight;
      clipBottom = empty ? -far : visibleBottom;
    }
    for (El child : el.children()) {
      walk(child, left, top, clipLeft, clipTop, clipRight, clipBottom, all, clipped);
    }
  }

  /**
   * For an error about a selector: the virtual lists of the last frame and how to bring one of
   * their rows into existence, or nothing if there are none.
   */
  private static String lists(List<Found> all) {
    StringBuilder rows = new StringBuilder();
    String example = null;
    for (Found found : all) {
      if (found.el.isVirtualList()) {
        found.el.describe(rows.append("    "), found.left, found.top);
        if (example == null) {
          example = found.el.key() != null
              ? "#" + found.el.key()
              : Math.round(found.x) + "," + Math.round(found.y);
        }
      }
    }
    if (example == null) {
      return "";
    }
    return "\n  A list of rows has only the rows near its visible part as elements, and a selector"
        + " finds only those. The last frame has these lists, with the rows that exist and how many"
        + " there are:\n" + rows + "  To reach another row, scroll its list first: `scroll "
        + example + " row <index>` puts that row at the top of the list at the next `frame`.";
  }

  /** The states of {@link El#dump} but the value, with a space before each and after the last. */
  private static String states(El el) {
    StringBuilder out = new StringBuilder();
    el.states(out);
    return out.append(' ').toString();
  }

  private static String listing(Set<String> words) {
    return words.isEmpty() ? "none" : String.join(" ", words);
  }

  /** A character as {@link El#quote} writes one of a private use area: <code>&#92;u{E0EB}</code>. */
  private static final Pattern CODE_POINT =
      Pattern.compile("u\\{(10\\p{XDigit}{4}|\\p{XDigit}{1,5})}");

  /** Undoes {@link El#quote}. {@link Line#words} made sure a character follows each backslash. */
  private static String unescape(String text) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\') {
        Matcher codePoint = CODE_POINT.matcher(text).region(++i, text.length());
        if (codePoint.lookingAt()) {
          out.appendCodePoint(Integer.parseInt(codePoint.group(1), 16));
          i = codePoint.end() - 1;
        } else {
          c = text.charAt(i);
          out.append(c == 'n' ? '\n' : c == 't' ? '\t' : c);
        }
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
