package com.agenthub.service;

import com.agenthub.model.ExtractionResult;
import com.agenthub.model.QAResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 *
 *  知识图谱服务
 *  使用 Neo4j Java Driver 管理知识图谱:
 *    - 实体 CRUD (带版本号)
 *    - 关系管理
 *    - 多跳子图检索
 *    - Cypher 查询
 *
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

    private static final Set<String> QUESTION_STOP_WORDS = Set.of(
            "请问", "请", "一下", "什么", "谁", "哪些", "哪个", "怎么", "如何", "吗", "呢",
            "有关", "相关", "介绍", "告诉我", "请告诉我", "是", "的", "了", "和", "与"
    );

    @PostConstruct
    public void init() {
        try {
            driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
            ensureIndexes();
        } catch (Exception e) {
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
            session.run("CREATE INDEX IF NOT EXISTS FOR (n:Entity) ON (n.name)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (n:Entity) ON (n.type)");
        }
    }

    /**
     * 更新或者插入，幂等写入点
     * @param entity
     * @param source
     */
    public void upsertEntity(ExtractionResult.Entity entity, String source) {
        if (driver == null) return;
        String docId = computeDocId(source);
        //todo MERGE (e:Entity {name: $name})目前还是基于name
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
            session.run(cypher, Map.of(
                    "name", entity.getName(),
                    "type", entity.getType(),
                    "desc", entity.getDescription(),
                    "source", source,
                    "docId", docId
            ));
        }
    }

    /**
     * 写入边
     * @param relation
     * @param source
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
                """, relType);
        try (Session session = driver.session()) {
            session.run(cypher, Map.of(
                    "head", relation.getHead(),
                    "tail", relation.getTail(),
                    "conf", relation.getConfidence(),
                    "source", source,
                    "docId", docId
            ));
        }
    }

    public List<QAResult.RetrievedContext> searchByQuestion(String question) {
        if (driver == null || question == null || question.isBlank()) return List.of();

        SearchIntent intent = analyzeQuestion(question);
        if (intent.terms().isEmpty()) return List.of();

        try (Session session = driver.session()) {
            List<EntityCandidate> candidates = findEntityCandidates(session, intent);
            if (candidates.isEmpty()) {
                return List.of();
            }
            return buildContexts(session, candidates, intent);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<EntityCandidate> findEntityCandidates(Session session, SearchIntent intent) {
        Map<String, EntityCandidate> candidates = new LinkedHashMap<>();

        String exactCypher = """
                MATCH (e:Entity)
                WHERE any(term IN $terms WHERE toLower(e.name) = term)
                RETURN e.name AS name,
                       e.type AS type,
                       coalesce(e.description, '') AS description,
                       size([term IN $terms WHERE toLower(e.name) = term]) AS matchCount
                ORDER BY matchCount DESC, size(coalesce(e.docIds, [])) DESC
                LIMIT 5
                """;

        String fuzzyCypher = """
                MATCH (e:Entity)
                WHERE any(term IN $terms
                          WHERE toLower(e.name) CONTAINS term
                             OR toLower(coalesce(e.description, '')) CONTAINS term)
                RETURN e.name AS name,
                       e.type AS type,
                       coalesce(e.description, '') AS description,
                       size([term IN $terms
                             WHERE toLower(e.name) CONTAINS term
                                OR toLower(coalesce(e.description, '')) CONTAINS term]) AS matchCount
                ORDER BY matchCount DESC, size(coalesce(e.docIds, [])) DESC
                LIMIT 8
                """;

        for (Record record : session.run(exactCypher, Map.of("terms", intent.terms())).list()) {
            EntityCandidate candidate = toCandidate(record, "exact", intent);
            candidates.put(candidate.name(), candidate);
        }

        for (Record record : session.run(fuzzyCypher, Map.of("terms", intent.terms())).list()) {
            EntityCandidate candidate = toCandidate(record, "fuzzy", intent);
            candidates.putIfAbsent(candidate.name(), candidate);
        }

        return candidates.values().stream()
                .sorted((a, b) -> Double.compare(b.baseScore(), a.baseScore()))
                .limit(5)
                .toList();
    }

    private EntityCandidate toCandidate(Record record, String matchType, SearchIntent intent) {
        String name = record.get("name").asString("");
        String type = record.get("type").asString("Concept");
        String description = record.get("description").asString("");
        int matchCount = record.get("matchCount").asInt(0);
        List<String> matchedTerms = intent.terms().stream()
                .filter(term -> matchesCandidate(term, name, description, "exact".equals(matchType)))
                .toList();
        double baseScore = scoreEntityCandidate(name, description, matchType, matchCount, matchedTerms, intent);
        return new EntityCandidate(name, type, description, matchType, matchCount, matchedTerms, baseScore);
    }

    private List<QAResult.RetrievedContext> buildContexts(Session session, List<EntityCandidate> candidates, SearchIntent intent) {
        String relationCypher = """
                MATCH (e:Entity {name: $name})
                OPTIONAL MATCH (e)-[r]-(neighbor:Entity)
                RETURN e.name AS entity,
                       e.type AS type,
                       coalesce(e.description, '') AS description,
                       type(r) AS relation,
                       neighbor.name AS neighbor,
                       coalesce(r.confidence, 0.5) AS confidence
                LIMIT 12
                """;

        List<QAResult.RetrievedContext> contexts = new ArrayList<>();
        for (EntityCandidate candidate : candidates) {
            LinkedHashSet<String> facts = new LinkedHashSet<>();
            List<Map<String, Object>> factMetadata = new ArrayList<>();
            double bestScore = candidate.baseScore();

            for (Record record : session.run(relationCypher, Map.of("name", candidate.name())).list()) {
                String relation = record.get("relation").asString("");
                String neighbor = record.get("neighbor").asString("");
                if (relation.isBlank() || neighbor.isBlank()) {
                    continue;
                }

                double confidence = record.get("confidence").asDouble(0.5);
                double relationScore = scoreRelation(candidate, relation, neighbor, confidence, intent);
                if (relationScore < 0.45) {
                    continue;
                }

                facts.add(candidate.name() + " --[" + relation + "]--> " + neighbor);
                factMetadata.add(Map.of(
                        "relation", relation,
                        "neighbor", neighbor,
                        "confidence", confidence,
                        "score", relationScore
                ));
                bestScore = Math.max(bestScore, relationScore);
            }

            String content = buildAggregatedContent(candidate, facts);
            contexts.add(QAResult.RetrievedContext.builder()
                    .content(content)
                    .source("knowledge_graph")
                    .score(Math.min(bestScore, 1.0))
                    .retrievalType("graph")
                    .metadata(Map.of(
                            "entity", candidate.name(),
                            "type", candidate.type(),
                            "matchType", candidate.matchType(),
                            "matchedTerms", candidate.matchedTerms(),
                            "preferredRelations", intent.preferredRelations(),
                            "factCount", facts.size(),
                            "facts", factMetadata
                    ))
                    .build());
        }

        return contexts.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(8)
                .toList();
    }

    private String buildAggregatedContent(EntityCandidate candidate, Set<String> facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Entity: ").append(candidate.name());
        if (!candidate.type().isBlank()) {
            sb.append(" (").append(candidate.type()).append(")");
        }
        if (!candidate.description().isBlank()) {
            sb.append("\nDescription: ").append(candidate.description());
        }
        if (!facts.isEmpty()) {
            sb.append("\nFacts:");
            facts.forEach(fact -> sb.append("\n- ").append(fact));
        }
        return sb.toString();
    }

    /**
     * 基于规则过滤输入和识别意图
     * @param question
     * @return
     */
    private SearchIntent analyzeQuestion(String question) {
        String normalized = normalize(question);
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.addAll(extractQuotedTerms(question));
        terms.addAll(extractEntityLikeTerms(question));
        terms.addAll(splitTerms(normalized));
        terms.removeIf(term -> term.isBlank() || term.length() < 2);
        List<String> preferredRelations = inferPreferredRelations(normalized);
        return new SearchIntent(normalized, List.copyOf(terms), preferredRelations);
    }

    //提取被引号包裹的专有名词
    private List<String> extractQuotedTerms(String question) {
        List<String> quoted = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[\"“”'‘’]([^\"“”'‘’]{2,})[\"“”'‘’]")
                .matcher(question);
        while (matcher.find()) {
            quoted.add(normalize(matcher.group(1)));
        }
        return quoted;
    }

    //尝试提取提问中的主语
    private List<String> extractEntityLikeTerms(String question) {
        String lowered = question.toLowerCase(Locale.ROOT);
        String[] separators = {
                "依赖", "使用", "开发", "属于", "是谁", "谁", "什么", "哪些", "哪个",
                "related to", "depends on", "uses", "developed by", "part of"
        };
        for (String separator : separators) {
            int idx = lowered.indexOf(separator.toLowerCase(Locale.ROOT));
            if (idx > 0) {
                String prefix = normalize(question.substring(0, idx));
                if (!prefix.isBlank()) {
                    return List.of(prefix);
                }
            }
        }
        return List.of();
    }

    //根据废话词典QUESTION_STOP_WORDS过滤句中废话
    private List<String> splitTerms(String normalized) {
        String cleaned = normalized;
        for (String stopWord : QUESTION_STOP_WORDS) {
            cleaned = cleaned.replace(stopWord, " ");
        }
        return Arrays.stream(cleaned.split("\\s+"))
                .map(String::trim)
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
    }

    private List<String> inferPreferredRelations(String normalizedQuestion) {
        LinkedHashSet<String> relations = new LinkedHashSet<>();
        if (containsAny(normalizedQuestion, "依赖", "depends on", "dependency")) relations.add("DEPENDS_ON");
        if (containsAny(normalizedQuestion, "开发", "谁开发", "developed by", "creator", "created by")) relations.add("DEVELOPED_BY");
        if (containsAny(normalizedQuestion, "使用", "uses", "used by")) relations.add("USES");
        if (containsAny(normalizedQuestion, "属于", "part of", "belongs to", "归属")) {
            relations.add("PART_OF");
            relations.add("BELONGS_TO");
        }
        if (containsAny(normalizedQuestion, "工作", "works at", "任职")) relations.add("WORKS_AT");
        if (relations.isEmpty()) relations.add("RELATED_TO");
        return List.copyOf(relations);
    }

    private boolean containsAny(String input, String... terms) {
        return Arrays.stream(terms).anyMatch(input::contains);
    }

    /*
     * 把一段乱七八糟的文本，洗成只有纯文字、由单个空格隔开、且全部小写的干净字符串。
     */
    private String normalize(String input) {
        return input == null ? "" : input
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}，。！？；：、】【（）“”‘’《》、]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean matchesCandidate(String term, String name, String description, boolean exact) {
        String normalizedName = normalize(name);
        String normalizedDescription = normalize(description);
        return exact
                ? normalizedName.equals(term)
                : normalizedName.contains(term) || normalizedDescription.contains(term);
    }

    private double scoreEntityCandidate(String name, String description, String matchType, int matchCount,
                                        List<String> matchedTerms, SearchIntent intent) {
        double score = "exact".equals(matchType) ? 0.65 : 0.42;
        score += Math.min(matchCount * 0.08, 0.2);
        if (!intent.normalizedQuestion().isBlank() && normalize(name).contains(intent.normalizedQuestion())) {
            score += 0.08;
        }
        if (!description.isBlank() && matchedTerms.stream().anyMatch(term -> normalize(description).contains(term))) {
            score += 0.05;
        }
        if (!intent.preferredRelations().contains("RELATED_TO")) {
            score += 0.03;
        }
        return Math.min(score, 0.92);
    }

    /**
     * 给关系打分，基于给实体打分的base
     * @param candidate
     * @param relation
     * @param neighbor
     * @param confidence
     * @param intent
     * @return
     */
    private double scoreRelation(EntityCandidate candidate, String relation, String neighbor,
                                 double confidence, SearchIntent intent) {
        double score = candidate.baseScore();
        if (intent.preferredRelations().contains(relation)) {
            score += 0.16;
        } else if (!intent.preferredRelations().contains("RELATED_TO")) {
            score -= 0.06;
        }
        if (intent.matchedAny(neighbor)) {
            score += 0.06;
        }
        score += Math.min(Math.max(confidence, 0.0), 1.0) * 0.08;
        return Math.min(Math.max(score, 0.0), 1.0);
    }

    public void deleteBySource(String source) {
        if (driver == null) return;
        String docId = computeDocId(source);
        try (Session session = driver.session()) {
            session.run("""
                    MATCH ()-[r]-()
                    WHERE $source IN coalesce(r.sources, []) OR $docId IN coalesce(r.docIds, [])
                    SET r.sources = [s IN coalesce(r.sources, []) WHERE s <> $source],
                        r.docIds = [d IN coalesce(r.docIds, []) WHERE d <> $docId]
                    """, Map.of("source", source, "docId", docId));
            session.run("""
                    MATCH ()-[r]-()
                    WHERE size(coalesce(r.sources, [])) = 0 OR size(coalesce(r.docIds, [])) = 0
                    DELETE r
                    """);
            session.run("""
                    MATCH (e:Entity)
                    WHERE $source IN coalesce(e.sources, []) OR $docId IN coalesce(e.docIds, [])
                    SET e.sources = [s IN coalesce(e.sources, []) WHERE s <> $source],
                        e.docIds = [d IN coalesce(e.docIds, []) WHERE d <> $docId],
                        e.updated_at = timestamp()
                    """, Map.of("source", source, "docId", docId));
            session.run("""
                    MATCH (e:Entity)
                    WHERE size(coalesce(e.sources, [])) = 0 AND size(coalesce(e.docIds, [])) = 0
                    DETACH DELETE e
                    """);
        }
    }

    private static String computeDocId(String path) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(path.hashCode());
        }
    }

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

    /**
     * @param normalizedQuestion 标准化后的问题文本
     * @param terms 拆分出的搜索词列表
     * @param preferredRelations 偏好的关系类型列表
     */
    private record SearchIntent(String normalizedQuestion, List<String> terms, List<String> preferredRelations) {
        //判断传入的 value 是否包含任意一个 terms 中的词（大小写不敏感）
        private boolean matchedAny(String value) {
            String normalizedValue = value == null ? "" : value.toLowerCase(Locale.ROOT);
            return terms.stream().anyMatch(normalizedValue::contains);
        }
    }

    /**
     * @param name
     * @param type
     * @param description
     * @param matchType 匹配方式（如精确匹配、模糊匹配等）
     * @param matchCount 命中的搜索词数量
     * @param matchedTerms 具体命中了哪些词
     * @param baseScore 基础得分，用于后续排序或筛选
     */
    private record EntityCandidate(String name, String type, String description, String matchType,
                                   int matchCount, List<String> matchedTerms, double baseScore) {}
}
