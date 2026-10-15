package dev.agentharness.fullstack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 跨进程保守结转并在联网前原子预留的模型请求与输出预算。 */
public final class ModelBudget {
    static final int TASK_MAX_REQUESTS = 28;
    static final int TASK_MAX_OUTPUT_TOKENS = 60_000;
    static final int FIXED_PRIOR_REQUESTS = 14;
    static final int FIXED_PRIOR_OUTPUT_TOKENS = 38_079;
    private static final long NEW_RUN_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final long AUDITED_MIGRATION_MILLIS = Duration.ofMinutes(20).toMillis();
    private static final String NEW_RUN_MARKER = "NEW_RUN_30M";
    private static final String AUDITED_MIGRATION_MARKER = "AUDITED_V1_14_38079_20M";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String LEDGER_FILE = "model-budget.json";
    private final int maxRequests;
    private final int priorRequests;
    private final int maxTokensPerRequest;
    private final int maxOutputTokens;
    private final LongSupplier epochMillis;
    private final long startedAtEpochMillis;
    private final long deadlineEpochMillis;
    private final String migrationMarker;
    private final Path ledgerFile;
    private int totalRequests;
    private int reservedOutputTokens;
    private int inputTokens;
    private int outputTokens;
    private int pendingCap;

    public ModelBudget(
            int maxRequests,
            int maxTokensPerRequest,
            int maxOutputTokens,
            Duration maxWall,
            LongSupplier epochMillis) {
        this(
                maxRequests,
                maxTokensPerRequest,
                maxOutputTokens,
                epochMillis,
                timing(epochMillis, maxWall, NEW_RUN_MARKER),
                0,
                0,
                0,
                0,
                0,
                null);
    }

    private ModelBudget(
            int maxRequests,
            int maxTokensPerRequest,
            int maxOutputTokens,
            LongSupplier epochMillis,
            Timing timing,
            int priorRequests,
            int totalRequests,
            int reservedOutputTokens,
            int inputTokens,
            int outputTokens,
            Path ledgerFile) {
        this(
                maxRequests,
                maxTokensPerRequest,
                maxOutputTokens,
                epochMillis,
                timing.startedAtEpochMillis(),
                timing.deadlineEpochMillis(),
                timing.marker(),
                priorRequests,
                totalRequests,
                reservedOutputTokens,
                inputTokens,
                outputTokens,
                ledgerFile);
    }

    private ModelBudget(
            int maxRequests,
            int maxTokensPerRequest,
            int maxOutputTokens,
            LongSupplier epochMillis,
            long startedAtEpochMillis,
            long deadlineEpochMillis,
            String migrationMarker,
            int priorRequests,
            int totalRequests,
            int reservedOutputTokens,
            int inputTokens,
            int outputTokens,
            Path ledgerFile) {
        // 所有上限与结转值必须形成合法的保守预算。
        if (maxRequests <= 0
                || maxTokensPerRequest <= 0
                || maxOutputTokens <= 0
                || priorRequests < 0
                || totalRequests < priorRequests
                || totalRequests > maxRequests
                || reservedOutputTokens < 0
                || reservedOutputTokens > maxOutputTokens
                || inputTokens < 0
                || outputTokens < 0
                || startedAtEpochMillis < 0
                || deadlineEpochMillis <= startedAtEpochMillis) {
            throw new IllegalArgumentException("model budget limits invalid");
        }
        // 保存不可被模型或 CLI 提高的固定上限与受信历史基线。
        this.maxRequests = maxRequests;
        this.priorRequests = priorRequests;
        this.maxTokensPerRequest = maxTokensPerRequest;
        this.maxOutputTokens = maxOutputTokens;
        this.epochMillis = Objects.requireNonNull(epochMillis, "epochMillis");
        this.startedAtEpochMillis = startedAtEpochMillis;
        this.deadlineEpochMillis = deadlineEpochMillis;
        this.migrationMarker = Objects.requireNonNull(migrationMarker, "migrationMarker");
        this.ledgerFile = ledgerFile;
        // 当前计数可能来自可信 ledger，重启不得归零。
        this.totalRequests = totalRequests;
        this.reservedOutputTokens = reservedOutputTokens;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public static ModelBudget fullRun() {
        // 回放和普通测试使用同一硬上限，但不携带真实历史消耗。
        return new ModelBudget(
                TASK_MAX_REQUESTS,
                4_096,
                TASK_MAX_OUTPUT_TOKENS,
                Duration.ofMinutes(30),
                System::currentTimeMillis);
    }

    public static ModelBudget fullRunWithPriorRequests(int priorRequests) {
        // 旧测试入口按每个未知历史请求 4096 输出做保守结转。
        return fullRunWithPriorUsage(priorRequests, Math.multiplyExact(priorRequests, 4_096));
    }

    static ModelBudget fullRunWithPriorUsage(int priorRequests, int priorOutputTokens) {
        // 受信历史只能消耗预算，不能超过任务硬上限。
        if (priorRequests < 0
                || priorRequests > TASK_MAX_REQUESTS
                || priorOutputTokens < 0
                || priorOutputTokens > TASK_MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("prior model usage invalid");
        }
        LongSupplier clock = System::currentTimeMillis;
        return new ModelBudget(
                TASK_MAX_REQUESTS,
                4_096,
                TASK_MAX_OUTPUT_TOKENS,
                clock,
                timing(clock, Duration.ofMillis(NEW_RUN_MILLIS), NEW_RUN_MARKER),
                priorRequests,
                priorRequests,
                priorOutputTokens,
                0,
                0,
                null);
    }

    public static ModelBudget fromTrustedEnvironment(Path runDirectory) {
        return fromTrustedEnvironment(runDirectory, System::currentTimeMillis);
    }

    static ModelBudget fromTrustedEnvironment(Path runDirectory, LongSupplier epochMillis) {
        // 固定事实种子为 14 请求/38079 预留输出；环境只允许进一步减少剩余额度。
        int seededRequests = trustedFloor("AGENT_HARNESS_REAL_REQUESTS_USED", FIXED_PRIOR_REQUESTS);
        int seededOutput =
                trustedFloor(
                        "AGENT_HARNESS_REAL_OUTPUT_TOKENS_USED",
                        FIXED_PRIOR_OUTPUT_TOKENS);
        Path ledger = trustedLedger(runDirectory);
        long now = epochMillis.getAsLong();
        Ledger state = readLedger(ledger, seededRequests, seededOutput, now);
        ModelBudget budget =
                new ModelBudget(
                        TASK_MAX_REQUESTS,
                        4_096,
                        TASK_MAX_OUTPUT_TOKENS,
                        epochMillis,
                        state.startedAtEpochMillis(),
                        state.deadlineEpochMillis(),
                        state.migrationMarker(),
                        FIXED_PRIOR_REQUESTS,
                        state.totalRequests(),
                        state.reservedOutputTokens(),
                        state.inputTokens(),
                        state.outputTokens(),
                        ledger);
        // 崩溃时仍为 pending 的请求结果未知，保留完整 cap 并只清 pending 标记。
        if (state.pendingCap() > 0) {
            budget.persistAndApply(
                    state.totalRequests(),
                    state.reservedOutputTokens(),
                    state.inputTokens(),
                    state.outputTokens(),
                    0);
        } else if (Files.notExists(ledger, LinkOption.NOFOLLOW_LINKS)
                || state.schemaVersion() == 1) {
            // 首次启动或审计过的 v1 迁移立即落盘 v2，后续重启不得续期。
            budget.persistAndApply(
                    state.totalRequests(),
                    state.reservedOutputTokens(),
                    state.inputTokens(),
                    state.outputTokens(),
                    0);
        }
        return budget;
    }

    public synchronized RequestWindow beforeRequest(
            FullStackRole role,
            int invocation,
            boolean finalRound,
            int requestedMaxTokens) {
        int fixedCap = fixedCap(role, invocation, finalRound);
        // 调用方只能降低对应角色/阶段 cap，不能通过输入提高。
        if (requestedMaxTokens <= 0
                || requestedMaxTokens > fixedCap
                || requestedMaxTokens > maxTokensPerRequest) {
            throw new IllegalArgumentException("max_tokens exceeds the fixed role cap");
        }
        // 未结算请求禁止并行叠加，确保每次 reservation 可追踪。
        if (pendingCap != 0) {
            throw new IllegalStateException("model request result is still pending");
        }
        long remainingMillis = remainingMillis();
        // 在产生网络副作用前检查持久绝对截止时间、请求和输出预算。
        if (remainingMillis <= 0) {
            throw new IllegalStateException("model wall-clock budget exhausted");
        }
        if (totalRequests >= maxRequests) {
            throw new IllegalStateException("model request budget exhausted");
        }
        if ((long) reservedOutputTokens + requestedMaxTokens > maxOutputTokens) {
            throw new IllegalStateException("model output budget exhausted");
        }
        // 请求数与完整 cap 先原子持久化，成功返回后 transport 才能联网。
        persistAndApply(
                totalRequests + 1,
                reservedOutputTokens + requestedMaxTokens,
                inputTokens,
                outputTokens,
                requestedMaxTokens);
        long remainingAfterReservation = remainingMillis();
        // 原子落盘期间若越过 deadline，已知未联网，释放 cap 但保留请求计数并停止。
        if (remainingAfterReservation <= 0) {
            persistAndApply(
                    totalRequests,
                    reservedOutputTokens - requestedMaxTokens,
                    inputTokens,
                    outputTokens,
                    0);
            throw new IllegalStateException("model wall-clock budget exhausted");
        }
        Duration networkCap =
                role == FullStackRole.FRONTEND
                        ? Duration.ofSeconds(180)
                        : Duration.ofSeconds(120);
        Duration processCap =
                role == FullStackRole.FRONTEND
                        ? Duration.ofSeconds(185)
                        : Duration.ofSeconds(125);
        // 网络与 Java 进程 deadline 都不得超过持久任务 deadline 的剩余时间。
        return new RequestWindow(
                minDuration(networkCap, remainingAfterReservation),
                minDuration(processCap, remainingAfterReservation));
    }

    public synchronized void record(ModelUsage usage) {
        Objects.requireNonNull(usage, "usage");
        if (pendingCap == 0) {
            throw new IllegalStateException("model request reservation missing");
        }
        // 供应商真实输出不得超过已发送 max_tokens；异常时保留完整预留。
        if (usage.outputTokens() > pendingCap) {
            throw new IllegalStateException("model usage exceeds reserved output");
        }
        int settledReserved = reservedOutputTokens - pendingCap + usage.outputTokens();
        persistAndApply(
                totalRequests,
                settledReserved,
                Math.addExact(inputTokens, usage.inputTokens()),
                Math.addExact(outputTokens, usage.outputTokens()),
                0);
    }

    public synchronized void recordFailure(boolean definitelyPreConnect) {
        if (pendingCap == 0) {
            throw new IllegalStateException("model request reservation missing");
        }
        // 只有明确未连接/未发送才释放输出 cap；未知结果保留完整消耗。
        int settledReserved =
                definitelyPreConnect ? reservedOutputTokens - pendingCap : reservedOutputTokens;
        persistAndApply(totalRequests, settledReserved, inputTokens, outputTokens, 0);
    }

    public boolean mayRetry(boolean preConnectFailure, int attempt) {
        // 总任务只剩固定 16 次调用，所有失败路径都禁止自动重试。
        return false;
    }

    public synchronized int requests() {
        // 返回固定历史种子之后本任务已经预留的请求数。
        return totalRequests - priorRequests;
    }

    public synchronized int totalRequests() {
        return totalRequests;
    }

    public synchronized int inputTokens() {
        return inputTokens;
    }

    public synchronized int outputTokens() {
        return outputTokens;
    }

    public synchronized int totalReservedOutputTokens() {
        return reservedOutputTokens;
    }

    synchronized long startedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    synchronized long deadlineEpochMillis() {
        return deadlineEpochMillis;
    }

    synchronized long remainingMillis() {
        return Math.max(0L, deadlineEpochMillis - epochMillis.getAsLong());
    }

    static int fixedCap(FullStackRole role, int invocation, boolean finalRound) {
        Objects.requireNonNull(role, "role");
        // 先校验服务端角色与固定 invocation，再选择该阶段 cap。
        int firstRoundCap = switch (invocation) {
            case 1 -> requireRole(role, FullStackRole.ARCHITECT, 1_024);
            case 2 -> requireRole(role, FullStackRole.FRONTEND, 3_072);
            case 3 -> requireRole(role, FullStackRole.BACKEND, 4_096);
            case 4 -> requireRole(role, FullStackRole.TEST, 1_024);
            case 5 -> requireRole(role, FullStackRole.TROUBLESHOOT, 1_024);
            case 6 -> requireRole(role, FullStackRole.BACKEND, 4_096);
            case 7 -> requireRole(role, FullStackRole.TEST, 1_024);
            case 8 -> requireRole(role, FullStackRole.RELEASE, 1_024);
            default -> throw new IllegalArgumentException("model invocation is outside fixed plan");
        };
        // 匹配 ToolResult 后的最终确认统一限制为 256。
        return finalRound ? 256 : firstRoundCap;
    }

    private static int requireRole(FullStackRole actual, FullStackRole expected, int cap) {
        if (actual != expected) {
            throw new IllegalArgumentException("model role does not match fixed invocation");
        }
        return cap;
    }

    private void persistAndApply(
            int nextRequests,
            int nextReserved,
            int nextInput,
            int nextOutput,
            int nextPending) {
        Ledger next =
                new Ledger(
                        2,
                        maxRequests,
                        maxOutputTokens,
                        startedAtEpochMillis,
                        deadlineEpochMillis,
                        migrationMarker,
                        nextRequests,
                        nextReserved,
                        nextInput,
                        nextOutput,
                        nextPending);
        if (ledgerFile != null) {
            writeLedger(ledgerFile, next);
        }
        // 仅在 ledger 原子替换成功后更新内存，避免网络前状态分叉。
        totalRequests = nextRequests;
        reservedOutputTokens = nextReserved;
        inputTokens = nextInput;
        outputTokens = nextOutput;
        pendingCap = nextPending;
    }

    private static Ledger readLedger(
            Path ledger, int seededRequests, int seededOutput, long now) {
        try {
            if (Files.notExists(ledger, LinkOption.NOFOLLOW_LINKS)) {
                return new Ledger(
                        2,
                        TASK_MAX_REQUESTS,
                        TASK_MAX_OUTPUT_TOKENS,
                        now,
                        Math.addExact(now, NEW_RUN_MILLIS),
                        NEW_RUN_MARKER,
                        seededRequests,
                        seededOutput,
                        0,
                        0,
                        0);
            }
            if (Files.isSymbolicLink(ledger)
                    || !Files.isRegularFile(ledger, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("model budget ledger invalid");
            }
            com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(ledger.toFile());
            int schemaVersion = root.path("schemaVersion").asInt(-1);
            if (schemaVersion == 1) {
                return migrateAuditedV1(root, seededRequests, seededOutput, now);
            }
            Ledger stored = JSON.treeToValue(root, Ledger.class);
            if (stored.schemaVersion() != 2
                    || stored.maxRequests() != TASK_MAX_REQUESTS
                    || stored.maxOutputTokens() != TASK_MAX_OUTPUT_TOKENS
                    || stored.totalRequests() < seededRequests
                    || stored.totalRequests() > TASK_MAX_REQUESTS
                    || stored.reservedOutputTokens() < seededOutput
                    || stored.reservedOutputTokens() > TASK_MAX_OUTPUT_TOKENS
                    || stored.inputTokens() < 0
                    || stored.outputTokens() < 0
                    || stored.outputTokens() > stored.reservedOutputTokens()
                    || stored.pendingCap() < 0
                    || stored.pendingCap() > 4_096
                    || stored.pendingCap() > stored.reservedOutputTokens()
                    || stored.startedAtEpochMillis() <= 0
                    || stored.startedAtEpochMillis() > now
                    || stored.deadlineEpochMillis() <= stored.startedAtEpochMillis()
                    || stored.deadlineEpochMillis() <= now
                    || stored.deadlineEpochMillis() - stored.startedAtEpochMillis()
                            > NEW_RUN_MILLIS
                    || !validMarker(stored)) {
                throw new IllegalStateException("model budget ledger binding invalid");
            }
            return stored;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("model budget ledger cannot be read", exception);
        }
    }

    private static Ledger migrateAuditedV1(
            com.fasterxml.jackson.databind.JsonNode root,
            int seededRequests,
            int seededOutput,
            long now) {
        // 只接受本次已审计失败现场的精确 v1 账本，其他旧账本一律 fail closed。
        if (root.path("maxRequests").asInt(-1) != 27
                || root.path("maxOutputTokens").asInt(-1) != TASK_MAX_OUTPUT_TOKENS
                || root.path("totalRequests").asInt(-1) != FIXED_PRIOR_REQUESTS
                || root.path("reservedOutputTokens").asInt(-1)
                        != FIXED_PRIOR_OUTPUT_TOKENS
                || root.path("inputTokens").asInt(-1) != 5_367
                || root.path("outputTokens").asInt(-1) != 1_026
                || root.path("pendingCap").asInt(-1) != 0
                || seededRequests > FIXED_PRIOR_REQUESTS
                || seededOutput > FIXED_PRIOR_OUTPUT_TOKENS) {
            throw new IllegalStateException("model budget v1 migration not authorized");
        }
        return new Ledger(
                1,
                TASK_MAX_REQUESTS,
                TASK_MAX_OUTPUT_TOKENS,
                now,
                Math.addExact(now, AUDITED_MIGRATION_MILLIS),
                AUDITED_MIGRATION_MARKER,
                FIXED_PRIOR_REQUESTS,
                FIXED_PRIOR_OUTPUT_TOKENS,
                5_367,
                1_026,
                0);
    }

    private static boolean validMarker(Ledger stored) {
        long duration = stored.deadlineEpochMillis() - stored.startedAtEpochMillis();
        return (NEW_RUN_MARKER.equals(stored.migrationMarker())
                        && duration == NEW_RUN_MILLIS)
                || (AUDITED_MIGRATION_MARKER.equals(stored.migrationMarker())
                        && duration == AUDITED_MIGRATION_MILLIS);
    }

    private static Duration minDuration(Duration cap, long remainingMillis) {
        return Duration.ofMillis(Math.min(cap.toMillis(), remainingMillis));
    }

    private static Timing timing(LongSupplier clock, Duration duration, String marker) {
        // 只读取一次受信时钟，避免 startedAt 与 deadline 因跨毫秒取值产生漂移。
        long startedAt = Objects.requireNonNull(clock, "clock").getAsLong();
        return new Timing(startedAt, Math.addExact(startedAt, duration.toMillis()), marker);
    }

    private static void writeLedger(Path ledger, Ledger value) {
        Path temporary = null;
        try {
            // 每次写前再次拒绝预置链接或特殊文件。
            if (Files.exists(ledger, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(ledger)
                            || !Files.isRegularFile(ledger, LinkOption.NOFOLLOW_LINKS))) {
                throw new IllegalStateException("model budget ledger invalid");
            }
            temporary = Files.createTempFile(ledger.getParent(), ".model-budget-", ".tmp");
            JSON.writeValue(temporary.toFile(), value);
            Files.move(
                    temporary,
                    ledger,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception exception) {
            throw new IllegalStateException("model budget ledger cannot be saved", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // 临时文件清理失败不覆盖预算持久化主错误。
                }
            }
        }
    }

    private static Path trustedLedger(Path runDirectory) {
        try {
            Path normalized = runDirectory.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                    || !normalized.toRealPath().equals(normalized)) {
                throw new IllegalArgumentException("model budget run directory invalid");
            }
            return normalized.resolve(LEDGER_FILE);
        } catch (Exception exception) {
            throw new IllegalArgumentException("model budget run directory cannot be verified", exception);
        }
    }

    private static int trustedFloor(String name, int minimum) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return minimum;
        }
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < minimum) {
                throw new IllegalStateException("trusted model usage cannot reduce fixed history");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("trusted model usage is invalid", exception);
        }
    }

    private record Ledger(
            int schemaVersion,
            int maxRequests,
            int maxOutputTokens,
            long startedAtEpochMillis,
            long deadlineEpochMillis,
            String migrationMarker,
            int totalRequests,
            int reservedOutputTokens,
            int inputTokens,
            int outputTokens,
            int pendingCap) {}

    private record Timing(long startedAtEpochMillis, long deadlineEpochMillis, String marker) {}

    public record RequestWindow(Duration networkTimeout, Duration processTimeout) {}
}
