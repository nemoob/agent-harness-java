package dev.agentharness.demo.evaluation;

import dev.agentharness.demo.infra.AppConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.CachePolicy;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 每个试次新建模型装配、Agent、工具箱、会话和内存状态，结束即关闭。 */
public final class LiveExecutor implements EvaluationRunner.Executor {
    private final AppConfig config;
    public LiveExecutor(AppConfig config) { this.config = config; }

    @Override public EvaluationRunner.Observation execute(EvaluationRunner.Plan plan) {
        // 工具实例只服务当前试次，记录与下一次执行互不共享。
        EvaluationTool tool = new EvaluationTool();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        // 会话与状态标签为当前新实例生成，供离线检查隔离性。
        String sessionId = UUID.randomUUID().toString();
        String stateStoreId = UUID.randomUUID().toString();
        RuntimeContext context = RuntimeContext.builder().sessionId(sessionId).build();
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        EvaluationTrace trace = new EvaluationTrace();
        // 与公开运行入口使用相同 DeepSeek 官方适配器和受限请求参数。
        var creation = ModelCreationContext.builder().stream(false).cachePolicy(CachePolicy.DISABLED)
                .apiKey(config.apiKey()).build();
        var model = ModelRegistry.resolve("deepseek:" + config.model, creation);
        var execution = ExecutionConfig.builder().timeout(config.requestTimeout).maxAttempts(EvaluationRunner.MAX_ATTEMPTS).build();
        var options = GenerateOptions.builder().stream(false).maxTokens(config.maxTokens)
                .executionConfig(execution).additionalBodyParam("thinking", Map.of("type", "disabled")).build();
        try (ReActAgent agent = ReActAgent.builder().name("date-evaluation").sysPrompt(plan.prompt())
                .model(model).generateOptions(options).modelExecutionConfig(execution)
                .toolkit(toolkit).stateStore(store).middleware(trace).maxIters(config.maxIterations).build()) {
            // 一题只有当前日期，不携带此前用户对话或历史工具结果。
            var answer = agent.call(List.of(new UserMessage(EvaluationRunner.userTask(plan.date()))), context)
                    .block(config.turnTimeout);
            String text = answer == null ? null : answer.getTextContent();
            String reason = answer == null ? "NO_ANSWER" : String.valueOf(answer.getGenerateReason());
            return new EvaluationRunner.Observation(text, reason, trace.calls(), tool.calls(), sessionId, stateStoreId, trace.attempts(), trace.usage());
        } catch (RuntimeException failure) {
            // 供应商异常正文不外传，只保存已发生的调用与工具事实。
            throw new EvaluationRunner.TrialFailure(trace.calls(), tool.calls(), trace.attempts(), sessionId, stateStoreId, trace.usage());
        }
    }
}
