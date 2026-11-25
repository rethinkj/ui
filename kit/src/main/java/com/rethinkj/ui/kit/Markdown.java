package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Clipboard;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Highlighter;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.RowHeights;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.WindowState;
import com.rethinkj.ui.kit.MarkdownDocument.Block;
import com.rethinkj.ui.kit.MarkdownDocument.Inline;
import com.rethinkj.ui.text.Font;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A Markdown document in the theme's {@link Theme.Prose} look, as a scroll area that builds only
 * the blocks in view, to lay into a card: headings, paragraphs with bold, italic, inline code,
 * struck-out text and links, code blocks, quotes, lists and task lists, tables and thematic
 * breaks. Raw HTML shows as text.
 *
 * <pre>{@code
 * // In the state, once:
 * MarkdownDocument manual = Markdown.parse(text);
 * Markdown.State selection = new Markdown.State();
 * // In the view:
 * El doc = Markdown.markdown(manual,
 *     Markdown.options().onLink(url -> state.open(url)).copy(app.clipboard()).state(selection));
 * El page = Card.flush(doc.grow(1)).grow(1);
 * }</pre>
 *
 * <p>Every block of text is a read-only multi-line field ({@link El#fitLines}), as high as its
 * rows, and the document's list is a {@link El#selectionScope selection scope}, so a press and a
 * drag—or Shift+press—selects across blocks, including rows out of view, and Control+C copies the
 * selected text of all of them in order with a line break between blocks; Tab passes the fields by
 * ({@link El#skipTab}) and reaches the links. Control+A selects the whole document, which then
 * shows as selected, and Control+C copies all of it as text ({@link #text}); a press anywhere or
 * Escape ends that. {@link #fragment} marks its column as a selection scope too, and several
 * fragments in one container (such as a list of chat messages) join into one selection when the
 * app marks the container {@link El#selectionScope}.
 *
 * <p>A link is an element over its range of the text ({@link El#range}): {@code role=link} with
 * its text as the label, its title (if it has one) as its tooltip, in {@link Theme.Prose#link} and
 * underlined, with the pointer's cursor and a tint under it, one stop for Tab, and pressed by
 * Enter; it hands its destination to {@link Options#onLink}. Headings have the key {@code
 * heading-<id>} with GitHub's anchor as the id ({@link MarkdownDocument.Heading#id}), so that the
 * app finds one to scroll to for a link {@code #id}.
 *
 * <p>Code blocks are in the mono type on the raised surface, scroll sideways, and are colored by
 * the highlighter {@link Options#languages} gives for their language; with {@link Options#copy} a
 * copy button shows in the corner under the pointer. Task list items show the kit's checkbox,
 * which takes no input.
 *
 * <p>Images come from the app: {@link Options#images} gives the image for an address, or null
 * while it has none, and the kit's {@link Image} shows it, or its placeholder with the image's
 * description until then. An image is as large as its pixels at its scale and at most as wide as
 * the page, and its title, if it has one, is its tooltip. Images inside links, emphasis, strong or
 * strikethrough also show as images; a linked image is {@code role=link} labeled by its alt text
 * and hands its destination to {@link Options#onLink}. Images that follow each other in a
 * paragraph, separated only by spaces or line breaks, sit in one wrapping row; among text they
 * break the paragraph there, the text before above and the text after below, since a block of text
 * is one field whose lines cannot make room for an image. A heading or table cell that holds only
 * images (and links or emphasis around them) shows those images; one that mixes text and images
 * keeps their alt text for the same reason. An image that arrives later makes its row higher; when
 * that row is above the top of the view, the view moves along with it, so that what is in view
 * stays where it is. Without {@link Options#images}, an image shows as its description: in a
 * frame alone in its paragraph, in muted text (or as a text link) everywhere else.
 *
 * <p>What the component makes of a document for a theme it keeps with the document, so a rebuild of
 * the view reads nothing again and highlights nothing again. To show a document that grows while it
 * streams, keep it in the state and replace it with {@link MarkdownDocument#next
 * document.next(text)} as each piece arrives: unchanged blocks keep their identity and what the
 * component made of them.
 *
 * <p>For Markdown embedded inside another element—such as a rendered documentation comment, a
 * quick-documentation popover or completion details—use {@link #fragment} (a plain, non-scrolling
 * column with compact spacing that takes its container's width) and {@link #definitions} (a
 * two-column list of labeled documentation sections with code-styled terms and Markdown
 * descriptions).
 */
public final class Markdown {

  private Markdown() {}

  /** Reads a Markdown text; see {@link MarkdownDocument#parse}. */
  public static MarkdownDocument parse(String source) {
    return MarkdownDocument.parse(source);
  }

  /** Whether Control+A has selected the whole document; the app keeps one per document. */
  public static final class State {
    private boolean all;
    final Button.Copied copied = new Button.Copied(); // a code block's copy button's confirmation

    /** Whether the whole document shows as selected. */
    public boolean allSelected() {
      return all;
    }

    /** Selects the whole document, or no longer. */
    public void selectAll(boolean all) {
      this.all = all;
    }
  }

  /** What the document does beyond showing itself; each part is off unless set. */
  public static final class Options {
    private Consumer<String> onLink;
    private Function<String, Highlighter> languages = language -> null;
    private Clipboard clipboard;
    private State state;
    private Function<String, com.rethinkj.ui.Image> images;
    private int textColor;

    private Options() {}

    /**
     * The text color of the prose, as {@code 0xAARRGGBB} ({@link com.rethinkj.ui.Colors}), in place
     * of the theme's default ({@link Theme.TextColors#primary()} in {@link #markdown} and {@link
     * #fragment}, {@link Theme.TextColors#muted()} for descriptions in {@link #definitions}); for
     * example {@link Theme.Code#comment()} for rendered doc comments. {@code 0} keeps the theme's
     * default.
     */
    public Options textColor(int color) {
      this.textColor = color;
      return this;
    }

    /**
     * The image for an image's address as written ({@code images/frame.png}), or null while the
     * app has none, which shows a placeholder. Asked on every build of the image's row, so keep
     * the images in the state, read off the app's thread ({@link
     * com.rethinkj.ui.App#background}), and hand out the same image each time.
     */
    public Options images(Function<String, com.rethinkj.ui.Image> images) {
      this.images = images;
      return this;
    }

    /** Takes the destination of a link that is pressed, as written: a URL, or {@code #id}. */
    public Options onLink(Consumer<String> handler) {
      this.onLink = handler;
      return this;
    }

    /**
     * The highlighter for a code block's language, the first word of its info string as written
     * ({@code SQL}, {@code java}), or null for none; without it, no block is colored. Return the
     * same object or an equal one for a language on each call: it is the block's key for what was
     * highlighted.
     */
    public Options languages(Function<String, Highlighter> highlighters) {
      this.languages = Objects.requireNonNull(highlighters, "highlighters");
      return this;
    }

    /** Copying: a copy button on code blocks, and Control+C after Control+A, go here. */
    public Options copy(Clipboard clipboard) {
      this.clipboard = clipboard;
      return this;
    }

    /** Where Control+A keeps that the whole document is selected; without it, it selects none. */
    public Options state(State state) {
      this.state = state;
      return this;
    }
  }

  public static Options options() {
    return new Options();
  }

  /**
   * One entry of a {@link Section} in a {@link #definitions definition list}: an optional name in
   * code style and its Markdown description.
   *
   * @param term the name shown as a code chip before the description, such as a parameter or
   *     exception name; null or empty for an entry without a name (such as a return value)
   * @param description the entry's Markdown description; null for a term without a description
   */
  public record Definition(String term, MarkdownDocument description) {}

  /**
   * A labeled group of {@link Definition}s in a {@link #definitions definition list}, such as
   * parameters, return value or thrown exceptions.
   *
   * @param label the section's label in the left column, typically from {@link Texts} (such as
   *     {@link Texts#parameters()})
   * @param definitions the section's entries in order
   */
  public record Section(String label, List<Definition> definitions) {
    public Section {
      Objects.requireNonNull(label, "label");
      definitions = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
    }
  }

  /** The document with links that go to {@code onLink}, nothing else. */
  public static El markdown(MarkdownDocument document, Consumer<String> onLink) {
    return markdown(document, options().onLink(onLink));
  }

  /**
   * The document as a scroll area of its blocks, {@code role=document}, with the theme's spaces
   * between them and {@link Theme.Spacing#xxl} around them, each as wide as the area up to {@link
   * Theme.Prose#measure} and centered in it: a virtual list ({@link El#rows(int, RowHeights,
   * java.util.function.IntFunction)}) whose rows are the document's top-level blocks, so that only
   * the blocks in view and a few around them are built and laid out, however long the document.
   * Give it a height, or let it {@link El#grow} in a column, as any scroll area.
   */
  public static El markdown(MarkdownDocument document, Options options) {
    Theme theme = Theme.CURRENT.get();
    Prepared prepared = prepared(theme, document);
    int color = options.textColor != 0 ? options.textColor : theme.text().primary();
    Context c = new Context(theme, options, prepared, color, 0, false);
    List<Unit> units = prepared.units;
    El list = col()
        .role("document")
        .key("markdown")
        .selectionScope()
        .padding(theme.space().xxl())
        .scrollbars(theme.scrollbars())
        .rows(
            units.size(),
            prepared.heights,
            i -> col()
                .align(Style.Align.CENTER)
                .child(unit(c, units.get(i))
                    .wFull()
                    .maxSize(theme.prose().measure(), Float.POSITIVE_INFINITY)));
    State state = options.state;
    if (state != null) {
      list.onAnyPointerDown(() -> state.all = false);
      // Overlays in the rows of a list keep their time only while their row exists: the
      // confirmation of a code block's copy button ends by one on the document.
      list.overlay(Button.ending(theme, state.copied));
    }
    list.onKey(k -> keys(c, k));
    return list;
  }

  /**
   * The document as a plain, non-scrolling column of its blocks with compact spacing, as wide as
   * its container, without padding or a maximum measure: for rendered documentation comments,
   * popovers and completion details embedded inside another element. A container that sizes to
   * its content gives the fragment a width. Links, images, code-block highlighting and copying
   * follow {@code options} as in {@link #markdown}; a selection reaches across the fragment's
   * blocks (and across several fragments when their enclosing container is marked {@link
   * El#selectionScope}), while Ctrl+A selects the text of one block, not the whole document.
   */
  public static El fragment(MarkdownDocument document, Options options) {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(options, "options");
    Theme theme = Theme.CURRENT.get();
    int color = options.textColor != 0 ? options.textColor : theme.text().primary();
    El out = fragment(theme, document, options, color).wFull();
    if (options.state != null) {
      out.overlay(Button.ending(theme, options.state.copied));
    }
    return out;
  }

  private static El fragment(Theme theme, MarkdownDocument document, Options options, int color) {
    Context c = new Context(theme, options, prepared(theme, document), color, 0, true);
    El out = col().minSize(0, 0).selectionScope();
    blocks(c, document.blocks(), false, out);
    return out;
  }

  /**
   * Labeled sections of code-styled terms and Markdown descriptions, for documentation sections
   * such as parameters, return values and thrown exceptions. Section labels sit in one aligned
   * column on the left in muted small text (the caller passes localized labels from {@link Texts},
   * such as {@link Texts#parameters()}, {@link Texts#returns()}, {@link Texts#thrown()} and {@link
   * Texts#seeAlso()}); each entry shows its optional term as a small monospace chip on the theme's
   * inline-code fill ({@link Theme.Prose#code}) beside its description rendered as a compact
   * fragment in muted text, or above it when the two do not fit beside each other.
   *
   * <p>In a tree dump the list is {@code role=list}, each section {@code role=group} with its
   * label, and each entry {@code role=listitem} (labeled by its term when it has one).
   */
  public static El definitions(List<Section> sections, Options options) {
    Objects.requireNonNull(sections, "sections");
    Objects.requireNonNull(options, "options");
    Theme theme = Theme.CURRENT.get();
    Theme.TextStyle mono = theme.type().mono();
    float line = lineHeight(theme.prose().body());
    float column = labelColumn(sections);
    El out = col().role("list").gap(theme.space().m()).minSize(0, 0);
    for (Section section : sections) {
      if (section.definitions().isEmpty()) {
        continue;
      }
      El entries = col().grow(1).minSize(0, 0).gap(theme.space().s());
      for (Definition definition : section.definitions()) {
        boolean hasTerm = definition.term() != null && !definition.term().isEmpty();
        boolean hasDescription = definition.description() != null
            && !definition.description().blocks().isEmpty();
        El entry = row()
            .role("listitem")
            .gap(theme.space().m())
            .align(Style.Align.START)
            .wrap() // a description too narrow beside a long term goes below it
            .minSize(0, 0);
        if (hasTerm) {
          entry.label(definition.term());
          El chip = row()
              .minSize(0, line)
              .clip() // a term wider than the whole entry is cut at its edge
              .align(Style.Align.CENTER)
              .padding(0, theme.space().s())
              .radius(theme.radius().small())
              .fill(theme.prose().code())
              .child(El.text(definition.term())
                  .font(mono.font().get())
                  .fontSize(mono.size())
                  .color(theme.text().primary()));
          entry.child(chip);
        }
        if (hasDescription) {
          int color = options.textColor != 0 ? options.textColor : theme.text().muted();
          entry.child(fragment(theme, definition.description(), options, color)
              .basis(theme.prose().measure() / 4)
              .grow(1));
        }
        entries.child(entry);
      }
      El label = row()
          .w(column)
          .h(line)
          .shrink(0)
          .align(Style.Align.CENTER)
          .child(Label.label(Label.Variant.SMALL, section.label()));
      out.child(row()
          .role("group")
          .label(section.label())
          .gap(theme.space().l())
          .align(Style.Align.START)
          .children(label, entries));
    }
    State state = options.state;
    if (state != null) {
      out.overlay(Button.ending(theme, state.copied));
    }
    return out;
  }

  private static float labelColumn(List<Section> sections) {
    float widest = 0;
    for (Section section : sections) {
      if (!section.definitions().isEmpty()) {
        widest =
            Math.max(widest, TextSizing.natural(Label.label(Label.Variant.SMALL, section.label())));
      }
    }
    return widest == 0 ? 0 : (float) Math.ceil(widest) + 1;
  }

  private static float lineHeight(Theme.TextStyle style) {
    Font font = style.font().get();
    return Math.round((font.ascent() + font.descent() + font.lineGap()) * style.size());
  }

  /**
   * The row of the element {@link #markdown} returns that holds the heading with this anchor
   * ({@link MarkdownDocument.Heading#id}), for {@link com.rethinkj.ui.App#scrollToRow}; -1 if
   * there is none. The rows are the top-level blocks, and each item of a top-level list.
   */
  public static int rowOf(MarkdownDocument document, String id) {
    List<Unit> units = units(document);
    for (int i = 0; i < units.size(); i++) {
      List<Block> inside = units.get(i).item < 0
          ? List.of(units.get(i).block)
          : ((MarkdownDocument.ListBlock) units.get(i).block)
              .items()
              .get(units.get(i).item)
              .blocks();
      if (new MarkdownDocument(inside).headings().stream().anyMatch(h -> h.id().equals(id))) {
        return i;
      }
    }
    return -1;
  }

  /**
   * A row of the document: a top-level block, or an item of a top-level list, so that a long
   * list is built an item at a time.
   *
   * @param index the block's index among the top-level blocks
   * @param item the item's index in the list, -1 for a whole block
   */
  private record Unit(List<Block> blocks, int index, Block block, int item) {}

  private static List<Unit> units(MarkdownDocument document) {
    List<Block> blocks = document.blocks();
    List<Unit> units = new ArrayList<>();
    for (int i = 0; i < blocks.size(); i++) {
      if (blocks.get(i) instanceof MarkdownDocument.ListBlock l && !l.items().isEmpty()) {
        for (int j = 0; j < l.items().size(); j++) {
          units.add(new Unit(blocks, i, l, j));
        }
      } else {
        units.add(new Unit(blocks, i, blocks.get(i), -1));
      }
    }
    return units;
  }

  /** The element of a row: a block with its space, or an item with the space of its list. */
  private static El unit(Context c, Unit u) {
    if (u.item < 0) {
      return spaced(c, u.blocks, u.index, false, block(c, u.block));
    }
    Theme theme = c.theme;
    MarkdownDocument.ListBlock list = (MarkdownDocument.ListBlock) u.block;
    El item = item(c, list, u.item, markerColumn(c, list));
    boolean lastItem = u.item + 1 == list.items().size();
    float below = list.tight() ? theme.space().xs() : theme.space().l();
    if (lastItem) {
      below = u.index + 1 == u.blocks.size() ? 0 : theme.space().l();
    }
    return col().role("list").child(item).padding(0, 0, below, 0);
  }

  /**
   * The text of the document as it shows, for the clipboard: blocks apart by an empty line, the
   * items of lists after their markers, the rows of tables with tabs between their cells.
   */
  public static String text(MarkdownDocument document) {
    StringBuilder out = new StringBuilder();
    text(document.blocks(), "", out);
    return out.toString().strip();
  }

  // ---- What is kept with the document

  /** The widest natural and longest-word text widths of a table's columns. */
  private record TableWidths(float[] natural, float[] min) {}

  /** What table widths were measured for: the scale, and whether cells showed their images. */
  private record Measured(float scale, boolean images) {}

  /** The blocks of text of a document, made once per theme. */
  private static final class Prepared {
    final MarkdownDocument document;
    // By the prose text color they were built for, then by the block or inline list they came from.
    final Map<Integer, Map<Object, Piece>> pieces = new HashMap<>();
    // The widths of lists' markers, by the window's scale they were measured at.
    final Map<Float, Map<Object, Float>> columns = new HashMap<>();
    // The natural and longest-word column widths of tables, by what they were measured for.
    final Map<Measured, Map<MarkdownDocument.Table, TableWidths>> tables = new HashMap<>();
    final List<Unit> units;
    final RowHeights heights;
    String text; // the whole document's, for Control+C after Control+A

    Prepared(MarkdownDocument document, Theme theme) {
      this(document, theme, new double[0]);
    }

    /** With the heights of the first rows as they were, the others estimated. */
    Prepared(MarkdownDocument document, Theme theme, double[] kept) {
      this.document = document;
      List<Unit> units = units(document);
      this.units = units;
      this.heights = new RowHeights(i -> {
        if (i < kept.length) {
          return kept[i];
        }
        Unit u = units.get(i);
        return (u.item < 0
                ? estimate(theme, u.block)
                : ((MarkdownDocument.ListBlock) u.block)
                    .items().get(u.item).blocks().stream()
                        .mapToDouble(b -> estimate(theme, b))
                        .sum())
            + theme.space().l();
      });
    }
  }

  private static void takeOver(Block block, Prepared old, Prepared target) {
    switch (block) {
      case MarkdownDocument.Heading h -> copyPiece(h, old, target);
      case MarkdownDocument.Paragraph p -> {
        copyPiece(p, old, target);
        copyPiece(p.content(), old, target);
        for (Object part : split(p.content(), null)) {
          if (part instanceof Picture picture) {
            copyPiece(picture.image(), old, target);
          }
        }
      }
      case MarkdownDocument.Quote q -> {
        for (Block child : q.blocks()) {
          takeOver(child, old, target);
        }
      }
      case MarkdownDocument.ListBlock l -> {
        for (var entry : old.columns.entrySet()) {
          Float width = entry.getValue().get(l);
          if (width != null) {
            target
                .columns
                .computeIfAbsent(entry.getKey(), s -> new IdentityHashMap<>())
                .put(l, width);
          }
        }
        for (MarkdownDocument.Item item : l.items()) {
          for (Block child : item.blocks()) {
            takeOver(child, old, target);
          }
        }
      }
      case MarkdownDocument.Table table -> {
        for (var entry : old.tables.entrySet()) {
          TableWidths widths = entry.getValue().get(table);
          if (widths != null) {
            target
                .tables
                .computeIfAbsent(entry.getKey(), s -> new IdentityHashMap<>())
                .put(table, widths);
          }
        }
        for (List<Inline> cell : table.header()) {
          copyPiece(cell, old, target);
        }
        for (List<List<Inline>> row : table.rows()) {
          for (List<Inline> cell : row) {
            copyPiece(cell, old, target);
          }
        }
      }
      case MarkdownDocument.CodeBlock _, MarkdownDocument.ThematicBreak _ -> {}
    }
  }

  private static void copyPiece(Object owner, Prepared old, Prepared target) {
    for (var entry : old.pieces.entrySet()) {
      Piece piece = entry.getValue().get(owner);
      if (piece != null) {
        target
            .pieces
            .computeIfAbsent(entry.getKey(), color -> new IdentityHashMap<>())
            .put(owner, piece);
      }
    }
  }

  /**
   * About how high a block is at the width of {@link Theme.Prose#measure}, from the length of its
   * text, until it is laid out.
   */
  private static double estimate(Theme theme, Block block) {
    double line = theme.prose().body().size() * 1.25;
    double perLine = Math.max(theme.prose().measure() / (theme.prose().body().size() * 0.5), 1);
    return switch (block) {
      case MarkdownDocument.Heading h ->
        theme.prose().headings().get(h.level() - 1).size() * 1.25
            + theme.space().xxl();
      case MarkdownDocument.Paragraph p ->
        Math.ceil(MarkdownDocument.plainText(p.content()).length() / perLine) * line;
      case MarkdownDocument.CodeBlock code ->
        code.code().lines().count() * theme.type().mono().size() * 1.35
            + 2 * theme.space().l();
      case MarkdownDocument.Quote q ->
        q.blocks().stream().mapToDouble(b -> estimate(theme, b)).sum();
      case MarkdownDocument.ListBlock l ->
        l.items().stream()
            .flatMap(i -> i.blocks().stream())
            .mapToDouble(b -> estimate(theme, b) + theme.space().xs())
            .sum();
      case MarkdownDocument.ThematicBreak _ -> 2 * theme.space().m();
      case MarkdownDocument.Table table ->
        (table.rows().size() + 1) * (line + 2 * theme.space().s());
    };
  }

  private static Prepared prepared(Theme theme, MarkdownDocument document) {
    synchronized (document) {
      if (document.prepared instanceof Prepared p && document.preparedFor == theme) {
        if (p.document == document) {
          return p;
        }
        // Handed over by MarkdownDocument#next: take what the blocks kept from it are made of.
        Prepared next = prepareFrom(document, theme, p);
        document.prepared = next;
        return next;
      }
      Prepared p = new Prepared(document, theme);
      document.prepared = p;
      document.preparedFor = theme;
      return p;
    }
  }

  /**
   * A Prepared for a document that keeps the first blocks of an earlier one, with what was made of
   * those blocks and the heights of their rows, and nothing that leads to the earlier document.
   */
  private static Prepared prepareFrom(MarkdownDocument document, Theme theme, Prepared old) {
    List<Block> oldBlocks = old.document.blocks();
    List<Block> blocks = document.blocks();
    int reused = 0;
    while (reused < Math.min(oldBlocks.size(), blocks.size())
        && oldBlocks.get(reused) == blocks.get(reused)) {
      reused++;
    }
    // The last row had no space below it, which it has now that blocks follow it.
    int rows = 0;
    while (rows < Math.min(old.units.size() - 1, old.heights.count())
        && old.units.get(rows).index < reused) {
      rows++;
    }
    double[] kept = new double[rows];
    for (int i = 0; i < rows; i++) {
      kept[i] = old.heights.height(i);
    }
    Prepared p = new Prepared(document, theme, kept);
    for (int i = 0; i < reused; i++) {
      takeOver(blocks.get(i), old, p);
    }
    return p;
  }

  /** A range of a piece's text that a highlighter gives: a font, a color or a line. */
  private record Span(int kind, int from, int to, int color, Font font, float room, float radius) {
    Span(int kind, int from, int to, int color, Font font) {
      this(kind, from, to, color, font, 0, 0);
    }
  }

  private static final int COLOR = 0;
  private static final int FONT = 1;
  private static final int BACKGROUND = 2;
  private static final int UNDERLINE = 3;
  private static final int STRIKE = 4;

  /** A link of a piece: its range, what it shows, where it goes and its title. */
  private record Link(int from, int to, String label, String destination, String title) {}

  /**
   * The text of a block with its ranges, its own highlighter: it hands each line the spans of its
   * range of the text, the line's start passed on as the state.
   */
  private static final class Piece implements Highlighter {
    final String text;
    final List<Span> spans;
    final List<Link> links;

    Piece(String text, List<Span> spans, List<Link> links) {
      this.text = text;
      this.spans = List.copyOf(spans);
      this.links = List.copyOf(links);
    }

    @Override
    public Object line(String line, Object state, Spans out) {
      int start = state instanceof Integer i ? i : 0;
      int end = start + line.length();
      for (Span s : spans) {
        if (s.to <= start || s.from >= end) {
          continue;
        }
        int from = s.from - start;
        int to = s.to - start;
        switch (s.kind) {
          case COLOR -> out.color(from, to, s.color);
          case FONT -> out.font(from, to, s.font);
          case BACKGROUND -> out.background(from, to, s.color, s.room, s.radius);
          case UNDERLINE -> out.underline(from, to, s.color);
          default -> out.strike(from, to, s.color);
        }
      }
      return end + 1; // past the line feed
    }
  }

  /** The bits of inline style that choose a font. */
  private static final int EM = 1;

  private static final int STRONG = 2;

  static int piecesBuilt; // for tests
  static int tablesMeasured; // for tests

  /** Makes the piece of a run of inline parts, once per document, theme and text color. */
  private static Piece piece(Context c, Object owner, List<Inline> content, boolean heading) {
    Map<Object, Piece> byOwner =
        c.prepared.pieces.computeIfAbsent(c.color, color -> new IdentityHashMap<>());
    Piece kept = byOwner.get(owner);
    if (kept != null) {
      return kept;
    }
    piecesBuilt++;
    StringBuilder text = new StringBuilder();
    List<Span> spans = new ArrayList<>();
    List<Link> links = new ArrayList<>();
    inline(c, content, 0, null, false, heading, text, spans, links);
    Piece piece = new Piece(text.toString(), spans, links);
    byOwner.put(owner, piece);
    return piece;
  }

  private static void inline(
      Context c,
      List<Inline> parts,
      int style,
      String link,
      boolean strike,
      boolean heading,
      StringBuilder text,
      List<Span> spans,
      List<Link> links) {
    Theme theme = c.theme;
    for (Inline part : parts) {
      int from = text.length();
      switch (part) {
        case MarkdownDocument.Text x -> text.append(x.text());
        case MarkdownDocument.SoftBreak _ -> text.append(' ');
        case MarkdownDocument.HardBreak _ -> text.append('\n');
        case MarkdownDocument.Code x -> {
          text.append(x.code());
          Font mono = heading || (style & STRONG) != 0
              ? Font.monoBold()
              : theme.type().mono().font().get();
          spans.add(new Span(FONT, from, text.length(), 0, mono));
          spans.add(new Span(
              BACKGROUND,
              from,
              text.length(),
              theme.prose().code(),
              null,
              theme.space().xs(),
              theme.radius().small()));
        }
        case MarkdownDocument.Emphasis x ->
          inline(c, x.content(), style | EM, link, strike, heading, text, spans, links);
        case MarkdownDocument.Strong x ->
          inline(c, x.content(), style | STRONG, link, strike, heading, text, spans, links);
        case MarkdownDocument.Strike x ->
          inline(c, x.content(), style, link, true, heading, text, spans, links);
        case MarkdownDocument.Link x -> {
          inline(c, x.content(), style, x.destination(), strike, heading, text, spans, links);
          if (text.length() > from) {
            spans.add(new Span(COLOR, from, text.length(), theme.prose().link(), null));
            spans.add(new Span(UNDERLINE, from, text.length(), theme.prose().link(), null));
            links.add(
                new Link(from, text.length(), text.substring(from), x.destination(), x.title()));
          }
          continue;
        }
        case MarkdownDocument.Image x -> {
          text.append(x.alt().isEmpty() ? x.source() : x.alt());
          spans.add(new Span(COLOR, from, text.length(), theme.text().muted(), null));
          if (link != null) {
            continue;
          }
        }
      }
      int to = text.length();
      // Font and line belong to the parts that hold text; spans of the parts around them, laid
      // over their spans after them, would take the inner part's style away (*a **b** c*).
      if (part instanceof MarkdownDocument.Emphasis
          || part instanceof MarkdownDocument.Strong
          || part instanceof MarkdownDocument.Strike) {
        continue;
      }
      if (to > from && !(part instanceof MarkdownDocument.Code)) {
        Font font = switch (style) {
          case EM -> Font.sansItalic();
          case STRONG -> heading ? Font.sansBold() : Font.sansSemibold();
          case EM | STRONG -> Font.sansBoldItalic();
          default -> null;
        };
        if (font != null) {
          spans.add(new Span(FONT, from, to, 0, font));
        }
      }
      if (strike && to > from) {
        spans.add(new Span(STRIKE, from, to, link != null ? theme.prose().link() : c.color, null));
      }
    }
  }

  // ---- Blocks

  /** What building a document needs, and the color of its text where it is now. */
  private record Context(
      Theme theme, Options options, Prepared prepared, int color, int depth, boolean compact) {
    Context muted() { // the app's text color stays
      int muted = options.textColor != 0 ? options.textColor : theme.text().muted();
      return new Context(theme, options, prepared, muted, depth, compact);
    }

    Context deeper() {
      return new Context(theme, options, prepared, color, depth + 1, compact);
    }
  }

  private static void blocks(Context c, List<Block> blocks, boolean tight, El into) {
    for (int i = 0; i < blocks.size(); i++) {
      into.child(spaced(c, blocks, i, tight, block(c, blocks.get(i))));
    }
  }

  /**
   * The block with this index among {@code blocks} with the space above and below it: a heading
   * more above than below, nothing after the last block.
   */
  private static El spaced(Context c, List<Block> blocks, int i, boolean tight, El el) {
    Theme theme = c.theme;
    Block b = blocks.get(i);
    boolean last = i + 1 == blocks.size();
    float normal = c.compact ? theme.space().m() : theme.space().l();
    float below = last ? 0 : tight ? theme.space().xs() : normal;
    if (b instanceof MarkdownDocument.Heading && !last) {
      below = c.compact ? theme.space().s() : theme.space().m();
    }
    float above = 0;
    if (b instanceof MarkdownDocument.Heading h && i > 0) {
      above = c.compact
          ? (h.level() <= 2 ? theme.space().l() : theme.space().m())
          : (h.level() <= 2 ? theme.space().xxl() : theme.space().l());
    }
    return el.margin(above, 0, below, 0);
  }

  private static El block(Context c, Block block) {
    Theme theme = c.theme;
    return switch (block) {
      case MarkdownDocument.Heading h -> heading(c, h);
      case MarkdownDocument.Paragraph p -> paragraph(c, p);
      case MarkdownDocument.CodeBlock code -> codeBlock(c, code);
      case MarkdownDocument.Quote q -> {
        El content = col().grow(1).minSize(0, 0);
        blocks(c.muted(), q.blocks(), false, content);
        yield row()
            .role("blockquote")
            .gap(c.compact ? theme.space().m() : theme.space().l())
            .children(
                el().w(theme.size().indicator() + 1)
                    .shrink(0)
                    .radius(theme.radius().small() / 2)
                    .fill(theme.prose().quote())
                    .alignSelf(Style.Align.STRETCH),
                content);
      }
      case MarkdownDocument.ListBlock l -> list(c, l);
      case MarkdownDocument.ThematicBreak _ -> {
        float margin = c.compact ? theme.space().s() : theme.space().m();
        yield Divider.divider().margin(margin, 0, margin, 0);
      }
      case MarkdownDocument.Table table -> table(c, table);
    };
  }

  /** A read-only field for a block of text: selectable, passed by Tab, as high as its rows. */
  private static El field(Context c, Piece piece, Theme.TextStyle style, int color) {
    Theme theme = c.theme;
    El field = El.text(piece.text)
        .editable(text -> {})
        .readOnly()
        .multiline()
        .wrapText()
        .fitLines()
        .skipTab()
        .font(style.font().get())
        .fontSize(style.size())
        .color(color)
        .selectionColor(theme.accent().selection())
        .caretColor(color)
        .highlight(piece, piece);
    State state = c.options.state;
    if (state != null && state.all && !c.compact) {
      int selection = theme.accent().selection();
      field.decorate((line, start, text, out) -> out.background(0, text.length() + 1, selection));
    }
    if (!c.compact) { // a fragment has no whole-document selection
      field.onKey(k -> keys(c, k));
    }
    for (int i = 0; i < piece.links.size(); i++) {
      Link link = piece.links.get(i);
      El el = link(c, link.label, link.destination).key("link-" + i).range(link.from, link.to);
      if (!link.title.isEmpty()) {
        Tooltip.tooltip(el, link.title);
      }
      field.child(el);
    }
    return field;
  }

  /** A link element: focusable, with the theme's pointer cursor, hover/pressed tint and action. */
  private static El link(Context c, String label, String destination) {
    Theme theme = c.theme;
    El el = el().role("link")
        .label(label)
        .value(destination)
        .radius(theme.radius().small())
        .focusable()
        .focusRing(theme.accent().edge());
    Consumer<String> onLink = c.options.onLink;
    if (onLink != null) {
      el.cursor(Cursor.POINTER)
          .onPress(() -> onLink.accept(destination))
          .whenHovered(e -> e.fill(theme.surface().hover()))
          .whenPressed(e -> e.fill(theme.surface().pressed()));
    }
    return el;
  }

  /**
   * Control+A selects the document, Control+C after it copies the whole of it, and Escape ends the
   * selection.
   */
  private static boolean keys(Context c, Key k) {
    State state = c.options.state;
    if (state == null) {
      return false;
    }
    if (k.modifiers() == 0 && k.name().equals("Escape")) {
      if (!state.all) {
        return false;
      }
      state.all = false;
      return true;
    }
    if (k.modifiers() != Key.CONTROL) {
      return false;
    }
    if (k.name().equals("A")) {
      state.all = true;
      return true;
    }
    if ((k.name().equals("C") || k.name().equals("Insert"))
        && state.all
        && c.options.clipboard != null) {
      Prepared p = c.prepared;
      if (p.text == null) {
        p.text = text(documentOf(c));
      }
      c.options.clipboard.write(p.text);
      return true;
    }
    return false;
  }

  private static MarkdownDocument documentOf(Context c) {
    return c.prepared.document;
  }

  private static El heading(Context c, MarkdownDocument.Heading h) {
    Theme theme = c.theme;
    List<Picture> pictures = c.options.images == null ? List.of() : imagesOnly(h.content());
    El content;
    if (!pictures.isEmpty()) {
      content = imageRow(c, pictures);
    } else {
      Theme.TextStyle style = theme.prose().headings().get(h.level() - 1);
      int color =
          h.level() == 6 && c.color == theme.text().primary() ? theme.text().muted() : c.color;
      content = field(c, piece(c, h, h.content(), true), style, color);
    }
    content.role("heading").level(h.level()).key("heading-" + h.id());
    if (h.level() > 2) {
      return content;
    }
    // The two top levels stand over a hairline, as on a page of documentation.
    content.padding(0, 0, theme.space().s(), 0);
    return col().children(content, Divider.divider());
  }

  private static El paragraph(Context c, MarkdownDocument.Paragraph p) {
    Theme theme = c.theme;
    List<Inline> content = p.content();
    if (content.size() == 1 && content.getFirst() instanceof MarkdownDocument.Image image) {
      return image(c, image);
    }
    List<Object> parts = c.options.images == null ? List.of() : split(content, null);
    if (parts.stream().noneMatch(Picture.class::isInstance)) {
      return field(c, piece(c, p, content, false), theme.prose().body(), c.color)
          .role("paragraph");
    }
    // Images among the text break the paragraph: text, a row of images, text, each a block of its
    // own. A run of text is kept by the image before it, the first by the content: the paragraph
    // keeps its piece shown whole, for a view of the same document without images.
    El blocks = col().gap(c.compact ? theme.space().m() : theme.space().l());
    Object owner = content;
    List<Inline> run = new ArrayList<>();
    List<Picture> pictures = new ArrayList<>();
    for (Object part : parts) {
      if (!(part instanceof Picture picture)) {
        run.add((Inline) part);
        continue;
      }
      // Images with only spaces or line breaks between them share a row.
      if (!blank(run)) {
        if (!pictures.isEmpty()) {
          blocks.child(imageRow(c, pictures));
        }
        run(c, owner, run, blocks);
        pictures = new ArrayList<>();
      }
      run = new ArrayList<>();
      pictures.add(picture);
      owner = picture.image();
    }
    blocks.child(imageRow(c, pictures));
    run(c, owner, run, blocks);
    return blocks;
  }

  /** An image of a block, with the destination of the link around it, if any. */
  private record Picture(MarkdownDocument.Image image, String link) {}

  /**
   * The content with its images, however deep, taken out as {@link Picture}s, in order; the text
   * between them stays in the emphasis and links it was in.
   */
  private static List<Object> split(List<Inline> content, String link) {
    List<Object> out = new ArrayList<>();
    for (Inline part : content) {
      switch (part) {
        case MarkdownDocument.Image x -> out.add(new Picture(x, link));
        case MarkdownDocument.Emphasis x ->
          rewrap(split(x.content(), link), MarkdownDocument.Emphasis::new, out);
        case MarkdownDocument.Strong x ->
          rewrap(split(x.content(), link), MarkdownDocument.Strong::new, out);
        case MarkdownDocument.Strike x ->
          rewrap(split(x.content(), link), MarkdownDocument.Strike::new, out);
        case MarkdownDocument.Link x ->
          rewrap(
              split(x.content(), x.destination()),
              run -> new MarkdownDocument.Link(x.destination(), x.title(), run),
              out);
        default -> out.add(part);
      }
    }
    return out;
  }

  /** Puts each run of text among {@code parts} back into its wrapper, and the pictures as they are. */
  private static void rewrap(
      List<Object> parts, Function<List<Inline>, Inline> wrapper, List<Object> out) {
    List<Inline> run = new ArrayList<>();
    for (Object part : parts) {
      if (part instanceof Inline inline) {
        run.add(inline);
        continue;
      }
      if (!run.isEmpty()) {
        out.add(wrapper.apply(run));
        run = new ArrayList<>();
      }
      out.add(part);
    }
    if (!run.isEmpty()) {
      out.add(wrapper.apply(run));
    }
  }

  /**
   * The images of content that holds nothing else but spaces and line breaks (as a heading or a
   * table cell made of badges), or none.
   */
  private static List<Picture> imagesOnly(List<Inline> content) {
    List<Picture> pictures = new ArrayList<>();
    List<Inline> rest = new ArrayList<>();
    for (Object part : split(content, null)) {
      if (part instanceof Picture picture) {
        pictures.add(picture);
      } else {
        rest.add((Inline) part);
      }
    }
    return blank(rest) ? pictures : List.of();
  }

  /** Whether the content shows nothing but spaces (no-break ones too) and line breaks. */
  private static boolean blank(List<Inline> content) {
    return MarkdownDocument.plainText(content).replace('\u00a0', ' ').isBlank();
  }

  /** A wrapping row of images (and linked images) that follow each other without text between. */
  private static El imageRow(Context c, List<Picture> pictures) {
    El out = row().wrap().gap(c.theme.space().s()).align(Style.Align.CENTER);
    for (Picture picture : pictures) {
      out.child(imageItem(c, picture.image, picture.link));
    }
    return out;
  }

  private static El imageItem(Context c, MarkdownDocument.Image image, String destination) {
    String alt = image.alt().isEmpty() ? image.source() : image.alt();
    com.rethinkj.ui.Image picture =
        c.options.images == null ? null : c.options.images.apply(image.source());
    El el = Image.image(picture, alt).minSize(0, 0);
    if (picture == null) {
      el.justify(Style.Content.START);
    }
    El out = destination == null
        ? el
        : link(c, alt, destination)
            .radius(c.theme.radius().medium())
            .align(Style.Align.START)
            .minSize(0, 0)
            .child(el);
    if (picture != null && !image.title().isEmpty()) {
      Tooltip.tooltip(out, image.title());
    }
    return out;
  }

  private static float imageRowWidth(Context c, List<Picture> pictures) {
    Theme theme = c.theme;
    float width = Math.max(0, pictures.size() - 1) * theme.space().s();
    for (Picture item : pictures) {
      MarkdownDocument.Image image = item.image;
      com.rethinkj.ui.Image picture = c.options.images.apply(image.source());
      if (picture != null) {
        width += picture.width() / picture.scale();
      } else {
        String alt = image.alt().isEmpty() ? image.source() : image.alt();
        width += theme.size().icon()
            + theme.space().m()
            + TextSizing.natural(Label.label(Label.Variant.MUTED, alt))
            + 2 * (theme.space().l() + theme.size().stroke());
      }
    }
    return width;
  }

  /** A run of a paragraph's text between its images, without the breaks around it, if any. */
  private static void run(Context c, Object owner, List<Inline> run, El into) {
    List<Inline> text = trimmed(trimmed(run, true), false);
    if (text.isEmpty()) {
      return;
    }
    into.child(
        field(c, piece(c, owner, text, false), c.theme.prose().body(), c.color).role("paragraph"));
  }

  /**
   * The content without the spaces and line breaks at its start (or end), inside emphasis and links
   * too.
   */
  private static List<Inline> trimmed(List<Inline> content, boolean start) {
    List<Inline> out = new ArrayList<>(content);
    while (!out.isEmpty() && blank(List.of(start ? out.getFirst() : out.getLast()))) {
      out.remove(start ? 0 : out.size() - 1);
    }
    if (out.isEmpty()) {
      return out;
    }
    int i = start ? 0 : out.size() - 1;
    out.set(
        i,
        switch (out.get(i)) {
          case MarkdownDocument.Text x ->
            new MarkdownDocument.Text(start ? x.text().stripLeading() : x.text().stripTrailing());
          case MarkdownDocument.Emphasis x ->
            new MarkdownDocument.Emphasis(trimmed(x.content(), start));
          case MarkdownDocument.Strong x ->
            new MarkdownDocument.Strong(trimmed(x.content(), start));
          case MarkdownDocument.Strike x ->
            new MarkdownDocument.Strike(trimmed(x.content(), start));
          case MarkdownDocument.Link x ->
            new MarkdownDocument.Link(x.destination(), x.title(), trimmed(x.content(), start));
          default -> out.get(i);
        });
    return out;
  }

  /**
   * An image as a block: the app's image, at most as wide as the page, with its title as its
   * tooltip, or the placeholder with its description until the app has it; without {@link
   * Options#images}, the description in a frame.
   */
  private static El image(Context c, MarkdownDocument.Image image) {
    String alt = image.alt().isEmpty() ? image.source() : image.alt();
    com.rethinkj.ui.Image picture =
        c.options.images == null ? null : c.options.images.apply(image.source());
    El el = Image.image(picture, alt);
    if (picture == null) {
      el.justify(Style.Content.START);
      el.children().getLast().grow(1);
      return el;
    }
    if (!image.title().isEmpty()) {
      Tooltip.tooltip(el, image.title());
    }
    // In a column of its own, which the page stretches, so that a small image keeps its size and
    // a large one is as wide as the page (El.image takes no more than the width there is).
    return col().align(Style.Align.START).child(el);
  }

  private static El codeBlock(Context c, MarkdownDocument.CodeBlock block) {
    Theme theme = c.theme;
    String code = block.code().endsWith("\n")
        ? block.code().substring(0, block.code().length() - 1)
        : block.code();
    String language = block.language();
    Highlighter highlighter = c.options.languages.apply(language);
    Theme.TextStyle mono = theme.type().mono();
    El field = El.text(code)
        .editable(text -> {})
        .readOnly()
        .multiline()
        .fitLines()
        .skipTab()
        .role("code")
        .label(language.isEmpty() ? null : language)
        .font(mono.font().get())
        .fontSize(mono.size())
        .color(theme.text().primary())
        .selectionColor(theme.accent().selection())
        .caretColor(theme.text().primary())
        .scrollbars(theme.scrollbars());
    // Below the last line, the room of the bar along the bottom, which shows for a long line.
    float bar = theme.scrollbars().thickness() + theme.scrollbars().margin();
    float padY = c.compact ? theme.space().s() : theme.space().m();
    float padX = c.compact ? theme.space().m() : theme.space().l();
    field.padding(padY, padX, padY + bar, padX);
    if (highlighter != null) {
      field.highlight(highlighter, highlighter);
    }
    State state = c.options.state;
    if (state != null && state.all && !c.compact) {
      int selection = theme.accent().selection();
      field.decorate((line, start, text, out) -> out.background(0, text.length() + 1, selection));
    }
    if (!c.compact) {
      field.onKey(k -> keys(c, k));
    }
    El box = col()
        .role("group")
        .fill(theme.surface().raised())
        .border(theme.size().stroke(), theme.border().subtle())
        .radius(theme.radius().medium())
        .clip()
        .child(field);
    Clipboard clipboard = c.options.clipboard;
    if (clipboard != null) {
      Button.Copied copied = c.options.state == null ? null : c.options.state.copied;
      String label = Texts.CURRENT.get().copyCode();
      boolean shown = copied != null && copied.shows(label, code); // confirming: seen till it ends
      El copy = Button.copy(clipboard, code, label, copied).key("copy").opacity(shown ? 1 : 0);
      copy.style().position = Style.Position.ABSOLUTE;
      copy.style().top = Len.px(theme.space().s());
      copy.style().right = Len.px(theme.space().s());
      box.child(copy).whenHovered(e -> copy.opacity(1)).whenFocusWithin(e -> copy.opacity(1));
    }
    return box;
  }

  private static final String[] BULLETS = {"•", "◦", "▪"};

  private static El list(Context c, MarkdownDocument.ListBlock list) {
    Theme theme = c.theme;
    float column = markerColumn(c, list);
    float loose = c.compact ? theme.space().m() : theme.space().l();
    El out = col().role("list").gap(list.tight() ? theme.space().xs() : loose);
    for (int i = 0; i < list.items().size(); i++) {
      out.child(item(c, list, i, column));
    }
    return out;
  }

  /** How wide the markers of a list are, measured once per scale: see {@link #measureMarkers}. */
  private static float markerColumn(Context c, MarkdownDocument.ListBlock list) {
    return c.prepared
        .columns
        .computeIfAbsent(WindowState.CURRENT.get().scale(), s -> new IdentityHashMap<>())
        .computeIfAbsent(list, l -> measureMarkers(c, list));
  }

  /** How wide the markers of a list are: its widest number, or room for a bullet. */
  private static float measureMarkers(Context c, MarkdownDocument.ListBlock list) {
    Theme theme = c.theme;
    float column = theme.space().l();
    if (list.ordered()) {
      for (int i = 0; i < list.items().size(); i++) {
        column =
            Math.max(column, TextSizing.natural(marker(theme, (list.start() + i) + ".", c.color)));
      }
    }
    return (float) Math.ceil(column);
  }

  /** An item of a list: its marker, then its blocks, which wrap under each other. */
  private static El item(Context c, MarkdownDocument.ListBlock list, int i, float column) {
    Theme theme = c.theme;
    Theme.TextStyle body = theme.prose().body();
    MarkdownDocument.Item item = list.items().get(i);
    El marker;
    if (item.task() != null) {
      // The box on the middle of the first line, which is the body's line height high.
      float line = lineHeight(body);
      marker = row()
          .w(theme.size().checkbox() + theme.space().xs())
          .shrink(0)
          .padding(Math.max(0, (line - theme.size().checkbox()) / 2), 0, 0, 0)
          .child(Checkbox.checkbox(item.task(), null));
    } else {
      // The bullet goes by how deep the list is in other lists.
      String text = list.ordered() ? (list.start() + i) + "." : BULLETS[c.depth % BULLETS.length];
      marker = marker(theme, text, c.color)
          .w(column)
          .shrink(0)
          .textAlign(list.ordered() ? Style.Align.END : Style.Align.CENTER);
    }
    El content = col().grow(1).minSize(0, 0);
    blocks(c.deeper(), item.blocks(), list.tight(), content);
    El line = row()
        .role("listitem")
        .position(i + 1, list.items().size())
        .gap(theme.space().s())
        .children(marker, content);
    if (item.task() != null) {
      line.checked(item.task());
    }
    return line;
  }

  private static El marker(Theme theme, String text, int color) {
    Theme.TextStyle body = theme.prose().body();
    return El.text(text)
        .font(body.font().get())
        .fontSize(body.size())
        .color(color)
        .fontFeatures("+tnum");
  }

  /**
   * The natural and widest-word column widths of a table's text, measured once per scale and
   * whether the view shows images, whose cells it does not measure.
   */
  private static TableWidths tableWidths(
      Context c,
      MarkdownDocument.Table table,
      List<List<Piece>> cells,
      Theme.TextStyle head,
      Theme.TextStyle body) {
    return c.prepared
        .tables
        .computeIfAbsent(
            new Measured(WindowState.CURRENT.get().scale(), c.options.images != null),
            m -> new IdentityHashMap<>())
        .computeIfAbsent(table, t -> measureTable(cells, table.aligns().size(), head, body));
  }

  private static TableWidths measureTable(
      List<List<Piece>> cells, int columns, Theme.TextStyle head, Theme.TextStyle body) {
    tablesMeasured++;
    float[] natural = new float[columns];
    float[] min = new float[columns];
    for (int r = 0; r < cells.size(); r++) {
      Theme.TextStyle style = r == 0 ? head : body;
      for (int j = 0; j < columns; j++) {
        Piece piece = cells.get(r).get(j);
        if (piece == null) {
          continue; // images, measured on each build
        }
        El sample = El.text(piece.text)
            .wrapText()
            .font(style.font().get())
            .fontSize(style.size())
            .highlight(piece, piece);
        natural[j] = Math.max(natural[j], TextSizing.natural(sample));
        min[j] = Math.max(min[j], TextSizing.minimum(sample));
      }
    }
    return new TableWidths(natural, min);
  }

  private static El table(Context c, MarkdownDocument.Table table) {
    Theme theme = c.theme;
    Theme.TextStyle body = theme.prose().body();
    Theme.TextStyle head = new Theme.TextStyle(theme.type().strong().font(), body.size());
    int columns = table.aligns().size();
    float pad = theme.space().m();
    List<List<Piece>> cells = new ArrayList<>();
    List<Piece> header = new ArrayList<>();
    for (int j = 0; j < columns; j++) {
      List<Inline> content = table.header().get(j);
      header.add(
          c.options.images != null && !imagesOnly(content).isEmpty()
              ? null
              : piece(c, content, content, true));
    }
    cells.add(header);
    for (List<List<Inline>> row : table.rows()) {
      List<Piece> pieces = new ArrayList<>();
      for (int j = 0; j < columns; j++) {
        List<Inline> content = row.get(j);
        pieces.add(
            c.options.images != null && !imagesOnly(content).isEmpty()
                ? null
                : piece(c, content, content, false));
      }
      cells.add(pieces);
    }
    TableWidths widths = tableWidths(c, table, cells, head, body);
    float[] natural = widths.natural().clone();
    float[] least = widths.min().clone();
    // Images are measured on every build: their width changes when they arrive. A picture scales
    // down with its column; a placeholder does not, and counts as a word.
    for (int r = 0; r < cells.size(); r++) {
      for (int j = 0; j < columns; j++) {
        if (cells.get(r).get(j) == null) {
          List<Inline> content =
              r == 0 ? table.header().get(j) : table.rows().get(r - 1).get(j);
          List<Picture> pictures = imagesOnly(content);
          natural[j] = Math.max(natural[j], imageRowWidth(c, pictures));
          for (Picture picture : pictures) {
            if (c.options.images.apply(picture.image().source()) == null) {
              least[j] = Math.max(least[j], imageRowWidth(c, List.of(picture)));
            }
          }
        }
      }
    }
    float[] basis = new float[columns];
    float[] min = new float[columns];
    float totalBasis = 2 * theme.size().stroke();
    float totalMin = 2 * theme.size().stroke();
    for (int j = 0; j < columns; j++) {
      basis[j] = natural[j] + 2 + 2 * pad;
      min[j] = Math.min(basis[j], least[j] + 2 + 2 * pad);
      totalBasis += basis[j];
      totalMin += min[j];
    }
    // Whole pixels: at a fractional scale, a fraction of overflow rounds away and does not scroll.
    totalMin = (float) Math.ceil(totalMin);
    El grid = col()
        .role("table")
        .radius(theme.radius().medium())
        .border(theme.size().stroke(), theme.border().subtle())
        .clip()
        .minSize(totalMin, 0)
        .maxSize(totalBasis, Float.POSITIVE_INFINITY);
    for (int r = 0; r < cells.size(); r++) {
      El line = row().role("row");
      if (r == 0) {
        line.fill(theme.surface().band());
      }
      for (int j = 0; j < columns; j++) {
        Piece piece = cells.get(r).get(j);
        Style.Content justify = switch (table.aligns().get(j)) {
          case CENTER -> Style.Content.CENTER;
          case RIGHT -> Style.Content.END;
          default -> Style.Content.START;
        };
        El cell;
        if (piece == null) {
          List<Inline> content =
              r == 0 ? table.header().get(j) : table.rows().get(r - 1).get(j);
          cell = imageRow(c, imagesOnly(content)).justify(justify).minSize(0, 0);
        } else {
          cell = field(c, piece, r == 0 ? head : body, c.color);
        }
        line.child(row()
            .role(r == 0 ? "columnheader" : "cell")
            .basis(basis[j])
            .minSize(min[j], 0)
            .maxSize(basis[j], Float.POSITIVE_INFINITY)
            .justify(justify)
            .padding(theme.space().s(), pad)
            .child(cell));
      }
      if (r > 0) {
        grid.child(Divider.divider());
      }
      grid.child(line);
    }
    // Room for the bar below only while the table scrolls, that is while the area is narrower
    // than totalMin: then the two parts of totalMin no longer share a line, and the row gap
    // between the two lines is the room.
    El room = row().wrap().child(col().w(totalMin - 1)).child(col().w(1));
    room.style().rowGap =
        Len.px(theme.scrollbars().thickness() + theme.scrollbars().margin());
    return col().scrollBoth().scrollbars(theme.scrollbars()).child(grid).child(room);
  }

  // ---- As text

  private static void text(List<Block> blocks, String indent, StringBuilder out) {
    for (Block b : blocks) {
      switch (b) {
        case MarkdownDocument.Heading h ->
          out.append(indent).append(MarkdownDocument.plainText(h.content())).append("\n\n");
        case MarkdownDocument.Paragraph p ->
          out.append(indent)
              .append(MarkdownDocument.plainText(p.content()).replace("\n", "\n" + indent))
              .append("\n\n");
        case MarkdownDocument.CodeBlock code -> {
          String raw = code.code();
          if (raw.endsWith("\n")) {
            raw = raw.substring(0, raw.length() - 1);
          }
          for (String line : raw.split("\n", -1)) {
            out.append(indent).append(line).append('\n');
          }
          out.append('\n');
        }
        case MarkdownDocument.Quote q -> text(q.blocks(), indent, out);
        case MarkdownDocument.ListBlock l -> {
          for (int i = 0; i < l.items().size(); i++) {
            MarkdownDocument.Item item = l.items().get(i);
            String marker = l.ordered() ? (l.start() + i) + ". " : "- ";
            if (item.task() != null) {
              marker += Boolean.TRUE.equals(item.task()) ? "[x] " : "[ ] ";
            }
            StringBuilder inner = new StringBuilder();
            text(item.blocks(), "", inner);
            String[] lines = inner.toString().strip().split("\n", -1);
            String pad = " ".repeat(marker.length());
            for (int k = 0; k < lines.length; k++) {
              out.append(indent)
                  .append(k == 0 ? marker : lines[k].isEmpty() ? "" : pad)
                  .append(lines[k])
                  .append('\n');
            }
          }
          out.append('\n');
        }
        case MarkdownDocument.ThematicBreak _ -> out.append(indent).append("---\n\n");
        case MarkdownDocument.Table table -> {
          out.append(indent)
              .append(String.join(
                  "\t", table.header().stream().map(MarkdownDocument::plainText).toList()))
              .append('\n');
          for (List<List<Inline>> row : table.rows()) {
            out.append(indent)
                .append(String.join(
                    "\t", row.stream().map(MarkdownDocument::plainText).toList()))
                .append('\n');
          }
          out.append('\n');
        }
      }
    }
  }
}
