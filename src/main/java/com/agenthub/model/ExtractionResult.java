package com.agenthub.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractionResult {
    private List<Entity> entities;
    private List<Relation> relations;
    private String sourceChunkId;

    /**
     * 表示知识图谱里的“点”。
     * name：实体名，比如 “Spring Boot”
     * type：实体类型，比如 Technology、Person
     * description：对实体的补充说明
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entity {
        private String name;
        private String type;
        private String description;
    }

    /**
     * 表示知识图谱里的“边”。
     * head：关系起点实体
     * relation：关系类型，比如 depends_on
     * tail：关系终点实体
     * confidence：模型对这条关系的置信度
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Relation {
        private String head;
        private String relation;
        private String tail;
        private double confidence;
    }
}
