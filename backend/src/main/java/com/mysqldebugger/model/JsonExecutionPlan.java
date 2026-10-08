package com.mysqldebugger.model;

public class JsonExecutionPlan {

    private int selectId;

    private double queryCost;

    private boolean orderingUsingFilesort;

    private boolean groupingUsingTemporaryTable;

    private boolean groupingUsingFilesort;


    // -------------------------
    // Select ID
    // -------------------------

    public int getSelectId() {
        return selectId;
    }

    public void setSelectId(int selectId) {
        this.selectId = selectId;
    }


    // -------------------------
    // Query Cost
    // -------------------------

    public double getQueryCost() {
        return queryCost;
    }

    public void setQueryCost(double queryCost) {
        this.queryCost = queryCost;
    }


    // -------------------------
    // Ordering - Filesort
    // -------------------------

    public boolean isOrderingUsingFilesort() {
        return orderingUsingFilesort;
    }

    public void setOrderingUsingFilesort(boolean orderingUsingFilesort) {
        this.orderingUsingFilesort = orderingUsingFilesort;
    }


    // -------------------------
    // Grouping - Temporary Table
    // -------------------------

    public boolean isGroupingUsingTemporaryTable() {
        return groupingUsingTemporaryTable;
    }

    public void setGroupingUsingTemporaryTable(
            boolean groupingUsingTemporaryTable) {

        this.groupingUsingTemporaryTable =
                groupingUsingTemporaryTable;
    }


    // -------------------------
    // Grouping - Filesort
    // -------------------------

    public boolean isGroupingUsingFilesort() {
        return groupingUsingFilesort;
    }

    public void setGroupingUsingFilesort(
            boolean groupingUsingFilesort) {

        this.groupingUsingFilesort =
                groupingUsingFilesort;
    }
}