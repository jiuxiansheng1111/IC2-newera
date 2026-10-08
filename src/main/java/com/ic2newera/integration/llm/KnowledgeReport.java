package com.ic2newera.integration.llm;

import java.util.List;

/** Sources collected for one question, shown independently of the model's generated citations. */
public record KnowledgeReport(List<KnowledgeSource> sources, String summary) {
    public KnowledgeReport {
        sources = List.copyOf(sources);
        summary = summary == null ? "" : summary;
    }

    public static KnowledgeReport empty() { return new KnowledgeReport(List.of(), "尚未检索资料。"); }
}
