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

import org.apache.log4j.Logger;

import com.cloud.api.ApiConstants;
import com.cloud.api.BaseAsyncCmd;
import com.cloud.api.BaseCmd;
import com.cloud.api.IdentityMapper;
import com.cloud.api.Implementation;
import com.cloud.api.Parameter;
import com.cloud.api.ServerApiException;
import com.cloud.api.response.SuccessResponse;
import com.cloud.event.EventTypes;
import com.cloud.user.Account;
import com.cloud.user.UserContext;

@Implementation(description = "Accepts or declines project invitation", responseObject = SuccessResponse.class, since = "3.0.0")
public class UpdateProjectInvitationCmd extends BaseAsyncCmd {
    public static final Logger s_logger = Logger.getLogger(UpdateProjectInvitationCmd.class.getName());
    private static final String s_name = "updateprojectinvitationresponse";

    // ///////////////////////////////////////////////////
    // ////////////// API parameters /////////////////////
    // ///////////////////////////////////////////////////
    @IdentityMapper(entityTableName = "projects")
    @Parameter(name = ApiConstants.PROJECT_ID, required = true, type = CommandType.LONG, description = "id of the project to join")
    private Long projectId;

    @Parameter(name = ApiConstants.ACCOUNT, type = CommandType.STRING, description = "account that is joining the project")
    private String accountName;

    @Parameter(name = ApiConstants.TOKEN, type = CommandType.STRING, description = "list invitations for specified account; this parameter has to be specified with domainId")
    private String token;

    @Parameter(name = ApiConstants.ACCEPT, type = CommandType.BOOLEAN, description = "if true, accept the invitation, decline if false. True by default")
    private Boolean accept;

    // ///////////////////////////////////////////////////
    // ///////////////// Accessors ///////////////////////
    // ///////////////////////////////////////////////////
    public Long getProjectId() {
        return projectId;
    }

    public String getAccountName() {
        return accountName;
    }

    @Override
    public String getCommandName() {
        return s_name;
    }

    public String getToken() {
        return token;
    }

    public Boolean getAccept() {
        if (accept == null) {
            return true;
        }
        return accept;
    }

    // ///////////////////////////////////////////////////
    // ///////////// API Implementation///////////////////
    // ///////////////////////////////////////////////////
    @Override
    public long getEntityOwnerId() {
        // TODO - return project entity ownerId
        return Account.ACCOUNT_ID_SYSTEM; // no account info given, parent this command to SYSTEM so ERROR events are
// tracked
    }

    @Override
    public void execute() {
        UserContext.current().setEventDetails("Project id: " + projectId + "; accountName " + accountName + "; accept " + getAccept());
        boolean result = _projectService.updateInvitation(projectId, accountName, token, getAccept());
        if (result) {
            SuccessResponse response = new SuccessResponse(getCommandName());
            this.setResponseObject(response);
        } else {
            throw new ServerApiException(BaseCmd.INTERNAL_ERROR, "Failed to join the project");
        }
    }

    @Override
    public String getEventType() {
        return EventTypes.EVENT_PROJECT_INVITATION_UPDATE;
    }

    @Override
    public String getEventDescription() {
        return "Updating project invitation for projectId " + projectId;
    }
}
