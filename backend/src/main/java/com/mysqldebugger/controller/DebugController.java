package com.mysqldebugger.controller;

import com.mysqldebugger.model.DebugRequest;
import com.mysqldebugger.model.DebugResponse;
import com.mysqldebugger.service.AnalysisService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DebugController {

    private final AnalysisService analysisService;

    public DebugController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /** Analyze any supported MySQL statement. */
    @PostMapping({"/analyze", "/debug"})
    public DebugResponse analyze(@RequestBody DebugRequest request) {
        return analysisService.analyze(request.getQuery());
    }
}
