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
package com.cloud.api.commands;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import com.cloud.api.ApiConstants;
import com.cloud.api.BaseListCmd;
import com.cloud.api.IdentityMapper;
import com.cloud.api.Implementation;
import com.cloud.api.Parameter;
import com.cloud.api.response.ClusterResponse;
import com.cloud.api.response.ListResponse;
import com.cloud.org.Cluster;
import com.cloud.utils.Pair;

@Implementation(description="Lists clusters.", responseObject=ClusterResponse.class)
public class ListClustersCmd extends BaseListCmd {
    public static final Logger s_logger = Logger.getLogger(ListServiceOfferingsCmd.class.getName());

    private static final String s_name = "listclustersresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @IdentityMapper(entityTableName="cluster")
    @Parameter(name=ApiConstants.ID, type=CommandType.LONG, description="lists clusters by the cluster ID")
    private Long id;

    @Parameter(name=ApiConstants.NAME, type=CommandType.STRING, description="lists clusters by the cluster name")
    private String clusterName;

    @IdentityMapper(entityTableName="host_pod_ref")
    @Parameter(name=ApiConstants.POD_ID, type=CommandType.LONG, description="lists clusters by Pod ID")
    private Long podId;

    @IdentityMapper(entityTableName="data_center")
    @Parameter(name=ApiConstants.ZONE_ID, type=CommandType.LONG, description="lists clusters by Zone ID")
    private Long zoneId;

    @Parameter(name=ApiConstants.HYPERVISOR, type=CommandType.STRING, description="lists clusters by hypervisor type")
    private String hypervisorType;

    @Parameter(name=ApiConstants.CLUSTER_TYPE, type=CommandType.STRING, description="lists clusters by cluster type")
    private String clusterType;
    
    @Parameter(name=ApiConstants.ALLOCATION_STATE, type=CommandType.STRING, description="lists clusters by allocation state")
    private String allocationState;
    
    @Parameter(name=ApiConstants.MANAGED_STATE, type=CommandType.STRING, description="whether this cluster is managed by cloudstack")
    private String managedState;
    
    @Parameter(name=ApiConstants.SHOW_CAPACITIES, type=CommandType.BOOLEAN, description="flag to display the capacity of the clusters")
    private Boolean showCapacities;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    public String getClusterName() {
        return clusterName;
    }

    public Long getPodId() {
        return podId;
    }

    public Long getZoneId() {
        return zoneId;
    }
    
    public String getHypervisorType() {
    	return hypervisorType;
    }
    
    public String getClusterType() {
    	return clusterType;
    }

    public String getAllocationState() {
    	return allocationState;
    }
    

    public String getManagedstate() {
        return managedState;
    }

    public void setManagedstate(String managedstate) {
        this.managedState = managedstate;
    }


    public Boolean getShowCapacities() {
		return showCapacities;
	}

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

	@Override
    public String getCommandName() {
        return s_name;
    }
    
    @Override
    public void execute(){
        Pair<List<? extends Cluster>, Integer> result = _mgr.searchForClusters(this);
        ListResponse<ClusterResponse> response = new ListResponse<ClusterResponse>();
        List<ClusterResponse> clusterResponses = new ArrayList<ClusterResponse>();
        for (Cluster cluster : result.first()) {
            ClusterResponse clusterResponse = _responseGenerator.createClusterResponse(cluster,showCapacities);
            clusterResponse.setObjectName("cluster");
            clusterResponses.add(clusterResponse);
        }

        response.setResponses(clusterResponses, result.second());
        response.setResponseName(getCommandName());
        this.setResponseObject(response);
    }
}
