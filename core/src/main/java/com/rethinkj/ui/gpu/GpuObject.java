package com.rethinkj.ui.gpu;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * A wgpu object owned by Java code: a handle, the label it was created with and the object that
 * created it. Objects form a tree with the {@link Instance} at its root. Closing an object closes
 * the objects it created first, newest first, and then releases its own handle, so wgpu never sees
 * a handle after it was released, and a child is never released after its parent.
 *
 * <p>All objects of an instance belong to the thread that created the instance; using one from
 * another thread throws {@link WrongThreadException}. wgpu-native keeps one error-scope stack per
 * device, not per thread, so two threads that record at the same time would see each other's
 * errors.
 *
 * <p>Using an object after it was closed, or closing it again, throws {@link
 * IllegalStateException} that says what the object is and how it was closed. No closed handle
 * reaches wgpu: wgpu-native ends the process for a NULL handle.
 */
public abstract sealed class GpuObject implements AutoCloseable
    permits Instance,
        Adapter,
        Surface,
        SurfaceFrame,
        Device,
        ShaderModule,
        Buffer,
        Texture,
        TextureView,
        BindGroupLayout,
        BindGroup,
        RenderPipeline,
        CommandEncoder,
        RenderPass,
        CommandBuffer {

  final Instance instance;
  final GpuObject parent;
  final Thread owner;
  private final String label;
  private MemorySegment handle;
  /** Children in creation order; null until the first one. */
  private LinkedHashSet<GpuObject> children;
  /** Null while open; afterwards, how it was closed: "closed", "submitted", ... */
  private String closed;

  /**
   * @param parent the object whose closing closes this one, or null for an instance
   * @param handle a handle wgpu returned, not NULL
   */
  GpuObject(Instance instance, GpuObject parent, String label, MemorySegment handle) {
    this.instance = instance == null ? (Instance) this : instance;
    this.owner = instance == null ? Thread.currentThread() : instance.owner;
    this.parent = parent;
    this.label = label;
    this.handle = handle;
    if (handle.equals(MemorySegment.NULL)) {
      throw new IllegalStateException("wgpu returned NULL for " + describe(kind(), label));
    }
    if (parent != null) {
      if (parent.children == null) {
        parent.children = new LinkedHashSet<>();
      }
      parent.children.add(this);
    }
  }

  /** The label given at creation; wgpu repeats it in its error messages. */
  public final String label() {
    return label;
  }

  public final boolean isClosed() {
    return closed != null;
  }

  /** "Buffer 'quads'". */
  @Override
  public String toString() {
    return describe(kind(), label);
  }

  /**
   * Closes the objects this one created, newest first, then releases this one.
   *
   * @throws IllegalStateException if it is already closed
   */
  @Override
  public final void close() {
    checkThread();
    if (closed != null) {
      throw new IllegalStateException(this + " is already " + closed + ".");
    }
    closeTree("closed");
    if (parent != null && parent.children != null) {
      parent.children.remove(this);
    }
  }

  /** The handle, after checking that this object is open and used on its thread. */
  final MemorySegment handle() {
    checkOpen();
    return handle;
  }

  final void checkOpen() {
    checkThread();
    if (closed != null) {
      throw new IllegalStateException(this + " is " + closed + "; it cannot be used any more.");
    }
  }

  final void checkThread() {
    if (Thread.currentThread() != owner) {
      throw new WrongThreadException(this + " belongs to the thread \"" + owner.getName()
          + "\", which created its instance; it cannot be used on the thread \""
          + Thread.currentThread().getName() + "\".");
    }
  }

  /** Closes this object from inside the layer, for example once a command buffer is submitted. */
  final void closeAs(String how) {
    if (closed == null) {
      closeTree(how);
      if (parent != null && parent.children != null) {
        parent.children.remove(this);
      }
    }
  }

  /** The objects this one created that are still open, in creation order. */
  final List<GpuObject> children() {
    return children == null ? List.of() : List.copyOf(children);
  }

  /** "Buffer", "Texture view": what the object is, for messages. */
  abstract String kind();

  /** Releases the handle with the release function of its type. */
  abstract void release(MemorySegment handle);

  /** Runs before the children are closed; for objects that must undo something in wgpu first. */
  void beforeClose() {}

  private void closeTree(String how) {
    RuntimeException failure = null;
    try {
      beforeClose();
    } catch (RuntimeException e) {
      failure = e;
    }
    if (children != null) {
      List<GpuObject> open = new ArrayList<>(children);
      children = null;
      String reason = "closed with its " + kind().toLowerCase(Locale.ROOT) + " '" + label + "'";
      for (int i = open.size() - 1; i >= 0; i--) {
        if (open.get(i).isClosed()) {
          continue; // closed by a sibling's beforeClose
        }
        try {
          open.get(i).closeTree(reason);
        } catch (RuntimeException e) {
          if (failure == null) {
            failure = e;
          } else {
            failure.addSuppressed(e);
          }
        }
      }
    }
    MemorySegment released = handle;
    handle = null;
    closed = how;
    release(released);
    instance.released(this);
    if (failure != null) {
      throw failure;
    }
  }

  static String describe(String kind, String label) {
    return kind + " '" + label + "'";
  }

  static String requireLabel(String label) {
    if (label == null) {
      throw new NullPointerException("label");
    }
    return label;
  }
}
