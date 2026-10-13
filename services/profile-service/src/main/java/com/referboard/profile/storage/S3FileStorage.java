package com.referboard.profile.storage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** Any S3-compatible store: AWS S3, MinIO, Cloudflare R2. Objects are private and encrypted at rest (SSE-S3). */
public class S3FileStorage implements FileStorage {

    private final S3Client s3;
    private final String bucket;
    private final boolean serverSideEncryption;

    public S3FileStorage(S3Client s3, String bucket, boolean serverSideEncryption) {
        this.s3 = s3;
        this.bucket = bucket;
        this.serverSideEncryption = serverSideEncryption;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType);
        if (serverSideEncryption) {
            request.serverSideEncryption(ServerSideEncryption.AES256);
        }
        s3.putObject(request.build(), RequestBody.fromBytes(content));
    }

    @Override
    public byte[] get(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
