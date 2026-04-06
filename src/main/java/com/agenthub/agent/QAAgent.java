package com.agenthub.agent;

import com.agenthub.model.AskRequest;
import com.agenthub.model.ChatMessageRecord;
import com.agenthub.model.QAResult;
import com.agenthub.service.ChatMemoryService;
import com.agenthub.service.KnowledgeGraphService;
import com.agenthub.service.VectorStoreService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 问答 Agent (Java版)
 */
@Component
public class QAAgent {

    private static final String ANSWER_PROMPT = """
            你是一个专业的企业知识问答助手。请结合会话记忆和检索到的上下文回答用户问题。
            要求：
            1. 优先基于检索上下文回答
            2. 会话摘要和最近对话只用于承接上下文，不得编造事实
            3. 如果信息不足，请明确说明
            """;

    private final ChatClient chatClient;
    private final ChatMemoryService chatMemoryService;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeGraphService knowledgeGraphService;

    public QAAgent(ChatClient.Builder chatClientBuilder,
                   ChatMemoryService chatMemoryService,
                   VectorStoreService vectorStoreService,
                   KnowledgeGraphService knowledgeGraphService) {
        this.chatClient = chatClientBuilder.build();
        this.chatMemoryService = chatMemoryService;
        this.vectorStoreService = vectorStoreService;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    public QAResult answer(AskRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }

        var session = chatMemoryService.getOrCreateSession(
                request.getUserId(),
                request.getSessionId(),
                request.isAutoCreateSession(),
                request.getQuestion()
        );
        String userId = session.getUserId();
        String sessionId = session.getSessionId();
        String question = request.getQuestion();

        chatMemoryService.appendUserMessage(userId, sessionId, question);
        ChatMemoryService.MemorySnapshot memorySnapshot = chatMemoryService.loadMemorySnapshot(userId, sessionId);

        List<String> reasoning = new ArrayList<>();

        List<QAResult.RetrievedContext> vectorResults = vectorStoreService.search(question, 5);
        reasoning.add("向量检索命中 " + vectorResults.size() + " 条结果");

        List<QAResult.RetrievedContext> graphResults = knowledgeGraphService.searchByQuestion(question);
        reasoning.add("图谱检索命中 " + graphResults.size() + " 条结果");

        List<QAResult.RetrievedContext> merged = hybridRerank(vectorResults, graphResults);
        List<QAResult.RetrievedContext> topContexts = merged.subList(0, Math.min(8, merged.size()));

        String contextText = buildContextText(topContexts);
        String answerText = generateAnswer(question, contextText, memorySnapshot);
        chatMemoryService.appendAssistantMessage(userId, sessionId, answerText);
        chatMemoryService.maybeSummarize(userId, sessionId);
        reasoning.add("答案生成完成");

        return QAResult.builder()
                .userId(userId)
                .sessionId(sessionId)
                .question(question)
                .answer(answerText)
                .confidence(calcConfidence(topContexts))
                .intent("factoid")
                .contexts(topContexts)
                .reasoningSteps(reasoning)
                .build();
    }

    private List<QAResult.RetrievedContext> hybridRerank(
            List<QAResult.RetrievedContext> vector,
            List<QAResult.RetrievedContext> graph) {
        List<QAResult.RetrievedContext> all = new ArrayList<>();
        vector.forEach(context -> {
            context.setScore(context.getScore() * 1.0);
            all.add(context);
        });
        graph.forEach(context -> {
            context.setScore(context.getScore() * 1.2);
            all.add(context);
        });
        all.sort((left, right) -> Double.compare(right.getScore(), left.getScore()));
        return all;
    }

    private String buildContextText(List<QAResult.RetrievedContext> contexts) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < contexts.size(); index++) {
            QAResult.RetrievedContext context = contexts.get(index);
            builder.append(String.format("[来源 %d: %s | 分数: %.2f]%n%s%n%n",
                    index + 1,
                    context.getSource(),
                    context.getScore(),
                    context.getContent()));
        }
        return builder.toString();
    }

    private String generateAnswer(String question,
                                  String contextText,
                                  ChatMemoryService.MemorySnapshot memorySnapshot) {
        return chatClient.prompt(new Prompt(List.of(
                new SystemMessage(ANSWER_PROMPT),
                new UserMessage(buildUserPrompt(question, contextText, memorySnapshot))
        ))).call().content();
    }

    private String buildUserPrompt(String question,
                                   String contextText,
                                   ChatMemoryService.MemorySnapshot memorySnapshot) {
        StringBuilder prompt = new StringBuilder();
        if (memorySnapshot.summary() != null
                && memorySnapshot.summary().getSummary() != null
                && !memorySnapshot.summary().getSummary().isBlank()) {
            prompt.append("会话摘要:\n")
                    .append(memorySnapshot.summary().getSummary())
                    .append("\n\n");
        }

        if (memorySnapshot.recentMessages() != null && !memorySnapshot.recentMessages().isEmpty()) {
            prompt.append("最近对话:\n")
                    .append(buildRecentHistory(memorySnapshot.recentMessages()))
                    .append("\n\n");
        }

        prompt.append("检索上下文:\n")
                .append(contextText)
                .append("\n当前问题: ")
                .append(question);
        return prompt.toString();
    }

    private String buildRecentHistory(List<ChatMessageRecord> recentMessages) {
        StringBuilder history = new StringBuilder();
        for (ChatMessageRecord message : recentMessages) {
            history.append(message.getRole()).append(": ").append(message.getContent()).append("\n");
        }
        return history.toString();
    }

    private double calcConfidence(List<QAResult.RetrievedContext> contexts) {
        if (contexts.isEmpty()) {
            return 0.0;
        }
        return Math.min(contexts.stream().mapToDouble(QAResult.RetrievedContext::getScore).average().orElse(0), 1.0);
    }
}
