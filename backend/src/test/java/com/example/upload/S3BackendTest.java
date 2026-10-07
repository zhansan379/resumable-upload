package com.example.upload;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** S3 后端集成测试（真实 MinIO 容器）。 */
public class S3BackendTest extends AbstractS3ContainerBackendTest {

    private static final String ROOT_USER = "minioadmin";
    private static final String ROOT_PASSWORD = "minioadmin-resumable";
    private static final String BUCKET = "resumable-upload-it";

    @SuppressWarnings("resource")
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
            .withEnv("MINIO_ROOT_USER", ROOT_USER)
            .withEnv("MINIO_ROOT_PASSWORD", ROOT_PASSWORD)
            .withExposedPorts(9000)
            .withCommand("server", "/data")
            .waitingFor(Wait.forHttp("/minio/health/ready").forStatusCode(200));

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void s3Props(DynamicPropertyRegistry registry) {
        registerS3Props(registry, MINIO.getHost(), MINIO.getMappedPort(9000),
                ROOT_USER, ROOT_PASSWORD, BUCKET);
    }

    @Override
    protected String containerTag() {
        return "minio";
    }
}
