package dev.agentharness.demo.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** 证据只保存固定题目、受限答案和公开源码摘要。 */
public final class Evidence {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private Evidence() {}

    // 不让模型控制字符或过长答案破坏证据文件与终端。
    public static String bounded(String answer) {
        if (answer == null) return null;
        StringBuilder clean = new StringBuilder();
        answer.codePoints().limit(4096).forEach(point -> {
            if (point == '\n' || point == '\t' || point >= 0x20 && point != 0x7f) clean.appendCodePoint(point);
            else clean.append(String.format("\\u%04x", point));
        });
        if (answer.codePointCount(0, answer.length()) > 4096) clean.append("[TRUNCATED]");
        return clean.toString();
    }

    public static String sha256(String content) {
        return digest(content.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(Path path) throws IOException {
        return digest(Files.readAllBytes(path));
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }

    public static Map<String, Object> readFrozen(Path path, Map<String, Object> expected) throws IOException {
        // 运行前逐项核对计划、提示词与源码摘要，运行ID和冻结时间除外。
        Map<String, Object> existing = JSON.readValue(path.toFile(), LinkedHashMap.class);
        ObjectNode actualTree = (ObjectNode) JSON.valueToTree(existing);
        ObjectNode expectedTree = (ObjectNode) JSON.valueToTree(expected);
        for (ObjectNode tree : new ObjectNode[] {actualTree, expectedTree}) {
            tree.remove("run_id");
            tree.remove("frozenAt");
        }
        if (!actualTree.equals(expectedTree)) throw new IllegalStateException("冻结清单与当前源码或配置不一致");
        return existing;
    }

    public static void write(Path path, Map<String, Object> data) throws IOException {
        // 证据路径由入口固定，先写同目录临时文件再替换，避免半份 JSON。
        Path temporary = Files.createTempFile(path.getParent(), "evaluation-", ".tmp");
        try {
            JSON.writeValue(temporary.toFile(), data);
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
