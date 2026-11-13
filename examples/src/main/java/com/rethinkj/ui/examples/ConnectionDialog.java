package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.El.row;
import static com.rethinkj.ui.examples.TitleBar.titleBar;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.Label.Variant.HEADING;
import static com.rethinkj.ui.kit.Label.Variant.MUTED;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TextField.textField;
import static com.rethinkj.ui.kit.Toggle.toggle;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;

/**
 * The dialog in which a database tool asks where to connect, built from the kit: labelled text
 * fields, a checkbox, a switch between the dark and the light theme, and two buttons. Connect
 * stays disabled until host, port, database and user are filled in, and the port takes only
 * digits. Enter in a field connects, as does Connect; the example only says where it would go.
 */
public final class ConnectionDialog {

  String host = "localhost";
  String port = "5432";
  String database = "";
  String user = "";
  boolean ssl = true;
  boolean dark = true;
  String status = "";

  Theme theme() {
    return dark ? Theme.DARK : Theme.LIGHT;
  }

  void port(String value) {
    if (value.chars().allMatch(c -> c >= '0' && c <= '9')) {
      port = value; // anything else is refused, and the field stays as it was
    }
  }

  boolean valid() {
    int number = port.isEmpty() ? 0 : Integer.parseInt(port); // at most five digits
    return !host.isBlank() && number >= 1 && number <= 65535
        && !database.isBlank() && !user.isBlank();
  }

  void connect() {
    status = "Connecting to " + database + " on " + host + ":" + port + " as " + user
        + (ssl ? " with SSL" : "");
  }

  void reset() {
    ConnectionDialog fresh = new ConnectionDialog();
    host = fresh.host;
    port = fresh.port;
    database = fresh.database;
    user = fresh.user;
    ssl = fresh.ssl;
    status = fresh.status;
  }

  public static void main(String[] args) throws IOException {
    App<ConnectionDialog> app = new App<>(new ConnectionDialog(), ConnectionDialog::view);
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Window.options().frameless().minimumSize(400, 380);
    try (Window window = Window.open("New connection", 440, 400, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }

  static El view(ConnectionDialog s) {
    Theme t = s.theme();
    El form = col().grow(1).padding(t.space().xxl()).gap(t.space().l())
        .child(label(t, HEADING, "Connect to PostgreSQL"))
        .child(label(t, MUTED, "The server and the account to log in with."))
        .child(col().gap(t.space().m()).margin(t.space().s(), 0, t.space().s(), 0)
            .child(field(t, "Host", textField(t, s.host, "Name or address", v -> s.host = v)
                .key("host").w(260)))
            .child(field(t, "Port", textField(t, s.port, "5432", s::port)
                .key("port").maxLength(5).w(72)))
            .child(field(t, "Database", textField(t, s.database, "Name", v -> s.database = v)
                .key("database").w(260)))
            .child(field(t, "User", textField(t, s.user, "Role", v -> s.user = v)
                .key("user").w(260))))
        .child(checkbox(t, s.ssl, "Require SSL", () -> s.ssl = !s.ssl))
        .child(toggle(t, s.dark, "Dark theme", () -> s.dark = !s.dark))
        .child(el().grow(1))
        .child(label(t, MUTED, s.status))
        .child(row().gap(t.space().m()).justify(Style.Content.END)
            .child(button(t, SECONDARY, "Reset", s::reset))
            // A null handler disables the button.
            .child(button(t, PRIMARY, "Connect", s.valid() ? s::connect : null)));
    return col().wFull().hFull().fill(t.surface().window())
        // Enter in a field connects; on a button or the checkbox it presses that instead.
        .onKey(key -> {
          if (key.name().equals("Enter") && s.valid()) {
            s.connect();
            return true;
          }
          return false;
        })
        .child(titleBar(t, "New connection", t.accent().color()))
        .child(form);
  }

  /** A field with its name before it, which is also the field's label. */
  static El field(Theme t, String name, El field) {
    return row().gap(t.space().m()).align(Style.Align.CENTER)
        .child(label(t, name).w(72))
        .child(field.label(name));
  }
}
