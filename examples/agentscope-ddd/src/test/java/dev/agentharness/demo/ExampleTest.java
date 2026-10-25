package dev.agentharness.demo;

import dev.agentharness.demo.domain.*;
import dev.agentharness.demo.app.ChatService;
import dev.agentharness.demo.dto.AssistantReply;
import dev.agentharness.demo.infra.AppConfig;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ExampleTest {
    @Test void leapYearsAndInvalidCalendarDates() {
        // 领域规则覆盖世纪闰年、普通闰年、非法月份和公元零年。
        var dates = new DateValidation();
        assertTrue(dates.isValid("2000-02-29"));
        assertTrue(dates.isValid("2024-02-29"));
        assertFalse(dates.isValid("1900-02-29"));
        assertFalse(dates.isValid("2025-02-29"));
        assertFalse(dates.isValid("2025-13-01"));
        assertFalse(dates.isValid("0000-01-01"));
    }
    @Test void ambiguousAndInjectedParametersAreRejected() {
        var dates = new DateValidation();
        assertThrows(IllegalArgumentException.class, () -> dates.isValid("03/04"));
        assertThrows(IllegalArgumentException.class, () -> dates.isValid("2025-01-01\n伪造日志"));
        assertThrows(IllegalArgumentException.class, () -> dates.isValid(null));
    }
    @Test void invalidInputNeverReachesModelPort() {
        var calls = new AtomicInteger();
        var gateway = new AssistantGateway() {
            public AssistantReply ask(String question) {
                calls.incrementAndGet();
                return new AssistantReply(question, "STOP");
            }
            public void close() {}
        };
        var service = new ChatService(gateway);
        assertThrows(IllegalArgumentException.class, () -> service.ask(" "));
        assertThrows(IllegalArgumentException.class, () -> service.ask("a".repeat(8001)));
        assertEquals(0, calls.get());
        assertEquals("你好", service.ask("你好").text());
        assertEquals(1, calls.get());
    }
    @Test void ollamaNeedsNoCloudKeyAndHonorsModelOverride() throws Exception {
        var config = new AppConfig(Path.of("."), "ollama", Map.of("OLLAMA_MODEL", "qwen2.5:0.5b"));
        assertEquals("qwen2.5:0.5b", config.model);
        assertTrue(config.apiKey().isEmpty());
        assertTrue(config.prompt.contains("通用中文助手"));
    }
    @Test void missingCloudKeyAndUnknownProviderFailBeforeNetwork() {
        assertThrows(IllegalArgumentException.class, () -> new AppConfig(Path.of("."), "deepseek", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AppConfig(Path.of("."), "other", Map.of()));
    }
    @Test void domainAndAppStayIndependentOfSdkAndInfra() throws Exception {
        // 最小源码依赖守卫，防止后续把 SDK 或具体接入类带回领域与应用层。
        for (String layer : new String[]{"domain", "app", "dto"}) {
            try (var files = java.nio.file.Files.walk(Path.of("src/main/java/dev/agentharness/demo", layer))) {
                for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String source = java.nio.file.Files.readString(file);
                    assertFalse(source.contains("import io.agentscope."), file.toString());
                    assertFalse(source.contains("import dev.agentharness.demo.infra."), file.toString());
                }
            }
        }
    }
}
