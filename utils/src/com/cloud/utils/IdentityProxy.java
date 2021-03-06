// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.utils;

public class IdentityProxy {
    private String _tableName;
    private Long _value;
    private String _idFieldName;

    public IdentityProxy() {
    }

    public IdentityProxy(String tableName) {
        _tableName = tableName;
    }

    public IdentityProxy(String tableName, Long id, String fieldName) {
    	_tableName = tableName;
    	_value = id;
    	_idFieldName = fieldName;
    }
    
    public String getTableName() {
        return _tableName;
    }

    public void setTableName(String tableName) {
        _tableName = tableName;
    }

    public Long getValue() {
        return _value;
    }

    public void setValue(Long value) {
        _value = value;
    }
    
    public void setidFieldName(String value) {
    	_idFieldName = value;
    }
    
    public String getidFieldName() {
    	return _idFieldName;
    }
}
