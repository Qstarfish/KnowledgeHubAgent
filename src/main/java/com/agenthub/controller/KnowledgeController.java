package com.agenthub.controller;

import com.agenthub.agent.DocParserAgent;
import com.agenthub.agent.KnowledgeExtractAgent;
import com.agenthub.agent.KnowledgeUpdateAgent;
import com.agenthub.agent.QAAgent;
import com.agenthub.model.AskRequest;
import com.agenthub.model.ChatSessionSummary;
import com.agenthub.model.ChatSessionView;
import com.agenthub.model.CreateSessionRequest;
import com.agenthub.model.DocumentChunk;
import com.agenthub.model.ExtractionResult;
import com.agenthub.model.QAResult;
import com.agenthub.service.ChatMemoryService;
import com.agenthub.service.KnowledgeGraphService;
import com.agenthub.service.VectorStoreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private final DocParserAgent docParser;
    private final KnowledgeExtractAgent extractor;
    private final QAAgent qaAgent;
    private final KnowledgeUpdateAgent updateAgent;
    private final ChatMemoryService chatMemoryService;
    private final VectorStoreService vectorStore;
    private final KnowledgeGraphService knowledgeGraph;

    public KnowledgeController(DocParserAgent docParser,
                               KnowledgeExtractAgent extractor,
                               QAAgent qaAgent,
                               KnowledgeUpdateAgent updateAgent,
                               ChatMemoryService chatMemoryService,
                               VectorStoreService vectorStore,
                               KnowledgeGraphService knowledgeGraph) {
        this.docParser = docParser;
        this.extractor = extractor;
        this.qaAgent = qaAgent;
        this.updateAgent = updateAgent;
        this.chatMemoryService = chatMemoryService;
        this.vectorStore = vectorStore;
        this.knowledgeGraph = knowledgeGraph;
    }

    @PostMapping("/ingest/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws Exception {
        Path tempDir = Files.createTempDirectory("uploads");
        File saved = tempDir.resolve(file.getOriginalFilename()).toFile();
        file.transferTo(saved);

        List<DocumentChunk> chunks = docParser.parse(saved.getAbsolutePath());
        vectorStore.addChunks(chunks);

        List<ExtractionResult> extractions = extractor.extract(chunks);
        int entityCount = 0;
        int relationCount = 0;
        String source = saved.getAbsolutePath();
        for (ExtractionResult extraction : extractions) {
            for (ExtractionResult.Entity entity : extraction.getEntities()) {
                knowledgeGraph.upsertEntity(entity, source);
                entityCount++;
            }
            for (ExtractionResult.Relation relation : extraction.getRelations()) {
                knowledgeGraph.addRelation(relation, source);
                relationCount++;
            }
        }

        return ResponseEntity.ok(Map.of(
                "fileName", file.getOriginalFilename(),
                "chunks", chunks.size(),
                "entities", entityCount,
                "relations", relationCount,
                "status", "success"
        ));
    }

    @PostMapping("/qa/ask")
    public ResponseEntity<QAResult> ask(@RequestBody AskRequest request) {
        return ResponseEntity.ok(qaAgent.answer(request));
    }

    @PostMapping("/qa/sessions")
    public ResponseEntity<ChatSessionView> createSession(@RequestBody CreateSessionRequest request) {
        var session = chatMemoryService.createSession(request.getUserId(), request.getTitle());
        return ResponseEntity.ok(ChatSessionView.builder()
                .userId(session.getUserId())
                .sessionId(session.getSessionId())
                .title(session.getTitle())
                .status(session.getStatus())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .messageCount(0)
                .hasSummary(false)
                .build());
    }

    @GetMapping("/qa/sessions")
    public ResponseEntity<List<ChatSessionView>> listSessions(@RequestParam("userId") String userId) {
        return ResponseEntity.ok(chatMemoryService.listSessions(userId));
    }

    /**
     *
     * @param sessionId
     * @param userId
     * @return
     */
    @PostMapping("/qa/sessions/{sessionId}/summarize")
    public ResponseEntity<ChatSessionSummary> summarizeSession(@PathVariable("sessionId") String sessionId,
                                                               @RequestParam("userId") String userId) {
        return ResponseEntity.ok(chatMemoryService.summarizeSession(userId, sessionId));
    }

    @GetMapping("/admin/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(Map.of(
                "vectorStore", vectorStore.getStats(),
                "knowledgeGraph", knowledgeGraph.getStats()
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "service", "AgentKnowledgeHub-Java"));
    }
}
