/*
 * Copyright (c) Bounce Storage, Inc. All rights reserved.
 * For more information, please see COPYRIGHT in the top-level directory.
 */

package com.bouncestorage.bounce.admin;

import javax.ws.rs.HttpMethod;

import com.bouncestorage.bounce.ForwardingBlobStore;

import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.options.GetOptions;
import org.jclouds.blobstore.options.PutOptions;

public final class LoggingBlobStore extends ForwardingBlobStore {

    private BounceApplication app;

    public LoggingBlobStore(BlobStore blobStore, BounceApplication app) {
        super(blobStore);
        this.app = app;
    }

    @Override
    public Blob getBlob(String containerName, String blobName, GetOptions options) {
        Blob blob = delegate().getBlob(containerName, blobName, options);
        if (blob != null) {
            app.getBounceStats().logOperation(HttpMethod.GET, containerName, blobName, blob.getMetadata().getSize());
        }
        return blob;
    }

    @Override
    public String putBlob(String containerName, Blob blob, PutOptions options) {
        String result = delegate().putBlob(containerName, blob, options);
        app.getBounceStats().logOperation(HttpMethod.PUT, containerName, blob.getMetadata().getName(),
                blob.getMetadata().getContentMetadata().getContentLength());
        return result;
    }
}
