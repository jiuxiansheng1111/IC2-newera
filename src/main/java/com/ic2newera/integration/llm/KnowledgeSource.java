package com.ic2newera.integration.llm;

/** Evidence with its provenance. An empty URL marks local registry or JAR information. */
public record KnowledgeSource(String title, String url, String excerpt) {
    public KnowledgeSource {
        title = title == null ? "资料" : title;
        url = url == null ? "" : url;
        excerpt = excerpt == null ? "" : excerpt;
    }
}
