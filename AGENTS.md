# rethinkj UI

A native, GPU-rendered UI framework for Java, modelled on [rune](https://github.com/anomalyco/rune) (Rust, MIT). Rune is the reference for scope and concepts: its own renderer, text, layout and animation, plus a headless mode in which an agent can render to PNG, dump the element tree and drive an app with scripted input.

Status: early. `core` runs an app without a window: element tree, flexbox layout, springs, clipping and scrolling, pointer presses and the wheel, CPU reference renderer, PNG, and scripts that drive an app. There is no window, GPU renderer, text or keyboard yet, and `kit` is empty. The native libraries for the window and the renderer are packaged and loaded, but nothing calls them yet.

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

On Windows and Linux, the first build downloads the native libraries (see below) and needs network access; later builds, `mvn clean` included, do not. On Linux it also builds SDL from source, which takes about half a minute and needs cmake, make, a C compiler, pkg-config and the development packages listed in `natives-linux-x64/natives.properties`. `mvn verify -P '!natives-<platform>'` builds without them and skips the tests that load them.

## Driving an app with a script

`com.rethinkj.ui.Script` drives an app without a window: a script of steps goes in, images and trees come out. An app's `main` passes its arguments to `Script.handle(args, app)`; with `--script <file> --out <folder>` that runs the script, prints the files it wrote and returns true, and without them it returns false and `main` opens the window. `Script.run(text, app, folder)` does the same from Java. On a mistake it throws `Script.Failure`, which names the line and says what to write instead; uncaught, the program exits with status 1.

One step per line. A `#` at the start of a line or before a space starts a comment.

| Step | What it does |
| --- | --- |
| `size <width> <height> [scale <factor>]` | Sets the window size in logical pixels and the physical pixels per logical pixel (default 1). Needed before the first `frame`. |
| `press <target>` | Pointer down and up on the target. |
| `down <target>`, `up <target>` | The two halves, for a press that ends elsewhere. A press counts only when both land on the same element. |
| `wheel <target> <deltaX> <deltaY>` | Turns the wheel with the pointer on the target, by these distances in logical pixels; positive scrolls down or right. Along each axis the innermost scroll area under the pointer that can still move that way takes the whole distance, up to its end. |
| `advance <seconds>` | Moves the app's clock. |
| `frame` | Runs one frame: the handlers and scrolls that input queued, a rebuild of the view, the animations at the current time. |
| `snap <name>` | Writes `<name>.png` and `<name>.tree.txt` of the last frame into the output folder. `<name>` may contain `/` but must stay inside the folder. |

Only `frame` runs a frame, as in a window, where input arrives between frames: `press` queues a handler, `wheel` a scroll and `advance` moves the clock, but the screen changes at the next `frame`. `snap` refuses to write a frame that does not show the steps before it.

A `.tree.txt` has one line per element, children indented, with its rectangle in window coordinates as shown, that is, moved by the scroll offsets above it. A scroll area that is scrolled adds its offset after the rectangle, as in `el #list role=list 16,16 208x168 scroll 0,90`. A line that ends in `exiting` is an element with a key and motion that a rebuild left out: it fades out where it was, takes no input, and is removed once invisible. Selectors do not match it, nor anything at opacity 0.

A target is a position `x,y` in logical pixels, or a selector for exactly one element of the last frame: `#key` (anywhere in the tree), `role=<role>`, `label="<text>"` (`\"` and `\\` inside), or several of them, as in `role=switch label="Lights"`. A selector presses the centre of the part of the element's rectangle that its clips leave visible, and does not match an element they clip away entirely, such as a row scrolled out of its list. When it matches nothing, the error lists the keys, roles and labels there are; when it matches several, it lists them with their rectangles.

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
mvn -q -pl core test-compile
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

On Windows, separate the class path with `;`. The snaps equal the stored images of the same names under `core/src/test/resources/golden`; `ScriptTest` checks that, and that these examples match their files.

## Modules

| Directory              | Artifact                          | Base package          | Purpose                                     |
| ---------------------- | --------------------------------- | --------------------- | ------------------------------------------- |
| `core/`                | `rethinkj-ui-core`                | `com.rethinkj.ui`     | The framework itself                        |
| `kit/`                 | `rethinkj-ui-kit`                 | `com.rethinkj.ui.kit` | Components built on `core`                  |
| `natives-windows-x64/` | `rethinkj-ui-natives-windows-x64` | none, no Java code    | SDL3 and wgpu-native for Windows on x64     |
| `natives-linux-x64/`   | `rethinkj-ui-natives-linux-x64`   | none, no Java code    | SDL3 and wgpu-native for Linux on x64       |

All artifacts share the groupId `com.rethinkj.ui`.

## Native libraries

The window (SDL3) and the GPU renderer (wgpu-native) will call native libraries. They reach users in one JAR per platform, `rethinkj-ui-natives-<platform>`, where the platform is `windows-x64` or `linux-x64`. Both hold SDL 3.4.20 and wgpu-native 29.0.1.1.

- The JAR has no Java code. Under `META-INF/rethinkj-ui/natives/<platform>/` it holds the libraries, `manifest.txt` and the licence texts in `licenses/`. `manifest.txt` lists the libraries in load order, one line each: `library <name> <version> <file> <sha256>`, after the lines `rethinkj-ui-natives 1` (the format) and `platform <platform>`. A library built from source is followed by `built <name> <sha256 of the source archive>`, because its own hash differs between machines. `com.rethinkj.ui.natives.Manifest` reads it, ignores `built` and documents the format.
- No binaries are committed. `natives-<platform>/natives.properties` pins each library: version, archive URL, the archive's SHA-256 and the file inside it. `scripts/FetchNatives.java` (run with `java scripts/FetchNatives.java natives-windows-x64`; it needs only the JDK) downloads the archives into `natives-<platform>/fetched/archives/`, checks their hashes, and unpacks the libraries and writes `manifest.txt` into `natives-<platform>/fetched/resources/`, which the module packages. The module's build runs it in `generate-resources`; it does nothing while `fetched/` matches `natives.properties`. `fetched/` is ignored by git and survives `mvn clean`; delete it to fetch again. Offline, put the archive at the path the error message names.
- A library with a `<name>.cmake` line, SDL on Linux, comes from a source archive: the tool builds it with cmake and make in `fetched/builds/<name>/` and reuses that build while archive, options and entry stay the same. It fails if cmake turns off an option the line sets `ON`, such as `SDL_X11` or `SDL_WAYLAND` when their development files are missing.
- A Linux library is packaged under its SONAME (`libSDL3.so.0`), so that a library that needs it finds the loaded copy; the tool checks that.
- To change a version, edit `natives.properties`, build, and update the licence texts under `natives-<platform>/src/main/resources` if the release changed them.
- A natives module is built only on its own platform: the root `pom.xml` lists it in a profile of the same name, activated by the operating system and architecture, and `core` has a profile with that id that adds it as a test dependency.
- `com.rethinkj.ui.natives.Natives.lookup("sdl3")` returns a `SymbolLookup`, loading the library and every library listed before it on first use. The package is internal to `core`. It finds the manifest of the current platform on the class path and unpacks the libraries into a per-user cache, `%LOCALAPPDATA%\rethinkj-ui\natives\` on Windows and `$XDG_CACHE_HOME/rethinkj-ui/natives/` (default `~/.cache`) on Linux, in a subfolder named after their hashes. Two system properties change that:
  - `rethinkj.ui.natives.dir`: load from this folder, which holds the libraries and their `manifest.txt` as the JAR has them. Nothing is unpacked or hash-checked, and the class path is not read. For packaged apps and GraalVM native image.
  - `rethinkj.ui.natives.cache`: unpack into this folder instead. Core's tests set it to `core/target/natives-cache`.
- Native access must be enabled for core: `--enable-native-access=com.rethinkj.ui` (core's tests pass it). On the module path, the natives JAR is resolved only with `--add-modules com.rethinkj.ui.natives.<os>.x64`.
- Loading happens only on first use. Never call `Natives` from a static initialiser that the headless path can reach: rendering without a window must not need the libraries.
- Linux cannot load a library from a file system mounted `noexec`; then the cache has to move. On Linux, a failed load reports the dynamic linker's reason, which the JDK leaves out.
- The SDL built for Linux needs the glibc of the machine that built it (2.38 on Ubuntu 24.04) and lacks what that machine had no headers for (IBus, libdecor). A build on an older distribution does not exist yet.

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
