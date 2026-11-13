import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.PaddingLayout;
import java.lang.foreign.StructLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares the handwritten struct layouts in {@code Wgpu.java} with the ones jextract derives from the headers:
 * total size, alignment, and the name, offset and size of every member.
 *
 * <p>The generated classes are not in the repository. From the repository root, with a jextract build unpacked in
 * the git-ignored {@code spikes/gpu/tools/}:
 *
 * <pre>
 * cd spikes/gpu
 * tools/jextract-25/bin/jextract --dump-includes generated/includes.txt -I native/include/webgpu \
 *     native/include/webgpu/wgpu.h
 * grep -E "^--include-[a-z]+ +(wgpu|WGPU)" generated/includes.txt | grep -v WGPUProc > generated/includes-wgpu.txt
 * tools/jextract-25/bin/jextract --output generated/filtered --target-package com.rethinkj.ui.wgpu \
 *     --header-class-name Wgpu -I native/include/webgpu @generated/includes-wgpu.txt native/include/webgpu/wgpu.h
 * javac -d generated/classes $(find generated/filtered -name '*.java')
 * cd ../..
 * java -cp spikes/gpu/generated/classes spikes/gpu/LayoutCheck.java
 * </pre>
 */
public final class LayoutCheck {
    private LayoutCheck() {}

    public static void main(String[] args) {
        Map<String, GroupLayout> generated = new LinkedHashMap<>();
        generated.put("WGPUStringView", com.rethinkj.ui.wgpu.WGPUStringView.layout());
        generated.put("WGPUChainedStruct", com.rethinkj.ui.wgpu.WGPUChainedStruct.layout());
        generated.put("WGPUFuture", com.rethinkj.ui.wgpu.WGPUFuture.layout());
        generated.put("WGPUFutureWaitInfo", com.rethinkj.ui.wgpu.WGPUFutureWaitInfo.layout());
        generated.put("WGPUInstanceDescriptor", com.rethinkj.ui.wgpu.WGPUInstanceDescriptor.layout());
        generated.put("WGPUXlibDisplayHandle", com.rethinkj.ui.wgpu.WGPUXlibDisplayHandle.layout());
        generated.put("WGPUNativeDisplayHandle", com.rethinkj.ui.wgpu.WGPUNativeDisplayHandle.layout());
        generated.put("WGPUInstanceExtras", com.rethinkj.ui.wgpu.WGPUInstanceExtras.layout());
        generated.put("WGPUInstanceEnumerateAdapterOptions",
                com.rethinkj.ui.wgpu.WGPUInstanceEnumerateAdapterOptions.layout());
        generated.put("WGPUSurfaceDescriptor", com.rethinkj.ui.wgpu.WGPUSurfaceDescriptor.layout());
        generated.put("WGPUSurfaceSourceWindowsHWND", com.rethinkj.ui.wgpu.WGPUSurfaceSourceWindowsHWND.layout());
        generated.put("WGPURequestAdapterOptions", com.rethinkj.ui.wgpu.WGPURequestAdapterOptions.layout());
        generated.put("WGPURequestAdapterCallbackInfo", com.rethinkj.ui.wgpu.WGPURequestAdapterCallbackInfo.layout());
        generated.put("WGPURequestDeviceCallbackInfo", com.rethinkj.ui.wgpu.WGPURequestDeviceCallbackInfo.layout());
        generated.put("WGPUPopErrorScopeCallbackInfo", com.rethinkj.ui.wgpu.WGPUPopErrorScopeCallbackInfo.layout());
        generated.put("WGPUDeviceLostCallbackInfo", com.rethinkj.ui.wgpu.WGPUDeviceLostCallbackInfo.layout());
        generated.put("WGPUUncapturedErrorCallbackInfo",
                com.rethinkj.ui.wgpu.WGPUUncapturedErrorCallbackInfo.layout());
        generated.put("WGPUAdapterInfo", com.rethinkj.ui.wgpu.WGPUAdapterInfo.layout());
        generated.put("WGPUQueueDescriptor", com.rethinkj.ui.wgpu.WGPUQueueDescriptor.layout());
        generated.put("WGPUDeviceDescriptor", com.rethinkj.ui.wgpu.WGPUDeviceDescriptor.layout());
        generated.put("WGPUSurfaceCapabilities", com.rethinkj.ui.wgpu.WGPUSurfaceCapabilities.layout());
        generated.put("WGPUSurfaceConfiguration", com.rethinkj.ui.wgpu.WGPUSurfaceConfiguration.layout());
        generated.put("WGPUSurfaceTexture", com.rethinkj.ui.wgpu.WGPUSurfaceTexture.layout());
        generated.put("WGPUShaderModuleDescriptor", com.rethinkj.ui.wgpu.WGPUShaderModuleDescriptor.layout());
        generated.put("WGPUShaderSourceWGSL", com.rethinkj.ui.wgpu.WGPUShaderSourceWGSL.layout());
        generated.put("WGPUVertexAttribute", com.rethinkj.ui.wgpu.WGPUVertexAttribute.layout());
        generated.put("WGPUVertexBufferLayout", com.rethinkj.ui.wgpu.WGPUVertexBufferLayout.layout());
        generated.put("WGPUVertexState", com.rethinkj.ui.wgpu.WGPUVertexState.layout());
        generated.put("WGPUPrimitiveState", com.rethinkj.ui.wgpu.WGPUPrimitiveState.layout());
        generated.put("WGPUMultisampleState", com.rethinkj.ui.wgpu.WGPUMultisampleState.layout());
        generated.put("WGPUColorTargetState", com.rethinkj.ui.wgpu.WGPUColorTargetState.layout());
        generated.put("WGPUFragmentState", com.rethinkj.ui.wgpu.WGPUFragmentState.layout());
        generated.put("WGPURenderPipelineDescriptor", com.rethinkj.ui.wgpu.WGPURenderPipelineDescriptor.layout());
        generated.put("WGPUColor", com.rethinkj.ui.wgpu.WGPUColor.layout());
        generated.put("WGPURenderPassColorAttachment", com.rethinkj.ui.wgpu.WGPURenderPassColorAttachment.layout());
        generated.put("WGPURenderPassDescriptor", com.rethinkj.ui.wgpu.WGPURenderPassDescriptor.layout());
        generated.put("WGPUBufferDescriptor", com.rethinkj.ui.wgpu.WGPUBufferDescriptor.layout());

        int compared = 0;
        int different = 0;
        for (StructLayout written : Wgpu.LAYOUTS) {
            String name = written.name().orElseThrow();
            // One handwritten layout stands for the four callback info structs with a mode.
            List<String> targets = name.equals("WGPU*CallbackInfo")
                    ? List.of("WGPURequestAdapterCallbackInfo", "WGPURequestDeviceCallbackInfo",
                            "WGPUPopErrorScopeCallbackInfo", "WGPUDeviceLostCallbackInfo")
                    : List.of(name);
            for (String target : targets) {
                GroupLayout other = generated.get(target);
                if (other == null) {
                    System.out.printf("%-38s no generated layout%n", target);
                    different++;
                    continue;
                }
                compared++;
                List<String> mine = members(written);
                List<String> theirs = members(other);
                boolean same = written.byteSize() == other.byteSize()
                        && written.byteAlignment() == other.byteAlignment() && mine.equals(theirs);
                System.out.printf("%-38s %3d bytes, %2d members  %s%n", target, written.byteSize(), mine.size(),
                        same ? "same" : "DIFFERENT");
                if (!same) {
                    different++;
                    System.out.printf("    handwritten: %d bytes, align %d, %s%n", written.byteSize(),
                            written.byteAlignment(), mine);
                    System.out.printf("    jextract:    %d bytes, align %d, %s%n", other.byteSize(),
                            other.byteAlignment(), theirs);
                }
            }
        }
        System.out.printf("%d layouts compared, %d different%n", compared, different);
    }

    /** "path@offset+size" for every member that is not padding; structs are walked into, unions are one member. */
    static List<String> members(GroupLayout layout) {
        List<String> out = new ArrayList<>();
        collect(layout, "", 0, out);
        return out;
    }

    static void collect(GroupLayout layout, String prefix, long base, List<String> out) {
        long offset = base;
        for (MemoryLayout member : layout.memberLayouts()) {
            if (!(member instanceof PaddingLayout)) {
                String path = prefix + member.name().orElse("?");
                if (member instanceof StructLayout struct) {
                    collect(struct, path + ".", offset, out);
                } else {
                    out.add(path + "@" + offset + "+" + member.byteSize());
                }
            }
            offset += member.byteSize();
        }
    }
}
