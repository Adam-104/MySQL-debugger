package com.mysqldebugger.service;

import com.mysqldebugger.model.StatementType;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class StatementClassifier {

    private static final int F = Pattern.CASE_INSENSITIVE;

    private static final Pattern VIEW = Pattern.compile(
            "^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:ALGORITHM\\s*=\\s*\\w+\\s+)?"
                    + "(?:DEFINER\\s*=\\s*\\S+\\s+)?(?:SQL\\s+SECURITY\\s+\\w+\\s+)?VIEW\\b", F);
    private static final Pattern TRIGGER = Pattern.compile(
            "^\\s*CREATE\\s+(?:DEFINER\\s*=\\s*\\S+\\s+)?TRIGGER\\b", F);
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "^\\s*CREATE\\s+(?:UNIQUE\\s+|FULLTEXT\\s+|SPATIAL\\s+)?INDEX\\b", F);
    private static final Pattern ALTER_ADD_INDEX = Pattern.compile(
            "^\\s*ALTER\\s+TABLE\\s+\\S+\\s+ADD\\s+(?:UNIQUE\\s+|FULLTEXT\\s+|SPATIAL\\s+)?(?:INDEX|KEY)\\b", F);

    /** @param sql comment-free SQL as returned by {@link QueryValidator#validate} */
    public StatementType classify(String sql) {

        String word = SqlText.firstWord(sql);

        switch (word) {
            case "SELECT":
                return StatementType.SELECT;
            case "INSERT":
                return StatementType.INSERT;
            case "REPLACE":
                return StatementType.REPLACE;
            case "UPDATE":
                return StatementType.UPDATE;
            case "DELETE":
                return StatementType.DELETE;
            case "WITH":
                return classifyWith(sql);
            case "CREATE":
            case "ALTER":
                if (VIEW.matcher(sql).find()) return StatementType.CREATE_VIEW;
                if (TRIGGER.matcher(sql).find()) return StatementType.CREATE_TRIGGER;
                if (CREATE_INDEX.matcher(sql).find() || ALTER_ADD_INDEX.matcher(sql).find()) {
                    return StatementType.CREATE_INDEX;
                }
                return StatementType.UNSUPPORTED;
            default:
                return StatementType.UNSUPPORTED;
        }
    }

    private StatementType classifyWith(String sql) {
        String main = CteParser.parse(sql).mainStatement();
        return switch (SqlText.firstWord(main)) {
            case "SELECT" -> StatementType.CTE_SELECT;
            case "INSERT" -> StatementType.INSERT;
            case "UPDATE" -> StatementType.UPDATE;
            case "DELETE" -> StatementType.DELETE;
            default -> StatementType.UNSUPPORTED;
        };
    }
}
