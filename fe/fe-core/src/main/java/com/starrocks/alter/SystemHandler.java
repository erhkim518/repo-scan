// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/alter/SystemHandler.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.alter;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.starrocks.analysis.AddBackendClause;
import com.starrocks.analysis.AddComputeNodeClause;
import com.starrocks.analysis.AddFollowerClause;
import com.starrocks.analysis.AddObserverClause;
import com.starrocks.analysis.AlterClause;
import com.starrocks.analysis.AlterLoadErrorUrlClause;
import com.starrocks.analysis.CancelAlterSystemStmt;
import com.starrocks.analysis.CancelStmt;
import com.starrocks.analysis.DecommissionBackendClause;
import com.starrocks.analysis.DropBackendClause;
import com.starrocks.analysis.DropComputeNodeClause;
import com.starrocks.analysis.DropFollowerClause;
import com.starrocks.analysis.DropObserverClause;
import com.starrocks.analysis.ModifyBackendAddressClause;
import com.starrocks.analysis.ModifyBrokerClause;
import com.starrocks.analysis.ModifyFrontendAddressClause;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.TabletInvertedIndex;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.Pair;
import com.starrocks.common.UserException;
import com.starrocks.ha.FrontendNodeType;
import com.starrocks.qe.ShowResultSet;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.system.Backend;
import com.starrocks.system.SystemInfoService;
import org.apache.commons.lang.NotImplementedException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/*
 * SystemHandler is for
 * 1. add/drop/decommisson backends
 * 2. add/drop frontends
 * 3. add/drop/modify brokers
 */
public class SystemHandler extends AlterHandler {
    private static final Logger LOG = LogManager.getLogger(SystemHandler.class);

    public SystemHandler() {
        super("cluster");
    }


    @Override
    protected void runAfterCatalogReady() {
        super.runAfterCatalogReady();
        runAlterJobV2();
    }

    // check all decommissioned backends, if there is no tablet on that backend, drop it.
    private void runAlterJobV2() {
        SystemInfoService systemInfoService = GlobalStateMgr.getCurrentSystemInfo();
        TabletInvertedIndex invertedIndex = GlobalStateMgr.getCurrentInvertedIndex();
        // check if decommission is finished
        for (Long beId : systemInfoService.getBackendIds(false)) {
            Backend backend = systemInfoService.getBackend(beId);
            if (backend == null || !backend.isDecommissioned()) {
                continue;
            }

            List<Long> backendTabletIds = invertedIndex.getTabletIdsByBackendId(beId);
            if (backendTabletIds.isEmpty() && Config.drop_backend_after_decommission) {
                try {
                    systemInfoService.dropBackend(beId);
                    LOG.info("no tablet on decommission backend {}, drop it", beId);
                } catch (DdlException e) {
                    // does not matter, may be backend not exist
                    LOG.info("backend {} is dropped failed after decommission {}", beId, e.getMessage());
                }
                continue;
            }

            LOG.info("backend {} lefts {} replicas to decommission: {}", beId, backendTabletIds.size(),
                    backendTabletIds.size() <= 20 ? backendTabletIds : "too many");
        }
    }

    @Override
    public List<List<Comparable>> getAlterJobInfosByDb(Database db) {
        throw new NotImplementedException();
    }

    @Override
    // add synchronized to avoid process 2 or more stmts at same time
    public synchronized ShowResultSet process(List<AlterClause> alterClauses, Database dummyDb,
                                     OlapTable dummyTbl) throws UserException {
        Preconditions.checkArgument(alterClauses.size() == 1);
        AlterClause alterClause = alterClauses.get(0);
        if (alterClause instanceof AddBackendClause) {
            // add backend
            AddBackendClause addBackendClause = (AddBackendClause) alterClause;
            GlobalStateMgr.getCurrentSystemInfo().addBackends(addBackendClause.getHostPortPairs());
        } else if (alterClause instanceof ModifyBackendAddressClause) {
            // update Backend Address
            ModifyBackendAddressClause modifyBackendAddressClause = (ModifyBackendAddressClause) alterClause;
            return GlobalStateMgr.getCurrentSystemInfo().modifyBackendHost(modifyBackendAddressClause);
        } else if (alterClause instanceof DropBackendClause) {
            // drop backend
            DropBackendClause dropBackendClause = (DropBackendClause) alterClause;
            GlobalStateMgr.getCurrentSystemInfo().dropBackends(dropBackendClause);
        } else if (alterClause instanceof DecommissionBackendClause) {
            // decommission
            DecommissionBackendClause decommissionBackendClause = (DecommissionBackendClause) alterClause;
            // check request
            List<Backend> decommissionBackends = checkDecommission(decommissionBackendClause);

            // set backend's state as 'decommissioned'
            // for decommission operation, here is no decommission job. the system handler will check
            // all backend in decommission state
            for (Backend backend : decommissionBackends) {
                backend.setDecommissioned(true);
                GlobalStateMgr.getCurrentState().getEditLog().logBackendStateChange(backend);
                LOG.info("set backend {} to decommission", backend.getId());
            }

        } else if (alterClause instanceof AddObserverClause) {
            AddObserverClause clause = (AddObserverClause) alterClause;
            GlobalStateMgr.getCurrentState().addFrontend(FrontendNodeType.OBSERVER, clause.getHost(), clause.getPort());
        } else if (alterClause instanceof DropObserverClause) {
            DropObserverClause clause = (DropObserverClause) alterClause;
            GlobalStateMgr.getCurrentState()
                    .dropFrontend(FrontendNodeType.OBSERVER, clause.getHost(), clause.getPort());
        } else if (alterClause instanceof AddFollowerClause) {
            AddFollowerClause clause = (AddFollowerClause) alterClause;
            GlobalStateMgr.getCurrentState().addFrontend(FrontendNodeType.FOLLOWER, clause.getHost(), clause.getPort());
        } else if (alterClause instanceof ModifyFrontendAddressClause) {
            // update Frontend Address
            ModifyFrontendAddressClause modifyFrontendAddressClause = (ModifyFrontendAddressClause) alterClause;
            GlobalStateMgr.getCurrentState().modifyFrontendHost(modifyFrontendAddressClause);
        } else if (alterClause instanceof DropFollowerClause) {
            DropFollowerClause clause = (DropFollowerClause) alterClause;
            GlobalStateMgr.getCurrentState()
                    .dropFrontend(FrontendNodeType.FOLLOWER, clause.getHost(), clause.getPort());
        } else if (alterClause instanceof ModifyBrokerClause) {
            ModifyBrokerClause clause = (ModifyBrokerClause) alterClause;
            GlobalStateMgr.getCurrentState().getBrokerMgr().execute(clause);
        } else if (alterClause instanceof AlterLoadErrorUrlClause) {
            AlterLoadErrorUrlClause clause = (AlterLoadErrorUrlClause) alterClause;
            GlobalStateMgr.getCurrentState().getLoadInstance().setLoadErrorHubInfo(clause.getProperties());
        } else if (alterClause instanceof AddComputeNodeClause) {
            AddComputeNodeClause addComputeNodeClause = (AddComputeNodeClause) alterClause;
            GlobalStateMgr.getCurrentSystemInfo().addComputeNodes(addComputeNodeClause.getHostPortPairs());
        } else if (alterClause instanceof DropComputeNodeClause) {
            DropComputeNodeClause dropComputeNodeClause = (DropComputeNodeClause) alterClause;
            GlobalStateMgr.getCurrentSystemInfo().dropComputeNodes(dropComputeNodeClause.getHostPortPairs());
        } else {
            Preconditions.checkState(false, alterClause.getClass());
        }
        return null;
    }

    private List<Backend> checkDecommission(DecommissionBackendClause decommissionBackendClause)
            throws DdlException {
        return checkDecommission(decommissionBackendClause.getHostPortPairs());
    }

    /*
     * check if the specified backends can be decommissioned
     * 1. backend should exist.
     * 2. after decommission, the remaining backend num should meet the replication num.
     * 3. after decommission, The remaining space capacity can store data on decommissioned backends.
     */
    public static List<Backend> checkDecommission(List<Pair<String, Integer>> hostPortPairs)
            throws DdlException {
        SystemInfoService infoService = GlobalStateMgr.getCurrentSystemInfo();
        List<Backend> decommissionBackends = Lists.newArrayList();
        // check if exist
        for (Pair<String, Integer> pair : hostPortPairs) {
            Backend backend = infoService.getBackendWithHeartbeatPort(pair.first, pair.second);
            if (backend == null) {
                throw new DdlException("Backend does not exist[" + pair.first + ":" + pair.second + "]");
            }
            if (backend.isDecommissioned()) {
                // already under decommission, ignore it
                continue;
            }
            decommissionBackends.add(backend);
        }

        // TODO(cmy): check if replication num can be met
        // TODO(cmy): check remaining space

        return decommissionBackends;
    }

    @Override
    public synchronized void cancel(CancelStmt stmt) throws DdlException {
        CancelAlterSystemStmt cancelAlterSystemStmt = (CancelAlterSystemStmt) stmt;
        cancelAlterSystemStmt.getHostPortPairs();

        SystemInfoService infoService = GlobalStateMgr.getCurrentSystemInfo();
        // check if backends is under decommission
        List<Backend> backends = Lists.newArrayList();
        List<Pair<String, Integer>> hostPortPairs = cancelAlterSystemStmt.getHostPortPairs();
        for (Pair<String, Integer> pair : hostPortPairs) {
            // check if exist
            Backend backend = infoService.getBackendWithHeartbeatPort(pair.first, pair.second);
            if (backend == null) {
                throw new DdlException("Backend does not exists[" + pair.first + "]");
            }

            if (!backend.isDecommissioned()) {
                // it's ok. just log
                LOG.info("backend is not decommissioned[{}]", pair.first);
                continue;
            }

            backends.add(backend);
        }

        for (Backend backend : backends) {
            if (backend.setDecommissioned(false)) {
                GlobalStateMgr.getCurrentState().getEditLog().logBackendStateChange(backend);
            } else {
                LOG.info("backend is not decommissioned[{}]", backend.getHost());
            }
        }
    }

}
