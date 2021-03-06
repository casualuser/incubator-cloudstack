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
package com.cloud.api.response;

import java.util.List;

import com.cloud.api.ApiConstants;
import com.cloud.serializer.Param;
import com.cloud.utils.IdentityProxy;
import com.google.gson.annotations.SerializedName;

@SuppressWarnings("unused")
public class ProjectResponse extends BaseResponse{
    
    @SerializedName(ApiConstants.ID) @Param(description="the id of the project")
    private IdentityProxy id = new IdentityProxy("projects");
    
    @SerializedName(ApiConstants.NAME) @Param(description="the name of the project")
    private String name;
    
    @SerializedName(ApiConstants.DISPLAY_TEXT) @Param(description="the displaytext of the project")
    private String displaytext;

    @SerializedName(ApiConstants.DOMAIN_ID) @Param(description="the domain id the project belongs to")
    private IdentityProxy domainId = new IdentityProxy("domain");
    
    @SerializedName(ApiConstants.DOMAIN) @Param(description="the domain name where the project belongs to")
    private String domain;
    
    @SerializedName(ApiConstants.ACCOUNT) @Param(description="the account name of the project's owner")
    private String ownerName;
    
    @SerializedName(ApiConstants.STATE) @Param(description="the state of the project")
    private String state;
    
    @SerializedName(ApiConstants.TAGS)  @Param(description="the list of resource tags associated with vm", responseObject = ResourceTagResponse.class)
    private List<ResourceTagResponse> tags;
 

    public void setId(Long id) {
        this.id.setValue(id);
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDisplaytext(String displaytext) {
        this.displaytext = displaytext;
    }

    public void setDomainId(Long domainId) {
        this.domainId.setValue(domainId);
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public void setOwner(String owner) {
        this.ownerName = owner;
    }

    public void setState(String state) {
        this.state = state;
    }
    
    public void setTags(List<ResourceTagResponse> tags) {
        this.tags = tags;
    }
}
