package com.rethinkj.ui.window;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.rethinkj.ui.FileDialog;
import com.rethinkj.ui.natives.Natives;
import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The file dialogs' callback, called through its upcall stub as SDL calls it, on this thread and
 * on another, with the lists SDL hands over; and what the dialogs give SDL. No dialog opens.
 */
class SystemDialogsTest {

  /** The callback as native code calls it: a downcall to the stub's address. */
  private static final MethodHandle CALL = Linker.nativeLinker().downcallHandle(
      SystemDialogs.callback(), Sdl.DIALOG_FILE_CALLBACK);

  private record Answer(FileDialog.Choice choice, String error, String thread) {}

  /** Calls the callback with these strings as SDL's list, or with NULL for {@code null}. */
  private static Answer call(List<String> files, int filter, boolean otherThread)
      throws Exception {
    AtomicReference<Answer> answer = new AtomicReference<>();
    long id = SystemDialogs.expect(
        c -> answer.set(new Answer(c, null, Thread.currentThread().getName())),
        e -> answer.set(new Answer(null, e, Thread.currentThread().getName())));
    Runnable call = () -> {
      try (Arena arena = Arena.ofConfined()) {
        MemorySegment list = MemorySegment.NULL;
        if (files != null) {
          list = arena.allocate(ADDRESS, files.size() + 1);
          for (int i = 0; i < files.size(); i++) {
            list.setAtIndex(ADDRESS, i, arena.allocateFrom(files.get(i))); // UTF-8
          }
          list.setAtIndex(ADDRESS, files.size(), MemorySegment.NULL);
        }
        CALL.invokeExact(MemorySegment.ofAddress(id), list, filter);
      } catch (Throwable t) {
        throw new AssertionError(t);
      }
    };
    if (otherThread) {
      Thread thread = Thread.ofPlatform().name("SDL_ZenityFileDialog").start(call);
      thread.join();
    } else {
      call.run();
    }
    return answer.get();
  }

  @Test
  void theCallbackCopiesPathsInUtf8OnTheThreadSdlCallsItOn() throws Exception {
    String deep = String.join(File.separator, java.util.Collections.nCopies(120, "Verzeichnis"))
        + File.separator + "Größe & Ölpreis (Entwurf) 2026.sql";
    assertTrue(deep.length() > 1400, "longer than Windows' MAX_PATH and most file names");
    List<String> files = List.of("/srv/ä ö ü/Abfrage 1.sql", "C:\\Daten\\日本語 テスト.md", deep);
    Answer answer = call(files, 1, true);
    assertNull(answer.error());
    assertEquals(files.stream().map(Path::of).toList(), answer.choice().paths());
    assertEquals(1, answer.choice().filter());
    assertEquals("SDL_ZenityFileDialog", answer.thread(), "handed on where SDL called it");

    Answer here = call(List.of("/tmp/a b"), -1, false);
    assertEquals(List.of(Path.of("/tmp/a b")), here.choice().paths());
    assertEquals(-1, here.choice().filter());
  }

  /** {@code DWORD GetShortPathNameW(LPCWSTR, LPWSTR, DWORD)}, the other way round, for the test. */
  private static String shortPathName(String path) throws Throwable {
    MethodHandle h = Linker.nativeLinker().downcallHandle(
        SymbolLookup.libraryLookup("kernel32", Arena.global()).find("GetShortPathNameW")
            .orElseThrow(),
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment to = arena.allocate(2 * 4096, 2);
      int n = (int) h.invokeExact(arena.allocateFrom("\\\\?\\" + path, StandardCharsets.UTF_16LE),
          to, 4096);
      String out = n == 0 ? path : to.getString(0, StandardCharsets.UTF_16LE);
      return out.startsWith("\\\\?\\") ? out.substring(4) : out;
    }
  }

  @Test
  void aPathLongerThanMaxPathComesWithItsLongNames(@TempDir Path temp) throws Throwable {
    assumeTrue(File.separatorChar == '\\', "8.3 names are Windows'");
    Path folder = temp;
    for (int i = 0; i < 4; i++) {
      folder = folder.resolve("ein sehr langer Ordner mit Ümlauten und Leerzeichen 0123456789 " + i);
    }
    Files.createDirectories(folder);
    Path file = Files.writeString(folder.resolve("lange Datei.txt"), "x");
    assertTrue(file.toString().length() > 260, file.toString().length() + " characters");
    String shortName = shortPathName(file.toString());
    assumeTrue(shortName.contains("~"), "the volume makes no 8.3 names");
    String shortFolder = shortPathName(folder.toString());

    // As SDL hands over what the dialog answered: the short form of a long path.
    Answer answer = call(List.of(shortName, shortFolder + File.separator + "neue Datei.sql"), 0,
        true);
    assertEquals(List.of(file, folder.resolve("neue Datei.sql")), answer.choice().paths(),
        "a file that is not there yet keeps its name after its folder's long form");
  }

  @Test
  void anEmptyListAndZenitysEmptyLineAreACancel() throws Exception {
    assertTrue(call(List.of(), -1, false).choice().cancelled());
    assertTrue(call(List.of(""), -1, true).choice().cancelled(), "zenity's cancel");
  }

  @Test
  void aNullListIsAFailureWithSdlsText() throws Exception {
    try {
      Natives.lookup("sdl3");
    } catch (UnsatisfiedLinkError e) {
      assumeTrue(false, "Skipped: SDL 3 cannot be loaded. " + e.getMessage());
    }
    Answer answer = call(null, -1, false);
    assertNull(answer.choice());
    assertTrue(answer.error() != null && !answer.error().isEmpty(), String.valueOf(answer));
  }

  @Test
  void aHandlerThatThrowsBecomesAFailureInsteadOfEndingTheJvm() throws Exception {
    List<String> failures = new ArrayList<>();
    long id = SystemDialogs.expect(c -> {
      throw new IllegalStateException("broken");
    }, failures::add);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment list = arena.allocate(ADDRESS, 2);
      list.setAtIndex(ADDRESS, 0, arena.allocateFrom("a.sql"));
      list.setAtIndex(ADDRESS, 1, MemorySegment.NULL);
      try {
        CALL.invokeExact(MemorySegment.ofAddress(id), list, 0);
      } catch (Throwable t) {
        throw new AssertionError(t);
      }
    }
    assertEquals(List.of("The file dialog's answer could not be read:"
        + " java.lang.IllegalStateException: broken"), failures);
    assertTrue(!SystemDialogs.waiting(), "nothing is left waiting");
  }

  @Test
  void aDialogOfAWindowThatClosedFailsWithoutAskingSdl() {
    Desktop.assumeWindows();
    Window window = Window.open("rethinkj closed dialog test", 80, 60);
    window.close();
    List<String> failures = new ArrayList<>();
    new SystemDialogs(window).file(FileDialog.open("x"), c -> failures.add("chosen " + c),
        failures::add);
    assertEquals(List.of("The window of this dialog has closed."), failures);
    assertTrue(!SystemDialogs.waiting());
  }

  @Test
  void theDialogStartsInItsFolderOrAtItsSuggestedFile() {
    Path folder = Path.of("exports").toAbsolutePath();
    assertNull(SystemDialogs.location(FileDialog.open("x")));
    assertEquals(folder + File.separator, SystemDialogs.location(FileDialog.open("x").in(folder)),
        "a separator after it, or Windows takes the folder for a file's name");
    assertEquals(folder.resolve("orders.csv").toString(),
        SystemDialogs.location(FileDialog.save("x").in(folder).fileName("orders.csv")));
    assertEquals("orders.csv", SystemDialogs.location(FileDialog.save("x").fileName("orders.csv")));
  }

  @Test
  void withoutPortalOrZenityTheReasonSaysWhatToInstall() {
    String reason = SystemDialogs.reason("File dialog driver unsupported (supported values for"
        + " SDL_HINT_FILE_DIALOG_DRIVER are 'zenity' and 'portal')");
    assertTrue(reason.contains("xdg-desktop-portal") && reason.contains("zenity"), reason);
    assertEquals("Failed to connect to DBus", SystemDialogs.reason("Failed to connect to DBus"));
  }

  @Test
  void aFailureAtTheStartIsToldWithItsCauses() {
    Throwable failure = new IllegalStateException("No GPU adapter",
        new UnsatisfiedLinkError("libvulkan.so.1: cannot open shared object file"));
    assertEquals("""
        Documents stopped because of an error.

        IllegalStateException: No GPU adapter
        UnsatisfiedLinkError: libvulkan.so.1: cannot open shared object file""",
        SystemDialogs.failureText("Documents", failure));
  }
}
