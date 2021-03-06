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
package com.cloud.network.guru;

import javax.ejb.Local;

import org.apache.log4j.Logger;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapcityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.State;
import com.cloud.network.NetworkManager;
import com.cloud.network.NetworkProfile;
import com.cloud.network.NetworkVO;
import com.cloud.network.Networks.AddressFormat;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.IsolationType;
import com.cloud.network.Networks.Mode;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.vpc.PrivateIpAddress;
import com.cloud.network.vpc.PrivateIpVO;
import com.cloud.network.vpc.dao.PrivateIpDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.user.Account;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.component.Inject;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.Nic.ReservationStrategy;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;

@Local(value = NetworkGuru.class)
public class PrivateNetworkGuru extends AdapterBase implements NetworkGuru {
    private static final Logger s_logger = Logger.getLogger(PrivateNetworkGuru.class);
    @Inject
    protected ConfigurationManager _configMgr;
    @Inject
    protected PrivateIpDao _privateIpDao;
    @Inject
    protected NetworkManager _networkMgr;
    
    private static final TrafficType[] _trafficTypes = {TrafficType.Guest};

    protected PrivateNetworkGuru() {
        super();
    }

    @Override
    public boolean isMyTrafficType(TrafficType type) {
        for (TrafficType t : _trafficTypes) {
            if (t == type) {
                return true;
            }
        }
        return false;
    }

    @Override
    public TrafficType[] getSupportedTrafficType() {
        return _trafficTypes;
    }

    protected boolean canHandle(NetworkOffering offering, DataCenter dc) {
        // This guru handles only system Guest network
        if (dc.getNetworkType() == NetworkType.Advanced && isMyTrafficType(offering.getTrafficType()) 
                && offering.getGuestType() == Network.GuestType.Isolated && offering.isSystemOnly()) {
            return true;
        } else {
            s_logger.trace("We only take care of system Guest networks of type   " + GuestType.Isolated + " in zone of type "
                    + NetworkType.Advanced);
            return false;
        }
    }

    @Override
    public Network design(NetworkOffering offering, DeploymentPlan plan, Network userSpecified, Account owner) {
        DataCenter dc = _configMgr.getZone(plan.getDataCenterId());
        if (!canHandle(offering, dc)) {
            return null;
        }

        NetworkVO network = new NetworkVO(offering.getTrafficType(), Mode.Static, BroadcastDomainType.Vlan, offering.getId(),
                State.Allocated, plan.getDataCenterId(), plan.getPhysicalNetworkId());
        if (userSpecified != null) {
            if ((userSpecified.getCidr() == null && userSpecified.getGateway() != null) || 
                    (userSpecified.getCidr() != null && userSpecified.getGateway() == null)) {
                throw new InvalidParameterValueException("cidr and gateway must be specified together.");
            }

            if (userSpecified.getCidr() != null) {
                network.setCidr(userSpecified.getCidr());
                network.setGateway(userSpecified.getGateway());
            } else {
                throw new InvalidParameterValueException("Can't design network " + network + "; netmask/gateway must be passed in");
            }

            if (offering.getSpecifyVlan()) {
                network.setBroadcastUri(userSpecified.getBroadcastUri());
                network.setState(State.Setup);
            }
        } else {
            throw new CloudRuntimeException("Can't design network " + network + "; netmask/gateway must be passed in");
           
        }

        return network;
    }

    @Override
    public void deallocate(Network network, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm) {
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Deallocate network: networkId: " + nic.getNetworkId() + ", ip: " + nic.getIp4Address());
        }
        
        PrivateIpVO ip = _privateIpDao.findByIpAndSourceNetworkId(nic.getNetworkId(), nic.getIp4Address());
        if (ip != null) {
            _privateIpDao.releaseIpAddress(nic.getIp4Address(), nic.getNetworkId());
        }
        nic.deallocate();
    }
    
    
    @Override
    public Network implement(Network network, NetworkOffering offering, DeployDestination dest, 
            ReservationContext context) throws InsufficientVirtualNetworkCapcityException {
        
        return network;
    }

    @Override
    public NicProfile allocate(Network network, NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm)
            throws InsufficientVirtualNetworkCapcityException, InsufficientAddressCapacityException {
        DataCenter dc = _configMgr.getZone(network.getDataCenterId());
        NetworkOffering offering = _configMgr.getNetworkOffering(network.getNetworkOfferingId());
        if (!canHandle(offering, dc)) {
            return null;
        }
        
        if (nic == null) {
            nic = new NicProfile(ReservationStrategy.Create, null, null, null, null);
        }
        
        getIp(nic, dc, network);

        if (nic.getIp4Address() == null) {
            nic.setStrategy(ReservationStrategy.Start);
        } else {
            nic.setStrategy(ReservationStrategy.Create);
        }

        return nic;
    }
    
    
    protected void getIp(NicProfile nic, DataCenter dc, Network network)
            throws InsufficientVirtualNetworkCapcityException,InsufficientAddressCapacityException {
        if (nic.getIp4Address() == null) {
            PrivateIpVO ipVO = _privateIpDao.allocateIpAddress(network.getDataCenterId(), network.getId(), null);
            String vlanTag = network.getBroadcastUri().getHost();
            String netmask = NetUtils.getCidrNetmask(network.getCidr());
            PrivateIpAddress ip = new PrivateIpAddress(ipVO, vlanTag, network.getGateway(), netmask, 
                    NetUtils.long2Mac(NetUtils.createSequenceBasedMacAddress(ipVO.getMacAddress())));

            nic.setIp4Address(ip.getIpAddress());
            nic.setGateway(ip.getGateway());
            nic.setNetmask(ip.getNetmask());
            nic.setIsolationUri(IsolationType.Vlan.toUri(ip.getVlanTag()));
            nic.setBroadcastUri(IsolationType.Vlan.toUri(ip.getVlanTag()));
            nic.setBroadcastType(BroadcastDomainType.Vlan);
            nic.setFormat(AddressFormat.Ip4);
            nic.setReservationId(String.valueOf(ip.getVlanTag()));
            nic.setMacAddress(ip.getMacAddress());
        }

        nic.setDns1(dc.getDns1());
        nic.setDns2(dc.getDns2());
    }
    

    @Override
    public void updateNicProfile(NicProfile profile, Network network) {
        DataCenter dc = _configMgr.getZone(network.getDataCenterId());
        if (profile != null) {
            profile.setDns1(dc.getDns1());
            profile.setDns2(dc.getDns2());
        }
    }

    @Override
    public void reserve(NicProfile nic, Network network, VirtualMachineProfile<? extends VirtualMachine> vm,
            DeployDestination dest, ReservationContext context)
            throws InsufficientVirtualNetworkCapcityException, InsufficientAddressCapacityException {
        if (nic.getIp4Address() == null) {
            getIp(nic, _configMgr.getZone(network.getDataCenterId()), network);
            nic.setStrategy(ReservationStrategy.Create);
        }
    }

    @Override
    public boolean release(NicProfile nic, VirtualMachineProfile<? extends VirtualMachine> vm, String reservationId) {
        return true;
    }

    @Override
    public void shutdown(NetworkProfile profile, NetworkOffering offering) {
        
    }

    @Override
    public boolean trash(Network network, NetworkOffering offering, Account owner) {
        return true;
    }

    @Override
    public void updateNetworkProfile(NetworkProfile networkProfile) {
        DataCenter dc = _configMgr.getZone(networkProfile.getDataCenterId());
        networkProfile.setDns1(dc.getDns1());
        networkProfile.setDns2(dc.getDns2());
    }
}
