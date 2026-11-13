package com.rethinkj.ui.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.Key;

import org.junit.jupiter.api.Test;

/** Key names and modifiers from SDL's codes; needs no window. */
class KeysTest {

  @Test
  void altSpaceAloneOpensTheSystemMenu() {
    assertTrue(WindowLoop.opensSystemMenu(new Key("Space", Key.ALT, false)));
    assertFalse(WindowLoop.opensSystemMenu(new Key("Space", Key.ALT, true)), "a repeat");
    assertFalse(WindowLoop.opensSystemMenu(new Key("Space", 0, false)));
    assertFalse(WindowLoop.opensSystemMenu(new Key("Space", Key.ALT | Key.SHIFT, false)));
    assertFalse(WindowLoop.opensSystemMenu(new Key("Enter", Key.ALT, false)));
  }

  @Test
  void keysThatTypeACharacterAreNamedByItInUpperCase() {
    assertEquals("A", Keys.name('a'));
    assertEquals("Z", Keys.name('z'));
    assertEquals("1", Keys.name('1'));
    assertEquals("-", Keys.name('-'));
    assertEquals("Ä", Keys.name(0xE4));
  }

  @Test
  void otherKeysHaveTheNamesOfTheWebsKeyValues() {
    assertEquals("Enter", Keys.name(0x0D));
    assertEquals("Escape", Keys.name(0x1B));
    assertEquals("Backspace", Keys.name(0x08));
    assertEquals("Tab", Keys.name(0x09));
    assertEquals("Space", Keys.name(' '));
    assertEquals("Delete", Keys.name(0x7F));
    assertEquals("F1", Keys.name(0x4000003A));
    assertEquals("F12", Keys.name(0x40000045));
    assertEquals("F13", Keys.name(0x40000068));
    assertEquals("F24", Keys.name(0x40000073));
    assertEquals("ArrowLeft", Keys.name(0x40000050));
    assertEquals("ArrowUp", Keys.name(0x40000052));
    assertEquals("PageDown", Keys.name(0x4000004E));
    assertEquals("Home", Keys.name(0x4000004A));
    assertEquals("Numpad1", Keys.name(0x40000059));
    assertEquals("Numpad9", Keys.name(0x40000061));
    assertEquals("Numpad0", Keys.name(0x40000062));
    assertEquals("Enter", Keys.name(0x40000058), "the numpad's Enter");
    assertEquals("Shift", Keys.name(0x400000E1));
    assertEquals("Shift", Keys.name(0x400000E5));
    assertEquals("Control", Keys.name(0x400000E0));
    assertEquals("Meta", Keys.name(0x400000E3));
  }

  @Test
  void unknownKeysAreUnidentified() {
    assertEquals("Unidentified", Keys.name(0));
    assertEquals("Unidentified", Keys.name(0x01));
    assertEquals("Unidentified", Keys.name(0x40000001));
  }

  @Test
  void modifiersCombineLeftAndRightAndIgnoreLocks() {
    assertEquals(Key.SHIFT, Window.modifiers((short) 0x0001));
    assertEquals(Key.SHIFT, Window.modifiers((short) 0x0002));
    assertEquals(Key.CONTROL, Window.modifiers((short) 0x0080));
    assertEquals(Key.ALT, Window.modifiers((short) 0x0100));
    assertEquals(Key.META, Window.modifiers((short) 0x0800));
    assertEquals(0, Window.modifiers((short) 0x3000), "num lock and caps lock");
    assertEquals(Key.SHIFT | Key.CONTROL | Key.ALT | Key.META,
        Window.modifiers((short) 0x0FC3));
  }
}
