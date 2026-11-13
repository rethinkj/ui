package com.rethinkj.ui.gpu;

/**
 * An error wgpu reported: a validation error, running out of memory, a lost device, a failed
 * request. It carries wgpu's text unchanged, which names the wgpu function and repeats the label,
 * and for WGSL errors gives line and column. The message adds what the layer was doing and the
 * label of the object it was doing it to.
 *
 * <p>Errors in arguments that the layer catches before calling wgpu are {@link
 * IllegalArgumentException} or {@link IllegalStateException} instead.
 */
public final class GpuException extends RuntimeException {

  /** What kind of error wgpu reported. */
  public enum Kind {
    /** {@code WGPUErrorType_Validation}: the call was not valid. */
    VALIDATION("Validation error"),
    /** {@code WGPUErrorType_OutOfMemory}. */
    OUT_OF_MEMORY("Out of memory"),
    /** {@code WGPUErrorType_Internal}. */
    INTERNAL("Internal error"),
    /** {@code WGPUErrorType_Unknown}, or a value this layer does not know. */
    UNKNOWN("Unknown error"),
    /** The device was lost; it cannot be used any more. */
    DEVICE_LOST("Device lost"),
    /** No adapter of the backend fits the request. */
    ADAPTER_UNAVAILABLE("No adapter"),
    /** wgpu refused to create the adapter or the device. */
    REQUEST_FAILED("Request failed"),
    /** Mapping a buffer for reading failed. */
    MAP_FAILED("Map failed"),
    /** The surface could not present its frame. */
    PRESENT_FAILED("Present failed");

    private final String text;

    Kind(String text) {
      this.text = text;
    }
  }

  private final Kind kind;
  private final String label;
  private final String wgpuMessage;

  /**
   * @param what what the layer was doing, such as "creating shader module"
   * @param label the label of the object it was doing it to
   * @param wgpuMessage what wgpu said, unchanged
   */
  GpuException(Kind kind, String what, String label, String wgpuMessage) {
    super(kind.text + " while " + what + " '" + label + "'"
        + (wgpuMessage.isEmpty() ? "" : ":\n" + wgpuMessage));
    this.kind = kind;
    this.label = label;
    this.wgpuMessage = wgpuMessage;
  }

  public Kind kind() {
    return kind;
  }

  /** The label of the object the error is about. */
  public String label() {
    return label;
  }

  /** wgpu's text as it came, possibly over several lines; empty if wgpu gave none. */
  public String wgpuMessage() {
    return wgpuMessage;
  }

  /** The kind for a {@code WGPUErrorType}. */
  static Kind kindOf(int errorType) {
    return switch (errorType) {
      case Wgpu.ERROR_TYPE_VALIDATION -> Kind.VALIDATION;
      case Wgpu.ERROR_TYPE_OUT_OF_MEMORY -> Kind.OUT_OF_MEMORY;
      case Wgpu.ERROR_TYPE_INTERNAL -> Kind.INTERNAL;
      default -> Kind.UNKNOWN;
    };
  }
}
