/*
 * Copyright 2009-2010 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.hyracks.control.cc.job.manager.events;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import edu.uci.ics.hyracks.control.cc.ClusterControllerService;
import edu.uci.ics.hyracks.control.cc.NodeControllerState;
import edu.uci.ics.hyracks.control.cc.job.JobAttempt;
import edu.uci.ics.hyracks.control.cc.job.JobRun;
import edu.uci.ics.hyracks.control.cc.job.manager.JobLifecycleHelper;

public class StageletFailureEvent implements Runnable {
    private final ClusterControllerService ccs;
    private final UUID jobId;
    private final UUID stageId;
    private final int attempt;
    private final String nodeId;

    public StageletFailureEvent(ClusterControllerService ccs, UUID jobId, UUID stageId, int attempt, String nodeId) {
        this.ccs = ccs;
        this.jobId = jobId;
        this.stageId = stageId;
        this.attempt = attempt;
        this.nodeId = nodeId;
    }

    @Override
    public void run() {
        final JobRun run = ccs.getRunMap().get(jobId);
        List<JobAttempt> attempts = run.getAttempts();
        JobAttempt ja = attempts.get(attempt);
        final Set<String> targetNodes = new HashSet<String>(ja.getParticipatingNodeIds());
        Map<String, NodeControllerState> nodeMap = new HashMap<String, NodeControllerState>();
        for (String nodeId : targetNodes) {
            nodeMap.get(nodeId).getActiveJobIds().remove(jobId);
        }
        JobLifecycleHelper.abortJob(ccs, jobId, targetNodes);
    }
}