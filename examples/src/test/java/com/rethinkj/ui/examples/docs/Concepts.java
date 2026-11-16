package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.El.text;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;

import com.rethinkj.ui.App;
import com.rethinkj.ui.Colors;
import com.rethinkj.ui.Cursor;
import com.rethinkj.ui.Drag;
import com.rethinkj.ui.El;
import com.rethinkj.ui.FieldState;
import com.rethinkj.ui.FileDialog;
import com.rethinkj.ui.Image;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Lane;
import com.rethinkj.ui.Side;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.Work;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Progress;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.motion.Spring;
import com.rethinkj.ui.text.Font;
import com.rethinkj.ui.window.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/** The examples of the concept pages, built from core's elements where they can be. */
final class Concepts {

  Theme t = Theme.DARK;
  App<?> app; // set by whoever makes the app
  int presses;
  boolean open;
  boolean menu;
  float knob;
  String name = "orders";
  final FieldState query = new FieldState();
  String sql = "SELECT *\nFROM orders\nLIMIT 20;";
  Work count;
  Work download;
  int received; // megabytes of the download so far
  Lane connection; // the lane of the made-up connection, made with the app
  Connection db;
  Work report;
  String reportStatus = "";
  String counted = "";
  String rows = "";
  String message = "";
  Image photo;
  String opened = "";

  static El layout(Concepts s) {
    Theme t = s.t;
    // docs:start layout
    El sidebar = col()
        .w(160)
        .fill(t.surface().panel())
        .padding(t.space().m())
        .gap(t.space().s())
        .child(text("Tables").color(t.text().muted()))
        .child(text("orders").color(t.text().primary()));
    El editor = col()
        .grow(1)
        .fill(t.surface().raised())
        .padding(t.space().m())
        .child(text("SELECT * FROM orders").font(Font.mono()).color(t.text().primary()));
    El status = row()
        .h(28)
        .padding(0, t.space().m())
        .align(Style.Align.CENTER)
        .justify(Style.Content.SPACE_BETWEEN)
        .child(text("localhost").color(t.text().muted()))
        .child(text("Ln 1, Col 1").color(t.text().muted()));
    El root = col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        .child(row().grow(1).gap(1).child(sidebar).child(editor))
        .child(status);
    // docs:end layout
    return root;
  }

  static El texts(Concepts s) {
    Theme t = s.t;
    // docs:start text
    El title =
        text("Orders").font(Font.sansSemibold()).fontSize(20).color(t.text().primary());
    El body = text("Each order belongs to a customer and has one or more items.")
        .wrapText()
        .w(240)
        .color(t.text().muted());
    El cut = text("a very long value that does not fit in its column")
        .ellipsis()
        .w(160)
        .minSize(0, 0)
        .color(t.text().primary());
    El code = text("x -> x != null")
        .font(Font.mono())
        .fontFeatures("+calt")
        .color(t.text().primary());
    // docs:end text
    return Frame.of(t, title, body, cut, code);
  }

  static El input(Concepts s) {
    Theme t = s.t;
    // docs:start input
    El counter = el().key("counter")
        .role("button")
        .label("Count")
        .size(120, 32)
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .center()
        .focusable()
        .cursor(Cursor.POINTER)
        .onPress(s::press) // also Enter and Space when focused
        .onKey(s::resetOnDelete)
        .whenHovered(e -> e.fill(t.surface().hover()))
        .whenPressed(e -> e.fill(t.surface().pressed()))
        .focusRing(t.accent().edge())
        .child(text(String.valueOf(s.presses)).color(t.text().primary()));
    // docs:end input
    // docs:start drag
    El knob = el().key("knob")
        .size(24, 24)
        .radius(12)
        .fill(t.accent().color())
        .absolute(s.knob, 0)
        .cursor(Cursor.EW_RESIZE)
        .onDrag(s::dragKnob);
    El track = el().w(200).h(24).child(knob);
    // docs:end drag
    return Frame.of(t, counter, track);
  }

  // docs:start input-handlers
  void press() {
    presses++;
  }

  // true: the key was taken
  boolean resetOnDelete(Key key) {
    if (key.name().equals("Delete")) {
      presses = 0;
      return true;
    }
    return false;
  }
  // docs:end input-handlers

  // docs:start drag-handlers
  void dragKnob(Drag d) {
    if (d.phase() == Drag.Phase.MOVE) {
      knob = Math.clamp(knob + d.dx(), 0, 176);
    }
  }
  // docs:end drag-handlers

  static El states(Concepts s) {
    Theme t = s.t;
    // docs:start states
    El row = row()
        .key("row-3")
        .role("treeitem")
        .label("orders")
        .selected(true)
        .expanded(false)
        .level(2)
        .position(1, 3)
        .child(text("orders").color(t.text().primary()));
    // docs:end states
    return Frame.of(t, row);
  }

  static El motion(Concepts s) {
    Theme t = s.t;
    // docs:start motion
    El panel = el().key("panel")
        .w(s.open ? 240 : 120)
        .h(40)
        .radius(t.radius().medium())
        .fill(s.open ? t.accent().color() : t.surface().raised())
        .motion(Spring.SETTLE); // size and colour spring to their new values
    El toggle = Button.button(t, SECONDARY, s.open ? "Close" : "Open", s::toggleOpen);
    // docs:end motion
    // docs:start cycle
    El pulse = el().key("pulse")
        .size(16, 16)
        .radius(8)
        .fill(t.accent().edge())
        .cycle(1.2, (e, phase) -> e.opacity((float) (0.4 + 0.6 * Math.sin(phase * Math.PI))));
    // docs:end cycle
    return Frame.of(t, panel, toggle, pulse);
  }

  // docs:start motion-handlers
  void toggleOpen() {
    open = !open;
  }
  // docs:end motion-handlers

  static El overlay(Concepts s) {
    Theme t = s.t;
    // docs:start overlay
    El menu = col()
        .key("menu")
        .role("menu")
        .padding(t.space().xs())
        .radius(t.radius().medium())
        .fill(t.surface().raised())
        .border(1, t.border().control())
        .shadow(t.elevation().popup())
        .side(Side.BOTTOM, Style.Align.START, 4) // below the button
        .onDismiss(s::closeMenu) // a press outside
        .onKey(s::closeOnEscape)
        .child(Button.button(t, SECONDARY, "Open", s::closeMenu).key("open"));
    El button = Button.button(t, SECONDARY, "Actions", s::toggleMenu)
        .key("actions")
        .overlay(s.menu ? menu : null); // open is the app's state
    // docs:end overlay
    return Frame.of(t, button);
  }

  // docs:start overlay-handlers
  void toggleMenu() {
    menu = !menu;
  }

  void closeMenu() {
    menu = false;
  }

  boolean closeOnEscape(Key key) {
    if (key.name().equals("Escape")) {
      menu = false;
      return true;
    }
    return false;
  }
  // docs:end overlay-handlers

  static El rows(Concepts s) {
    Theme t = s.t;
    // docs:start rows
    El list = col()
        .key("rows")
        .role("list")
        .label("Rows")
        .wFull()
        .grow(1)
        .scrollbars(t.scrollbars())
        .rows(
            1_000_000,
            24,
            i -> row()
                .key("row-" + i)
                .role("listitem")
                .h(24)
                .padding(0, t.space().m())
                .align(Style.Align.CENTER)
                .child(text("Row " + i).color(t.text().primary())));
    // docs:end rows
    return Frame.of(t, list);
  }

  static El fields(Concepts s) {
    Theme t = s.t;
    // docs:start field
    El name = text(s.name)
        .editable(s::name) // every edit hands on the string
        .key("name")
        .role("textbox")
        .label("Table name")
        .w(200)
        .h(28)
        .padding(0, 8)
        .fill(t.surface().raised())
        .color(t.text().primary())
        .maxLength(63);
    El query = text(s.sql)
        .editable(s::sql)
        .multiline()
        .lines(4)
        .fieldState(s.query) // caret and history in the state
        .key("query")
        .role("textbox")
        .label("Query")
        .w(260)
        .padding(8)
        .font(Font.mono())
        .fill(t.surface().raised())
        .color(t.text().primary());
    // docs:end field
    return Frame.of(t, name, query);
  }

  // docs:start field-handlers
  void name(String value) {
    name = value;
  }

  void sql(String value) {
    sql = value;
  }
  // docs:end field-handlers

  static El background(Concepts s) {
    Theme t = s.t;
    // docs:start background
    El count = Button.button(t, PRIMARY, null, "Count rows", s.count != null, s::countRows);
    El cancel = Button.button(t, SECONDARY, "Cancel", s.count == null ? null : s::cancelCount);
    // docs:end background
    // docs:start after
    El save = Button.button(t, SECONDARY, "Save", s::save);
    // docs:end after
    return Frame.of(
        t,
        row().gap(t.space().s()).children(count, cancel, save),
        Label.label(t, s.rows).key("rows"),
        Label.label(t, s.message).key("message"));
  }

  // docs:start background-handlers
  void countRows() {
    String table = name; // only what the work needs
    count = app.background(
        "count " + table,
        () -> slowCount(table), // on a virtual thread
        this::rowsCounted,
        this::countFailed);
  }

  // back on the app's thread
  void rowsCounted(Long n) {
    rows = n + " rows";
    count = null;
  }

  void countFailed(Exception e) {
    rows = e.getMessage();
    count = null;
  }

  void cancelCount() {
    count.cancel(); // neither handler runs
    count = null;
  }
  // docs:end background-handlers

  // docs:start after-handlers
  void save() {
    message = "Saved";
    app.after(3, this::clearMessage); // on the app's clock
  }

  void clearMessage() {
    message = "";
  }
  // docs:end after-handlers

  static long slowCount(String table) throws InterruptedException {
    Thread.sleep(Duration.ofMillis(50));
    return table.length() * 1000L;
  }

  static El lanes(Concepts s) {
    Theme t = s.t;
    // docs:start lane
    El report = Button.button(t, PRIMARY, null, "Run report", s.report != null, s::runReport);
    El cancel = Button.button(t, SECONDARY, "Cancel", s.report == null ? null : s::cancelReport);
    El count = Button.button(t, SECONDARY, "Count orders", s::countOrders);
    El waiting = Label.label(t, s.connection.pending().size() + " on the connection");
    // docs:end lane
    return Frame.of(
        t,
        row().gap(t.space().s()).children(report, cancel, count),
        Label.label(t, s.reportStatus).key("report"),
        Label.label(t, s.counted).key("count"),
        waiting.key("waiting"));
  }

  // docs:start lane-handlers
  void runReport() {
    Connection db = this.db;
    reportStatus = "Running…";
    report = connection.background(
        "report", () -> db.query("SELECT pg_sleep(60)"), this::reportDone, this::reportFailed);
  }

  void reportDone(String result) {
    reportStatus = result;
    report = null;
  }

  void reportFailed(Exception e) {
    report = null;
  }

  void cancelReport() {
    Work cancelled = report;
    cancelled.cancel(); // interrupts it; the driver still finishes the statement
    cancelled.onEnd(this::reportCancelled); // once it has returned
    report = null;
    reportStatus = "Cancelling…";
  }

  void reportCancelled() {
    reportStatus = "Cancelled";
  }

  void countOrders() {
    Connection db = this.db;
    connection.background( // begins once the work before it on the lane has ended
        "count",
        () -> db.query("SELECT count(*) FROM orders"),
        this::ordersCounted,
        this::ordersFailed);
  }

  void ordersCounted(String n) {
    counted = n;
  }

  void ordersFailed(Exception e) {
    counted = e.getMessage();
  }
  // docs:end lane-handlers

  /**
   * A made-up connection, which one thread at a time may use. Its server finishes the report two
   * seconds of the app's clock after the connection was made, by an action of {@link App#after},
   * and does so also when the work is cancelled, as a driver that waits for the server's answer.
   */
  static final class Connection {
    private final Semaphore reportDone = new Semaphore(0);

    static Connection madeUp(App<?> app) {
      Connection connection = new Connection();
      app.after(2, connection.reportDone::release);
      return connection;
    }

    /** Runs a statement, on the work's thread. */
    String query(String sql) {
      if (sql.startsWith("SELECT pg_sleep")) {
        reportDone.acquireUninterruptibly(); // the interrupt does not stop it
        return "Report done";
      }
      return "42 orders";
    }
  }

  static El progress(Concepts s) {
    Theme t = s.t;
    // docs:start reporting
    El download = Button.button(t, PRIMARY, null, "Download", s.download != null, s::download);
    El bar = Progress.labeled(t, "Driver", s.received / 8.0, s.received + " of 8 MB");
    // docs:end reporting
    return Frame.of(t, download, bar.w(240));
  }

  // docs:start reporting-handlers
  void download() {
    received = 0;
    Driver driver = Driver.madeUp(app, 8); // only what the work needs
    download = app.background(
        "download of " + driver.name,
        progress -> { // on a virtual thread
          int megabytes = 0;
          while (driver.read()) { // blocks for the next megabyte; cancel() interrupts it
            progress.report(++megabytes); // reports that wait are coalesced into the newest
          }
          return megabytes;
        },
        this::received,
        this::downloaded,
        this::downloadFailed);
  }

  // back on the app's thread, each report
  void received(Integer megabytes) {
    received = megabytes;
  }

  void downloaded(Integer megabytes) {
    received = megabytes;
    download = null;
  }

  void downloadFailed(Exception e) {
    download = null;
  }
  // docs:end reporting-handlers

  /**
   * A made-up download: its megabytes arrive one each quarter second of the app's clock, by
   * actions of {@link App#after}, so that a script decides with {@code advance} how far it got.
   */
  static final class Driver {
    final String name = "postgresql.jar";
    private final Semaphore arrived = new Semaphore(0);
    private int left;

    static Driver madeUp(App<?> app, int megabytes) {
      Driver driver = new Driver();
      driver.left = megabytes;
      for (int i = 1; i <= megabytes; i++) {
        app.after(0.25 * i, driver.arrived::release);
      }
      return driver;
    }

    /** Waits for the next megabyte, on the work's thread; false at the end. */
    boolean read() throws InterruptedException {
      if (left == 0) {
        return false;
      }
      arrived.acquire();
      left--;
      return true;
    }
  }

  static El images(Concepts s) {
    Theme t = s.t;
    // docs:start images
    El open = Button.button(t, SECONDARY, "Open image…", s::openImage);
    El photo = s.photo == null
        ? Label.label(t, Label.Variant.MUTED, "No image")
        : El.image(s.photo).label("The chosen image").radius(t.radius().medium());
    // docs:end images
    return Frame.of(t, open, photo);
  }

  // docs:start images-handlers
  void openImage() {
    app.fileDialog(
        FileDialog.open("Open an image").filter("Images", "png", "jpg", "jpeg"),
        this::imageChosen,
        this::dialogFailed);
  }

  void imageChosen(FileDialog.Choice choice) {
    if (choice.paths().isEmpty()) {
      return; // cancelled
    }
    Path path = choice.path();
    app.background(
        "read " + path,
        () -> Image.decode(Files.readAllBytes(path)), // decoding takes a while
        this::photo,
        this::readFailed);
  }

  void photo(Image image) {
    photo = image;
  }

  void readFailed(Exception e) {
    message = e.getMessage();
  }

  void dialogFailed(String error) {
    message = error;
  }
  // docs:end images-handlers

  static El theme(Concepts s) {
    // docs:start theme
    Theme t = Theme.LIGHT.withAccent(Theme.Hue.TEAL); // a named accent, made for the light theme
    El save = Button.button(t, PRIMARY, "Save", s::save);
    // docs:end theme
    return Frame.of(t, save);
  }

  static Theme ownAccent() {
    // docs:start own-accent
    int teal = Colors.rgb(0x0F766E); // 0xFF0F766E, opaque
    Theme t = Theme.LIGHT.withAccent(new Theme.Accent(
        teal, Colors.mix(teal, Colors.BLACK, 0.12), Colors.mix(teal, Colors.BLACK, 0.24)));
    // docs:end own-accent
    return t;
  }

  static int colours(Theme t) {
    // docs:start colours
    int lamp = Colors.hex("#FACC15"); // from a setting, 0xFFFACC15
    int shade = Colors.alpha(Colors.BLACK, 0.4f); // black at 40 %, 0x66000000
    int hovered = Colors.over(t.surface().panel(), t.surface().hover()); // as drawn, opaque
    double ratio = Colors.contrast(t.text().primary(), hovered); // WCAG's, 1 to 21
    boolean readable = ratio >= 4.5;
    // docs:end colours
    return readable ? lamp : shade;
  }

  // docs:start window-options
  static Window.Options options() {
    return Window.options().frameless().minimumSize(480, 320);
  }
  // docs:end window-options
}
