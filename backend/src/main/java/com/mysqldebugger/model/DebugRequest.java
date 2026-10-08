package com.mysqldebugger.model;

public class DebugRequest {

    private String query;

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
}
