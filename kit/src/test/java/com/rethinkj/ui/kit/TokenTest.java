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

/** Components take colours and springs from the theme only; the theme is the one file with them. */
class TokenTest {

  private static final Path SOURCES = Path.of("src/main/java/com/rethinkj/ui/kit");
  private static final Pattern LITERAL =
      Pattern.compile("0[xX][0-9A-Fa-f]{8}\\b|Spring\\.[A-Z]|new Spring\\(");

  @Test
  void componentsHaveNoColourOrSpringOfTheirOwn() throws IOException {
    List<String> found = new ArrayList<>();
    try (Stream<Path> files = Files.list(SOURCES)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
        if (file.getFileName().toString().equals("Theme.java")) {
          continue;
        }
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
          if (LITERAL.matcher(lines.get(i)).find()) {
            found.add(file.getFileName() + ":" + (i + 1) + ": " + lines.get(i).strip());
          }
        }
      }
    }
    assertTrue(found.size() == 0,
        "Take these from the theme instead:\n" + String.join("\n", found));
  }
}
