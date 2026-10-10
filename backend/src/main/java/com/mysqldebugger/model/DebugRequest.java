package com.mysqldebugger.model;

public class DebugRequest {

    private String query;

    /** Optional: CREATE TABLE / CREATE INDEX statements describing the visitor's own tables. */
    private String schema;

    public DebugRequest(){}

    public DebugRequest(String query){
        this.query = query;
    }

    public String getQuery() {
        return query;
    }
    public void setQuery(String query) {
        this.query = query;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }
}
