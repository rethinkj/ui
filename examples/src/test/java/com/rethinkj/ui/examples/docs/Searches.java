package com.rethinkj.ui.examples.docs;

import com.rethinkj.ui.kit.CommandPalette;
import com.rethinkj.ui.kit.FilterList;
import com.rethinkj.ui.kit.Fuzzy;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** The example of a search of one's own with Fuzzy, for the command palette's page. */
final class Searches {

  private Searches() {}

  record Table(String schema, String name) {}

  // docs:start fuzzy
  /** The 50 tables whose names match best, with their schemas beside them. */
  static List<FilterList.Entry> tables(String typed, List<Table> all, Consumer<Table> open) {
    record Scored(Table table, int score) {}
    Fuzzy.Matcher matcher = Fuzzy.matcher(typed); // reused for every name: allocates nothing
    return all.stream()
        .map(table -> new Scored(table, matcher.score(table.name())))
        .filter(scored -> scored.score() != Fuzzy.NO_MATCH)
        .sorted(Comparator.comparingInt(Scored::score).reversed())
        .limit(50)
        .map(scored -> FilterList.entry(scored.table().name(), () -> open.accept(scored.table()))
            .detail(scored.table().schema()))
        .toList();
  }

  static CommandPalette.Source source(List<Table> all, Consumer<Table> open) {
    return new CommandPalette.Source(
        "@", "tables", FilterList.Entries.by(typed -> tables(typed, all, open)));
  }
  // docs:end fuzzy
}
