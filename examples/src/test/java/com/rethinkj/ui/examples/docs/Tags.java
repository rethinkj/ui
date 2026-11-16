package com.rethinkj.ui.examples.docs;

import static com.rethinkj.ui.kit.Button.Variant.PRIMARY;

import com.rethinkj.ui.El;
import com.rethinkj.ui.kit.Button;
import com.rethinkj.ui.kit.Editing;
import com.rethinkj.ui.kit.Form;
import com.rethinkj.ui.kit.Label;
import com.rethinkj.ui.kit.Part;
import com.rethinkj.ui.kit.TextField;
import com.rethinkj.ui.kit.Theme;
import java.util.List;

/** The app around {@link TagInput}: the guide "Write your own component". */
final class Tags {

  Theme theme = Theme.DARK;

  // docs:start tags-state
  List<String> tags = List.of("orders", "daily");
  final TagInput.State tagDraft = new TagInput.State();

  void tags(List<String> value) {
    tags = value;
  }
  // docs:end tags-state

  static El view(Tags state) {
    Theme theme = state.theme;
    // docs:start tags-view
    El tags = TagInput.tagInput(theme, state.tags, "Add tag", state.tagDraft, state::tags)
        .key("tags")
        .label("Tags");
    // docs:end tags-view
    return Frame.of(
        theme, tags, Label.label(theme, Label.Variant.MUTED, String.join(" · ", state.tags)));
  }

  // docs:start tags-record
  record Dataset(String name, List<String> tags) {
    static final Part<Dataset, String> NAME = Part.of(Dataset::name, Dataset::withName);
    static final Part<Dataset, List<String>> TAGS = Part.of(Dataset::tags, Dataset::withTags);

    Dataset withName(String name) {
      return new Dataset(name, tags);
    }

    Dataset withTags(List<String> tags) {
      return new Dataset(name, tags);
    }
  }

  final Editing<Dataset> dataset = Editing.of(new Dataset("orders_2026", List.of()))
      .rule(Dataset.TAGS, dataset -> !dataset.tags().isEmpty(), "Give it at least one tag");
  final TagInput.State datasetDraft = new TagInput.State();

  // docs:end tags-record

  static El form(Tags state) {
    Theme theme = state.theme;
    // docs:start tags-form
    Editing<Dataset> editing = state.dataset;
    El form = Form.form(
        theme,
        Form.Labels.BESIDE,
        Form.field("Name", TextField.textField(theme, editing.at(Dataset.NAME)).key("name")),
        Form.field(
            "Tags",
            TagInput.tagInput(theme, editing.at(Dataset.TAGS), "Add tag", state.datasetDraft)
                .key("tags")));
    El save = Button.button(
        theme, PRIMARY, "Save", editing.valid() && editing.changed() ? editing::saved : null);
    // docs:end tags-form
    return Frame.of(theme, form, Form.buttons(theme, save));
  }
}
