package dev.agentharness.demo.infra;

import dev.agentharness.demo.domain.*;
import dev.agentharness.demo.dto.AssistantReply;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.util.*;

/** 同一 CLI 复用同一 Agent 和内存会话；每次启动创建独立身份。 */
public final class AgentScopeGateway implements AssistantGateway {
    private final ReActAgent agent;
    private final RuntimeContext context;
    private final AppConfig config;
    public AgentScopeGateway(AppConfig config) {
        this(config, config.prompt, dateToolkit());
    }
    private static Toolkit dateToolkit() {
        // 原通用助手仍只注册日期工具。
        var toolkit = new Toolkit();
        toolkit.registerTool(new DateTool(new DateValidation()));
        return toolkit;
    }
    public AgentScopeGateway(AppConfig config, String prompt, Toolkit toolkit) {
        // 注入点仍要求明确的工具集与提示词，不静默构建空能力实例。
        Objects.requireNonNull(toolkit, "toolkit");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("提示词不能为空");
        this.config = config;
        this.context = RuntimeContext.builder().sessionId(UUID.randomUUID().toString()).build();
        // DeepSeek 使用官方适配器；Ollama 仅连接本机环回接口。
        var creation = ModelCreationContext.builder().stream(false).cachePolicy(CachePolicy.DISABLED);
        if (config.provider.equals("ollama")) creation.baseUrl("http://127.0.0.1:11434");
        else creation.apiKey(config.apiKey());
        var model = ModelRegistry.resolve(config.provider + ":" + config.model, creation.build());
        // 单次模型尝试与整轮分别限时，关闭自动重试以方便观察调用次数。
        var execution = ExecutionConfig.builder().timeout(config.requestTimeout).maxAttempts(1).build();
        var options = GenerateOptions.builder().stream(false).maxTokens(config.maxTokens).executionConfig(execution);
        if (config.provider.equals("deepseek")) options.additionalBodyParam("thinking", Map.of("type", "disabled"));
        this.agent = ReActAgent.builder().name("general-assistant").sysPrompt(prompt)
                .model(model).generateOptions(options.build()).modelExecutionConfig(execution)
                .toolkit(toolkit).stateStore(new InMemoryAgentStateStore())
                .middleware(new ModelTrace()).maxIters(config.maxIterations).build();
    }
    @Override public AssistantReply ask(String question) {
        // 固定会话身份让后续提问带上历史；本例不跨进程保存。
        var answer = agent.call(List.of(new UserMessage(question)), context).block(config.turnTimeout);
        if (answer == null || Boolean.TRUE.equals(answer.getMetadata().get(MessageMetadataKeys.SUMMARY_FAILED))
                || answer.getTextContent() == null || answer.getTextContent().isBlank()) {
            // 不把框架收尾失败的非空说明当成正常答案。
            throw new IllegalStateException("未取得可用回答");
        }
        return new AssistantReply(answer.getTextContent(), String.valueOf(answer.getGenerateReason()));
    }
    @Override public void close() {
        // 关闭当前实例；本例没有外部 MCP 子进程。
        agent.close();
    }
}
