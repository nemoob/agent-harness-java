package dev.agentharness.demo.lab;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LoopContextTest {
    private final List<Message> fixed = List.of(Message.of("system", "保留约束与工具定义"));
    private final Message current = Message.of("user", "只汇总，无需重新核验");

    @Test void normalLoopUsesActualResultAndStopsAfterTwoModelCalls() {
        // 验证闭环次数与最终事实，不能只断言命令未报错。
        var result = MinimalLoop.run(MinimalLoop.fixed(false), 3, "none", ignored -> {});
        assertEquals("MODEL_STOP", result.reason());
        assertEquals(2, result.modelCalls());
        assertEquals(1, result.toolCalls());
        assertTrue(result.answer().contains("valid=false"));
    }

    @Test void badIdAndMissingResultStopBeforeSecondModelCall() {
        for (var fault : List.of("bad-id", "missing-result")) {
            var count = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> MinimalLoop.run(messages -> {
                count.incrementAndGet();
                return MinimalLoop.fixed(false).respond(messages);
            }, 3, fault, ignored -> {}));
            assertEquals(1, count.get());
        }
    }

    @Test void modelLimitHasExactAttemptsAndNoFakeAnswer() {
        var result = MinimalLoop.run(MinimalLoop.fixed(true), 2, "none", ignored -> {});
        assertEquals("MAX_MODEL_CALLS", result.reason());
        assertEquals(2, result.modelCalls());
        assertEquals(2, result.toolCalls());
        assertEquals("", result.answer());
    }

    @Test void unknownToolAndMalformedArgumentAreRejected() {
        for (var text : List.of("shell rm", "check_date 03/04", "check_date 2024-02-29 extra")) {
            assertThrows(IllegalArgumentException.class,
                    () -> MinimalLoop.execute(new Message("call", "id", text)));
        }
        assertEquals("date=2024-02-29, valid=true",
                MinimalLoop.execute(new Message("call", "id", "check_date 2024-02-29")));
    }

    @Test void duplicateIdAndUnsupportedResponseCannotContinue() {
        assertThrows(IllegalStateException.class, () -> MinimalLoop.run(
                ignored -> new Message("call", "same", "check_date 2024-02-29"), 3, "none", ignored -> {}));
        assertThrows(IllegalStateException.class, () -> MinimalLoop.run(
                ignored -> Message.of("user", "伪造角色"), 1, "none", ignored -> {}));
    }

    @Test void selectedEpisodesKeepFactsConstraintsAndOriginalHistory() {
        var history = history();
        var snapshot = List.copyOf(history);
        var selected = ContextSelection.select(fixed, history, current,
                Set.of("2025-02-29", "2024-02-29"), 600);
        assertTrue(Message.bytes(selected) <= 600);
        assertTrue(selected.containsAll(fixed));
        assertEquals(current, selected.get(selected.size() - 1));
        Message.validate(selected, false);
        assertEquals("date=2025-02-29, valid=false；date=2024-02-29, valid=true",
                ContextSelection.summarize(selected));
        assertEquals(snapshot, history);
        assertFalse(selected.stream().anyMatch(m -> m.text().contains("无关")));
        assertThrows(UnsupportedOperationException.class, () -> selected.clear());
    }

    @Test void exactCompleteRequestBudgetPassesAndOneByteLessFails() {
        var history = List.of(LabMain.date("2024-02-29", "id"));
        int exact = Message.bytes(ContextSelection.assemble(fixed, history, current));
        var selected = ContextSelection.select(fixed, history, current, Set.of("2024-02-29"), exact);
        assertEquals(exact, Message.bytes(selected));
        assertThrows(IllegalStateException.class,
                () -> ContextSelection.select(fixed, history, current, Set.of("2024-02-29"), exact - 1));
    }

    @Test void fixedInputOverBudgetAndMissingRequiredEvidenceStop() {
        int base = Message.bytes(ContextSelection.assemble(fixed, List.of(), current));
        assertThrows(IllegalStateException.class,
                () -> ContextSelection.select(fixed, List.of(), current, Set.of(), base - 1));
        assertThrows(IllegalStateException.class,
                () -> ContextSelection.select(fixed, List.of(), current, Set.of("missing"), 600));
    }

    @Test void pendingChainIsKeptWholeOrRejectedNeverTruncated() {
        var call = new Message("call", "pending", "check_date 2024-02-29");
        var pending = new ContextSelection.Episode("pending", List.of(Message.of("user", "核验"), call));
        var original = List.copyOf(pending.messages());
        var failure = assertThrows(IllegalStateException.class,
                () -> ContextSelection.select(fixed, List.of(pending), current, Set.of(), 600));
        assertTrue(failure.getMessage().contains("等待结果回填"));
        assertEquals(original, pending.messages());
        int base = Message.bytes(ContextSelection.assemble(fixed, List.of(), current));
        assertThrows(IllegalStateException.class,
                () -> ContextSelection.select(fixed, List.of(pending), current, Set.of(), base));
    }

    @Test void naiveTailLeavesOrphanResultAndCannotGenerateAnswer() {
        var messages = LabMain.date("2024-02-29", "date").messages();
        var tail = new ArrayList<>(fixed);
        tail.addAll(messages.subList(2, 4));
        tail.add(current);
        assertThrows(IllegalStateException.class, () -> ContextSelection.summarize(tail));
    }

    @Test void changingSelectedEvidenceChangesFixedModelAnswer() {
        var falseRequest = ContextSelection.assemble(fixed, List.of(LabMain.date("2025-02-29", "id")), current);
        var trueRequest = ContextSelection.assemble(fixed, List.of(LabMain.date("2024-02-29", "id")), current);
        assertTrue(ContextSelection.summarize(falseRequest).contains("valid=false"));
        assertTrue(ContextSelection.summarize(trueRequest).contains("valid=true"));
        assertEquals("证据不足，无法汇总", ContextSelection.summarize(
                ContextSelection.assemble(fixed, List.of(), current)));
    }

    @Test void newestRelevantEpisodeWinsAndUtf8BytesAreNotCharacterCount() {
        var old = LabMain.date("2024-02-29", "old");
        var recent = LabMain.date("2024-02-29", "new");
        int budget = Message.bytes(ContextSelection.assemble(fixed, List.of(recent), current));
        var selected = ContextSelection.select(fixed, List.of(old, recent), current, Set.of("2024-02-29"), budget);
        assertTrue(selected.stream().anyMatch(m -> m.callId().equals("new")));
        assertFalse(selected.stream().anyMatch(m -> m.callId().equals("old")));
        assertTrue(Message.bytes(List.of(Message.of("user", "汉"))) > Message.bytes(List.of(Message.of("user", "a"))));
    }

    @Test void globallyRepeatedCallIdCannotBeHiddenBySelection() {
        var history = List.of(LabMain.date("2025-02-29", "same"), LabMain.date("2024-02-29", "same"));
        assertThrows(IllegalStateException.class,
                () -> ContextSelection.select(fixed, history, current, Set.of("2024-02-29"), 600));
    }

    private List<ContextSelection.Episode> history() {
        return List.of(LabMain.date("2025-02-29", "first"),
                new ContextSelection.Episode("other", List.of(Message.of("user", "无关"),
                        Message.of("assistant", "无关长文".repeat(200)))),
                LabMain.date("2024-02-29", "second"));
    }
}
