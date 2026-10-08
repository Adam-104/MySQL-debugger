package com.mysqldebugger.model;

public class ExecutionStep {

    private int step;
    private String operation;
    private String description;
    private String expression;
    private String details;

    public ExecutionStep () {}

    public ExecutionStep(int step, String operation, String description, String expression, String details) {
        this.step = step;
        this.operation = operation;
        this.description = description;
        this.expression = expression;
        this.details = details;
    }


    public int getStep() {
        return step;
    }
    public void setStep(int step) {
        this.step = step;
    }

    public String getOperation() {
        return operation;
    }
    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }

    public String getExpression() {
        return expression;
    }
    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getDetails() {
        return details;
    }
    public void setDetails(String details) {
        this.details = details;
    }

}
