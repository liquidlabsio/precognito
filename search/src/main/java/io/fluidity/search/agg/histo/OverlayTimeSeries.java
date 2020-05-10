package io.fluidity.search.agg.histo;

import io.fluidity.util.DateUtil;
import org.graalvm.collections.Pair;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static io.fluidity.util.DateUtil.*;

/**
 * {
 * Provides a timeseries overlay with different intervals over the previous periods
 * <p>
 * It currently uses a generic rule to choose the overlay rule (but will become exposed as part of the search expression)
 * Rules:
 * - 1-7 days uses a 1-hour overlay with 1 minute granularity- i.e. all event are mapping into the current-rounded hour (i.e. 6-7pm) and the minute
 * - 7-30 days uses 1-day overlay with 10minute buckets
 * - 30+days uses 7 day overlay with 1 hour buckets
 * name: "Series 1",
 * data: [
 * [1486684800000, 34],
 * [1486771200000, 43],
 * [1486857600000, 31] ,
 * [1486944000000, 43],
 * [1487030400000, 33],
 * [1487116800000, 52]
 * ]
 * }
 */
public class OverlayTimeSeries<T> implements Series<T> {

    public String name;
    public String groupBy;
    public List<Pair<Long, T>> data = new ArrayList();
    public long delta = MINUTE;
    private long from;
    private long to;
    private long duration;

    public OverlayTimeSeries(){

    }
    public OverlayTimeSeries(String seriesName, String groupBy, long from, long to) {
        this.name = seriesName;
        this.groupBy = groupBy;
        buildHistogram(from, to);
    }

    @Override
    public List<Pair<Long, T>> data() {
        return data;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String groupBy() {
        return groupBy;
    }

    private void buildHistogram(long from, long to) {


        long duration = to - from;

        if (duration < DAY) {
            // < 1-day == 1 hour/minute
            delta = MINUTE;
            this.from = DateUtil.floorHour(to);
            this.to = DateUtil.ceilHour(to);
        } else if (duration < 7 * DAY) {
            // 1-7 days == 1 hour overlay by minute
            delta = MINUTE;
            this.from = DateUtil.floorHour(to);
            this.to = DateUtil.ceilHour(to);
        } else if (duration < DAY * 30) {
            // 7-30 days 1 day overlay by hour
            delta = HOUR;
            this.from = DateUtil.floorDay(to);
            this.to = DateUtil.ceilDay(to);
        } else {
            delta = DAY;
            this.from = DateUtil.floorDay(to);
            this.to = DateUtil.ceilDay(to);
        }

        for (long time = this.from; time <= this.to; time += delta) {
            data.add(Pair.create(time, null));
        }
        this.duration = this.to - this.from;
    }

    @Override
    public T get(long time) {
        int index = index(time);
        if (index < 0 || index >= data.size()) return null;
        return data.get(index).getRight();
    }

    @Override
    public void update(long time, T value) {
        int index = index(time);
        if (index < 0 || index >= data.size()) {
            System.out.println("Bad time series index:" + index);
            return;
        }
        Pair<Long, T> current = data.get(index);
        data.add(index, Pair.create(current.getLeft(), value));
    }

    @Override
    public String toString() {
        return "OverlayTimeSeries{" +
                "name='" + name + '\'' +
                ", delta=" + delta +
                ", from=" + new Date(from) +
                ", to=" + new Date(to) +
                ", duration=" + duration +
                '}';
    }

//    @Override
//    public int index(long time) {
//        long timeFromStart = time - from;
//        return (int) (timeFromStart / delta);
//    }


    @Override
    public int index(long time) {
        if (time < this.from) {
            // calculate how many factors prior to the window the request is
            long requestDelta = ((from - time) / this.duration) + 1;
            // translate back to an overlay so it looks like a normal time (i.e. index-0 is still index 0 bt yesterdays version)
            long translatedFromTime = from - (requestDelta * this.duration);
            long translatedTimeRequest = time - translatedFromTime;
            return (int) (translatedTimeRequest / delta);
        } else {
            long timeFromStart = time - from;
            return (int) (timeFromStart / delta);
        }
    }

    @Override
    public boolean hasData() {
        return data.stream().filter(item -> item.getRight() != null).count() > 0;
    }

    @Override
    public void merge(Series<T> series) {
        series.data().stream()
                .forEach(dataPoint ->
                        this.update(dataPoint.getLeft(), add(this.get(dataPoint.getLeft()), dataPoint.getRight()))
                );
    }

    /**
     * Cater for sentinal value of -1
     * @param currentValue
     * @param newValue
     * @return
     */
    private T add(T currentValue, T newValue) {
//        currentValue = currentValue == -1 ? 0 : currentValue;
//        newValue = newValue == -1 ? 0 : newValue;
        return newValue;//currentValue + newValue;
    }
}
