import com.rethinkj.ui.wgpu.WGPUAdapterInfo;
import com.rethinkj.ui.wgpu.WGPUInstanceDescriptor;
import com.rethinkj.ui.wgpu.WGPUInstanceExtras;
import com.rethinkj.ui.wgpu.WGPURequestAdapterCallback;
import com.rethinkj.ui.wgpu.WGPURequestAdapterCallbackInfo;
import com.rethinkj.ui.wgpu.WGPURequestAdapterOptions;
import com.rethinkj.ui.wgpu.WGPUStringView;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

/**
 * The first steps of {@code Main} written against jextract's output instead of {@code Wgpu.java}, to see what
 * calling code looks like: an instance for one backend, an adapter through the callback, its info.
 *
 * <p>The generated classes are not in the repository. From {@code spikes/gpu}, after the two commands in
 * {@code LayoutCheck} that write {@code generated/includes-wgpu.txt}:
 *
 * <pre>
 * tools/jextract-25/bin/jextract --output generated/withlib --target-package com.rethinkj.ui.wgpu \
 *     --header-class-name Wgpu -l :spikes/gpu/native/lib/wgpu_native.dll -I native/include/webgpu \
 *     @generated/includes-wgpu.txt native/include/webgpu/wgpu.h
 * javac -d generated/classes-lib $(find generated/withlib -name '*.java')
 * cd ../..
 * java --enable-native-access=ALL-UNNAMED -cp spikes/gpu/generated/classes-lib spikes/gpu/JextractProbe.java \
 *     [dx12|vulkan]
 * </pre>
 */
public final class JextractProbe {
    private JextractProbe() {}

    static MemorySegment adapter = MemorySegment.NULL;

    public static void main(String[] args) {
        // jextract has no constants for WGPUInstanceBackend: the header declares them as "static const" variables,
        // which it leaves out. The numbers come from wgpu.h by hand.
        long backends = args.length > 0 && args[0].equals("dx12") ? 1 << 3
                : args.length > 0 && args[0].equals("vulkan") ? 1 << 0 : 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment extras = WGPUInstanceExtras.allocate(arena);
            // The nested chain header has accessors of its own; the member is reached through a slice.
            com.rethinkj.ui.wgpu.WGPUChainedStruct.sType(WGPUInstanceExtras.chain(extras),
                    com.rethinkj.ui.wgpu.Wgpu.WGPUSType_InstanceExtras());
            WGPUInstanceExtras.backends(extras, backends);
            MemorySegment descriptor = WGPUInstanceDescriptor.allocate(arena);
            WGPUInstanceDescriptor.nextInChain(descriptor, extras);
            MemorySegment instance = com.rethinkj.ui.wgpu.Wgpu.wgpuCreateInstance(descriptor);

            MemorySegment callback = WGPURequestAdapterCallback.allocate((status, handle, message, u1, u2) -> {
                System.out.printf("callback: status %d, message \"%s\"%n", status, string(message));
                adapter = handle;
            }, arena);
            MemorySegment info = WGPURequestAdapterCallbackInfo.allocate(arena);
            WGPURequestAdapterCallbackInfo.mode(info, com.rethinkj.ui.wgpu.Wgpu.WGPUCallbackMode_AllowProcessEvents());
            WGPURequestAdapterCallbackInfo.callback(info, callback);
            MemorySegment options = WGPURequestAdapterOptions.allocate(arena);
            // The struct passed by value and the struct returned by value are plain segments.
            com.rethinkj.ui.wgpu.Wgpu.wgpuInstanceRequestAdapter(arena, instance, options, info);

            MemorySegment adapterInfo = WGPUAdapterInfo.allocate(arena);
            com.rethinkj.ui.wgpu.Wgpu.wgpuAdapterGetInfo(adapter, adapterInfo);
            System.out.printf("adapter: backend %d, vendor 0x%04X, device \"%s\"%n",
                    WGPUAdapterInfo.backendType(adapterInfo), WGPUAdapterInfo.vendorID(adapterInfo),
                    string(WGPUAdapterInfo.device(adapterInfo)));
            // An enum value is a method call, so it cannot be a case label.
            System.out.println("is D3D12: " + (WGPUAdapterInfo.backendType(adapterInfo)
                    == com.rethinkj.ui.wgpu.Wgpu.WGPUBackendType_D3D12()));
            com.rethinkj.ui.wgpu.Wgpu.wgpuAdapterInfoFreeMembers(adapterInfo);
            com.rethinkj.ui.wgpu.Wgpu.wgpuAdapterRelease(adapter);
            com.rethinkj.ui.wgpu.Wgpu.wgpuInstanceRelease(instance);
        }
    }

    static String string(MemorySegment view) {
        MemorySegment data = WGPUStringView.data(view);
        long length = WGPUStringView.length(view);
        return data.equals(MemorySegment.NULL) ? ""
                : new String(data.reinterpret(length).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                        StandardCharsets.UTF_8);
    }
}
