package io.fluidity.search.agg.histo;

public interface HistoFunction<V, T> {
    V calculate(V currentValue, T newValue, String nextLine, long position, long time, int histoIndex, String expression);
}
