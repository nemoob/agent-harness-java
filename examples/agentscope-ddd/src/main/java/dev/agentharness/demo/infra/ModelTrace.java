package dev.agentharness.demo.infra;

import io.agentscope.core.agent.*;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** 记录本进程累计模型阶段，不记录请求正文、凭据或供应商异常正文。 */
public final class ModelTrace implements MiddlewareBase {
    private final AtomicInteger calls = new AtomicInteger();
    @Override public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context,
            ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        // 订阅时计数，避免把仅构造响应链当成模型调用。
        return Flux.defer(() -> {
            int number = calls.incrementAndGet();
            System.out.println("[模型 #" + number + "] 开始");
            return Flux.defer(() -> next.apply(input))
                    .doOnComplete(() -> System.out.println("[模型 #" + number + "] 完成"))
                    .doOnError(error -> System.out.println("[模型 #" + number + "] 失败"))
                    .doOnCancel(() -> System.out.println("[模型 #" + number + "] 取消"));
        });
    }
}
