package com.agenthub.controller;

import com.agenthub.agent.DocParserAgent;
import com.agenthub.agent.KnowledgeExtractAgent;
import com.agenthub.agent.KnowledgeUpdateAgent;
import com.agenthub.agent.QAAgent;
import com.agenthub.model.DocumentChunk;
import com.agenthub.model.ExtractionResult;
import com.agenthub.model.QAResult;
import com.agenthub.service.KnowledgeGraphService;
import com.agenthub.service.VectorStoreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST API 控制器 (Java版)
 */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    //负责把上传的文件解析成文本块
    private final DocParserAgent docParser;
    //负责从文本块里抽取实体和关系
    private final KnowledgeExtractAgent extractor;
    //负责问答
    private final QAAgent qaAgent;

    private final KnowledgeUpdateAgent updateAgent;
    //负责向量存储和检索
    private final VectorStoreService vectorStore;
    //负责知识图谱写入和查询
    private final KnowledgeGraphService knowledgeGraph;

    public KnowledgeController(DocParserAgent docParser, KnowledgeExtractAgent extractor,
                                QAAgent qaAgent, KnowledgeUpdateAgent updateAgent,
                                VectorStoreService vectorStore, KnowledgeGraphService knowledgeGraph) {
        this.docParser = docParser;
        this.extractor = extractor;
        this.qaAgent = qaAgent;
        this.updateAgent = updateAgent;
        this.vectorStore = vectorStore;
        this.knowledgeGraph = knowledgeGraph;
    }

    @PostMapping("/ingest/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws Exception {
        //临时目录，把文件先落到本地
        Path tempDir = Files.createTempDirectory("uploads");
        File saved = tempDir.resolve(file.getOriginalFilename()).toFile();
        file.transferTo(saved);

        //文件切块，并写入向量库
        List<DocumentChunk> chunks = docParser.parse(saved.getAbsolutePath());
        vectorStore.addChunks(chunks);

        //从 chunk 中抽取实体和关系,传入知识图谱
        List<ExtractionResult> extractions = extractor.extract(chunks);
        int entityCount = 0, relCount = 0;
        for (ExtractionResult ext : extractions) {
            for (ExtractionResult.Entity e : ext.getEntities()) {
                knowledgeGraph.upsertEntity(e);
                entityCount++;
            }
            for (ExtractionResult.Relation r : ext.getRelations()) {
                knowledgeGraph.addRelation(r);
                relCount++;
            }
        }

        return ResponseEntity.ok(Map.of(
                "fileName", file.getOriginalFilename(),
                "chunks", chunks.size(),
                "entities", entityCount,
                "relations", relCount,
                "status", "success"
        ));
    }

    @PostMapping("/qa/ask")
    public ResponseEntity<QAResult> ask(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "");
        QAResult result = qaAgent.answer(question);
        return ResponseEntity.ok(result);
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
