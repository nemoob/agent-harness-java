package dev.agentharness.fullstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SafeWorkspaceTest {
    @Test
    void appliesCompleteBoundedPatchAndReturnsDeterministicManifest() throws Exception {
        Path root = root();
        SafeWorkspace workspace = new SafeWorkspace(root);
        WorkspacePatch patch =
                new WorkspacePatch(
                        Map.of(
                                "web/index.html", "<main>safe</main>",
                                "web/styles.css", "body{}",
                                "web/app.js", "document.body.dataset.ready='1';"));

        WorkspaceManifest first = workspace.apply(patch);
        WorkspaceManifest second = workspace.manifest();

        assertEquals(first.sha256(), second.sha256());
        assertEquals(3, first.fileSha256().size());
        assertEquals("<main>safe</main>", Files.readString(root.resolve("web/index.html")));
    }

    @Test
    void rejectsLeafIntermediateOutsideAndBoundarySwapWithoutPartialWrites() throws Exception {
        Path root = root();
        Path outside = root.getParent().resolve("outside-" + UUID.randomUUID());
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("sentinel"), "unchanged");

        Path leafLink = root.resolve("leaf.txt");
        Files.createSymbolicLink(leafLink, outside.resolve("sentinel"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SafeWorkspace(root).apply(new WorkspacePatch(Map.of("leaf.txt", "bad"))));
        assertEquals("unchanged", Files.readString(outside.resolve("sentinel")));

        Path intermediate = root.resolve("linked");
        Files.createSymbolicLink(intermediate, outside);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SafeWorkspace(root)
                                .apply(new WorkspacePatch(Map.of("linked/escape.txt", "bad"))));
        assertFalse(Files.exists(outside.resolve("escape.txt")));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SafeWorkspace(root)
                                .apply(new WorkspacePatch(Map.of("../escape.txt", "bad"))));

        Files.createDirectories(root.resolve("swap"));
        SafeWorkspace boundarySwap =
                new SafeWorkspace(
                        root,
                        () -> {
                            try {
                                Files.delete(root.resolve("swap"));
                                Files.createSymbolicLink(root.resolve("swap"), outside);
                            } catch (Exception exception) {
                                throw new RuntimeException(exception);
                            }
                        });
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        boundarySwap.apply(
                                new WorkspacePatch(
                                        Map.of("safe.txt", "must-not-write", "swap/file.txt", "bad"))));
        assertFalse(Files.exists(root.resolve("safe.txt")));
        assertFalse(Files.exists(outside.resolve("file.txt")));
    }

    @Test
    void rejectsCountPerFileAndTotalLimitsAtomically() throws Exception {
        Path root = root();
        Files.writeString(root.resolve("existing.txt"), "before");
        SafeWorkspace workspace = new SafeWorkspace(root);

        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int index = 0; index < 65; index++) {
            tooMany.put("many/file-" + index, "x");
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> workspace.apply(new WorkspacePatch(tooMany)));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        workspace.apply(
                                new WorkspacePatch(Map.of("large.txt", "x".repeat(262_145)))));

        Map<String, String> tooLarge = new LinkedHashMap<>();
        for (int index = 0; index < 5; index++) {
            tooLarge.put("total/file-" + index, "x".repeat(220_000));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> workspace.apply(new WorkspacePatch(tooLarge)));
        assertEquals("before", Files.readString(root.resolve("existing.txt")));
        assertFalse(Files.exists(root.resolve("many")));
        assertFalse(Files.exists(root.resolve("large.txt")));
        assertFalse(Files.exists(root.resolve("total")));
    }

    @Test
    void completeValidationPreventsPartialWriteWhenOneMemberIsLinked() throws Exception {
        Path root = root();
        Path outside = root.getParent().resolve("atomic-outside-" + UUID.randomUUID());
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("target"), "outside");
        Files.createSymbolicLink(root.resolve("bad.txt"), outside.resolve("target"));
        SafeWorkspace workspace = new SafeWorkspace(root);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        workspace.apply(
                                new WorkspacePatch(
                                        Map.of("good.txt", "new", "bad.txt", "overwrite"))));
        assertFalse(Files.exists(root.resolve("good.txt")));
        assertEquals("outside", Files.readString(outside.resolve("target")));
    }

    private static Path root() throws Exception {
        Path path = Path.of("target", "safe-workspace-test", UUID.randomUUID().toString());
        Files.createDirectories(path);
        assertTrue(path.toRealPath().startsWith(Path.of("target").toRealPath()));
        return path.toRealPath();
    }
}
