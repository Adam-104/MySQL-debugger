package com.mysqldebugger.model;

/** A "what if I add this index?" comparison: the query, optionally the visitor's own schema, and the index. */
public class WhatIfRequest {

    private String query;
    private String schema;
    private String indexDdl;

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }

    public String getIndexDdl() { return indexDdl; }
    public void setIndexDdl(String indexDdl) { this.indexDdl = indexDdl; }
}
