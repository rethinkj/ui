# rethinkj UI

A native, GPU-rendered UI framework for Java, modelled on [rune](https://github.com/anomalyco/rune) (Rust, MIT). Rune is the reference for scope and concepts: its own renderer, text, layout and animation, plus a headless mode in which an agent can render to PNG, dump the element tree and drive an app with scripted input.

Status: early. `core` runs an app with and without a window: element tree, flexbox layout, text (shaped with HarfBuzz, rasterised with FreeType), springs and fading, clipping and scrolling, pointer, wheel and keyboard input with focus, hover and pressed looks and drags, single-line text fields, a CPU reference renderer and a GPU renderer that is checked against it, PNG, scripts that drive an app, and SDL windows. `kit` has a theme, the first components and a data grid; `examples` has a runnable light switch. The native libraries (SDL3, wgpu-native, FreeType, HarfBuzz) are packaged per platform and loaded on demand.

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
| `press <target> [clicks <n>] [shift] [control] [alt] [meta]` | Pointer down and up on the target. `clicks 2` is the second press of a double press, `clicks 3` of a triple one; the modifiers are held during the press, as in `press #name shift`. |
| `press <target> right` | The same with the secondary (right) button, without clicks or modifiers: it runs the handler of `El.onSecondaryPress` with the position, where a context menu opens. `down <target> right` and `up <target> right` are its halves. |
| `down <target> [clicks <n>] [shift] [control] [alt] [meta]`, `up <target>` | The two halves, for a press that ends elsewhere. A press counts only when both land on the same element. Between them, `move` drags an element with a drag handler (`El.onDrag`) once the pointer is more than 4 pixels from where it went down, and then the press does not count. In a text field, `move` in between selects by dragging, from the first pixel, and drags no element around the field; on a scrollbar's thumb it drags the thumb, also from the first pixel. |
| `move <target>` | Moves the pointer onto the target, which hovers it and the elements around it. Presses do not move the pointer. |
| `leave` | The pointer leaves the window: nothing is hovered. |
| `wheel <target> <deltaX> <deltaY>` | Turns the wheel with the pointer on the target, by these distances in logical pixels; positive scrolls down or right. Along each axis the innermost scroll area under the pointer that can still move that way takes the whole distance, up to its end. |
| `scroll <target> row <index>` | Scrolls the list of rows (`El.rows`) at the target, or the one around it, so that the row `<index>`, counted from 0, is at its top, or as far as the end of the list allows. |
| `key <name> [shift] [control] [alt] [meta]` | Presses and releases a key with these modifiers held, as in `key Tab shift`. Keys have the web's key names (`Enter`, `Tab`, `Space`, `Escape`, `ArrowDown`, `F5`), and a key that types a character is named by it in upper case (`A`, `1`); the Javadoc of `com.rethinkj.ui.Key` lists them all. |
| `type "<text>"` | Text input for the focused element (`\"` and `\\` inside). |
| `compose "<text>"` | The composition of an input method in the focused text field: shown underlined in place of the selection, not yet in the field's string. `compose ""` ends it; a `type` commits text. |
| `clipboard "<text>"` | Puts the text on the app's clipboard, where a paste takes it from. Needs no `frame`. |
| `focus <target>` | Gives the keyboard focus to the target, or the nearest focusable element around it, as Tab would. |
| `advance <seconds>` | Moves the app's clock. |
| `await [<seconds>]` | Waits until the work the app started on other threads (`App.background`) has delivered or was cancelled, at most 10 seconds unless it says otherwise, and queues what it and other threads posted for the next `frame`. Posted tasks reach a script only here (see "Work off the app's thread"). When work still runs after the timeout, it fails and names that work. |
| `frame` | Runs one frame: the handlers and scrolls that input queued, a rebuild of the view, the animations at the current time. |
| `snap <name>` | Writes `<name>.png` and `<name>.tree.txt` of the last frame into the output folder, and `<name>.clipboard.txt` with the text on the app's clipboard if there is any. `<name>` may contain `/` but must stay inside the folder. |

Only `frame` runs a frame, as in a window, where input arrives between frames: `press`, `move`, `leave`, `key`, `type`, `compose`, `focus`, `wheel` and `scroll` queue what they do, `await` queues what other threads delivered and `advance` moves the clock, but the screen changes at the next `frame`. The frame dispatches the queued input in order, each against a tree built from the state the input before it left: after an input that a handler took, it builds the view again before the next, so two `key ArrowDown` before one `frame` move a selection by two rows, a `key` after the `press` that opened a menu goes to the menu, and a `press` after one that dismissed a menu finds no menu there. `snap` refuses to write a frame that does not show the steps before it. Scripts use an in-memory clipboard, the app's default; only a window gives the app the system's.

A `.tree.txt` has one line per element, children indented, with its rectangle in window coordinates as shown, that is, moved by the scroll offsets above it. After the rectangle come the element's states, what the app's state is, with their names from WAI-ARIA, only those that are set and always in this order: `disabled`, `readonly`, `selected`, `current` (the cursor's cell or row among the selected ones, WAI-ARIA's `aria-current`), `checked` (or `checked=false`, `checked=mixed`), `expanded` (or `expanded=false`; absent where nothing opens), `level=<n>` (from 1), `pos=<n>/<size>` (the position in its set, from 1, and the set's size), `sort=ascending` or `sort=descending`, and `value="<text>"`, as in `el #row-8 role=treeitem label="created_at" 8,184 264x22 selected level=5 pos=2/3`. The view sets them with `El.disabled(boolean)`, `selected(boolean)`, `current(boolean)`, `checked(boolean)`, `checkedMixed()`, `expanded(boolean)`, `level(n)`, `position(n, size)`, `sortAscending()`, `sortDescending()` and `value(text)`; they change nothing about how the element looks or takes input. A text field's `disabled` (`editable(null)`) and `readonly` (`readOnly()`) come from the field, and each row of a list of rows (`El.rows`) has its index plus 1 among all rows as `pos` unless its row function sets another, as in `el #row-765436 role=row 8,119 964x22 selected pos=765437/1000000`. A scroll area that is scrolled adds its offset after the rectangle, as in `el #list role=list 16,16 208x168 scroll 0,90`. A list of rows (`El.rows`) adds before that the rows that exist, first and last, and how many rows it has, as in `el #rows role=grid 16,44 248x160 rows 765429-765440/1000000 scroll 0,21432096`, or `rows -/<count>` when none exist; its children are those rows in order. A line that ends in `exiting` is an element with a key and motion that a rebuild left out: it fades out where it was, takes no input, and is removed once invisible. Selectors do not match it, nor anything at opacity 0. The line of the element that a press under the pointer would go to ends in `hovered`, during a drag that of the dragged element, during a selection by dragging that of the text field and over a scrollbar or while its thumb is dragged that of the scroll area, and that of the element that has the keyboard focus in `focused`. A focused text field adds its caret as an index into its string, then the other end of a selection and an input method's composition: `text #name role=textbox label="Table name" "SELECT name FROM customers" 40,26 160x28 focused caret 17 anchor 26 composing "ni"`.

The overlays shown above the tree (see Overlays) follow it, in the order they are drawn, each under a line that starts with `overlay`, says where it is placed and ends with what it is anchored to, its key, role, label and rectangle as shown; the overlay's tree follows, indented by two spaces, in window coordinates:

```text
overlay at 120,85 bottom start on #list role=list label="Tables" 16,16 208x146
  el #menu role=menu 120,85 81x86
    el #open role=menuitem label="Open" 125,90 71x24 focused
overlay right start gap 6 on #export role=menuitem label="Export" 125,116 71x24
  el #formats role=menu 53,116 66x60
overlay center modal scrim on 0,0 240x200
  el #dialog role=dialog label="Delete" 34,55 172x91
```

Keys go to the focused element and then up through its ancestors until a key handler takes them; without focus only the root gets them. If none does, Tab and Shift+Tab move the focus through the focusable elements in tree order, around at the ends, and Enter and Space press the focused element. A press focuses the focusable element it lands in and clears the focus elsewhere. Focus moved by the keyboard or by `focus` is drawn with a ring and scrolls into view; a press shows no ring.

A target is a position `x,y` in logical pixels, or a selector for exactly one element of the last frame: `#key` (anywhere in the tree and its overlays), `role=<role>`, `label="<text>"` (`\"` and `\\` inside), `text="<string>"` (see Text), a state as a `.tree.txt` writes it (`selected`, `current`, `disabled`, `readonly`, `checked`, `checked=false`, `checked=mixed`, `expanded`, `expanded=false`, `level=<n>`, `pos=<n>/<size>`, `sort=ascending`, `sort=descending`, `value="<text>"`; `checked=true` and `expanded=true` also do), or several of them, as in `role=switch label="Lights"` or `role=treeitem expanded label="public"`. A selector presses the centre of the element's rectangle. When it matches nothing, the error lists the keys, roles, labels and states there are, and the texts if there are any, and the lists of rows with the rows that exist, since a selector finds only those; when only its states match nothing, it lists the lines of the elements that match the rest, which show their states; when it matches several, it lists them with their rectangles.

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

The examples have text, so running one needs the fonts and the platform's native libraries on the class path besides core's classes. Build them, and keep the class path in `CP`, from the repository root. On Linux, in a POSIX shell, with `:` between the entries:

```sh
mvn -q -pl core -am test-compile
CP=core/target/classes:core/target/test-classes:fonts/target/classes:natives-linux-x64/target/classes
```

On Windows, in PowerShell, with `;` between the entries and the Windows natives instead, since `natives-linux-x64/target/classes` exists only where the Linux module is built:

```powershell
mvn -q -pl core -am test-compile
$CP = 'core/target/classes;core/target/test-classes;fonts/target/classes;natives-windows-x64/target/classes'
```

Every `java` command below then runs as written in both shells. Run the switch's script, and look at the three images and trees in `core/target/switch`:

```sh
java --enable-native-access=ALL-UNNAMED -cp $CP com.rethinkj.ui.Examples --script core/src/test/resources/scripts/switch.txt --out core/target/switch
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
java --enable-native-access=ALL-UNNAMED -cp $CP com.rethinkj.ui.Examples list --script core/src/test/resources/scripts/list.txt --out core/target/list
```

`core/src/test/resources/scripts/rows.txt` scrolls a list of a million rows, which `Examples` runs when `rows` comes first. A list made with `El.rows(count, rowHeight, row)` builds only the rows in its visible part and three more on each side, so the tree holds a dozen rows, not a million:

```text
# A million rows in a virtual list (Examples rows): only the rows in view and three more on
# each side exist, and the tree says which.
size 280 220 scale 2
frame
snap rows-top

wheel #rows 0 40          # 40 pixels down: the rows that exist suffice, none is built
frame
snap rows-scrolled

scroll #rows row 765432   # row 765432 at the top: the list builds the rows around it
frame
snap rows-far
```

```sh
java --enable-native-access=ALL-UNNAMED -cp $CP com.rethinkj.ui.Examples rows --script core/src/test/resources/scripts/rows.txt --out core/target/rows
```

A scroll area with `El.scrollbars(look)` has a bar along each axis whose content does not fit, and the line of the area in a `.tree.txt` ends in the rectangle of each thumb, `thumb-x` for the bar along the bottom and `thumb-y` for the one along the right edge, as in `el #rows role=grid 16,44 248x160 rows 0-8/1000000 thumb-y 256,46 6x24`. `down`, `move` and `up` at positions inside it drag the thumb, and a `press` on the track beside it scrolls a page. `core/src/test/resources/scripts/scrollbars.txt` drags the thumb of the million rows with bars, which `Examples` runs when `bars` comes first:

```text
# A million rows with scrollbars (Examples bars): the thumb shows where the rows in view are, and
# dragging it reaches both ends.
size 280 220 scale 2
frame
snap bars-top          # the line of #rows ends in thumb-y 256,46 6x24

down 259,58            # on the thumb, which follows the pointer from the first pixel
move 259,400           # beyond the bottom of the window: the thumb at the end of its track
frame
snap bars-end          # the last row, 999999, at the bottom; the thumb in its dragged colour
up 259,400

press 259,120          # on the track above the thumb: one page, the list's height, up
frame
snap bars-paged

down 259,190           # the thumb, back up beyond the top
move 259,-100
up 259,-100
frame
snap bars-back         # row 0 at the top again
```

```sh
java --enable-native-access=ALL-UNNAMED -cp $CP com.rethinkj.ui.Examples bars --script core/src/test/resources/scripts/scrollbars.txt --out core/target/bars
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

In `switch-focused.tree.txt` the switch's line is `el #switch role=switch label="Lights" 39,49 38x22 checked=false focused`.

```sh
java --enable-native-access=ALL-UNNAMED -cp $CP com.rethinkj.ui.Examples --script core/src/test/resources/scripts/focus.txt --out core/target/focus
```

`core/src/test/resources/scripts/hover.txt` shows the looks the switch has while the pointer is over it (`El.whenHovered`) and while it is pressed (`El.whenPressed`):

```text
# The light switch of core's tests (Examples) under the pointer: hovered, pressed, on, left.
size 200 120 scale 2
frame
move #switch           # the pointer over the switch, which springs to a lighter grey
frame
advance 1
frame
snap switch-hovered

down #switch           # darker while pressed; the press also focuses it, without a ring
frame
advance 1
frame
snap switch-pressed

up #switch             # the press toggles the switch, which is still hovered
frame
advance 1
frame
snap switch-hovered-on

leave                  # the pointer leaves the window: as after a press without hovering
frame
advance 1
frame
snap switch-on
```

In `switch-hovered.tree.txt` the switch's line is `el #switch role=switch label="Lights" 39,49 38x22 checked=false hovered`, although the pointer is over the knob: the knob has no handler, so a press would go to the switch.

```sh
java --enable-native-access=ALL-UNNAMED -cp $CP com.rethinkj.ui.Examples --script core/src/test/resources/scripts/hover.txt --out core/target/hover
```

`core/src/test/resources/scripts/field.txt` types into the text field of core's tests, which `Examples` runs when `field` comes first, selects and scrolls:

```text
# The text field of core's tests (Examples): typing, selecting and scrolling.
size 240 80 scale 2
frame
snap field-empty

press #name                  # focuses the field: the caret, and the placeholder until text comes
type "SELECT name FROM customers"
frame                        # wider than the field: the text scrolls to keep the caret in view
snap field-typed

key ArrowLeft control shift  # selects the word before the caret
frame
snap field-word

key Home                     # back to the start, and the text scrolls back
frame
snap field-home

down 60,40                   # a drag from the second letter to beyond the right edge
move 230,40
up 230,40
frame
snap field-dragged

key End
compose "ni"                 # an input method composes: underlined, not yet in the string
frame
snap field-composing
```

In `field-word.tree.txt` the field's line ends in `focused caret 17 anchor 26`: "customers" is selected, with the caret at its start.

```sh
java --enable-native-access=ALL-UNNAMED -cp $CP com.rethinkj.ui.Examples field --script core/src/test/resources/scripts/field.txt --out core/target/field
```

`core/src/test/resources/scripts/overlay.txt` opens a context menu on a row of a scrolled list, its submenu and a modal dialog, which `Examples` runs when `overlay` comes first:

```text
# The menus of core's tests (Examples overlay): a context menu on a row of a scrolled list, its
# submenu beside it, and a modal dialog over a scrim.
size 240 200 scale 2
frame
wheel #list 0 40
frame
press #row-3 right     # the secondary button: the row's handler opens the menu there
frame
snap overlay-menu      # over the list and across its rounded clip; the focus on its first item

press #export          # opens the submenu beside the item
frame
snap overlay-submenu

press #delete          # in the menu, below the submenu: the submenu is dismissed, the press counts
frame
snap overlay-dialog    # the menu closed, the dialog over a scrim, the focus on Cancel

key Tab                # stays inside the dialog: Delete, then around to Cancel
key Tab
frame
key Escape             # the dialog's handler closes it, and the focus goes back to the list
frame
snap overlay-closed
```

```sh
java --enable-native-access=ALL-UNNAMED -cp $CP com.rethinkj.ui.Examples overlay --script core/src/test/resources/scripts/overlay.txt --out core/target/overlay
```

The snaps equal the stored images of the same names under `core/src/test/resources/golden`; `ScriptTest` checks that, and that these examples match their files.

## Text fields

`El.text(value).editable(onEdit)` makes a single-line text field. The string belongs to the app: the view builds the field with the current value, and every edit hands the new string to `onEdit`, which stores it in the state. A handler may refuse an edit (store nothing) or change it (keep only digits, say); the field then shows the app's string, with the caret where it was before a refused edit, and otherwise at the same distance from the text after it or before it, whichever the change left alone. A value the app changes on its own keeps the caret near its text and ends the undo history.

- `App` keeps per field, by the element's identity as for scroll offsets: caret and anchor (indexes into the string), horizontal scroll, undo history (100 steps; a run of typing, a run of deleting, a paste or cut each one step; moving the caret ends a run) and an input method's composition. The editing core is `Editor`, package-private; it works on strings and does not assume one line except where a single-line field turns line breaks into spaces and Home and End go to the ends.
- The caret stops only at grapheme boundaries (`BreakIterator.getCharacterInstance`): never inside a surrogate pair, between a letter and its combining mark, or inside an emoji sequence; Backspace removes a grapheme. Words (Ctrl+arrows, Ctrl+Backspace, Ctrl+Delete, double press) are segments of `BreakIterator.getWordInstance` with a letter or digit. Ctrl+Right and Ctrl+Delete go to the end of a word on Linux and to the start of the next one on Windows (`Editor.WORD_ENDS`), so scripts with stored images avoid them.
- Keys: arrows, Home, End, with Shift to select and Control by words; Backspace, Delete; Control+A, C, X, V, Z, Y and Control+Shift+Z; Control+Insert, Shift+Insert and Shift+Delete. Keys that type are the field's (their text comes with `textInput`); Enter, Escape, Tab and unknown shortcuts go on to the `onKey` handlers. The field's own `onKey` sees every key first. While an input method composes, it has the keys. Tab into a field selects its text.
- Pointer: a press places the caret at the nearest boundary, a drag selects (beyond the edge it scrolls, one step per pointer move), a second press (`clicks 2`) selects a word and drags by words, a third everything, Shift extends. `App.pointerDown(x, y, clicks, modifiers)` takes what the window reports. A press in a field is the field's, even inside an element with `onPress` or `onDrag`: moving from it selects from the first pixel, without the drag threshold, and never becomes a drag of an element around the field, which can be grabbed outside the field instead. Until the release the field counts as hovered and pressed (`El.whenPressed`) and keeps its cursor, wherever the pointer is, as a dragged element does. A selection has nothing to cancel: if the field leaves the tree or the release gets lost, it stays as far as it got.
- Caret positions come from the shaped `GlyphRun`: at the pen position of the glyph that starts the next cluster, so kerning counts, and inside a ligature (Inter's `->` with `.fontFeatures("+calt")`) in proportion to the graphemes before it. JetBrains Mono draws its ligatures with one glyph per character, so its carets are plain.
- Drawing: `Text.selection()` behind the glyphs, `Text.composition()` (the underline) and `Text.caret()` after them, all ordinary rectangles with edges on whole physical pixels, clipped with the glyphs to the content box across and the padding box from top to bottom; the glyphs move left by `Text.scroll()`, which keeps the caret in view with `Editor.MARGIN` (4 logical pixels) to spare when the text is wider than the field. Without the focus a field shows its start, no caret and no selection, and forgets its scroll; a window that loses the focus leaves all that as it is and only hides the caret (next point). The line is centred vertically; layout measures a field as 0 wide, so its style gives the width.
- The caret blinks on the app's clock, on for `App.BLINK` (0.5 s), off as long, and restarts on after each change; `frame()` returns true only when it turns, and `App.nextChange()` says when that is, which `WindowLoop` waits for. While the window is inactive (`App.windowActive(false)`, which `WindowLoop` passes on from the window's focus events) there is no caret and nothing blinks; focus, selection and ring stay as they are, and activation turns the caret on.
- `.readOnly()`: focus, selection and copy, no edits; it shows a caret and takes no text input. `editable(null)` disables the field: no focus, presses go to its ancestors. A `.tree.txt` says `readonly` or `disabled` after the field's rectangle without a call of `El.disabled`. `.maxLength(n)` limits typed and pasted text to `n` code points, cut at a whole grapheme. `.placeholder(text, argb)`, `.caretColor(argb)` (the text colour unless set), `.selectionColor(argb)`.
- The clipboard is `App.clipboard()`, a `Clipboard`: `Clipboard.inMemory()` at first, which scripts and tests use, and the system's (SDL) once a `WindowLoop` runs the app.
- `App.inputArea()` is the focused field's box and caret for the system's input method, or null when the focused element takes no text; `WindowLoop` turns the window's text input on with it (`Window.textInputArea`) and off without (`Window.stopTextInput`), so an on-screen keyboard shows only for a field. Compositions arrive as `WindowEvent.TextEditing` and go to `App.textComposition`; the window asks SDL to leave drawing them to the app (`SDL_IME_IMPLEMENTED_UI=composition`).
- A field in a row of a virtual list (`El.rows`) gets its editor when the list builds the row, by the row's index. The editor goes when the row leaves the rows that exist, as everything else the app keeps of a row, except the focused field's: it stays with the focus and comes back with the row, without its composition.
- `FieldTest` and `FieldGeometryTest` drive fields through `App`, `FieldRowsTest` drives them in the rows of a list and in a scroll area; `EditorTest` checks boundaries and needs no native libraries.

## Overlays

A menu, a tooltip, a dropdown list or a dialog lies above everything else and is not clipped by the scroll area its anchor is in. The view declares it next to the element it belongs to, `anchor.overlay(menu)`, so that a component returns its anchor with what it opens attached; whether it is open is the app's state, as all state: `button.overlay(s.menuOpen ? menu : null)`. An overlay is an ordinary tree with layout, text, scrolling, motion and presence: its identity is the anchor's plus its key or index among the anchor's overlays, counted apart from the children, so one with a key and `motion` fades in as it opens and out as it closes. `El.overlays()` lists an element's overlays; after a frame, the root's `El.shownOverlays()` lists those shown, in drawing order, in window coordinates.

- Placement, after layout (`Overlays`, package-private): each overlay is laid out as a root of its own, at most as large as the window (its max size is limited to it; one with `scroll()` then scrolls), and placed beside its anchor's rectangle as shown, moved by the scroll offsets above it. `.side(Side, Style.Align, gap)` (default `BOTTOM`, `START`, 0) puts it on a side, aligned `START`, `CENTER` or `END` along it, or `STRETCH` for at least as wide as the anchor (a select's list); `.atPoint(x, y)` takes a point of the window instead of the rectangle (a context menu at the pointer); `.centerInWindow()` a dialog. Where it does not fit, it goes to the other side, then to the other end along the side, then moves into the window as far as it needs. Positions are whole logical pixels. It is placed again in every frame that scrolls, moves or builds rows, so it follows its anchor.
- An overlay shows while its anchor shows. When the anchor is clipped away entirely (scrolled out of its area), invisible or leaving, the overlay is neither drawn nor hit, and stays open: the state is the app's, and the core never closes anything. In practice the wheel closes menus first (below); a programmatic scroll leaves them hidden until the anchor is back.
- Drawing (`Scene`): after the whole tree, in the order the overlays opened (the animator counts when an identity first appeared; a submenu opens after its menu, and ties keep tree order), each as a tree of its own from clip entry 0, so free of the anchor's clips and opacity, with its own (rounded) clips. `.scrim(argb)` draws a rectangle as large as clip entry 0 in that colour before the overlay, at its opacity. No new instance kind: order and clip indices only. An overlay's shadows (`El.shadow`, see GPU renderer) come with it, under it and free of the anchor's clips like the rest of it; the kit lifts its overlays with them and keeps a border from its tokens.
- Hit testing: overlays first, the topmost first; a hit inside one goes no further than its root: press and drag targets, hover looks, the cursor and the scroll areas the wheel moves are those of the overlay, never its anchor's. Hover belongs to the topmost thing under the pointer.
- A press outside: the overlays above the first one the press lands in get their `.onDismiss` handler called, the topmost first. If the press lands in an overlay below them, it goes on there (a press on a menu item closes the item's sibling's submenu and chooses the item). If it lands outside all overlays and one was dismissed, that is all it does: the menu button pressed again closes its menu and does not open it again, and a press beside a menu does no second, accidental action. Overlays without a dismiss handler (a tooltip) let presses through. The secondary button dismisses the same way. The handlers run when the frame reaches the press, in the order of input, and the input after it finds the tree they left.
- The wheel outside a non-modal overlay calls its dismiss handler and scrolls what is under the pointer, as Swing's popups and browsers' select lists close on scrolling: a menu that rode along with scrolled content would cover other content and lose its anchor.
- `.modal()`: input outside it reaches nothing below it. A press there calls its dismiss handler and nothing else (the scrim of a dialog), the wheel does nothing, hover finds nothing, `App.focus` refuses elements outside it, Tab and Shift+Tab go around inside it, and keys go up from the focused element only as far as its root, which gets them while nothing has the focus. Overlays that open above it, such as its select's list, work as usual. An overlay that is leaving no longer blocks.
- Focus: an overlay that opens moves the focus to its first focusable element in tree order, without a ring; a modal one without such an element takes the focus away. When it closes, the focus goes back to where it was before it opened, with the ring it had, if the focus is still inside the overlay or nowhere and that element still exists; overlays that close in the same frame give it back the newest first, so that a submenu returns it to its menu and the menu to its button. This happens before the looks are applied, so the focus look shows in the same frame, and in every build of the view, also between two inputs of one frame, so that a key right after the press that opened a menu goes to the menu. Tab walks a non-modal overlay right after its anchor and everything inside the anchor. Overlays attached inside the rows of a virtual list are placed and drawn but do not take or give back the focus; attach a row's menu to the list.
- Keys: from the focused element up to the overlay's root, then on to its anchor and the anchor's ancestors, so that a handler of the menu's owner sees Escape. Escape closes nothing by itself: the overlay's own `onKey` calls the app.
- The secondary button: `El.onSecondaryPress((x, y) -> ...)` runs when the right button goes down and up on the element or a descendant without such a handler, with the release's position in the window; it focuses as a press does and never drags or selects. `App.secondaryDown/secondaryUp` take it, `WindowLoop` passes the right button there, and the Menu key or Shift+F10 that no key handler takes run the nearest handler at the bottom left of the focused element's cursor: the first element inside it that is `current` (a grid's cursor cell), else the first that is `selected` (a tree's selected row), else the focused element, kept inside its box. Scripts write `press <target> right`.
- Delays on the app's clock: `.whenHoveredFor(seconds, look)` is a look that applies once the pointer has rested on the element (or inside it) that long, until it leaves or presses; it may attach an overlay, which is how a tooltip appears. `.onHoverFor(seconds, handler)` runs a handler once per stay (0: in the frame that finds the pointer there), which a menu uses to follow the pointer and to open a submenu after a delay. `App.nextChange()` reports when the next one is due, so an idle window wakes for it, and `frame()` returns false while it waits: no frames are presented. A press keeps both away until the pointer leaves and comes back.
- `OverlayTest` checks placement, following and hiding, drawing order and clips, dismissing, modality, focus and keys, the secondary button, the delays, presence and the dump without text; `ScriptTest` runs `overlay.txt` against the stored images `overlay-*`; `GpuRendererTest` compares those and a menu with its submenu across the rounded clip of a scrolled list and a dialog over a scrim at scales 1, 1.5 and 2.

## What a table needs from core

The kit's data grid is built from these; any view may use them. `TableTest` checks each without text.

- Presses: `El.onPress((clicks, modifiers) -> …)` is told the modifiers held when the pointer went down (Shift adds a sort key); Enter and Space give `1, 0`. `El.onPointerDown((clicks, modifiers) -> …)` runs as the primary button goes down on the element or inside it, before anything moves, without making the element the target of the press or drag that follows, which may belong to an ancestor; a press on a scrollbar, in a text field or outside an open overlay runs none.
- `Drag` has `x` and `y`: where the pointer is in the dragged element's content, its border box as the last frame shows it plus its own scroll offset, doubles, so that a drag on a list of rows reads the row under the pointer also while the list scrolls. The wheel's scrolling of the frame that runs a step comes after it.
- `El.fixedX()` keeps an element in place sideways while the nearest scroll area around it that scrolls sideways (`scrollBoth`) scrolls, for drawing and input: frozen columns. It is drawn in tree order, so it comes after what it covers and needs a fill. `El.fix(root)` links such elements after each build, and a list links the rows it builds.
- `El.rows(count, rowHeight, (index, from, to) -> row)` builds each row for the part in view sideways, from `from` to `to` in the content box, which is the visible part (clipped by the window and the ancestors that clip) widened by half its width on each side; scrolling sideways beyond that part builds the rows anew, without the view. The `IntFunction` form builds whole rows as before.
- `El.reveal(key, x, y, width, height)` keeps a rectangle of a scroll area's content in view whenever a rebuild gives it another `key` than the tree before, as `revealRow` does for a row's index: the grid keeps its cursor's column, or a dragged edge, in view without jumping back when nothing moved.
- `El.onRowsBuilt((first, last) -> …)` tells the app which rows a list built, when they change, not on every rebuild; the handler runs after the input of the frame that built the rows, or in the next frame when the rebuild at its end built them, and `App.nextChange()` returns the current time while a handler waits. There is no thread-safe way yet to hand state from another thread to an `App` and wake its window.

## Work off the app's thread

An app, its state and its elements belong to the thread that runs it; `App` is not thread-safe, and only handlers change the state, in the order of the input. A query or a file that takes long runs on another thread, and its result comes back through `App.post(Runnable)`, the one method of `App` that any thread may call. It never blocks. The task joins the queue of input after the input that came before it, the tasks of one thread in their order, and runs on the app's thread as the handler of an input: against the tree the input before it left, and the view is built again before the next input, so a key, a task and a key each see what the one before left, and the frame draws. A task posted while a frame runs, by a handler too, waits for the next frame.

- `App.background(name, compute, onResult, onFailure)` runs `compute`, a `Callable`, on a virtual thread of its own (`Thread.ofVirtual()`, no pool) and posts `onResult` with its result or `onFailure` with its exception. It returns a `Work`: `work.cancel()`, called on the app's thread, keeps both handlers from running, also when the answer already waits in the queue, and interrupts the thread, so the answer of a request the user closed or asked again never arrives. The computation gets only what the handler put in its closure (names, an SQL string, a connection), never the state or elements: the app's thread reads them while it builds the view, and nothing guards them. An `Error` it throws is thrown on the app's thread. Virtual threads do not keep the JVM alive, so work that runs when `main` returns is dropped. The Javadoc of `post` shows the same with a thread of the app's own, in three lines.
- A `WindowLoop` wakes for tasks. It gives the app `Window::wake` with `App.onPost`, which the first post after the app took the ones before it calls: it pushes an SDL event of a type from `SDL_RegisterEvents` (`SDL_PushEvent` may be called on any thread), which ends `Window.waitEvents`. The tasks that arrived until the frame takes them run in that one frame, and a thread that posts nothing costs nothing. On Linux under X11, `WindowLoopTest` measured about 1 ms from a post to its frame; a burst of 1000 tasks that arrives while the loop draws is one frame, and one that arrives while it waits is one or two, as the first task wakes it before the last is there.
- Without a window (`Headless`, tests that call `frame()`) a task runs at the next `frame()`. A task that throws ends the frame as a handler that throws does: the exception comes out of `frame()` and the input after it waits for the next frame; a `WindowLoop` ends with it, and a script fails at its `frame` step. After the window has closed, tasks still queue; they run if the app runs frames again.
- Scripts and tests do not wait for the clock on the wall. `App.await(Duration)` waits until the work that `background` started has delivered or was cancelled, then queues what it and other threads posted for the next `frame()`; when work still runs after the timeout, it throws and names it. A script holds every posted task until its step `await`, so a snap shows "Loading…" and, after `await` and `frame`, the answer, however fast the work was. An app whose made-up work sleeps makes it immediate under a script: `SchemaBrowser.main` sets its latency to 0 before `Script.handle`.
- `PostTest` checks the order of ten threads' tasks, the dispatch rule, cancellation, failures, idle frames and scripts; `WindowLoopTest` the wake-up and the frames of a burst in a real window; `TreeTest` the kit's tree with a catalog whose staging server answers on another thread.

## Examples

`examples/` holds small apps that open a window, written as a user of the library writes them. The module is built and tested with the others but never installed or deployed. From the repository root, on Linux and Windows, this builds what it needs and starts the light switch, `com.rethinkj.ui.examples.LightSwitch`:

```sh
mvn -q -pl examples -am compile exec:exec
```

It is the switch of rune's README in a frameless window with a title bar of its own, built from the kit's `toggle` and `label` in `Theme.DARK`: drag the bar to move the window, use its buttons, press the switch or its text, or Tab to it and press Space; the lamp above it shows the state. The example runs in a JVM of its own, with the JDK that runs Maven and `--enable-native-access=ALL-UNNAMED`. With `-Dargs="--script <file> --out <folder>"` it runs a script instead of opening the window, with paths relative to the folder Maven was started in. `LightSwitchTest` passes `examples/src/test/resources/scripts/light-switch.txt` to the example's `main` and compares the snaps with `examples/src/test/resources/golden`; `mvn -pl examples -am test -Dgolden.update=true` rewrites them, and core's and kit's stored images too.

`-Dexample=<class>` starts another example of the package instead, as in `mvn -q -pl examples -am compile exec:exec -Dexample=ConnectionDialog`: the dialog in which a database tool asks where to connect, the kit's components in a small form with a switch between the dark and the light theme. `ConnectionDialogTest` runs it on `connection-dialog.txt` in the same way. `-Dexample=DataBrowser` shows a million made-up orders in the kit's data grid, with a filter above it and a status line below; the text columns repeat every thousand rows, so the filter tests those and finds the other rows by arithmetic. `DataBrowserTest` runs it on `data-browser.txt` and checks the filter against a test of every row. `-Dexample=SchemaBrowser` is the schema of made-up database servers in the kit's tree beside the rows of the selected table: opening a node and selecting a table ask on other threads (see "Work off the app's thread"), every answer takes 0.8 seconds and staging's three times as long, prod-eu refuses to connect, and meanwhile the tree shows "Loading…" and the window moves and scrolls; selecting another table cancels the rows asked for before. A right click on a node selects it and opens a menu (Open, Copy name, and on a table Drop table…, which asks for the table's name in a dialog and drops nothing), and the title bar's minimize and close buttons have tooltips. `SchemaBrowserTest` runs it on `schema-browser.txt`.

## Kit

`kit` (`com.rethinkj.ui.kit`) holds components built on `core`: `Label.label`, `Icon.icon`, `Button.button`, `Toggle.toggle`, `Checkbox.checkbox`, `TextField.textField`, `DataGrid.dataGrid`, `Split.split`, `Tabs.tabs`, `Menu`, `Tooltip.tooltip`, `Dialog.dialog` and `Select.select`, one file each. A component is a static function that returns an `El`, which the caller may chain further, as in `.key("save")`; there is no base class.

- The theme is `Theme`, a record of token groups: surfaces (with the scrim under a modal dialog), text colours, borders, accent, status colours, spacing, radii, type, sizes, motion (with the delays of submenus and tooltips), the data grid's, and elevation, the shadows under popups and dialogs. `Theme.DARK` and `Theme.LIGHT` are the neutral default; an app copies one and changes a group with `withAccent(...)` and the other `with` methods. Fonts are `Supplier<Font>`, so that making or reading a theme loads no native library; the defaults need `rethinkj-ui-fonts`.
- A component takes the theme as its first parameter, `button(theme, PRIMARY, "Run", onRun)`. The view gets it from the app's state or a constant. There is no global or per-thread theme: a view says what it draws with, and two themes can be on screen at once, as in the gallery. Every focusable component draws its focus ring in `theme.border().focus()` (`El.focusRing`), so a view that switches its theme switches the ring with it.
- Components read only tokens: no colour, size or spring of their own. `TokenTest` fails on an eight-digit hex literal or a `Spring` constant in any file of the package but `Theme.java`. A component that needs a new value gets a new token, with a value in both themes; `ThemeTest` checks the contrast of the default themes (WCAG AA, 4.5:1 for text on the backgrounds it is used on, 3:1 for the strong border and the focus ring).
- A null handler disables a component: it takes no focus, shows no pointer cursor and draws in the disabled colours, a press on it goes to its ancestors, and its line in a `.tree.txt` says `disabled`.
- Each component sets the states it knows (see "Driving an app with a script"): buttons `disabled`; toggles (`role=switch`, on is `checked`, as WAI-ARIA has it) and checkboxes `checked` or `checked=false` and `disabled`; text fields `disabled` and `readonly` from core; the data grid `selected` and `current` on the selected rows or cells and the cursor's, `pos=<column>/<columns>` and `sort=` on the header and the other cells, and the rows their `pos` from the list; the tree `selected`, `expanded` or `expanded=false` (not on leaves), `level` and `pos` among the node's siblings on each row; tabs `selected` on the active tab and `disabled` without a select handler; the split's separator its position as `value`, `first 200`, `second 320` or `share 0.5`.
- Toggle and checkbox are a row of the control and its text, and the row takes the press and the focus; its `whenHovered` and `whenPressed` looks restyle the track or box, which they capture. The row is `Len.FIT_CONTENT` wide, so a column that stretches its children leaves press area and ring as wide as control and text, and one that centres them still centres it.
- `textField(theme, value, placeholder, onChange)` is the core's text field (see Text fields) in a frame: `size().control()` high and `size().field()` wide unless the view sets a width, the body type, the strong border, a lighter fill under the pointer and the accent border while focused, the placeholder in muted text, the selection in `accent().selection()` and the caret in the text colour. The placeholder is also the label unless `.label(...)` sets one. The result is the field itself, so `.readOnly()`, `.maxLength(n)` and `.w(...)` chain onto it.
- A new component: a file with a static function whose first parameter is the theme, with `role`, `label`, `focusable()`, `cursor(Cursor.POINTER)`, `onPress` and the looks while it is enabled, and that sets its states: at least `disabled(handler == null)`, and whatever of its state an agent would otherwise read from a colour. Add its rows to `Gallery.ROWS` and its cases to `ComponentTest.components()` in kit's tests (or tests of its own there, as the text field has, when it is not pressed), rewrite the stored images with `mvn -pl kit -am test -Dgolden.update=true`, and look at them.
- `DataGrid.dataGrid(theme, columns, rowCount, DataGrid.options()…)` shows query results; the options turn on what the grid does, and state stays the app's:
  - Each `Column` has its title, width, alignment, whether it is monospaced and sortable, and a function from a row index to the cell's text (null is SQL NULL, shown muted). Hiding or reordering a column is the app's: it changes its list.
  - `.select(selection)`: a `DataGrid.Selection` the app keeps and the grid changes, as a `Menu.State` (`onSelect` tells of a change): a cursor, an anchor and `Range`s, whole rows in `Mode.ROWS`, cells in `Mode.CELLS`; a million selected rows are one range. A press selects at pointer down (`El.onPointerDown`), Shift extends from the anchor, Control adds or removes; dragging extends to the cell under the pointer (`Drag.x/y` on the list of rows) and beyond an edge scrolls one step per pointer movement, as core has no timer; the keys move the cursor (arrows, Page Up/Down, Home/End, with Control to the first and last cell), Shift extends, Control+A selects all. The rows follow the cursor (`revealRow`), the columns too (`El.reveal` keyed by the selection's moves). A press on a row number (`.rowNumbers()`, `role=rowheader`, `number-<row>`) selects rows.
  - `.copy(clipboard)`: Control+C and Control+Insert put the selection on the clipboard as tab-separated lines (`DataGrid.copyText`: the rows and columns with a selected cell, quotes only where a tab, line break or quote needs them, NULL as `.nullText`, "" by default); beyond `.copyLimit(cells, handler)` (100 000 cells) or with rows not loaded the grid copies nothing and hands a `Copy` to the handler. `.contextMenu(state, items)` opens a `Menu` at a secondary press, after selecting the cell unless it is selected: Copy, Copy with headers, then the app's items.
  - `.sort(keys, onSort)`: a press on a sortable header cycles ascending, descending, none and replaces the keys; Shift with a press adds or cycles a further key; arrows from the icon font (`Theme.Grid.mark`) and, with several keys, their number. `.onResize`: dragging a header edge resizes (beyond the grid's side the grid scrolls along), a double press fits the column to the title and the text of its rows that exist, within `minColumn` and `maxColumn`. `.frozen(n)` keeps the first n columns, and the row numbers, at the left (`El.fixedX`).
  - Rows are an `El.rows` list built for the columns in view sideways (`RowBuilder`), so a frame costs about the same for 300 columns as for 20; the header is built whole, so a rebuild of the view costs in proportion to the columns. Cell text ends in an ellipsis where it does not fit and shows whole in a tooltip after the theme's delay. `.loading(loaded, onRows)`: rows not loaded show a muted `…` in each cell without asking the cell functions, and `onRows` is told the rows the grid built when they change (`El.onRowsBuilt`). `.onEdit((row, column) -> …)`: Enter, F2 and a double press ask the app to edit the cursor's cell; the app lays a text field over it (`CellEditorTest`).
  - In a dump: `role=grid`, `columnheader` `column-<i>` with `pos` and `sort`, `separator` `resize-<i>`, `row` `row-<i>`, `gridcell` `cell-<row>-<column>` with `pos=<column>/<columns>`; `selected` on selected rows or cells and `current` on the cursor's. Its tokens are `Theme.Grid`: the cursor's outline stands out 3:1 from rows and selection (`ThemeTest`). The sort arrows need `rethinkj-ui-icons`.
  - `DataGridTest` runs `grid.txt`, `cells.txt` (a range, two sort keys, frozen columns, a tooltip, the menu, rows not loaded) and `rows.txt` against a million orders (`Orders`) in both themes and compares the snaps with `golden/<script>-<dark|light>-<snap>.png`; `GridCellsTest` drives the rest by scripts and reads the dump's states, and prints a frame's cost with 20 and 300 columns; `GridSelectionTest` checks selection, copied text and sort keys without the native libraries.
- `Tree.tree(theme, model, isOpen, selected, onSelect, onToggle, onActivate)` shows nodes that open and close, such as a database's servers, databases, schemas, tables and columns. The app implements `Tree.Model`: the roots, a node's children (null while they are not known), whether it is a leaf, its label, and optionally an `El` before the label (an icon) and the text of the loading row. It owns which nodes are open and which is selected; the tree reports a selection (only a change), a node to open or close (`Tree.Toggle`), and an activation. A press selects, a press on the mark (the icons `Tree.CLOSED` and `Tree.OPEN`, chevrons, which need `rethinkj-ui-icons`) selects and opens or closes, a double press opens or closes and activates (it reads the count from `El.onPress(IntConsumer)`); with the focus, Up, Down, Home, End, Page Up and Page Down move, Right opens and then goes to the first child, Left closes and then goes to the parent, Enter activates. A secondary press on a row selects it and then runs the tree's own `onSecondaryPress` handler, so that a `Menu.contextMenu` on the tree acts on the row it opened on. Each rebuild of the view walks the open nodes from the roots to number the rows, in proportion to the nodes shown (`TreeTest` prints it: about 1.3 ms for 100 000), and the rows are an `El.rows` list, so scrolling builds rows, never the view. An open node whose children are null shows one muted loading row; the app starts the load in `onToggle` and changes its state when the data is there. Rows are `role=treeitem` with the node's label and the key `row-<index>`, marks have the key `mark-<index>`; each row says its level, whether it is open and its position among its siblings as states (`expanded level=2 pos=1/3`), which costs two more ints per node shown in the walk. Its tokens are the grid's row height and selection colours, `space().xl()` per level and `surface().hover()` over the rows. `TreeTest` runs `kit/src/test/resources/scripts/tree.txt` against `Catalog`, three servers with a schema of 300 tables, in both themes, and compares the snaps with `golden/tree-<dark|light>-<snap>.png`.
- `Icon.icon(theme, symbol[, variant[, size]])` shows a `Symbol` of `Font.icons()` as a text element of one glyph, `size().icon()` (16) wide and high unless the call gives another size, that does not shrink, in the text colour or the variant's: `MUTED`, `DISABLED`, `DANGER`, `WARNING` or `SUCCESS`, the text and status colours. It has no role or label; one that stands alone, such as a button without text, gets a label from the caller. `Symbol` is an enum of 94 of the font's icons with Fluent's names in upper case (`CHEVRON_RIGHT` is `ic_fluent_chevron_right_<size>_regular`) and the code point of each size's drawing; `Symbol.codePoint(size)` picks the drawing made for the size, else the nearest. `Symbol.java` is generated: add a name to the list in `scripts/GenerateSymbols.java` and run `java scripts/GenerateSymbols.java` from the repository root, which reads the names from the font's own `post` and `cmap` tables. The font has one name per glyph, so a name Fluent's site gives as an alias may be missing (`arrow_export_ltr`, not `arrow_export`). A theme does not choose the icon font: the code points belong to this one. Apps that show icons, the tree and a sorted data grid among them, add `rethinkj-ui-icons`. `IconTest` stores a sheet of every symbol at 16 and 20 with its name and the colour variants, `golden/icons-<dark|light>-<1|2>x.png`.
- Scrollbars: `Theme.scrollbars()` is core's `Scrollbars` look, so any scroll area or list shows the themed bars with `.scrollbars(theme.scrollbars())`. Its thumbs, at rest, hovered and dragged, stand out 3:1 from the surfaces and the grid's rows, which `ThemeTest` checks. `DataGrid` has a bar along the right edge of its rows, below the header, and one along the bottom of the grid. `ScrollbarsTest` runs `kit/src/test/resources/scripts/scrollbars.txt` against a list in both themes at scales 1 and 2 and compares the snaps with `golden/scrollbars-<dark|light>-<1|2>x-<rest|hovered|dragged>.png`.
- `Split.split(theme, direction, position, reset, onMove, first, minFirst, second, minSecond)` puts two parts side by side (`Direction.ROW`) or one above the other (`COLUMN`) with a line between them. The app keeps a `Split.Position` and gets a new one of the same kind while the line is dragged or moved by keys: `Position.first(pixels)` keeps the first part's size when the split grows, as a side panel does, `second(pixels)` the second's, and `share(fraction)` the first part's share of the room, as an editor over its results. The handler turns the pointer's distance into a position from the sizes of the view the drag began in, whose elements it captured after their layout, so the view needs no container size. Layout keeps both minimums when the split is smaller than the position allows, without changing the position, so a wider window brings the layout back. The line is `border().subtle()` and `size().stroke()` wide; a strip of `size().handle()` around it takes the pointer, with the resize cursor of its direction, the strong border under the pointer and the accent while it is dragged. So that the strip lies over both parts, the second part and the strip are in a box of their own (`#rest`) in reverse order. The strip (`role=separator`, labelled `Resize` and the first part's label) is one stop for Tab, the arrow keys along the split move it by `size().step()`, and a double press (`El.onDoublePress`) reports `reset`. Each part fills a box that clips it (`#first`, `#second`), and splits nest.
- `Tabs.tabs(theme, tabs, active, onSelect, onClose, content)` is a bar of tabs over the content of the active one, which the app builds alone. Each `Tab` has its title, whether it can be closed and whether it is modified (a dot before the title); the app owns the list and the active index and gets the index of a chosen or closed tab. The active tab is `surface().raised()` with a subtle border and primary text, the others muted on the bar's `surface().panel()`, with the hover and pressed overlays and primary text under the pointer; a closable tab's cross (the font's `×`, as the checkbox's mark is `✓`) shows on the active tab and under the pointer, at opacity 0 otherwise, so it takes no press there. The bar (`#tablist`, `role=tablist`) is one stop for Tab: Left, Right, Home and End choose tabs. Control+Tab, Control+Shift+Tab, Control+W and Control+F4 are on the component's root, so they work from the bar and its content; when the new content lacks the focused element, the focus is gone and keys reach only the view's root, which an app gives the same handler with `root.onKey(tabs.onKey())`. Middle-button close waits for a second pointer button in core, and tabs do not move by dragging. The bar scrolls sideways only (`overflow-y` hidden): `App.wheel` turns a wheel that turns only up and down sideways in such an area, and `El.revealRow(active)`, which on a scroll area that is not a list of rows keeps the child with that index in view, shows the active tab whenever it changes. Each input reaches the handlers of a view built after the input before it, so keys in one frame go on from each other's choice, and a second Control+W closes the tab the first made active.
- `SplitTest` runs `kit/src/test/resources/scripts/split.txt` and `TabsTest` `tabs.txt` against the state in `Workbench`, and `WorkbenchTest` `frame.txt`, the frame of a database tool: the schema beside queries in tabs, the active one an editor over its results, at scales 1 and 2. They compare the snaps with `golden/<script>-<dark|light>-<snap>.png`.
- `Menu` shows items in an overlay (see Overlays): `Menu.item(label, onChoose)`, disabled with a null handler, with `.shortcut("Ctrl+C")` shown at the right in muted text and `.checked(true)` for a check mark; `Menu.separator()`; `Menu.submenu(label, items)`, one level deep, opening beside the item. The app keeps a `Menu.State` per menu in its state (open or not, the point it opened at, the highlighted items) and opens it in its own handlers: `Menu.dropdown(theme, button, state, items)` hangs the menu below a button whose press calls `state.toggle()`, `Menu.contextMenu(theme, target, state, items)` opens it at a secondary press with `state.openAt(x, y)` unless the target already has such a handler (rows can select themselves first), and `Menu.menu` is the panel alone. The menu takes the focus as it opens: Up and Down move the highlight around the items and past separators, Home and End, Enter and Space choose, Arrow Right opens a submenu and Arrow Left or Escape close it, Escape and Tab close the menu, a letter or digit goes to the next item that starts with it. The pointer highlights the item it rests on (`El.onHoverFor(0, ...)`), and after `motion().submenuDelay()` on an item opens its submenu or closes another. Choosing closes the menu and then runs the handler; a press outside closes it and does nothing else. Items are `role=menuitem` with their text as the label and the keys `item-<index>` or, in a submenu, `subitem-<index>`, separators counted. Its tokens: the raised surface with the subtle border and the medium radius, rows of `size().control()`, a mark column of `size().checkbox()`, the accent with text on accent for the highlight, and the hover overlay for a highlighted disabled item; it lies on `elevation().popup()`. `MenuTest` runs `menu.txt` (dropdown, keys into the submenu, choosing, the submenu after a rest, dismissing), `context.txt` (a secondary press on a row, letters, Escape), `corner.txt` (a menu in a scrolled panel in the bottom right corner, flipped above and to the left and drawn whole across the panel's clip) and `select.txt` against `Bench`, the workbench of a database tool in a window of 360 by 240, in both themes at scales 1 and 2, and compares the snaps with `golden/<script>-<dark|light>-<1|2>x-<snap>.png`.
- Elevation: menus, a select's list and tooltips lie on `Theme.elevation().popup()`, dialogs on `elevation().dialog()`, each a list of `Shadow`s for `El.shadow`: a tight one that darkens the edge and a wide one that shows the height. `Theme.DARK`'s are stronger and tighter (alpha 0.40 to 0.50, blurs of 2 to 24) than `Theme.LIGHT`'s (0.12 to 0.20, blurs of 3 to 40), since a shadow shows little on a dark surface. Both keep the subtle border, which the dark theme still needs to separate a raised surface from a panel. `TokenTest` also refuses `new Shadow(` outside `Theme.java`.
- `Tooltip.tooltip(theme, anchor, text)` shows the text below the anchor, centred, once the pointer has rested on it for `motion().tooltipDelay()`, and hides it when the pointer leaves or presses. It keeps no state: it is a delayed look of the anchor (`El.whenHoveredFor`) that attaches the overlay, and the app's clock carries the delay, so an idle window wakes for it and presents nothing while it waits. `role=tooltip` with the text as the label; the raised surface, the subtle border and the small radius. `TooltipTest` runs `tooltip.txt` against `Bench`: nothing after 0.4 seconds, the tooltip after 0.5.
- `Dialog.dialog(theme, title, content, buttons, onDismiss)` is a modal overlay in the middle of the window over `surface().scrim()`: the title as a heading, the content and the buttons at the bottom right. The app attaches it to the root while its state says it is open: `root.overlay(s.renaming ? dialog(...) : null)`. The focus goes to its first focusable element as it opens and stays inside it, Escape and a press on the scrim call `onDismiss`, and when it closes the focus goes back to where it was, with the ring it had. `role=dialog` with the title as the label. `DialogTest` runs `dialog.txt` against `Bench`: opened with the keyboard, Tab around the field and both buttons, Escape, the ring back on the button that opened it.
- `Select.select(theme, choices, selected, state, onSelect)` is a button with the current choice and `▼` that opens a `Menu` of the choices below it, at least as wide as itself (`Style.Align.STRETCH`), the current one checked and highlighted; a press, Enter, Space, Arrow Down and Arrow Up open it. `role=combobox` with the choice as the label; the text field's frame and width.
- The gallery, `kit/src/test/resources/golden/gallery-<dark|light>-<1|2>x.png`, is a sheet with a row per component and variant and a column per state: rest, hover, pressed, focused (by the keyboard, with the ring) and disabled. Each cell is an app of its own that a script brings into its state; its `.tree.txt` holds the tree of each cell. A row may add steps after each state's, as `field selected` selects a word. The text field's rows show it empty with its placeholder, with text, and with a selection; pressed and focused show the caret. Kit's tests copy core's golden helper, whose test sources they do not see.

## Modules

| Directory              | Artifact                          | Base package          | Purpose                                                   |
| ---------------------- | --------------------------------- | --------------------- | --------------------------------------------------------- |
| `core/`                | `rethinkj-ui-core`                | `com.rethinkj.ui`     | The framework itself                                      |
| `kit/`                 | `rethinkj-ui-kit`                 | `com.rethinkj.ui.kit` | Components built on `core`                                |
| `natives-windows-x64/` | `rethinkj-ui-natives-windows-x64` | none, no Java code    | SDL3, wgpu-native, FreeType and HarfBuzz for Windows on x64 |
| `natives-linux-x64/`   | `rethinkj-ui-natives-linux-x64`   | none, no Java code    | SDL3, wgpu-native, FreeType and HarfBuzz for Linux on x64   |
| `fonts/`               | `rethinkj-ui-fonts`               | none, no Java code    | Inter and JetBrains Mono, the default fonts               |
| `icons/`               | `rethinkj-ui-icons`               | none, no Java code    | Fluent UI System Icons, the default icon font             |
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
- Check arguments that wgpu-native panics on before calling: sizes of 0, a second surface texture while one is held, a buffer still mapped when commands using it are submitted, a command buffer submitted twice, NULL handles, a bind group entry with neither buffer nor view, a texture entry of a bind group layout without a view dimension (WebGPU would take 2D, wgpu-native panics), a texel copy with 0 bytes per row or rows per image.
- Do not bind any of the 35 functions wgpu-native leaves unimplemented (`WgpuTest` lists them), and do not rely on `wgpuInstanceWaitAny`: callbacks run inside the call or inside `wgpuDevicePoll`.
- Textures for shaders to read: `Device.createTexture` makes a 2D texture, `createTextureArray` an array of layers whose views are always 2D arrays, even of one layer. `BindGroupLayout.Entry.texture` and `textureArray` (`texture_2d<f32>`, `texture_2d_array<f32>`) bind a view that the shader reads with `textureLoad`, without a sampler: the sample type is unfilterable float, which takes every float format, `R8_UNORM` among them. `BindGroup.Entry.texture` binds the view. `Queue.writeTexture` writes a rectangle of texels into one layer from memory, rows `bytesPerRow` apart from an offset, so a region of a larger image goes without copying it out first; unlike a copy between buffer and texture, the rows need no alignment of 256.

Objects belong to the thread that created their `Instance`; closing one closes what it created, newest first. After adding or changing a struct, function or callback, run `scripts/LayoutCheck.java` (its header has the commands; it needs jextract, which stays out of the repository).

## Text

Text is shaped with HarfBuzz, rasterised with FreeType into greyscale bitmaps and drawn by `CpuRenderer` and `GpuRenderer`.

- `El.text("…")` makes a text element, a leaf that layout sizes to its shaped text. Chained calls: `.font(Font)`, `.fontSize(<logical pixels>)`, `.color(0xAARRGGBB)`, `.wrapText()`, `.ellipsis()`, `.fontFeatures("+calt")`, and `.editable(onEdit)` for a text field (see Text fields). With `.ellipsis()` a line wider than its box ends in `…` (`Text.ELLIPSIS`): as many graphemes as fit, without the spaces before it, shaped anew with the ellipsis; layout sizes the text as before, so the element needs a width or `minSize(0, 0)` to shrink below its text, as a table's cells have. `Text.shortened()` says whether a line was shortened; the tree dump keeps the whole string, and a right-to-left run is cut before it, not inside it. `TextRenderTest` stores `text-ellipsis-<1|2>x`, and `GpuRendererTest` compares them. The defaults are `Font.sans()`, 14, opaque black and no wrapping. Each line feed starts a line; with `wrapText` it also breaks where `java.text.BreakIterator` allows, and a word wider than the box stays whole. Characters the font lacks come from fallback fonts (below); a character no font has shows as the first font's glyph 0.
- `Font.sans()`, `sansBold()`, `mono()` and `monoBold()` are Inter 4.1 and JetBrains Mono 2.304, regular and bold, the static hinted TrueType files of their releases. They come in `fonts/` (`rethinkj-ui-fonts`, under `META-INF/rethinkj-ui/fonts/` with their SIL Open Font License 1.1 texts in `licenses/`); `core` uses that module only in tests, and without it these methods throw a message that names it. Other fonts: `Font.of(bytes)` or `Font.resource(owner, name)`, freed by `close()`.
- `Font.icons()` is Fluent UI System Icons Regular (MIT), the TrueType file the project publishes at its tag 1.1.345, unchanged: 2.9 MB, in `icons/` (`rethinkj-ui-icons`, under `META-INF/rethinkj-ui/icons/` with its licence in `licenses/`), loaded like the default fonts. An icon is a glyph at a private-use code point, drawn anew for some of 12, 16, 20, 24, 28, 32 and 48 pixels, each drawing at its own code point and sharp at its size. Two things set this font apart (`Font.wholePixels()`): its line is the em square, which the drawings fill, so an icon is as high as its font size (the font asks for a line gap of 0.09, which would put it a pixel low in a box a pixel too high), and its glyphs sit on whole physical pixels at every scale, also where text would use quarter pixels, so a 16-pixel drawing at scale 2 keeps strokes of two whole pixels in a box scrolled by a fraction. Icons are hinted alike under every `TextRendering`, with FreeType's auto-hinter in both directions (`Font.ICON_HINTING`), which puts the straight strokes it finds on whole pixels: the light auto-hinter that `LINUX` uses for text moves outlines vertically only and left an arrow's shaft at 16 pixels and scale 1 across two rows of pixels, 29 and 72 % covered, where the auto-hinter fills one row. A drawing whose strokes lie between two pixels in its design, such as `ADD` at 16, stays half covered under any hinting. At scale 1.5 the 16-pixel drawings have strokes of 1.5 pixels, which no position makes sharp.
- `Font.shape(text, size, scale, features...)` shapes with HarfBuzz into a `GlyphRun` in physical pixels: at `size * scale`, advances and offsets rounded to whole pixels below scale 1.5 and to quarter pixels from 1.5 on. Kerning is on and `calt` off (JetBrains Mono's ligatures); features use HarfBuzz's syntax, such as `"+calt"` or `"-kern"`. Each font keeps the last 1024 runs.
- Fallback fonts: `font.withFallback(Font...)` is a font that falls back to those fonts in order, `font.withSystemFonts()` one that then falls back to the fonts installed on the system; both are the same object for the same arguments, own no native objects, and keep the first font's metrics, so the line height stays its own. `Runs` splits the text into runs of one font and one script, without native libraries: each grapheme cluster (`BreakIterator.getCharacterInstance`: a letter with its marks, an emoji sequence, a flag, a variation selector with its base) goes to the first font that has all its characters but joiners, variation selectors, format and control characters (or all of its NFC form), else to the first font; neutrals (scripts Common and Inherited: spaces, punctuation, digits) the first font has stay with the font of the clusters on both sides when that is one font that has them. Each run is shaped on its own with HarfBuzz, in its script (`hb_buffer_guess_segment_properties` on the run alone) and direction, with the whole string as context, at the element's size, and the runs follow each other on one baseline in the order of the string. A glyph's font is `GlyphRun.font(i)`, which the scene passes to the atlas; the renderers did not change.
- Right to left: a run of Arabic, Hebrew and other right-to-left scripts is shaped right to left, so its letters join and stand in their order, and a neutral is right to left only between two right-to-left letters, not next to a number, whose digits stay left to right. Lines are not reordered by the bidirectional algorithm: runs follow each other from left to right. A right-to-left run is one cluster (every glyph at its first character), so a line does not break inside it, and in a field the caret moves through it from its left edge in proportion to its graphemes.
- The system's fonts (`text.SystemFonts`, through FreeType, without fontconfig or DirectWrite): the first cluster that no font of a chain has walks `/usr/share/fonts`, `/usr/local/share/fonts`, `$XDG_DATA_HOME/fonts` (or `~/.local/share/fonts`) and `~/.fonts` on Linux, `%WINDIR%\Fonts` and `%LOCALAPPDATA%\Microsoft\Windows\Fonts` on Windows, maps each `.ttf`, `.otf`, `.ttc` and `.otc` in turn and keeps each face's family, style and characters as ranges; it runs once per process, and the lookups are kept. Faces without outlines are left out (CBDT, COLR version 1). A cluster gets the first face with all its characters by the platform's preferred families for its script (Linux: Noto Sans, Noto Sans Symbols and Symbols 2, the Noto Sans family of the script, such as Noto Sans Arabic, DejaVu Sans, Noto Emoji, Symbola; Windows: Segoe UI, Segoe UI Symbol, Segoe UI Emoji, Leelawadee UI, Nirmala UI, Segoe UI Historic; emoji first to the emoji families), then the style nearest the text's font. The CJK families (Noto Sans CJK, or Yu Gothic UI, Microsoft YaHei UI, Microsoft JhengHei UI and Malgun Gothic) come first for Han, kana and Hangul, ordered by `Locale.getDefault()` (Japanese for other languages), with Korean first for Hangul and Japanese for kana. Private-use and unassigned characters are never looked up. A chosen face is mapped and opened as a `Font` for the process; the others are unmapped after the scan. On a Linux desktop with 408 font files in the file cache, the scan takes 0.15 to 0.25 s in a fresh process (up to 0.45 s while tests run beside it), and the index holds about 0.75 MB; on Windows 11 with 145 files it takes 0.2 s with the files in the cache and 1.1 s without, and holds 0.66 MB. So that no frame waits for it, `App.systemFonts(true)`, which a `WindowLoop` calls when it starts, begins the scan on a virtual thread (`Font.prepareSystemFonts`); a lookup before it ends waits for the rest. In a window on Windows that took the first Japanese character typed from 139 ms to 12 ms between the key and its frame.
- `App.systemFonts(boolean)` switches system fallback for the app's text; it is off, so scripts, `Headless` and tests draw the same everywhere and never scan, and a `WindowLoop` turns it on when it starts unless `loop.systemFonts(false)`. Stored images use only explicit chains: `Examples.fallbackChain()` is Inter with JetBrains Mono (its box drawing and maths), Noto Sans Hebrew and Noto Sans Thai, small OFL fonts in `core/src/test/resources/fonts`.
- Colour: glyphs are coverage in one colour, so a colour font draws only where its glyphs have outlines, in the text colour (the base glyphs of COLR version 0, as in Segoe UI Emoji, whose COLR table on Windows 11 is version 1 with version 0's records and outlines for its base glyphs; monochrome fonts such as Noto Emoji or DejaVu Sans). This FreeType has COLR layers but no PNG, so CBDT bitmaps (Noto Color Emoji) fail to load (error 7) and are left out; COLR version 1 needs a painter of its own and SVG glyphs a renderer. Colour emoji would need an RGBA atlas and a third kind of instance besides that.
- `App` and `Headless` pass the window's scale, and `App` whether to use the system's fonts, to every text element before layout (`El.scaleText`); `Flex.layout` alone shapes at scale 1. After layout, `el.text().lines()` holds what a renderer draws: per line a `GlyphRun`, its width and its baseline, in physical pixels from the top left of the content box. Layout hands the final content size to `Measure.placed`, and the lines are broken for that width.
- In a `.tree.txt`, a text element's line starts with `text` instead of `el` and has its string in quotes after the key, role and label: `text #save "Save\nall" 0,0 32x36`. Strings and labels are quoted with `\"`, `\\`, `\n` for a line feed and `\u{E0EB}` for a character of a private use area, such as an icon, which would otherwise show as nothing or a box. The selector `text="<string>"` matches the exact string, written the same way.
- `Scene` turns the glyphs of each line into glyph instances, `KIND` 1, in the same list as the rectangles (`KIND` 0), so text and boxes overlap in tree order. Their 64 bytes, the WGSL struct and the coverage formula are in `Scene`'s Javadoc; `CpuRenderer` is the reference. A glyph's bitmap is drawn one texel to one physical pixel, never resampled: the content box of a text element is snapped to the grid of the glyph positions, whole pixels vertically and, horizontally, whole pixels below scale 1.5 and quarter pixels from there, so text in a moving or scrolled box keeps its bitmaps.
- Bitmaps live in a `text.Atlas`: pages of 8-bit coverage (`scene.pages()`), keyed by font, physical size, hinting, glyph and quarter-pixel offset, bounded in entries and bytes, least recently used out first but never what the current scene uses. `Page.takeChanged()` gives the region written since the last call, for uploads; the GPU renderer takes it, so it must be the only one that does for an atlas. An `App` keeps one atlas; `Headless.render` and `Scene.of(root)` make their own.
- `TextRendering` holds the hinting and the two exponents of the coverage correction: `LINUX` (FreeType's light auto-hinter, 1.8 and 1.8) and `WINDOWS` (the font's own hints in FreeType's v40 interpreter, 1.8 and 1.4); `TextRendering.platform()` is the default. Set it with `app.textRendering(...)` or `Headless.render(root, width, height, scale, rendering)`. Tests that compare images pin `TextRendering.LINUX`, and so does `Examples.main`, so that the stored images do not depend on the machine.
- The correction thickens text darker than its background, `1 - (1 - a)^darkGamma`, and thins lighter text, `a^lightGamma`. The scene finds the background in the fills of the text element and its ancestors; it misses earlier siblings, absolutely placed boxes, borders and what lies outside rounded corners, and then decides by the text colour, biased towards thickening (thicken up to L* 60, thin from 80). When the answer is wrong, state the background: `El.text("…").textBackground(0xFFFFFFFF)`.
- The switch and list examples have text, so the tests that render them call `TextLibraries.assumeLoaded()`; tests about input, focus and motion use `Examples.switchAlone`, which has none and runs without the natives module. The views behind the stored text images are in `Examples` (`textSizes`, `greyOnLight`, `textOpacity`, `scrolledText`), so that the image tests and `GpuRendererTest` draw the same; `Examples.laidOut` lays out a view with its text shaped for a scale, as `Headless.render` does.
- The bindings are in `com.rethinkj.ui.text.ffi`, not exported, in the style of `com.rethinkj.ui.gpu`. FreeType's C `long` (64 bits on Linux, 32 on Windows) is handled only in `FreeType`: `C_LONG`, its method handles, which take a Java `long` for it, and `getLong`/`setLong`. Tests that need the libraries skip themselves where there is no natives module.

## GPU renderer

- `render.GpuRenderer` draws a `Scene` with wgpu. Its WGSL is the constant `GpuRenderer.WGSL`, and every line of the fragment shader has a counterpart in `CpuRenderer`: change both together. The instance block and the clip table go unchanged into two read-only storage buffers, and one instanced draw call, four vertices per instance as a triangle strip, draws all instances in order, rectangles, glyphs and shadows alike. The buffers double when a scene needs more and are kept for the next frame. The shader declares the slots in which the kinds differ as `u32` and bitcasts them to `f32` where they hold floats: a glyph's small integers read as floats would be subnormals, which a GPU may flush to zero.
- Shadows (`El.shadow(Shadow...)`, CSS's `box-shadow` without `inset`: offset, blur, spread and colour, the first on top) are a third kind of instance, `Scene.SHADOW`, drawn right before their element with its clip and opacity, over the shape grown by three deviations. A shadow shows also under a translucent fill, and it does not spring with `motion`. Its coverage is an analytic Gaussian blur of the rounded rectangle, evaluated per pixel with no offscreen pass or texture: two crossed boxes, whose blur is a product of `erf` differences and exact, and four quarter discs at the corners, each in eight slices of exact Gaussian mass across within three deviations; the deviation is half the blur radius with a pixel's share added (variance 1/12), so a shadow without blur gets an antialiased edge. `erf` is Abramowitz and Stegun's 7.1.28, arithmetic only and within 3e-7, written the same in WGSL and `CpuRenderer`, where the only other function is `sqrt`. `ShadowTest` compares the formula with a brute-force sum over a grid of cells: square corners within 1e-5, rounded ones within 0.006 (radius 0, small, half the height, blur below and above the radius, a spread, a menu's tight layer). The CPU draws a grid frame as fast as before (the kind is chosen per instance); a dialog of 400 by 240 with the dark theme's two shadows adds 23 ms at scale 1 and 94 ms at scale 2 to a CPU frame. Within three deviations at most 0.14 % of the colour is cut off, below a step for a dark shadow; a light glow on black can show that edge faintly. Gradients of a dark shadow on a dark surface step by single 8-bit levels, a few physical pixels apart, as in browsers; dithering in both renderers would hide them.
- A glyph's quad covers exactly its texels, from the physical pixel `floor(x * scale + 0.5)` on, as the CPU's `Math.round`; the fragment reads its texel with `textureLoad`, never a sampler. The atlas pages are the layers of one `r8unorm` texture array, page `i` in layer `i`: one array keeps the single draw call, since WGSL cannot choose among bound textures by a per-instance index. Before each draw the renderer writes into each layer the region `Page.takeChanged()` reports, nothing for an unchanged page, and a page it has not seen in that layer whole, once. More pages than layers, or pages of another size, replace the array by one with the next power of two of layers (at most `maxTextureArrayLayers`, 256 or more), and every page is written whole again. `GpuPresenter.atlasUploads()` counts the writes; a frame without new glyphs, and an idle window, upload nothing.
- It draws only into sRGB targets, with premultiplied blending (`one, one-minus-src-alpha`), so that the GPU blends in linear light and the target encodes: `RGBA8_UNORM_SRGB` for images, `BGRA8_UNORM_SRGB` (or `RGBA8_UNORM_SRGB`) for windows. `GpuRenderer.create()` and `render(scene, width, height, scale)` draw into `Pixels` without a window, like `CpuRenderer.render`.
- `render.GpuPresenter` is the `WindowLoop.Presenter` that owns a window's surface (Direct3D 12 on Windows, Vulkan on Linux, FIFO, opaque) and configures it with the window's size in physical pixels. It draws nothing while the window is minimized. When the surface is out of date, lost or times out, it reports `pending()`, and the loop offers the newest frame again a refresh later; the presenter then reads the window's size again. Close it before the window.
- `GpuRendererTest` compares the GPU's images with the CPU's: the views behind every stored image of core, which it builds as their tests and scripts do and checks against the stored PNG first (`theCasesIncludeTheViewBehindEveryStoredImageOfCore` fails when a stored image has no case), and scenes that stress one formula each, at scales 1, 1.5 and 2. The text scenes have 11 to 14 pixels in both fonts on light, dark and grey with both `TextRendering`s, opacity, rounded clips, translucent boxes over and under text, an atlas of 64-texel pages that a frame fills several of, and empties for the next, text fields with a selection, a caret and a composition, scrolled and in the rows of a list, and fallback fonts in lines and a field (`Examples.fallbackText`, `fallbackField`). Overlays are there too: a menu with its submenu across the rounded clip of a scrolled list, and a dialog over a scrim, all with shadows. So are shadows of their own (`shadows`): on light and dark, under text and translucent boxes, cut by a rounded clip, at half opacity, overlapping, wide, sharp, coloured and three on one element; `aWrongShadowShaderIsNoticed` makes the blur a tenth wider, leaves out the pixel's share or the corners, takes the slices at their top and leaves out the clip, and each fails hundreds to thousands of pixels. The kit's data grid is there too: core's tests cannot see the kit, so `Examples.grid` builds the grid of `Orders` as `DataGrid` does, from core's elements in `Theme.DARK`'s values; the test first checks that it draws the kit's stored images `grid-dark-*` from kit's `grid.txt`, then compares at scales 1.5 and 2. A change of the grid's look shows there, and `Examples.grid` follows it. A channel may differ by 1 step of 8 bits, and by half a step more for each further instance that draws into the pixel, because the GPU rounds into the 8-bit target after every draw (`tolerance` says why). On top of that comes what the GPU's blending can add: it takes the source's alpha at the 8 bits of the target, so a layer lets up to 1/255 more or less of the light under it through (`ALPHA_STEP`); under nearly opaque dark text on white that measured up to 4 steps near black, and the test adds what it can be at each pixel, from the CPU's blends (`CpuRenderer.Blends`). `aWrongGlyphShaderIsNoticed` breaks the texel mapping, the reading of texels, the correction and the clip one at a time; each fails thousands of pixels. A failing scene leaves `.cpu.png`, `.gpu.png` and `.diff.png` in `core/target/gpu-vs-cpu`. The test is skipped without the natives module or an adapter.

## Windows

- `com.rethinkj.ui.window` opens windows through SDL 3 (`Window`), turns SDL's events into `WindowEvent`s and runs an `App` in a window (`WindowLoop`), which hands each new frame to a `Presenter`. `Sdl` and `Win32` hold the handwritten bindings: a new function gets its descriptor as a constant there and its handle in the class's lazy holder.
- Windows are used on the thread that opened the first one, until all are closed. Every event is translated in an SDL event watch as SDL queues it (a wheel that turns only up and down with Shift held as one that turns sideways, as in the system's own apps) and delivered in that order by `Window.pollEvents` or `waitEvents`; SDL's own queue only ends the wait. On Windows the listener is also called from inside the system's move and resize loop, with `Redraw(live = true)`.
- The hit test, the event watch and the Windows subclass are upcalls: each catches every `Throwable` and hands it to `Window.fail`, and the next `pollEvents` or `waitEvents` throws it. One that escapes ends the JVM.
- Text input is off until `Window.textInputArea` turns it on, which `WindowLoop` does while the focused element takes text (`App.inputArea`), and `stopTextInput` turns it off. `WindowLoop` also gives the app the system clipboard (`SystemClipboard`, through SDL) and the system's fonts (`App.systemFonts`), passes the click count and modifiers of a press (a second press counts as a double one within the system's distance, half of `SM_CXDOUBLECLK` on Windows and 5 pixels elsewhere, which `Window` sets as SDL's `SDL_MOUSE_DOUBLE_CLICK_RADIUS` in place of its 32 for touch screens), and wakes an idle window for the caret's blink (`App.nextChange`).
- A frameless window draws its own title bar: the view marks elements with `El.windowPart`, and `App.windowPart` answers SDL's hit test. Where a press would go to a control (`WindowPart.CONTROL`: an element with a press or drag handler, a text field, a scrollbar's strip) or to a minimize, maximize or close part, the resize margin narrows to `Window.CONTROL_MARGIN`, 2 pixels, so that a scrollbar along the window's edge can be grabbed at its outer pixels; the corners keep their reach. The Windows 11 parts (rounded corners, snap layouts on the maximize part, system menu on a right click) are in `Win32Frame`, whose Javadoc also says why the resize margin lies inside the window. `Win32Frame` also gives a minimized frameless window no client area, as Windows does for a framed one; otherwise SDL reports the 160 by 28 pixels of a minimized window when the system restores it.
- Tests that open windows skip themselves without the natives module or a display. The demo in the test sources draws the light switch with its label with `GpuPresenter`: build with `mvn -pl core -am test-compile`, then from the repository root `java --enable-native-access=ALL-UNNAMED -cp "core/target/classes;core/target/test-classes;natives-windows-x64/target/classes;fonts/target/classes" com.rethinkj.ui.window.WindowDemo` (on Linux, with `:` between the entries and `natives-linux-x64`). It prints every frame it presents with the atlas uploads so far; `--cpu-png=<file>` writes the CPU's image of the last frame when it stops, to compare with a screenshot of the window, and `--text=linux` or `--text=windows` overrides the platform's `TextRendering`. `--exit-after=<seconds>` and its other options are in its Javadoc; keep windows short-lived when you drive them with synthetic input. SDL drops the click that activates a window, so click once on the background first. Windows 11 rounds the corners of a frameless window and draws a 1-pixel border over its edge, so leave the edge out of such a comparison.

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
