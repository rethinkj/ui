package com.rethinkj.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rethinkj.ui.kit.DataGrid.Range;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Longer histories with sorting, filtering and clear, independent of the implementation's ops. */
class GridChangesHistoryTest {
  private static final long TOP = Long.MIN_VALUE;

  record Added(long key, long anchor) {}

  record Pending(Map<Long, Map<Integer, String>> values, Set<Long> deleted, List<Added> added) {
    Pending copy() {
      Map<Long, Map<Integer, String>> v = new HashMap<>();
      values.forEach((key, cells) -> v.put(key, new HashMap<>(cells)));
      return new Pending(v, new HashSet<>(deleted), new ArrayList<>(added));
    }
  }

  private static String base(long key, int column) {
    return (key + column) % 4 == 0 ? null : key + ":" + column;
  }

  private static GridChanges.Rows rows(List<Long> keys) {
    return new GridChanges.Rows() {
      public int count() {
        return keys.size();
      }

      public long key(int row) {
        return keys.get(row);
      }

      public int place(long key) {
        return keys.indexOf(key);
      }

      public String cell(int row, int column) {
        return base(keys.get(row), column);
      }
    };
  }

  private static List<Long> shown(Pending p, List<Long> keys) {
    List<Long> shown = new ArrayList<>();
    for (int position = -1; position < keys.size(); position++) {
      if (position >= 0) {
        shown.add(keys.get(position));
      }
      for (Added row : p.added()) {
        int anchor = row.anchor() == TOP ? -1 : keys.indexOf(row.anchor());
        // An anchor not shown puts its new rows at the end, in their existing order.
        if (row.anchor() != TOP && anchor < 0) {
          anchor = keys.size() - 1;
        }
        if (anchor == position) {
          shown.add(row.key());
        }
      }
    }
    return shown;
  }

  @Test
  void longRunsMatchTheModelAcrossHistoryLimitsClearSortAndFilter() {
    for (int seed = 0; seed < 8; seed++) {
      Random random = new Random(seed);
      List<Long> keys = new ArrayList<>(List.of(0L, 1L, 2L, 3L, 4L, 5L));
      Pending p = new Pending(new HashMap<>(), new HashSet<>(), new ArrayList<>());
      List<Pending> undo = new ArrayList<>();
      List<Pending> redo = new ArrayList<>();
      long next = -1;
      GridChanges changes = new GridChanges(rows(keys));
      for (int step = 0; step < 500; step++) {
        List<Long> shown = shown(p, keys);
        Pending before = p.copy();
        int what = random.nextInt(20);
        if (what < 8 && !shown.isEmpty()) {
          int row = random.nextInt(shown.size());
          long key = shown.get(row);
          int column = random.nextInt(3);
          String value = switch (random.nextInt(5)) {
            case 0 -> null;
            case 1 -> "";
            case 2 -> key < 0 ? null : base(key, column);
            default -> "v" + random.nextInt(4);
          };
          changes.set(row, column, value);
          if (!p.deleted().contains(key)) {
            Map<Integer, String> cells = p.values().computeIfAbsent(key, k -> new HashMap<>());
            if (key >= 0 && Objects.equals(value, base(key, column))) {
              cells.remove(column);
            } else {
              cells.put(column, value);
            }
            if (cells.isEmpty()) {
              p.values().remove(key);
            }
          }
        } else if (what < 11) {
          int after = random.nextInt(shown.size() + 1) - 1;
          changes.insert(after);
          long key = after < 0 ? TOP : shown.get(after);
          long anchor = key;
          int index = p.added().size();
          if (key == TOP) {
            index = 0;
          } else if (key < 0) {
            for (int i = 0; i < p.added().size(); i++) {
              if (p.added().get(i).key() == key) {
                anchor = p.added().get(i).anchor();
                index = i + 1;
                break;
              }
            }
          } else {
            for (int i = 0; i < p.added().size(); i++) {
              if (p.added().get(i).anchor() == key) {
                index = i;
                break;
              }
            }
          }
          p.added().add(index, new Added(next--, anchor));
        } else if (what < 13 && !shown.isEmpty()) {
          int top = random.nextInt(shown.size());
          int bottom = Math.min(shown.size() - 1, top + random.nextInt(4));
          changes.delete(List.of(new Range(top, 0, bottom, Range.ALL)));
          List<Long> selected = shown.subList(top, bottom + 1);
          boolean all = true;
          for (long key : selected) {
            all &= key < 0 || p.deleted().contains(key);
          }
          for (long key : selected) {
            if (key < 0) {
              p.added().removeIf(a -> a.key() == key);
              p.values().remove(key);
            } else if (all) {
              p.deleted().remove(key);
            } else {
              p.deleted().add(key);
            }
          }
        } else if (what < 16) {
          changes.undo();
          if (!undo.isEmpty()) {
            redo.add(p.copy());
            p = undo.removeLast();
          }
          verify(changes, p, keys, seed, step);
          continue;
        } else if (what < 18) {
          changes.redo();
          if (!redo.isEmpty()) {
            undo.add(p.copy());
            p = redo.removeLast();
          }
          verify(changes, p, keys, seed, step);
          continue;
        } else if (what == 18) {
          changes.clear();
          p = new Pending(new HashMap<>(), new HashSet<>(), new ArrayList<>());
          undo.clear();
          redo.clear();
          verify(changes, p, keys, seed, step);
          continue;
        } else {
          keys.clear();
          for (long key = 0; key < 6; key++) {
            if (random.nextBoolean()) {
              keys.add(key);
            }
          }
          Collections.shuffle(keys, random);
          changes.rows(rows(keys));
          verify(changes, p, keys, seed, step);
          continue;
        }
        if (!before.equals(p)) {
          undo.add(before);
          if (undo.size() > 100) {
            undo.removeFirst();
          }
          redo.clear();
        }
        verify(changes, p, keys, seed, step);
      }
    }
  }

  @Test
  void onlyTheLastHundredChangesCanBeUndoneAndAllCanBeRedone() {
    GridChanges changes = new GridChanges(1, (r, c) -> "0");
    for (int i = 1; i <= 120; i++) {
      changes.set(0, 0, Integer.toString(i));
    }
    for (int i = 0; i < 110; i++) {
      changes.undo();
    }
    assertEquals("20", changes.cell(0, 0));
    for (int i = 0; i < 110; i++) {
      changes.redo();
    }
    assertEquals("120", changes.cell(0, 0));
  }

  private static void verify(GridChanges c, Pending p, List<Long> keys, int seed, int step) {
    String where = "seed=" + seed + " step=" + step + " keys=" + keys + " pending=" + p;
    List<Long> shown = shown(p, keys);
    assertEquals(shown.size(), c.rowCount(), where);
    for (int row = 0; row < shown.size(); row++) {
      long key = shown.get(row);
      assertEquals(key, c.key(row), where);
      assertEquals(row, c.rowOf(key), where);
      for (int column = 0; column < 3; column++) {
        Map<Integer, String> values = p.values().getOrDefault(key, Map.of());
        String value =
            values.containsKey(column) ? values.get(column) : key < 0 ? null : base(key, column);
        assertEquals(value, c.cell(row, column), where);
      }
    }
    int count = p.deleted().size() + p.added().size();
    for (long key : p.values().keySet()) {
      if (key >= 0 && !p.deleted().contains(key)) {
        count++;
      }
    }
    assertEquals(count, c.count(), where);
    for (var change : c.changes()) {
      if (change.kind() != GridChanges.Kind.DELETE) {
        assertEquals(p.values().getOrDefault(change.key(), Map.of()), change.values(), where);
      }
    }
  }
}
