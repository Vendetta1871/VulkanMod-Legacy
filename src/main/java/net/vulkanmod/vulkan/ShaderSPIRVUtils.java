package net.vulkanmod.vulkan;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.*;

public class ShaderSPIRVUtils {
    private static long compiler;

    public static SPIRV compileShaderFile(String shaderFile, ShaderKind shaderKind) {
        // getResourceAsStream reads through both the dev classes dir and a packaged jar;
        // Paths.get(URI) throws FileSystemNotFoundException on jar: URIs, so loading
        // from the mod jar used to fail outright.
        try (InputStream in = ShaderSPIRVUtils.class.getResourceAsStream("/assets/vulkanmod/shaders/" + shaderFile)) {
            if (in == null) {
                throw new IOException("Shader resource not found: " + shaderFile);
            }
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return compileShader(shaderFile, source, shaderKind);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static SPIRV compileShader(String filename, String source, ShaderKind shaderKind) {

        // Optimized SPIR-V is deterministic for a given source, so the result is cached
        // on disk keyed by the source hash: launches after the first skip shaderc.
        byte[] cached = loadCachedSpv(filename, source, shaderKind);
        if (cached != null) {
            return SPIRV.fromBytes(cached);
        }

        if(compiler == 0) compiler = shaderc_compiler_initialize();

        if(compiler == NULL) {
            throw new RuntimeException("Failed to create shader compiler");
        }

        long options = shaderc_compile_options_initialize();

        if(options == NULL) {
            throw new RuntimeException("Failed to create compiler options");
        }

        shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

        long result;
        try {
            result = shaderc_compile_into_spv(compiler, source, shaderKind.kind, filename, "main", options);
        } finally {
            // The options are not referenced by the result; releasing them here kept
            // leaking one options object per compiled shader before.
            shaderc_compile_options_release(options);
        }

        if(result == NULL) {
            throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V");
        }

        if(shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
            String message = shaderc_result_get_error_message(result);
            shaderc_result_release(result);
            throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V:\n" + message);
        }

//        shaderc_compiler_release(compiler);

        SPIRV spirv = new SPIRV(result, shaderc_result_get_bytes(result));
        storeCachedSpv(filename, source, shaderKind, spirv.bytecode());
        return spirv;
    }

    // --- SPIR-V disk cache (config/vulkanmod/spv/<source-hash>.spv) ---

    private static final java.nio.file.Path SPV_CACHE_DIR =
            java.nio.file.Paths.get("config", "vulkanmod", "spv");

    private static String cacheKey(String filename, String source, ShaderKind shaderKind) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            // N14: the compiler identity and the compile options are part of the key, so
            // an updated shaderc or different options never reuse SPIR-V built by the old
            // configuration. LWJGL does not bind shaderc_shaderc_version_string, so the
            // LWJGL version (bumped whenever the bundled shaderc is upgraded) stands in.
            digest.update(("shaderc " + org.lwjgl.Version.getVersion()
                    + " opt=performance entry=main").getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(shaderKind.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(filename.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(source.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] loadCachedSpv(String filename, String source, ShaderKind shaderKind) {
        String key = cacheKey(filename, source, shaderKind);
        if (key == null) {
            return null;
        }
        try {
            java.nio.file.Path file = SPV_CACHE_DIR.resolve(key + ".spv");
            if (!java.nio.file.Files.isRegularFile(file)) {
                return null;
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(file);
            // N14: SPIR-V is a stream of 32-bit words opening with the magic 0x07230203
            // and a 20-byte header; a truncated or corrupt file (crash mid-write) is
            // rejected and recompiled instead of reaching vkCreateShaderModule.
            if (bytes.length < 20 || (bytes.length & 3) != 0
                    || (bytes[0] & 0xFF) != 0x03 || (bytes[1] & 0xFF) != 0x02
                    || (bytes[2] & 0xFF) != 0x23 || (bytes[3] & 0xFF) != 0x07) {
                return null;
            }
            return bytes;
        } catch (Exception e) {
            return null;
        }
    }

    private static void storeCachedSpv(String filename, String source, ShaderKind shaderKind, ByteBuffer spv) {
        String key = cacheKey(filename, source, shaderKind);
        if (key == null) {
            return;
        }
        try {
            byte[] bytes = new byte[spv.remaining()];
            int pos = spv.position();
            spv.get(bytes);
            spv.position(pos);
            java.nio.file.Files.createDirectories(SPV_CACHE_DIR);
            // N14: temp file + atomic rename, so a crash mid-write cannot leave a
            // partial .spv for the next launch to feed to the driver.
            java.nio.file.Path file = SPV_CACHE_DIR.resolve(key + ".spv");
            java.nio.file.Path tmp = file.resolveSibling(key + ".spv.tmp");
            java.nio.file.Files.write(tmp, bytes);
            try {
                java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            // Best-effort: a failed write only costs compile time on the next launch.
        }
    }

    public enum ShaderKind {

        VERTEX_SHADER(shaderc_glsl_vertex_shader),
        GEOMETRY_SHADER(shaderc_glsl_geometry_shader),
        FRAGMENT_SHADER(shaderc_glsl_fragment_shader);

        private final int kind;

        ShaderKind(int kind) {
            this.kind = kind;
        }
    }

    public static final class SPIRV implements NativeResource {

        private final long handle;
        private ByteBuffer bytecode;

        public SPIRV(long handle, ByteBuffer bytecode) {
            this.handle = handle;
            this.bytecode = bytecode;
        }

        /** SPIR-V loaded from the disk cache: a plain off-heap buffer, no shaderc result. */
        static SPIRV fromBytes(byte[] bytes) {
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
            buffer.put(bytes);
            buffer.position(0);
            return new SPIRV(0, buffer);
        }

        public ByteBuffer bytecode() {
            return bytecode;
        }

        @Override
        public void free() {
            // Safe once the bytecode has been copied into the VkShaderModule
            // (vkCreateShaderModule copies the SPIR-V); Pipeline frees the SPIRV right
            // after createShaderModule.
            if (handle != 0) {
                shaderc_result_release(handle);
            } else if (bytecode != null) {
                MemoryUtil.memFree(bytecode);
            }
            bytecode = null; // Help the GC
        }
    }

}