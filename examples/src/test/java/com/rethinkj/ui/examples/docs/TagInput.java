package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.El.row;

import com.rethinkj.ui.Colors;
import com.rethinkj.ui.El;
import com.rethinkj.ui.Key;
import com.rethinkj.ui.Shadow;
import com.rethinkj.ui.Style;
import com.rethinkj.ui.kit.Badge;
import com.rethinkj.ui.kit.Prop;
import com.rethinkj.ui.kit.Theme;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A component of the app's own, written as the kit writes its components: tags as badges with a
 * cross that removes each, and a field after them in which Enter adds a tag and Backspace on an
 * empty field removes the last. The guide "Write your own component" shows it.
 */
final class TagInput {

  private TagInput() {}

  // docs:start tag-state
  /** What the input keeps between builds besides the tags: the text typed for the next tag. */
  static final class State {
    String draft = "";

    void draft(String value) {
      draft = value;
    }
  }

  // docs:end tag-state

  // docs:start tag-input
  /**
   * @param tags the tags, from the app's state
   * @param placeholder shown in the field while it is empty, also its label
   * @param state the draft, from the app's state
   * @param onChange receives the new list when a tag is added or removed; null disables the input
   */
  static El tagInput(
      List<String> tags, String placeholder, State state, Consumer<List<String>> onChange) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onChange != null;
    El input = row()
        .role("group")
        .value(String.join(", ", tags))
        .disabled(!enabled)
        .wrap()
        .align(Style.Align.CENTER)
        .gap(theme.space().xs())
        .w(theme.size().field());
    for (int index = 0; index < tags.size(); index++) {
      input.child(chip(tags, index, onChange));
    }
    input.child(field(tags, placeholder, state, onChange));
    return looks(input, enabled);
  }

  // docs:end tag-input

  // docs:start tag-looks
  /** A tag, with a cross that removes it while the input is enabled. */
  private static El chip(List<String> tags, int index, Consumer<List<String>> onChange) {
    Badge.Options options = Badge.options();
    if (onChange != null) {
      options.onRemove(() -> onChange.accept(without(tags, index)));
    }
    return Badge.badge(tags.get(index), options)
        .key("tag-" + tags.get(index)); // by text: it keeps its identity
  }

  /** The frame around tags and field, in the look of the kit's text fields. */
  private static El looks(El input, boolean enabled) {
    Theme theme = Theme.CURRENT.get();
    float xs = theme.space().xs();
    int fill = theme.surface().raised();
    input
        // as high as a text field with one row of tags; the bottom's padding stands in for a border
        .padding(xs, xs, xs + theme.size().stroke(), xs)
        .radius(theme.radius().medium())
        .fill(fill)
        .motion(theme.motion().feedback());
    if (!enabled) {
      return edge(input, theme.border().divider(), theme.border().divider());
    }
    int hover = Colors.over(fill, theme.surface().hover());
    return edge(input, theme.border().control(), theme.border().strong())
        .whenHovered(element -> element.fill(hover))
        .whenFocusWithin(
            element -> edge(element, theme.accent().color(), theme.accent().color()));
  }

  /** A border at the top and sides, and a line along the bottom, as a text field has. */
  private static El edge(El input, int frame, int bottom) {
    Theme theme = Theme.CURRENT.get();
    float stroke = theme.size().stroke();
    return input
        .border(stroke, stroke, 0, stroke, frame)
        .shadow(new Shadow(0, stroke, 0, 0, bottom));
  }

  // docs:end tag-looks

  // docs:start tag-keys
  /** The field for the next tag: Enter adds it, Backspace on an empty field removes the last. */
  private static El field(
      List<String> tags, String placeholder, State state, Consumer<List<String>> onChange) {
    Theme theme = Theme.CURRENT.get();
    boolean enabled = onChange != null;
    Theme.TextStyle body = theme.type().body();
    return El.text(state.draft)
        .editable(enabled ? state::draft : null)
        .key("draft")
        .label(placeholder)
        .placeholder(placeholder, enabled ? theme.text().muted() : theme.text().disabled())
        .font(body.font().get())
        .fontSize(body.size())
        .color(enabled ? theme.text().primary() : theme.text().disabled())
        .selectionColor(theme.accent().selection())
        .h(theme.badge().row())
        .w(theme.size().field() / 3) // at least this wide, or it wraps onto the next row
        .grow(1)
        .padding(0, theme.space().m() - theme.space().xs())
        .onKey(key -> key(key, tags, state, onChange));
  }

  private static boolean key(
      Key key, List<String> tags, State state, Consumer<List<String>> onChange) {
    if (key.modifiers() != 0) {
      return false;
    }
    String tag = state.draft.strip();
    if (key.name().equals("Enter") && !tag.isEmpty()) {
      if (!tags.contains(tag)) {
        List<String> more = new ArrayList<>(tags);
        more.add(tag);
        onChange.accept(List.copyOf(more));
      }
      state.draft = "";
      return true;
    }
    if (key.name().equals("Backspace") && state.draft.isEmpty() && !tags.isEmpty()) {
      onChange.accept(without(tags, tags.size() - 1));
      return true;
    }
    return false; // the field edits its text as usual
  }

  private static List<String> without(List<String> tags, int index) {
    List<String> fewer = new ArrayList<>(tags);
    fewer.remove(index);
    return List.copyOf(fewer);
  }
  // docs:end tag-keys

  // docs:start tag-prop
  /**
   * A tag input of a {@link Prop}, such as a field of an editing: its tags, written back on each
   * change, with the field's error. A prop that cannot be written disables it.
   */
  static El tagInput(Prop<List<String>> tags, String placeholder, State state) {
    Theme theme = Theme.CURRENT.get();
    List<String> now = tags.get() == null ? List.of() : tags.get();
    El input = tagInput(now, placeholder, state, tags.onChange());
    String error = tags.error();
    if (error == null) {
      return input;
    }
    int danger = theme.status().danger();
    return edge(input.invalid(true).errorMessage(error), danger, danger).whenFocusWithin(null);
  }
  // docs:end tag-prop
}
