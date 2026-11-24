package com.rethinkj.ui.diagram;

import com.rethinkj.ui.Context;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZonedDateTime;

/**
 * The texts the diagram's components write themselves, in one language, as the kit's {@link
 * com.rethinkj.ui.kit.Texts} are for its components. Components read the texts of {@link
 * #CURRENT}, {@link #ENGLISH} unless the app gives others: {@code app.provide(DiagramTexts.CURRENT,
 * Settings::diagramTexts)}. The module comes with {@link #ENGLISH} and {@link GermanDiagramTexts};
 * another language is an implementation of this interface, and a single text an override of its
 * method. A method's default is the English text.
 */
public interface DiagramTexts {

  /** The English texts, the module's default. */
  DiagramTexts ENGLISH = new DiagramTexts() {};

  /** The texts the diagram's components read, {@link #ENGLISH} unless the app gives others. */
  Context<DiagramTexts> CURRENT = new Context<>(ENGLISH);

  /** The label of a {@link Canvas} that the app gives none. */
  default String diagram() {
    return "Diagram";
  }

  /** A canvas's zoom, as its value in a tree dump: {@code Zoom 100%}. */
  default String zoom(int percent) {
    return "Zoom " + percent + "%";
  }

  /** The value of a node that the user moved, which a layout leaves where it is. */
  default String pinned() {
    return "Pinned";
  }

  /** The value of an edge that stands out, {@link Canvas.Emphasis#HIGHLIGHTED}. */
  default String highlighted() {
    return "Highlighted";
  }

  /** The value of an edge that steps back, {@link Canvas.Emphasis#DIMMED}. */
  default String dimmed() {
    return "Dimmed";
  }

  /** The label of an {@link com.rethinkj.ui.diagram.er.ErDiagram}'s canvas. */
  default String erDiagram() {
    return "Entity relationship diagram";
  }

  /** A count of tables, as a schema's frame shows it: {@code 1 table}, {@code 8 tables}. */
  default String tables(int count) {
    return count == 1 ? "1 table" : count + " tables";
  }

  /** A count of columns, as a collapsed table shows it: {@code 6 columns}. */
  default String columns(int count) {
    return count == 1 ? "1 column" : count + " columns";
  }

  /** The row of a table that shows only its keys, for the columns it hides: {@code +3 columns}. */
  default String moreColumns(int count) {
    return "+" + columns(count);
  }

  /** What a table refers to, in its label: {@code references customer, address}. */
  default String references(String tables) {
    return "references " + tables;
  }

  /** What refers to a table, in its label: {@code referenced by order_item}. */
  default String referencedBy(String tables) {
    return "referenced by " + tables;
  }

  /** A column of the primary key, in the column's label. */
  default String primaryKey() {
    return "primary key";
  }

  /** A column of a foreign key, in the column's label. */
  default String foreignKey() {
    return "foreign key";
  }

  /** A column with a unique constraint of its own, in the column's label. */
  default String unique() {
    return "unique";
  }

  /** The short mark in a column's row for a unique column. */
  default String uniqueMark() {
    return "UQ";
  }

  /** A column that may be null, in the column's label. */
  default String nullable() {
    return "nullable";
  }

  /** A column's default, in its tooltip: {@code Default now()}. */
  default String defaultValue(String value) {
    return "Default " + value;
  }

  /** What a foreign key does when the row it refers to is deleted: {@code ON DELETE CASCADE}. */
  default String onDelete(String rule) {
    return "ON DELETE " + rule;
  }

  /** The choice that shows all columns of every table. */
  default String allColumns() {
    return "All columns";
  }

  /** The choice that shows only the key columns of every table. */
  default String keysOnly() {
    return "Keys only";
  }

  /** The choice that shows every table collapsed to its name. */
  default String collapsed() {
    return "Collapsed";
  }

  /** The placeholder of the field that filters tables by name. */
  default String filterTables() {
    return "Filter tables";
  }

  /** Shown on the canvas when a filter matches no table: {@code No tables match “foo”}. */
  default String noTablesMatch(String filter) {
    return "No tables match “" + filter + "”";
  }

  /** The action that zooms so that the whole diagram shows. */
  default String fit() {
    return "Fit";
  }

  /** The action that lays out the tables that the user did not move. */
  default String arrange() {
    return "Arrange";
  }

  /** The action that lays out all tables, also those the user moved. */
  default String arrangeAll() {
    return "Arrange all";
  }

  /** The button that collapses a table to its name. */
  default String collapse() {
    return "Collapse";
  }

  /** The button that shows a collapsed table's columns. */
  default String expand() {
    return "Expand";
  }

  /** A local git branch pill's label and tooltip: {@code Branch main}. */
  default String branch(String name) {
    return "Branch " + name;
  }

  /** The git branch that HEAD points to: {@code HEAD → main}. */
  default String headBranch(String name) {
    return "HEAD → " + name;
  }

  /** Detached HEAD, pointing at a commit rather than a branch. */
  default String detachedHead() {
    return "HEAD (detached)";
  }

  /** A remote git branch pill's label and tooltip: {@code Remote branch origin/main}. */
  default String remoteBranch(String name) {
    return "Remote branch " + name;
  }

  /** A git tag pill's label and tooltip: {@code Tag v1.0}. */
  default String tag(String name) {
    return "Tag " + name;
  }

  /**
   * A local or HEAD branch in sync with one or more remote branches on the same commit: {@code
   * Branch main, in sync with origin/main}.
   */
  default String inSync(String ref, String remotes) {
    return ref + ", in sync with " + remotes;
  }

  /**
   * The overflow pill for refs hidden beyond the limit: {@code 1 more ref: v1.0}, {@code 2 more
   * refs: origin/dev, v1.0}.
   */
  default String moreRefs(int count, String names) {
    return (count == 1 ? "1 more ref: " : count + " more refs: ") + names;
  }

  /** The title of a {@link com.rethinkj.ui.diagram.git.GitLog}. */
  default String history() {
    return "History";
  }

  /** A count of commits in a git log's header: {@code 1 commit}, {@code 24 commits}. */
  default String commits(int count) {
    return count == 1 ? "1 commit" : count + " commits";
  }

  /**
   * A count of loaded commits out of a known total in a git log's header: {@code 12 of 24}.
   */
  default String commitsOf(int loaded, int total) {
    return loaded + " of " + total;
  }

  /** The row at the end of a git log while older commits are loading. */
  default String loadingCommits() {
    return "Loading older commits…";
  }

  /** Shown when a git log has no commits. */
  default String noCommits() {
    return "No commits";
  }

  /** The heading of the graph column in a git log. */
  default String graphColumn() {
    return "Graph";
  }

  /** The heading of the message column in a git log. */
  default String messageColumn() {
    return "Message";
  }

  /** The heading of the author column in a git log. */
  default String authorColumn() {
    return "Author";
  }

  /** The heading of the date column in a git log. */
  default String dateColumn() {
    return "Date";
  }

  /** The heading of the commit hash column in a git log. */
  default String commitColumn() {
    return "Commit";
  }

  /** A count of branches in a git log's footer: {@code 1 branch}, {@code 4 branches}. */
  default String branches(int count) {
    return count == 1 ? "1 branch" : count + " branches";
  }

  /** A count of tags in a git log's footer: {@code 1 tag}, {@code 3 tags}. */
  default String tags(int count) {
    return count == 1 ? "1 tag" : count + " tags";
  }

  /** The prefix of a commit row's label in a tree dump: {@code commit a41f9c2}. */
  default String commitLabel(String shortHash) {
    return "commit " + shortHash;
  }

  /** A month's short name as shown in dates: {@code Nov}. */
  default String monthShort(Month month) {
    return switch (month) {
      case JANUARY -> "Jan";
      case FEBRUARY -> "Feb";
      case MARCH -> "Mar";
      case APRIL -> "Apr";
      case MAY -> "May";
      case JUNE -> "Jun";
      case JULY -> "Jul";
      case AUGUST -> "Aug";
      case SEPTEMBER -> "Sep";
      case OCTOBER -> "Oct";
      case NOVEMBER -> "Nov";
      case DECEMBER -> "Dec";
    };
  }

  /** A commit authored less than a minute ago: {@code just now}. */
  default String justNow() {
    return "just now";
  }

  /** A commit authored less than an hour ago: {@code 38 min ago}. */
  default String minutesAgo(int minutes) {
    return minutes + " min ago";
  }

  /** A commit authored less than a day ago: {@code 2 h ago}. */
  default String hoursAgo(int hours) {
    return hours + " h ago";
  }

  /** A commit authored between one and two days ago: {@code yesterday}. */
  default String yesterday() {
    return "yesterday";
  }

  /** A commit authored between two and six days ago: {@code 3 days ago}. */
  default String daysAgo(int days) {
    return days + " days ago";
  }

  /**
   * A short date for an older commit: {@code Nov 16} in the current year, {@code Nov 16, 2025} in
   * another.
   */
  default String shortDate(LocalDate date, boolean withYear) {
    String base = monthShort(date.getMonth()) + " " + date.getDayOfMonth();
    return withYear ? base + ", " + date.getYear() : base;
  }

  /** A full date and time for a commit's tooltip: {@code Nov 24, 2026, 09:30}. */
  default String fullDate(ZonedDateTime date) {
    return String.format(
        "%s %d, %d, %02d:%02d",
        monthShort(date.getMonth()),
        date.getDayOfMonth(),
        date.getYear(),
        date.getHour(),
        date.getMinute());
  }

  /**
   * A commit's time relative to {@code clock}: {@code just now}, {@code 38 min ago}, {@code 2 h
   * ago}, {@code yesterday}, {@code 3 days ago}, then {@link #shortDate} for a week or more (or a
   * time after {@code clock}).
   */
  default String relativeDate(Instant time, Clock clock) {
    Instant now = clock.instant();
    long seconds = Duration.between(time, now).getSeconds();
    if (seconds < 0) {
      ZonedDateTime at = time.atZone(clock.getZone());
      ZonedDateTime ref = now.atZone(clock.getZone());
      return shortDate(at.toLocalDate(), at.getYear() != ref.getYear());
    }
    if (seconds < 60) {
      return justNow();
    }
    long minutes = seconds / 60;
    if (minutes < 60) {
      return minutesAgo((int) minutes);
    }
    if (minutes < 1440) {
      return hoursAgo((int) (minutes / 60));
    }
    if (minutes < 2880) {
      return yesterday();
    }
    if (minutes < 10080) {
      return daysAgo((int) (minutes / 1440));
    }
    ZonedDateTime at = time.atZone(clock.getZone());
    ZonedDateTime ref = now.atZone(clock.getZone());
    return shortDate(at.toLocalDate(), at.getYear() != ref.getYear());
  }
}
