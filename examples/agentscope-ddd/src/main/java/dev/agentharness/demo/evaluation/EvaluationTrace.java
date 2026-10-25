package dev.agentharness.demo.evaluation;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ActingInput;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** 仅按试次记录实际订阅的模型调用，内部自动重试冻结为一次。 */
public final class EvaluationTrace implements MiddlewareBase {
    private final AtomicInteger calls = new AtomicInteger();
    private final List<EvaluationRunner.ToolAttempt> attempts = new ArrayList<>();
    private final List<ChatUsage> usages = new ArrayList<>();
    @Override public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context,
            ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            input.toolCalls().forEach(call -> {
                String date = call.getInput().get("date") instanceof String value
                        ? Evidence.bounded(value) : "NON_STRING_OR_MISSING";
                String name = "check_date".equals(call.getName()) ? "check_date" : "UNKNOWN_TOOL";
                attempts.add(new EvaluationRunner.ToolAttempt(name, date));
            });
            return next.apply(input);
        });
    }
    @Override public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context,
            ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> {
            calls.incrementAndGet();
            return next.apply(input).doOnNext(event -> {
                if (event instanceof ModelCallEndEvent end) usages.add(end.getUsage());
            });
        });
    }
    public EvaluationRunner.Usage usage() {
        if (calls.get() == 0) return EvaluationRunner.Usage.unknown("no_model_call");
        if (usages.size() != calls.get() || usages.stream().anyMatch(java.util.Objects::isNull))
            return EvaluationRunner.Usage.unknown("response_field_absent");
        int input = usages.stream().mapToInt(ChatUsage::getInputTokens).sum();
        int output = usages.stream().mapToInt(ChatUsage::getOutputTokens).sum();
        return new EvaluationRunner.Usage("known", null, input, output,
                usages.stream().mapToInt(ChatUsage::getCachedTokens).sum(),
                usages.stream().mapToInt(ChatUsage::getCacheCreationTokens).sum(),
                usages.stream().mapToInt(ChatUsage::getReasoningTokens).sum(),
                usages.stream().mapToInt(ChatUsage::getToolUsePromptTokens).sum(),
                usages.stream().mapToInt(ChatUsage::getTotalTokens).sum(),
                usages.stream().mapToDouble(ChatUsage::getTime).sum(),
                "agentscope_2_0_4_ModelCallEndEvent.getUsage");
    }
    public int calls() { return calls.get(); }
    public List<EvaluationRunner.ToolAttempt> attempts() { return List.copyOf(attempts); }
}
