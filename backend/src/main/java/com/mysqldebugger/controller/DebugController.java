package com.mysqldebugger.controller;

import com.mysqldebugger.exception.InvalidQueryException;
import com.mysqldebugger.model.DebugRequest;
import com.mysqldebugger.model.DebugResponse;
import com.mysqldebugger.model.TableStats;
import com.mysqldebugger.model.WhatIfRequest;
import com.mysqldebugger.model.WhatIfResponse;
import com.mysqldebugger.service.AnalysisService;
import com.mysqldebugger.service.SchemaSandboxService;
import com.mysqldebugger.service.SchemaStatsService;
import com.mysqldebugger.service.WhatIfService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DebugController {

    private final AnalysisService analysisService;
    private final SchemaStatsService schemaStatsService;
    private final SchemaSandboxService sandbox;
    private final WhatIfService whatIfService;

    public DebugController(AnalysisService analysisService, SchemaStatsService schemaStatsService,
                           SchemaSandboxService sandbox, WhatIfService whatIfService) {
        this.analysisService = analysisService;
        this.schemaStatsService = schemaStatsService;
        this.sandbox = sandbox;
        this.whatIfService = whatIfService;
    }

    /** Tables in the connected database with their row counts. */
    @GetMapping("/tables")
    public List<TableStats> tables() {
        return schemaStatsService.tables();
    }

    /** Column names per table (for editor autocomplete). */
    @GetMapping("/columns")
    public Map<String, List<String>> columns() {
        return schemaStatsService.columns();
    }

    /** Loads the pasted schema (and sample rows) into a temporary database, reports its tables, and drops it. */
    @PostMapping("/schema/check")
    public List<TableStats> checkSchema(@RequestBody DebugRequest request) {
        if (request.getSchema() == null || request.getSchema().isBlank()) {
            throw new InvalidQueryException("Paste your CREATE TABLE statements first.");
        }
        return sandbox.withSandbox(request.getSchema(), schemaStatsService::tables);
    }

    /** Compares a query's plan before and after adding one index, on a temporary copy. */
    @PostMapping("/whatif")
    public WhatIfResponse whatIf(@RequestBody WhatIfRequest request) {
        return whatIfService.compare(request.getQuery(), request.getSchema(), request.getIndexDdl());
    }

    /** Analyze any supported MySQL statement. */
    @PostMapping({"/analyze", "/debug"})
    public DebugResponse analyze(@RequestBody DebugRequest request) {
        return analysisService.analyze(request.getQuery(), request.getSchema());
    }
}
