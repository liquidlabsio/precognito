package io.precognito.search.matchers;

public class GrepMatcher implements PMatcher {
    public static final String PREFIX = "record.contains(";
    private final String expr;

    public GrepMatcher(String expression) {
        this.expr = expression.substring(PREFIX.length(), expression.length()-1);
    }

    public GrepMatcher() {
        this.expr = "NO!";
    }

    @Override
    public boolean isForMe(String expression) {
        return expression.startsWith(PREFIX);
    }

    @Override
    public boolean matches(String nextLine) {
        return nextLine.indexOf(expr) > -1;
    }

    @Override
    public PMatcher clone(String expr) {
        return new GrepMatcher(expr);
    }
}
