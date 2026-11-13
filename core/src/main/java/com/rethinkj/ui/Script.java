package com.rethinkj.ui;

import com.rethinkj.ui.render.Png;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 * </pre>
 *
 * <p>{@code snap} also writes {@code <name>.clipboard.txt} with the text on the app's clipboard,
 * when there is any.
 *
 * <p>Only {@code frame} runs a frame. As in a window, input and {@code advance} between frames
 * change nothing on screen until the next one, so {@code snap} refuses to write a frame that does
 * not show the steps before it. AGENTS.md describes the format.
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
  private static final Pattern STATE =
      Pattern.compile(
          "disabled|readonly|password|selected|current|checked(=true|=false|=mixed)?|expanded(=true|=false)?"
              + "|level=[1-9]\\d{0,9}|pos=[1-9]\\d{0,9}/[1-9]\\d{0,9}|sort=(ascending|descending)");
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
    Path folder = out.toAbsolutePath().normalize();
    List<Path> written = new ArrayList<>();
    Map<Path, Integer> snapped = new HashMap<>();
    boolean sized = false;
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
      switch (step) {
        case "size" -> {
          boolean scaled = args.size() == 4 && args.get(2).equals("scale");
          if (args.size() != 2 && !scaled) {
            throw line.usage(step);
          }
          app.resize(
              (float) line.number(args.get(0), true, step),
              (float) line.number(args.get(1), true, step),
              scaled ? (float) line.number(args.get(3), true, step) : 1);
          sized = true;
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
            } else if (!step.equals("up") && target.size() > 1
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
          if (app.tree() == null) {
            throw line.fail(
                "No frame has run yet, so there is nothing to press. Add `frame` before this"
                    + " line.");
          }
          float[] at = line.point(target, app.tree());
          if (!step.equals("up")) {
            if (right) {
              app.secondaryDown(at[0], at[1]);
            } else {
              app.pointerDown(at[0], at[1], clicks, modifiers);
            }
          }
          if (!step.equals("down")) {
            if (right) {
              app.secondaryUp(at[0], at[1]);
            } else {
              app.pointerUp(at[0], at[1]);
            }
          }
        }
        case "move" -> {
          if (args.isEmpty()) {
            throw line.usage(step);
          }
          needsFrame(line, app, "there is nothing to move over");
          float[] at = line.point(args, app.tree());
          app.pointerMove(at[0], at[1]);
        }
        case "leave" -> {
          if (!args.isEmpty()) {
            throw line.usage(step);
          }
          app.pointerLeave();
        }
        case "wheel" -> {
          if (args.size() < 3
              || !SIGNED.matcher(args.get(args.size() - 2)).matches()
              || !SIGNED.matcher(args.getLast()).matches()) {
            throw line.usage(step);
          }
          if (app.tree() == null) {
            throw line.fail(
                "No frame has run yet, so there is nothing to scroll. Add `frame` before this"
                    + " line.");
          }
          float[] at = line.point(args.subList(0, args.size() - 2), app.tree());
          app.wheel(
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
            throw line.fail(
                "The list has " + list.rowCount() + " rows, "
                    + (list.rowCount() == 0 ? "so none to scroll to." : "from 0 to "
                        + (list.rowCount() - 1) + ". Write a row in that range."));
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
          for (String word : args.subList(1, args.size())) {
            int modifier = modifier(word);
            if (modifier == 0) {
              throw line.usage(step);
            }
            modifiers |= modifier;
          }
          needsFrame(line, app, "nothing can take the key");
          Key key = new Key(name, modifiers, false);
          app.keyDown(key);
          app.keyUp(key);
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
          app.advance(line.number(args.getFirst(), false, step));
        }
        case "frame" -> {
          if (!args.isEmpty()) {
            throw line.usage(step);
          }
          if (!sized) {
            throw line.fail(
                "The window has no size yet. Add `size <width> <height>` before this line, for"
                    + " example `size 200 120`.");
          }
          try {
            app.frame();
          } catch (RuntimeException e) {
            throw line.fail("The app threw " + e + " in this frame; its stack trace follows.", e);
          }
        }
        case "snap" -> {
          if (args.size() != 1) {
            throw line.usage(step);
          }
          String name = args.getFirst();
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
          if (app.tree() == null) {
            throw line.fail(
                "No frame has run yet, so there is nothing to snap. Add `frame` before this line.");
          }
          if (pending != null) {
            throw line.fail(
                "The last frame does not show line " + pending.number + " (" + pending.text
                    + ") yet. Add `frame` before this line: only `frame` runs the handlers,"
                    + " rebuilds the view and moves what is animated.");
          }
          Headless.Frame frame = app.render();
          Files.createDirectories(png.getParent());
          Files.write(png, Png.encode(frame.pixels()));
          Files.writeString(tree, frame.tree());
          written.add(png);
          written.add(tree);
          String clipboard = app.clipboard().read();
          if (!clipboard.isEmpty()) {
            Path copied = folder.resolve(name + ".clipboard.txt").normalize();
            Files.writeString(copied, clipboard);
            written.add(copied);
          }
        }
        default ->
            throw line.fail(
                "There is no step " + step
                    + ". The steps are size, press, down, up, move, leave, wheel, scroll, key,"
                    + " type, compose, clipboard, focus, advance, frame and snap.");
      }
      if (step.equals("frame")) {
        pending = null;
      } else if (!step.equals("snap") && !step.equals("clipboard") && pending == null) {
        pending = line;
      }
    }
    return written;
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

  /** One line of a script, without the space around it. */
  private record Line(int number, String text) {

    Failure fail(String message) {
      return fail(message, null);
    }

    Failure fail(String message, Throwable cause) {
      return new Failure("line " + number + ": " + text + "\n  " + message, cause);
    }

    Failure usage(String step) {
      return fail(
          "Write "
              + switch (step) {
                case "size" ->
                    "`size <width> <height>` or `size <width> <height> scale <factor>`, with"
                        + " numbers above 0, for example `size 200 120 scale 2`.";
                case "advance" ->
                    "`advance <seconds>`, with a number of 0 or more, for example `advance 0.04`.";
                case "frame", "leave" -> "`" + step + "` with nothing after it.";
                case "key" ->
                    "`key <name>`, followed by any of shift, control, alt and meta that are held,"
                        + " for example `key Enter` or `key Tab shift` for Shift+Tab.";
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
                case "snap" ->
                    "`snap <name>`, for example `snap after-press`, to write after-press.png and"
                        + " after-press.tree.txt into the output folder.";
                default -> "`" + step + " <target>`. A target is " + TARGET + ".";
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
          throw fail(
              near.size() + " elements of the last frame match " + String.join(" ", others)
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
            texts.add("text=" + El.quote(found.el.text().string()));
          }
          for (String state : states(found.el).strip().split(" ")) {
            if (!state.isEmpty() && !state.startsWith("pos=")) { // a position per row says little
              states.add(state);
            }
          }
        }
        throw fail(
            "No element of the last frame matches " + text + ". It has\n"
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
      throw fail(
          matches.size() + " elements match " + text + ":\n" + rows
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
      El el, float parentX, float parentY, float clipLeft, float clipTop, float clipRight,
      float clipBottom, List<Found> all, List<Found> clipped) {
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
    Found found =
        new Found(
            el, left, top, (visibleLeft + visibleRight) / 2, (visibleTop + visibleBottom) / 2);
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
          out.append(c == 'n' ? '\n' : c);
        }
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
