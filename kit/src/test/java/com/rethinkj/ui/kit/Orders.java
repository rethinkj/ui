package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.DataGrid.dataGrid;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.Sort;
import com.rethinkj.ui.text.TextRendering;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * A result set of orders as a database tool holds it: the rows, made up from their index, and the
 * grid's state, which the grid's handlers change and log. The view puts the grid in a window with
 * a margin, which leaves room for the focus ring.
 */
final class Orders {

  private static final String[] FIRST = {
    "Ada", "Grace", "Linus", "Margaret", "Ken", "Barbara", "Edsger", "Frances", "Donald", "Radia"
  };
  private static final String[] LAST = {
    "Lovelace", "Hopper", "Torvalds", "Hamilton", "Thompson", "Liskov", "Dijkstra", "Allen",
    "Knuth", "Perlman"
  };
  private static final String[] STATUS = {"paid", "pending", "shipped", "refunded"};
  private static final LocalDateTime START = LocalDateTime.of(2026, 3, 1, 8, 0);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  static final String NOTE =
      "Customer asked to split the delivery into two parcels and to leave them next door";

  /** The seven columns of an order, repeated beyond seven with a number after the title. */
  private static final List<Column> BASE = List.of(
      new Column("id", 64, Align.RIGHT, true, i -> String.valueOf(i + 1)),
      new Column("customer", 140, Align.LEFT, false, Orders::customer),
      new Column("email", 200, Align.LEFT, false,
          i -> customer(i).toLowerCase(Locale.ROOT).replace(' ', '.') + "@example.com"),
      new Column("created_at", 150, Align.LEFT, true,
          i -> START.plusSeconds(i * 97L).format(TIME)),
      new Column("amount", 90, Align.RIGHT, true, i -> i % 11 == 5 ? null
          : String.format(Locale.ROOT, "%d.%02d", i * 7919 % 250_000 / 100, i * 13 % 100)),
      new Column("status", 80, Align.LEFT, false, i -> STATUS[i * 7 % STATUS.length]),
      new Column("note", 240, Align.LEFT, false, i -> i % 3 != 1 ? null : NOTE));

  final Theme theme;
  int count;
  int selected = 2;
  final float[] widths;
  Sort sort = new Sort(0, false);
  final List<String> log = new ArrayList<>();
  final List<Integer> asked = new ArrayList<>(); // the rows whose cells were asked for

  Orders(Theme theme, int count) {
    this(theme, count, BASE.size());
  }

  Orders(Theme theme, int count, int columns) {
    this.theme = theme;
    this.count = count;
    this.widths = new float[columns];
    for (int c = 0; c < columns; c++) {
      widths[c] = BASE.get(c % BASE.size()).width();
    }
  }

  static String customer(int i) {
    return FIRST[i % FIRST.length] + " " + LAST[i / FIRST.length % LAST.length];
  }

  List<Column> columns() {
    List<Column> columns = new ArrayList<>();
    for (int c = 0; c < widths.length; c++) {
      Column base = BASE.get(c % BASE.size());
      IntFunction<String> cell = base.cell();
      columns.add(new Column(
          c < BASE.size() ? base.title() : base.title() + "_" + c / BASE.size(),
          widths[c], base.align(), base.mono(), i -> {
            asked.add(i);
            return cell.apply(i);
          }));
    }
    return columns;
  }

  El view() {
    return col().wFull().hFull().padding(theme.space().m()).fill(theme.surface().panel())
        .child(grid().key("orders").label("Orders").grow(1));
  }

  El grid() {
    return dataGrid(theme, columns(), count, selected,
        i -> {
          log.add("select " + i);
          selected = i;
        },
        (c, w) -> {
          log.add("resize " + c + " " + w);
          widths[c] = w;
        },
        sort,
        c -> {
          log.add("sort " + c);
          sort = new Sort(c, sort != null && sort.column() == c && !sort.descending());
        });
  }

  App<Orders> app() {
    App<Orders> app = new App<>(this, Orders::view);
    app.textRendering(TextRendering.LINUX);
    return app;
  }
}
