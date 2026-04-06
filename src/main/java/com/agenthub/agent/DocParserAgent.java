package com.agenthub.agent;

import com.agenthub.model.DocumentChunk;
import org.apache.tika.Tika;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 文档解析 Agent (Java版)
 *
 * 使用 Apache Tika 实现多格式文档解析，
 * 支持 PDF / Word / Excel / 图片等格式。
 * 结合 Spring AI ChatClient 进行 LLM 视觉理解。
 */
@Component
public class DocParserAgent {

    private static final int CHUNK_SIZE = 512;
    private static final int CHUNK_OVERLAP = 64;

    private final Tika tika = new Tika();
    private final ChatClient chatClient;

    public DocParserAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public List<DocumentChunk> parse(String filePath) throws IOException {
        File file = new File(filePath);
        String docId = computeDocId(filePath);
        String docType = detectType(file);

        String rawText = extractText(file);
        return chunkText(rawText, docId, docType, filePath);
    }

    public List<DocumentChunk> parseBatch(List<String> filePaths) throws IOException {
        List<DocumentChunk> allChunks = new ArrayList<>();
        for (String path : filePaths) {
            allChunks.addAll(parse(path));
        }
        return allChunks;
    }

    private String extractText(File file) throws IOException {
        try {
            return tika.parseToString(file);
        } catch (Exception e) {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        }
    }

    private String detectType(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".pdf")) return "pdf";
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image";
        if (name.endsWith(".csv") || name.endsWith(".xlsx")) return "table";
        if (name.endsWith(".md")) return "markdown";
        return "text";
    }

    /**
     * 滑动窗口
     * 每次最多切 CHUNK_SIZE(默认512) 个字符
     * 下一块会和上一块重叠 CHUNK_OVERLAP(默认64) 个字符
     * @param text
     * @param docId
     * @param docType
     * @param source
     * @return
     */
    private List<DocumentChunk> chunkText(String text, String docId, String docType, String source) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int idx = 0;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            String content = text.substring(start, end).trim();
            if (!content.isEmpty()) {
                chunks.add(DocumentChunk.builder()
                        .chunkId(docId + "#chunk-" + idx)
                        .docId(docId)
                        .chunkIndex(idx)
                        .content(content)
                        .docType(docType)
                        .metadata(Map.of("source", source, "charStart", start, "charEnd", end))
                        .build());
                idx++;
            }
            if (end >= text.length()) {
                //避免text.length()<CHUNK_SIZE时的死循环 以及 最后一块重复处理的情况
                break;
            }
            start = Math.max(end - CHUNK_OVERLAP, 0);
        }
        return chunks;
    }

    private static String computeDocId(String path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(path.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(path.hashCode());
        }
    }
}
