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
  }

  // docs:end tag-state

  // docs:start tag-input
  /**
   * @param tags the tags, from the app's state
   * @param placeholder shown in the field while it is empty, also its label
   * @param s the draft, from the app's state
   * @param onChange receives the new list when a tag is added or removed; null disables the input
   */
  static El tagInput(
      Theme t, List<String> tags, String placeholder, State s, Consumer<List<String>> onChange) {
    boolean enabled = onChange != null;
    El input = row()
        .role("group")
        .value(String.join(", ", tags))
        .disabled(!enabled)
        .wrap()
        .align(Style.Align.CENTER)
        .gap(t.space().xs())
        .w(t.size().field());
    for (int i = 0; i < tags.size(); i++) {
      input.child(chip(t, tags, i, onChange));
    }
    input.child(field(t, tags, placeholder, s, onChange));
    return looks(t, input, enabled);
  }

  // docs:end tag-input

  // docs:start tag-looks
  /** A tag, with a cross that removes it while the input is enabled. */
  private static El chip(Theme t, List<String> tags, int i, Consumer<List<String>> onChange) {
    Badge.Options o = Badge.options();
    if (onChange != null) {
      o.onRemove(() -> onChange.accept(without(tags, i)));
    }
    return Badge.badge(t, tags.get(i), o)
        .key("tag-" + tags.get(i)); // by text: it keeps its identity
  }

  /** The frame around tags and field, in the look of the kit's text fields. */
  private static El looks(Theme t, El input, boolean enabled) {
    float xs = t.space().xs();
    int fill = t.surface().raised();
    input
        // as high as a text field with one row of tags; the bottom's padding stands in for a border
        .padding(xs, xs, xs + t.size().stroke(), xs)
        .radius(t.radius().medium())
        .fill(fill)
        .motion(t.motion().feedback());
    if (!enabled) {
      return edge(t, input, t.border().divider(), t.border().divider());
    }
    int hover = Colors.over(fill, t.surface().hover());
    return edge(t, input, t.border().control(), t.border().strong())
        .whenHovered(e -> e.fill(hover))
        .whenFocusWithin(e -> edge(t, e, t.accent().color(), t.accent().color()));
  }

  /** A border at the top and sides, and a line along the bottom, as a text field has. */
  private static El edge(Theme t, El input, int frame, int bottom) {
    float w = t.size().stroke();
    return input.border(w, w, 0, w, frame).shadow(new Shadow(0, w, 0, 0, bottom));
  }

  // docs:end tag-looks

  // docs:start tag-keys
  /** The field for the next tag: Enter adds it, Backspace on an empty field removes the last. */
  private static El field(
      Theme t, List<String> tags, String placeholder, State s, Consumer<List<String>> onChange) {
    boolean enabled = onChange != null;
    Theme.TextStyle body = t.type().body();
    return El.text(s.draft)
        .editable(enabled ? v -> s.draft = v : null)
        .key("draft")
        .label(placeholder)
        .placeholder(placeholder, enabled ? t.text().muted() : t.text().disabled())
        .font(body.font().get())
        .fontSize(body.size())
        .color(enabled ? t.text().primary() : t.text().disabled())
        .selectionColor(t.accent().selection())
        .h(t.badge().row())
        .w(t.size().field() / 3) // at least this wide, or it wraps onto the next row
        .grow(1)
        .padding(0, t.space().m() - t.space().xs())
        .onKey(key -> key(key, tags, s, onChange));
  }

  private static boolean key(Key key, List<String> tags, State s, Consumer<List<String>> onChange) {
    if (key.modifiers() != 0) {
      return false;
    }
    String tag = s.draft.strip();
    if (key.name().equals("Enter") && !tag.isEmpty()) {
      if (!tags.contains(tag)) {
        List<String> more = new ArrayList<>(tags);
        more.add(tag);
        onChange.accept(List.copyOf(more));
      }
      s.draft = "";
      return true;
    }
    if (key.name().equals("Backspace") && s.draft.isEmpty() && !tags.isEmpty()) {
      onChange.accept(without(tags, tags.size() - 1));
      return true;
    }
    return false; // the field edits its text as usual
  }

  // docs:end tag-keys

  private static List<String> without(List<String> tags, int i) {
    List<String> fewer = new ArrayList<>(tags);
    fewer.remove(i);
    return List.copyOf(fewer);
  }

  // docs:start tag-prop
  /**
   * A tag input of a {@link Prop}, such as a field of an editing: its tags, written back on each
   * change, with the field's error. A prop that cannot be written disables it.
   */
  static El tagInput(Theme t, Prop<List<String>> tags, String placeholder, State s) {
    List<String> now = tags.get() == null ? List.of() : tags.get();
    El input = tagInput(t, now, placeholder, s, tags.onChange());
    String error = tags.error();
    if (error == null) {
      return input;
    }
    int danger = t.status().danger();
    return edge(t, input.invalid(true).errorMessage(error), danger, danger).whenFocusWithin(null);
  }
  // docs:end tag-prop
}
