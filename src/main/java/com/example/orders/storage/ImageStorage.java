package com.example.orders.storage;

import com.example.orders.exception.StorageUnavailableException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Product photos in MinIO, or any S3-compatible store. The bucket is created by an operator, not
 * by the app: the app's key may only read and write objects in it (see README).
 */
@Component
public class ImageStorage {

    private static final Logger log = LoggerFactory.getLogger(ImageStorage.class);

    private final MinioClient client;
    private final String bucket;
    private final boolean configured;

    public ImageStorage(@Value("${orders.storage.endpoint}") String endpoint,
                        @Value("${orders.storage.bucket}") String bucket,
                        @Value("${orders.storage.access-key:}") String accessKey,
                        @Value("${orders.storage.secret-key:}") String secretKey) {
        this.bucket = bucket;
        this.configured = !accessKey.isBlank() && !secretKey.isBlank();
        this.client = configured
                ? MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build()
                : null;
        if (!configured) {
            log.warn("[PHOTOS] No storage key configured (orders.storage.access-key): photo upload is off; the shop shows placeholders");
        }
    }

    /** A stored photo, streamed to the caller; close it when done. */
    public record StoredImage(InputStream stream, long size, String contentType) {}

    public void put(String key, byte[] bytes, String contentType) {
        try {
            client().putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .data(bytes, bytes.length).contentType(contentType).build());
        } catch (Exception e) {
            throw unavailable("store", e);
        }
    }

    /** @return the photo, or null when the store does not have it */
    public StoredImage get(String key) {
        try {
            GetObjectResponse response = client().getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
            String length = response.headers().get("Content-Length");
            return new StoredImage(response, length == null ? -1 : Long.parseLong(length),
                    response.headers().get("Content-Type"));
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return null;
            }
            throw unavailable("read", e);
        } catch (StorageUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable("read", e);
        }
    }

    /** Removing a photo that is already gone is not an error. */
    public void remove(String key) {
        try {
            client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw unavailable("remove", e);
        }
    }

    /** For the health check and the README's set-up check. */
    public boolean bucketReachable() {
        try {
            return configured && client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        } catch (Exception e) {
            return false;
        }
    }

    private MinioClient client() {
        if (!configured) {
            throw new StorageUnavailableException("Photo storage is not set up on this server", null);
        }
        return client;
    }

    private StorageUnavailableException unavailable(String action, Exception e) {
        log.warn("[PHOTOS] Could not {} a photo in bucket {}: {}", action, bucket, e.getMessage());
        return new StorageUnavailableException("Photo storage is not available right now. Please try again later.", e);
    }
}
