/*
 *
 *  Copyright (c) 2020. Liquidlabs Ltd <info@liquidlabs.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software  distributed under the License is distributed on an "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 *   See the License for the specific language governing permissions and  limitations under the License.
 *
 */

package io.fluidity.dataflow;

import java.util.List;

public class FlowInfo {
    public static final int START_TIME_INDEX = 0;
    public static final int END_TIME_INDEX = 1;
    /**
     * Info to render the span breakdown
     */
    public String flowId;
    public List<String> flowFiles;
    public long durationMs;
    public List<Long[]> durations;

    public FlowInfo() {
    }

    public FlowInfo(String flowId, List<String> flowFiles, List<Long[]> durations) {
        this.flowId = flowId;
        this.durations = durations;
        this.durationMs = getEnd() - getStart();
        this.flowFiles = flowFiles;
    }

    public long getStart() {
        return durations.get(0)[0];
    }

    public long getEnd() {
        return durations.get(durations.size() - 1)[1];
    }

    public long getDuration() {
        return getEnd() - getStart();
    }

    public long[] getMinMaxOp2OpLatencyWithMaxOpAndE2E() {
        long min = Long.MAX_VALUE;
        long max = 0;
        long maxOpDuration = 0;
        for (int i = 0; i < durations.size(); i++) {
            if (i > 0) {
                long interval = durations.get(i)[START_TIME_INDEX] - durations.get(i - 1)[END_TIME_INDEX];
                if (interval < min || interval == Long.MAX_VALUE) min = interval;
                if (interval > max) max = interval;
            }
            long duration = durations.get(i)[1] - durations.get(i)[0];
            if (maxOpDuration < duration) maxOpDuration = duration;
        }
        return new long[]{min, max, maxOpDuration, durationMs};
    }

}
