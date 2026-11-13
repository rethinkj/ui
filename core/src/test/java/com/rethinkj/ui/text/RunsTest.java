package com.rethinkj.ui.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/** How a text is split into runs of one font and one script; no native libraries needed. */
class RunsTest {

  /** Font 0 has Latin, digits and punctuation; the others have what the test gives them. */
  private static final IntPredicate LATIN = c -> c < 0x250;

  private static List<String> runs(String text, IntPredicate... fonts) {
    return Runs.of(text, fonts.length, (f, c) -> fonts[f].test(c)).stream()
        .map(r -> r.font() + (r.rtl() ? "<" : ">") + text.substring(r.from(), r.to()))
        .toList();
  }

  private static IntPredicate range(int first, int last) {
    return c -> c >= first && c <= last;
  }

  @Test
  void textTheFirstFontHasIsOneRun() {
    assertEquals(
        List.of("0>Kunde Müller, Köln: 1.234,56"), runs("Kunde Müller, Köln: 1.234,56", LATIN));
    assertEquals(List.of(), runs("", LATIN));
  }

  @Test
  void eachClusterGoesToTheFirstFontThatHasIt() {
    IntPredicate cjk = range(0x4E00, 0x9FFF);
    assertEquals(List.of("0>a", "1>中", "0>b"), runs("a中b", LATIN, cjk));
    assertEquals(
        List.of("1>中", "2>✓"), runs("中✓", LATIN, cjk.or(c -> c == ' '), c -> c == '✓' || c == '中'));
    assertEquals(
        List.of("0>a", "0>中", "0>b"),
        runs("a中b", LATIN),
        "a character no font has stays with the first font, in a run of its script");
  }

  @Test
  void neutralsBetweenTwoClustersOfAFallbackFontStayWithIt() {
    IntPredicate cjk = range(0x3000, 0x9FFF).or(c -> c == ' ' || c == ',' || Character.isDigit(c));
    assertEquals(List.of("1>日本 語, 12 字"), runs("日本 語, 12 字", LATIN, cjk));
    assertEquals(
        List.of("0>abc ", "1>日本", "0> 12"),
        runs("abc 日本 12", LATIN, cjk),
        "not where a font change follows, or at the ends");
    assertEquals(List.of("0>(", "1>日本", "0>)"), runs("(日本)", LATIN, cjk));
    IntPredicate symbols = c -> c == '┌' || c == '─' || c == ' ';
    assertEquals(
        List.of("0>a ", "1>┌─ ─", "0> b"),
        runs("a ┌─ ─ b", LATIN, symbols),
        "symbols of a fallback font keep the space between them");
  }

  @Test
  void graphemeClustersAreNeverSplitAcrossFonts() {
    IntPredicate marks = LATIN.or(c -> c == 0x301);
    IntPredicate noAcute = c -> LATIN.test(c) && c != 0xE9;
    assertEquals(
        List.of("0>a", "1>e\u0301", "0>b"),
        runs("ae\u0301b", noAcute, marks),
        "a letter with a mark the first font lacks goes whole to the font that has both");
    assertEquals(
        List.of("0>ae\u0301b"),
        runs("ae\u0301b", LATIN, marks),
        "unless the first font has the composed letter");
    IntPredicate people = c -> c == 0x1F468 || c == 0x1F469 || c == 0x1F467;
    assertEquals(
        List.of("0>a", "1>👨\u200D👩\u200D👧", "0>b"),
        runs("a👨\u200D👩\u200D👧b", LATIN, people),
        "a sequence joined by ZWJ, which no font needs to have");
    assertEquals(
        List.of("2>🇩🇪"),
        runs("🇩🇪", LATIN, c -> c == 0x1F1E9, range(0x1F1E6, 0x1F1FF)),
        "a flag goes to the font with both regional indicators");
    assertEquals(
        List.of("0>I ", "1>❤\uFE0F", "0> SQL"),
        runs("I ❤\uFE0F SQL", LATIN, c -> c == 0x2764),
        "a variation selector stays with its base");
  }

  @Test
  void scriptsSplitRunsOfOneFont() {
    IntPredicate all = c -> true;
    assertEquals(List.of("0>abc ", "0>αβγ"), runs("abc αβγ", all));
    assertEquals(List.of("0>Ab12 ", "0<אב"), runs("Ab12 אב", all));
  }

  @Test
  void rightToLeftRunsKeepTheirSpacesAndNotTheirNumbers() {
    IntPredicate hebrew = range(0x0590, 0x05FF).or(c -> c == ' ' || c == ',');
    assertEquals(
        List.of("0>Kunde: ", "1<שלום עולם", "0>, Tel Aviv"),
        runs("Kunde: שלום עולם, Tel Aviv", LATIN, hebrew));
    assertEquals(
        List.of("1<הזמנה", "1> ", "0>1234", "1> ", "1<נשלחה"),
        runs("הזמנה 1234 נשלחה", LATIN, hebrew),
        "digits and the spaces beside them stay left to right, in the font around them");
    IntPredicate arabic = range(0x0600, 0x06FF).or(c -> c == ' ');
    assertEquals(
        List.of("1<رقم", "1> ٣٤"),
        runs("رقم ٣٤", LATIN, arabic),
        "Arabic-Indic digits too; a space is right to left only between right-to-left letters");
    assertEquals(
        List.of("0>(", "1<שלום", "0>)"),
        runs("(שלום)", LATIN, hebrew),
        "brackets at the ends are not mirrored into the run");
  }
}
