/*
 * Copyright (c) Bounce Storage, Inc. All rights reserved.
 * For more information, please see COPYRIGHT in the top-level directory.
 */

package com.bouncestorage.bounce.admin;

import com.bouncestorage.bounce.BounceStorageMetadata;
import com.bouncestorage.bounce.IForwardingBlobStore;

import org.apache.commons.configuration.Configuration;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.CreateContainerOptions;
import org.jclouds.domain.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BouncePolicy implements IForwardingBlobStore {
    public enum BounceResult {
        NO_OP,
        COPY,
        MOVE,
        REMOVE,
        LINK,
    }

    protected Logger logger = LoggerFactory.getLogger(getClass());

    private BlobStore sourceBlobStore;
    private BlobStore destinationBlobStore;

    public void init(BounceService service, Configuration config) {
    }

    public final void setBlobStores(BlobStore source, BlobStore destination) {
        sourceBlobStore = source;
        destinationBlobStore = destination;
    }

    public final BlobStore getSource() {
        return sourceBlobStore;
    }

    public final BlobStore getDestination() {
        return destinationBlobStore;
    }

    @Override
    public boolean createContainerInLocation(Location location, String container, CreateContainerOptions options) {
        return getDestination().createContainerInLocation(location, container, options) |
                getSource().createContainerInLocation(location, container, options);
    }

    @Override
    public BlobStore delegate() {
        return getSource();
    }

    public abstract BounceResult reconcileObject(String container, BounceStorageMetadata sourceObject, StorageMetadata
            destinationObject);
}
