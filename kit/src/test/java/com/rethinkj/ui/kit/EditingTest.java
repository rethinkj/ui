package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Props and editings over records and POJOs, without native libraries. */
class EditingTest {

  record Tls(String mode, String cert) {
    static final Part<Tls, String> MODE = Part.of(Tls::mode, Tls::withMode);

    Tls withMode(String mode) {
      return new Tls(mode, cert);
    }
  }

  record Profile(String host, Integer port, Tls tls, LocalDate from, LocalDate to) {
    static final Part<Profile, String> HOST = Part.of(Profile::host, Profile::withHost);
    static final Part<Profile, Integer> PORT = Part.of(Profile::port, Profile::withPort);
    static final Part<Profile, Tls> TLS = Part.of(Profile::tls, Profile::withTls);
    static final Part<Profile, LocalDate> TO = Part.of(Profile::to, (p, v) -> p.withTo(v));

    Profile withHost(String host) {
      return new Profile(host, port, tls, from, to);
    }

    Profile withPort(Integer port) {
      return new Profile(host, port, tls, from, to);
    }

    Profile withTls(Tls tls) {
      return new Profile(host, port, tls, from, to);
    }

    Profile withTo(LocalDate to) {
      return new Profile(host, port, tls, from, to);
    }
  }

  static final class Settings {
    static final Part<Settings, Integer> FETCH =
        Part.ofSetter(Settings::getFetch, Settings::setFetch);
    static final Part<Settings, String> NAME = Part.ofSetter(Settings::getName, (s, v) -> {
      s.setName(v);
    });

    private int fetch = 100;
    private String name = "local";

    Settings() {}

    Settings(Settings other) {
      fetch = other.fetch;
      name = other.name;
    }

    int getFetch() {
      return fetch;
    }

    void setFetch(Integer fetch) {
      this.fetch = fetch;
    }

    String getName() {
      return name;
    }

    void setName(String name) {
      this.name = name;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Settings s && s.fetch == fetch && s.name.equals(name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(fetch, name);
    }
  }

  static final Format<Integer> PORT =
      Format.INTEGER.where(p -> p >= 1 && p <= 65535, "1 to 65535").optional();

  static Profile profile() {
    return new Profile(
        "localhost",
        5432,
        new Tls("require", null),
        LocalDate.of(2026, 11, 1),
        LocalDate.of(2026, 11, 30));
  }

  /** What a field of the port does as it is built: it shows the value, or its draft. */
  static String shown(Editing<Profile> e) {
    Prop<Integer> p = e.at(Profile.PORT);
    p.draft().show(PORT, p.get());
    return p.draft().text();
  }

  /** What a field of the port does with a text typed into it. */
  static void type(Editing<Profile> e, String text) {
    shown(e);
    Prop<Integer> p = e.at(Profile.PORT);
    p.draft().edit(PORT, text, p.onChange());
  }

  @Test
  void aPropOverTheAppsStateWritesARecordBackThroughItsParts() {
    Profile[] state = {profile()};
    Prop<Profile> p = Prop.of(() -> state[0], v -> state[0] = v);
    p.at(Profile.HOST).set("db");
    assertEquals("db", state[0].host());
    p.at(Profile.TLS).at(Tls.MODE).set("verify-full");
    assertEquals(new Tls("verify-full", null), state[0].tls(), "nested: a new Tls, a new Profile");
    p.at(Profile.TLS.then(Tls.MODE)).set("disable");
    assertEquals("disable", state[0].tls().mode(), "a joined part is the same way in");
    assertEquals("db", state[0].host());
  }

  @Test
  void aPropWithoutASetterOrOfANullWholeCannotBeWritten() {
    Profile[] state = {profile().withTls(null)};
    Prop<Profile> p = Prop.of(() -> state[0], null);
    assertFalse(p.at(Profile.HOST).writes());
    assertNull(p.at(Profile.HOST).onChange(), "a component gets a null handler: disabled");
    assertThrows(IllegalStateException.class, () -> p.at(Profile.HOST).set("x"));
    Prop<Profile> w = Prop.of(() -> state[0], v -> state[0] = v);
    Prop<String> mode = w.at(Profile.TLS).at(Tls.MODE);
    assertNull(mode.get(), "inside a null part reads null");
    assertFalse(mode.writes());
    assertNull(w.at(Profile.TLS.then(Tls.MODE)).get());
  }

  @Test
  void aPojoIsEditedInACopyThroughItsSetters() {
    Settings original = new Settings();
    Editing<Settings> e = Editing.of(original, Settings::new);
    assertNotSame(original, e.value());
    e.at(Settings.FETCH).set(500);
    e.at(Settings.NAME).set("prod");
    assertEquals(500, e.value().getFetch());
    assertEquals(100, original.getFetch(), "the original stays as it was");
    assertTrue(e.changed());
    e.at(Settings.FETCH).set(100);
    e.at(Settings.NAME).set("local");
    assertFalse(e.changed(), "by equals");
    e.at(Settings.FETCH).set(7);
    e.reset();
    assertEquals(100, e.value().getFetch());
    assertNotSame(original, e.value(), "reset edits a new copy");
  }

  @Test
  void changedValidResetAndSaved() {
    Profile start = profile();
    Editing<Profile> e = Editing.of(start);
    assertFalse(e.changed());
    assertTrue(e.valid());
    e.at(Profile.HOST).set("db");
    assertTrue(e.changed());
    e.at(Profile.HOST).set("localhost");
    assertFalse(e.changed(), "back to an equal record");
    type(e, "70000");
    assertEquals(5432, e.value().port(), "a text that is no port leaves the value");
    assertFalse(e.valid());
    assertEquals("1 to 65535", e.at(Profile.PORT).error());
    assertEquals("70000", shown(e));
    type(e, "6543");
    assertTrue(e.valid());
    assertEquals(6543, e.value().port());
    e.saved();
    assertFalse(e.changed());
    assertEquals(6543, e.original().port());
    type(e, "0");
    e.reset();
    assertTrue(e.valid(), "reset forgets drafts");
    assertEquals(6543, e.value().port());
    assertEquals("6543", shown(e), "the field shows the value again");
  }

  @Test
  void aRuleShowsAtItsFieldAndMakesTheEditInvalid() {
    Editing<Profile> e = Editing.of(profile())
        .rule(Profile.TO, p -> !p.to().isBefore(p.from()), "Not before the start")
        .rule(Profile.TLS.then(Tls.MODE), p -> !p.tls().mode().isBlank(), "Choose a mode");
    assertTrue(e.valid());
    e.at(Profile.TO).set(LocalDate.of(2026, 10, 1));
    assertFalse(e.valid());
    assertEquals("Not before the start", e.at(Profile.TO).error());
    assertNull(e.at(Profile.HOST).error(), "only at the field it names");
    e.at(Profile.TO).set(LocalDate.of(2026, 12, 1));
    e.at(Profile.TLS).at(Tls.MODE).set("");
    assertEquals("Choose a mode", e.at(Profile.TLS).at(Tls.MODE).error(), "a field in a field");
    assertFalse(e.valid());
  }

  @Test
  void anErrorOfTheAppShowsUntilTheNextEditOfItsField() {
    Editing<Profile> e = Editing.of(profile());
    e.at(Profile.PORT).error("The server refused the port");
    assertEquals("The server refused the port", e.at(Profile.PORT).error());
    assertFalse(e.valid());
    e.at(Profile.HOST).set("db");
    assertFalse(e.valid(), "an edit of another field leaves it");
    type(e, "6543");
    assertNull(e.at(Profile.PORT).error());
    assertTrue(e.valid());
    assertThrows(IllegalStateException.class, () -> Prop.of(() -> 1, null).error("x"));
  }

  @Test
  void aFieldThatShowsOnlyUnderAConditionKeepsItsDraft() {
    Editing<Profile> e = Editing.of(profile());
    type(e, "99999");
    // Builds without the port's field: the host's field finds no draft, the port's stays.
    e.at(Profile.HOST).set("db");
    assertNull(e.at(Profile.HOST).error());
    assertFalse(e.valid(), "the hidden field's text still is no port");
    assertEquals("99999", shown(e), "the field comes back with its text");
    assertEquals("1 to 65535", e.at(Profile.PORT).error());
  }

  @Test
  void aPropMadeInEachBuildIsTheSameFieldButAPartMadeInEachBuildIsNot() {
    Editing<Profile> e = Editing.of(profile());
    assertSame(e.at(Profile.PORT).draft(), e.at(Profile.PORT).draft());
    Part<Profile, Integer> again = Part.of(Profile::port, Profile::withPort);
    assertNotSame(e.at(Profile.PORT).draft(), e.at(again).draft(), "declare a part once");
    assertThrows(IllegalStateException.class, () -> Prop.of(() -> 1, v -> {}).draft());
  }
}
