package com.rethinkj.ui.editor;

import com.rethinkj.ui.kit.MarkdownDocument;
import java.util.List;
import java.util.Objects;

/**
 * A language-neutral model of a parsed doc comment (such as Javadoc or JSDoc), produced by {@link
 * Language#docComment}.
 *
 * @param body the main prose of the doc comment as Markdown, or {@code null} when absent
 * @param parameters the documented parameters in order; never {@code null}
 * @param returns the return-value description as Markdown, or {@code null} when absent
 * @param exceptions the documented exceptions or errors in order; never {@code null}
 * @param see the {@code @see} references as Markdown in order; never {@code null}
 * @param deprecated the deprecation note as Markdown, or {@code null} when absent
 */
public record DocComment(
    MarkdownDocument body,
    List<Entry> parameters,
    MarkdownDocument returns,
    List<Entry> exceptions,
    List<MarkdownDocument> see,
    MarkdownDocument deprecated) {

  public DocComment {
    parameters = List.copyOf(parameters);
    exceptions = List.copyOf(exceptions);
    see = List.copyOf(see);
  }

  /**
   * One named entry in a doc comment, such as a parameter or a thrown exception.
   *
   * @param name the parameter or exception name
   * @param type the type given by the doc comment (such as JSDoc's {@code {type}}), or {@code null}
   *     when the language gives none (as in Javadoc)
   * @param description the entry's description as Markdown, or {@code null} when absent
   */
  public record Entry(String name, String type, MarkdownDocument description) {
    public Entry {
      Objects.requireNonNull(name, "name");
    }
  }
}
