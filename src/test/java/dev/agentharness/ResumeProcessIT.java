package dev.agentharness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ResumeProcessIT {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void twoIndependentJarProcessesResumeWithoutRepeatingCompletedTool() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath();
        Path jar = Path.of("target", "agent-harness-java.jar").toAbsolutePath();
        Path state =
                Path.of("target", "it-state", "resume-process-" + UUID.randomUUID());

        JsonNode first =
                run(
                        List.of(
                                java.toString(),
                                "-jar",
                                jar.toString(),
                                "resume",
                                "--state-root",
                                state.toString(),
                                "--session",
                                "cross-process",
                                "--stop-after",
                                "config"));
        assertEquals("REQUEST_PENDING", first.path("status").asText());
        assertEquals(1, first.path("callCounts").path("query_effective_config").asInt());

        JsonNode second =
                run(
                        List.of(
                                java.toString(),
                                "-jar",
                                jar.toString(),
                                "resume",
                                "--state-root",
                                state.toString(),
                                "--session",
                                "cross-process"));
        assertEquals("COMPLETE", second.path("status").asText());
        assertEquals(1, second.path("callCounts").path("query_effective_config").asInt());
        assertEquals(1, second.path("callCounts").path("query_request_facts").asInt());
    }

    private JsonNode run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String output =
                new String(process.getInputStream().readNBytes(65_536), StandardCharsets.UTF_8);
        assertEquals(true, finished, "子进程超时，有限输出: " + output);
        int exit = process.exitValue();
        assertEquals(0, exit, output);
        return mapper.readTree(output.lines().filter(line -> line.startsWith("{")).reduce((a, b) -> b).orElseThrow());
    }
}
