package com.mysqldebugger.service;

import com.mysqldebugger.exception.InvalidQueryException;
import com.mysqldebugger.model.ExplainerResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explains CREATE TRIGGER: when it fires and what its body costs. */
@Service
public class TriggerExplainer {

    private static final Pattern TRIGGER = Pattern.compile(
            "^\\s*CREATE\\s+(?:DEFINER\\s*=\\s*\\S+\\s+)?TRIGGER\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([\\w.]+)`?\\s+"
                    + "(BEFORE|AFTER)\\s+(INSERT|UPDATE|DELETE)\\s+ON\\s+`?([\\w.]+)`?\\s+FOR\\s+EACH\\s+ROW\\s*"
                    + "(?:(FOLLOWS|PRECEDES)\\s+`?(\\w+)`?\\s*)?(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final int CI = Pattern.CASE_INSENSITIVE;

    public ExplainerResult explain(String sql) {

        Matcher m = TRIGGER.matcher(sql);
        if (!m.find()) {
            throw new InvalidQueryException(
                    "Could not parse CREATE TRIGGER. Expected: CREATE TRIGGER name {BEFORE|AFTER} "
                            + "{INSERT|UPDATE|DELETE} ON table FOR EACH ROW body");
        }

        String name = SqlText.stripQuotes(m.group(1));
        String timing = m.group(2).toUpperCase();
        String event = m.group(3).toUpperCase();
        String table = SqlText.stripQuotes(m.group(4));
        String orderKind = m.group(5) == null ? null : m.group(5).toUpperCase();
        String orderOther = m.group(6);
        String body = m.group(7).trim();

        if (body.isEmpty()) {
            throw new InvalidQueryException("The trigger has no body.");
        }

        ExplainerResult r = new ExplainerResult();
        r.targetTable = table;
        r.summary = "Trigger '" + name + "' runs " + timing + " every " + event + " of a row in '" + table + "'.";

        String here = "  ◀ this trigger (" + name + ")";
        boolean before = timing.equals("BEFORE");

        r.add("TRIGGERING STATEMENT",
                "A statement such as " + event + " on '" + table + "' reaches the table. Triggers fire once per "
                        + "affected row, not once per statement.",
                event + " ... " + table, "Rows are processed one at a time through the steps below.");
        r.add("BEFORE " + event + " TRIGGERS",
                "BEFORE triggers run first. They can change the row about to be stored (SET NEW.col = ...) or "
                        + "stop the statement with SIGNAL.",
                before ? "BEFORE " + event + here : "(any BEFORE " + event + " triggers)",
                before && orderKind != null
                        ? "Runs " + orderKind.toLowerCase().replace("follows", "after").replace("precedes", "before")
                        + " trigger '" + orderOther + "'." : "");
        r.add("CONSTRAINTS", "MySQL checks NOT NULL, CHECK, UNIQUE and FOREIGN KEY constraints on the final values.",
                "per row", "A BEFORE trigger runs before this check, so it can still fix values; an AFTER trigger "
                        + "only runs if the constraints passed.");
        r.add(event.equals("DELETE") ? "DELETE ROW" : "WRITE ROW",
                "The row is " + (event.equals("INSERT") ? "inserted" : event.equals("UPDATE") ? "updated" : "deleted")
                        + " in the clustered index and all secondary indexes.",
                "table '" + table + "'", "");
        r.add("AFTER " + event + " TRIGGERS",
                "AFTER triggers run once the row change succeeded. They cannot modify NEW, so they are used for side "
                        + "effects such as audit logs or summary tables.",
                !before ? "AFTER " + event + here : "(any AFTER " + event + " triggers)",
                !before && orderKind != null
                        ? "Runs " + orderKind.toLowerCase().replace("follows", "after").replace("precedes", "before")
                        + " trigger '" + orderOther + "'." : "");

        String stripped = SqlText.collapse(body);
        r.add("TRIGGER BODY", "The statements inside the trigger execute inside the same transaction as the "
                        + "triggering statement.",
                SqlText.shorten(stripped, 500), "If the body fails, the whole triggering statement is rolled back.");
        r.add("COMMIT", "The row change and everything the trigger did are committed together.", "", "");

        analyseBody(r, body, name, timing, event, table);

        r.notes.add("Triggers fire for INSERT, UPDATE and DELETE statements, but not for TRUNCATE TABLE, and not for "
                + "changes made by foreign-key cascades.");
        r.notes.add("REPLACE fires DELETE then INSERT triggers when it replaces a row. INSERT ... ON DUPLICATE KEY "
                + "UPDATE fires BEFORE INSERT, and then the UPDATE triggers if the update path is taken.");
        r.notes.add("FOR EACH ROW means the body runs once per affected row: an UPDATE of 100,000 rows runs it "
                + "100,000 times.");
        return r;
    }

    // ------------------------------------------------------------------

    private void analyseBody(ExplainerResult r, String body, String name, String timing, String event, String table) {

        String text = SqlScan.blankLiterals(body);

        boolean usesNew = Pattern.compile("\\bNEW\\s*\\.", CI).matcher(text).find();
        boolean usesOld = Pattern.compile("\\bOLD\\s*\\.", CI).matcher(text).find();

        if (event.equals("INSERT") && usesOld) {
            r.issues.add(ExplainerResult.issue("WARNING", "OLD Used In INSERT Trigger",
                    "OLD.* is referenced in a trigger for INSERT.",
                    "There is no previous version of a row that is being inserted, so MySQL rejects the trigger "
                            + "(error 1363).",
                    "Use NEW.* in INSERT triggers."));
        }
        if (event.equals("DELETE") && usesNew) {
            r.issues.add(ExplainerResult.issue("WARNING", "NEW Used In DELETE Trigger",
                    "NEW.* is referenced in a trigger for DELETE.",
                    "A deleted row has no new version, so MySQL rejects the trigger (error 1363).",
                    "Use OLD.* in DELETE triggers."));
        }

        boolean assignsNew = Pattern.compile("\\bSET\\s+NEW\\s*\\.\\s*\\w+\\s*(=|:=)", CI).matcher(text).find();
        if (assignsNew && !timing.equals("BEFORE")) {
            r.issues.add(ExplainerResult.issue("WARNING", "Changing NEW In An AFTER Trigger",
                    "The body assigns to NEW.* but the trigger is AFTER.",
                    "By the time an AFTER trigger runs the row has already been stored, so MySQL does not allow "
                            + "changing it (error 1362).",
                    "Make it a BEFORE trigger if it must modify the row being written."));
        }

        // writes
        Set<String> otherWrites = new LinkedHashSet<>();
        boolean writesOwnTable = false;
        Matcher w = Pattern.compile(
                "\\b(?:INSERT\\s+(?:IGNORE\\s+)?INTO|REPLACE\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+`?([\\w.]+)`?", CI).matcher(text);
        while (w.find()) {
            String t = SqlText.stripQuotes(w.group(1));
            if (t.equalsIgnoreCase(table)) {
                writesOwnTable = true;
            } else {
                otherWrites.add(t);
            }
        }

        if (writesOwnTable) {
            r.issues.add(ExplainerResult.issue("WARNING", "Trigger Modifies Its Own Table",
                    "The body writes to '" + table + "', the table that fired the trigger.",
                    "MySQL does not allow a trigger to change the table the triggering statement is already using "
                            + "(error 1442), because it could cause endless recursion.",
                    "Use SET NEW.col = ... in a BEFORE trigger for the current row, or move the logic elsewhere."));
        }
        if (!otherWrites.isEmpty()) {
            r.issues.add(ExplainerResult.issue("NOTICE", "Extra Write Per Row",
                    "The body writes to " + String.join(", ", otherWrites) + ".",
                    "Every row of the triggering statement now causes another write (plus its index updates and "
                            + "locks). A bulk statement is multiplied by that cost.",
                    "Make sure those tables are indexed on the columns used in the trigger's WHERE, and consider "
                            + "batching the work if bulk loads are common."));
        }

        if (Pattern.compile("\\bSELECT\\b", CI).matcher(text).find()) {
            r.issues.add(ExplainerResult.issue("NOTICE", "Query Inside Trigger",
                    "The body runs a SELECT for every row.",
                    "A SELECT that scans a table once per row makes a bulk statement quadratic: 10,000 rows with "
                            + "a 10,000-row scan is 100 million row reads.",
                    "Index the columns used by the query and run EXPLAIN on it separately."));
        }

        if (Pattern.compile("\\b(WHILE|LOOP|REPEAT|CURSOR)\\b", CI).matcher(text).find()) {
            r.issues.add(ExplainerResult.issue("WARNING", "Loop Inside Trigger",
                    "The body contains a loop or cursor.",
                    "A loop inside FOR EACH ROW multiplies its iterations by the number of affected rows.",
                    "Move the loop out of the trigger or replace it with one set-based statement."));
        }

        if (Pattern.compile("\\b(SLEEP|BENCHMARK)\\s*\\(", CI).matcher(text).find()) {
            r.issues.add(ExplainerResult.issue("WARNING", "Slow Function In Trigger",
                    "The body calls SLEEP or BENCHMARK.",
                    "The trigger runs inside the writer's transaction, so any delay keeps row locks held and "
                            + "blocks other sessions.",
                    "Remove it; do slow work outside the database."));
        }

        boolean signals = Pattern.compile("\\bSIGNAL\\b", CI).matcher(text).find();
        if (signals && !timing.equals("BEFORE")) {
            r.issues.add(ExplainerResult.issue("INFO", "Validation In An AFTER Trigger",
                    "The trigger raises an error with SIGNAL after the row was written.",
                    "It works (the statement is rolled back), but the database did the write, index updates and "
                            + "logging before rejecting it.",
                    "Validate in a BEFORE trigger so bad rows are rejected before any work is done."));
        }

        if (timing.equals("BEFORE") && !assignsNew && !signals && !otherWrites.isEmpty()) {
            r.issues.add(ExplainerResult.issue("INFO", "BEFORE Trigger Used For Side Effects",
                    "The trigger neither changes NEW nor validates, it only writes elsewhere.",
                    "A BEFORE trigger runs before constraints are checked, so it can log a row that is rejected "
                            + "a moment later (and the work is undone only by rolling back).",
                    "Use an AFTER trigger for audit logging and other side effects."));
        }

        if (otherWrites.isEmpty() && !assignsNew && !signals && !writesOwnTable) {
            // nothing special detected
            List<String> info = new ArrayList<>();
            info.add("No writes, NEW assignments or SIGNAL were detected in the body.");
            r.notes.addAll(info);
        }
    }
}
