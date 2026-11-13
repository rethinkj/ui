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
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.RowHeights;
import com.rethinkj.ui.Spans;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.MarkdownDocument.Block;
import com.rethinkj.ui.kit.MarkdownDocument.Inline;
import com.rethinkj.ui.text.Font;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
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
 * El doc = Markdown.markdown(theme, manual,
 *     Markdown.options().onLink(url -> s.open(url)).copy(app.clipboard()).state(selection));
 * El page = Card.flush(theme, doc.grow(1)).grow(1);
 * }</pre>
 *
 * <p>Every block of text is a read-only multi-line field ({@link El#fitLines}), as high as its
 * rows, so that a press and a drag select inside it and Control+C copies that; Tab passes the
 * fields by ({@link El#skipTab}) and reaches the links. Control+A selects the whole document,
 * which then shows as selected, and Control+C copies all of it as text ({@link #text}); a press
 * anywhere ends that. A selection does not reach from one block into the next: the fields are
 * apart, and a selection across them would need one editor over several fields.
 *
 * <p>A link is an element over its range of the text ({@link El#range}): {@code role=link} with
 * its text as the label, in {@link Theme.Prose#link} and underlined, with the pointer's cursor and
 * a tint under it, one stop for Tab, and pressed by Enter; it hands its destination to {@link
 * Options#onLink}. Headings have the key {@code heading-<id>} with GitHub's anchor as the id
 * ({@link MarkdownDocument.Heading#id}), so that the app finds one to scroll to for a link {@code
 * #id}.
 *
 * <p>Code blocks are in the mono type on the raised surface, scroll sideways, and are coloured by
 * the highlighter {@link Options#languages} gives for their language, {@link Sql} for {@code
 * sql}; with {@link Options#copy} a copy button shows in the corner under the pointer. Task list
 * items show the kit's checkbox, which takes no input.
 *
 * <p>Images come from the app: {@link Options#images} gives the image for an address, or null
 * while it has none, and the kit's {@link Image} shows it, or its placeholder with the image's
 * description until then. An image is as large as its pixels at its scale and at most as wide as
 * the page, and its title, if it has one, is its tooltip. It stands as a block: an image alone in
 * its paragraph is one, and one among the text of a paragraph breaks the paragraph there, the
 * text before it above and the text after it below, since a block of text is one field whose
 * lines cannot make room for an image. An image that arrives later makes its row higher; when
 * that row is above the top of the view, the view moves along with it, so that what is in view
 * stays where it is. Without {@link Options#images}, an image shows as its description: in a
 * frame alone in its paragraph, in muted text among other text.
 *
 * <p>What the component makes of a document for a theme it keeps with the document, so a
 * rebuild of the view reads nothing again and highlights nothing again.
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

    private Options() {}

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
     * ({@code SQL}, {@code java}), or null for none; asked before the kit's own, {@link Sql} for
     * {@code sql}. Return the same object or an equal one for a language on each call: it is the
     * block's key for what was highlighted.
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

  /** The document with links that go to {@code onLink}, nothing else. */
  public static El markdown(Theme t, MarkdownDocument document, Consumer<String> onLink) {
    return markdown(t, document, options().onLink(onLink));
  }

  /**
   * The document as a scroll area of its blocks, {@code role=document}, with the theme's spaces
   * between them and {@link Theme.Spacing#xxl} around them, each as wide as the area up to {@link
   * Theme.Prose#measure} and centred in it: a virtual list ({@link El#rows(int, RowHeights,
   * java.util.function.IntFunction)}) whose rows are the document's top-level blocks, so that only
   * the blocks in view and a few around them are built and laid out, however long the document.
   * Give it a height, or let it {@link El#grow} in a column, as any scroll area.
   */
  public static El markdown(Theme t, MarkdownDocument document, Options options) {
    Prepared prepared = prepared(t, document);
    Context c = new Context(t, options, prepared, t.text().primary(), 0);
    List<Unit> units = prepared.units;
    El list = col().role("document").key("markdown").padding(t.space().xxl())
        .scrollbars(t.scrollbars())
        .rows(units.size(), prepared.heights, i -> col().align(Style.Align.CENTER)
            .child(unit(c, units.get(i)).wFull()
                .maxSize(t.prose().measure(), Float.POSITIVE_INFINITY)));
    State state = options.state;
    if (state != null) {
      list.onAnyPointerDown(() -> state.all = false);
      // Overlays in the rows of a list keep no time: the confirmation of a code block's copy
      // button ends by one on the document.
      list.overlay(Button.ending(t, state.copied));
    }
    list.onKey(k -> keys(c, k));
    return list;
  }

  /**
   * The row of the element {@link #markdown} returns that holds the heading with this anchor
   * ({@link MarkdownDocument.Heading#id}), for {@link com.rethinkj.ui.App#scrollToRow}; -1 if
   * there is none. The rows are the top-level blocks, and each item of a top-level list.
   */
  public static int rowOf(MarkdownDocument document, String id) {
    List<Unit> units = units(document);
    for (int i = 0; i < units.size(); i++) {
      List<Block> inside = units.get(i).item < 0 ? List.of(units.get(i).block)
          : ((MarkdownDocument.ListBlock) units.get(i).block).items().get(units.get(i).item)
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
      return spaced(c, u.blocks, u.index, false, block(c, u.block, false));
    }
    Theme t = c.t;
    MarkdownDocument.ListBlock list = (MarkdownDocument.ListBlock) u.block;
    float column = c.prepared.columns.computeIfAbsent(list, l -> markerColumn(c, list));
    El item = item(c, list, u.item, column);
    boolean lastItem = u.item + 1 == list.items().size();
    float below = list.tight() ? t.space().xs() : t.space().l();
    if (lastItem) {
      below = u.index + 1 == u.blocks.size() ? 0 : t.space().l();
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

  /** The blocks of text of a document, made once per theme. */
  private static final class Prepared {
    final MarkdownDocument document;
    final Map<Object, Piece> pieces = new IdentityHashMap<>();
    final Map<Object, Float> columns = new IdentityHashMap<>(); // the widths of lists' markers
    final List<Unit> units;
    final RowHeights heights;
    String text; // the whole document's, for Control+C after Control+A

    Prepared(MarkdownDocument document, Theme t) {
      this.document = document;
      this.units = units(document);
      this.heights = new RowHeights(i -> {
        Unit u = units.get(i);
        return (u.item < 0 ? estimate(t, u.block) : ((MarkdownDocument.ListBlock) u.block).items()
            .get(u.item).blocks().stream().mapToDouble(b -> estimate(t, b)).sum())
            + t.space().l();
      });
    }
  }

  /**
   * About how high a block is at the width of {@link Theme.Prose#measure}, from the length of its
   * text, until it is laid out.
   */
  private static double estimate(Theme t, Block block) {
    double line = t.prose().body().size() * 1.25;
    double perLine = Math.max(t.prose().measure() / (t.prose().body().size() * 0.5), 1);
    return switch (block) {
      case MarkdownDocument.Heading h -> t.prose().headings().get(h.level() - 1).size() * 1.25
          + t.space().xxl();
      case MarkdownDocument.Paragraph p ->
          Math.ceil(MarkdownDocument.plainText(p.content()).length() / perLine) * line;
      case MarkdownDocument.CodeBlock code -> code.code().lines().count()
          * t.type().mono().size() * 1.35 + 2 * t.space().l();
      case MarkdownDocument.Quote q -> q.blocks().stream().mapToDouble(b -> estimate(t, b)).sum();
      case MarkdownDocument.ListBlock l -> l.items().stream().flatMap(i -> i.blocks().stream())
          .mapToDouble(b -> estimate(t, b) + t.space().xs()).sum();
      case MarkdownDocument.ThematicBreak b -> 2 * t.space().m();
      case MarkdownDocument.Table table -> (table.rows().size() + 1) * (line + 2 * t.space().s());
    };
  }

  private static Prepared prepared(Theme t, MarkdownDocument document) {
    synchronized (document) {
      if (document.prepared instanceof Prepared p && document.preparedFor == t) {
        return p;
      }
      Prepared p = new Prepared(document, t);
      document.prepared = p;
      document.preparedFor = t;
      return p;
    }
  }

  /** A range of a piece's text that a highlighter gives: a font, a colour or a line. */
  private record Span(int kind, int from, int to, int color, Font font) {}

  private static final int COLOR = 0;
  private static final int FONT = 1;
  private static final int BACKGROUND = 2;
  private static final int UNDERLINE = 3;
  private static final int STRIKE = 4;

  /** A link of a piece: its range, what it shows and where it goes. */
  private record Link(int from, int to, String label, String destination) {}

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
          case BACKGROUND -> out.background(from, to, s.color);
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

  /** Makes the piece of a run of inline parts, once per document and theme. */
  private static Piece piece(Context c, Object owner, List<Inline> content, boolean heading) {
    Piece kept = c.prepared.pieces.get(owner);
    if (kept != null) {
      return kept;
    }
    StringBuilder text = new StringBuilder();
    List<Span> spans = new ArrayList<>();
    List<Link> links = new ArrayList<>();
    inline(c, content, 0, null, false, heading, text, spans, links);
    Piece piece = new Piece(text.toString(), spans, links);
    c.prepared.pieces.put(owner, piece);
    return piece;
  }

  private static void inline(Context c, List<Inline> parts, int style, String link,
      boolean strike, boolean heading, StringBuilder text, List<Span> spans, List<Link> links) {
    Theme t = c.t;
    for (Inline part : parts) {
      int from = text.length();
      switch (part) {
        case MarkdownDocument.Text x -> text.append(x.text());
        case MarkdownDocument.SoftBreak x -> text.append(' ');
        case MarkdownDocument.HardBreak x -> text.append('\n');
        case MarkdownDocument.Code x -> {
          text.append(x.code());
          spans.add(new Span(FONT, from, text.length(), 0, Font.mono()));
          spans.add(new Span(BACKGROUND, from, text.length(), t.prose().code(), null));
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
            spans.add(new Span(COLOR, from, text.length(), t.prose().link(), null));
            spans.add(new Span(UNDERLINE, from, text.length(), t.prose().link(), null));
            links.add(new Link(from, text.length(), text.substring(from), x.destination()));
          }
          continue;
        }
        case MarkdownDocument.Image x -> {
          text.append(x.alt().isEmpty() ? x.source() : x.alt());
          spans.add(new Span(COLOR, from, text.length(), t.text().muted(), null));
          if (link != null) {
            continue;
          }
        }
      }
      int to = text.length();
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
        spans.add(new Span(STRIKE, from, to, c.color, null));
      }
    }
  }

  // ---- Blocks

  /** What building a document needs, and the colour of its text where it is now. */
  private record Context(Theme t, Options options, Prepared prepared, int color, int depth) {
    Context muted() {
      return new Context(t, options, prepared, t.text().muted(), depth);
    }

    Context deeper() {
      return new Context(t, options, prepared, color, depth + 1);
    }
  }

  private static void blocks(Context c, List<Block> blocks, boolean tight, El into) {
    for (int i = 0; i < blocks.size(); i++) {
      into.child(spaced(c, blocks, i, tight, block(c, blocks.get(i), tight)));
    }
  }

  /**
   * The block with this index among {@code blocks} with the space above and below it: a heading
   * more above than below, nothing after the last block.
   */
  private static El spaced(Context c, List<Block> blocks, int i, boolean tight, El el) {
    Theme t = c.t;
    Block b = blocks.get(i);
    boolean last = i + 1 == blocks.size();
    float below = last ? 0 : tight ? t.space().xs() : t.space().l();
    if (b instanceof MarkdownDocument.Heading && !last) {
      below = t.space().m();
    }
    float above = 0;
    if (b instanceof MarkdownDocument.Heading h && i > 0) {
      above = h.level() <= 2 ? t.space().xxl() : t.space().l();
    }
    return el.margin(above, 0, below, 0);
  }

  private static El block(Context c, Block block, boolean tight) {
    Theme t = c.t;
    return switch (block) {
      case MarkdownDocument.Heading h -> heading(c, h);
      case MarkdownDocument.Paragraph p -> paragraph(c, p);
      case MarkdownDocument.CodeBlock code -> codeBlock(c, code);
      case MarkdownDocument.Quote q -> {
        El content = col().grow(1).minSize(0, 0);
        blocks(c.muted(), q.blocks(), false, content);
        yield row().role("blockquote").gap(t.space().l()).children(
            el().w(t.size().indicator() + 1).shrink(0).radius(t.radius().small() / 2)
                .fill(t.prose().quote()).alignSelf(Style.Align.STRETCH),
            content);
      }
      case MarkdownDocument.ListBlock l -> list(c, l);
      case MarkdownDocument.ThematicBreak x -> Divider.divider(t)
          .margin(t.space().m(), 0, t.space().m(), 0);
      case MarkdownDocument.Table table -> table(c, table);
    };
  }

  /** A read-only field for a block of text: selectable, passed by Tab, as high as its rows. */
  private static El field(Context c, Piece piece, Theme.TextStyle style, int color) {
    Theme t = c.t;
    El field = El.text(piece.text).editable(text -> {}).readOnly().multiline().wrapText()
        .fitLines().skipTab()
        .font(style.font().get()).fontSize(style.size()).color(color)
        .selectionColor(t.accent().selection())
        .caretColor(color)
        .highlight(piece, piece);
    State state = c.options.state;
    if (state != null && state.all) {
      int selection = t.accent().selection();
      field.decorate((line, start, text, out) -> out.background(0, text.length() + 1, selection));
    }
    field.onKey(k -> keys(c, k));
    for (int i = 0; i < piece.links.size(); i++) {
      Link link = piece.links.get(i);
      Consumer<String> onLink = c.options.onLink;
      El el = el().key("link-" + i).role("link").label(link.label).value(link.destination)
          .range(link.from, link.to).radius(t.radius().small())
          .focusable().focusRing(t.accent().edge());
      if (onLink != null) {
        el.cursor(Cursor.POINTER).onPress(() -> onLink.accept(link.destination))
            .whenHovered(e -> e.fill(t.surface().hover()))
            .whenPressed(e -> e.fill(t.surface().pressed()));
      }
      field.child(el);
    }
    return field;
  }

  /** Control+A selects the document, Control+C after it copies the whole of it. */
  private static boolean keys(Context c, Key k) {
    State state = c.options.state;
    if (state == null || k.modifiers() != Key.CONTROL) {
      return false;
    }
    if (k.name().equals("A")) {
      state.all = true;
      return true;
    }
    if ((k.name().equals("C") || k.name().equals("Insert")) && state.all
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
    Theme t = c.t;
    Theme.TextStyle style = t.prose().headings().get(h.level() - 1);
    int color = h.level() == 6 ? t.text().muted() : c.color == t.text().muted() ? c.color
        : t.text().primary();
    El field = field(c, piece(c, h, h.content(), true), style, color)
        .role("heading").level(h.level()).key("heading-" + h.id());
    if (h.level() > 2) {
      return field;
    }
    // The two top levels stand over a hairline, as on a page of documentation.
    field.padding(0, 0, t.space().s(), 0);
    return col().children(field, Divider.divider(t));
  }

  private static El paragraph(Context c, MarkdownDocument.Paragraph p) {
    Theme t = c.t;
    List<Inline> content = p.content();
    if (content.size() == 1 && content.getFirst() instanceof MarkdownDocument.Image image) {
      return image(c, image);
    }
    if (c.options.images == null
        || content.stream().noneMatch(i -> i instanceof MarkdownDocument.Image)) {
      return field(c, piece(c, p, content, false), t.prose().body(), c.color).role("paragraph");
    }
    // Images among the text break the paragraph: text, image, text, each a block of its own.
    El blocks = col().gap(t.space().l());
    Object owner = p; // a run of text is kept by the paragraph, or by the image before it
    List<Inline> run = new ArrayList<>();
    for (Inline part : content) {
      if (part instanceof MarkdownDocument.Image image) {
        run(c, owner, run, blocks);
        blocks.child(image(c, image));
        owner = image;
        run = new ArrayList<>();
      } else {
        run.add(part);
      }
    }
    run(c, owner, run, blocks);
    return blocks;
  }

  /** A run of a paragraph's text between its images, without the breaks around it, if any. */
  private static void run(Context c, Object owner, List<Inline> run, El into) {
    while (!run.isEmpty() && (run.getFirst() instanceof MarkdownDocument.SoftBreak
        || run.getFirst() instanceof MarkdownDocument.HardBreak)) {
      run.removeFirst();
    }
    while (!run.isEmpty() && (run.getLast() instanceof MarkdownDocument.SoftBreak
        || run.getLast() instanceof MarkdownDocument.HardBreak)) {
      run.removeLast();
    }
    if (!run.isEmpty() && run.getFirst() instanceof MarkdownDocument.Text x) {
      run.set(0, new MarkdownDocument.Text(x.text().stripLeading()));
    }
    if (!run.isEmpty() && run.getLast() instanceof MarkdownDocument.Text x) {
      run.set(run.size() - 1, new MarkdownDocument.Text(x.text().stripTrailing()));
    }
    if (MarkdownDocument.plainText(run).isBlank()) {
      return;
    }
    into.child(field(c, piece(c, owner, run, false), c.t.prose().body(), c.color)
        .role("paragraph"));
  }

  /**
   * An image as a block: the app's image, at most as wide as the page, with its title as its
   * tooltip, or the placeholder with its description until the app has it; without {@link
   * Options#images}, the description in a frame.
   */
  private static El image(Context c, MarkdownDocument.Image image) {
    Theme t = c.t;
    String alt = image.alt().isEmpty() ? image.source() : image.alt();
    com.rethinkj.ui.Image picture = c.options.images == null ? null
        : c.options.images.apply(image.source());
    El el = Image.image(t, picture, alt);
    if (picture == null) {
      el.justify(Style.Content.START);
      el.children().getLast().grow(1);
      return el;
    }
    if (!image.title().isEmpty()) {
      Tooltip.tooltip(t, el, image.title());
    }
    // In a column of its own, which the page stretches, so that a small image keeps its size and
    // a large one is as wide as the page (El.image takes no more than the width there is).
    return col().align(Style.Align.START).child(el);
  }

  private static El codeBlock(Context c, MarkdownDocument.CodeBlock block) {
    Theme t = c.t;
    String code = block.code().endsWith("\n")
        ? block.code().substring(0, block.code().length() - 1) : block.code();
    String language = block.language();
    Highlighter highlighter = c.options.languages.apply(language);
    if (highlighter == null && language.toLowerCase(Locale.ROOT).matches("sql|pgsql|postgresql")) {
      highlighter = Sql.of(t);
    }
    Theme.TextStyle mono = t.type().mono();
    El field = El.text(code).editable(text -> {}).readOnly().multiline().fitLines().skipTab()
        .role("code").label(language.isEmpty() ? null : language)
        .font(mono.font().get()).fontSize(mono.size()).color(t.text().primary())
        .selectionColor(t.accent().selection()).caretColor(t.text().primary())
        .scrollbars(t.scrollbars());
    // Below the last line, the room of the bar along the bottom, which shows for a long line.
    float bar = t.scrollbars().thickness() + t.scrollbars().margin();
    field.padding(t.space().m(), t.space().l(), t.space().m() + bar, t.space().l());
    if (highlighter != null) {
      field.highlight(highlighter, highlighter);
    }
    State state = c.options.state;
    if (state != null && state.all) {
      int selection = t.accent().selection();
      field.decorate((line, start, text, out) -> out.background(0, text.length() + 1, selection));
    }
    field.onKey(k -> keys(c, k));
    El box = col().role("group").fill(t.surface().raised())
        .border(t.size().stroke(), t.border().subtle()).radius(t.radius().medium()).clip()
        .child(field);
    Clipboard clipboard = c.options.clipboard;
    if (clipboard != null) {
      Button.Copied copied = c.options.state == null ? null : c.options.state.copied;
      boolean shown = copied != null && copied.shows("Copy code", code); // confirming: seen till it ends
      El copy = Button.copy(t, clipboard, code, "Copy code", copied).key("copy")
          .opacity(shown ? 1 : 0);
      copy.style().position = Style.Position.ABSOLUTE;
      copy.style().top = Len.px(t.space().s());
      copy.style().right = Len.px(t.space().s());
      box.child(copy).whenHovered(e -> copy.opacity(1)).whenFocusWithin(e -> copy.opacity(1));
    }
    return box;
  }

  private static final String[] BULLETS = {"•", "◦", "▪"};

  private static El list(Context c, MarkdownDocument.ListBlock list) {
    Theme t = c.t;
    float column = markerColumn(c, list);
    El out = col().role("list").gap(list.tight() ? t.space().xs() : t.space().l());
    for (int i = 0; i < list.items().size(); i++) {
      out.child(item(c, list, i, column));
    }
    return out;
  }

  /** How wide the markers of a list are: its widest number, or room for a bullet. */
  private static float markerColumn(Context c, MarkdownDocument.ListBlock list) {
    Theme t = c.t;
    float column = t.space().l();
    if (list.ordered()) {
      for (int i = 0; i < list.items().size(); i++) {
        column = Math.max(column, marker(t, (list.start() + i) + ".", c.color).text()
            .measure(Float.NaN, Float.NaN, Measure.MAX_CONTENT, Measure.MAX_CONTENT).width());
      }
    }
    return (float) Math.ceil(column);
  }

  /** An item of a list: its marker, then its blocks, which wrap under each other. */
  private static El item(Context c, MarkdownDocument.ListBlock list, int i, float column) {
    Theme t = c.t;
    Theme.TextStyle body = t.prose().body();
    MarkdownDocument.Item item = list.items().get(i);
    El marker;
    if (item.task() != null) {
      // The box on the middle of the first line, which is the body's line height high.
      Font font = body.font().get();
      float line = Math.round((font.ascent() + font.descent() + font.lineGap()) * body.size());
      marker = row().w(t.size().checkbox() + t.space().xs()).shrink(0)
          .padding(Math.max(0, (line - t.size().checkbox()) / 2), 0, 0, 0)
          .child(Checkbox.checkbox(t, item.task(), null));
    } else {
      // The bullet goes by how deep the list is in other lists.
      String text = list.ordered() ? (list.start() + i) + "."
          : BULLETS[c.depth % BULLETS.length];
      marker = marker(t, text, c.color).w(column).shrink(0)
          .textAlign(list.ordered() ? Style.Align.END : Style.Align.CENTER);
    }
    El content = col().grow(1).minSize(0, 0);
    blocks(c.deeper(), item.blocks(), list.tight(), content);
    El line = row().role("listitem").position(i + 1, list.items().size())
        .gap(t.space().s()).children(marker, content);
    if (item.task() != null) {
      line.checked(item.task());
    }
    return line;
  }

  private static El marker(Theme t, String text, int color) {
    Theme.TextStyle body = t.prose().body();
    return El.text(text).font(body.font().get()).fontSize(body.size()).color(color)
        .fontFeatures("+tnum");
  }

  private static El table(Context c, MarkdownDocument.Table table) {
    Theme t = c.t;
    Theme.TextStyle body = t.prose().body();
    Theme.TextStyle head = new Theme.TextStyle(t.type().strong().font(), body.size());
    int columns = table.aligns().size();
    float pad = t.space().m();
    float[] widths = new float[columns];
    List<List<Piece>> cells = new ArrayList<>();
    List<Piece> header = new ArrayList<>();
    for (int j = 0; j < columns; j++) {
      List<Inline> content = table.header().get(j);
      header.add(piece(c, content, content, true));
    }
    cells.add(header);
    for (List<List<Inline>> row : table.rows()) {
      List<Piece> pieces = new ArrayList<>();
      for (int j = 0; j < columns; j++) {
        List<Inline> content = row.get(j);
        pieces.add(piece(c, content, content, false));
      }
      cells.add(pieces);
    }
    for (int r = 0; r < cells.size(); r++) {
      for (int j = 0; j < columns; j++) {
        Piece piece = cells.get(r).get(j);
        Theme.TextStyle style = r == 0 ? head : body;
        float width = El.text(piece.text).font(style.font().get()).fontSize(style.size())
            .highlight(piece, piece).text()
            .measure(Float.NaN, Float.NaN, Measure.MAX_CONTENT, Measure.MAX_CONTENT).width();
        widths[j] = Math.max(widths[j], Math.min(width + 2, t.prose().measure() / 2));
      }
    }
    El grid = col().role("table").radius(t.radius().medium())
        .border(t.size().stroke(), t.border().subtle()).clip();
    grid.style().width = Len.FIT_CONTENT;
    for (int r = 0; r < cells.size(); r++) {
      El line = row().role("row");
      if (r == 0) {
        line.fill(t.surface().band());
      }
      for (int j = 0; j < columns; j++) {
        Piece piece = cells.get(r).get(j);
        El field = field(c, piece, r == 0 ? head : body, c.color).w(widths[j]);
        Style.Content justify = switch (table.aligns().get(j)) {
          case CENTER -> Style.Content.CENTER;
          case RIGHT -> Style.Content.END;
          default -> Style.Content.START;
        };
        float measured = El.text(piece.text).font((r == 0 ? head : body).font().get())
            .fontSize((r == 0 ? head : body).size()).highlight(piece, piece).text()
            .measure(Float.NaN, Float.NaN, Measure.MAX_CONTENT, Measure.MAX_CONTENT).width();
        if (measured + 2 < widths[j]) {
          field.w((float) Math.ceil(measured + 2));
        }
        line.child(row().role(r == 0 ? "columnheader" : "cell").w(widths[j] + 2 * pad).shrink(0)
            .justify(justify).padding(t.space().s(), pad).child(field));
      }
      if (r > 0) {
        grid.child(Divider.divider(t));
      }
      grid.child(line);
    }
    El area = col().scrollBoth().scrollbars(t.scrollbars()).child(grid);
    area.style().paddingBottom = Len.px(t.scrollbars().thickness() + t.scrollbars().margin());
    return area;
  }

  // ---- As text

  private static void text(List<Block> blocks, String indent, StringBuilder out) {
    for (Block b : blocks) {
      switch (b) {
        case MarkdownDocument.Heading h ->
            out.append(indent).append(MarkdownDocument.plainText(h.content())).append("\n\n");
        case MarkdownDocument.Paragraph p -> out.append(indent)
            .append(MarkdownDocument.plainText(p.content()).replace("\n", "\n" + indent))
            .append("\n\n");
        case MarkdownDocument.CodeBlock code -> {
          for (String line : code.code().split("\n", -1)) {
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
              marker += item.task() ? "[x] " : "[ ] ";
            }
            StringBuilder inner = new StringBuilder();
            text(item.blocks(), "", inner);
            String[] lines = inner.toString().strip().split("\n", -1);
            String pad = " ".repeat(marker.length());
            for (int k = 0; k < lines.length; k++) {
              out.append(indent).append(k == 0 ? marker : lines[k].isEmpty() ? "" : pad)
                  .append(lines[k]).append('\n');
            }
          }
          out.append('\n');
        }
        case MarkdownDocument.ThematicBreak x -> out.append(indent).append("---\n\n");
        case MarkdownDocument.Table table -> {
          out.append(indent).append(String.join("\t",
              table.header().stream().map(MarkdownDocument::plainText).toList())).append('\n');
          for (List<List<Inline>> row : table.rows()) {
            out.append(indent).append(String.join("\t",
                row.stream().map(MarkdownDocument::plainText).toList())).append('\n');
          }
          out.append('\n');
        }
      }
    }
  }
}
