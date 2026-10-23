package dev.agentharness.demo.adapter;

import dev.agentharness.demo.app.ChatService;
import java.util.Scanner;

/** 入口只处理终端输入输出，不在这里创建模型或工具。 */
public final class ChatCli {
    public static void run(ChatService service) {
        System.out.println("自由提问；输入 /exit 退出。会话仅保留在当前进程。");
        var scanner = new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8);
        while (scanner.hasNextLine()) {
            String question = scanner.nextLine();
            if (question.equals("/exit")) return;
            if (question.isBlank()) continue;
            // 请求失败由最外层结束当前进程，避免在取消未确认时继续使用同一 Agent。
            var reply = service.ask(question);
            System.out.println(reply.text());
            System.out.println("[结束] reason=" + reply.reason());
        }
    }
}
