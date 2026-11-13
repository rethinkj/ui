package com.rethinkj.ui.layout;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.rethinkj.ui.El;
import com.rethinkj.ui.Len;
import com.rethinkj.ui.Measure;
import com.rethinkj.ui.Style;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs Taffy's flexbox test cases: styled trees with the rectangles Chrome computes for them. The
 * data in {@code taffy/flex.xml} is imported by {@code scripts/import-taffy-fixtures.sh}.
 */
class TaffyFlexTest {

  /**
   * The test cases that are left out, by what they use. A case that uses a style property or value
   * not listed here and not known to {@link #style} fails.
   */
  private static final List<Skip> SKIPPED =
      List.of(
          new Skip("Style has no writing direction, so no rtl", uses("direction", "rtl")),
          new Skip("block and grid layout are not implemented", uses("display", "block|grid")),
          new Skip(
              "Style has no position: static, which the data writes by leaving position out",
              xml -> xml.anyMatch(element -> !element.attributes.containsKey("position"))),
          new Skip(
              "Style has no flex-wrap: balance and no flex-line-count",
              uses("flex-wrap", ".*balance").or(uses("flex-line-count", ".*"))),
          new Skip(
              "Style.Align and Style.Content have no safe and unsafe",
              uses("align-items|align-self|align-content|justify-content", "(un)?safe .*")),
          new Skip(
              "Style.Align has no self-start and self-end",
              uses("align-items|align-self", "self-(start|end)")),
          new Skip(
              "Len has no min-content, max-content, fit-content, stretch and content",
              uses(
                  "width|height|flex-basis",
                  "min-content|max-content|fit-content|stretch|content")));

  private record Skip(String reason, Predicate<Xml> applies) {}

  /** Chrome's and Taffy's rounding can differ in the last digits; Taffy's own tests allow this. */
  private static final float TOLERANCE = 0.1f;

  @TestFactory
  Stream<DynamicTest> matchesChrome() throws IOException {
    return cases().stream().map(test -> DynamicTest.dynamicTest(test.get("name"), () -> run(test)));
  }

  @Test
  void everySkipRuleStillApplies() throws IOException {
    List<Xml> cases = cases();
    for (Skip skip : SKIPPED) {
      assertTrue(
          cases.stream().anyMatch(test -> skip.applies.test(test.child("input").children.get(0))),
          "no test case is skipped because: " + skip.reason);
    }
  }

  private static List<Xml> cases() throws IOException {
    try (InputStream in = TaffyFlexTest.class.getResourceAsStream("/taffy/flex.xml")) {
      return new XmlReader(new String(in.readAllBytes(), StandardCharsets.UTF_8))
          .element()
          .children;
    }
  }

  private static void run(Xml test) {
    Xml input = test.child("input").children.get(0);
    for (Skip skip : SKIPPED) {
      Assumptions.assumeFalse(skip.applies.test(input), skip.reason);
    }
    El root = element(input);
    Xml viewport = test.child("viewport");
    Flex.layout(
        root,
        available(viewport.get("width")),
        available(viewport.get("height")),
        Boolean.parseBoolean(test.get("use-rounding")));

    Xml expected = test.child("expectations").children.get(0);
    StringBuilder differences = new StringBuilder();
    compare(root, expected, "root", differences);
    if (!differences.isEmpty()) {
      fail(
          test.get("name")
              + "\n"
              + differences
              + "\nlayout:\n"
              + root.dump()
              + "\ninput:\n"
              + input);
    }
  }

  private static void compare(El el, Xml expected, String path, StringBuilder differences) {
    float[] actual = {el.x(), el.y(), el.width(), el.height()};
    String[] names = {"x", "y", "width", "height"};
    for (int i = 0; i < names.length; i++) {
      float value = Float.parseFloat(expected.get(names[i]));
      if (!(Math.abs(actual[i] - value) < TOLERANCE)) {
        differences
            .append(path)
            .append(' ')
            .append(names[i])
            .append(": expected ")
            .append(value)
            .append(" but was ")
            .append(actual[i])
            .append('\n');
      }
    }
    if (el.children().size() != expected.children.size()) {
      throw new IllegalStateException("input and expectations differ in shape at " + path);
    }
    for (int i = 0; i < expected.children.size(); i++) {
      compare(el.children().get(i), expected.children.get(i), path + "/" + i, differences);
    }
  }

  // Building the tree

  private static El element(Xml xml) {
    El el = El.el();
    xml.attributes.forEach((name, value) -> style(el.style(), name, value));
    String text = xml.text.strip();
    if (xml.children.isEmpty() && !text.isEmpty()) {
      el.measure(ahem(text, xml.attributes.getOrDefault("writing-mode", "").contains("vertical")));
    }
    for (Xml child : xml.children) {
      el.child(element(child));
    }
    return el;
  }

  private static void style(Style s, String name, String value) {
    switch (name) {
      case "display" -> s.display = keyword(Style.Display.class, value);
      case "direction" -> require(value.equals("ltr"), name, value);
      case "writing-mode" -> {} // only on text, where element() reads it
      case "box-sizing" -> s.boxSizing = keyword(Style.BoxSizing.class, value);
      case "position" -> s.position = keyword(Style.Position.class, value);
      case "overflow-x" -> s.overflowX = keyword(Style.Overflow.class, value);
      case "overflow-y" -> s.overflowY = keyword(Style.Overflow.class, value);
      case "scrollbar-width" -> s.scrollbarWidth = Float.parseFloat(value);
      case "flex-direction" -> s.direction = keyword(Style.Direction.class, value);
      case "flex-wrap" ->
          s.wrap = keyword(Style.Wrap.class, value.equals("nowrap") ? "no-wrap" : value);
      case "align-items" -> s.alignItems = keyword(Style.Align.class, value);
      case "align-self" -> s.alignSelf = keyword(Style.Align.class, value);
      case "align-content" -> s.alignContent = keyword(Style.Content.class, value);
      case "justify-content" -> s.justifyContent = keyword(Style.Content.class, value);
      case "row-gap" -> s.rowGap = len(value);
      case "column-gap" -> s.columnGap = len(value);
      case "flex-grow" -> s.grow = Float.parseFloat(value);
      case "flex-shrink" -> s.shrink = Float.parseFloat(value);
      case "flex-basis" -> s.basis = len(value);
      case "width" -> s.width = len(value);
      case "height" -> s.height = len(value);
      case "min-width" -> s.minWidth = len(value);
      case "min-height" -> s.minHeight = len(value);
      case "max-width" -> s.maxWidth = len(value);
      case "max-height" -> s.maxHeight = len(value);
      case "aspect-ratio" -> s.aspectRatio = Float.parseFloat(value);
      case "margin-left" -> s.marginLeft = len(value);
      case "margin-right" -> s.marginRight = len(value);
      case "margin-top" -> s.marginTop = len(value);
      case "margin-bottom" -> s.marginBottom = len(value);
      case "padding-left" -> s.paddingLeft = len(value);
      case "padding-right" -> s.paddingRight = len(value);
      case "padding-top" -> s.paddingTop = len(value);
      case "padding-bottom" -> s.paddingBottom = len(value);
      case "border-left" -> s.borderLeft = len(value);
      case "border-right" -> s.borderRight = len(value);
      case "border-top" -> s.borderTop = len(value);
      case "border-bottom" -> s.borderBottom = len(value);
      case "left" -> s.left = len(value);
      case "right" -> s.right = len(value);
      case "top" -> s.top = len(value);
      case "bottom" -> s.bottom = len(value);
      default -> require(false, name, value);
    }
  }

  private static void require(boolean known, String name, String value) {
    if (!known) {
      throw new IllegalArgumentException("unknown style " + name + "=\"" + value + "\"");
    }
  }

  private static <E extends Enum<E>> E keyword(Class<E> type, String value) {
    return Enum.valueOf(type, value.toUpperCase().replace('-', '_'));
  }

  private static Len len(String value) {
    if (value.equals("auto")) {
      return Len.AUTO;
    }
    if (value.endsWith("px")) {
      return Len.px(Float.parseFloat(value.substring(0, value.length() - 2)));
    }
    if (value.endsWith("%")) {
      return Len.percent(Float.parseFloat(value.substring(0, value.length() - 1)) / 100);
    }
    throw new IllegalArgumentException("unknown length " + value);
  }

  private static float available(String value) {
    return switch (value) {
      case "max-content" -> Measure.MAX_CONTENT;
      case "min-content" -> Measure.MIN_CONTENT;
      default -> len(value).value();
    };
  }

  /**
   * Text in the Ahem font at 10px, as Taffy's tests measure it: every glyph is a 10px square, and a
   * line can break at each zero-width space. The baseline is 8px below the top.
   */
  private static Measure ahem(String text, boolean vertical) {
    String[] words = text.split("​");
    int longestWord = 0;
    int length = 0;
    for (String word : words) {
      longestWord = Math.max(longestWord, word.length());
      length += word.length();
    }
    float minContent = longestWord * 10f;
    float maxContent = length * 10f;
    return (knownWidth, knownHeight, availableWidth, availableHeight) -> {
      float inline = vertical ? knownHeight : knownWidth;
      float block = vertical ? knownWidth : knownHeight;
      float available = vertical ? availableHeight : availableWidth;
      if (Float.isNaN(inline)) {
        inline =
            available == Measure.MIN_CONTENT
                ? minContent
                : available == Measure.MAX_CONTENT ? maxContent : Math.min(available, maxContent);
      }
      inline = Math.max(inline, minContent);
      if (Float.isNaN(block)) {
        int glyphsPerLine = (int) Math.floor(inline / 10);
        int lines = 1;
        int glyphs = 0;
        for (String word : words) {
          if (glyphs + word.length() > glyphsPerLine) {
            if (glyphs > 0) {
              lines++;
            }
            glyphs = word.length();
          } else {
            glyphs += word.length();
          }
        }
        block = lines * 10f;
      }
      return vertical ? new Measure.Size(block, inline) : new Measure.Size(inline, block, 8);
    };
  }

  // Reading the data. java.xml is not on the module path, and the files only use elements,
  // attributes in double quotes and text.

  private static Predicate<Xml> uses(String names, String values) {
    Pattern name = Pattern.compile(names);
    Pattern value = Pattern.compile(values);
    return xml ->
        xml.anyMatch(
            element ->
                element.attributes.entrySet().stream()
                    .anyMatch(
                        attribute ->
                            name.matcher(attribute.getKey()).matches()
                                && value.matcher(attribute.getValue()).matches()));
  }

  private static final class Xml {
    String name;
    final Map<String, String> attributes = new LinkedHashMap<>();
    final List<Xml> children = new ArrayList<>();
    String text = "";

    String get(String attribute) {
      return attributes.get(attribute);
    }

    Xml child(String name) {
      return children.stream().filter(child -> child.name.equals(name)).findFirst().orElseThrow();
    }

    /** Whether this element or one below it matches. */
    boolean anyMatch(Predicate<Xml> matches) {
      return matches.test(this) || children.stream().anyMatch(child -> child.anyMatch(matches));
    }

    @Override
    public String toString() {
      StringBuilder out = new StringBuilder();
      print(out, 0);
      return out.toString();
    }

    private void print(StringBuilder out, int depth) {
      out.repeat("  ", depth).append(name);
      attributes.forEach((key, value) -> out.append(' ').append(key).append('=').append(value));
      out.append('\n');
      children.forEach(child -> child.print(out, depth + 1));
    }
  }

  private static final class XmlReader {
    private final String source;
    private int at;

    XmlReader(String source) {
      this.source = source;
    }

    /** Reads the element that starts at the next {@code <}. */
    Xml element() {
      Xml xml = new Xml();
      at = source.indexOf('<', at) + 1;
      xml.name = until(" />");
      while (true) {
        while (source.charAt(at) == ' ') {
          at++;
        }
        if (source.startsWith("/>", at)) {
          at += 2;
          return xml;
        }
        if (source.charAt(at) == '>') {
          at++;
          break;
        }
        String name = until("=");
        at += 2;
        xml.attributes.put(name, until("\""));
        at++;
      }
      while (true) {
        int next = source.indexOf('<', at);
        xml.text += source.substring(at, next);
        at = next;
        if (source.startsWith("</", at)) {
          at = source.indexOf('>', at) + 1;
          return xml;
        }
        xml.children.add(element());
      }
    }

    private String until(String stops) {
      int start = at;
      while (stops.indexOf(source.charAt(at)) < 0) {
        at++;
      }
      return source.substring(start, at);
    }
  }
}
