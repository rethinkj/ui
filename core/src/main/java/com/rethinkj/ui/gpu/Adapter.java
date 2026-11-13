package com.rethinkj.ui.gpu;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import com.rethinkj.ui.gpu.Structs.CallbackInfo;
import com.rethinkj.ui.gpu.Structs.DeviceDescriptor;
import com.rethinkj.ui.gpu.Structs.UncapturedErrorCallbackInfo;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * A GPU, or a software renderer, on the instance's backend. It reports what it is and creates
 * devices. A device keeps what it needs of its adapter, so the adapter may be closed first.
 */
public final class Adapter extends GpuObject {

  /** What wgpu says about an adapter. */
  public record Info(
      String vendor,
      String architecture,
      String device,
      String description,
      Backend backend,
      Type type,
      int vendorId,
      int deviceId) {

    @Override
    public String toString() {
      return device + " (" + type + ", " + backend + ", vendor 0x"
          + Integer.toHexString(vendorId) + ", device 0x" + Integer.toHexString(deviceId)
          + (description.isEmpty() ? "" : ", " + description) + ")";
    }
  }

  /** {@code WGPUAdapterType}. */
  public enum Type {
    DISCRETE_GPU,
    INTEGRATED_GPU,
    CPU,
    UNKNOWN;

    static Type of(int value) {
      return switch (value) {
        case 1 -> DISCRETE_GPU;
        case 2 -> INTEGRATED_GPU;
        case 3 -> CPU;
        default -> UNKNOWN;
      };
    }
  }

  Adapter(Instance instance, String label, MemorySegment handle) {
    super(instance, instance, label, handle);
  }

  public Info info() {
    MemorySegment adapter = handle();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment info = arena.allocate(Structs.AdapterInfo.LAYOUT);
      if (Wgpu.adapterGetInfo(adapter, info) != Wgpu.STATUS_SUCCESS) {
        throw new IllegalStateException("wgpuAdapterGetInfo failed for " + this);
      }
      try {
        int backendType = info.get(JAVA_INT, Structs.AdapterInfo.BACKEND_TYPE);
        Backend backend = Backend.ofType(backendType);
        if (backend == null) {
          throw new IllegalStateException(this + " runs on the backend type " + backendType
              + ", which its instance did not enable.");
        }
        return new Info(
            Wgpu.string(info, Structs.AdapterInfo.VENDOR),
            Wgpu.string(info, Structs.AdapterInfo.ARCHITECTURE),
            Wgpu.string(info, Structs.AdapterInfo.DEVICE),
            Wgpu.string(info, Structs.AdapterInfo.DESCRIPTION),
            backend,
            Type.of(info.get(JAVA_INT, Structs.AdapterInfo.ADAPTER_TYPE)),
            info.get(JAVA_INT, Structs.AdapterInfo.VENDOR_ID),
            info.get(JAVA_INT, Structs.AdapterInfo.DEVICE_ID));
      } finally {
        Wgpu.adapterInfoFreeMembers(info);
      }
    }
  }

  public Limits limits() {
    MemorySegment adapter = handle();
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment limits = arena.allocate(Structs.Limits.LAYOUT);
      if (Wgpu.adapterGetLimits(adapter, limits) != Wgpu.STATUS_SUCCESS) {
        throw new IllegalStateException("wgpuAdapterGetLimits failed for " + this);
      }
      return Limits.read(limits);
    }
  }

  /**
   * Creates a device with the adapter's default limits and no optional features. Its
   * uncaptured-error and device-lost callbacks are always installed: without them wgpu-native
   * ends the process on the first error outside an error scope.
   *
   * @throws GpuException of kind {@link GpuException.Kind#REQUEST_FAILED} if wgpu refuses
   */
  public Device requestDevice(String label) {
    requireLabel(label);
    MemorySegment adapter = handle();
    Callbacks.DeviceEvents events = new Callbacks.DeviceEvents(label);
    long eventsId = Callbacks.register(events);
    Callbacks.Request request = new Callbacks.Request();
    long requestId = Callbacks.register(request);
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment descriptor = arena.allocate(DeviceDescriptor.LAYOUT);
      Structs.string(descriptor, DeviceDescriptor.LABEL, label, arena);
      Structs.string(descriptor, DeviceDescriptor.DEFAULT_QUEUE_LABEL, label + " queue", arena);
      CallbackInfo.set(
          descriptor, DeviceDescriptor.DEVICE_LOST_CALLBACK_INFO, Callbacks.DEVICE_LOST, eventsId);
      long uncaptured = DeviceDescriptor.UNCAPTURED_ERROR_CALLBACK_INFO;
      descriptor.set(
          ADDRESS, uncaptured + UncapturedErrorCallbackInfo.CALLBACK, Callbacks.UNCAPTURED_ERROR);
      descriptor.set(
          ADDRESS,
          uncaptured + UncapturedErrorCallbackInfo.USERDATA1,
          MemorySegment.ofAddress(eventsId));
      Wgpu.adapterRequestDevice(
          arena,
          adapter,
          descriptor,
          CallbackInfo.allocate(arena, Callbacks.REQUEST_DEVICE, requestId));
    } catch (RuntimeException | Error e) {
      Callbacks.unregister(eventsId);
      throw e;
    } finally {
      Callbacks.unregister(requestId);
    }
    try {
      request.rethrowFailure("requesting a device");
      if (!request.done) {
        throw Instance.notSynchronous("wgpuAdapterRequestDevice");
      }
      if (request.status != Wgpu.REQUEST_DEVICE_STATUS_SUCCESS) {
        throw new GpuException(
            GpuException.Kind.REQUEST_FAILED, "requesting device", label, request.message);
      }
    } catch (RuntimeException e) {
      Callbacks.unregister(eventsId);
      throw e;
    }
    // The device keeps the adapter alive for surface capabilities.
    Wgpu.adapterAddRef(adapter);
    return new Device(instance, label, request.handle, adapter, events, eventsId);
  }

  @Override
  String kind() {
    return "Adapter";
  }

  @Override
  void release(MemorySegment handle) {
    Wgpu.adapterRelease(handle);
  }
}
