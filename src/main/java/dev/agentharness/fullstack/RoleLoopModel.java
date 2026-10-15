package dev.agentharness.fullstack;

import io.agentscope.core.model.Model;
import java.util.List;

/** RoleAgentExecutor 需要收集的最小模型闭环证据。 */
interface RoleLoopModel extends Model {
    int calls();

    List<String> toolSchemas();

    boolean toolResultMatched();
}
