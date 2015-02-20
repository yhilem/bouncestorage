/*
 * Copyright (c) Bounce Storage, Inc. All rights reserved.
 * For more information, please see COPYRIGHT in the top-level directory.
 */

package com.bouncestorage.bounce;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;

import com.google.common.io.ByteSource;

import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.options.GetOptions;
import org.jclouds.io.ContentMetadata;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class BounceTest {
    private BlobStoreContext bounceContext;
    private BlobStore nearBlobStore;
    private BlobStore farBlobStore;
    private BounceBlobStore bounceBlobStore;
    private String containerName;

    @Before
    public void setUp() throws Exception {
        containerName = UtilsTest.createRandomContainerName();

        bounceContext = UtilsTest.createTransientBounceBlobStore();
        bounceBlobStore = (BounceBlobStore) bounceContext.getBlobStore();
        nearBlobStore = bounceBlobStore.getNearStore();
        farBlobStore = bounceBlobStore.getFarStore();
        bounceBlobStore.createContainerInLocation(null, containerName);
    }

    @After
    public void tearDown() throws Exception {
        if (bounceBlobStore != null) {
            bounceBlobStore.deleteContainer(containerName);
        }
        if (bounceContext != null) {
            bounceContext.close();
        }
    }

    @Test
    public void testCreateBucket() throws Exception {
        assertThat(nearBlobStore.containerExists(containerName)).isTrue();
        assertThat(farBlobStore.containerExists(containerName)).isTrue();
    }

    @Test
    public void testCreateLink() throws Exception {
        String blobName = "blob";
        ByteSource byteSource = ByteSource.wrap(new byte[1]);
        Blob blob = UtilsTest.makeBlob(nearBlobStore, blobName, byteSource);
        nearBlobStore.putBlob(containerName, blob);

        assertThat(BounceLink.isLink(nearBlobStore.blobMetadata(
                containerName, blobName))).isFalse();

        bounceBlobStore.copyBlobAndCreateBounceLink(containerName, blobName);

        assertThat(BounceLink.isLink(nearBlobStore.blobMetadata(
                containerName, blobName))).isTrue();
    }

    @Test
    public void testBounceNonexistentBlob() throws Exception {
        String blobName = UtilsTest.createRandomBlobName();
        bounceBlobStore.copyBlobAndCreateBounceLink(containerName, blobName);
        assertThat(nearBlobStore.blobExists(containerName, blobName)).isFalse();
        assertThat(farBlobStore.blobExists(containerName, blobName)).isFalse();
    }

    @Test
    public void test404Meta() throws Exception {
        String blobName = UtilsTest.createRandomBlobName();
        BlobMetadata meta = bounceBlobStore.blobMetadata(containerName, blobName);
        assertThat(meta).isNull();
    }

    @Test
    public void testBounceBlob() throws Exception {
        String blobName = "blob";
        ByteSource byteSource = ByteSource.wrap(new byte[1]);
        Blob blob = UtilsTest.makeBlob(bounceBlobStore, blobName, byteSource);
        ContentMetadata metadata = blob.getMetadata().getContentMetadata();
        bounceBlobStore.putBlob(containerName, blob);
        BlobMetadata meta1 = bounceBlobStore.blobMetadata(containerName, blobName);

        bounceBlobStore.copyBlobAndCreateBounceLink(containerName, blobName);
        BlobMetadata meta2 = bounceBlobStore.blobMetadata(containerName, blobName);
        assertThat((Object) meta2).isEqualToComparingFieldByField(meta1);

        Blob blob2 = bounceBlobStore.getBlob(containerName, blobName);
        assertEqualBlobs(blob, blob2);
    }

    @Test
    public void testTakeOverFarStore() throws Exception {
        String blobName = "blob";
        Blob blob = UtilsTest.makeBlob(farBlobStore, blobName);
        farBlobStore.putBlob(containerName, blob);
        assertThat(nearBlobStore.blobExists(containerName, blobName)).isFalse();
        assertThat(bounceBlobStore.sanityCheck(containerName)).isFalse();

        bounceBlobStore.takeOver(containerName);
        assertThat(nearBlobStore.blobExists(containerName, blobName)).isTrue();
        assertThat(BounceLink.isLink(nearBlobStore.blobMetadata(
                containerName, blobName))).isTrue();
        assertThat(bounceBlobStore.sanityCheck(containerName)).isTrue();
    }

    @Test
    public void testUnbounce() throws Exception {
        String blobName = "blob";
        Blob blob = UtilsTest.makeBlob(farBlobStore, blobName);
        nearBlobStore.putBlob(containerName, blob);
        bounceBlobStore.copyBlobAndCreateBounceLink(containerName, blobName);

        assertThat(nearBlobStore.blobExists(containerName, blobName)).isTrue();
        assertThat(farBlobStore.blobExists(containerName, blobName)).isTrue();
        Blob nearBlob = nearBlobStore.getBlob(containerName, blobName);
        assertThat(BounceLink.isLink(nearBlob.getMetadata())).isTrue();

        bounceBlobStore.getBlob(containerName, blobName, GetOptions.NONE);
        assertThat(nearBlobStore.blobExists(containerName, blobName)).isTrue();
        assertThat(farBlobStore.blobExists(containerName, blobName)).isTrue();
        nearBlob = nearBlobStore.getBlob(containerName, blobName);
        assertThat(BounceLink.isLink(nearBlob.getMetadata())).isFalse();
        Blob farBlob = farBlobStore.getBlob(containerName, blobName);
        assertEqualBlobs(nearBlob, blob);
        assertEqualBlobs(farBlob, blob);
    }

    private void assertEqualBlobs(Blob one, Blob two) throws Exception {
        try (InputStream is = one.getPayload().openStream();
             InputStream is2 = two.getPayload().openStream()) {
            assertThat(is2).hasContentEqualTo(is);
        }
        // TODO: assert more metadata, including user metadata
        ContentMetadata metadata = one.getMetadata().getContentMetadata();
        ContentMetadata metadata2 = two.getMetadata().getContentMetadata();
        assertThat(metadata2.getContentMD5AsHashCode()).isEqualTo(
                metadata.getContentMD5AsHashCode());
        assertThat(metadata2.getContentType()).isEqualTo(
                metadata.getContentType());
    }
}
