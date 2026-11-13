# rethinkj UI

A native, GPU-rendered UI framework for Java, modelled on [rune](https://github.com/anomalyco/rune) (Rust, MIT). Rune is the reference for scope and concepts: its own renderer, text, layout and animation, plus a headless mode in which an agent can render to PNG, dump the element tree and drive an app with scripted input.

Status: early. `core` runs an app with and without a window: element tree, flexbox layout, springs, clipping and scrolling, pointer presses and the wheel, CPU reference renderer, PNG, scripts that drive an app, and SDL windows that feed an app their input. There is no GPU renderer, text or keyboard handling in apps yet, and `kit` is empty. The native libraries for the window and the renderer, and those for text, are packaged and loaded; `core` has bindings to wgpu-native that no renderer uses yet.

- Repository: https://github.com/rethinkj/ui
- License: MIT

## Targets

- Desktop only: Windows 11 or later and Linux. macOS, web and mobile are not targets.
- Runs on an ordinary JRE. GraalVM native image is not a goal, but it has to stay possible. That means:
  - `core` and `kit` depend on `java.base` only. No `java.desktop`: no AWT, no ImageIO, no `java.awt.Font`.
  - No reflection, dynamic proxies or classes generated at run time, in this code or in anything it depends on at run time.
  - Native code is called through the Foreign Function & Memory API only, not JNI or JNA. Every native function's descriptor is a `static final` constant, so it can be registered for native image.

## Build

Requires JDK 25 and Maven 3.9+.

```sh
mvn verify                # build and test everything
mvn -pl kit -am verify    # one module plus the modules it depends on
```

Image tests compare what `Headless.render` produces with the PNG and tree files under `core/src/test/resources/golden`. After a deliberate change of the output, rewrite them with `mvn -pl core -am test -Dgolden.update=true` (`-am` builds the natives module that core tests with) and look at the images before committing. A failing image test leaves the expected image, the actual image and their difference in `core/target/golden`.

The layout is tested against Taffy's flexbox cases in `core/src/test/resources/taffy`; `scripts/import-taffy-fixtures.sh` refreshes them.

On Windows and Linux, the first build downloads the native libraries (see below) and needs network access; later builds, `mvn clean` included, do not. On Linux it also builds SDL, FreeType and HarfBuzz from source, which takes about a minute and needs cmake, make, a C and a C++ compiler, pkg-config, tar with xz, and the development packages listed in `natives-linux-x64/natives.properties`. On Windows it builds FreeType and HarfBuzz from source, which takes one to three minutes and needs Visual Studio's C++ tools (see "Native libraries"). `mvn verify -P '!natives-<platform>'` builds without them and skips the tests that load them.

## Driving an app with a script

`com.rethinkj.ui.Script` drives an app without a window: a script of steps goes in, images and trees come out. An app's `main` passes its arguments to `Script.handle(args, app)`; with `--script <file> --out <folder>` that runs the script, prints the files it wrote and returns true, and without them it returns false and `main` opens the window. `Script.run(text, app, folder)` does the same from Java. On a mistake it throws `Script.Failure`, which names the line and says what to write instead; uncaught, the program exits with status 1.

One step per line. A `#` at the start of a line or before a space starts a comment.

| Step | What it does |
| --- | --- |
| `size <width> <height> [scale <factor>]` | Sets the window size in logical pixels and the physical pixels per logical pixel (default 1). Needed before the first `frame`. |
| `press <target>` | Pointer down and up on the target. |
| `down <target>`, `up <target>` | The two halves, for a press that ends elsewhere. A press counts only when both land on the same element. |
| `wheel <target> <deltaX> <deltaY>` | Turns the wheel with the pointer on the target, by these distances in logical pixels; positive scrolls down or right. Along each axis the innermost scroll area under the pointer that can still move that way takes the whole distance, up to its end. |
| `key <name> [shift] [control] [alt] [meta]` | Presses and releases a key with these modifiers held, as in `key Tab shift`. Keys have the web's key names (`Enter`, `Tab`, `Space`, `Escape`, `ArrowDown`, `F5`), and a key that types a character is named by it in upper case (`A`, `1`); the Javadoc of `com.rethinkj.ui.Key` lists them all. |
| `type "<text>"` | Text input for the focused element (`\"` and `\\` inside). |
| `focus <target>` | Gives the keyboard focus to the target, or the nearest focusable element around it, as Tab would. |
| `advance <seconds>` | Moves the app's clock. |
| `frame` | Runs one frame: the handlers and scrolls that input queued, a rebuild of the view, the animations at the current time. |
| `snap <name>` | Writes `<name>.png` and `<name>.tree.txt` of the last frame into the output folder. `<name>` may contain `/` but must stay inside the folder. |

Only `frame` runs a frame, as in a window, where input arrives between frames: `press`, `key`, `type` and `focus` queue what they do, `wheel` a scroll and `advance` moves the clock, but the screen changes at the next `frame`. `snap` refuses to write a frame that does not show the steps before it.

A `.tree.txt` has one line per element, children indented, with its rectangle in window coordinates as shown, that is, moved by the scroll offsets above it. A scroll area that is scrolled adds its offset after the rectangle, as in `el #list role=list 16,16 208x168 scroll 0,90`. A line that ends in `exiting` is an element with a key and motion that a rebuild left out: it fades out where it was, takes no input, and is removed once invisible. Selectors do not match it, nor anything at opacity 0. The line of the element that has the keyboard focus ends in `focused`.

Keys go to the focused element and then up through its ancestors until a key handler takes them; without focus only the root gets them. If none does, Tab and Shift+Tab move the focus through the focusable elements in tree order, around at the ends, and Enter and Space press the focused element. A press focuses the focusable element it lands in and clears the focus elsewhere. Focus moved by the keyboard or by `focus` is drawn with a ring and scrolls into view; a press shows no ring.

A target is a position `x,y` in logical pixels, or a selector for exactly one element of the last frame: `#key` (anywhere in the tree), `role=<role>`, `label="<text>"` (`\"` and `\\` inside), `text="<string>"` (see Text), or several of them, as in `role=switch label="Lights"`. A selector presses the centre of the element's rectangle. When it matches nothing, the error lists the keys, roles and labels there are, and the texts if there are any; when it matches several, it lists them with their rectangles.

`core/src/test/resources/scripts/switch.txt` drives the switch of core's tests:

```text
# The light switch of core's tests (Examples): off, pressed, moving, settled.
size 200 120
frame
snap switch-off

size 200 120 scale 2
press role=switch label="Lights"   # queues the switch's handler
frame                              # runs it; the knob starts to move
advance 0.04
frame
snap switch-moving-040ms

advance 1
frame
snap switch-on
```

Run it, and look at the three images and trees in `core/target/switch`:

```sh
mvn -q -pl core -am test-compile
java -cp core/target/classes:core/target/test-classes com.rethinkj.ui.Examples \
  --script core/src/test/resources/scripts/switch.txt --out core/target/switch
```

`core/src/test/resources/scripts/list.txt` scrolls the list of core's tests, which `Examples` runs when `list` comes first:

```text
# The list of core's tests (Examples): twenty rows in a rounded scroll area.
size 240 200 scale 2
frame
snap list-top

wheel #list 0 90       # the wheel over the middle of the list, 90 pixels down
frame                  # moves the rows; rows cut by the edge are drawn in part
snap list-scrolled

wheel #list 0 10000    # far past the end: stops at the last row
frame
snap list-end
```

```sh
java -cp core/target/classes:core/target/test-classes com.rethinkj.ui.Examples list \
  --script core/src/test/resources/scripts/list.txt --out core/target/list
```

`core/src/test/resources/scripts/focus.txt` reaches the switch with the keyboard:

```text
# The light switch of core's tests (Examples), reached with Tab and toggled with Space.
size 200 120 scale 2
frame
key Tab                # the switch takes the focus; the keyboard moved it, so it has a ring
frame
snap switch-focused

key Space              # presses the focused switch
frame
advance 1
frame
snap switch-focused-on
```

In `switch-focused.tree.txt` the switch's line is `el #switch role=switch label="Lights" 81,49 38x22 focused`.

```sh
java -cp core/target/classes:core/target/test-classes com.rethinkj.ui.Examples \
  --script core/src/test/resources/scripts/focus.txt --out core/target/focus
```

On Windows, separate the class path with `;`. The snaps equal the stored images of the same names under `core/src/test/resources/golden`; `ScriptTest` checks that, and that these examples match their files.

## Examples

`examples/` holds small apps that open a window, written as a user of the library writes them. The module is built and tested with the others but never installed or deployed. From the repository root, on Linux and Windows, this builds what it needs and starts the light switch, `com.rethinkj.ui.examples.LightSwitch`:

```sh
mvn -q -pl examples -am compile exec:exec
```

It is the switch of rune's README in a frameless window with a title bar of its own: drag the bar to move the window, use its buttons, press the switch, or Tab to it and press Space; the lamp above it shows the state. The example runs in a JVM of its own, with the JDK that runs Maven and `--enable-native-access=ALL-UNNAMED`. With `-Dargs="--script <file> --out <folder>"` it runs a script instead of opening the window, with paths relative to the folder Maven was started in. `LightSwitchTest` passes `examples/src/test/resources/scripts/light-switch.txt` to the example's `main` and compares the snaps with `examples/src/test/resources/golden`; `mvn -pl examples -am test -Dgolden.update=true` rewrites them, and core's stored images too.

## Modules

| Directory              | Artifact                          | Base package          | Purpose                                                   |
| ---------------------- | --------------------------------- | --------------------- | --------------------------------------------------------- |
| `core/`                | `rethinkj-ui-core`                | `com.rethinkj.ui`     | The framework itself                                      |
| `kit/`                 | `rethinkj-ui-kit`                 | `com.rethinkj.ui.kit` | Components built on `core`                                |
| `natives-windows-x64/` | `rethinkj-ui-natives-windows-x64` | none, no Java code    | SDL3, wgpu-native, FreeType and HarfBuzz for Windows on x64 |
| `natives-linux-x64/`   | `rethinkj-ui-natives-linux-x64`   | none, no Java code    | SDL3, wgpu-native, FreeType and HarfBuzz for Linux on x64   |
| `fonts/`               | `rethinkj-ui-fonts`               | none, no Java code    | Inter and JetBrains Mono, the default fonts               |
| `examples/`            | `rethinkj-ui-examples`            | `com.rethinkj.ui.examples` | Apps that open a window; not published               |

All artifacts share the groupId `com.rethinkj.ui`.

## Native libraries

The window (SDL3), the GPU renderer (wgpu-native) and text (HarfBuzz shapes, FreeType rasterises) will call native libraries. They reach users in one JAR per platform, `rethinkj-ui-natives-<platform>`, where the platform is `windows-x64` or `linux-x64`. Both hold SDL 3.4.20, wgpu-native 29.0.1.1, FreeType 2.15.0 and HarfBuzz 15.1.0. FreeType and HarfBuzz are built from the same source archives with the same options on both platforms, without dependencies beyond the C runtime: on Linux HarfBuzz needs no libstdc++, and on Windows both link the C runtime statically and need only `KERNEL32.dll`.

- The JAR has no Java code. Under `META-INF/rethinkj-ui/natives/<platform>/` it holds the libraries, `manifest.txt` and the licence texts in `licenses/`. `manifest.txt` lists the libraries in load order, one line each: `library <name> <version> <file> <sha256>`, after the lines `rethinkj-ui-natives 1` (the format) and `platform <platform>`. A library built from source is followed by `built <name> <sha256 of the source archive>`, because its own hash differs between machines. `com.rethinkj.ui.natives.Manifest` reads it, ignores `built` and documents the format.
- No binaries are committed. `natives-<platform>/natives.properties` pins each library: version, archive URL, the archive's SHA-256 and the file inside it. `scripts/FetchNatives.java` (run with `java scripts/FetchNatives.java natives-windows-x64`; it needs only the JDK) downloads the archives into `natives-<platform>/fetched/archives/`, checks their hashes, and unpacks the libraries and writes `manifest.txt` into `natives-<platform>/fetched/resources/`, which the module packages. The module's build runs it in `generate-resources`; it does nothing while `fetched/` matches `natives.properties`. `fetched/` is ignored by git and survives `mvn clean`; delete it to fetch again. Offline, put the archive at the path the error message names.
- A library with a `<name>.cmake` line (on Linux every library but wgpu-native, on Windows FreeType and HarfBuzz) comes from a source archive, a `.zip` or a `.tar.xz`: the tool builds it with cmake in `fetched/builds/<name>/`, on Linux with make, and reuses that build while archive, options and entry stay the same. It fails if cmake turns off an option the line sets `ON`, such as `SDL_X11` or `SDL_WAYLAND` when their development files are missing.
- On Windows the tool builds with Visual Studio 2022 or later, or its Build Tools, and needs nothing on the PATH. It takes the installation of the developer prompt it runs in (`VSINSTALLDIR`), else the newest one with the C++ x64 tools that `vswhere.exe` finds (one that also has Visual Studio's CMake first), and runs every command in the environment that its `VsDevCmd.bat -arch=amd64` sets up. cmake is the file the environment variable `CMAKE` names, else the first `cmake.exe` on that environment's PATH, where Visual Studio's own (the component "C++ CMake tools for Windows") comes before the user's PATH. The generator is the Ninja that the same component brings, else NMake: FreeType and HarfBuzz take about 70 s with Ninja and about three minutes with NMake. Windows' `tar.exe` reads `.tar.xz` but cannot make a symbolic link without a privilege, so the tool has it rewrite the archive as a zip and unpacks that; a link becomes a file holding its target, as Git makes links where it cannot. The libraries link the C runtime statically (`CMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded`), because `VCRUNTIME140.dll` comes with the Visual C++ Redistributable, not with Windows.
- A fresh Windows machine needs Visual Studio or its Build Tools with the workload "Desktop development with C++", whose defaults include the x64 tools, a Windows SDK and "C++ CMake tools for Windows"; nothing else needs installing or setting. Where cmake is missing, the build stops with a message that says what to do; it does not download CMake itself. The fix is needed once, as later builds reuse `fetched/builds/`, and the build cannot get Visual Studio by itself either. Without installing anything, unpack the Windows x64 ZIP from cmake.org anywhere and build once with `$env:CMAKE = '<folder>\bin\cmake.exe'`. An agent working unattended does that, or builds with `-P '!natives-windows-x64'` when it needs no native libraries.
- A Linux library is packaged under its SONAME (`libSDL3.so.0`), so that a library that needs it finds the loaded copy; the tool checks that. HarfBuzz's CMake build gives it the SONAME `libharfbuzz.so`, without the `.0` of its Meson build.
- To change a version, edit `natives.properties`, build, and update the licence texts under `natives-<platform>/src/main/resources` if the release changed them.
- A natives module is built only on its own platform: the root `pom.xml` lists it in a profile of the same name, activated by the operating system and architecture, and `core` has a profile with that id that adds it as a test dependency.
- `com.rethinkj.ui.natives.Natives.lookup("sdl3")` returns a `SymbolLookup`, loading the library and every library listed before it on first use. The package is internal to `core`. It finds the manifest of the current platform on the class path and unpacks the libraries into a per-user cache, `%LOCALAPPDATA%\rethinkj-ui\natives\` on Windows and `$XDG_CACHE_HOME/rethinkj-ui/natives/` (default `~/.cache`) on Linux, in a subfolder named after their hashes. Two system properties change that:
  - `rethinkj.ui.natives.dir`: load from this folder, which holds the libraries and their `manifest.txt` as the JAR has them. Nothing is unpacked or hash-checked, and the class path is not read. For packaged apps and GraalVM native image.
  - `rethinkj.ui.natives.cache`: unpack into this folder instead. Core's tests set it to `core/target/natives-cache`.
- Native access must be enabled for core: `--enable-native-access=com.rethinkj.ui` (core's tests pass it). On the module path, the natives JAR is resolved only with `--add-modules com.rethinkj.ui.natives.<os>.x64`.
- Loading happens only on first use. Never call `Natives` from a static initialiser that the headless path can reach: rendering without a window must not need the libraries.
- Linux cannot load a library from a file system mounted `noexec`; then the cache has to move. On Linux, a failed load reports the dynamic linker's reason, which the JDK leaves out.
- The libraries built for Linux need the glibc of the machine that built them (2.38 on Ubuntu 24.04, for SDL and FreeType), and SDL lacks what that machine had no headers for (IBus, libdecor). A build on an older distribution does not exist yet.

## GPU bindings

`com.rethinkj.ui.gpu` (internal to `core`) binds wgpu-native in two layers. `Wgpu` and `Structs` are the raw layer: constants, function and callback descriptors, struct layouts written by hand and the header's `*_INIT` defaults (zero is not always the default). Everything else is the safe layer, which the renderer uses; renderer code never sees a handle or a `MemorySegment` of wgpu.

wgpu-native ends the process where WebGPU would report an error, so the safe layer has to keep these from happening:

- Every call that can fail runs inside a validation and an out-of-memory error scope and throws `GpuException` with wgpu's text and the label. A command encoder keeps its scopes open until `finish`, which throws instead of handing out a command buffer that recorded an error. Push and pop scopes only through `Device`: popping one too many ends the process, and so does the `Internal` filter.
- Every device gets the uncaptured-error and device-lost callbacks. Upcalls catch everything and hand it to the calling code. The stubs live in `Callbacks` for the process and find their target by an id in `userdata1`.
- Check arguments that wgpu-native panics on before calling: sizes of 0, a second surface texture while one is held, a buffer still mapped when commands using it are submitted, a command buffer submitted twice, NULL handles.
- Do not bind any of the 35 functions wgpu-native leaves unimplemented (`WgpuTest` lists them), and do not rely on `wgpuInstanceWaitAny`: callbacks run inside the call or inside `wgpuDevicePoll`.

Objects belong to the thread that created their `Instance`; closing one closes what it created, newest first. After adding or changing a struct, function or callback, run `scripts/LayoutCheck.java` (its header has the commands; it needs jextract, which stays out of the repository).

## Text

Text is shaped but not drawn yet: a `Scene` has no instances for text elements.

- `El.text("…")` makes a text element, a leaf that layout sizes to its shaped text. Chained calls: `.font(Font)`, `.fontSize(<logical pixels>)`, `.color(0xAARRGGBB)`, `.wrapText()`. The defaults are `Font.sans()`, 14, opaque black and no wrapping. Each line feed starts a line; with `wrapText` it also breaks where `java.text.BreakIterator` allows, and a word wider than the box stays whole. No bidirectional text, mixed scripts or fallback fonts yet: a character the font lacks shows as its glyph 0.
- `Font.sans()`, `sansBold()`, `mono()` and `monoBold()` are Inter 4.1 and JetBrains Mono 2.304, regular and bold, the static hinted TrueType files of their releases. They come in `fonts/` (`rethinkj-ui-fonts`, under `META-INF/rethinkj-ui/fonts/` with their SIL Open Font License 1.1 texts in `licenses/`); `core` uses that module only in tests, and without it these methods throw a message that names it. Other fonts: `Font.of(bytes)` or `Font.resource(owner, name)`, freed by `close()`.
- `Font.shape(text, size, scale, features...)` shapes with HarfBuzz into a `GlyphRun` in physical pixels: at `size * scale`, advances and offsets rounded to whole pixels below scale 1.5 and to quarter pixels from 1.5 on. Kerning is on and `calt` off (JetBrains Mono's ligatures); features use HarfBuzz's syntax, such as `"+calt"` or `"-kern"`. Each font keeps the last 1024 runs.
- `App` and `Headless` pass the window's scale to every text element before layout (`El.scaleText`); `Flex.layout` alone shapes at scale 1. After layout, `el.text().lines()` holds what a renderer draws: per line a `GlyphRun`, its width and its baseline, in physical pixels from the top left of the content box. Layout hands the final content size to `Measure.placed`, and the lines are broken for that width.
- In a `.tree.txt`, a text element's line starts with `text` instead of `el` and has its string in quotes after the key, role and label: `text #save "Save\nall" 0,0 32x36`. Strings and labels are quoted with `\"`, `\\` and `\n` for a line feed. The selector `text="<string>"` matches the exact string, written the same way.
- The bindings are in `com.rethinkj.ui.text.ffi`, not exported, in the style of `com.rethinkj.ui.gpu`. FreeType's C `long` (64 bits on Linux, 32 on Windows) is handled only in `FreeType`: `C_LONG`, its method handles, which take a Java `long` for it, and `getLong`/`setLong`. Tests that need the libraries skip themselves where the natives module lacks them, as on Windows for now.

## GPU renderer

- `render.GpuRenderer` draws a `Scene` with wgpu. Its WGSL is the constant `GpuRenderer.WGSL`, and every line of the fragment shader has a counterpart in `CpuRenderer`: change both together. The instance block and the clip table go unchanged into two read-only storage buffers, and one instanced draw call, four vertices per instance as a triangle strip, draws all instances in order. The buffers double when a scene needs more and are kept for the next frame.
- It draws only into sRGB targets, with premultiplied blending (`one, one-minus-src-alpha`), so that the GPU blends in linear light and the target encodes: `RGBA8_UNORM_SRGB` for images, `BGRA8_UNORM_SRGB` (or `RGBA8_UNORM_SRGB`) for windows. `GpuRenderer.create()` and `render(scene, width, height, scale)` draw into `Pixels` without a window, like `CpuRenderer.render`.
- `render.GpuPresenter` is the `WindowLoop.Presenter` that owns a window's surface (Direct3D 12 on Windows, Vulkan on Linux, FIFO, opaque) and configures it with the window's size in physical pixels. It draws nothing while the window is minimized. When the surface is out of date, lost or times out, it reports `pending()`, and the loop offers the newest frame again a refresh later; the presenter then reads the window's size again. Close it before the window.
- `GpuRendererTest` compares the GPU's images with the CPU's: the views behind the stored images, and scenes that stress one formula each, at scales 1, 1.5 and 2. A channel may differ by 1 step of 8 bits, and by half a step more for each further instance that draws into the pixel, because the GPU rounds into the 8-bit target after every draw (`tolerance` says why). A failing scene leaves `.cpu.png`, `.gpu.png` and `.diff.png` in `core/target/gpu-vs-cpu`. The test is skipped without the natives module or an adapter.

## Windows

- `com.rethinkj.ui.window` opens windows through SDL 3 (`Window`), turns SDL's events into `WindowEvent`s and runs an `App` in a window (`WindowLoop`), which hands each new frame to a `Presenter`. `Sdl` and `Win32` hold the handwritten bindings: a new function gets its descriptor as a constant there and its handle in the class's lazy holder.
- Windows are used on the thread that opened the first one, until all are closed. Every event is translated in an SDL event watch as SDL queues it and delivered in that order by `Window.pollEvents` or `waitEvents`; SDL's own queue only ends the wait. On Windows the listener is also called from inside the system's move and resize loop, with `Redraw(live = true)`.
- The hit test, the event watch and the Windows subclass are upcalls: each catches every `Throwable` and hands it to `Window.fail`, and the next `pollEvents` or `waitEvents` throws it. One that escapes ends the JVM.
- A frameless window draws its own title bar: the view marks elements with `El.windowPart`, and `App.windowPart` answers SDL's hit test. The Windows 11 parts (rounded corners, snap layouts on the maximize part, system menu on a right click) are in `Win32Frame`, whose Javadoc also says why the resize margin lies inside the window. `Win32Frame` also gives a minimized frameless window no client area, as Windows does for a framed one; otherwise SDL reports the 160 by 28 pixels of a minimized window when the system restores it.
- Tests that open windows skip themselves without the natives module or a display. The demo in the test sources draws with `GpuPresenter`: build with `mvn -pl core -am test-compile`, then from the repository root `java --enable-native-access=ALL-UNNAMED -cp "core/target/classes;core/target/test-classes;natives-windows-x64/target/classes" com.rethinkj.ui.window.WindowDemo` (on Linux, with `:` between the entries and `natives-linux-x64`). It prints every frame it presents; `--cpu-png=<file>` writes the CPU's image of the last frame when it stops, to compare with a screenshot of the window. `--exit-after=<seconds>` and its other options are in its Javadoc; keep windows short-lived when you drive them with synthetic input. SDL drops the click that activates a window, so click once on the background first.

## Conventions

- A new module gets the directory `<name>/`, the artifactId `rethinkj-ui-<name>` and the base package `com.rethinkj.ui.<name>`, and is listed under `<modules>` in the root `pom.xml`; a natives module, in the profile of its platform.
- Shared build configuration (Java release, encoding, plugin and dependency versions) belongs in the root `pom.xml`. Module POMs declare only what is specific to them.
- Dependencies point one way: `kit` depends on `core`, never the reverse.
- The version is repeated in every module's `<parent>` block. Change it with `mvn versions:set -DnewVersion=<version>`, not by editing one POM.

## Changes

- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `<type>(<scope>): <summary>`, for example `feat(core): add text shaping`. The scope is the module when the change stays in one. A breaking change gets `!` before the colon.
- Keep commit messages short: a subject line of at most 72 characters, and a body only when the diff doesn't show why. No lists of changed files and no test or verification reports.
- For a change that users of the library can notice (new or changed API, changed behaviour, a fix, a raised requirement), add `changelog/unreleased/<YYYY-MM-DD>-<slug>.md` with one top-level bullet written for those users. These files are folded into `CHANGELOG.md` at release.
- Don't add files that describe your own work: no `VALIDATION.md`, `SUMMARY.md`, plans, reports or review notes. What changed and why goes in the commit message; anything longer goes in the pull request description.

## This repo is public

Everything pushed stays public in history. Before committing, run `git diff --cached | grep -nE '/home/|/Users/|/private/var/|\.ts\.net|ses_[A-Za-z0-9]{20}'` and keep these out:

- secrets, tokens and credential file contents;
- home folder and temp paths, machine names and tailnet hostnames. Write repo-relative paths, and use `host.example.ts.net` in tests;
- session IDs, chat or Slack links, and quotes from private sessions, chats or calls;
- issue numbers, links and suggested branch names from a private issue tracker;
- teammates' names attached to their plans, opinions or internal discussions, and private repos or branches;
- quotes of, or citations to, internal guides and rules. Say what the code does and why on its own terms;
- agent handoff notes (`REVIEW-TO-PARENT.md` and similar), raw evidence, logs and benchmark dumps;
- generated videos and strips nothing links to. Commit the stills a doc shows.
