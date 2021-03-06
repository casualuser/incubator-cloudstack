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

import java.util.List;

import org.apache.log4j.Logger;

import com.cloud.api.ApiConstants;
import com.cloud.api.BaseAsyncCreateCmd;
import com.cloud.api.BaseCmd;
import com.cloud.api.IdentityMapper;
import com.cloud.api.Implementation;
import com.cloud.api.Parameter;
import com.cloud.api.ServerApiException;
import com.cloud.api.response.AutoScaleVmGroupResponse;
import com.cloud.async.AsyncJob;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.network.as.AutoScaleVmGroup;
import com.cloud.network.rules.LoadBalancer;

@Implementation(description = "Creates and automatically starts a virtual machine based on a service offering, disk offering, and template.", responseObject = AutoScaleVmGroupResponse.class)
public class CreateAutoScaleVmGroupCmd extends BaseAsyncCreateCmd {
    public static final Logger s_logger = Logger.getLogger(CreateAutoScaleVmGroupCmd.class.getName());

    private static final String s_name = "autoscalevmgroupresponse";

    // ///////////////////////////////////////////////////
    // ////////////// API parameters /////////////////////
    // ///////////////////////////////////////////////////

    @IdentityMapper(entityTableName = "firewall_rules")
    @Parameter(name = ApiConstants.LBID, type = CommandType.LONG, required = true, description = "the ID of the load balancer rule")
    private long lbRuleId;

    @Parameter(name = ApiConstants.MIN_MEMBERS, type = CommandType.INTEGER, required = true, description = "the minimum number of members in the vmgroup, the number of instances in the vm group will be equal to or more than this number.")
    private int minMembers;

    @Parameter(name = ApiConstants.MAX_MEMBERS, type = CommandType.INTEGER, required = true, description = "the maximum number of members in the vmgroup, The number of instances in the vm group will be equal to or less than this number.")
    private int maxMembers;

    @Parameter(name = ApiConstants.INTERVAL, type = CommandType.INTEGER, description = "the frequency at which the conditions have to be evaluated")
    private Integer interval;

    @IdentityMapper(entityTableName = "autoscale_policies")
    @Parameter(name = ApiConstants.SCALEUP_POLICY_IDS, type = CommandType.LIST, collectionType = CommandType.LONG, required = true, description = "list of scaleup autoscale policies")
    private List<Long> scaleUpPolicyIds;

    @IdentityMapper(entityTableName = "autoscale_policies")
    @Parameter(name = ApiConstants.SCALEDOWN_POLICY_IDS, type = CommandType.LIST, collectionType = CommandType.LONG, required = true, description = "list of scaledown autoscale policies")
    private List<Long> scaleDownPolicyIds;

    @IdentityMapper(entityTableName = "autoscale_vmprofiles")
    @Parameter(name = ApiConstants.VMPROFILE_ID, type = CommandType.LONG, required = true, description = "the autoscale profile that contains information about the vms in the vm group.")
    private long profileId;

    // ///////////////////////////////////////////////////
    // ///////////////// Accessors ///////////////////////
    // ///////////////////////////////////////////////////

    @Override
    public String getEntityTable() {
        return "autoscale_vmgroups";
    }

    public int getMinMembers() {
        return minMembers;
    }

    public int getMaxMembers() {
        return maxMembers;
    }

    public Integer getInterval() {
        return interval;
    }

    public long getProfileId() {
        return profileId;
    }

    public List<Long> getScaleUpPolicyIds() {
        return scaleUpPolicyIds;
    }

    public List<Long> getScaleDownPolicyIds() {
        return scaleDownPolicyIds;
    }

    public long getLbRuleId() {
        return lbRuleId;
    }

    // ///////////////////////////////////////////////////
    // ///////////// API Implementation///////////////////
    // ///////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return s_name;
    }

    public static String getResultObjectName() {
        return "autoscalevmgroup";
    }

    @Override
    public long getEntityOwnerId() {
        LoadBalancer lb = _entityMgr.findById(LoadBalancer.class, getLbRuleId());
        if (lb == null) {
            throw new InvalidParameterValueException("Unable to find loadbalancer by lbRuleId");
        }
        return lb.getAccountId();
    }

    public void setLbRuleId(Long lbRuleId) {
        this.lbRuleId = lbRuleId;
    }

    @Override
    public String getEventType() {
        return EventTypes.EVENT_AUTOSCALEVMGROUP_CREATE;
    }

    @Override
    public String getCreateEventType() {
        return EventTypes.EVENT_AUTOSCALEVMGROUP_CREATE;
    }

    @Override
    public String getCreateEventDescription() {
        return "creating AutoScale Vm Group";
    }

    @Override
    public String getEventDescription() {
        return "configuring AutoScale Vm Group. Vm Group Id: " + getEntityId();
    }

    @Override
    public AsyncJob.Type getInstanceType() {
        return AsyncJob.Type.AutoScaleVmGroup;
    }

    @Override
    public void create() throws ResourceAllocationException {
        AutoScaleVmGroup result = _autoScaleService.createAutoScaleVmGroup(this);
        if (result != null) {
            this.setEntityId(result.getId());
        } else {
            throw new ServerApiException(BaseCmd.INTERNAL_ERROR, "Failed to create Autoscale Vm Group");
        }
    }

    @Override
    public void execute() {
        boolean success = false;
        AutoScaleVmGroup vmGroup = null;
        try
        {
            success = _autoScaleService.configureAutoScaleVmGroup(this);
            if (success) {
                vmGroup = _entityMgr.findById(AutoScaleVmGroup.class, getEntityId());
                AutoScaleVmGroupResponse responseObject = _responseGenerator.createAutoScaleVmGroupResponse(vmGroup);
                setResponseObject(responseObject);
                responseObject.setResponseName(getCommandName());
            }
        } catch (Exception ex) {
            // TODO what will happen if Resource Layer fails in a step inbetween
            s_logger.warn("Failed to create autoscale vm group", ex);
        } finally {
            if (!success || vmGroup == null) {
                _autoScaleService.deleteAutoScaleVmGroup(getEntityId());
                throw new ServerApiException(BaseCmd.INTERNAL_ERROR, "Failed to create Autoscale Vm Group");
            }
        }
    }
}
