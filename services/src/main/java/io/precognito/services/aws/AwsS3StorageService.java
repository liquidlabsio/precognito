package io.precognito.services.aws;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.HttpMethod;
import com.amazonaws.SdkClientException;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import io.precognito.services.query.FileMeta;
import io.precognito.services.storage.Storage;
import io.precognito.util.DateUtil;
import io.precognito.util.UriUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class AwsS3StorageService implements Storage {

    private static final long LIMIT = (long) (FileUtils.ONE_MB * 4.5);
    public static final int MAX_KEYS = 100000;
    public static final int S3_REQ_THREADS = 100;
    public static final int CONNECTION_POOL_SIZE = 200;

    private final Logger log = LoggerFactory.getLogger(AwsS3StorageService.class);

    @ConfigProperty(name = "precognito.prefix", defaultValue = "precognito-")
    private String PREFIX;


    public AwsS3StorageService() {
        log.info("Created");
    }

    /**
     * Doesnt actually removed external entities, but lists them instead
     * @param region
     * @param tenant
     * @param storageId
     * @param includeFileMask
     * @return
     */
    @Override
    public List<FileMeta> removeByStorageId(String region, String tenant, String storageId, String includeFileMask) {
        return importFromStorage(region, tenant, storageId, includeFileMask, "");
    }

    /**
     * TODO: should be using callback to prevent OOM
     *
     * @param region
     * @param tenant
     * @param storageId
     * @param includeFileMask
     * @param tags
     * @return
     */
    @Override
    public List<FileMeta> importFromStorage(String region, String tenant, String storageId, String includeFileMask, String tags) {

        String filePrefix = "";

        log.info("Importing from:{} mask:{}", storageId, includeFileMask);
        String bucketName = storageId;
        AmazonS3 s3Client = getAmazonS3Client(region);

        ListObjectsV2Request req = new ListObjectsV2Request().withBucketName(bucketName);
        ListObjectsV2Result objectListing = s3Client.listObjectsV2(bucketName, filePrefix);
        ArrayList<FileMeta> results = new ArrayList<>();
        addSummaries(tenant, includeFileMask, tags, bucketName, objectListing, results);
        while (objectListing.isTruncated() && results.size() < 200000) {
            objectListing = s3Client.listObjectsV2(req);
            addSummaries(tenant, includeFileMask, tags, bucketName, objectListing, results);
            req.setContinuationToken(objectListing.getContinuationToken());
        }
        log.info("Import finished, total:{}", results.size());

        return results;
    }

    private void addSummaries(String tenant, String includeFileMask, String tags, String bucketName, ListObjectsV2Result objectListing, ArrayList<FileMeta> results) {
        results.addAll(objectListing.getObjectSummaries().stream().filter(item -> item.getKey().contains(includeFileMask) || includeFileMask.equals("*")).map(objSummary ->
        {
            FileMeta fileMeta = new FileMeta(tenant,
                    objSummary.getBucketName(),
                    objSummary.getETag(),
                    objSummary.getKey(),
                    new byte[0],
                    inferFakeStartTimeFromSize(objSummary.getSize(), objSummary.getLastModified().getTime()),
                    objSummary.getLastModified().getTime());
            fileMeta.setSize(objSummary.getSize());
            fileMeta.setStorageUrl(String.format("s3://%s/%s", bucketName, objSummary.getKey()));
            fileMeta.setTags(tags + " " + getExtensions(objSummary.getKey()));
            return fileMeta;
        })
                .collect(Collectors.toList()));
        log.info("Import progress:{}", results.size());
    }

    private long inferFakeStartTimeFromSize(long size, long lastModified) {
        if (size < 4096) return lastModified - DateUtil.HOUR;
        int fudgeLineLength = 256;
        int fudgeLineCount = (int) (size / fudgeLineLength);
        long fudgedTimeIntervalPerLineMs = 1000;
        long startTimeOffset = fudgedTimeIntervalPerLineMs * fudgeLineCount;
        if (startTimeOffset < DateUtil.HOUR) startTimeOffset = DateUtil.HOUR;
        return lastModified - startTimeOffset;
    }

    private String getExtensions(String filename) {
        if (filename.contains(".")) return Arrays.toString(filename.split("."));
        return "";
    }


    @Override
    public FileMeta upload(final String region, final FileMeta upload) {
        bind();

        String bucketName = getBucketName(upload.getTenant());
        String filePath = upload.resource + "/" + upload.filename;

        log.info("uploading:" + upload + " bucket:" + bucketName);

        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.addUserMetadata("tags", upload.getTags());
        objectMetadata.addUserMetadata("tenant", upload.tenant);
        objectMetadata.addUserMetadata("length", "" + upload.fileContent.length);

        upload.setStorageUrl(writeToS3(region, upload.fileContent, bucketName, filePath, objectMetadata));

        return upload;
    }

    private String writeToS3(String region, byte[] fileContent, String bucketName, String filePath, ObjectMetadata objectMetadata) {
        File file = createTempFile(fileContent);
        return writeFileToS3(region, file, bucketName, filePath, objectMetadata);
    }

    private String writeFileToS3(String region, File file, String bucketName, String filePath, ObjectMetadata objectMetadata) {

        log.debug("Write:{} {} length:{}", bucketName, filePath, file.length());
        if (file.length() == 0) {
            log.warn("Attempted to write empty file to S3:{}", filePath);
            return "empty-file";
        }
        long contentLength = file.length();
        long partSize = 5 * 1024 * 1024; // Set part size to 5 MB.

        /**
         * Cannot write to S3 with '/' header
         */
        if (filePath.startsWith("/")) {
            filePath = filePath.substring(1);
        }

        try {
            AmazonS3 s3Client = getAmazonS3Client(region);


            if (!s3Client.doesBucketExistV2(bucketName)) {
                log.info("Bucket:{} doesnt exist, creating", bucketName);
                s3Client.createBucket(bucketName);
            }

            // Create a list of ETag objects. You retrieve ETags for each object part uploaded,
            // then, after each individual part has been uploaded, pass the list of ETags to
            // the request to complete the upload.
            List<PartETag> partETags = new ArrayList<>();

            // Initiate the multipart upload.
            InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(bucketName, filePath, objectMetadata);
            InitiateMultipartUploadResult initResponse = s3Client.initiateMultipartUpload(initRequest);


            // Upload the file parts.
            long filePosition = 0;
            for (int i = 1; filePosition < file.length(); i++) {
                // Because the last part could be less than 5 MB, adjust the part size as needed.
                partSize = Math.min(partSize, (contentLength - filePosition));

                // Create the request to upload a part.
                UploadPartRequest uploadRequest = new UploadPartRequest()
                        .withBucketName(bucketName)
                        .withKey(filePath)
                        .withUploadId(initResponse.getUploadId())
                        .withPartNumber(i)
                        .withFileOffset(filePosition)
                        .withFile(file)
                        .withPartSize(partSize);


                log.debug("UploadPart:{} {} {}", filePath, i, filePosition);

                // Upload the part and add the response's ETag to our list.
                UploadPartResult uploadResult = s3Client.uploadPart(uploadRequest);
                partETags.add(uploadResult.getPartETag());

                filePosition += partSize;
            }

            log.debug("Complete - ETags:{} File.length:{}", partETags, file.length());
            // Complete the multipart upload.
            CompleteMultipartUploadRequest compRequest = new CompleteMultipartUploadRequest(bucketName, filePath,
                    initResponse.getUploadId(), partETags);
            CompleteMultipartUploadResult completeMultipartUploadResult = s3Client.completeMultipartUpload(compRequest);

//            upload.storageUrl = completeMultipartUploadResult.getLocation();


        } catch (AmazonServiceException e) {
            // The call was transmitted successfully, but Amazon S3 couldn't process
            // it, so it returned an error response.
            log.error("AmazonServiceException S3 Upload failed to process:{}", filePath, e);
        } catch (SdkClientException e) {
            // Amazon S3 couldn't be contacted for a response, or the client
            // couldn't parse the response from Amazon S3.
            log.error("SdkClientException S3 not responding:{}", filePath, e);
        } finally {
            file.delete();
        }
        return String.format("s3://%s/%s", bucketName, filePath);
    }

    public String getBucketName(String tenant) {
        return (PREFIX + "-" + tenant).toLowerCase();
    }

    private static AmazonS3 getAmazonS3Client(String region) {
        ClientConfiguration clientConfiguration = new ClientConfiguration();
        clientConfiguration.setMaxConnections(CONNECTION_POOL_SIZE);
        return AmazonS3ClientBuilder.standard()
                .withRegion(region)
                .withClientConfiguration(clientConfiguration)
                .build();
    }

    synchronized private void bind() {
        if (PREFIX == null) {
            PREFIX = ConfigProvider.getConfig().getValue("precognito.prefix", String.class);
        }
    }

    /**
     * TODO: Seek to offset/compression-handling[on-off]/direct-download etd
     * @param region
     * @param storageUrl
     * @return
     */
    @Override
    public byte[] get(String region, String storageUrl) {
        bind();
        try {
            String[] hostnameAndPath = UriUtil.getHostnameAndPath(storageUrl);
            String bucket = hostnameAndPath[0];
            String filename = hostnameAndPath[1];

            AmazonS3 s3Client = getAmazonS3Client(region);
            S3Object s3object = s3Client.getObject(bucket, filename);
            S3ObjectInputStream inputStream = s3object.getObjectContent();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            IOUtils.copyLarge(inputStream, baos, 0, LIMIT);
            return baos.toByteArray();

        } catch (Exception e) {
            log.error("Failed to retrieve {}", storageUrl, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public InputStream getInputStream(String region, String tenant, String storageUrl) {
        bind();
        try {
            String[] hostnameAndPath = UriUtil.getHostnameAndPath(storageUrl);
            String bucket = hostnameAndPath[0];
            String filename = hostnameAndPath[1];

            AmazonS3 s3Client = getAmazonS3Client(region);
            S3Object s3object = s3Client.getObject(bucket, filename);
            return copyToLocalTempFs(s3object.getObjectContent());

        } catch (Exception e) {
            log.error("Failed to retrieve {}", storageUrl, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Map<String, InputStream> getInputStreams(String region, String tenant, List<String> urls) {
        return urls.stream().collect(Collectors.toMap(item -> item, item -> getInputStream(region, tenant, item)));
    }

    @Override
    public Map<String, InputStream> getInputStreams(String region, String tenant, String filePathPrefix, String filenameExtension, long fromTime) {
        String bucketName = getBucketName(tenant);
        AmazonS3 s3Client = getAmazonS3Client(region);

        long start = System.currentTimeMillis();

        ListObjectsV2Request req = new ListObjectsV2Request().withBucketName(bucketName).withPrefix(filePathPrefix).withMaxKeys(MAX_KEYS);

        ListObjectsV2Result objectListing = s3Client.listObjectsV2(req);
        Map<String, InputStream> results = new HashMap<>();
        results.putAll(getInputStreamsFromS3(s3Client, filenameExtension, objectListing, fromTime));
        while (objectListing.isTruncated()) {
            objectListing = s3Client.listObjectsV2(req);
            results.putAll(getInputStreamsFromS3(s3Client, filenameExtension, objectListing, fromTime));
            req.setContinuationToken(objectListing.getNextContinuationToken());
        }
        log.info("getInputStreams Elapsed:{}", (System.currentTimeMillis() - start));
        return results;
    }

    private Map<String, InputStream> getInputStreamsFromS3(final AmazonS3 s3Client, String filenameExtension, final ListObjectsV2Result objectListing, final long fromTime) {

        Map<String, InputStream> results = new ConcurrentHashMap<>();
        ExecutorService executorService = Executors.newFixedThreadPool(S3_REQ_THREADS);

        objectListing.getObjectSummaries().stream()
                .filter(objSummary -> objSummary.getKey().endsWith(filenameExtension) && objSummary.getLastModified().getTime() > fromTime)
                .forEach(
                        objSummary -> executorService.submit(() -> {
                            results.put(objSummary.getKey(), copyToLocalTempFs(s3Client.getObject(objSummary.getBucketName(), objSummary.getKey())
                                    .getObjectContent()));
                        })
                );

        executorService.shutdown();
        try {
            executorService.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        log.info("getInputStreamsFromS3 Count:" + results.size());

        return results;
    }

    /**
     * AWS Recommend grabbing the content as quickly as possible
     *
     * @param objectContent
     * @return
     */
    private InputStream copyToLocalTempFs(S3ObjectInputStream objectContent) {
        try {
            File temp = File.createTempFile("precog", "s3");

            FileOutputStream fos = new FileOutputStream(temp);
            IOUtils.copyLarge(objectContent, fos);
            fos.flush();
            fos.close();
            objectContent.close();

            return new BufferedInputStream(new FileInputStream(temp)) {
                @Override
                public void close() {
                    temp.delete();
                }
            };
        } catch (Exception e) {
            log.error(e.toString(), e);
        }
        throw new RuntimeException("Failed to localise S3 data");
    }

    @Override
    public OutputStream getOutputStream(String region, String tenant, String filenameURL) {
        try {
            File toS3 = File.createTempFile("S3OutStream", "tmp");
            return new FileOutputStream(toS3) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                        if (toS3.length() > 0) {
                            ObjectMetadata objectMetadata = new ObjectMetadata();
                            objectMetadata.addUserMetadata("tenant", tenant);
                            objectMetadata.addUserMetadata("length", "" + toS3.length());
                            writeFileToS3(region, toS3, getBucketName(tenant), UriUtil.getHostnameAndPath(filenameURL)[1], objectMetadata);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        toS3.delete();
                    }
                }
            };
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getSignedDownloadURL(String region, String storageUrl) {
        bind();

        try {
            String[] hostnameAndPath = UriUtil.getHostnameAndPath(storageUrl);
            String bucket = hostnameAndPath[0];
            String filename = hostnameAndPath[1];

            AmazonS3 s3Client = getAmazonS3Client(region);

            // Set the pre-signed URL to expire after one hour.
            java.util.Date expiration = new java.util.Date();
            long expTimeMillis = expiration.getTime();
            expTimeMillis += 1000 * 60 * 60;
            expiration.setTime(expTimeMillis);

            // Generate the pre-signed URL.
            GeneratePresignedUrlRequest generatePresignedUrlRequest =
                    new GeneratePresignedUrlRequest(bucket, filename)
                            .withMethod(HttpMethod.GET)
                            .withExpiration(expiration);
            return s3Client.generatePresignedUrl(generatePresignedUrlRequest).toString();

        } catch (Exception e) {
            log.error("Failed to retrieve {}", storageUrl, e);
            throw new RuntimeException(e);
        }
    }

    private File createTempFile(byte[] filecontent) {
        try {
            File tempFile = File.createTempFile("test", ".tmp");
            FileOutputStream fos = new FileOutputStream(tempFile);
            fos.write(filecontent);
            fos.flush();
            fos.close();
            return tempFile;
        } catch (IOException e) {
            log.error("Failed to created temp file:{}", e);
            e.printStackTrace();
        }
        throw new RuntimeException("Failed to create temp file");
    }


    public static void main(String[] args) throws IOException {
        Regions clientRegion = Regions.DEFAULT_REGION;
        String bucketName = "*** Bucket name ***";
        String keyName = "*** Key name ***";
        String filePath = "*** Path to file to upload ***";

        File file = new File(filePath);
        long contentLength = file.length();
        long partSize = 5 * 1024 * 1024; // Set part size to 5 MB.

        try {
            AmazonS3 s3Client = getAmazonS3Client(clientRegion.getName());

            // Create a list of ETag objects. You retrieve ETags for each object part uploaded,
            // then, after each individual part has been uploaded, pass the list of ETags to
            // the request to complete the upload.
            List<PartETag> partETags = new ArrayList<PartETag>();

            // Initiate the multipart upload.
            InitiateMultipartUploadRequest initRequest = new InitiateMultipartUploadRequest(bucketName, keyName);
            InitiateMultipartUploadResult initResponse = s3Client.initiateMultipartUpload(initRequest);

            // Upload the file parts.
            long filePosition = 0;
            for (int i = 1; filePosition < contentLength; i++) {
                // Because the last part could be less than 5 MB, adjust the part size as needed.
                partSize = Math.min(partSize, (contentLength - filePosition));

                // Create the request to upload a part.
                UploadPartRequest uploadRequest = new UploadPartRequest()
                        .withBucketName(bucketName)
                        .withKey(keyName)
                        .withUploadId(initResponse.getUploadId())
                        .withPartNumber(i)
                        .withFileOffset(filePosition)
                        .withFile(file)
                        .withPartSize(partSize);

                // Upload the part and add the response's ETag to our list.
                UploadPartResult uploadResult = s3Client.uploadPart(uploadRequest);
                partETags.add(uploadResult.getPartETag());

                filePosition += partSize;
            }

            // Complete the multipart upload.
            CompleteMultipartUploadRequest compRequest = new CompleteMultipartUploadRequest(bucketName, keyName,
                    initResponse.getUploadId(), partETags);
            s3Client.completeMultipartUpload(compRequest);
        } catch (AmazonServiceException e) {
            // The call was transmitted successfully, but Amazon S3 couldn't process
            // it, so it returned an error response.
            e.printStackTrace();
        } catch (SdkClientException e) {
            // Amazon S3 couldn't be contacted for a response, or the client
            // couldn't parse the response from Amazon S3.
            e.printStackTrace();
        }
    }


}