package com.agenthub.service;

import com.agenthub.model.ExtractionResult;
import com.agenthub.model.QAResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.neo4j.driver.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱服务 (Java版)
 *
 * 使用 Neo4j Java Driver 管理知识图谱:
 *   - 实体 CRUD (带版本号)
 *   - 关系管理
 *   - 多跳子图检索
 *   - Cypher 查询
 */
@Service
public class KnowledgeGraphService {

    @Value("${app.neo4j.uri}")
    private String uri;
    @Value("${app.neo4j.user}")
    private String user;
    @Value("${app.neo4j.password}")
    private String password;

    private Driver driver;

    //初始化函数，在 Spring 启动后自动执行。
    @PostConstruct
    public void init() {
        //连得上 Neo4j，就启用图谱能力
        try {
            driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
            ensureIndexes();
        } catch (Exception e) {
            //连不上，也允许应用先起来，只是图谱相关功能不可用
            System.err.println("Neo4j connection failed: " + e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        if (driver != null) driver.close();
    }

    private void ensureIndexes() {
        if (driver == null) return;
        try (Session session = driver.session()) {
            /**
             * 给 Entity.name 建索引
             * 给 Entity.type 建索引
             */
            session.run("CREATE INDEX IF NOT EXISTS FOR (n:Entity) ON (n.name)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (n:Entity) ON (n.type)");
        }
    }

    /**
     * 幂等写入”：
     *
     * 用 MERGE (e:Entity {name: $name})
     * 如果不存在就创建
     * 如果已存在就更新部分字段
     *
     * @param entity
     */
    public void upsertEntity(ExtractionResult.Entity entity, String source) {
        if (driver == null) return;
        String docId = computeDocId(source);
        String cypher = """
                MERGE (e:Entity {name: $name})
                ON CREATE SET e.type = $type, e.description = $desc, e.created_at = timestamp(),
                              e.sources = [$source], e.docIds = [$docId]
                ON MATCH SET e.description = CASE WHEN $desc <> '' THEN $desc ELSE e.description END,
                             e.sources = CASE WHEN $source IN coalesce(e.sources, []) THEN e.sources
                                              ELSE coalesce(e.sources, []) + $source END,
                             e.docIds = CASE WHEN $docId IN coalesce(e.docIds, []) THEN e.docIds
                                             ELSE coalesce(e.docIds, []) + $docId END,
                             e.updated_at = timestamp()
                """;
        try (Session session = driver.session()) {
            session.run(cypher, Map.of("name", entity.getName(), "type", entity.getType(),
                    "desc", entity.getDescription(), "source", source, "docId", docId));
        }
    }

    /**
     * 负责写入关系边
     *(节点A: Entity {name: "Steve Jobs"})
     *     │
     *     │ ─── [关系: FOUNDED] ───▶
     *     │       ├── confidence: 0.98
     *     │       ├── sources: ["wiki_01.txt", "news_05.txt"]
     *     │       ├── docIds: ["doc_101", "doc_105"]
     *     │       └── updated_at: 1712395530123
     *     ▼
     * (节点B: Entity {name: "Apple Inc."})
     *
     * @param relation
     */
    public void addRelation(ExtractionResult.Relation relation, String source) {
        if (driver == null) return;
        String docId = computeDocId(source);
        String relType = relation.getRelation().toUpperCase().replace(" ", "_");
        String cypher = String.format("""
                MATCH (h:Entity {name: $head})
                MATCH (t:Entity {name: $tail})
                MERGE (h)-[r:%s]->(t)
                SET r.confidence = $conf,
                    r.sources = CASE WHEN $source IN coalesce(r.sources, []) THEN r.sources
                                     ELSE coalesce(r.sources, []) + $source END,
                    r.docIds = CASE WHEN $docId IN coalesce(r.docIds, []) THEN r.docIds
                                    ELSE coalesce(r.docIds, []) + $docId END,
                    r.updated_at = timestamp()
                """, relType);//用 Java 的 String.format 配合 %s 来硬拼接，因为数据库的查询优化器在执行前需要知道图的骨架结构
        try (Session session = driver.session()) {
            session.run(cypher, Map.of("head", relation.getHead(), "tail", relation.getTail(),
                    "conf", relation.getConfidence(), "source", source, "docId", docId));
        }
    }

    /**
     * 从问题里截前 20 个字符当关键词
     * 查找名字或描述包含这个关键词的实体
     * 再可选匹配它相邻的关系和邻居节点
     * 把结果拼成字符串上下文，返回给 QA 模块
     * @param question
     * @return
     */
    public List<QAResult.RetrievedContext> searchByQuestion(String question) {
        if (driver == null) return List.of();
        List<QAResult.RetrievedContext> results = new ArrayList<>();
        String cypher = """
                MATCH (e:Entity)
                WHERE e.name CONTAINS $keyword OR e.description CONTAINS $keyword
                OPTIONAL MATCH (e)-[r]-(neighbor:Entity)
                RETURN e.name AS entity, type(r) AS relation, neighbor.name AS neighbor
                LIMIT 20
                """;
        try (Session session = driver.session()) {
            String keyword = question.length() > 20 ? question.substring(0, 20) : question;
            var records = session.run(cypher, Map.of("keyword", keyword)).list();
            for (var record : records) {
                String content = record.get("entity").asString("") + " --["
                        + record.get("relation").asString("related") + "]--> "
                        + record.get("neighbor").asString("");
                results.add(QAResult.RetrievedContext.builder()
                        .content(content)
                        .source("knowledge_graph")
                        //todo 图谱返回的score先写死，
                        .score(0.8)
                        .retrievalType("graph")
                        //todo 图谱返回的附加信息metadata先传空
                        .metadata(Map.of())
                        .build());
            }
        } catch (Exception e) {
            // graph not available
        }
        return results;
    }

    public void deleteBySource(String source) {
        if (driver == null) return;
        String docId = computeDocId(source);
        try (Session session = driver.session()) {
            //1.删除关系（边）上的来源标记
            session.run("""
                    MATCH ()-[r]-()
                    WHERE $source IN coalesce(r.sources, []) OR $docId IN coalesce(r.docIds, [])
                    SET r.sources = [s IN coalesce(r.sources, []) WHERE s <> $source],
                        r.docIds = [d IN coalesce(r.docIds, []) WHERE d <> $docId]
                    """, Map.of("source", source, "docId", docId));
            //2.删除彻底失去来源支撑的关系（边）
            session.run("""
                    MATCH ()-[r]-()
                    WHERE size(coalesce(r.sources, [])) = 0 OR size(coalesce(r.docIds, [])) = 0
                    DELETE r
                    """);
            //3.删除实体（节点）上的来源标记
            session.run("""
                    MATCH (e:Entity)
                    WHERE $source IN coalesce(e.sources, []) OR $docId IN coalesce(e.docIds, [])
                    SET e.sources = [s IN coalesce(e.sources, []) WHERE s <> $source],
                        e.docIds = [d IN coalesce(e.docIds, []) WHERE d <> $docId],
                        e.updated_at = timestamp()
                    """, Map.of("source", source, "docId", docId));
            //4.删除彻底失去来源支撑的实体（节点）
            session.run("""
                    MATCH (e:Entity)
                    WHERE size(coalesce(e.sources, [])) = 0 AND size(coalesce(e.docIds, [])) = 0
                    DETACH DELETE e
                    """);
        }
    }

    /**
     * 利用SHA-256 哈希算法计算出，截取前 16 个字符作为最终的 docId
     * @param path
     * @return
     */
    private static String computeDocId(String path) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(path.hashCode());
        }
    }

    //统计实体数和关系数
    public Map<String, Object> getStats() {
        if (driver == null) return Map.of("status", "disconnected");
        try (Session session = driver.session()) {
            long entities = session.run("MATCH (e:Entity) RETURN count(e) AS cnt")
                    .single().get("cnt").asLong();
            long relations = session.run("MATCH ()-[r]->() RETURN count(r) AS cnt")
                    .single().get("cnt").asLong();
            return Map.of("totalEntities", entities, "totalRelations", relations);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }
}
