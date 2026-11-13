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

/** The examples of the concept pages, built from core's elements where they can be. */
final class Concepts {

  final Theme t = Theme.DARK;
  App<?> app; // set by whoever makes the app
  int presses;
  boolean open;
  boolean menu;
  float knob;
  String name = "orders";
  final FieldState query = new FieldState();
  String sql = "SELECT *\nFROM orders\nLIMIT 20;";
  Work count;
  String rows = "";
  String message = "";
  Image photo;
  String opened = "";

  static El layout(Concepts s) {
    Theme t = s.t;
    // docs:start layout
    El sidebar = col().w(160).fill(t.surface().panel()).padding(t.space().m()).gap(t.space().s())
        .child(text("Tables").color(t.text().muted()))
        .child(text("orders").color(t.text().primary()));
    El editor = col().grow(1).fill(t.surface().raised()).padding(t.space().m())
        .child(text("SELECT * FROM orders").font(Font.mono()).color(t.text().primary()));
    El status = row().h(28).padding(0, t.space().m()).align(Style.Align.CENTER)
        .justify(Style.Content.SPACE_BETWEEN)
        .child(text("localhost").color(t.text().muted()))
        .child(text("Ln 1, Col 1").color(t.text().muted()));
    El root = col().wFull().hFull().fill(t.surface().window())
        .child(row().grow(1).gap(1).child(sidebar).child(editor))
        .child(status);
    // docs:end layout
    return root;
  }

  static El texts(Concepts s) {
    Theme t = s.t;
    // docs:start text
    El title = text("Orders").font(Font.sansSemibold()).fontSize(20).color(t.text().primary());
    El body = text("Each order belongs to a customer and has one or more items.")
        .wrapText().w(240).color(t.text().muted());
    El cut = text("a very long value that does not fit in its column").ellipsis()
        .w(160).minSize(0, 0).color(t.text().primary());
    El code = text("x -> x != null").font(Font.mono()).fontFeatures("+calt")
        .color(t.text().primary());
    // docs:end text
    return Frame.of(t, title, body, cut, code);
  }

  static El input(Concepts s) {
    Theme t = s.t;
    // docs:start input
    El counter = el().key("counter").role("button").label("Count").size(120, 32)
        .radius(t.radius().medium()).fill(t.surface().raised()).center()
        .focusable().cursor(Cursor.POINTER)
        .onPress(() -> s.presses++)                            // also Enter and Space when focused
        .onKey(key -> {                                        // true: the key was taken
          if (key.name().equals("Delete")) {
            s.presses = 0;
            return true;
          }
          return false;
        })
        .whenHovered(e -> e.fill(t.surface().hover()))
        .whenPressed(e -> e.fill(t.surface().pressed()))
        .focusRing(t.accent().edge())
        .child(text(String.valueOf(s.presses)).color(t.text().primary()));
    // docs:end input
    // docs:start drag
    El knob = el().key("knob").size(24, 24).radius(12).fill(t.accent().color())
        .absolute(s.knob, 0).cursor(Cursor.EW_RESIZE)
        .onDrag(d -> {
          if (d.phase() == Drag.Phase.MOVE) {
            s.knob = Math.clamp(s.knob + d.dx(), 0, 176);
          }
        });
    El track = el().w(200).h(24).child(knob);
    // docs:end drag
    return Frame.of(t, counter, track);
  }

  static El states(Concepts s) {
    Theme t = s.t;
    // docs:start states
    El row = row().key("row-3").role("treeitem").label("orders")
        .selected(true).expanded(false).level(2).position(1, 3)
        .child(text("orders").color(t.text().primary()));
    // docs:end states
    return Frame.of(t, row);
  }

  static El motion(Concepts s) {
    Theme t = s.t;
    // docs:start motion
    El panel = el().key("panel").w(s.open ? 240 : 120).h(40).radius(t.radius().medium())
        .fill(s.open ? t.accent().color() : t.surface().raised())
        .motion(Spring.SETTLE);                     // size and colour spring to their new values
    El toggle = Button.button(t, SECONDARY, s.open ? "Close" : "Open", () -> s.open = !s.open);
    // docs:end motion
    // docs:start cycle
    El pulse = el().key("pulse").size(16, 16).radius(8).fill(t.accent().edge())
        .cycle(1.2, (e, phase) -> e.opacity((float) (0.4 + 0.6 * Math.sin(phase * Math.PI))));
    // docs:end cycle
    return Frame.of(t, panel, toggle, pulse);
  }

  static El overlay(Concepts s) {
    Theme t = s.t;
    // docs:start overlay
    El menu = col().key("menu").role("menu").padding(t.space().xs()).radius(t.radius().medium())
        .fill(t.surface().raised()).border(1, t.border().control())
        .shadow(t.elevation().popup())
        .side(Side.BOTTOM, Style.Align.START, 4)                 // below the button
        .onDismiss(() -> s.menu = false)                         // a press outside
        .onKey(key -> {
          if (key.name().equals("Escape")) {
            s.menu = false;
            return true;
          }
          return false;
        })
        .child(Button.button(t, SECONDARY, "Open", () -> s.menu = false).key("open"));
    El button = Button.button(t, SECONDARY, "Actions", () -> s.menu = !s.menu)
        .key("actions")
        .overlay(s.menu ? menu : null);                          // open is the app's state
    // docs:end overlay
    return Frame.of(t, button);
  }

  static El rows(Concepts s) {
    Theme t = s.t;
    // docs:start rows
    El list = col().key("rows").role("list").label("Rows").wFull().grow(1)
        .scrollbars(t.scrollbars())
        .rows(1_000_000, 24, i -> row().key("row-" + i).role("listitem").h(24)
            .padding(0, t.space().m()).align(Style.Align.CENTER)
            .child(text("Row " + i).color(t.text().primary())));
    // docs:end rows
    return Frame.of(t, list);
  }

  static El fields(Concepts s) {
    Theme t = s.t;
    // docs:start field
    El name = text(s.name).editable(v -> s.name = v)          // every edit hands on the string
        .key("name").role("textbox").label("Table name").w(200).h(28).padding(0, 8)
        .fill(t.surface().raised()).color(t.text().primary()).maxLength(63);
    El query = text(s.sql).editable(v -> s.sql = v).multiline().lines(4)
        .fieldState(s.query)                                  // caret and history in the state
        .key("query").role("textbox").label("Query").w(260).padding(8)
        .font(Font.mono()).fill(t.surface().raised()).color(t.text().primary());
    // docs:end field
    return Frame.of(t, name, query);
  }

  static El background(Concepts s) {
    Theme t = s.t;
    // docs:start background
    El count = Button.button(t, PRIMARY, null, "Count rows", s.count != null, () -> {
      String table = s.name;                                  // only what the work needs
      s.count = s.app.background("count " + table,
          () -> slowCount(table),                             // on a virtual thread
          n -> {                                              // back on the app's thread
            s.rows = n + " rows";
            s.count = null;
          },
          e -> {
            s.rows = e.getMessage();
            s.count = null;
          });
    });
    El cancel = Button.button(t, SECONDARY, "Cancel", s.count == null ? null : () -> {
      s.count.cancel();                                       // neither handler runs
      s.count = null;
    });
    // docs:end background
    // docs:start after
    El save = Button.button(t, SECONDARY, "Save", () -> {
      s.message = "Saved";
      s.app.after(3, () -> s.message = "");                  // on the app's clock
    });
    // docs:end after
    return Frame.of(t, row().gap(t.space().s()).children(count, cancel, save),
        Label.label(t, s.rows).key("rows"), Label.label(t, s.message).key("message"));
  }

  static long slowCount(String table) throws InterruptedException {
    Thread.sleep(Duration.ofMillis(50));
    return table.length() * 1000L;
  }

  static El images(Concepts s) {
    Theme t = s.t;
    // docs:start images
    El open = Button.button(t, SECONDARY, "Open image…", () -> s.app.fileDialog(
        FileDialog.open("Open an image").filter("Images", "png", "jpg", "jpeg"),
        choice -> {
          if (choice.paths().isEmpty()) {
            return;                                           // cancelled
          }
          Path path = choice.path();
          s.app.background("read " + path,
              () -> Image.decode(Files.readAllBytes(path)),   // decoding takes a while
              image -> s.photo = image,
              e -> s.message = e.getMessage());
        },
        error -> s.message = error));
    El photo = s.photo == null ? Label.label(t, Label.Variant.MUTED, "No image")
        : El.image(s.photo).label("The chosen image").radius(t.radius().medium());
    // docs:end images
    return Frame.of(t, open, photo);
  }

  static El theme(Concepts s) {
    // docs:start theme
    Theme t = Theme.LIGHT.withAccent(Theme.Hue.TEAL);   // a named accent, made for the light theme
    El save = Button.button(t, PRIMARY, "Save", () -> s.presses++);
    // docs:end theme
    return Frame.of(t, save);
  }

  static Theme ownAccent() {
    // docs:start own-accent
    int teal = Colors.rgb(0x0F766E);                     // 0xFF0F766E, opaque
    Theme t = Theme.LIGHT.withAccent(new Theme.Accent(
        teal, Colors.mix(teal, Colors.BLACK, 0.12), Colors.mix(teal, Colors.BLACK, 0.24)));
    // docs:end own-accent
    return t;
  }

  static int colours(Theme t) {
    // docs:start colours
    int lamp = Colors.hex("#FACC15");                    // from a setting, 0xFFFACC15
    int shade = Colors.alpha(Colors.BLACK, 0.4f);        // black at 40 %, 0x66000000
    int hovered = Colors.over(t.surface().panel(), t.surface().hover()); // as drawn, opaque
    double ratio = Colors.contrast(t.text().primary(), hovered);        // WCAG's, 1 to 21
    boolean readable = ratio >= 4.5;
    // docs:end colours
    return readable ? lamp : shade;
  }

  static Theme themeOf(boolean dark) {
    // docs:start theme-switch
    Theme t = dark ? Theme.DARK : Theme.LIGHT;
    // docs:end theme-switch
    return t;
  }

  // docs:start window-options
  static Window.Options options() {
    return Window.options().frameless().minimumSize(480, 320);
  }
  // docs:end window-options
}
