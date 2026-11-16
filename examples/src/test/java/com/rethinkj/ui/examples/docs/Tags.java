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

  Theme t = Theme.DARK;

  // docs:start tags-state
  List<String> tags = List.of("orders", "daily");
  final TagInput.State tagDraft = new TagInput.State();

  void tags(List<String> value) {
    tags = value;
  }
  // docs:end tags-state

  static El view(Tags s) {
    Theme t = s.t;
    // docs:start tags-view
    El tags =
        TagInput.tagInput(t, s.tags, "Add tag", s.tagDraft, s::tags).key("tags").label("Tags");
    // docs:end tags-view
    return Frame.of(t, tags, Label.label(t, Label.Variant.MUTED, String.join(" · ", s.tags)));
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
      .rule(Dataset.TAGS, d -> !d.tags().isEmpty(), "Give it at least one tag");
  final TagInput.State datasetDraft = new TagInput.State();

  // docs:end tags-record

  static El form(Tags s) {
    Theme t = s.t;
    // docs:start tags-form
    Editing<Dataset> e = s.dataset;
    El form = Form.form(
        t,
        Form.Labels.BESIDE,
        Form.field("Name", TextField.textField(t, e.at(Dataset.NAME)).key("name")),
        Form.field(
            "Tags",
            TagInput.tagInput(t, e.at(Dataset.TAGS), "Add tag", s.datasetDraft).key("tags")));
    El save = Button.button(t, PRIMARY, "Save", e.valid() && e.changed() ? e::saved : null);
    // docs:end tags-form
    return Frame.of(t, form, Form.buttons(t, save));
  }
}
