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
 * advance 0.04
 * frame
 * snap moving                        # writes moving.png and moving.tree.txt
 * </pre>
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
      "a position x,y in logical pixels such as 90,60, or a selector of #key, role=<role> and"
          + " label=\"<text>\" such as role=switch label=\"Lights\"";
  private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?");
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
          if (args.isEmpty()) {
            throw line.usage(step);
          }
          if (app.tree() == null) {
            throw line.fail(
                "No frame has run yet, so there is nothing to press. Add `frame` before this"
                    + " line.");
          }
          float[] at = line.point(args, app.tree());
          if (!step.equals("up")) {
            app.pointerDown(at[0], at[1]);
          }
          if (!step.equals("down")) {
            app.pointerUp(at[0], at[1]);
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
        }
        default ->
            throw line.fail(
                "There is no step " + step
                    + ". The steps are size, press, down, up, advance, frame and snap.");
      }
      if (step.equals("frame")) {
        pending = null;
      } else if (!step.equals("snap") && pending == null) {
        pending = line;
      }
    }
    return written;
  }

  /** An element of the last frame with the window position of its border box. */
  private record Found(El el, float left, float top) {}

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
                case "frame" -> "`frame` with nothing after it.";
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
      for (String word : target) {
        if (word.length() > 1 && word.startsWith("#")) {
          String key = word.substring(1);
          tests.add(el -> key.equals(el.key()));
        } else if (word.length() > 5 && word.startsWith("role=")) {
          String role = word.substring(5);
          tests.add(el -> role.equals(el.role()));
        } else if (word.length() > 7 && word.startsWith("label=\"") && word.endsWith("\"")) {
          String label = unescape(word.substring(7, word.length() - 1));
          tests.add(el -> label.equals(el.label()));
        } else {
          throw fail(word + " is not a target. A target is " + TARGET + ".");
        }
      }
      List<Found> all = new ArrayList<>();
      walk(root, 0, 0, all);
      List<Found> matches =
          all.stream().filter(found -> tests.stream().allMatch(t -> t.test(found.el))).toList();
      if (matches.size() == 1) {
        Found found = matches.getFirst();
        return new float[] {
          found.left + found.el.width() / 2, found.top + found.el.height() / 2
        };
      }
      if (matches.isEmpty()) {
        Set<String> keys = new TreeSet<>();
        Set<String> roles = new TreeSet<>();
        Set<String> labels = new TreeSet<>();
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
        }
        throw fail(
            "No element of the last frame matches " + text + ". It has\n"
                + "    keys:   " + listing(keys) + "\n"
                + "    roles:  " + listing(roles) + "\n"
                + "    labels: " + listing(labels) + "\n"
                + "  Select with these, or press a position x,y. The .tree.txt of a snap shows"
                + " every element with its rectangle.");
      }
      StringBuilder rows = new StringBuilder();
      for (Found found : matches) {
        found.el.describe(rows.append("    "), found.left, found.top);
      }
      throw fail(
          matches.size() + " elements match " + text + ":\n" + rows
              + "  Add #key, role= or label= to the selector until it matches one, or press a"
              + " position x,y inside one of these rectangles.");
    }
  }

  /** Collects the elements on screen; one that is not placed hides its children too. */
  private static void walk(El el, float parentX, float parentY, List<Found> all) {
    if (!el.isPlaced()) {
      return;
    }
    float left = parentX + el.x();
    float top = parentY + el.y();
    all.add(new Found(el, left, top));
    for (El child : el.children()) {
      walk(child, left, top, all);
    }
  }

  private static String listing(Set<String> words) {
    return words.isEmpty() ? "none" : String.join(" ", words);
  }

  /** Undoes {@link El#quote}. {@link Line#words} made sure a character follows each backslash. */
  private static String unescape(String text) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      out.append(c == '\\' ? text.charAt(++i) : c);
    }
    return out.toString();
  }
}
