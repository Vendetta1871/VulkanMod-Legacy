package net.vulkanmod.vulkan;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeResource;
import org.lwjgl.util.shaderc.ShadercIncludeResolve;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultRelease;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.*;

public class ShaderSPIRVUtils {
    private static long compiler;

    /** Root of every shader resource; include paths are relative to it. */
    private static final String SHADER_ROOT = "/assets/vulkanmod/shaders/";

    /** Matches {@code #include "x.glsl"} and {@code #include <x.glsl>}. */
    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile("^\\s*#\\s*include\\s*([<\"])([^>\"]+)[>\"]", Pattern.MULTILINE);

    /** Guards against a cyclic or absurdly deep include chain. */
    private static final int MAX_INCLUDE_DEPTH = 32;

    public static SPIRV compileShaderFile(String shaderFile, ShaderKind shaderKind) {
        return compileShaderFile(shaderFile, shaderKind, Collections.emptyMap());
    }

    public static SPIRV compileShaderFile(String shaderFile, ShaderKind shaderKind, Map<String, String> defines) {
        // getResourceAsStream reads through both the dev classes dir and a packaged jar;
        // Paths.get(URI) throws FileSystemNotFoundException on jar: URIs, so loading
        // from the mod jar used to fail outright.
        try (InputStream in = ShaderSPIRVUtils.class.getResourceAsStream(SHADER_ROOT + shaderFile)) {
            if (in == null) {
                throw new IOException("Shader resource not found: " + shaderFile);
            }
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return compileShader(shaderFile, source, shaderKind, defines);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static SPIRV compileShader(String filename, String source, ShaderKind shaderKind) {
        return compileShader(filename, source, shaderKind, Collections.emptyMap());
    }

    public static SPIRV compileShader(String filename, String source, ShaderKind shaderKind,
                                      Map<String, String> defines) {

        // Includes change the effective source, so the closure is expanded here and
        // hashed into the cache key: editing an included file must invalidate the SPV.
        String includes = includeClosure(filename, source);

        // Optimized SPIR-V is deterministic for a given source, so the result is cached
        // on disk keyed by the source hash: launches after the first skip shaderc.
        byte[] cached = loadCachedSpv(filename, source, shaderKind, defines, includes);
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

        // Raster shaders keep the shaderc default target so their output is unchanged;
        // compute and ray tracing need Vulkan 1.2 / SPIR-V 1.4.
        if(shaderKind.modern) {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan,
                    shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_4);
        }

        // The callbacks are static and outlive every compile; shaderc only borrows them.
        shaderc_compile_options_set_include_callbacks(options, INCLUDE_RESOLVE, INCLUDE_RELEASE, NULL);

        for(Map.Entry<String, String> define : defines.entrySet()) {
            String value = define.getValue();
            shaderc_compile_options_add_macro_definition(options, define.getKey(), value == null ? "" : value);
        }

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
        storeCachedSpv(filename, source, shaderKind, defines, includes, spirv.bytecode());
        return spirv;
    }

    // --- Includes (#include "x.glsl" / #include <x.glsl> from the shader root) ---

    /** Resolve callback: returns a native {@link ShadercIncludeResult} alive until release. */
    private static final ShadercIncludeResolve INCLUDE_RESOLVE = ShadercIncludeResolve.create(
            (userData, requestedSource, type, requestingSource, includeDepth) -> {
                try {
                    String requested = requestedSource == NULL ? "" : MemoryUtil.memUTF8(requestedSource);
                    String requesting = requestingSource == NULL ? "" : MemoryUtil.memUTF8(requestingSource);
                    String path = resolveIncludePath(requested, type, requesting);
                    String text = path.isEmpty() ? null : readShaderResource(path);
                    if (text == null) {
                        // shaderc reports a failed include when source_name is empty and
                        // the message sits in content.
                        return createIncludeResult("", "include file not found: " + requested);
                    }
                    return createIncludeResult(path, text);
                } catch (Throwable t) {
                    return createIncludeResult("", "include resolution failed: " + t);
                }
            });

    /** Release callback: frees the result, its name and its content. */
    private static final ShadercIncludeResultRelease INCLUDE_RELEASE = ShadercIncludeResultRelease.create(
            (userData, includeResult) -> {
                ShadercIncludeResult result = ShadercIncludeResult.create(includeResult);
                ByteBuffer name = result.source_name();
                ByteBuffer content = result.content();
                if (name != null) MemoryUtil.memFree(name);
                if (content != null) MemoryUtil.memFree(content);
                result.free();
            });

    /** Builds a native include result; shaderc frees it through {@link #INCLUDE_RELEASE}. */
    private static long createIncludeResult(String sourceName, String content) {
        ShadercIncludeResult result = ShadercIncludeResult.calloc();
        ByteBuffer name = MemoryUtil.memUTF8(sourceName, true);
        ByteBuffer text = MemoryUtil.memUTF8(content, true);
        result.source_name(name);
        result.content(text);
        // The struct setters count the trailing NUL; shaderc wants the real lengths.
        ShadercIncludeResult.nsource_name_length(result.address(), name.remaining() - 1);
        ShadercIncludeResult.ncontent_length(result.address(), text.remaining() - 1);
        return result.address();
    }

    /** Relative includes resolve against the requesting file's directory, others from the root. */
    private static String resolveIncludePath(String requested, int type, String requesting) {
        String path = requested.replace('\\', '/').trim();
        if (type == shaderc_include_type_relative && requesting != null) {
            int slash = requesting.lastIndexOf('/');
            if (slash >= 0) {
                path = requesting.substring(0, slash + 1) + path;
            }
        }
        return normalizePath(path);
    }

    /** Collapses {@code .} and {@code ..} segments so equal files share one cache entry. */
    private static String normalizePath(String path) {
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.removeLast();
                }
                continue;
            }
            parts.addLast(part);
        }
        return String.join("/", parts);
    }

    /** Shader resource text, or null when it does not exist. */
    private static String readShaderResource(String path) {
        try (InputStream in = ShaderSPIRVUtils.class.getResourceAsStream(SHADER_ROOT + path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Text of every transitively included file, keyed by resolved path. Included texts
     * are part of the SPV cache key, so editing an include recompiles its users.
     */
    private static String includeClosure(String filename, String source) {
        TreeMap<String, String> files = new TreeMap<>();
        collectIncludes(filename, source, files, 0);
        if (files.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            sb.append(entry.getKey()).append('\n').append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static void collectIncludes(String requesting, String source,
                                        Map<String, String> files, int depth) {
        if (depth > MAX_INCLUDE_DEPTH) {
            return;
        }
        Matcher matcher = INCLUDE_PATTERN.matcher(source);
        while (matcher.find()) {
            int type = matcher.group(1).equals("\"")
                    ? shaderc_include_type_relative : shaderc_include_type_standard;
            String path = resolveIncludePath(matcher.group(2), type, requesting);
            // Already collected: also breaks include cycles.
            if (path.isEmpty() || files.containsKey(path)) {
                continue;
            }
            String text = readShaderResource(path);
            if (text == null) {
                continue;
            }
            files.put(path, text);
            collectIncludes(path, text, files, depth + 1);
        }
    }

    // --- SPIR-V disk cache (config/vulkanmod/spv/<source-hash>.spv) ---

    private static final java.nio.file.Path SPV_CACHE_DIR =
            java.nio.file.Paths.get("config", "vulkanmod", "spv");

    /** Cache-key form of the compile target. */
    private static String targetEnvKey(ShaderKind shaderKind) {
        return shaderKind.modern ? "vk1.2/spv1.4" : "default";
    }

    private static String cacheKey(String filename, String source, ShaderKind shaderKind,
                                   Map<String, String> defines, String includes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            // N14: the compiler identity and the compile options are part of the key, so
            // an updated shaderc or different options never reuse SPIR-V built by the old
            // configuration. LWJGL does not bind shaderc_shaderc_version_string, so the
            // LWJGL version (bumped whenever the bundled shaderc is upgraded) stands in.
            digest.update(("shaderc " + org.lwjgl.Version.getVersion()
                    + " opt=performance entry=main target=" + targetEnvKey(shaderKind))
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(shaderKind.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(filename.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(source.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            // Sorted so an unordered defines map still maps to one key.
            for (Map.Entry<String, String> define : new TreeMap<>(defines).entrySet()) {
                digest.update(("define " + define.getKey() + '=' + define.getValue())
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            digest.update(includes.getBytes(StandardCharsets.UTF_8));
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

    private static byte[] loadCachedSpv(String filename, String source, ShaderKind shaderKind,
                                        Map<String, String> defines, String includes) {
        String key = cacheKey(filename, source, shaderKind, defines, includes);
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

    private static void storeCachedSpv(String filename, String source, ShaderKind shaderKind,
                                       Map<String, String> defines, String includes, ByteBuffer spv) {
        String key = cacheKey(filename, source, shaderKind, defines, includes);
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

        VERTEX_SHADER(shaderc_glsl_vertex_shader, false),
        GEOMETRY_SHADER(shaderc_glsl_geometry_shader, false),
        FRAGMENT_SHADER(shaderc_glsl_fragment_shader, false),
        COMPUTE_SHADER(shaderc_glsl_compute_shader, true),
        RAYGEN_SHADER(shaderc_glsl_raygen_shader, true),
        MISS_SHADER(shaderc_glsl_miss_shader, true),
        CLOSEST_HIT_SHADER(shaderc_glsl_closesthit_shader, true),
        ANY_HIT_SHADER(shaderc_glsl_anyhit_shader, true);

        private final int kind;
        /** True for kinds compiled against Vulkan 1.2 / SPIR-V 1.4. */
        private final boolean modern;

        ShaderKind(int kind, boolean modern) {
            this.kind = kind;
            this.modern = modern;
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
