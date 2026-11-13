package com.rethinkj.ui.kit;

import java.util.ArrayList;
import java.util.List;

/**
 * A Markdown text read into a tree of blocks and inline parts, which {@link Markdown#markdown}
 * shows. {@link #parse} reads CommonMark with the GitHub extensions tables, task list items,
 * strikethrough and bare URLs; it never throws and takes time in proportion to the text. Raw HTML
 * stays text. An app parses a document once and keeps it in its state, so that a rebuild of the
 * view does not read it again: the component keeps what it makes of the document with it. Two
 * documents are equal only when they are the same object.
 */
public final class MarkdownDocument {

  private final List<Block> blocks;
  // What Markdown made of the document for a theme, kept with it; see Markdown#prepared.
  Object prepared;
  Theme preparedFor;

  /** A document of these blocks, as {@link #parse} gives one. */
  public MarkdownDocument(List<Block> blocks) {
    this.blocks = List.copyOf(blocks);
  }

  /** The document's blocks in order. */
  public List<Block> blocks() {
    return blocks;
  }

  /** Reads a Markdown text; never throws, also not on text that is not Markdown. */
  public static MarkdownDocument parse(String source) {
    return MarkdownParser.parse(source);
  }

  /** A block of the document. */
  public sealed interface Block {}

  /**
   * A heading, {@code #} to {@code ######} or underlined with {@code =} (level 1) or {@code -}
   * (level 2).
   *
   * @param id the anchor a link {@code #id} reaches: GitHub's slug of the heading's text (lower
   *     case, spaces as hyphens, punctuation left out), with {@code -1}, {@code -2} after a slug
   *     that an earlier heading of the document has
   */
  public record Heading(int level, List<Inline> content, String id) implements Block {
    public Heading {
      content = List.copyOf(content);
    }
  }

  /** A paragraph. */
  public record Paragraph(List<Inline> content) implements Block {
    public Paragraph {
      content = List.copyOf(content);
    }
  }

  /**
   * A code block, fenced or indented.
   *
   * @param language the first word of a fence's info string, such as {@code sql}, or "" for none
   * @param code the code, each line ending in a line feed
   */
  public record CodeBlock(String language, String code) implements Block {}

  /** A block quote, {@code >}, which may hold any blocks, quotes among them. */
  public record Quote(List<Block> blocks) implements Block {
    public Quote {
      blocks = List.copyOf(blocks);
    }
  }

  /**
   * A list.
   *
   * @param ordered whether its items are numbered
   * @param start the number of the first item of an ordered list
   * @param tight whether no blank line separates its items or their blocks, so that its
   *     paragraphs show without the space between paragraphs
   */
  public record ListBlock(boolean ordered, int start, boolean tight, List<Item> items)
      implements Block {
    public ListBlock {
      items = List.copyOf(items);
    }
  }

  /**
   * An item of a list.
   *
   * @param task null for an ordinary item, {@code false} for {@code [ ]}, {@code true} for {@code
   *     [x]}
   */
  public record Item(Boolean task, List<Block> blocks) {
    public Item {
      blocks = List.copyOf(blocks);
    }
  }

  /** A thematic break, {@code ---}, {@code ***} or {@code ___}. */
  public record ThematicBreak() implements Block {}

  /** How a table's column aligns its cells. */
  public enum Align {
    NONE,
    LEFT,
    CENTER,
    RIGHT
  }

  /**
   * A table (GitHub's extension).
   *
   * @param aligns one per column
   * @param header the header's cells, one per column
   * @param rows the rows, each with one cell per column (cells a row lacks are empty)
   */
  public record Table(List<Align> aligns, List<List<Inline>> header, List<List<List<Inline>>> rows)
      implements Block {
    public Table {
      aligns = List.copyOf(aligns);
      header = header.stream().map(List::copyOf).toList();
      rows = rows.stream().map(r -> r.stream().map(List::copyOf).toList()).toList();
    }
  }

  /** An inline part of a paragraph, heading or table cell. */
  public sealed interface Inline {}

  /** Text, with escapes and entities resolved; raw HTML is text too. */
  public record Text(String text) implements Inline {}

  /** Emphasis, {@code *a*} or {@code _a_}: italic. */
  public record Emphasis(List<Inline> content) implements Inline {
    public Emphasis {
      content = List.copyOf(content);
    }
  }

  /** Strong emphasis, {@code **a**} or {@code __a__}: bold. */
  public record Strong(List<Inline> content) implements Inline {
    public Strong {
      content = List.copyOf(content);
    }
  }

  /** Strikethrough, {@code ~~a~~} (GitHub's extension). */
  public record Strike(List<Inline> content) implements Inline {
    public Strike {
      content = List.copyOf(content);
    }
  }

  /** Inline code, {@code `a`}. */
  public record Code(String code) implements Inline {}

  /**
   * A link: inline, by reference, an autolink {@code <…>} or a bare URL.
   *
   * @param destination the destination as written, escapes and entities resolved, such as
   *     {@code https://example.com} or {@code #text-fields}
   * @param title the title, or "" for none
   */
  public record Link(String destination, String title, List<Inline> content) implements Inline {
    public Link {
      content = List.copyOf(content);
    }
  }

  /**
   * An image.
   *
   * @param alt the plain text of its description
   */
  public record Image(String source, String title, String alt) implements Inline {}

  /** A line break that shows as a space. */
  public record SoftBreak() implements Inline {}

  /** A hard line break: two spaces or a backslash at the end of a line. */
  public record HardBreak() implements Inline {}

  /** The headings of the document, those inside quotes and lists included, in order. */
  public List<Heading> headings() {
    List<Heading> out = new ArrayList<>();
    headings(blocks, out);
    return out;
  }

  private static void headings(List<Block> blocks, List<Heading> out) {
    for (Block b : blocks) {
      switch (b) {
        case Heading h -> out.add(h);
        case Quote q -> headings(q.blocks(), out);
        case ListBlock l -> l.items().forEach(i -> headings(i.blocks(), out));
        default -> {}
      }
    }
  }

  /** The plain text of inline parts, as it shows: soft breaks as spaces, images as their alt. */
  public static String plainText(List<Inline> content) {
    StringBuilder out = new StringBuilder();
    plainText(content, out);
    return out.toString();
  }

  private static void plainText(List<Inline> content, StringBuilder out) {
    for (Inline i : content) {
      switch (i) {
        case Text t -> out.append(t.text());
        case Emphasis e -> plainText(e.content(), out);
        case Strong s -> plainText(s.content(), out);
        case Strike s -> plainText(s.content(), out);
        case Code c -> out.append(c.code());
        case Link l -> plainText(l.content(), out);
        case Image m -> out.append(m.alt());
        case SoftBreak s -> out.append(' ');
        case HardBreak h -> out.append('\n');
      }
    }
  }

  /**
   * The tree as text, one node per line, children indented by two spaces, as the reader's tests
   * compare it.
   */
  public String tree() {
    return MarkdownParser.tree(this);
  }
}
