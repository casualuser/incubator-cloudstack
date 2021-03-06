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
import com.cloud.api.response.SystemVmResponse;
import com.cloud.async.AsyncJob;
import com.cloud.event.EventTypes;
import com.cloud.user.Account;
import com.cloud.user.UserContext;
import com.cloud.vm.VirtualMachine;

@Implementation(responseObject=SystemVmResponse.class, description="Starts a system virtual machine.")
public class StartSystemVMCmd extends BaseAsyncCmd {
	public static final Logger s_logger = Logger.getLogger(StartSystemVMCmd.class.getName());

    private static final String s_name = "startsystemvmresponse";
    
    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @IdentityMapper(entityTableName="vm_instance")
    @Parameter(name=ApiConstants.ID, type=CommandType.LONG, required=true, description="The ID of the system virtual machine")
    private Long id;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return s_name;
    }
    
    public static String getResultObjectName() {
    	return "systemvm"; 
    }

    @Override
    public long getEntityOwnerId() {
        Account account = UserContext.current().getCaller();
        if (account != null) {
            return account.getId();
        }

        return Account.ACCOUNT_ID_SYSTEM; // no account info given, parent this command to SYSTEM so ERROR events are tracked
    }

    @Override
    public String getEventType() {
        VirtualMachine.Type type = _mgr.findSystemVMTypeById(getId());
        if(type == VirtualMachine.Type.ConsoleProxy){
            return EventTypes.EVENT_PROXY_START;
        }
        else{
            return EventTypes.EVENT_SSVM_START;
        }
    }

    @Override
    public String getEventDescription() {
        return  "starting system vm: " + getId();
    }
    
    public AsyncJob.Type getInstanceType() {
    	return AsyncJob.Type.SystemVm;
    }
    
    public Long getInstanceId() {
    	return getId();
    }
	
    @Override
    public void execute(){
        UserContext.current().setEventDetails("Vm Id: "+getId());
        VirtualMachine instance = _mgr.startSystemVM(getId());
        if (instance != null) {
            SystemVmResponse response = _responseGenerator.createSystemVmResponse(instance);
            response.setResponseName(getCommandName());
            this.setResponseObject(response);
        } else {
            throw new ServerApiException(BaseCmd.INTERNAL_ERROR, "Fail to start system vm");
        }
    }
}
