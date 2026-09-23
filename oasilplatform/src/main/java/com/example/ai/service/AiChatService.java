package com.example.ai.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 智能问答：角色设定（buildSystemPrompt 里的 sb.append）+ 会话记忆 + 聊天日志。
 *
 * <p>已移除本地知识库检索：回答只依据下面 buildSystemPrompt() 里自己设定的角色信息，
 * 加上 MessageChatMemoryAdvisor 提供的对话记忆（用于理解指代与追问）。</p>
 */
@Service
public class AiChatService {

    /** Spring AI 会话记忆上下文键名：MessageChatMemoryAdvisor 从 request context 读取，缺省会抛 conversationId cannot be null */
    private static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    /** 已撤销登录功能，所有访问者共用同一个会话记忆与日志用户名 */
    private static final String DEFAULT_CONVERSATION_ID = "default";
    private static final String GUEST_NAME = "访客";

    @Autowired
    private ChatMemory chatMemory;

    private final ChatClient chatClient;
    private final ChatLogService chatLogService;

    public AiChatService(ChatClient.Builder chatClientBuilder, ChatLogService chatLogService) {
        this.chatLogService = chatLogService;

        // builder 链：默认顾问（Spring AI 上下文日志）
        this.chatClient = chatClientBuilder
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    /** 聊天：角色设定 -> 生成回答 -> 写入聊天日志 */
    public String chat(String message) {
        String answer = chatClient
                .prompt()
                .system(buildSystemPrompt())                      /* 角色设定 */
                .advisors(a -> a                                  /* 上下文 */
                        .advisors(
                                MessageChatMemoryAdvisor.builder(chatMemory).build()
                        )
                        .param(CHAT_MEMORY_CONVERSATION_ID_KEY, DEFAULT_CONVERSATION_ID)
                )
                .user(message)
                .call()
                .content();

        chatLogService.log(GUEST_NAME, message, answer == null ? "" : answer);
        return answer;
    }

    /**
     * 角色设定：想改人设 / 回答规则，直接改下面 sb.append(""" ... """) 里的文字即可，
     * 需要再加规则就继续 sb.append(""" ... """) 追加（也可以用 sb.append(变量) 拼自己的内容）。
     */
    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();

        sb.append("""
               //
                 你是雌小鬼。
               //
                """);

        return sb.toString();
    }
}
