/*
 * Copyright (c) Bounce Storage, Inc. All rights reserved.
 * For more information, please see COPYRIGHT in the top-level directory.
 */

package com.bouncestorage.bounce.admin.policy;

import static java.util.Objects.requireNonNull;

import static com.bouncestorage.bounce.Utils.eTagsEqual;
import static com.google.common.base.Throwables.propagate;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.ServiceUnavailableException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import com.bouncestorage.bounce.BounceLink;
import com.bouncestorage.bounce.BounceStorageMetadata;
import com.bouncestorage.bounce.SystemMetadataSerializer;
import com.bouncestorage.bounce.Utils;
import com.bouncestorage.bounce.admin.BounceApplication;
import com.bouncestorage.bounce.admin.BouncePolicy;
import com.bouncestorage.bounce.utils.ReconcileLocker;
import com.google.auto.service.AutoService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.PeekingIterator;
import com.google.common.io.ByteSource;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.input.TeeInputStream;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.KeyNotFoundException;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.domain.MutableBlobMetadata;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.internal.BlobImpl;
import org.jclouds.blobstore.domain.internal.MutableBlobMetadataImpl;
import org.jclouds.blobstore.domain.internal.PageSetImpl;
import org.jclouds.blobstore.options.CopyOptions;
import org.jclouds.blobstore.options.GetOptions;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.blobstore.options.PutOptions;
import org.jclouds.http.HttpResponseException;
import org.jclouds.io.MutableContentMetadata;
import org.jclouds.io.Payload;
import org.jclouds.util.Strings2;

@AutoService(BouncePolicy.class)
public class WriteBackPolicy extends BouncePolicy {
    public static final String COPY_DELAY = "copyDelay";
    public static final String EVICT_DELAY = "evictDelay";
    public static final String LOG_MARKER_SUFFIX = "     bounce!log";
    @VisibleForTesting
    public static final String INTERNAL_PREFIX = ".bounce internal reserved prefix/";
    @VisibleForTesting
    public static final String TAKEOVER_MARKER = INTERNAL_PREFIX + "need_take_over";
    private static final Predicate<String> SWIFT_SEGMENT_PATTERN =
            Pattern.compile(".*/slo/\\d{10}\\.\\d{6}/\\d+/\\d+/\\d{8}$").asPredicate();
    private static final Iterable<Character> skipPathEncoding = Lists.charactersOf("/:;=");
    private static final String LOG_MARKER_SUFFIX_ESCAPED = Strings2.urlEncode(LOG_MARKER_SUFFIX, skipPathEncoding);
    private static final ListContainerOptions LIST_CONTAINER_RECURSIVE = new ListContainerOptions().recursive();
    protected Duration copyDelay;
    protected Duration evictDelay;
    private ReconcileLocker reconcileLocker = new ReconcileLocker();

    public static boolean isMarkerBlob(String name) {
        return name.endsWith(LOG_MARKER_SUFFIX);
    }

    public static String markerBlobGetName(String marker) {
        return marker.substring(0, marker.length() - LOG_MARKER_SUFFIX.length());
    }

    @VisibleForTesting
    String blobGetMarkerName(String blob) {
        if ("transient".equals(getContext().unwrap().getId())) {
            return blob + LOG_MARKER_SUFFIX;
        } else {
            return Strings2.urlEncode(blob, skipPathEncoding) + LOG_MARKER_SUFFIX_ESCAPED;
        }
    }

    private void putMarkerBlob(String containerName, String key) {
        getSource().putBlob(containerName,
                getSource().blobBuilder(blobGetMarkerName(key))
                        .payload(ByteSource.empty())
                        .contentLength(0)
                        .build());
    }

    private void removeMarkerBlob(String containerName, String key) {
        logger.debug("deleting marker blob for {}", key);
        getSource().removeBlob(containerName, blobGetMarkerName(key));
    }

    private boolean deleteContainerOrLogContent(BlobStore blobStore, String container) {
        if (!blobStore.deleteContainerIfEmpty(container)) {
            logger.info("container {} not empty", container);
            if (logger.isDebugEnabled()) {
                blobStore.list(container, LIST_CONTAINER_RECURSIVE).forEach(s -> logger.debug("{}", s.getName()));
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean deleteContainerIfEmpty(String container) {
        try {
            Utils.waitUntil(app::hasNoPendingReconcileTasks);
        } catch (TimeoutException e) {
            // ignore
        } catch (Exception e) {
            throw propagate(e);
        }

        if (deleteContainerOrLogContent(getSource(), container)) {
            if (takeOverInProcess) {
                return deleteContainerOrLogContent(getDestination(), container);
            } else {
                getDestination().deleteContainer(container);
                return true;
            }
        } else {
            return false;
        }
    }

    @Override
    public String putBlob(String containerName, Blob blob, PutOptions options) {
        String blobName = blob.getMetadata().getName();
        if (blobName.startsWith(INTERNAL_PREFIX)) {
            throw new UnsupportedOperationException("illegal prefix");
        }
        if (BounceLink.isLink(blob.getMetadata())) {
            throw new IllegalArgumentException(blobName + " is a link");
        }

        try (ReconcileLocker.LockKey ignored = reconcileLocker.lockObject(containerName, blobName, false)) {
            putMarkerBlob(containerName, blobName);
            String etag = getSource().putBlob(containerName, blob, options);
            enqueueReconcile(containerName, blobName);
            return etag;
        }
    }

    @Override
    public void removeBlob(String container, String name) {
        if (name.startsWith(INTERNAL_PREFIX)) {
            throw new UnsupportedOperationException("illegal prefix");
        }
        if (name.endsWith(LOG_MARKER_SUFFIX) || name.endsWith(LOG_MARKER_SUFFIX_ESCAPED)) {
            throw new UnsupportedOperationException("illegal suffix: " + name);
        }
        super.removeBlob(container, name);
        removeMarkerBlob(container, name);
        enqueueReconcile(container, name);
    }

    private void enqueueReconcile(String containerName, String blobName) {
        if (app != null) {
            if (isCopy()) {
                app.executeBackgroundReconcileTask(() -> reconcileObject(containerName, blobName),
                        copyDelay.getSeconds(), TimeUnit.SECONDS);
            }
            if (isEvict() && !copyDelay.equals(evictDelay)) {
                app.executeBackgroundReconcileTask(() -> reconcileObject(containerName, blobName),
                        evictDelay.getSeconds(), TimeUnit.SECONDS);
            }
        }
    }

    @Override
    public void init(BounceApplication app, Configuration config) {
        super.init(app, config);
        if (config.getString(COPY_DELAY) != null) {
            this.copyDelay = requireNonNull(Duration.parse(config.getString(COPY_DELAY)));
        }
        if (config.getString(EVICT_DELAY) != null) {
            this.evictDelay = requireNonNull(Duration.parse(config.getString(EVICT_DELAY)));
        }
    }

    private String replaceMetadata(BlobStore blobStore, String container, String blobName,
                                   CopyOptions options) {
        return blobStore.copyBlob(container, blobName, container, blobName, options);
    }

    @Override
    public String copyBlob(String fromContainer, String fromName, String toContainer, String toName, CopyOptions options) {
        if (!fromContainer.equals(toContainer)) {
            // TODO we don't support cross container copy for now, since that may involve
            // different policies
            throw new UnsupportedOperationException("cross container copy");
        }

        BlobMetadata sourceMeta = getSource().blobMetadata(fromContainer, fromName);
        if (sourceMeta == null) {
            // nothing to copy
            return null;
        }

        if (fromName.equals(toName) && options.getUserMetadata().isPresent()) {
            // we are only updating the user metadata
            if (BounceLink.isLink(sourceMeta)) {
                String etag = replaceMetadata(getDestination(), fromContainer, fromName, options);
                BlobMetadata meta = getDestination().blobMetadata(toContainer, toName);
                Utils.createBounceLink(getSource(), meta);
                return etag == null ? meta.getETag() : etag;
            } else {
                try {
                    replaceMetadata(getDestination(), fromContainer, fromName, options);
                } catch (KeyNotFoundException e) {
                    // if it's not on the remote side yet, it's ok
                }
                String etag = replaceMetadata(getSource(), fromContainer, fromName, options);
                return etag == null ? getSource().blobMetadata(fromContainer, fromName).getETag() : etag;
            }
        }

        String etag;
        if (BounceLink.isLink(sourceMeta)) {
            // we know that the far store has a valid object
            etag = getDestination().copyBlob(fromContainer, fromName, toContainer, toName, options);
            if (etag != null) {
                Utils.createBounceLink(getSource(), getDestination().blobMetadata(toContainer, toName));
                removeMarkerBlob(fromContainer, toName);
            }
        } else {
            putMarkerBlob(toContainer, toName);
            etag = getSource().copyBlob(fromContainer, fromName, toContainer, toName, options);
            if (!etag.equals(sourceMeta.getETag())) {
                // another process just updated the source blob, we could have copied a link
                // ideally copyBlob would return the copied metadata so that we don't have
                // to do another call
                sourceMeta = getSource().blobMetadata(toContainer, toName);
                if (sourceMeta != null && BounceLink.isLink(sourceMeta) && etag.equals(sourceMeta.getETag())) {
                    getSource().removeBlob(toContainer, toName);
                    throw new ClientErrorException(Response.Status.CONFLICT);
                } else {
                    // we copied an old object, this is not ideal but ok for now
                }
            }

            enqueueReconcile(toContainer, toName);
        }

        return etag;
    }

    protected BounceResult reconcileObject(String container, String blob)
            throws InterruptedException, ExecutionException {
        BlobMetadata sourceMeta = getSource().blobMetadata(container, blob);
        BlobMetadata sourceMarkerMeta = getSource().blobMetadata(container, blobGetMarkerName(blob));
        BlobMetadata destMeta = getDestination().blobMetadata(container, blob);

        BounceStorageMetadata meta;
        if (sourceMeta != null) {
            if (destMeta != null) {
                if (sourceMarkerMeta != null) {
                    if (BounceLink.isLink(sourceMeta)) {
                        meta = new BounceStorageMetadata(destMeta, BounceStorageMetadata.FAR_ONLY);
                    } else {
                        meta = new BounceStorageMetadata(sourceMeta, BounceStorageMetadata.NEAR_ONLY);
                    }
                } else {
                    meta = new BounceStorageMetadata(sourceMeta, BounceStorageMetadata.EVERYWHERE);
                }
            } else {
                meta = new BounceStorageMetadata(sourceMeta, BounceStorageMetadata.NEAR_ONLY);
            }

            return reconcileObject(container, meta, destMeta);
        } else {
            if (sourceMarkerMeta != null) {
                removeMarkerBlob(container, blob);
            }
            return reconcileObject(container, null, destMeta);
        }
    }

    @Override
    public BounceResult reconcileObject(String container, BounceStorageMetadata sourceObject, StorageMetadata
            destinationObject) {
        String blobName = sourceObject == null ? destinationObject.getName() : sourceObject.getName();
        try (ReconcileLocker.LockKey ignored = reconcileLocker.lockObject(container, blobName, true)) {
            if (sourceObject != null) {
                logger.debug("reconciling {} {} {}", sourceObject.getName(),
                        destinationObject == null ? "null" : destinationObject.getName(), sourceObject.getRegions());
                try {
                    if (isEvict() && isObjectExpired(sourceObject, evictDelay)) {
                        return maybeMoveObject(container, sourceObject, destinationObject);
                    } else if (isCopy() && (isImmediateCopy() || isObjectExpired(sourceObject, copyDelay))) {
                        return maybeCopyObject(container, sourceObject, destinationObject);
                    }
                } catch (IOException e) {
                    throw propagate(e);
                }

                return BounceResult.NO_OP;
            } else {
                logger.debug("reconciling null {}", destinationObject.getName());
                getDestination().removeBlob(container, destinationObject.getName());
                return BounceResult.REMOVE;
            }
        } catch (ServiceUnavailableException e) {
            return BounceResult.NO_OP;
        }
    }

    @Override
    public void takeOver(String containerName) {
        takeOverInProcess = true;
        ForkJoinPool fjp = new ForkJoinPool(100);
        takeOverFuture = fjp.submit(() -> {
            StreamSupport.stream(Utils.crawlBlobStore(getDestination(), containerName).spliterator(), true)
                    .filter(sm -> !getSource().blobExists(containerName, sm.getName()))
                    .forEach(sm -> {
                        logger.debug("taking over blob {}", sm.getName());
                        BlobMetadata metadata = getDestination().blobMetadata(containerName,
                                sm.getName());
                        BounceLink link = new BounceLink(Optional.of(metadata));
                        getSource().putBlob(containerName, link.toBlob(getSource()));
                    });
            getSource().removeBlob(containerName, TAKEOVER_MARKER);
            takeOverInProcess = false;
        });
        fjp.shutdown();
    }

    @Override
    public boolean sanityCheck(String containerName) {
        if (getSource().blobExists(containerName, TAKEOVER_MARKER)) {
            return false;
        }

        PageSet<? extends StorageMetadata> res = getDestination().list(containerName);

        ForkJoinPool fjp = new ForkJoinPool(100);
        try {
            boolean sane = !fjp.submit(() -> {
                return res.stream().parallel().map(sm -> {
                    BlobMetadata meta = blobMetadata(containerName, sm.getName());
                    return !Utils.equalsOtherThanTime(sm, meta);
                }).anyMatch(Boolean::booleanValue);
            }).get();

            if (!sane) {
                Blob b = getSource().blobBuilder(TAKEOVER_MARKER)
                        .payload(ByteSource.empty())
                        .build();
                getSource().putBlob(containerName, b);
            }
            return sane;
        } catch (InterruptedException | ExecutionException e) {
            throw propagate(e);
        } finally {
            fjp.shutdown();
        }
    }

    protected boolean isObjectExpired(StorageMetadata metadata, Duration duration) {
        Instant now = app.getClock().instant();
        Instant then = metadata.getLastModified().toInstant();
        logger.debug("now {} mtime {}", now, then);
        return !now.plusSeconds(1).minus(duration).isBefore(then);
    }

    private Blob pipeBlobAndReturn(String container, Blob blob) throws IOException {
        String name = blob.getMetadata().getName();
        logger.debug("piping {} from {} to {}", name, getDestStoreName(), getSourceStoreName());

        PipedInputStream pipeIn = new PipedInputStream();
        PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);

        Payload blobPayload = blob.getPayload();
        MutableContentMetadata contentMetadata = blob.getMetadata().getContentMetadata();
        Blob retBlob = new BlobImpl(replaceSystemMetadata(blob.getMetadata()));
        retBlob.setPayload(pipeIn);
        retBlob.setAllHeaders(blob.getAllHeaders());
        TeeInputStream tee = new TeeInputStream(blobPayload.openStream(), pipeOut, true);
        retBlob.getMetadata().setContentMetadata(contentMetadata);

        app.executeBackgroundTask(() -> {
            try {
                logger.debug("copying {} to tee stream", name);
                return Utils.copyBlob(getDestination(), getSource(), container, blob, tee);
            } catch (RuntimeException e) {
                logger.error("copying " + name + " to tee stream failed", e);
                throw e;
            } finally {
                tee.close();
            }
        });
        return retBlob;
    }

    private String getSourceStoreName() {
        return getSource().getContext().unwrap().getId();
    }

    private String getDestStoreName() {
        return getDestination().getContext().unwrap().getId();
    }

    private GetOptions maybeConditionalGet(String container, String blobName, GetOptions options) {
        if (options.getIfMatch() != null || options.getIfNoneMatch() != null ||
                options.getIfModifiedSince() != null || options.getIfUnmodifiedSince() != null) {

            BlobMetadata meta = blobMetadata(container, blobName);
            HttpResponseException ex = null;

            if (options.getIfMatch() != null && !eTagsEqual(options.getIfMatch(), meta.getETag())) {
                throw new ClientErrorException(Response.Status.PRECONDITION_FAILED);
            }

            if (options.getIfNoneMatch() != null && eTagsEqual(options.getIfNoneMatch(), meta.getETag())) {
                throw new WebApplicationException(Response.Status.NOT_MODIFIED);
            }

            if (options.getIfModifiedSince() != null &&
                    meta.getLastModified().compareTo(options.getIfModifiedSince()) <= 0) {
                throw new WebApplicationException(Response.Status.NOT_MODIFIED);
            }

            if (options.getIfUnmodifiedSince() != null &&
                    meta.getLastModified().compareTo(options.getIfUnmodifiedSince()) > 0) {
                throw new ClientErrorException(Response.Status.PRECONDITION_FAILED);
            }

            return new GetOptions() {
                @Override
                public List<String> getRanges() {
                    return options.getRanges();
                }
            };
        }

        return options;
    }

    @Override
    public Blob getBlob(String container, String blobName, GetOptions options) {
        if (blobName.startsWith(INTERNAL_PREFIX)) {
            throw new UnsupportedOperationException("illegal prefix");
        }

        options = maybeConditionalGet(container, blobName, options);

        Blob blob = null;
        boolean isLink = false;
        try {
            blob = super.getBlob(container, blobName, options);
            if (blob == null) {
                if (takeOverInProcess) {
                    return getDestination().getBlob(container, blobName, options);
                }
                return null;
            }
            isLink = BounceLink.isLink(blob.getMetadata());
        } catch (HttpResponseException e) {
            if (e.getResponse() != null) {
                int status = e.getResponse().getStatusCode();
                if (status == Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE.getStatusCode() &&
                        e.getResponse().getHeaders().containsKey("X-Object-Meta-Bounce-Link")) {
                    isLink = true;
                }
            }
            if (!isLink) {
                throw e;
            }
        }

        if (isLink) {
            logger.debug("following link {}", blobName);
            try {
                if (app != null && options.equals(GetOptions.NONE)) {
                    blob = getDestination().getBlob(container, blobName, GetOptions.NONE);
                    return pipeBlobAndReturn(container, blob);
                } else {
                    // fallback to the dumb thing and do double streaming
                    logger.debug("unbouncing {} from {} to {}", blobName, getDestStoreName(), getSourceStoreName());
                    Utils.copyBlob(getDestination(), getSource(), container, container, blobName);
                    logger.debug("returning unbounced blob {}", blobName);
                    blob = getSource().getBlob(container, blobName, options);
                }
            } catch (IOException e) {
                e.printStackTrace();
                blob = getDestination().getBlob(container, blobName, options);
            }
        }

        return replaceSystemMetadata(blob);
    }

    private Blob replaceSystemMetadata(Blob blob) {
        MutableContentMetadata contentMetadata = blob.getMetadata().getContentMetadata();
        Blob newBlob = new BlobImpl(replaceSystemMetadata(blob.getMetadata()));
        newBlob.setPayload(blob.getPayload());
        newBlob.setAllHeaders(blob.getAllHeaders());
        newBlob.getMetadata().setContentMetadata(contentMetadata);
        return newBlob;
    }

    private MutableBlobMetadata replaceSystemMetadata(MutableBlobMetadata blobMetadata) {
        Map<String, String> userMetadata = blobMetadata.getUserMetadata();
        SystemMetadataSerializer.SYSTEM_METADATA.stream()
                .filter(t -> userMetadata.containsKey(t.getName()))
                .forEach(t -> {
                    t.deserialize(blobMetadata, userMetadata.get(t.getName()));
                });
        Map<String, String> filtered = userMetadata.entrySet().stream()
                .filter(e -> !e.getKey().startsWith(SystemMetadataSerializer.METADATA_PREFIX))
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        blobMetadata.setUserMetadata(filtered);
        return blobMetadata;
    }

    private BlobMetadata replaceSystemMetadata(BlobMetadata blobMetadata) {
        MutableBlobMetadata mutable = new MutableBlobMetadataImpl(blobMetadata);
        return replaceSystemMetadata(mutable);
    }

    @Override
    public BlobMetadata blobMetadata(String container, String blobName) {
        if (blobName.startsWith(INTERNAL_PREFIX)) {
            throw new UnsupportedOperationException("illegal prefix");
        }

        BlobMetadata meta = getSource().blobMetadata(container, blobName);
        if (meta != null) {
            if (BounceLink.isLink(meta)) {
                Blob linkBlob = getSource().getBlob(container, blobName);
                if (linkBlob != null) {
                    if (BounceLink.isLink(linkBlob.getMetadata())) {
                        try {
                            return BounceLink.fromBlob(linkBlob).getBlobMetadata();
                        } catch (IOException e) {
                            throw propagate(e);
                        }
                    } else {
                        return replaceSystemMetadata(linkBlob.getMetadata());
                    }
                } else {
                    return null;
                }
            }

            return replaceSystemMetadata(meta);
        } else {
            return null;
        }
    }

    protected final BounceResult maybeMoveObject(String container, BounceStorageMetadata sourceObject,
            StorageMetadata destinationObject) throws IOException {
        if (sourceObject.getRegions().equals(BounceStorageMetadata.FAR_ONLY)) {
            return BounceResult.NO_OP;
        }
        if (sourceObject.getRegions().containsAll(BounceStorageMetadata.EVERYWHERE)) {
            BlobMetadata sourceMetadata = getSource().blobMetadata(container, sourceObject.getName());
            BlobMetadata destinationMetadata = getDestination().blobMetadata(container, destinationObject.getName());

            if (sourceMetadata != null && destinationMetadata != null) {
                Utils.createBounceLink(getSource(), sourceMetadata);
                removeMarkerBlob(container, sourceObject.getName());
                return BounceResult.LINK;
            }
        }

        logger.debug("moving {}", sourceObject.getName());
        Utils.copyBlobAndCreateBounceLink(getSource(), getDestination(), container,
                sourceObject.getName());
        removeMarkerBlob(container, sourceObject.getName());
        return BounceResult.MOVE;
    }

    public static boolean isSwiftSegmentBlob(String name) {
        return SWIFT_SEGMENT_PATTERN.test(name);
    }

    @Override
    public final PageSet<? extends StorageMetadata> list(String s, ListContainerOptions listContainerOptions) {
        if (takeOverInProcess) {
            return getDestination().list(s, listContainerOptions);
        }
        PeekingIterator<StorageMetadata> nearPage = Iterators.peekingIterator(
                Utils.crawlBlobStore(getSource(), s, listContainerOptions).iterator());
        PeekingIterator<StorageMetadata> farPage = Iterators.peekingIterator(
                Utils.crawlBlobStore(getDestination(), s, listContainerOptions).iterator());
        TreeMap<String, BounceStorageMetadata> contents = new TreeMap<>();
        int maxResults = listContainerOptions.getMaxResults() == null ?
                1000 : listContainerOptions.getMaxResults();

        while (nearPage.hasNext() && contents.size() < maxResults) {
            StorageMetadata nearMeta = nearPage.next();
            String name = nearMeta.getName();

            if (name.startsWith(INTERNAL_PREFIX) || isSwiftSegmentBlob(name)) {
                continue;
            }

            logger.debug("found near blob: {}", name);
            if (WriteBackPolicy.isMarkerBlob(name)) {
                BounceStorageMetadata meta = contents.get(WriteBackPolicy.markerBlobGetName(name));
                if (meta != null) {
                    meta.hasMarkerBlob(true);
                }
                logger.debug("skipping marker blob: {}", name);
                continue;
            }

            int compare = -1;
            StorageMetadata farMeta = null;
            while (farPage.hasNext()) {
                farMeta = farPage.peek();
                compare = name.compareTo(farMeta.getName());
                if (compare <= 0) {
                    break;
                } else {
                    farPage.next();
                    logger.debug("skipping far blob: {}", farMeta.getName());
                }
            }

            if (farMeta != null && isSwiftSegmentBlob(farMeta.getName())) {
                continue;
            }

            if (compare == 0) {
                farPage.next();
                logger.debug("found far blob with the same name: {}", name);
                boolean nextIsMarker = false;
                if (nearPage.hasNext()) {
                    StorageMetadata next = nearPage.peek();
                    logger.debug("next blob: {}", next.getName());
                    if (next.getName().equals(name + LOG_MARKER_SUFFIX)) {
                        nextIsMarker = true;
                    }
                }

                BounceStorageMetadata meta;
                ImmutableSet<BounceStorageMetadata.Region> farRegions = translateRegions(farMeta);

                if (nextIsMarker) {
                    if (BounceLink.isLink(getSource().blobMetadata(s, name))) {
                        meta = new BounceStorageMetadata(farMeta, farRegions);
                        meta.setLinkSize(nearMeta.getSize());
                    } else {
                        meta = new BounceStorageMetadata(nearMeta, BounceStorageMetadata.NEAR_ONLY);
                    }

                    meta.hasMarkerBlob(true);
                    contents.put(name, meta);
                } else {
                    if (Objects.equals(nearMeta.getSize(), farMeta.getSize())) {
                        meta = new BounceStorageMetadata(nearMeta,
                                new ImmutableSet.Builder<BounceStorageMetadata.Region>()
                                        .add(BounceStorageMetadata.Region.NEAR)
                                        .addAll(farRegions)
                                        .build());
                        if (nearMeta.getLastModified() != null &&
                                nearMeta.getLastModified().compareTo(meta.getLastModified()) < 0) {
                            meta.setLastModified(nearMeta.getLastModified());
                        }
                    } else {
                        meta = new BounceStorageMetadata(farMeta, farRegions);
                        meta.setLinkSize(nearMeta.getSize());
                    }
                }

                contents.put(name, meta);
            } else {
                contents.put(name, new BounceStorageMetadata(nearMeta, BounceStorageMetadata.NEAR_ONLY));
            }
        }

        if (nearPage.hasNext()) {
            StorageMetadata nearMeta = nearPage.next();
            String name = nearMeta.getName();

            logger.debug("found near blob: {}", name);
            if (WriteBackPolicy.isMarkerBlob(name)) {
                BounceStorageMetadata meta = contents.get(WriteBackPolicy.markerBlobGetName(name));
                if (meta != null) {
                    meta.hasMarkerBlob(true);
                }
            }
        }

        return new PageSetImpl<>(contents.values(), contents.isEmpty() ? null : contents.lastKey());
    }

    private ImmutableSet<BounceStorageMetadata.Region> translateRegions(StorageMetadata farMetadata) {
        if (!(farMetadata instanceof BounceStorageMetadata)) {
            return BounceStorageMetadata.FAR_ONLY;
        }
        ImmutableSet<BounceStorageMetadata.Region> regions = ((BounceStorageMetadata) farMetadata).getRegions();
        if (regions.equals(BounceStorageMetadata.FAR_ONLY)) {
            return ImmutableSet.of(BounceStorageMetadata.Region.FARTHER);
        } else if (regions.equals(BounceStorageMetadata.NEAR_ONLY)) {
            return ImmutableSet.of(BounceStorageMetadata.Region.FAR);
        } else {
            return ImmutableSet.of(BounceStorageMetadata.Region.FAR, BounceStorageMetadata.Region.FARTHER);
        }
    }

    protected final BounceResult maybeCopyObject(String container, BounceStorageMetadata sourceObject,
            StorageMetadata destinationObject) throws IOException {
        if (sourceObject.getRegions().equals(BounceStorageMetadata.FAR_ONLY)) {
            return BounceResult.NO_OP;
        }
        if (sourceObject.getRegions().equals(BounceStorageMetadata.EVERYWHERE)) {
            return BounceResult.NO_OP;
        }

        logger.debug("copying {} to far store", sourceObject.getName());
        Utils.copyBlob(getSource(), getDestination(), container, container, sourceObject.getName());
        removeMarkerBlob(container, sourceObject.getName());
        return BounceResult.COPY;
    }

    private boolean isCopy() {
        if (copyDelay == null) {
            return false;
        }
        return !copyDelay.isNegative();
    }

    private boolean isEvict() {
        if (evictDelay == null) {
            return false;
        }
        return !evictDelay.isNegative();
    }

    private boolean isImmediateCopy() {
        if (copyDelay == null) {
            return false;
        }
        return copyDelay.isZero();
    }
}
