package com.rethinkj.ui.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.StructLayout;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** What the bindings promise without loading the library. */
class WgpuTest {

  /** The functions of webgpu.h that wgpu-native v29 leaves unimplemented; each ends the process. */
  private static final List<String> UNIMPLEMENTED = List.of(
      "wgpuGetProcAddress",
      "wgpuBindGroupSetLabel",
      "wgpuBindGroupLayoutSetLabel",
      "wgpuBufferGetMapState",
      "wgpuBufferSetLabel",
      "wgpuCommandBufferSetLabel",
      "wgpuCommandEncoderSetLabel",
      "wgpuComputePassEncoderSetLabel",
      "wgpuComputePipelineSetLabel",
      "wgpuDeviceCreateComputePipelineAsync",
      "wgpuDeviceCreateRenderPipelineAsync",
      "wgpuDeviceGetAdapterInfo",
      "wgpuDeviceGetLostFuture",
      "wgpuDeviceSetLabel",
      "wgpuInstanceGetWGSLLanguageFeatures",
      "wgpuInstanceHasWGSLLanguageFeature",
      "wgpuPipelineLayoutSetLabel",
      "wgpuQuerySetSetLabel",
      "wgpuQueueSetLabel",
      "wgpuRenderBundleSetLabel",
      "wgpuRenderBundleEncoderSetLabel",
      "wgpuRenderPassEncoderSetLabel",
      "wgpuRenderPipelineSetLabel",
      "wgpuSamplerSetLabel",
      "wgpuShaderModuleGetCompilationInfo",
      "wgpuShaderModuleSetLabel",
      "wgpuSupportedWGSLLanguageFeaturesFreeMembers",
      "wgpuSurfaceSetLabel",
      "wgpuTextureSetLabel",
      "wgpuTextureViewSetLabel",
      "wgpuInstanceWaitAny",
      "wgpuExternalTextureSetLabel",
      "wgpuExternalTextureAddRef",
      "wgpuExternalTextureRelease",
      "wgpuTextureGetTextureBindingViewDimension");

  @Test
  void noFunctionThatWgpuNativeLeavesUnimplementedIsBound() {
    assertEquals(35, UNIMPLEMENTED.size());
    Set<String> bound = Wgpu.FUNCTIONS.keySet();
    assertEquals(
        Set.of(), bound.stream().filter(UNIMPLEMENTED::contains).collect(Collectors.toSet()));
    // Nothing that sets a label, whatever its object.
    assertFalse(bound.stream().anyMatch(name -> name.endsWith("SetLabel")));
  }

  @Test
  void layoutsHaveTheSizesOfTheHeaderOnX64() {
    // Spot checks; scripts/LayoutCheck.java compares every member with jextract's layouts.
    assertEquals(16, Structs.STRING_VIEW.byteSize());
    assertEquals(40, Structs.CallbackInfo.LAYOUT.byteSize());
    assertEquals(152, Structs.Limits.LAYOUT.byteSize());
    assertEquals(80, Structs.TextureDescriptor.LAYOUT.byteSize());
    assertEquals(120, Structs.BindGroupLayoutEntry.LAYOUT.byteSize());
    assertEquals(72, Structs.RenderPassColorAttachment.LAYOUT.byteSize());
    for (StructLayout layout : Structs.ALL) {
      assertEquals(0, layout.byteSize() % layout.byteAlignment(), layout.toString());
    }
  }

  @Test
  void defaultsAreTheHeadersInitValuesNotZero() {
    try (var arena = java.lang.foreign.Arena.ofConfined()) {
      var view = Structs.TextureViewDescriptor.allocate(arena);
      assertEquals(
          -1,
          view.get(
              java.lang.foreign.ValueLayout.JAVA_INT,
              Structs.TextureViewDescriptor.MIP_LEVEL_COUNT));
      assertEquals(
          -1L,
          view.get(
              java.lang.foreign.ValueLayout.JAVA_LONG, Structs.TextureViewDescriptor.LABEL + 8));
      var attachment = Structs.RenderPassColorAttachment.allocate(arena);
      assertEquals(
          -1,
          attachment.get(
              java.lang.foreign.ValueLayout.JAVA_INT,
              Structs.RenderPassColorAttachment.DEPTH_SLICE));
    }
  }

  @Test
  void aSurfaceOfSizeZeroIsRefusedBeforeWgpuSeesIt() {
    IllegalArgumentException e = assertThrows(
        IllegalArgumentException.class,
        () -> new Surface.Configuration(
            TextureFormat.BGRA8_UNORM, 0, 600, Surface.PresentMode.FIFO, Surface.AlphaMode.AUTO));
    assertTrue(e.getMessage().contains("0x600"), e.getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () -> new Surface.Configuration(
            TextureFormat.BGRA8_UNORM, 800, 0, Surface.PresentMode.FIFO, Surface.AlphaMode.AUTO));
  }

  @Test
  void aSurfaceNeedsTheWindowsHandles() {
    assertThrows(IllegalArgumentException.class, () -> new SurfaceSource.WindowsHwnd(1, 0));
    assertThrows(IllegalArgumentException.class, () -> new SurfaceSource.XlibWindow(0, 5));
    assertThrows(IllegalArgumentException.class, () -> new SurfaceSource.XlibWindow(1, 0));
    assertThrows(IllegalArgumentException.class, () -> new SurfaceSource.WaylandSurface(1, 0));
  }

  @Test
  void theDefaultBackendIsTheOneOfThePlatform() {
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    assertEquals(windows ? Backend.D3D12 : Backend.VULKAN, Backend.forPlatform());
  }
}
