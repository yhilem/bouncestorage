/*
 * Copyright (c) Bounce Storage, Inc. All rights reserved.
 * For more information, please see COPYRIGHT in the top-level directory.
 */

package com.bouncestorage.bounce.admin;

import java.util.Date;

import javax.ws.rs.HttpMethod;

import com.bouncestorage.bounce.ForwardingBlobStore;

import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.options.GetOptions;
import org.jclouds.blobstore.options.PutOptions;

public final class LoggingBlobStore extends ForwardingBlobStore {

    private BounceApplication app;
    private final int id;

    public LoggingBlobStore(BlobStore blobStore, String id, BounceApplication app) {
        super(blobStore);
        this.app = app;
        this.id = Integer.parseInt(id);
    }

    @Override
    public Blob getBlob(String containerName, String blobName) {
        return getBlob(containerName, blobName, GetOptions.NONE);
    }

    @Override
    public Blob getBlob(String containerName, String blobName, GetOptions options) {
        Date startTime = new Date();
        Blob blob = delegate().getBlob(containerName, blobName, options);

        if (blob != null) {
            app.getBounceStats().logOperation(HttpMethod.GET, getProviderId(), containerName, blobName,
                    blob.getMetadata().getSize(), startTime.getTime());
        }
        return blob;
    }

    @Override
    public String putBlob(String containerName, Blob blob) {
        return putBlob(containerName, blob, PutOptions.NONE);
    }

    @Override
    public String putBlob(String containerName, Blob blob, PutOptions options) {
        Date startTime = new Date();
        String result = delegate().putBlob(containerName, blob, options);
        app.getBounceStats().logOperation(HttpMethod.PUT, getProviderId(), containerName, blob.getMetadata().getName(),
                blob.getMetadata().getContentMetadata().getContentLength(), startTime.getTime());
        return result;
    }

    @Override
    public void removeBlob(String containerName, String blobName) {
        Date startTime = new Date();
        BlobMetadata meta = delegate().blobMetadata(containerName, blobName);
        if (meta != null) {
            delegate().removeBlob(containerName, blobName);
            app.getBounceStats().logOperation(HttpMethod.DELETE, getProviderId(), containerName, blobName,
                    meta.getSize(), startTime.getTime());
        }
    }

    public int getProviderId() {
        return id;
    }
}
