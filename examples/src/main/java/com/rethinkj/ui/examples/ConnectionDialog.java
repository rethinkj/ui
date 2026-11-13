package com.rethinkj.ui.examples;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.El.el;
import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;
import static com.rethinkj.ui.kit.Button.Variant.SECONDARY;
import static com.rethinkj.ui.kit.Button.button;
import static com.rethinkj.ui.kit.Checkbox.checkbox;
import static com.rethinkj.ui.kit.Form.field;
import static com.rethinkj.ui.kit.Form.form;
import static com.rethinkj.ui.kit.Label.Variant.MUTED;
import static com.rethinkj.ui.kit.Label.label;
import static com.rethinkj.ui.kit.TextField.passwordField;
import static com.rethinkj.ui.kit.TextField.revealable;
import static com.rethinkj.ui.kit.TextField.textField;
import static com.rethinkj.ui.kit.TitleBar.titleBar;
import static com.rethinkj.ui.kit.Toggle.toggle;
import static com.rethinkj.ui.kit.ValueField.valueField;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import com.rethinkj.ui.kit.Card;
import com.rethinkj.ui.kit.ComboBox;
import com.rethinkj.ui.kit.Dialog;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Symbol;
import com.rethinkj.ui.kit.Theme;
import com.rethinkj.ui.kit.ValueField;
import com.rethinkj.ui.render.GpuPresenter;
import com.rethinkj.ui.text.TextRendering;
import com.rethinkj.ui.window.Window;
import com.rethinkj.ui.window.WindowLoop;
import java.io.IOException;
import java.util.List;

/**
 * The dialog in which a database tool asks where to connect, built from the kit: a form of text
 * fields with their labels beside them and the required ones marked, a password field with a
 * button that shows the password, a checkbox, a switch between the dark and the light theme, and
 * two buttons. Connect stays disabled until host, port, database and user are filled in; the
 * port is an {@code Integer} in a {@link ValueField}, which takes only digits and says when the
 * number is no port. Enter in a field connects, as does Connect; the example only says where
 * it would go, and never what the password is.
 */
public final class ConnectionDialog {

  String host = "localhost";
  Integer port = 5432; // null while the field is empty
  final ValueField.Draft portDraft = new ValueField.Draft(); // the port's text while it is none
  String database = "";
  final ComboBox.State databases = new ComboBox.State(); // the server's, offered as one types
  String user = "";
  String password = "";
  boolean revealed; // whether the password field shows the password
  boolean ssl = true;
  boolean dark = true;
  App<ConnectionDialog> app; // whose window says whether it is maximized
  String status = "";

  /** The databases the server is known to have, which the database's field suggests. */
  static final List<String> DATABASES =
      List.of("postgres", "inventory", "invoices", "shop", "analytics", "template1");

  Theme theme() {
    return dark ? Theme.DARK : Theme.LIGHT;
  }

  /** A port: digits alone, typing refuses anything else; the empty field is no port yet. */
  static final Format<Integer> PORT =
      Format.INTEGER.where(p -> p >= 1 && p <= 65535, "1 to 65535").optional();

  boolean valid() {
    return !host.isBlank()
        && port != null
        && portDraft.error() == null // the field shows a port, not a text that is none
        && !database.isBlank()
        && !user.isBlank();
  }

  void connect() {
    status = "Connecting to " + database + " on " + host + ":" + port + " as " + user
        + (ssl ? " with SSL" : "");
  }

  void reset() {
    ConnectionDialog fresh = new ConnectionDialog();
    host = fresh.host;
    port = fresh.port;
    portDraft.clear();
    database = fresh.database;
    user = fresh.user;
    password = fresh.password;
    revealed = fresh.revealed;
    ssl = fresh.ssl;
    status = fresh.status;
  }

  public static void main(String[] args) throws IOException {
    App<ConnectionDialog> app = new App<>(new ConnectionDialog(), ConnectionDialog::view);
    app.state().app = app;
    // A script's images are stored, so they must not depend on the platform.
    app.textRendering(TextRendering.LINUX);
    if (Script.handle(args, app)) {
      return;
    }
    app.textRendering(TextRendering.platform());
    Window.Options options = Identity.options(
            ConnectionDialog.class, "New connection", Identity.GREEN, Symbol.PLUG_CONNECTED)
        .frameless()
        .minimumSize(400, 412);
    try (Window window = Window.open("New connection", 440, 432, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }

  static El view(ConnectionDialog s) {
    Theme t = s.theme();
    // A null handler disables the button.
    El connect = button(t, PRIMARY, "Connect", s.valid() ? s::connect : null);
    El form = Card.card(t)
        .child(
            Card.header(t, "Connect to PostgreSQL", "The server and the account to log in with."))
        .child(form(
                t,
                Form.Labels.BESIDE,
                field(
                        "Host",
                        textField(t, s.host, "Name or address", v -> s.host = v)
                            .key("host")
                            .w(260))
                    .required(),
                field(
                        "Port",
                        valueField(t, PORT, s.port, "5432", s.portDraft, v -> s.port = v)
                            .key("port")
                            .maxLength(5)
                            .w(72))
                    .required(),
                field(
                        "Database",
                        ComboBox.free(t, s.database, DATABASES, s.databases, v -> s.database = v)
                            .key("database")
                            .w(260))
                    .required(),
                field(
                        "User",
                        textField(t, s.user, "Role", v -> s.user = v)
                            .key("user")
                            .w(260))
                    .required(),
                field(
                    "Password",
                    revealable(
                        t,
                        passwordField(t, s.password, null, v -> s.password = v)
                            .key("password")
                            .w(260),
                        s.revealed,
                        () -> s.revealed = !s.revealed)))
            .margin(t.space().s(), 0, t.space().s(), 0))
        .child(checkbox(t, s.ssl, "Require SSL", () -> s.ssl = !s.ssl))
        .child(toggle(t, s.dark, "Dark theme", () -> s.dark = !s.dark))
        .child(el().grow(1))
        .child(label(t, MUTED, s.status))
        .child(Form.buttons(t, button(t, SECONDARY, "Reset", s::reset), connect));
    return col()
        .wFull()
        .hFull()
        .fill(t.surface().window())
        // Enter in a field connects; on a button or the checkbox it presses that instead.
        .onKey(Dialog.enterPresses(connect))
        .child(titleBar(t, "New connection", t.accent().color(), Identity.maximized(s.app)))
        .child(col()
            .grow(1)
            .padding(0, t.space().m(), t.space().m(), t.space().m())
            .child(form.grow(1)));
  }
}
