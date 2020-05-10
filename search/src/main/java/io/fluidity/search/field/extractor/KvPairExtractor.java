package io.fluidity.search.field.extractor;

import org.graalvm.collections.Pair;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Support "CPU: XXX" - using regexp pattern
 */
public class KvPairExtractor implements Extractor {

    private final Pattern pattern;
    private final String expressionPart;

    public KvPairExtractor(String expressionPart) {
        String patternString = ".*(" + expressionPart + ")(\\d+).*";
        this.expressionPart = expressionPart;
        pattern = Pattern.compile(patternString);
    }

    @Override
    public Pair<String, Long> getKeyAndValue(String sourceName, String nextLine) {
        Matcher matcher = pattern.matcher(nextLine);
        if (matcher.matches()) {
            return Pair.create(matcher.group(1).trim(), Long.valueOf(matcher.group(2)));
        } else {
            return null;
        }
    }
}
