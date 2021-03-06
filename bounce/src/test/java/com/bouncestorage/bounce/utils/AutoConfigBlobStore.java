/*
 * Copyright (c) Bounce Storage, Inc. All rights reserved.
 * For more information, please see COPYRIGHT in the top-level directory.
 */

package com.bouncestorage.bounce.utils;

import static com.google.common.base.Throwables.propagate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.bouncestorage.bounce.BlobStoreTarget;
import com.bouncestorage.bounce.Utils;
import com.bouncestorage.bounce.UtilsTest;
import com.bouncestorage.bounce.admin.BounceApplication;
import com.bouncestorage.bounce.admin.BounceConfiguration;
import com.bouncestorage.bounce.admin.BouncePolicy;
import com.bouncestorage.bounce.admin.policy.LRUStoragePolicy;
import com.bouncestorage.bounce.admin.policy.StoragePolicy;
import com.bouncestorage.bounce.admin.policy.WriteBackPolicy;
import com.google.common.collect.ImmutableMap;

import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.ContainerNotFoundException;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;
import org.jclouds.blobstore.domain.BlobBuilder;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.ContainerAccess;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;
import org.jclouds.blobstore.domain.internal.PageSetImpl;
import org.jclouds.blobstore.domain.internal.StorageMetadataImpl;
import org.jclouds.blobstore.options.CopyOptions;
import org.jclouds.blobstore.options.CreateContainerOptions;
import org.jclouds.blobstore.options.GetOptions;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.blobstore.options.PutOptions;
import org.jclouds.domain.Location;
import org.jclouds.io.Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoConfigBlobStore implements BlobStore {
    private Map<String, BouncePolicy> policyMap;
    private BounceApplication app;

    private Logger logger = LoggerFactory.getLogger(getClass());
    private AtomicLong pendingBytes = new AtomicLong();
    private AtomicLong pendingObjects = new AtomicLong();
    private String lastPutObject;

    public AutoConfigBlobStore(BounceApplication app) {
        policyMap = new HashMap<>();
        this.app = app;
    }

    @Override
    public String putBlob(String container, Blob blob) {
        return putBlob(container, blob, PutOptions.NONE);
    }

    @Override
    public String putBlob(String containerName, Blob blob, PutOptions options) {
        BouncePolicy policy = getPolicyFromContainer(containerName);
        if (blob.getMetadata().getName().equals(lastPutObject)) {
            // drain bounce if this is an immediate overwrite, because
            // we could be bouncing this object
            drainBackgroundTasks();
        }
        Long length = blob.getMetadata().getContentMetadata().getContentLength();
        lastPutObject = blob.getMetadata().getName();
        String result = policy.putBlob(containerName, blob, options);
        if (result == null) {
            return null;
        }
        if (length != null) {
            pendingBytes.getAndAdd(length);
        }
        pendingObjects.getAndIncrement();
        return result;
    }

    @Override
    public Blob getBlob(String container, String blob) {
        return getBlob(container, blob, GetOptions.NONE);
    }

    @Override
    public Blob getBlob(String containerName, String blobName, GetOptions options) {
        drainBackgroundTasks();
        BouncePolicy policy = getPolicyFromContainer(containerName);
        return policy.getBlob(containerName, blobName, options);
    }

    @Override
    public BlobMetadata blobMetadata(String container, String name) {
        // swiftproxy issues a blobmetadata right after a PUT
        //drainBackgroundTasks();
        BouncePolicy policy = getPolicyFromContainer(container);
        return policy.blobMetadata(container, name);
    }

    @Override
    public void removeBlob(String container, String name) {
        drainBackgroundTasks();
        BouncePolicy policy = getPolicyFromContainer(container);
        policy.removeBlob(container, name);
        pendingObjects.getAndIncrement();
        drainBackgroundTasks();
    }

    @Override
    public void removeBlobs(String container, Iterable<String> iterable) {
        drainBackgroundTasks();
        BouncePolicy policy = getPolicyFromContainer(container);
        policy.removeBlobs(container, iterable);
        drainBackgroundTasks();
    }

    @Override
    public BlobAccess getBlobAccess(String container, String blob) {
        return getPolicyFromContainer(container).getBlobAccess(container, blob);
    }

    @Override
    public void setBlobAccess(String container, String blob, BlobAccess blobAccess) {
        getPolicyFromContainer(container).setBlobAccess(container, blob, blobAccess);
    }

    @Override
    public long countBlobs(String container) {
        return getPolicyFromContainer(container).countBlobs(container);
    }

    @Override
    public long countBlobs(String container, ListContainerOptions listContainerOptions) {
        return getPolicyFromContainer(container).countBlobs(container, listContainerOptions);
    }

    @Override
    public MultipartUpload initiateMultipartUpload(String container, BlobMetadata blobMetadata) {
        return getPolicyFromContainer(container).initiateMultipartUpload(container, blobMetadata);
    }

    @Override
    public void abortMultipartUpload(MultipartUpload multipartUpload) {
        getPolicyFromContainer(multipartUpload.containerName()).abortMultipartUpload(multipartUpload);
    }

    @Override
    public String completeMultipartUpload(MultipartUpload multipartUpload, List<MultipartPart> list) {
        return getPolicyFromContainer(multipartUpload.containerName()).completeMultipartUpload(multipartUpload, list);
    }

    @Override
    public MultipartPart uploadMultipartPart(MultipartUpload multipartUpload, int i, Payload payload) {
        return getPolicyFromContainer(multipartUpload.containerName()).uploadMultipartPart(multipartUpload, i, payload);
    }

    @Override
    public List<MultipartPart> listMultipartUpload(MultipartUpload multipartUpload) {
        return getPolicyFromContainer(multipartUpload.containerName()).listMultipartUpload(multipartUpload);
    }

    @Override
    public long getMinimumMultipartPartSize() {
        return app.getBlobStore(0).getMinimumMultipartPartSize();
    }

    @Override
    public long getMaximumMultipartPartSize() {
        return app.getBlobStore(0).getMaximumMultipartPartSize();
    }

    @Override
    public int getMaximumNumberOfParts() {
        return app.getBlobStore(0).getMaximumNumberOfParts();
    }

    @Override
    public PageSet<? extends StorageMetadata> list(String container) {
        return list(container, ListContainerOptions.NONE);
    }

    @Override
    public PageSet<? extends StorageMetadata> list() {
        List<StorageMetadata> results = policyMap.keySet().stream()
                .map(container -> new StorageMetadataImpl(StorageType.CONTAINER, null, container, null, null,
                                null, null, null, ImmutableMap.of(), null))
                .sorted()
                .collect(Collectors.toList());
        return new PageSetImpl<>(results, null);
    }

    @Override
    public PageSet<? extends StorageMetadata> list(String containerName, ListContainerOptions options) {
        drainBackgroundTasks();
        BouncePolicy policy = getPolicyFromContainer(containerName);
        return policy.list(containerName, options);
    }

    @Override
    public void clearContainer(String container) {
        drainBackgroundTasks();
        BouncePolicy policy = getPolicyFromContainer(container);
        policy.clearContainer(container);
    }

    @Override
    public boolean containerExists(String container) {
        return policyMap.containsKey(container);
    }

    @Override
    public boolean createContainerInLocation(Location location, String container) {
        return createContainerInLocation(location, container, CreateContainerOptions.NONE);
    }

    @Override
    public boolean createContainerInLocation(Location location, String container, CreateContainerOptions options) {
        WriteBackPolicy policy = new LRUStoragePolicy();
        policy.init(app, getNewConfiguration());
        BlobStore tier1 = app.getBlobStore(0);
        BlobStore tier2 = app.getBlobStore(1);
        String tier1Container = UtilsTest.createOrRequestContainer(tier1);
        String tier2Container = UtilsTest.createOrRequestContainer(tier2);
        BlobStoreTarget source = new BlobStoreTarget(tier1, tier1Container);
        BlobStoreTarget destination = new BlobStoreTarget(tier2, tier2Container);
        policy.setBlobStores(source, destination);
        policyMap.put(container, policy);
        return true;
    }

    @Override
    public ContainerAccess getContainerAccess(String container) {
        if (!policyMap.containsKey(container)) {
            throw new ContainerNotFoundException();
        }
        return policyMap.get(container).getContainerAccess(container);
    }

    @Override
    public void setContainerAccess(String container, ContainerAccess containerAccess) {
        if (!policyMap.containsKey(container)) {
            throw new ContainerNotFoundException();
        }
        policyMap.get(container).setContainerAccess(container, containerAccess);
    }

    @Override
    public void clearContainer(String container, ListContainerOptions options) {
        BouncePolicy policy = getPolicyFromContainer(container);
        policy.clearContainer(container, options);
    }

    @Override
    public void deleteContainer(String container) {
        drainBackgroundTasks();
        BouncePolicy policy = getPolicyFromContainer(container);
        policy.deleteContainer(container);
        policyMap.remove(container);
        drainBackgroundTasks();
    }

    @Override
    public boolean deleteContainerIfEmpty(String container) {
        drainBackgroundTasks();
        BouncePolicy policy = getPolicyFromContainer(container);
        boolean result = policy.deleteContainerIfEmpty(container);
        if (result) {
            policyMap.remove(container);
        }
        return result;
    }

    @Override
    public boolean directoryExists(String container, String directory) {
        drainBackgroundTasks();
        return getPolicyFromContainer(container).directoryExists(container, directory);
    }

    @Override
    public void createDirectory(String container, String directory) {
        drainBackgroundTasks();
        getPolicyFromContainer(container).createDirectory(container, directory);
    }

    @Override
    public void deleteDirectory(String container, String directory) {
        drainBackgroundTasks();
        getPolicyFromContainer(container).deleteDirectory(container, directory);
        drainBackgroundTasks();
    }

    @Override
    public boolean blobExists(String container, String blob) {
        drainBackgroundTasks();
        return getPolicyFromContainer(container).blobExists(container, blob);
    }

    @Override
    public BlobStoreContext getContext() {
        return app.getBlobStore(0).getContext();
    }

    @Override
    public BlobBuilder blobBuilder(String name) {
        return app.getBlobStore(0).blobBuilder(name);
    }

    @Override
    public Set<? extends Location> listAssignableLocations() {
        return app.getBlobStore(0).listAssignableLocations();
    }

    @Override
    public String copyBlob(String fromContainer, String fromName, String toContainer, String toName,
                           CopyOptions options) {
        drainBackgroundTasks();
        if (!fromContainer.equals(toContainer)) {
            throw new IllegalArgumentException("Copy only between the same containers is supported");
        }
        BouncePolicy policy = getPolicyFromContainer(fromContainer);
        String etag = policy.copyBlob(fromContainer, fromName, toContainer, toName, options);
        pendingObjects.getAndIncrement();
        drainBackgroundTasks();
        return etag;
    }

    public BouncePolicy getPolicyFromContainer(String containerName) {
        if (!policyMap.containsKey(containerName)) {
            throw new ContainerNotFoundException();
        }
        return policyMap.get(containerName);
    }

    private BounceConfiguration getNewConfiguration() {
        Properties properties = new Properties();
        if (System.getProperty("bounce." + WriteBackPolicy.COPY_DELAY) != null) {
            properties.setProperty(WriteBackPolicy.COPY_DELAY,
                    System.getProperty("bounce." + WriteBackPolicy.COPY_DELAY));
        } else {
            properties.setProperty(WriteBackPolicy.COPY_DELAY, "P0D");
        }
        if (System.getProperty("bounce." + WriteBackPolicy.EVICT_DELAY) != null) {
            properties.setProperty(WriteBackPolicy.EVICT_DELAY,
                    System.getProperty("bounce." + WriteBackPolicy.EVICT_DELAY));
        } else {
            properties.setProperty(WriteBackPolicy.EVICT_DELAY, "P-1D");
        }

        properties.setProperty(StoragePolicy.CAPACITY_SETTING,
                System.getProperty("bounce." + StoragePolicy.CAPACITY_SETTING, "10"));

        BounceConfiguration config = new BounceConfiguration();
        config.setAll(properties);
        return config;
    }

    private void drainBackgroundTasks() {
        try {
            long assumeBW = 100 * 1000 / 1000; // 100KB/s in ms
            long totalTime = pendingBytes.getAndSet(0) / assumeBW;
            // allow for 5 seocnds overhead / object
            totalTime += pendingObjects.getAndSet(0) * 5 * 1000;
            Utils.waitUntil(10, totalTime, () -> app.hasNoPendingReconcileTasks());

            policyMap.keySet().iterator()
                    .forEachRemaining(container -> app.getBounceService().bounce(container));
            app.getBounceService().status().forEach(s -> {
                try {
                    s.future().get();
                } catch (ExecutionException | InterruptedException e) {
                    throw propagate(e);
                }
            });

        } catch (TimeoutException e) {
            logger.error(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw propagate(e);
        }
    }
}
