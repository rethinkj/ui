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
import com.rethinkj.ui.kit.Editing;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Format;
import com.rethinkj.ui.kit.Part;
import com.rethinkj.ui.kit.Prop;
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
 * two buttons. The form edits a {@link Profile} record through an {@link Editing}, which keeps the
 * port's text while it is no port and the errors of the fields. Connect stays disabled until host,
 * port, database and user are filled in, the edit is valid and differs from the profile last
 * connected with; the port is an {@code Integer} in a {@link ValueField}, which takes only digits
 * and says when the number is no port. A made-up rule lets {@code postgres} connect only to
 * localhost, and the made-up server refuses every port but 5432, which the port's field shows
 * until it is edited. Enter in a field connects, as does Connect; the example only says where it
 * would go, and never what the password is.
 */
public final class ConnectionDialog {

  /** Where to connect and as whom: what the form edits. */
  record Profile(
      String host, Integer port, String database, String user, String password, boolean ssl) {
    static final Part<Profile, String> HOST = Part.of(Profile::host, Profile::withHost);
    static final Part<Profile, Integer> PORT = Part.of(Profile::port, Profile::withPort);
    static final Part<Profile, String> DATABASE = Part.of(Profile::database, Profile::withDatabase);
    static final Part<Profile, String> USER = Part.of(Profile::user, Profile::withUser);
    static final Part<Profile, String> PASSWORD = Part.of(Profile::password, Profile::withPassword);
    static final Part<Profile, Boolean> SSL = Part.of(Profile::ssl, Profile::withSsl);

    static final Profile NEW = new Profile("localhost", 5432, "", "", "", true);

    Profile withHost(String host) {
      return new Profile(host, port, database, user, password, ssl);
    }

    Profile withPort(Integer port) {
      return new Profile(host, port, database, user, password, ssl);
    }

    Profile withDatabase(String database) {
      return new Profile(host, port, database, user, password, ssl);
    }

    Profile withUser(String user) {
      return new Profile(host, port, database, user, password, ssl);
    }

    Profile withPassword(String password) {
      return new Profile(host, port, database, user, password, ssl);
    }

    Profile withSsl(Boolean ssl) {
      return new Profile(host, port, database, user, password, ssl);
    }

    /** Whether the fields a connection needs are filled in. */
    boolean complete() {
      return !host.isBlank() && port != null && !database.isBlank() && !user.isBlank();
    }

    @Override
    public String toString() { // never the password
      return user + "@" + host + ":" + port + "/" + database;
    }
  }

  final Editing<Profile> editing = editing();
  final ComboBox.State databases = new ComboBox.State(); // the server's, offered as one types
  boolean revealed; // whether the password field shows the password
  boolean dark = true;
  App<ConnectionDialog> app; // whose window says whether it is maximized
  String status = "";

  /** The databases the server is known to have, which the database's field suggests. */
  static final List<String> DATABASES =
      List.of("postgres", "inventory", "invoices", "shop", "analytics", "template1");

  /** The edit of a new profile, with a rule across two of its fields. */
  static Editing<Profile> editing() {
    return Editing.of(Profile.NEW)
        .rule(
            Profile.USER,
            p -> !p.user().equals("postgres") || p.host().equals("localhost"),
            "postgres connects only to localhost");
  }

  Theme theme() {
    return dark ? Theme.DARK : Theme.LIGHT;
  }

  /** A port: digits alone, typing refuses anything else; the empty field is no port yet. */
  static final Format<Integer> PORT =
      Format.INTEGER.where(p -> p >= 1 && p <= 65535, "1 to 65535").optional();

  /** Whether Connect can go: a complete, valid profile other than the one last connected with. */
  boolean ready() {
    return editing.valid() && editing.changed() && editing.value().complete();
  }

  void connect() {
    Profile p = editing.value();
    if (p.port() != 5432) { // the made-up server listens on 5432 alone
      editing.at(Profile.PORT).error("No server answers on port " + p.port());
      status = "The server refused the connection";
      return;
    }
    editing.saved();
    status = "Connecting to " + p.database() + " on " + p.host() + ":" + p.port() + " as "
        + p.user() + (p.ssl() ? " with SSL" : "");
  }

  void reset() {
    editing.reset();
    revealed = false;
    status = "";
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
        .minimumSize(400, 452);
    try (Window window = Window.open("New connection", 440, 472, options);
        GpuPresenter presenter = new GpuPresenter(window)) {
      WindowLoop.run(window, app, presenter);
    }
  }

  static El view(ConnectionDialog s) {
    Theme t = s.theme();
    Editing<Profile> e = s.editing;
    // A null handler disables the button.
    El connect = button(t, PRIMARY, "Connect", s.ready() ? s::connect : null);
    El form = Card.card(t)
        .child(
            Card.header(t, "Connect to PostgreSQL", "The server and the account to log in with."))
        .child(form(
                t,
                Form.Labels.BESIDE,
                Form.group(
                    "Server",
                    // The host takes the room the port leaves; both are a field of their own.
                    Form.row(
                        field(
                                "Host",
                                textField(t, e.at(Profile.HOST), "Name or address")
                                    .key("host")
                                    .w(160))
                            .required()
                            .grow(),
                        field(
                                "Port",
                                valueField(t, PORT, e.at(Profile.PORT), "5432")
                                    .key("port")
                                    .maxLength(5)
                                    .w(72))
                            .required()),
                    field(
                            "Database",
                            ComboBox.free(t, e.at(Profile.DATABASE), DATABASES, s.databases)
                                .key("database")
                                .wFull())
                        .required()),
                Form.group(
                    "Login",
                    field(
                            "User",
                            textField(t, e.at(Profile.USER), "Role").key("user").wFull())
                        .required(),
                    field(
                        "Password",
                        revealable(
                                t,
                                passwordField(t, e.at(Profile.PASSWORD), null)
                                    .key("password")
                                    .wFull(),
                                s.revealed,
                                () -> s.revealed = !s.revealed)
                            .wFull())))
            .margin(t.space().s(), 0, t.space().s(), 0))
        .child(checkbox(t, e.at(Profile.SSL), "Require SSL"))
        .child(toggle(t, Prop.of(() -> s.dark, v -> s.dark = v), "Dark theme"))
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
