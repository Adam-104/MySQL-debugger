package com.mysqldebugger.model;

/**
 * Analysis of the same query before and after a new index, both run on a temporary copy.
 *
 * @param indexDdl        the index statement that was applied to the copy
 * @param before          analysis without the index
 * @param after           analysis with the index
 * @param copyOfDatabase  true when the temporary copy was made from the configured (demo) database, false when it was
 *                        built from the visitor's pasted schema
 */
public record WhatIfResponse(String indexDdl, DebugResponse before, DebugResponse after, boolean copyOfDatabase) {
}
