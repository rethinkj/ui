package com.rethinkj.ui.window;

import static com.rethinkj.ui.El.el;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rethinkj.ui.App;
import com.rethinkj.ui.El;
import com.rethinkj.ui.window.WindowEvent.AppearanceChanged;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a window gives the system and takes from it: its icon, the app's identity, the system's
 * appearance, attention and the taskbar badge. Real windows for a moment; skipped like {@link
 * WindowTest}. What only shows in the taskbar is checked by hand, as AGENTS.md says.
 */
class SystemIntegrationTest {

  private static final String TITLE = "rethinkj system integration test";
  private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

  private static El square(int size) {
    return el().wFull().hFull().radius(size / 5f).fill(0xFFD97706);
  }

  @BeforeEach
  void needsWindows() {
    Desktop.assumeWindows();
  }

  @Test
  void theIconReachesTheWindowInTheSizesWindowsAsksFor() throws Throwable {
    WindowIcon icon = WindowIcon.of(SystemIntegrationTest::square);
    try (Window window = Window.open(TITLE, 300, 200, Window.options().icon(icon))) {
      assertSame(icon, window.icon());
      if (!WINDOWS) {
        // X11 takes it; a Wayland compositor without xdg_toplevel_icon_v1 refuses it.
        window.icon(icon);
        return;
      }
      MemorySegment hwnd = ((WindowHandle.Win32) window.handle()).hwnd();
      int small = Math.round(16 * window.scale());
      int big = Math.round(32 * window.scale());
      assertEquals(small, iconWidth(hwnd, Win32.ICON_SMALL));
      assertEquals(big, iconWidth(hwnd, Win32.ICON_BIG));

      WindowIcon other = WindowIcon.of(size -> el().wFull().hFull().fill(0xFF16A34A));
      long before = Win32.sendMessage(hwnd, Win32.WM_GETICON, Win32.ICON_BIG, 0);
      assertTrue(window.icon(other), "SDL_SetWindowIcon");
      assertNotEquals(before, Win32.sendMessage(hwnd, Win32.WM_GETICON, Win32.ICON_BIG, 0));
      assertEquals(big, iconWidth(hwnd, Win32.ICON_BIG));
    }
  }

  /** The width of the window's icon of this kind, read back through GetIconInfo. */
  private static int iconWidth(MemorySegment hwnd, int kind) throws Throwable {
    Linker linker = Linker.nativeLinker();
    SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
    SymbolLookup gdi32 = SymbolLookup.libraryLookup("gdi32", Arena.global());
    MethodHandle getIconInfo = linker.downcallHandle(user32.find("GetIconInfo").orElseThrow(),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    MethodHandle getObject = linker.downcallHandle(gdi32.find("GetObjectW").orElseThrow(),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    MethodHandle deleteObject = linker.downcallHandle(gdi32.find("DeleteObject").orElseThrow(),
        FunctionDescriptor.of(JAVA_INT, ADDRESS));
    long icon = Win32.sendMessage(hwnd, Win32.WM_GETICON, kind, 0);
    assertNotEquals(0, icon, "the window has an icon of kind " + kind);
    try (Arena arena = Arena.ofConfined()) {
      // ICONINFO: BOOL fIcon, DWORD xHotspot, yHotspot, HBITMAP hbmMask, hbmColor
      MemorySegment info = arena.allocate(32, 8);
      assertNotEquals(0, (int) getIconInfo.invokeExact(MemorySegment.ofAddress(icon), info));
      MemorySegment mask = info.get(ADDRESS, 16);
      MemorySegment colour = info.get(ADDRESS, 24);
      try {
        // BITMAP: LONG bmType, bmWidth, ...
        MemorySegment bitmap = arena.allocate(32, 8);
        assertNotEquals(0, (int) getObject.invokeExact(colour, 32, bitmap));
        return bitmap.get(JAVA_INT, 4);
      } finally {
        int ignored = (int) deleteObject.invokeExact(mask);
        ignored = (int) deleteObject.invokeExact(colour);
      }
    }
  }

  @Test
  void theIdentityReachesSdlAndWindowsBeforeTheFirstWindow() {
    String id = "com.rethinkj.ui.test.SystemIntegration";
    Window.identity(id, "System integration test");
    assertThrows(IllegalArgumentException.class, () -> Window.identity("with space", null));
    try (Window window = Window.open(TITLE, 300, 200)) {
      assertEquals(id, Sdl.getAppMetadataProperty(Sdl.PROP_APP_METADATA_IDENTIFIER_STRING));
      assertEquals("System integration test",
          Sdl.getAppMetadataProperty(Sdl.PROP_APP_METADATA_NAME_STRING));
      if (WINDOWS) {
        assertEquals(id, Win32.currentProcessExplicitAppUserModelId());
      }
      assertThrows(IllegalStateException.class, () -> Window.identity(id, null),
          "the identity is set before the first window");
    }
  }

  @Test
  void theSystemsAppearanceIsReadAndItsChangeReachesEveryWindow() {
    try (Window first = Window.open(TITLE, 300, 200);
        Window second = Window.open(TITLE, 300, 200)) {
      SystemAppearance now = Window.systemAppearance();
      if (WINDOWS) {
        Integer light = Win32.userDword(
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "AppsUseLightTheme");
        SystemAppearance.Scheme expected = light == null || light != 0
            ? SystemAppearance.Scheme.LIGHT : SystemAppearance.Scheme.DARK;
        assertEquals(expected, now.scheme());
        assertEquals(0xFF000000, now.accent() & 0xFF000000, "an opaque accent");
      } else {
        assertEquals(0, now.accent());
        assertFalse(now.highContrast());
      }
      Desktop.settle();
      List<WindowEvent> one = new ArrayList<>();
      List<WindowEvent> two = new ArrayList<>();
      first.listener(one::add);
      second.listener(two::add);
      // As SDL sends it when the system switches between light and dark.
      Desktop.systemEvent(Sdl.EVENT_SYSTEM_THEME_CHANGED);
      assertTrue(Desktop.pollUntil(() -> !one.isEmpty() && !two.isEmpty()), "both windows");
      assertEquals(List.of(new AppearanceChanged(now)),
          one.stream().filter(e -> e instanceof AppearanceChanged).toList());
      assertEquals(List.of(new AppearanceChanged(now)),
          two.stream().filter(e -> e instanceof AppearanceChanged).toList());
    }
  }

  @Test
  void theLoopHandsTheAppearanceToTheAppAtTheStartAndOnEachChange() {
    try (Window window = Window.open(TITLE, 200, 120)) {
      Desktop.settle();
      List<SystemAppearance> seen = new ArrayList<>();
      App<List<SystemAppearance>> app = new App<>(seen, s -> el().wFull().hFull());
      AtomicReference<WindowLoop> loop = new AtomicReference<>();
      boolean[] pushed = {false};
      loop.set(new WindowLoop(window, app, (scene, w, h, s) -> {
        if (seen.size() == 1 && !pushed[0]) {
          pushed[0] = true;
          Desktop.systemEvent(Sdl.EVENT_SYSTEM_THEME_CHANGED); // once the first is in
        } else if (seen.size() == 2) {
          loop.get().stop();
        }
      }).appearance(seen::add));
      Thread.ofPlatform().daemon().start(() -> { // a guard, so that a failure does not hang
        try {
          Thread.sleep(10_000);
        } catch (InterruptedException e) {
          return;
        }
        loop.get().stop();
      });
      loop.get().run();
      SystemAppearance now = Window.systemAppearance();
      assertEquals(List.of(now, now), seen);
    }
  }

  @Test
  void attentionAndTheBadgeAreAskedFor() {
    try (Window window = Window.open(TITLE, 300, 200)) {
      // Windows and X11 flash or mark the window; Wayland may have no activation protocol.
      boolean flashed = window.attention(Window.Attention.BRIEFLY);
      if (WINDOWS) {
        assertTrue(flashed);
        assertTrue(window.attention(Window.Attention.UNTIL_FOCUSED));
      }
      window.attention(Window.Attention.NONE);
      WindowIcon dot = WindowIcon.of(size -> el().wFull().hFull().radius(size / 2f)
          .fill(0xFF16A34A));
      assertThrows(NullPointerException.class, () -> window.badge(dot, null));
      assertEquals(WINDOWS, window.badge(dot, "Connected"));
      assertEquals(WINDOWS, window.badge(null, null));
    }
  }
}
