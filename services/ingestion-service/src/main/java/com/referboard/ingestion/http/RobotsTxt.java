package com.referboard.ingestion.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Minimal RFC 9309 robots.txt evaluator: groups by user-agent (our product token, else {@code *}), allow/disallow
 * rules with {@code *} and {@code $}, longest match wins, ties go to allow.
 */
public final class RobotsTxt {

    private record Rule(boolean allow, String path, Pattern pattern) {
    }

    private final List<Rule> rules;
    private final boolean unreachable;

    private RobotsTxt(List<Rule> rules) {
        this(rules, false);
    }

    private RobotsTxt(List<Rule> rules, boolean unreachable) {
        this.rules = rules;
        this.unreachable = unreachable;
    }

    /** robots.txt couldn't be read (5xx / network): RFC 9309 says treat the whole host as disallowed for now. */
    public static RobotsTxt unreachable() {
        return new RobotsTxt(List.of(rule(false, "/")), true);
    }

    public boolean isUnreachable() {
        return unreachable;
    }

    public static RobotsTxt allowAll() {
        return new RobotsTxt(List.of());
    }

    public static RobotsTxt disallowAll() {
        return new RobotsTxt(List.of(rule(false, "/")));
    }

    /** @param productToken our crawler name, e.g. {@code ReferBoardBot} */
    public static RobotsTxt parse(String content, String productToken) {
        String token = productToken.toLowerCase(Locale.ROOT);
        List<Rule> specific = new ArrayList<>();
        List<Rule> wildcard = new ArrayList<>();
        boolean groupMatchesUs = false;
        boolean groupIsWildcard = false;
        boolean inAgentLines = false;
        boolean sawSpecificGroup = false;

        for (String raw : content.split("\\R")) {
            String line = raw.replaceAll("#.*$", "").trim();
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (field) {
                case "user-agent" -> {
                    if (!inAgentLines) {
                        groupMatchesUs = false;
                        groupIsWildcard = false;
                    }
                    inAgentLines = true;
                    String agent = value.toLowerCase(Locale.ROOT);
                    if (agent.equals("*")) {
                        groupIsWildcard = true;
                    } else if (!agent.isEmpty() && token.contains(agent.split("/")[0])) {
                        groupMatchesUs = true;
                        sawSpecificGroup = true;
                    }
                }
                case "allow", "disallow" -> {
                    inAgentLines = false;
                    if (value.isEmpty()) {
                        continue; // "Disallow:" with no path allows everything
                    }
                    Rule rule = rule(field.equals("allow"), value);
                    if (groupMatchesUs) {
                        specific.add(rule);
                    } else if (groupIsWildcard) {
                        wildcard.add(rule);
                    }
                }
                default -> inAgentLines = false;
            }
        }
        return new RobotsTxt(sawSpecificGroup ? specific : wildcard);
    }

    public boolean isAllowed(String pathAndQuery) {
        String path = pathAndQuery == null || pathAndQuery.isEmpty() ? "/" : pathAndQuery;
        Rule best = null;
        for (Rule rule : rules) {
            if (rule.pattern().matcher(path).lookingAt()) {
                if (best == null || rule.path().length() > best.path().length()
                        || (rule.path().length() == best.path().length() && rule.allow())) {
                    best = rule;
                }
            }
        }
        return best == null || best.allow();
    }

    private static Rule rule(boolean allow, String path) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '$' && i == path.length() - 1) {
                regex.append("$");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return new Rule(allow, path, Pattern.compile(regex.toString()));
    }
}
