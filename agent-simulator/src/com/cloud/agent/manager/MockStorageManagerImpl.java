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
package com.cloud.agent.manager;

import java.io.File;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ejb.Local;
import javax.naming.ConfigurationException;

import org.apache.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.AttachIsoCommand;
import com.cloud.agent.api.AttachVolumeAnswer;
import com.cloud.agent.api.AttachVolumeCommand;
import com.cloud.agent.api.BackupSnapshotAnswer;
import com.cloud.agent.api.BackupSnapshotCommand;
import com.cloud.agent.api.ComputeChecksumCommand;
import com.cloud.agent.api.CreatePrivateTemplateFromSnapshotCommand;
import com.cloud.agent.api.CreatePrivateTemplateFromVolumeCommand;
import com.cloud.agent.api.CreateStoragePoolCommand;
import com.cloud.agent.api.CreateVolumeFromSnapshotAnswer;
import com.cloud.agent.api.CreateVolumeFromSnapshotCommand;
import com.cloud.agent.api.DeleteSnapshotBackupCommand;
import com.cloud.agent.api.DeleteStoragePoolCommand;
import com.cloud.agent.api.GetStorageStatsAnswer;
import com.cloud.agent.api.GetStorageStatsCommand;
import com.cloud.agent.api.ManageSnapshotAnswer;
import com.cloud.agent.api.ManageSnapshotCommand;
import com.cloud.agent.api.ModifyStoragePoolAnswer;
import com.cloud.agent.api.ModifyStoragePoolCommand;
import com.cloud.agent.api.SecStorageSetupAnswer;
import com.cloud.agent.api.SecStorageSetupCommand;
import com.cloud.agent.api.SecStorageVMSetupCommand;
import com.cloud.agent.api.StoragePoolInfo;
import com.cloud.agent.api.storage.CopyVolumeAnswer;
import com.cloud.agent.api.storage.CopyVolumeCommand;
import com.cloud.agent.api.storage.CreateAnswer;
import com.cloud.agent.api.storage.CreateCommand;
import com.cloud.agent.api.storage.CreatePrivateTemplateAnswer;
import com.cloud.agent.api.storage.DeleteTemplateCommand;
import com.cloud.agent.api.storage.DestroyCommand;
import com.cloud.agent.api.storage.DownloadAnswer;
import com.cloud.agent.api.storage.DownloadCommand;
import com.cloud.agent.api.storage.DownloadProgressCommand;
import com.cloud.agent.api.storage.ListTemplateAnswer;
import com.cloud.agent.api.storage.ListTemplateCommand;
import com.cloud.agent.api.storage.PrimaryStorageDownloadAnswer;
import com.cloud.agent.api.storage.PrimaryStorageDownloadCommand;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.agent.api.to.VolumeTO;
import com.cloud.simulator.MockHost;
import com.cloud.simulator.MockSecStorageVO;
import com.cloud.simulator.MockStoragePoolVO;
import com.cloud.simulator.MockVMVO;
import com.cloud.simulator.MockVm;
import com.cloud.simulator.MockVolumeVO;
import com.cloud.simulator.MockVolumeVO.MockVolumeType;
import com.cloud.simulator.dao.MockHostDao;
import com.cloud.simulator.dao.MockSecStorageDao;
import com.cloud.simulator.dao.MockStoragePoolDao;
import com.cloud.simulator.dao.MockVMDao;
import com.cloud.simulator.dao.MockVolumeDao;
import com.cloud.storage.Storage;
import com.cloud.storage.VMTemplateStorageResourceAssoc;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.VMTemplateStorageResourceAssoc.Status;
import com.cloud.storage.template.TemplateInfo;
import com.cloud.utils.component.Inject;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.VirtualMachine.State;

@Local(value = { MockStorageManager.class })
public class MockStorageManagerImpl implements MockStorageManager {
	private static final Logger s_logger = Logger.getLogger(MockStorageManagerImpl.class);
	@Inject
	MockStoragePoolDao _mockStoragePoolDao = null;
	@Inject
	MockSecStorageDao _mockSecStorageDao = null;
	@Inject
	MockVolumeDao _mockVolumeDao = null;
	@Inject
	MockVMDao _mockVMDao = null;
	@Inject
	MockHostDao _mockHostDao = null;

	private MockVolumeVO findVolumeFromSecondary(String path, String ssUrl, MockVolumeType type) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			String volumePath = path.replaceAll(ssUrl, "");
			MockSecStorageVO secStorage = _mockSecStorageDao.findByUrl(ssUrl);
			if (secStorage == null) {
				return null;
			}
			volumePath = secStorage.getMountPoint() + volumePath;
			volumePath = volumePath.replaceAll("//", "/");
			MockVolumeVO volume = _mockVolumeDao.findByStoragePathAndType(volumePath);
			txn.commit();
			if (volume == null) {
				return null;
			}
			return volume;
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Unable to find volume " + path + " on secondary " + ssUrl, ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
	}

	@Override
	public PrimaryStorageDownloadAnswer primaryStorageDownload(PrimaryStorageDownloadCommand cmd) {
		MockVolumeVO template = findVolumeFromSecondary(cmd.getUrl(), cmd.getSecondaryStorageUrl(),
				MockVolumeType.TEMPLATE);
		if (template == null) {
			return new PrimaryStorageDownloadAnswer("Can't find primary storage");
		}

		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		MockStoragePoolVO primaryStorage = null;
		try {
			txn.start();
			primaryStorage = _mockStoragePoolDao.findByUuid(cmd.getPoolUuid());
			txn.commit();
			if (primaryStorage == null) {
				return new PrimaryStorageDownloadAnswer("Can't find primary storage");
			}
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when finding primary storagee " + cmd.getPoolUuid(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		String volumeName = UUID.randomUUID().toString();
		MockVolumeVO newVolume = new MockVolumeVO();
		newVolume.setName(volumeName);
		newVolume.setPath(primaryStorage.getMountPoint() + volumeName);
		newVolume.setPoolId(primaryStorage.getId());
		newVolume.setSize(template.getSize());
		newVolume.setType(MockVolumeType.VOLUME);
		txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			_mockVolumeDao.persist(newVolume);
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when saving volume " + newVolume, ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
		return new PrimaryStorageDownloadAnswer(newVolume.getPath(), newVolume.getSize());
	}

	@Override
	public CreateAnswer createVolume(CreateCommand cmd) {
		StorageFilerTO sf = cmd.getPool();
		DiskProfile dskch = cmd.getDiskCharacteristics();
		MockStoragePoolVO storagePool = null;
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			storagePool = _mockStoragePoolDao.findByUuid(sf.getUuid());
			txn.commit();
			if (storagePool == null) {
				return new CreateAnswer(cmd, "Failed to find storage pool: " + sf.getUuid());
			}
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when finding storage " + sf.getUuid(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		String volumeName = UUID.randomUUID().toString();
		MockVolumeVO volume = new MockVolumeVO();
		volume.setPoolId(storagePool.getId());
		volume.setName(volumeName);
		volume.setPath(storagePool.getMountPoint() + volumeName);
		volume.setSize(dskch.getSize());
		volume.setType(MockVolumeType.VOLUME);
		txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			volume = _mockVolumeDao.persist(volume);
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when saving volume " + volume, ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		VolumeTO volumeTo = new VolumeTO(cmd.getVolumeId(), dskch.getType(), sf.getType(), sf.getUuid(),
				volume.getName(), storagePool.getMountPoint(), volume.getPath(), volume.getSize(), null);

		return new CreateAnswer(cmd, volumeTo);
	}

	@Override
	public AttachVolumeAnswer AttachVolume(AttachVolumeCommand cmd) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			String poolid = cmd.getPoolUuid();
			String volumeName = cmd.getVolumeName();
			MockVolumeVO volume = _mockVolumeDao.findByStoragePathAndType(cmd.getVolumePath());
			if (volume == null) {
				return new AttachVolumeAnswer(cmd, "Can't find volume:" + volumeName + "on pool:" + poolid);
			}

			String vmName = cmd.getVmName();
			MockVMVO vm = _mockVMDao.findByVmName(vmName);
			if (vm == null) {
				return new AttachVolumeAnswer(cmd, "can't vm :" + vmName);
			}
			txn.commit();

			return new AttachVolumeAnswer(cmd, cmd.getDeviceId());
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when attaching volume " + cmd.getVolumeName() + " to VM "
					+ cmd.getVmName(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
	}

	@Override
	public Answer AttachIso(AttachIsoCommand cmd) {
		MockVolumeVO iso = findVolumeFromSecondary(cmd.getIsoPath(), cmd.getStoreUrl(), MockVolumeType.ISO);
		if (iso == null) {
			return new Answer(cmd, false, "Failed to find the iso: " + cmd.getIsoPath() + "on secondary storage "
					+ cmd.getStoreUrl());
		}

		String vmName = cmd.getVmName();
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		MockVMVO vm = null;
		try {
			txn.start();
			vm = _mockVMDao.findByVmName(vmName);
			txn.commit();
			if (vm == null) {
				return new Answer(cmd, false, "can't vm :" + vmName);
			}
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when attaching iso to vm " + vm.getName(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
		return new Answer(cmd);
	}

	@Override
	public Answer DeleteStoragePool(DeleteStoragePoolCommand cmd) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			MockStoragePoolVO storage = _mockStoragePoolDao.findByUuid(cmd.getPool().getUuid());
			if (storage == null) {
				return new Answer(cmd, false, "can't find storage pool:" + cmd.getPool().getUuid());
			}
			_mockStoragePoolDao.remove(storage.getId());
			txn.commit();
			return new Answer(cmd);
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when deleting storage pool " + cmd.getPool().getPath(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
	}

	@Override
	public ModifyStoragePoolAnswer ModifyStoragePool(ModifyStoragePoolCommand cmd) {
		StorageFilerTO sf = cmd.getPool();
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		MockStoragePoolVO storagePool = null;
		try {
			txn.start();
			storagePool = _mockStoragePoolDao.findByUuid(sf.getUuid());
			if (storagePool == null) {
				storagePool = new MockStoragePoolVO();
				storagePool.setUuid(sf.getUuid());
				storagePool.setMountPoint("/mnt/" + sf.getUuid() + File.separator);

				Long size = DEFAULT_HOST_STORAGE_SIZE;
				String path = sf.getPath();
				int index = path.lastIndexOf("/");
				if (index != -1) {
					path = path.substring(index + 1);
					if (path != null) {
						String values[] = path.split("=");
						if (values.length > 1 && values[0].equalsIgnoreCase("size")) {
							size = Long.parseLong(values[1]);
						}
					}
				}
				storagePool.setCapacity(size);
				storagePool.setStorageType(sf.getType());
				storagePool = _mockStoragePoolDao.persist(storagePool);
			}
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when modifying storage pool " + cmd.getPool().getPath(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
		return new ModifyStoragePoolAnswer(cmd, storagePool.getCapacity(), 0, new HashMap<String, TemplateInfo>());
	}

	@Override
	public Answer CreateStoragePool(CreateStoragePoolCommand cmd) {
		StorageFilerTO sf = cmd.getPool();
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		MockStoragePoolVO storagePool = null;
		try {
			txn.start();
			storagePool = _mockStoragePoolDao.findByUuid(sf.getUuid());
			if (storagePool == null) {
				storagePool = new MockStoragePoolVO();
				storagePool.setUuid(sf.getUuid());
				storagePool.setMountPoint("/mnt/" + sf.getUuid() + File.separator);

				Long size = DEFAULT_HOST_STORAGE_SIZE;
				String path = sf.getPath();
				int index = path.lastIndexOf("/");
				if (index != -1) {
					path = path.substring(index + 1);
					if (path != null) {
						String values[] = path.split("=");
						if (values.length > 1 && values[0].equalsIgnoreCase("size")) {
							size = Long.parseLong(values[1]);
						}
					}
				}
				storagePool.setCapacity(size);
				storagePool.setStorageType(sf.getType());
				storagePool = _mockStoragePoolDao.persist(storagePool);
			}
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when creating storage pool " + cmd.getPool().getPath(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
		return new ModifyStoragePoolAnswer(cmd, storagePool.getCapacity(), 0, new HashMap<String, TemplateInfo>());
	}

	@Override
	public Answer SecStorageSetup(SecStorageSetupCommand cmd) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		MockSecStorageVO storage = null;
		try {
			txn.start();
			storage = _mockSecStorageDao.findByUrl(cmd.getSecUrl());
			if (storage == null) {
				return new Answer(cmd, false, "can't find the storage");
			}
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when setting up sec storage" + cmd.getSecUrl(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
		return new SecStorageSetupAnswer(storage.getMountPoint());
	}

	@Override
	public Answer ListTemplates(ListTemplateCommand cmd) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		MockSecStorageVO storage = null;
		try {
			txn.start();
			storage = _mockSecStorageDao.findByUrl(cmd.getSecUrl());
			if (storage == null) {
				return new Answer(cmd, false, "Failed to get secondary storage");
			}
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when finding sec storage " + cmd.getSecUrl(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			List<MockVolumeVO> templates = _mockVolumeDao.findByStorageIdAndType(storage.getId(),
					MockVolumeType.TEMPLATE);

			Map<String, TemplateInfo> templateInfos = new HashMap<String, TemplateInfo>();
			for (MockVolumeVO template : templates) {
				templateInfos.put(template.getName(), new TemplateInfo(template.getName(), template.getPath()
						.replaceAll(storage.getMountPoint(), ""), template.getSize(), template.getSize(), true, false));
			}
			txn.commit();
			return new ListTemplateAnswer(cmd.getSecUrl(), templateInfos);
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when finding template on sec storage " + storage.getId(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
	}

	@Override
	public Answer Destroy(DestroyCommand cmd) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			MockVolumeVO volume = _mockVolumeDao.findByStoragePathAndType(cmd.getVolume().getPath());
			if (volume != null) {
				_mockVolumeDao.remove(volume.getId());
			}

			if (cmd.getVmName() != null) {
				MockVm vm = _mockVMDao.findByVmName(cmd.getVmName());
				vm.setState(State.Expunging);
				if (vm != null) {
					MockVMVO vmVo = _mockVMDao.createForUpdate(vm.getId());
					_mockVMDao.update(vm.getId(), vmVo);
				}
			}
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when destroying volume " + cmd.getVolume().getPath(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
		return new Answer(cmd);
	}

	@Override
	public DownloadAnswer Download(DownloadCommand cmd) {
		MockSecStorageVO ssvo = null;
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			ssvo = _mockSecStorageDao.findByUrl(cmd.getSecUrl());
			if (ssvo == null) {
				return new DownloadAnswer("can't find secondary storage",
						VMTemplateStorageResourceAssoc.Status.DOWNLOAD_ERROR);
			}
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error accessing secondary storage " + cmd.getSecUrl(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		MockVolumeVO volume = new MockVolumeVO();
		volume.setPoolId(ssvo.getId());
		volume.setName(cmd.getName());
		volume.setPath(ssvo.getMountPoint() + cmd.getName());
		volume.setSize(0);
		volume.setType(MockVolumeType.TEMPLATE);
		volume.setStatus(Status.DOWNLOAD_IN_PROGRESS);
		txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			volume = _mockVolumeDao.persist(volume);
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when saving volume " + volume, ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
		return new DownloadAnswer(String.valueOf(volume.getId()), 0, "Downloading", Status.DOWNLOAD_IN_PROGRESS,
				cmd.getName(), cmd.getName(), volume.getSize(), volume.getSize(), null);
	}

	@Override
	public DownloadAnswer DownloadProcess(DownloadProgressCommand cmd) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			String volumeId = cmd.getJobId();
			MockVolumeVO volume = _mockVolumeDao.findById(Long.parseLong(volumeId));
			if (volume == null) {
				return new DownloadAnswer("Can't find the downloading volume", Status.ABANDONED);
			}

			long size = Math.min(volume.getSize() + DEFAULT_TEMPLATE_SIZE / 5, DEFAULT_TEMPLATE_SIZE);
			volume.setSize(size);

			double volumeSize = volume.getSize();
			double pct = volumeSize / DEFAULT_TEMPLATE_SIZE;
			if (pct >= 1.0) {
				volume.setStatus(Status.DOWNLOADED);
				_mockVolumeDao.update(volume.getId(), volume);
				txn.commit();
				return new DownloadAnswer(cmd.getJobId(), 100, cmd,
						com.cloud.storage.VMTemplateStorageResourceAssoc.Status.DOWNLOADED, volume.getPath(),
						volume.getName());
			} else {
				_mockVolumeDao.update(volume.getId(), volume);
				txn.commit();
				return new DownloadAnswer(cmd.getJobId(), (int) (pct * 100.0), cmd,
						com.cloud.storage.VMTemplateStorageResourceAssoc.Status.DOWNLOAD_IN_PROGRESS, volume.getPath(),
						volume.getName());
			}
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error during download job " + cmd.getJobId(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
	}

	@Override
	public GetStorageStatsAnswer GetStorageStats(GetStorageStatsCommand cmd) {
		String uuid = cmd.getStorageId();
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			if (uuid == null) {
				String secUrl = cmd.getSecUrl();
				MockSecStorageVO secondary = _mockSecStorageDao.findByUrl(secUrl);
				if (secondary == null) {
					return new GetStorageStatsAnswer(cmd, "Can't find the secondary storage:" + secUrl);
				}
				Long totalUsed = _mockVolumeDao.findTotalStorageId(secondary.getId());
				txn.commit();
				return new GetStorageStatsAnswer(cmd, secondary.getCapacity(), totalUsed);
			} else {
				MockStoragePoolVO pool = _mockStoragePoolDao.findByUuid(uuid);
				if (pool == null) {
					return new GetStorageStatsAnswer(cmd, "Can't find the pool");
				}
				Long totalUsed = _mockVolumeDao.findTotalStorageId(pool.getId());
				if (totalUsed == null) {
					totalUsed = 0L;
				}
				txn.commit();
				return new GetStorageStatsAnswer(cmd, pool.getCapacity(), totalUsed);
			}
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("DBException during storage stats collection for pool " + uuid, ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
	}

	@Override
	public ManageSnapshotAnswer ManageSnapshot(ManageSnapshotCommand cmd) {
		String volPath = cmd.getVolumePath();
		MockVolumeVO volume = null;
		MockStoragePoolVO storagePool = null;
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			volume = _mockVolumeDao.findByStoragePathAndType(volPath);
			if (volume == null) {
				return new ManageSnapshotAnswer(cmd, false, "Can't find the volume");
			}
			storagePool = _mockStoragePoolDao.findById(volume.getPoolId());
			if (storagePool == null) {
				return new ManageSnapshotAnswer(cmd, false, "Can't find the storage pooll");
			}
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Unable to perform snapshot", ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		String mountPoint = storagePool.getMountPoint();
		MockVolumeVO snapshot = new MockVolumeVO();

		snapshot.setName(cmd.getSnapshotName());
		snapshot.setPath(mountPoint + cmd.getSnapshotName());
		snapshot.setSize(volume.getSize());
		snapshot.setPoolId(storagePool.getId());
		snapshot.setType(MockVolumeType.SNAPSHOT);
		snapshot.setStatus(Status.DOWNLOADED);
		txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			snapshot = _mockVolumeDao.persist(snapshot);
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when saving snapshot " + snapshot, ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		return new ManageSnapshotAnswer(cmd, snapshot.getId(), snapshot.getPath(), true, "");
	}

	@Override
	public BackupSnapshotAnswer BackupSnapshot(BackupSnapshotCommand cmd, SimulatorInfo info) {
		// emulate xenserver backupsnapshot, if the base volume is deleted, then
		// backupsnapshot failed
		MockVolumeVO volume = null;
		MockVolumeVO snapshot = null;
		MockSecStorageVO secStorage = null;
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			volume = _mockVolumeDao.findByStoragePathAndType(cmd.getVolumePath());
			if (volume == null) {
				return new BackupSnapshotAnswer(cmd, false, "Can't find base volume: " + cmd.getVolumePath(), null,
						true);
			}
			String snapshotPath = cmd.getSnapshotUuid();
			snapshot = _mockVolumeDao.findByStoragePathAndType(snapshotPath);
			if (snapshot == null) {
				return new BackupSnapshotAnswer(cmd, false, "can't find snapshot" + snapshotPath, null, true);
			}

			String secStorageUrl = cmd.getSecondaryStorageUrl();
			secStorage = _mockSecStorageDao.findByUrl(secStorageUrl);
			if (secStorage == null) {
				return new BackupSnapshotAnswer(cmd, false, "can't find sec storage" + snapshotPath, null, true);
			}
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when backing up snapshot");
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
		
		MockVolumeVO newsnapshot = new MockVolumeVO();
		String name = UUID.randomUUID().toString();
		newsnapshot.setName(name);
		newsnapshot.setPath(secStorage.getMountPoint() + name);
		newsnapshot.setPoolId(secStorage.getId());
		newsnapshot.setSize(snapshot.getSize());
		newsnapshot.setStatus(Status.DOWNLOADED);
		newsnapshot.setType(MockVolumeType.SNAPSHOT);
		txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			snapshot = _mockVolumeDao.persist(snapshot);
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when backing up snapshot " + newsnapshot, ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		return new BackupSnapshotAnswer(cmd, true, null, newsnapshot.getName(), true);
	}

	@Override
	public Answer DeleteSnapshotBackup(DeleteSnapshotBackupCommand cmd) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			MockVolumeVO backSnapshot = _mockVolumeDao.findByName(cmd.getSnapshotUuid());
			if (backSnapshot == null) {
				return new Answer(cmd, false, "can't find the backupsnapshot: " + cmd.getSnapshotUuid());
			}
			_mockVolumeDao.remove(backSnapshot.getId());
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when deleting snapshot");
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
		return new Answer(cmd);
	}

	@Override
	public CreateVolumeFromSnapshotAnswer CreateVolumeFromSnapshot(CreateVolumeFromSnapshotCommand cmd) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		MockVolumeVO backSnapshot = null;
		MockStoragePoolVO primary = null;
		try {
			txn.start();
			backSnapshot = _mockVolumeDao.findByName(cmd.getSnapshotUuid());
			if (backSnapshot == null) {
				return new CreateVolumeFromSnapshotAnswer(cmd, false, "can't find the backupsnapshot: "
						+ cmd.getSnapshotUuid(), null);
			}

			primary = _mockStoragePoolDao.findByUuid(cmd.getPrimaryStoragePoolNameLabel());
			if (primary == null) {
				return new CreateVolumeFromSnapshotAnswer(cmd, false, "can't find the primary storage: "
						+ cmd.getPrimaryStoragePoolNameLabel(), null);
			}
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when creating volume from snapshot", ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		String uuid = UUID.randomUUID().toString();
		MockVolumeVO volume = new MockVolumeVO();

		volume.setName(uuid);
		volume.setPath(primary.getMountPoint() + uuid);
		volume.setPoolId(primary.getId());
		volume.setSize(backSnapshot.getSize());
		volume.setStatus(Status.DOWNLOADED);
		volume.setType(MockVolumeType.VOLUME);
		txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			_mockVolumeDao.persist(volume);
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when creating volume from snapshot " + volume, ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		return new CreateVolumeFromSnapshotAnswer(cmd, true, null, volume.getPath());
	}

	@Override
	public Answer DeleteTemplate(DeleteTemplateCommand cmd) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			MockVolumeVO template = _mockVolumeDao.findByStoragePathAndType(cmd.getTemplatePath());
			if (template == null) {
				return new Answer(cmd, false, "can't find template:" + cmd.getTemplatePath());
			}
			_mockVolumeDao.remove(template.getId());
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when deleting template");
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
		return new Answer(cmd);
	}

	@Override
	public Answer SecStorageVMSetup(SecStorageVMSetupCommand cmd) {
		return new Answer(cmd);
	}

	@Override
	public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
		// TODO Auto-generated method stub
		return true;
	}

	@Override
	public boolean start() {
		// TODO Auto-generated method stub
		return true;
	}

	@Override
	public boolean stop() {
		// TODO Auto-generated method stub
		return true;
	}

	@Override
	public String getName() {
		return this.getClass().getSimpleName();
	}

	@Override
	public void preinstallTemplates(String url, long zoneId) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		MockSecStorageVO storage = null;
		try {
			txn.start();
			storage = _mockSecStorageDao.findByUrl(url);
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Unable to find sec storage at " + url, ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
		if (storage == null) {
			storage = new MockSecStorageVO();
			URI uri;
			try {
				uri = new URI(url);
			} catch (URISyntaxException e) {
				return;
			}

			String nfsHost = uri.getHost();
			String nfsPath = uri.getPath();
			String path = nfsHost + ":" + nfsPath;
			String dir = "/mnt/" + UUID.nameUUIDFromBytes(path.getBytes()).toString() + File.separator;

			storage.setUrl(url);
			storage.setCapacity(DEFAULT_HOST_STORAGE_SIZE);

			storage.setMountPoint(dir);
			txn = Transaction.open(Transaction.SIMULATOR_DB);
			try {
				txn.start();
				storage = _mockSecStorageDao.persist(storage);
				txn.commit();
			} catch (Exception ex) {
				txn.rollback();
				throw new CloudRuntimeException("Error when saving storage " + storage, ex);
			} finally {
				txn.close();
                txn = Transaction.open(Transaction.CLOUD_DB);
                txn.close();
			}

			// preinstall default templates into secondary storage
			long defaultTemplateSize = 2 * 1024 * 1024 * 1024L;
			MockVolumeVO template = new MockVolumeVO();
			template.setName("simulator-domR");
			template.setPath(storage.getMountPoint() + "template/tmpl/1/9/" + UUID.randomUUID().toString());
			template.setPoolId(storage.getId());
			template.setSize(defaultTemplateSize);
			template.setType(MockVolumeType.TEMPLATE);
			template.setStatus(Status.DOWNLOADED);
			txn = Transaction.open(Transaction.SIMULATOR_DB);
			try {
				txn.start();
				template = _mockVolumeDao.persist(template);
				txn.commit();
			} catch (Exception ex) {
				txn.rollback();
				throw new CloudRuntimeException("Error when saving template " + template, ex);
			} finally {
				txn.close();
                txn = Transaction.open(Transaction.CLOUD_DB);
                txn.close();
			}

			template = new MockVolumeVO();
			template.setName("simulator-Centos");
			template.setPath(storage.getMountPoint() + "template/tmpl/1/10/" + UUID.randomUUID().toString());
			template.setPoolId(storage.getId());
			template.setSize(defaultTemplateSize);
			template.setType(MockVolumeType.TEMPLATE);
			template.setStatus(Status.DOWNLOADED);
			txn = Transaction.open(Transaction.SIMULATOR_DB);
			try {
				txn.start();
				template = _mockVolumeDao.persist(template);
				txn.commit();
			} catch (Exception ex) {
				txn.rollback();
				throw new CloudRuntimeException("Error when saving template " + template, ex);
			} finally {
				txn.close();
                txn = Transaction.open(Transaction.CLOUD_DB);
                txn.close();
			}
		}

	}

	@Override
	public StoragePoolInfo getLocalStorage(String hostGuid) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		MockHost host = null; 
		MockStoragePoolVO storagePool = null;
		try {
			txn.start();
			host = _mockHostDao.findByGuid(hostGuid);
			storagePool = _mockStoragePoolDao.findByHost(hostGuid);
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Unable to find host " + hostGuid, ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
		
		if (storagePool == null) {
			String uuid = UUID.randomUUID().toString();
			storagePool = new MockStoragePoolVO();
			storagePool.setUuid(uuid);
			storagePool.setMountPoint("/mnt/" + uuid + File.separator);
			storagePool.setCapacity(DEFAULT_HOST_STORAGE_SIZE);
			storagePool.setHostGuid(hostGuid);
			storagePool.setStorageType(StoragePoolType.Filesystem);
			txn = Transaction.open(Transaction.SIMULATOR_DB);
			try {
				txn.start();
				storagePool = _mockStoragePoolDao.persist(storagePool);
				txn.commit();
			} catch (Exception ex) {
				txn.rollback();
				throw new CloudRuntimeException("Error when saving storagePool " + storagePool, ex);
			} finally {
				txn.close();
                txn = Transaction.open(Transaction.CLOUD_DB);
                txn.close();
			}
		}
		return new StoragePoolInfo(storagePool.getUuid(), host.getPrivateIpAddress(), storagePool.getMountPoint(),
				storagePool.getMountPoint(), storagePool.getPoolType(), storagePool.getCapacity(), 0);
	}

	@Override
	public StoragePoolInfo getLocalStorage(String hostGuid, Long storageSize) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		MockHost host = null; 
		try {
			txn.start();
			host = _mockHostDao.findByGuid(hostGuid);
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Unable to find host " + hostGuid, ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
		if (storageSize == null) {
			storageSize = DEFAULT_HOST_STORAGE_SIZE;
		}
		txn = Transaction.open(Transaction.SIMULATOR_DB);
		MockStoragePoolVO storagePool = null;
		try {
			txn.start();
			storagePool = _mockStoragePoolDao.findByHost(hostGuid);
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when finding storagePool " + storagePool, ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
		if (storagePool == null) {
			String uuid = UUID.randomUUID().toString();
			storagePool = new MockStoragePoolVO();
			storagePool.setUuid(uuid);
			storagePool.setMountPoint("/mnt/" + uuid + File.separator);
			storagePool.setCapacity(storageSize);
			storagePool.setHostGuid(hostGuid);
			storagePool.setStorageType(StoragePoolType.Filesystem);
			txn = Transaction.open(Transaction.SIMULATOR_DB);
			try {
				txn.start();
				storagePool = _mockStoragePoolDao.persist(storagePool);
				txn.commit();
			} catch (Exception ex) {
				txn.rollback();
				throw new CloudRuntimeException("Error when saving storagePool " + storagePool, ex);
			} finally {
				txn.close();
                txn = Transaction.open(Transaction.CLOUD_DB);
                txn.close();
			}
		}
		return new StoragePoolInfo(storagePool.getUuid(), host.getPrivateIpAddress(), storagePool.getMountPoint(),
				storagePool.getMountPoint(), storagePool.getPoolType(), storagePool.getCapacity(), 0);
	}

	@Override
	public CreatePrivateTemplateAnswer CreatePrivateTemplateFromSnapshot(CreatePrivateTemplateFromSnapshotCommand cmd) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		MockVolumeVO snapshot = null;
		MockSecStorageVO sec = null;
		try {
			txn.start();
			String snapshotUUId = cmd.getSnapshotUuid();
			snapshot = _mockVolumeDao.findByName(snapshotUUId);
			if (snapshot == null) {
				snapshotUUId = cmd.getSnapshotName();
				snapshot = _mockVolumeDao.findByName(snapshotUUId);
				if (snapshot == null) {
					return new CreatePrivateTemplateAnswer(cmd, false, "can't find snapshot:" + snapshotUUId);
				}
			}

			sec = _mockSecStorageDao.findByUrl(cmd.getSecondaryStorageUrl());
			if (sec == null) {
				return new CreatePrivateTemplateAnswer(cmd, false, "can't find secondary storage");
			}
			txn.commit();
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		MockVolumeVO template = new MockVolumeVO();
		String uuid = UUID.randomUUID().toString();
		template.setName(uuid);
		template.setPath(sec.getMountPoint() + uuid);
		template.setPoolId(sec.getId());
		template.setSize(snapshot.getSize());
		template.setStatus(Status.DOWNLOADED);
		template.setType(MockVolumeType.TEMPLATE);
		txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			template = _mockVolumeDao.persist(template);
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when saving template " + template, ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		return new CreatePrivateTemplateAnswer(cmd, true, "", template.getName(), template.getSize(),
				template.getSize(), template.getName(), ImageFormat.QCOW2);
	}

	@Override
	public Answer ComputeChecksum(ComputeChecksumCommand cmd) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			MockVolumeVO volume = _mockVolumeDao.findByName(cmd.getTemplatePath());
			if (volume == null) {
				return new Answer(cmd, false, "cant' find volume:" + cmd.getTemplatePath());
			}
			String md5 = null;
			try {
				MessageDigest md = MessageDigest.getInstance("md5");
				md5 = String.format("%032x", new BigInteger(1, md.digest(cmd.getTemplatePath().getBytes())));
			} catch (NoSuchAlgorithmException e) {
				s_logger.debug("failed to gernerate md5:" + e.toString());
			}
			txn.commit();
			return new Answer(cmd, true, md5);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}
	}

	@Override
	public CreatePrivateTemplateAnswer CreatePrivateTemplateFromVolume(CreatePrivateTemplateFromVolumeCommand cmd) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		MockVolumeVO volume = null;
		MockSecStorageVO sec = null;
		try {
			txn.start();
			volume = _mockVolumeDao.findByStoragePathAndType(cmd.getVolumePath());
			if (volume == null) {
				return new CreatePrivateTemplateAnswer(cmd, false, "cant' find volume" + cmd.getVolumePath());
			}

			sec = _mockSecStorageDao.findByUrl(cmd.getSecondaryStorageUrl());
			if (sec == null) {
				return new CreatePrivateTemplateAnswer(cmd, false, "can't find secondary storage");
			}
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Error when creating private template from volume");
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		MockVolumeVO template = new MockVolumeVO();
		String uuid = UUID.randomUUID().toString();
		template.setName(uuid);
		template.setPath(sec.getMountPoint() + uuid);
		template.setPoolId(sec.getId());
		template.setSize(volume.getSize());
		template.setStatus(Status.DOWNLOADED);
		template.setType(MockVolumeType.TEMPLATE);
		txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			template = _mockVolumeDao.persist(template);
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Encountered " + ex.getMessage() + " when persisting template "
					+ template.getName(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		return new CreatePrivateTemplateAnswer(cmd, true, "", template.getName(), template.getSize(),
				template.getSize(), template.getName(), ImageFormat.QCOW2);
	}

	@Override
	public CopyVolumeAnswer CopyVolume(CopyVolumeCommand cmd) {
		Transaction txn = Transaction.open(Transaction.SIMULATOR_DB);
		boolean toSecondaryStorage = cmd.toSecondaryStorage();
		MockSecStorageVO sec = null;
		MockStoragePoolVO primaryStorage = null;
		try {
			txn.start();
			sec = _mockSecStorageDao.findByUrl(cmd.getSecondaryStorageURL());
			if (sec == null) {
				return new CopyVolumeAnswer(cmd, false, "can't find secondary storage", null, null);
			}
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Encountered " + ex.getMessage() + " when accessing secondary at "
					+ cmd.getSecondaryStorageURL(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			primaryStorage = _mockStoragePoolDao.findByUuid(cmd.getPool().getUuid());
			if (primaryStorage == null) {
				return new CopyVolumeAnswer(cmd, false, "Can't find primary storage", null, null);
			}
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Encountered " + ex.getMessage() + " when accessing primary at "
					+ cmd.getPool(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		MockVolumeVO volume = null;
		txn = Transaction.open(Transaction.SIMULATOR_DB);
		try {
			txn.start();
			volume = _mockVolumeDao.findByStoragePathAndType(cmd.getVolumePath());
			if (volume == null) {
				return new CopyVolumeAnswer(cmd, false, "cant' find volume" + cmd.getVolumePath(), null, null);
			}
			txn.commit();
		} catch (Exception ex) {
			txn.rollback();
			throw new CloudRuntimeException("Encountered " + ex.getMessage() + " when accessing volume at "
					+ cmd.getVolumePath(), ex);
		} finally {
			txn.close();
            txn = Transaction.open(Transaction.CLOUD_DB);
            txn.close();
		}

		String name = UUID.randomUUID().toString();
		if (toSecondaryStorage) {
			MockVolumeVO vol = new MockVolumeVO();
			vol.setName(name);
			vol.setPath(sec.getMountPoint() + name);
			vol.setPoolId(sec.getId());
			vol.setSize(volume.getSize());
			vol.setStatus(Status.DOWNLOADED);
			vol.setType(MockVolumeType.VOLUME);
			txn = Transaction.open(Transaction.SIMULATOR_DB);
			try {
				txn.start();
				vol = _mockVolumeDao.persist(vol);
				txn.commit();
			} catch (Exception ex) {
				txn.rollback();
				throw new CloudRuntimeException("Encountered " + ex.getMessage() + " when persisting volume "
						+ vol.getName(), ex);
			} finally {
				txn.close();
                txn = Transaction.open(Transaction.CLOUD_DB);
                txn.close();
			}
			return new CopyVolumeAnswer(cmd, true, null, sec.getMountPoint(), vol.getPath());
		} else {
			MockVolumeVO vol = new MockVolumeVO();
			vol.setName(name);
			vol.setPath(primaryStorage.getMountPoint() + name);
			vol.setPoolId(primaryStorage.getId());
			vol.setSize(volume.getSize());
			vol.setStatus(Status.DOWNLOADED);
			vol.setType(MockVolumeType.VOLUME);
			txn = Transaction.open(Transaction.SIMULATOR_DB);
			try {
				txn.start();
				vol = _mockVolumeDao.persist(vol);
				txn.commit();
			} catch (Exception ex) {
				txn.rollback();
				throw new CloudRuntimeException("Encountered " + ex.getMessage() + " when persisting volume "
						+ vol.getName(), ex);
			} finally {
				txn.close();
                txn = Transaction.open(Transaction.CLOUD_DB);
                txn.close();
			}
			return new CopyVolumeAnswer(cmd, true, null, primaryStorage.getMountPoint(), vol.getPath());
		}
	}
}
