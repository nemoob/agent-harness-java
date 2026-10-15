package dev.agentharness.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppStateStoreTest {

    @Test
    void atomicallyPersistsRevisionEvidenceAndCallCount() throws IOException {
        Path root = uniqueRoot("atomic");
        AppStateStore store = new AppStateStore(root);

        try (LockedSession session = store.lock("session-1")) {
            session.recordEvidence("query_effective_config", "ev-config-001", "enabled=true");
            session.complete("query_effective_config", "REQUEST_PENDING");
        }

        AppState state = store.read("session-1");
        assertEquals(2, state.revision());
        assertEquals(1, state.callCounts().get("query_effective_config"));
        assertEquals("REQUEST_PENDING", state.phase());
        try (var files = Files.list(root.resolve("application"))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void rejectsSessionTraversalAndSymlinkRoot() throws IOException {
        Path parent = uniqueRoot("symlink-parent");
        Files.createDirectories(parent);
        Path target = parent.resolve("real");
        Files.createDirectories(target);
        Path link = parent.resolve("linked");
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, target.getFileName());

        assertThrows(IllegalArgumentException.class, () -> new AppStateStore(link));
        assertThrows(IllegalArgumentException.class, () -> new AppStateStore(parent).read("../escape"));
    }

    @Test
    void rejectsPreexistingApplicationAndLockLinks() throws IOException {
        Path applicationRoot = uniqueRoot("application-link");
        Path outside = uniqueRoot("application-link-target");
        Files.createDirectories(applicationRoot);
        Files.createDirectories(outside);
        Files.createSymbolicLink(applicationRoot.resolve("application"), outside.toAbsolutePath());

        assertThrows(IllegalArgumentException.class, () -> new AppStateStore(applicationRoot));

        Path lockRoot = uniqueRoot("lock-link");
        AppStateStore store = new AppStateStore(lockRoot);
        Path outsideFile = uniqueRoot("lock-file");
        Files.createDirectories(outsideFile.getParent());
        Files.writeString(outsideFile, "outside");
        Files.createSymbolicLink(
                lockRoot.resolve("application/session-1.lock"), outsideFile.toAbsolutePath());

        assertThrows(IllegalArgumentException.class, () -> store.lock("session-1"));
    }

    @Test
    void rejectsPreexistingAgentScopeAndNestedLinksBeforeAgentStoreConstruction()
            throws IOException {
        Path linkedRoot = uniqueRoot("agentscope-link");
        Path outside = uniqueRoot("agentscope-target");
        Files.createDirectories(linkedRoot);
        Files.createDirectories(outside);
        Files.createSymbolicLink(linkedRoot.resolve("agentscope"), outside.toAbsolutePath());

        AppStateStore linkedStore = new AppStateStore(linkedRoot);
        assertThrows(
                IllegalArgumentException.class,
                () -> linkedStore.agentStateRoot("teaching-user", "session-1"));

        Path nestedRoot = uniqueRoot("agentscope-nested-link");
        AppStateStore nestedStore = new AppStateStore(nestedRoot);
        Path nestedOutside = uniqueRoot("agentscope-user-target");
        Files.createDirectories(nestedRoot.resolve("agentscope"));
        Files.createDirectories(nestedOutside);
        Files.createSymbolicLink(
                nestedRoot.resolve("agentscope/teaching-user"), nestedOutside.toAbsolutePath());

        assertThrows(
                IllegalArgumentException.class,
                () -> nestedStore.agentStateRoot("teaching-user", "session-1"));
    }

    @Test
    void rejectsNonDirectoryAgentScopeStateComponent() throws IOException {
        Path root = uniqueRoot("agentscope-file");
        Files.createDirectories(root);
        Files.writeString(root.resolve("agentscope"), "not-a-directory");

        AppStateStore store = new AppStateStore(root);

        assertThrows(
                IllegalArgumentException.class,
                () -> store.agentStateRoot("teaching-user", "session-1"));
    }

    @Test
    void rejectsEveryAgentScopeLeafAndTemporarySymlinkWithoutChangingTarget()
            throws IOException {
        String[] leaves = {
            "agent_state.json",
            "agent_state.json.tmp",
            "memory_messages.jsonl",
            "memory_messages.jsonl.tmp",
            "memory_messages.hash",
            "memory_messages.hash.tmp",
            "toolkit_activeGroups.json",
            "toolkit_activeGroups.json.tmp"
        };
        for (String leaf : leaves) {
            Path root = uniqueRoot("agentscope-leaf-" + leaf.replace('.', '-'));
            AppStateStore store = new AppStateStore(root);
            Path sessionRoot = root.resolve("agentscope/teaching-user/session-1");
            Files.createDirectories(sessionRoot);
            Path outside = uniqueRoot("outside-" + leaf.replace('.', '-'));
            Files.createDirectories(outside.getParent());
            Files.writeString(outside, "unchanged");
            Files.createSymbolicLink(sessionRoot.resolve(leaf), outside.toAbsolutePath());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> store.agentStateRoot("teaching-user", "session-1"),
                    leaf);
            assertEquals("unchanged", Files.readString(outside), leaf);
        }
    }

    @Test
    void rejectsAgentScopeLeafReplacementAtCallBoundary() throws IOException {
        Path root = uniqueRoot("agentscope-call-boundary");
        AppStateStore store = new AppStateStore(root);
        store.agentStateRoot("teaching-user", "session-1");
        Path outside = uniqueRoot("agentscope-call-boundary-target");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "unchanged");
        Files.createSymbolicLink(
                root.resolve("agentscope/teaching-user/session-1/agent_state.json"),
                outside.toAbsolutePath());

        assertThrows(
                IllegalArgumentException.class,
                () -> store.verifyAgentStateSlot("teaching-user", "session-1"));
        assertEquals("unchanged", Files.readString(outside));
    }

    @Test
    void corruptedStateDoesNotLeakLockAndCanBeRetried() throws IOException {
        Path root = uniqueRoot("corrupt-retry");
        AppStateStore store = new AppStateStore(root);
        Path state = root.resolve("application/session-1.json");
        Files.writeString(state, "{not-json");

        assertThrows(IllegalStateException.class, () -> store.lock("session-1"));
        Files.delete(state);

        try (LockedSession ignored = store.lock("session-1")) {
            assertFalse(Files.exists(state));
        }
    }

    @Test
    void differentSessionsPersistIsolatedActionsAndCounts() {
        Path root = uniqueRoot("session-isolation");
        AppStateStore store = new AppStateStore(root);

        try (LockedSession first = store.lock("session-a");
                LockedSession second = store.lock("session-b")) {
            first.recordEvidence("query_effective_config", "ev-a", "enabled=true");
            first.complete("query_effective_config", "COMPLETE");
            second.recordEvidence("query_request_facts", "ev-b", "channel=desktop");
            second.complete("query_request_facts", "COMPLETE");
        }

        assertEquals(1, store.read("session-a").callCounts().get("query_effective_config"));
        assertFalse(store.read("session-a").callCounts().containsKey("query_request_facts"));
        assertEquals(1, store.read("session-b").callCounts().get("query_request_facts"));
        assertFalse(store.read("session-b").callCounts().containsKey("query_effective_config"));
    }

    private Path uniqueRoot(String prefix) {
        return Path.of("target", "test-state", prefix + "-" + UUID.randomUUID());
    }
}
