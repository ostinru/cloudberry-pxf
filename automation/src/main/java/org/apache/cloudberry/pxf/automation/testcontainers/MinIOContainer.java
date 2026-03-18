package org.apache.cloudberry.pxf.automation.testcontainers;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * TestContainers wrapper around the MinIO S3-compatible object storage.
 *
 * The container joins a shared Docker network with alias {@code minio},
 * so PXF inside the PXFCloudberry container can reach it at {@code http://minio:9000}.
 * Test code on the host reaches it via the mapped port.
 */
public class MinIOContainer extends GenericContainer<MinIOContainer> {

    private static final String IMAGE_NAME = "minio/minio:RELEASE.2023-09-04T19-57-37Z";
    public static final int API_PORT = 9000;
    public static final int CONSOLE_PORT = 9001;
    public static final String ROOT_USER = "admin";
    public static final String ROOT_PASSWORD = "password";
    public static final String DEFAULT_BUCKET = "gpdb-ud-scratch";
    public static final String NETWORK_ALIAS = "minio";

    private static MinIOContainer instance;

    private MinIOContainer(Network network) {
        super(DockerImageName.parse(IMAGE_NAME));
        super.withNetwork(network)
            .withNetworkAliases(NETWORK_ALIAS)
            .withExposedPorts(API_PORT, CONSOLE_PORT)
            .withEnv("MINIO_ROOT_USER", ROOT_USER)
            .withEnv("MINIO_ROOT_PASSWORD", ROOT_PASSWORD)
            .withEnv("MINIO_API_SELECT_PARQUET", "on")
            .withCommand("server", "/data", "--address", ":" + API_PORT, "--console-address", ":" + CONSOLE_PORT)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(API_PORT));
    }

    /**
     * S3 endpoint reachable from the host (uses the mapped port).
     */
    public String getHostEndpoint() {
        return "http://localhost:" + getMappedPort(API_PORT);
    }

    /**
     * S3 endpoint reachable from other containers on the shared Docker network.
     */
    public String getInternalEndpoint() {
        return "http://" + NETWORK_ALIAS + ":" + API_PORT;
    }

    public String getAccessKey() {
        return ROOT_USER;
    }

    public String getSecretKey() {
        return ROOT_PASSWORD;
    }

    /**
     * Creates a bucket using the AWS SDK (already on the classpath).
     */
    public void createBucket(String bucketName) {
        AmazonS3 s3 = AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(
                        new AwsClientBuilder.EndpointConfiguration(getHostEndpoint(), "us-east-1"))
                .withCredentials(
                        new AWSStaticCredentialsProvider(
                                new BasicAWSCredentials(ROOT_USER, ROOT_PASSWORD)))
                .withPathStyleAccessEnabled(true)
                .build();
        if (!s3.doesBucketExistV2(bucketName)) {
            s3.createBucket(bucketName);
        }
        s3.shutdown();
    }

    /**
     * Returns a singleton container, starting it on first access.
     *
     * @param network shared Docker network (must be the same network the PXF container uses)
     */
    public static synchronized MinIOContainer getInstance(Network network) {
        if (instance == null) {
            instance = new MinIOContainer(network);
            instance.start();
            instance.createBucket(DEFAULT_BUCKET);
            Runtime.getRuntime().addShutdownHook(new Thread(instance::stop));
            System.out.println("[MinIOContainer] Started at " + instance.getHostEndpoint()
                    + " (host) / " + instance.getInternalEndpoint()
                    + " (internal), bucket '" + DEFAULT_BUCKET + "' created");
        }
        return instance;
    }
}