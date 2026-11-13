package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Components take colours, springs and shadows from the theme only: colours are written in {@link
 * Palette} alone, springs and shadows in {@link Theme} alone, which makes the default themes of
 * the palette's colours.
 */
class TokenTest {

  private static final Path SOURCES = Path.of("src/main/java/com/rethinkj/ui/kit");
  private static final Pattern COLOUR =
      Pattern.compile("0[xX][0-9A-Fa-f]{8}\\b|\\brgb\\(\\s*0[xX]|\\bhex\\(\\s*\"");
  private static final Pattern MOTION =
      Pattern.compile("Spring\\.[A-Z]|new Spring\\(|new Shadow\\(");

  @Test
  void componentsHaveNoColourOrSpringOfTheirOwn() throws IOException {
    List<String> found = new ArrayList<>();
    try (Stream<Path> files = Files.list(SOURCES)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
        String name = file.getFileName().toString();
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
          if (!name.equals("Palette.java") && COLOUR.matcher(lines.get(i)).find()
              || !name.equals("Theme.java") && MOTION.matcher(lines.get(i)).find()) {
            found.add(file.getFileName() + ":" + (i + 1) + ": " + lines.get(i).strip());
          }
        }
      }
    }
    assertTrue(found.size() == 0,
        "Take these from the theme instead:\n" + String.join("\n", found));
  }
}
