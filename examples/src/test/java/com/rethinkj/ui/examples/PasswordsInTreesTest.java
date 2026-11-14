package com.rethinkj.ui.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No stored tree of core, kit or the examples holds a password the tests type or give a password
 * field, hidden or shown: a tree dump sees only the mask.
 */
class PasswordsInTreesTest {

  // The passwords of ConnectionDialog, the kit's gallery and tests, and the docs' examples; the
  // short ones only as a field's quoted string, as they are parts of other words.
  private static final List<String> PASSWORDS = List.of("hunter2", "\"pw\"", "\"secret\"");

  @Test
  void noStoredTreeHoldsAPassword() throws IOException {
    List<String> found = new ArrayList<>();
    int trees = 0;
    for (String module : List.of("core", "kit", "examples")) {
      Path golden = Path.of("..", module, "src", "test", "resources", "golden");
      try (Stream<Path> files = Files.walk(golden)) {
        for (Path file : files.filter(f -> f.toString().endsWith(".tree.txt")).toList()) {
          trees++;
          String tree = Files.readString(file);
          for (String password : PASSWORDS) {
            if (tree.contains(password)) {
              found.add(golden.relativize(file) + " in " + module + ": " + password);
            }
          }
        }
      }
    }
    assertTrue(trees > 100, "found only " + trees + " stored trees");
    assertEquals(List.of(), found);
  }
}
