package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.DataGrid.Align;
import com.rethinkj.ui.kit.DataGrid.CellValue;
import com.rethinkj.ui.kit.DataGrid.Column;
import com.rethinkj.ui.kit.DataGrid.DataType;
import com.rethinkj.ui.kit.DataGrid.Filter;
import com.rethinkj.ui.kit.DataGrid.Mode;
import com.rethinkj.ui.kit.DataGrid.Selection;
import com.rethinkj.ui.kit.DataGrid.SortKey;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Typed columns over rows of records and POJOs. */
class GridTableTest {

  enum Kind {
    BOOK,
    GAME,
    TOOL;

    String label() {
      return name().toLowerCase();
    }
  }

  static final Format<Kind> KIND = Format.choice(List.of(Kind.values()), Kind::label);

  record Item(long id, String name, BigDecimal price, LocalDate due, Kind kind) {
    Item withName(String name) {
      return new Item(id, name, price, due, kind);
    }

    Item withPrice(BigDecimal price) {
      return new Item(id, name, price, due, kind);
    }

    Item withDue(LocalDate due) {
      return new Item(id, name, price, due, kind);
    }

    Item withKind(Kind kind) {
      return new Item(id, name, price, due, kind);
    }
  }

  static final Part<Account, BigDecimal> BALANCE =
      Part.ofSetter(Account::getBalance, Account::setBalance);

  /** A POJO with setters. */
  static final class Account {
    private String owner;
    private BigDecimal balance;

    Account(String owner, BigDecimal balance) {
      this.owner = owner;
      this.balance = balance;
    }

    String getOwner() {
      return owner;
    }

    void setOwner(String owner) {
      this.owner = owner;
    }

    BigDecimal getBalance() {
      return balance;
    }

    void setBalance(BigDecimal balance) {
      this.balance = balance;
    }
  }

  static final class State {
    final List<Item> items = new ArrayList<>();
    final Selection selection = new Selection(Mode.CELLS);
    final List<String> log = new ArrayList<>();
    int loaded = Integer.MAX_VALUE;

    State() {
      for (int i = 0; i < 40; i++) {
        items.add(new Item(
            i + 1,
            "item " + i,
            BigDecimal.valueOf(i * 125L, 2),
            i % 3 == 0 ? null : LocalDate.of(2026, 1, 1).plusDays(i),
            Kind.values()[i % 3]));
      }
      selection.select(0, 1);
    }

    Item item(int i) {
      return i >= loaded ? null : items.get(i);
    }
  }

  static List<Column> columns(GridTable<Item> table) {
    return List.of(
        table.column("id", 60, Format.LONG, Item::id),
        table.column("name", 120, Format.TEXT, Item::name, Item::withName),
        table.column(
            "price",
            90,
            Format.DECIMAL.where(p -> p.signum() >= 0, "Not below zero"),
            Item::price,
            Item::withPrice),
        table.column("due", 110, Format.DATE.optional(), Item::due, Item::withDue),
        table.column("kind", 80, KIND, Item::kind, Item::withKind));
  }

  static El view(State s) {
    GridTable<Item> table = DataGrid.table(s::item);
    List<Column> columns = columns(table);
    return col()
        .wFull()
        .hFull()
        .padding(8)
        .child(DataGrid.dataGrid(
                Theme.DARK,
                columns,
                s.items.size(),
                DataGrid.options()
                    .select(s.selection)
                    .loading(table::loaded, (first, last) -> {})
                    .onCommit(table, (row, item) -> {
                      if (item.name().isBlank()) {
                        return "A name is required";
                      }
                      s.log.add(row + " " + item);
                      s.items.set(row, item);
                      return null;
                    }))
            .key("items")
            .grow(1));
  }

  // Without native libraries

  @Test
  void aColumnTakesItsLookAndEditorFromItsFormat() {
    State s = new State();
    List<Column> columns = columns(DataGrid.table(s::item));
    Column id = columns.get(0);
    assertEquals(Align.RIGHT, id.align());
    assertTrue(id.mono());
    assertEquals(DataType.INTEGER, id.type());
    assertEquals(Symbol.NUMBER_SYMBOL, id.icon());
    assertNull(id.editing().rows(), "a column without a writer edits nothing");
    assertEquals("1", id.cell().apply(0));
    Column due = columns.get(3);
    assertTrue(due.editing().nullable(), "an optional format allows NULL");
    assertNull(due.cell().apply(0), "NULL");
    assertEquals("2026-01-02", due.cell().apply(1));
    Column kind = columns.get(4);
    assertEquals(DataType.ENUM, kind.type());
    assertEquals(List.of("book", "game", "tool"), kind.editing().choices());
    assertEquals("12.50", columns.get(2).cell().apply(10));
    s.loaded = 5;
    assertNull(columns.get(1).cell().apply(7), "a row not loaded");
  }

  @Test
  void aWitherHandsTheAppANewRecordAndARefusalChangesNothing() {
    State s = new State();
    GridTable<Item> table = DataGrid.table(s::item);
    List<Column> columns = columns(table);
    List<Item> told = new ArrayList<>();
    DataGrid.Commit commit = table.commit(columns, (row, item) -> {
      told.add(item);
      return item.name().isBlank() ? "A name is required" : null;
    });
    Item before = s.items.get(2);
    assertNull(commit.commit(2, 2, "9.99"));
    assertEquals(new BigDecimal("9.99"), told.getLast().price());
    assertEquals(before, s.items.get(2), "the app stores it, not the table");
    assertNull(commit.commit(2, 4, "tool"));
    assertEquals(Kind.TOOL, told.getLast().kind());
    assertNull(commit.commit(2, 3, null));
    assertNull(told.getLast().due(), "NULL");
    assertEquals("A name is required", commit.commit(2, 1, " "));
  }

  @Test
  void aSetterChangesThePojoAndARefusalSetsItBack() {
    List<Account> accounts = List.of(
        new Account("Ada", new BigDecimal("10.00")), new Account("Bob", new BigDecimal("5")));
    GridTable<Account> table = DataGrid.table(accounts::get);
    List<Column> columns = List.of(
        table.column("owner", 100, Format.TEXT, Account::getOwner, Account::setOwner),
        table.column("balance", 100, Format.DECIMAL, Account::getBalance, Account::setBalance));
    List<String> told = new ArrayList<>();
    DataGrid.Commit commit = table.commit(columns, (row, account) -> {
      told.add(row + " " + account.getBalance());
      return account.getBalance().signum() < 0 ? "No debts" : null;
    });
    assertNull(commit.commit(0, 1, "12.5"));
    assertEquals(new BigDecimal("12.5"), accounts.get(0).getBalance());
    assertEquals("No debts", commit.commit(0, 1, "-1"));
    assertEquals(new BigDecimal("12.5"), accounts.get(0).getBalance(), "set back");
    assertEquals(List.of("0 12.5", "0 -1"), told);

    // A part, as a form's Editing uses: the same setter, and the same refusal.
    assertTrue(table.column("balance", 100, Format.DECIMAL, BALANCE).cells().writes());
    DataGrid.Commit partCommit = table.commit(
        List.of(columns.get(0), table.column("balance", 100, Format.DECIMAL, BALANCE)),
        (row, account) -> account.getBalance().signum() < 0 ? "No debts" : null);
    assertEquals("No debts", partCommit.commit(1, 1, "-2"));
    assertEquals(new BigDecimal("5"), accounts.get(1).getBalance(), "set back");
    assertNull(partCommit.commit(1, 1, "6"));
    assertEquals(new BigDecimal("6"), accounts.get(1).getBalance());

    // Pasting writes the cells of a row at once and tells the app once per row.
    table
        .paste(columns, accounts.size(), (row, account) -> {
          told.add(row + " " + account.getOwner() + " " + account.getBalance());
          return null;
        })
        .accept(List.of(
            new CellValue(1, 0, "Bea"), new CellValue(1, 1, "7"), new CellValue(0, 0, "Al")));
    assertEquals(List.of("1 Bea 7", "0 Al 12.5"), told.subList(2, 4));
  }

  @Test
  void aPastedRecordRowGetsAllItsValues() {
    State s = new State();
    GridTable<Item> table = DataGrid.table(s::item);
    List<Item> told = new ArrayList<>();
    table
        .paste(columns(table), s.items.size(), (row, item) -> {
          told.add(item);
          return null;
        })
        .accept(List.of(new CellValue(3, 1, "pasted"), new CellValue(3, 2, "1.5")));
    assertEquals(1, told.size());
    assertEquals("pasted", told.getFirst().name());
    assertEquals(new BigDecimal("1.5"), told.getFirst().price());
  }

  @Test
  void theAppSortsAndFiltersItsRowsByTheTypedValues() {
    State s = new State();
    GridTable<Item> table = DataGrid.table(s::item);
    List<Column> columns = columns(table);
    List<Item> sorted = new ArrayList<>(s.items);
    sorted.sort(table.comparator(columns, List.of(new SortKey(2, true))));
    assertEquals(new BigDecimal("48.75"), sorted.getFirst().price(), "by number, not by text");
    sorted.sort(table.comparator(columns, List.of(new SortKey(3, false), new SortKey(0, true))));
    assertNull(sorted.getFirst().due(), "NULL first");
    assertEquals(40, sorted.getFirst().id(), "then by the second key");

    // 9.00 is between 8.5 and 10, which as texts it is not.
    List<Item> kept = s.items.stream()
        .filter(table.filter(columns, List.of(Filter.between(2, "8.5", "10"))))
        .toList();
    assertEquals(List.of(8L, 9L), kept.stream().map(Item::id).toList());
    assertEquals(
        13,
        s.items.stream()
            .filter(table.filter(columns, List.of(Filter.oneOf(4, List.of("game")))))
            .count());
    assertTrue(
        Filter.of(2, Filter.Kind.EQUALS, "10").matches(new BigDecimal("10.00"), Format.DECIMAL));
    assertTrue(Filter.of(3, Filter.Kind.IS_NULL, "").matches(null, Format.DATE));
    assertFalse(Filter.of(1, Filter.Kind.CONTAINS, "x").matches("item", Format.TEXT));
    assertTrue(
        Filter.of(1, Filter.Kind.EQUALS, "ITEM").matches("item", Format.TEXT),
        "text compares whatever the case, as the text filter does");
  }

  @Test
  void equalityWithoutAValueOfTheFormatPassesNothing() {
    for (String operand : List.of("", " ", "ten")) {
      Filter equals = Filter.of(0, Filter.Kind.EQUALS, operand);
      assertFalse(equals.matches(10, Format.INTEGER), "\"" + operand + "\"");
      assertFalse(equals.matches(new BigDecimal("0"), Format.DECIMAL), "\"" + operand + "\"");
    }
    assertTrue(Filter.between(0, "", "").matches(10, Format.INTEGER), "a range may be open");
    assertTrue(Filter.of(0, Filter.Kind.EQUALS, " 10 ").matches(10, Format.INTEGER));
  }

  record Named(int id, String name) {
    Named withName(String name) {
      return new Named(id, name);
    }
  }

  @Test
  void aPasteWritesEachRowItTookAlsoWhenTheAppSortsBetweenRows() {
    List<Named> rows = new ArrayList<>(List.of(new Named(1, "A"), new Named(2, "B")));
    GridTable<Named> table = DataGrid.table(rows::get);
    List<Column> columns =
        List.of(table.column("name", 100, Format.TEXT, Named::name, Named::withName));
    table
        .paste(columns, rows.size(), (index, row) -> {
          rows.set(index, row);
          rows.sort(Comparator.comparing(Named::name)); // the app keeps its rows sorted
          return null;
        })
        .accept(List.of(new CellValue(0, 0, "Z"), new CellValue(1, 0, "Y")));
    assertEquals(List.of(new Named(2, "Y"), new Named(1, "Z")), rows);
  }

  @Test
  void clearingAndPastingLeaveACellWhoseFormatRefusesTheValue() throws IOException {
    TextLibraries.assumeLoaded();
    List<Named> rows = new ArrayList<>(List.of(new Named(1, "Ada"), new Named(2, "Bob")));
    GridTable<Named> table = DataGrid.table(rows::get);
    Format<String> required = Format.TEXT.where(s -> !s.isBlank(), "A name is required");
    List<Column> columns =
        List.of(table.column("name", 100, required, Named::name, Named::withName));
    List<Named> told = new ArrayList<>();
    Selection selection = new Selection(Mode.CELLS);
    selection.select(0, 0);
    selection.extend(1, 0);
    com.rethinkj.ui.Clipboard clipboard = com.rethinkj.ui.Clipboard.inMemory();
    App<Selection> app = new App<>(
        selection,
        sel -> DataGrid.dataGrid(
                Theme.DARK,
                columns,
                rows.size(),
                DataGrid.options().select(sel).copy(clipboard).onCommit(table, (index, row) -> {
                  told.add(row);
                  rows.set(index, row);
                  return null;
                }))
            .key("grid")
            .wFull()
            .hFull());
    run(app, "frame\nfocus #grid\nkey Delete\nframe");
    assertEquals(List.of(), told, "empty text is no name");
    clipboard.write(" ");
    run(app, "key V control\nframe");
    assertEquals(List.of(), told, "nor is a space");
    assertEquals(List.of(new Named(1, "Ada"), new Named(2, "Bob")), rows);
    DataGrid.Commit commit = table.commit(columns, (index, row) -> {
      told.add(row);
      return null;
    });
    assertEquals("A name is required", commit.commit(0, 0, " "), "never a null in its place");
    assertEquals(List.of(), told);
  }

  @Test
  void theChangesOfAGridChangesComeAsTypedValues() {
    State s = new State();
    GridTable<Item> table = DataGrid.table(s::item);
    List<Column> columns = columns(table);
    GridChanges changes = new GridChanges(
        s.items.size(), (row, column) -> columns.get(column).cell().apply(row));
    changes.set(4, 2, "3.25");
    changes.set(4, 3, null);
    changes.insert(4);
    changes.set(5, 1, "new item"); // the new row comes after row 4
    changes.delete(List.of(new DataGrid.Range(10, 0, 10, 4)));
    List<GridChanges.RowChange<Item>> typed = changes.changes(table, columns);
    assertEquals(3, typed.size());
    GridChanges.RowChange<Item> delete = typed.get(0);
    assertEquals(GridChanges.Kind.DELETE, delete.kind());
    assertSame(s.items.get(9), delete.row());
    GridChanges.RowChange<Item> update = typed.get(1);
    assertSame(s.items.get(4), update.row());
    assertEquals(BigDecimal.valueOf(500, 2), update.values().get(2).before());
    assertEquals(new BigDecimal("3.25"), update.values().get(2).after());
    assertEquals(LocalDate.of(2026, 1, 5), update.values().get(3).before());
    assertNull(update.values().get(3).after());
    Item written = update.apply(update.row());
    assertEquals(new BigDecimal("3.25"), written.price());
    assertNull(written.due());
    GridChanges.RowChange<Item> insert = typed.get(2);
    assertEquals(GridChanges.Kind.INSERT, insert.kind());
    assertNull(insert.row());
    assertEquals("new item", insert.apply(new Item(0, null, null, null, null)).name());
  }

  // Through the grid, with native libraries

  private static App<State> app(State s) {
    TextLibraries.assumeLoaded();
    return new App<>(s, GridTableTest::view);
  }

  private static void run(App<?> app, String steps) throws IOException {
    Script.run("size 520 300\nframe\n" + steps + "\nframe", app, Path.of("target/grid-table"));
  }

  @Test
  void editingACellHandsTheAppTheRecordWithTheTypedValue() throws IOException {
    State s = new State();
    App<State> app = app(s);
    run(app, "focus #items\nkey ArrowRight\ntype \"1.5\"\ntype \"x\"\nframe"); // x is no number's
    assertTrue(line(app, "field").contains("\"1.5\""), app.tree().dump());
    run(app, "key Enter");
    assertEquals(new BigDecimal("1.5"), s.items.getFirst().price());
    assertTrue(s.log.getFirst().startsWith("0 Item[id=1"), s.log.toString());

    run(app, "key ArrowUp\ntype \"-2\"\nkey Enter"); // the format's own check
    assertTrue(app.tree().dump().contains("role=alert label=\"Not below zero\""));
    run(app, "key Escape\nkey ArrowLeft\nkey F2\nframe\nkey A control\nkey Delete\nkey Enter");
    assertTrue(app.tree().dump().contains("role=alert label=\"A name is required\""));
    assertEquals(1, s.log.size(), "neither was stored");
    run(app, "key Escape\nfocus #items\nkey End\nkey Enter\nframe\nadvance 1\nframe");
    run(app, "key ArrowDown\nkey Enter"); // the choices' menu: from book to game
    assertEquals(Kind.GAME, s.items.getFirst().kind(), s.log.toString());
  }

  @Test
  void rowsNotLoadedShowTheLoadingCells() throws IOException {
    State s = new State();
    s.loaded = 3;
    App<State> app = app(s);
    run(app, "");
    String dump = app.tree().dump();
    assertTrue(dump.contains("text \"item 2\""), dump);
    assertFalse(dump.contains("text \"item 4\""), dump);
    assertTrue(dump.contains("text \"…\""), "the loading cells: " + dump);
  }

  private static String line(App<?> app, String key) {
    return StructureTest.line(app.tree().dump(), "#" + key);
  }

  // The cost of a frame

  /** A row of a result, made once: the table asks for it per cell, as from a list. */
  record Result(
      long id, String customer, String email, LocalDate due, BigDecimal amount, Kind kind) {}

  private static final Result[] RESULTS = new Result[1000];

  static {
    for (int i = 0; i < RESULTS.length; i++) {
      RESULTS[i] = new Result(
          i + 1,
          "Customer " + i,
          "customer." + i + "@example.com",
          i % 4 == 0 ? null : LocalDate.of(2026, 1, 1).plusDays(i),
          BigDecimal.valueOf(i * 7919L % 250_000, 2),
          Kind.values()[i % 3]);
    }
  }

  private static Result result(int i) {
    return RESULTS[i % RESULTS.length];
  }

  /** {@code count} columns: typed ones of a table, or text ones that write the same texts. */
  private static List<Column> results(int count, boolean typed) {
    GridTable<Result> table = DataGrid.table(GridTableTest::result);
    List<Column> columns = new ArrayList<>();
    for (int c = 0; c < count; c++) {
      String title = "c" + c;
      columns.add(
          switch (c % 6) {
            case 0 ->
              typed
                  ? table.column(title, 64, Format.LONG, Result::id)
                  : new Column(
                      title,
                      64,
                      DataType.INTEGER,
                      i -> Format.LONG.text(result(i).id()));
            case 1 ->
              typed
                  ? table.column(title, 120, Format.TEXT, Result::customer)
                  : new Column(title, 120, DataType.TEXT, i -> result(i).customer());
            case 2 ->
              typed
                  ? table.column(title, 180, Format.TEXT, Result::email)
                  : new Column(title, 180, DataType.TEXT, i -> result(i).email());
            case 3 ->
              typed
                  ? table.column(title, 100, Format.DATE.optional(), Result::due)
                  : new Column(title, 100, DataType.DATE, i -> {
                    LocalDate due = result(i).due();
                    return due == null ? null : Format.DATE.text(due);
                  });
            case 4 ->
              typed
                  ? table.column(title, 90, Format.DECIMAL, Result::amount)
                  : new Column(
                      title,
                      90,
                      DataType.DECIMAL,
                      i -> Format.DECIMAL.text(result(i).amount()));
            default ->
              typed
                  ? table.column(title, 80, KIND, Result::kind)
                  : new Column(
                      title, 80, DataType.ENUM, i -> KIND.text(result(i).kind()));
          });
    }
    return columns;
  }

  /**
   * Scrolling a million rows of records in typed columns against text columns that write the same
   * texts, 20 and 300 of each: the median time of a frame after a wheel step and of one after a
   * key that builds the view anew. Printed for the record.
   */
  @Test
  void aFrameOfTypedColumnsCostsAboutAsMuchAsOneOfText() {
    TextLibraries.assumeLoaded();
    long[][] times = new long[4][];
    for (int round = 0; round < 2; round++) { // the first round warms up
      times[0] = scrolling(20, false);
      times[1] = scrolling(20, true);
      times[2] = scrolling(300, false);
      times[3] = scrolling(300, true);
    }
    System.out.printf(
        "GridTable, a million rows: 20 columns of text %.1f µs a frame of scrolling, %.1f µs"
            + " after a key; typed %.1f µs, %.1f µs; 300 columns of text %.1f µs, %.1f µs;"
            + " typed %.1f µs, %.1f µs%n",
        times[0][0] / 1000.0,
        times[0][1] / 1000.0,
        times[1][0] / 1000.0,
        times[1][1] / 1000.0,
        times[2][0] / 1000.0,
        times[2][1] / 1000.0,
        times[3][0] / 1000.0,
        times[3][1] / 1000.0);
  }

  private static long[] scrolling(int count, boolean typed) {
    Selection selection = new Selection(Mode.ROWS);
    selection.select(2, 0);
    App<Object> app = new App<>(
        new Object(),
        x -> col()
            .wFull()
            .hFull()
            .padding(8)
            .child(DataGrid.dataGrid(
                    Theme.DARK,
                    results(count, typed),
                    1_000_000,
                    DataGrid.options().select(selection))
                .key("results")
                .grow(1)));
    app.textRendering(com.rethinkj.ui.text.TextRendering.LINUX);
    app.resize(560, 240, 1);
    app.frame();
    app.focus(find(app.tree(), "results"));
    app.frame();
    int frames = 300;
    long[] wheel = new long[frames];
    long[] keys = new long[frames];
    for (int i = -frames; i < frames; i++) {
      app.wheel(280, 120, 0, Math.floorMod(i, 80) < 40 ? 100 : -100);
      long start = System.nanoTime();
      app.frame();
      if (i >= 0) {
        wheel[i] = System.nanoTime() - start;
      }
    }
    for (int i = -frames; i < frames; i++) {
      app.keyDown(new com.rethinkj.ui.Key(Math.floorMod(i, 40) < 20 ? "ArrowDown" : "ArrowUp"));
      long start = System.nanoTime();
      app.frame();
      if (i >= 0) {
        keys[i] = System.nanoTime() - start;
      }
    }
    java.util.Arrays.sort(wheel);
    java.util.Arrays.sort(keys);
    return new long[] {wheel[frames / 2], keys[frames / 2]};
  }

  private static El find(El el, String key) {
    if (key.equals(el.key())) {
      return el;
    }
    for (El child : el.children()) {
      El found = find(child, key);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** The handler of the first row takes it out of the rows: the others are still written. */
  @Test
  void aPasteGoesOnWhenAHandlerTakesARowOut() {
    List<Named> rows = new ArrayList<>(List.of(new Named(1, "A"), new Named(2, "B")));
    GridTable<Named> table = DataGrid.table(rows::get);
    List<Column> columns =
        List.of(table.column("name", 100, Format.TEXT, Named::name, Named::withName));
    var paste = table.paste(columns, rows.size(), (index, row) -> {
      rows.set(index, row);
      rows.removeIf(r -> r.name().equals("hidden")); // the app's filter
      return null;
    });
    paste.accept(List.of(new CellValue(0, 0, "hidden"), new CellValue(1, 0, "edited B")));
    assertEquals(List.of(new Named(2, "edited B")), rows);
  }

  @Test
  void aPasteThroughTheGridGoesOnWhenAHandlerTakesARowOut() throws IOException {
    TextLibraries.assumeLoaded();
    List<Named> rows =
        new ArrayList<>(List.of(new Named(1, "A"), new Named(2, "B"), new Named(3, "C")));
    Selection selection = new Selection(Mode.CELLS);
    App<?>[] holder = new App<?>[1];
    App<Object> app = new App<>(new Object(), x -> {
      GridTable<Named> table = DataGrid.table(rows::get);
      return col()
          .wFull()
          .hFull()
          .child(DataGrid.dataGrid(
                  Theme.DARK,
                  List.of(table.column("Name", 180, Format.TEXT, Named::name, Named::withName)),
                  rows.size(),
                  DataGrid.options()
                      .select(selection)
                      .copy(holder[0].clipboard())
                      .onCommit(table, (index, row) -> {
                        if (row.id() == 1) {
                          rows.remove(index); // no longer passes the app's filter
                        } else {
                          rows.set(index, row);
                        }
                        return null;
                      }))
              .key("grid")
              .grow(1));
    });
    holder[0] = app;
    Script.run(
        "size 260 180\nframe\npress #cell-0-0\nframe\nclipboard \"AA\\nBB\\nCC\"\n"
            + "key V control\nframe",
        app,
        Path.of("target/grid-table"));
    assertEquals(List.of(new Named(2, "BB"), new Named(3, "CC")), rows);
    assertTrue(app.tree().dump().contains("\"CC\""), app.tree().dump());
  }

  static final class Details {
    static final Part<Details, String> NAME = Part.ofSetter(Details::name, Details::setName);
    private String name;

    Details(String name) {
      this.name = name;
    }

    String name() {
      return name;
    }

    void setName(String name) {
      this.name = name;
    }
  }

  record Entity(Details details) {
    static final Part<Entity, Details> DETAILS = Part.of(Entity::details, Entity::withDetails);

    Entity withDetails(Details details) {
      return new Entity(details);
    }
  }

  @Test
  void aRefusedSetterInsideAWitherIsSetBack() {
    Entity row = new Entity(new Details("original"));
    GridTable<Entity> table = DataGrid.table(i -> row);
    Column column = table.column("name", 100, Format.TEXT, Entity.DETAILS.then(Details.NAME));
    var commit = table.commit(List.of(column), (index, value) -> "Refused");
    assertEquals("Refused", commit.commit(0, 0, "rejected"));
    assertEquals("original", row.details().name());
  }
}
