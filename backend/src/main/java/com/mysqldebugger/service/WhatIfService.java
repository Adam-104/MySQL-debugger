package com.mysqldebugger.service;

import com.mysqldebugger.model.DebugResponse;
import com.mysqldebugger.model.WhatIfResponse;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * "What if I add this index?": analyses a query, applies ONE index to a temporary copy, analyses it again.
 * The real database is never changed: the copy is built from the visitor's pasted schema, or (for the demo tables)
 * from a copy of the configured database, and dropped afterwards.
 */
@Service
public class WhatIfService {

    private final AnalysisService analysis;
    private final SchemaSandboxService sandbox;
    private final QueryValidator validator;

    public WhatIfService(AnalysisService analysis, SchemaSandboxService sandbox, QueryValidator validator) {
        this.analysis = analysis;
        this.sandbox = sandbox;
        this.validator = validator;
    }

    public WhatIfResponse compare(String query, String schema, String indexDdl) {
        validator.validate(query);                                   // fail fast, before anything is created
        String index = SchemaScript.singleIndexStatement(indexDdl);
        boolean pasted = schema != null && !schema.isBlank();

        Supplier<WhatIfResponse> work = () -> {
            sandbox.refreshCurrentSchemaStatistics();                // same statistics for both runs
            DebugResponse before = analysis.analyze(query);
            sandbox.executeInCurrentSchema(index, "The index could not be created: ");
            sandbox.refreshCurrentSchemaStatistics();
            DebugResponse after = analysis.analyze(query);
            return new WhatIfResponse(index, before, after, !pasted);
        };

        return pasted ? sandbox.withSandbox(schema, work) : sandbox.withClonedDefaultSchema(work);
    }
}
