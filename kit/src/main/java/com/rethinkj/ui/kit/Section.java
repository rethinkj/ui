package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Label.label;

import com.rethinkj.ui.El;
import java.util.Arrays;
import java.util.Objects;

/**
 * A heading with an optional description above what it is about, and a page of such sections
 * below its title, as settings, a detail panel or a dashboard are laid out:
 *
 * <pre>{@code
 * page(t, "Editor",
 *     section(t, "Text", null, group(t, ...)),
 *     section(t, "Files", "What the editor does when it saves", group(t, ...), card(t, ...)))
 * }</pre>
 *
 * <p>The headings come in three levels of {@link Theme.Type}: a page's title, a section's
 * heading, and a card's title ({@link Card#header}). The spacing follows them: a page has {@link
 * Theme.Spacing#xxl} of padding and its title and sections are {@code xxl} apart, a section's
 * content is {@link Theme.Spacing#m} below its heading and description and {@code m} apart, and a
 * description is {@link Theme.Spacing#xs} below its heading. In a tree dump the headings are
 * {@code role=heading} with {@code level=1} for a page's title and {@code level=2} for a
 * section's.
 */
public final class Section {

  private Section() {}

  /**
   * @param description muted text below the heading that wraps; null for none
   * @param content cards, groups or anything else, stacked in a column; null entries are left
   *     out, so that a view can write {@code changed ? callout(...) : null}
   */
  public static El section(Theme t, String title, String description, El... content) {
    El head = col().gap(t.space().xs())
        .child(label(t, Label.Variant.HEADING, title).role("heading").level(2));
    if (description != null) {
      head.child(label(t, Label.Variant.MUTED, description).wrapText());
    }
    return col().gap(t.space().m()).child(head).children(present(content));
  }

  /**
   * A page that scrolls up and down, with the theme's scrollbars: its title and its sections,
   * which take its whole width; null sections are left out. It needs a height of its own or room
   * to grow, as any scroll area.
   */
  public static El page(Theme t, String title, El... sections) {
    return col().scroll().scrollbars(t.scrollbars())
        .padding(t.space().xxl()).gap(t.space().xxl())
        .child(label(t, Label.Variant.TITLE, title).role("heading").level(1))
        .children(present(sections));
  }

  /** The elements that are not null. */
  static El[] present(El... elements) {
    return Arrays.stream(elements).filter(Objects::nonNull).toArray(El[]::new);
  }
}
