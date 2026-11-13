# rethinkj UI

A native, GPU-rendered UI framework for Java, modelled on [rune](https://github.com/anomalyco/rune) (Rust, MIT). Rune is the reference for scope and concepts: its own renderer, text, layout and animation, plus a headless mode in which an agent can render to PNG, dump the element tree and drive an app with scripted input.

Status: early. `core` draws a view without a window: element tree, flexbox layout, CPU reference renderer, PNG. There is no window, GPU renderer, text or input yet, and `kit` is empty. The native libraries for the window and the renderer are packaged and loaded, but nothing calls them yet.

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

Image tests compare what `Headless.render` produces with the PNG and tree files under `core/src/test/resources/golden`. After a deliberate change of the output, rewrite them with `mvn -pl core test -Dgolden.update=true` and look at the images before committing. A failing image test leaves the expected image, the actual image and their difference in `core/target/golden`.

The layout is tested against Taffy's flexbox cases in `core/src/test/resources/taffy`; `scripts/import-taffy-fixtures.sh` refreshes them.

On Windows, the first build downloads the native libraries (see below) and needs network access; later builds, `mvn clean` included, do not. `mvn verify -P '!natives-windows-x64'` builds without them and skips the tests that load them.

## Modules

| Directory              | Artifact                          | Base package          | Purpose                                     |
| ---------------------- | --------------------------------- | --------------------- | ------------------------------------------- |
| `core/`                | `rethinkj-ui-core`                | `com.rethinkj.ui`     | The framework itself                        |
| `kit/`                 | `rethinkj-ui-kit`                 | `com.rethinkj.ui.kit` | Components built on `core`                  |
| `natives-windows-x64/` | `rethinkj-ui-natives-windows-x64` | none, no Java code    | SDL3 and wgpu-native for Windows on x64     |

All artifacts share the groupId `com.rethinkj.ui`.

## Native libraries

The window (SDL3) and the GPU renderer (wgpu-native) will call native libraries. They reach users in one JAR per platform, `rethinkj-ui-natives-<platform>`, where the platform is `windows-x64` or `linux-x64`. Only `natives-windows-x64` exists so far. Its versions: SDL 3.4.20 and wgpu-native 29.0.1.1.

- The JAR has no Java code. Under `META-INF/rethinkj-ui/natives/<platform>/` it holds the libraries, `manifest.txt` and the licence texts in `licenses/`. `manifest.txt` lists the libraries in load order, one line each: `library <name> <version> <file> <sha256>`, after the lines `rethinkj-ui-natives 1` (the format) and `platform <platform>`. It says nothing about where the files came from. `com.rethinkj.ui.natives.Manifest` reads it and documents the format.
- No binaries are committed. `natives-windows-x64/natives.properties` pins each library: version, release archive URL, the archive's SHA-256 and the file inside it. `scripts/FetchNatives.java` (run with `java scripts/FetchNatives.java natives-windows-x64`; it needs only the JDK) downloads the archives into `natives-windows-x64/fetched/archives/`, checks their hashes, and unpacks the libraries and writes `manifest.txt` into `natives-windows-x64/fetched/resources/`, which the module packages. The module's build runs it in `generate-resources`; it does nothing while `fetched/` matches `natives.properties`. `fetched/` is ignored by git and survives `mvn clean`; delete it to fetch again. Offline, put the archive at the path the error message names.
- To change a version, edit `natives.properties`, build, and update the licence texts under `natives-windows-x64/src/main/resources` if the release changed them.
- A natives module is built only on its own platform: the root `pom.xml` lists it in a profile of the same name, activated by the operating system and architecture, and `core` has a profile with that id that adds it as a test dependency.
- `com.rethinkj.ui.natives.Natives.lookup("sdl3")` returns a `SymbolLookup`, loading the library and every library listed before it on first use. The package is internal to `core`. It finds the manifest of the current platform on the class path and unpacks the libraries into a per-user cache, `%LOCALAPPDATA%\rethinkj-ui\natives\` on Windows and `$XDG_CACHE_HOME/rethinkj-ui/natives/` (default `~/.cache`) on Linux, in a subfolder named after their hashes. Two system properties change that:
  - `rethinkj.ui.natives.dir`: load from this folder, which holds the libraries and their `manifest.txt` as the JAR has them. Nothing is unpacked or hash-checked, and the class path is not read. For packaged apps and GraalVM native image.
  - `rethinkj.ui.natives.cache`: unpack into this folder instead. Core's tests set it to `core/target/natives-cache`.
- Native access must be enabled for core: `--enable-native-access=com.rethinkj.ui` (core's tests pass it). On the module path, the natives JAR is resolved only with `--add-modules com.rethinkj.ui.natives.windows.x64`.
- Loading happens only on first use. Never call `Natives` from a static initialiser that the headless path can reach: rendering without a window must not need the libraries.
- `natives-linux-x64` will follow with the same layout and manifest format. SDL publishes no Linux binaries, so its SDL comes from a local build rather than a release archive.

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
