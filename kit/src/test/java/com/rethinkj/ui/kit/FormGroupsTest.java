package com.rethinkj.ui.kit;

import static com.rethinkj.ui.El.col;
import static com.rethinkj.ui.kit.Form.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Script;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Groups of fields under headings and rows of several fields in a form, driven by scripts. */
class FormGroupsTest {

  @BeforeEach
  void textLibraries() {
    TextLibraries.assumeLoaded();
  }

  private static String dump(App<?> app, int width, String steps) throws IOException {
    Script.run(
        "size " + width + " 480\nframe\n" + steps + "\nframe\nadvance 1\nframe",
        app,
        Path.of("target/form-groups-test"));
    return app.tree().dump();
  }

  private static String dump(int width, Function<Theme, El> view) throws IOException {
    Theme t = Theme.DARK;
    App<Object> app = new App<>(
        new Object(),
        s -> col().wFull().hFull().padding(16).fill(t.surface().window()).child(view.apply(t)));
    return dump(app, width, "");
  }

  private static String line(String dump, String part) {
    return StructureTest.line(dump, part);
  }

  private static float[] rect(String dump, String part) {
    return StructureTest.rect(line(dump, part));
  }

  @Test
  void theControlsOfAllGroupsStartAfterTheWidestLabelOfAny() throws IOException {
    String dump = dump(new Sheets(Theme.DARK).app(Sheets::formGroups), 820, "");
    float host = rect(dump, "#beside-host")[0];
    for (String key : new String[] {"#beside-database", "#beside-user", "#beside-password"}) {
      assertEquals(host, rect(dump, key)[0], key + " in " + dump);
    }
    // "Password", in the second group, is the widest label, and its column is the form's.
    float[] password = rect(dump, "text \"Password\"");
    assertEquals(password[0] + password[2] + 8, host, 1, dump);
    assertTrue(rect(dump, "text \"Database\"")[2] < password[2], dump);
  }

  @Test
  void aGroupIsAGroupWithAHeadingOfTheFourthLevelAndADividerSetsItApart() throws IOException {
    String dump = dump(new Sheets(Theme.DARK).app(Sheets::formGroups), 820, "");
    assertTrue(dump.contains("el role=group label=\"Server\" "), dump);
    assertTrue(line(dump, "text role=heading \"Server\"").endsWith("level=4"), dump);
    assertTrue(dump.contains("text \"Where the database runs\""), dump);
    float[] database = rect(dump, "#beside-database");
    float[] divider = rect(dump, "role=separator");
    float[] login = rect(dump, "role=group label=\"Login\"");
    // Two steps further apart than fields, on each side of the divider.
    assertEquals(16, divider[1] - (database[1] + database[3]), 1, dump);
    assertEquals(16, login[1] - (divider[1] + divider[3]), 1, dump);
    float[] user = rect(dump, "#beside-user");
    float[] password = rect(dump, "#beside-password");
    assertEquals(8, password[1] - (user[1] + user[3]), 1, "fields are m apart: " + dump);
  }

  @Test
  void nullsAreLeftOutAlsoAGroupOfNullsAndARowOfOneIsThatField() throws IOException {
    String dump = dump(
        400,
        t -> Form.form(
            t,
            Form.Labels.BESIDE,
            null,
            Form.group("Empty", null, Form.row(null, null)).divided(),
            Form.group(
                "Server",
                Form.row(
                    field("Host", TextField.textField(t, "", null, v -> {}).key("host")), null)),
            Form.row()));
    assertFalse(dump.contains("Empty"), dump);
    assertFalse(dump.contains("role=separator"), "the first group has nothing before it");
    String host = line(dump, "#host");
    assertTrue(host.contains("label=\"Host\""), dump);
    // As a field of its own: the form's width less the label column.
    assertEquals(200, rect(dump, "#host")[2], dump);
  }

  @Test
  void eachFieldOfARowIsNamedAndHasItsOwnErrorAndHelpUnderItsControl() throws IOException {
    String dump = dump(new Sheets(Theme.DARK).app(Sheets::formGroups), 820, "");
    String host = line(dump, "#beside-host");
    String port = line(dump, "#beside-port");
    assertTrue(host.contains("label=\"Host\"") && !host.contains(" invalid"), host);
    assertTrue(port.contains("label=\"Port\" ") && port.contains("invalid error=\"Digits only\""));
    float[] h = rect(dump, "#beside-host");
    float[] p = rect(dump, "#beside-port");
    assertEquals(h[1], p[1], "on one line: " + dump);
    float[] help = rect(dump, "text \"A name or an address");
    float[] error = rect(dump, "text \"Digits only\"");
    assertEquals(h[0], help[0], dump);
    assertEquals(h[2], help[2], "as wide as its control: " + dump);
    assertEquals(p[0], error[0], dump);
    assertEquals(p[2], error[2], dump);
    // The port's label stands before its control, in the label's look.
    float[] label = rect(dump, "text \"Port\"");
    assertEquals(p[0] - 8, label[0] + label[2], 1, dump);
    assertTrue(label[0] > h[0] + h[2], dump);
  }

  @Test
  void aFieldThatGrowsTakesTheRoomTheOthersKeepTheirWidths() throws IOException {
    String dump = dump(new Sheets(Theme.DARK).app(Sheets::formGroups), 820, "");
    float[] port = rect(dump, "#beside-port");
    float[] database = rect(dump, "#beside-database");
    float[] form = rect(dump, "role=group label=\"Server\"");
    assertEquals(72, port[2], dump);
    assertEquals(form[0] + form[2], port[0] + port[2], 1, "the row ends at the form's edge");
    assertEquals(200, database[2], "a field alone keeps its width");
    assertTrue(rect(dump, "#beside-host")[2] > 300, dump);
  }

  @Test
  void withLabelsAboveEachFieldOfARowHasItsLabelAboveItsControl() throws IOException {
    String dump = dump(
        560,
        t -> Form.form(
            t,
            Form.Labels.ABOVE,
            Form.row(
                field("First name", TextField.textField(t, "", null, v -> {}).key("first")),
                field("Last name", TextField.textField(t, "", null, v -> {}).key("last"))
                    .required())));
    float[] first = rect(dump, "#first");
    float[] last = rect(dump, "#last");
    float[] firstLabel = rect(dump, "text \"First name\"");
    float[] lastLabel = rect(dump, "text \"Last name\"");
    assertEquals(first[1], last[1], dump);
    assertEquals(first[0], firstLabel[0], dump);
    assertEquals(last[0], lastLabel[0], dump);
    assertTrue(lastLabel[1] + lastLabel[3] <= last[1], dump);
    assertEquals(first[0] + first[2] + 8, last[0], 1, dump);
    assertTrue(line(dump, "#last").contains("label=\"Last name\""), dump);
  }

  @Test
  void formsInSeparateCardsShareALabelColumnWhenGivenTheWidthOfAll() throws IOException {
    String dump = dump(560, t -> {
      Form.Field host = field("Host", TextField.textField(t, "", null, v -> {}).key("host"));
      Form.Field database =
          field("Database", TextField.textField(t, "", null, v -> {}).key("database"));
      float column = Form.labelWidth(t, host, Form.group("Login", database));
      return col()
          .gap(16)
          .children(
              Card.card(t, Form.form(t, Form.Labels.BESIDE, column, host)),
              Card.card(t, Form.form(t, Form.Labels.BESIDE, column, database)));
    });
    assertEquals(rect(dump, "#host")[0], rect(dump, "#database")[0], dump);
  }

  record Server(String host, Integer port) {
    static final Part<Server, String> HOST = Part.of(Server::host, (s, v) -> new Server(v, s.port));
    static final Part<Server, Integer> PORT =
        Part.of(Server::port, (s, v) -> new Server(s.host, v));
  }

  @Test
  void aRowOfValueFieldsOverAnEditingShowsADraftsErrorAndARulesError() throws IOException {
    Theme t = Theme.DARK;
    Format<Integer> ports = Format.INTEGER.where(p -> p >= 1 && p <= 65535, "1 to 65535");
    Editing<Server> e = Editing.of(new Server("db", 5432))
        .rule(Server.HOST, s -> !s.host().isBlank(), "A host is required");
    App<Object> app = new App<>(
        new Object(),
        x -> col()
            .wFull()
            .hFull()
            .padding(16)
            .child(Form.form(
                t,
                Form.Labels.BESIDE,
                Form.row(
                    field("Host", TextField.textField(t, e.at(Server.HOST)).key("host"))
                        .grow(),
                    field(
                            "Port",
                            ValueField.valueField(t, ports, e.at(Server.PORT))
                                .key("port")
                                .w(72))
                        .required()))));
    String dump = dump(
        app, 520, "focus #host\nkey A control\nkey Delete\nfocus #port\nkey A control\ntype \"0\"");
    assertTrue(line(dump, "#host").contains("invalid error=\"A host is required\""), dump);
    assertTrue(line(dump, "#port").contains("invalid error=\"1 to 65535\""), dump);
    assertTrue(dump.contains("text \"A host is required\""), dump);
    assertTrue(dump.contains("text \"1 to 65535\""), dump);
    assertEquals(rect(dump, "#host")[1], rect(dump, "#port")[1], dump);
    assertFalse(e.valid());
    dump = dump(app, 520, "type \"1\"\nfocus #host\ntype \"db\"");
    assertFalse(line(dump, "#port").contains(" invalid"), dump);
    assertFalse(line(dump, "#host").contains(" invalid"), dump);
    assertEquals(new Server("db", 1), e.value());
  }
}
