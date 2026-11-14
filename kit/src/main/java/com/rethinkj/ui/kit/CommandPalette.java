package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A command palette, as editors have it: a field at the top of the window, in the middle, with a
 * {@linkplain FilterList filtered list} of commands below it that narrows {@linkplain Fuzzy
 * fuzzily} as one types; Enter or a press runs the highlighted one. The commands come from the
 * app's menus ({@link #commands}), each as its menu's title and its text, {@code "File: New
 * query"}, with its shortcut at the right, so that the app keeps them in one place; the app adds
 * others of its own. Those chosen last come first while nothing is typed.
 *
 * <p>Other kinds of things are found by a prefix, as {@code @} for a symbol in an editor: the app
 * gives {@link Source}s, each with its prefix, what it finds and where its entries come from, also
 * on another thread ({@link FilterList.Entries}). A text without a prefix, or after {@code >},
 * finds commands.
 *
 * <pre>{@code
 * List<Menu.Item> menus = s.menus();
 * root.onKey(MenuBar.keys(s.bar, menus).or(CommandPalette.keys(s.palette)))
 *     .overlay(CommandPalette.palette(t, s.palette, CommandPalette.commands(menus),
 *         List.of(new CommandPalette.Source("@", "to open a table", Entries.of(s.tables())))));
 * }</pre>
 *
 * <p>A command may ask for more before it acts: its handler calls {@link State#ask} with a {@link
 * Stage}, a list to choose from, whose highlighted entry it can preview, or a text to type, and
 * the palette shows that instead of closing. Escape and a press outside close the palette and
 * undo a preview ({@link Stage#onCancel}); the focus goes back to where it was before the palette
 * opened. While it is open, the field has the keys: Up and Down, Page Up and Page Down, Home and
 * End move the highlight, Tab does nothing, and keys that no part of the palette takes go on to
 * the root, so that the app's shortcuts still work.
 *
 * <p>It is {@code size().palette()} wide, below a {@link TitleBar} at the window's top, and at most
 * {@value #ROWS} rows high, then it scrolls; it has the look of a menu. In a tree dump it is an
 * overlay at the window's top edge, {@code role=dialog} with {@code label="Command palette"}, its
 * field {@code role=textbox} and its list {@code role=listbox} (see {@link FilterList}).
 */
public final class CommandPalette {

  /** The most rows the list shows at once; more scroll. */
  public static final int ROWS = 12;

  /** The group of the commands chosen last, while nothing is typed, and of the others. */
  static final String RECENT = "Recently used";

  static final String OTHERS = "Other commands";

  /** How many commands chosen last it remembers. */
  static final int REMEMBERED = 5;

  private CommandPalette() {}

  /**
   * A kind of thing the palette finds after a prefix.
   *
   * @param prefix what the text starts with, such as {@code "@"}
   * @param what what it finds, for the field's placeholder: {@code "to open a table"}
   * @param entries the entries for the text after the prefix
   */
  public record Source(String prefix, String what, FilterList.Entries entries) {
    public Source {
      if (prefix.isEmpty() || prefix.equals(">")) {
        throw new IllegalArgumentException(
            "A source needs a prefix of its own, not \"" + prefix + "\": commands have none, or >");
      }
      Objects.requireNonNull(entries, "entries");
    }
  }

  /**
   * A second step of a command, which it asks for with {@link State#ask}: a list to choose from,
   * or a text to type when {@code onEnter} is set.
   *
   * @param placeholder what the field says while it is empty
   * @param text the text in the field as the step begins
   * @param entries what to choose from; null for a step that asks for a text
   * @param highlight the index of the entry highlighted first, -1 for the best
   * @param onHighlight told of each entry the keys or the pointer highlight, to preview it; null
   *     for none
   * @param onCancel runs when the palette closes without a choice, to undo a preview; null for
   *     none
   * @param onEnter receives the text when Enter is pressed, for a step that asks for one
   */
  public record Stage(
      String placeholder,
      String text,
      List<FilterList.Entry> entries,
      int highlight,
      Consumer<FilterList.Entry> onHighlight,
      Runnable onCancel,
      Consumer<String> onEnter) {

    /** A list to choose from, the entry at {@code highlight} highlighted first. */
    public static Stage choose(String placeholder, List<FilterList.Entry> entries, int highlight) {
      return new Stage(placeholder, "", List.copyOf(entries), highlight, null, null, null);
    }

    /** A text to type, beginning with {@code text}, which Enter hands to {@code onEnter}. */
    public static Stage input(String placeholder, String text, Consumer<String> onEnter) {
      return new Stage(
          placeholder, text, null, -1, null, null, Objects.requireNonNull(onEnter, "onEnter"));
    }

    /**
     * This step, telling {@code onHighlight} of each entry highlighted, and running {@code
     * onCancel} when it closes without a choice: a theme shown while the keys go through the
     * themes, and the one before back after Escape.
     */
    public Stage preview(Consumer<FilterList.Entry> onHighlight, Runnable onCancel) {
      return new Stage(placeholder, text, entries, highlight, onHighlight, onCancel, onEnter);
    }
  }

  /**
   * What the app keeps of a palette: whether it is open, the text in its field, the step it is at
   * and the commands chosen last.
   */
  public static final class State {
    final FilterList.State list = new FilterList.State();
    private boolean open;
    private String text = "";
    private Stage stage;
    private int steps; // how often it opened or went on, which makes its field anew
    private final List<String> recent = new ArrayList<>();

    public boolean isOpen() {
      return open;
    }

    /** Opens the palette on the commands. */
    public void open() {
      open("");
    }

    /**
     * Opens the palette with this text, such as a source's prefix: {@code open("@")}. A step it
     * was at is cancelled first, as Escape cancels it, undoing what it previews.
     */
    public void open(String text) {
      close();
      open = true;
      steps++;
      this.text = text;
      list.reset(-1);
      list.typed(text, true); // the palette takes a source's prefix off as it shows
    }

    /**
     * Shows this step instead of closing; a command's handler calls it, and the palette goes on
     * with the step's entries or field.
     */
    public void ask(Stage stage) {
      open = true;
      steps++;
      this.stage = stage;
      text = stage.text();
      list.reset(-1);
      list.typed(text, true);
      if (stage.highlight() >= 0) {
        list.want = stage.highlight();
      }
    }

    /** Closes the palette as Escape does, undoing what a step previews. */
    public void close() {
      Stage was = stage;
      done();
      if (was != null && was.onCancel() != null) {
        was.onCancel().run();
      }
    }

    /** The text in the field. */
    public String text() {
      return text;
    }

    /** The texts of the commands chosen last, the last first. */
    public List<String> recent() {
      return List.copyOf(recent);
    }

    /** Closes the palette without undoing anything, as a choice does. */
    private void done() {
      open = false;
      stage = null;
      list.reset(-1);
    }

    private void remember(String command) {
      recent.remove(command);
      recent.addFirst(command);
      if (recent.size() > REMEMBERED) {
        recent.removeLast();
      }
    }
  }

  /**
   * The commands of these menus, as a {@link MenuBar}'s or a menu's items: each item that runs
   * something, with the titles of the menus it is in before its text, {@code "File: New query"}
   * or {@code "File: Export › CSV"}, its icon, its shortcut at the right and its handler;
   * disabled items stay disabled. A checked item shows the check mark.
   */
  public static List<FilterList.Entry> commands(List<Menu.Item> menus) {
    List<FilterList.Entry> commands = new ArrayList<>();
    for (Menu.Item menu : menus) {
      if (menu.submenu() != null) {
        collect(menu.submenu(), MenuBar.text(menu.label()) + ": ", commands);
      } else if (!menu.isSeparator()) {
        collect(List.of(menu), "", commands);
      }
    }
    return commands;
  }

  private static void collect(List<Menu.Item> items, String path, List<FilterList.Entry> into) {
    for (Menu.Item item : items) {
      if (item.isSeparator()) {
        continue;
      }
      String label = MenuBar.text(item.label());
      if (item.submenu() != null) {
        collect(item.submenu(), path + label + " › ", into);
        continue;
      }
      FilterList.Entry e =
          FilterList.entry(path + label, item.onChoose()).shortcut(item.shortcut());
      into.add(
          item.checked()
              ? e.symbol(Menu.CHECKED)
              : item.icon() != null ? e.symbol(item.icon()) : e);
    }
  }

  /**
   * A key handler for the root ({@link El#onKey}) that opens the palette on the commands with
   * Control+Shift+P and F1, as editors do.
   */
  public static Predicate<Key> keys(State state) {
    return k -> {
      boolean opens = k.name().equals("P") && k.modifiers() == (Key.CONTROL | Key.SHIFT)
          || k.name().equals("F1") && k.modifiers() == 0;
      if (opens) {
        state.open();
      }
      return opens;
    };
  }

  /** The text after a source's prefix, or after {@code >}, else the whole text. */
  private static String query(String text, List<Source> sources) {
    Source source = source(text, sources);
    String rest = source != null
        ? text.substring(source.prefix().length())
        : text.startsWith(">") ? text.substring(1) : text;
    return rest.stripLeading();
  }

  /** The source whose prefix the text starts with, the longest prefix first, or null. */
  private static Source source(String text, List<Source> sources) {
    Source found = null;
    for (Source s : sources) {
      if (text.startsWith(s.prefix())
          && (found == null || s.prefix().length() > found.prefix().length())) {
        found = s;
      }
    }
    return found;
  }

  /**
   * The palette, an overlay to attach to the window's root while it is open, else null; {@code
   * root.overlay(palette(...))} does both.
   *
   * @param commands what the text finds without a prefix, such as {@link #commands} of the app's
   *     menus and commands of its own
   * @param sources the kinds found after their prefixes
   */
  public static El palette(
      Theme t, State state, List<FilterList.Entry> commands, List<Source> sources) {
    if (!state.open) {
      return null;
    }
    Stage stage = state.stage;
    Source source = stage == null ? source(state.text, sources) : null;
    boolean ofCommands = stage == null && source == null;
    FilterList.Entries entries = stage != null
        ? stage.entries() == null ? null : FilterList.Entries.of(stage.entries())
        : source != null
            ? source.entries()
            : FilterList.Entries.inOrder(ranked(commands, state.recent));
    state.list.onHighlight = stage == null ? null : stage.onHighlight();
    state.list.query = stage != null ? state.text : query(state.text, sources);
    Consumer<FilterList.Entry> choose = e -> {
      if (ofCommands) {
        state.remember(e.text());
      }
      state.done();
      e.onChoose().run(); // which may ask for a step
    };
    String placeholder = stage != null ? stage.placeholder() : placeholder(sources);
    El field = TextField.textField(t, state.text, placeholder, v -> {
          state.text = v;
          state.list.typed(stage != null ? v : query(v, sources), true);
          if (entries != null && state.list.onHighlight != null) {
            int was = state.list.highlight;
            FilterList.Shown shown = FilterList.shown(state.list, entries);
            if (shown.choosable(state.list.highlight) && state.list.highlight != was) {
              state.list.onHighlight.accept(shown.entry(state.list.highlight));
            }
          }
        })
        .key("query-" + state.steps)
        .label(stage != null ? stage.placeholder() : "Command")
        .autofocus(state.steps)
        .wFull()
        .onKey(k -> key(k, state, stage, choose));
    El panel = col()
        .key("palette")
        .role("dialog")
        .label("Command palette")
        .w(t.size().palette())
        .padding(t.space().s())
        .gap(t.space().s())
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .border(t.size().stroke(), t.border().control())
        .shadow(t.elevation().popup())
        .motion(t.motion().fade())
        .atWindowEdge(Side.TOP, Style.Align.CENTER, TitleBar.HEIGHT + t.space().s())
        .onDismiss(state::close)
        .child(TextField.withIcon(t, field, stage == null ? Symbol.SEARCH : Symbol.CHEVRON_RIGHT));
    // Not as wide as the field's own width, but the panel's.
    panel.children().getFirst().wFull();
    if (entries != null) {
      FilterList.Shown shown = FilterList.shown(state.list, entries);
      panel.child(FilterList.list(t, state.list, shown, ROWS, "Commands", choose));
    }
    return panel;
  }

  /** The commands, those chosen last first, in their own group, while there are any. */
  private static List<FilterList.Entry> ranked(
      List<FilterList.Entry> commands, List<String> recent) {
    if (recent.isEmpty()) {
      return commands;
    }
    Map<String, FilterList.Entry> byText = new HashMap<>();
    for (FilterList.Entry e : commands) {
      byText.putIfAbsent(e.text(), e);
    }
    List<FilterList.Entry> ranked = new ArrayList<>(commands.size());
    for (String text : recent) {
      FilterList.Entry e = byText.remove(text);
      if (e != null) {
        ranked.add(e.group(RECENT));
      }
    }
    if (ranked.isEmpty()) {
      return commands;
    }
    for (FilterList.Entry e : commands) {
      if (byText.remove(e.text()) != null) {
        ranked.add(e.group(OTHERS));
      }
    }
    return ranked;
  }

  private static String placeholder(List<Source> sources) {
    StringBuilder text = new StringBuilder("Type a command");
    for (Source s : sources) {
      text.append(", ").append(s.prefix()).append(' ').append(s.what());
    }
    return text.toString();
  }

  /** The keys of the field, besides the list's own. */
  private static boolean key(Key k, State state, Stage stage, Consumer<FilterList.Entry> choose) {
    if (k.modifiers() == 0) {
      switch (k.name()) {
        case "Escape" -> {
          state.close();
          return true;
        }
        case "Tab" -> {
          return true; // the palette keeps the keys until it closes
        }
        case "Enter", "NumpadEnter" -> {
          if (stage != null && stage.onEnter() != null) {
            String text = state.text;
            state.done();
            stage.onEnter().accept(text);
            return true;
          }
        }
        default -> {}
      }
    }
    if (k.name().equals("Tab") && k.modifiers() == Key.SHIFT) {
      return true;
    }
    return FilterList.key(k, state.list, ROWS, choose);
  }
}
