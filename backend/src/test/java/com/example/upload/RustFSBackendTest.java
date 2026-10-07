package com.example.upload;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * S3 兼容性横向验证：RustFS 容器（MinIO 的 Apache-2.0 替代品）。
 * 与 MinIO 跑同一套协议用例与 S3 专属用例，验证本项目用到的 S3 接口子集在其兼容层的可用性：
 * CreateMultipartUpload / UploadPart / ListParts / ListMultipartUploads / CompleteMultipartUpload /
 * AbortMultipartUpload / HeadObject / GetObject(Range) / DeleteObject / HeadBucket / CreateBucket。
 */
public class RustFSBackendTest extends AbstractS3ContainerBackendTest {

    private static final String ROOT_USER = "rustfsadmin";
    private static final String ROOT_PASSWORD = "rustfsadmin-resumable";
    private static final String BUCKET = "resumable-upload-it";

    @SuppressWarnings("resource")
    static final GenericContainer<?> RUSTFS = new GenericContainer<>(DockerImageName.parse("rustfs/rustfs:latest"))
            .withEnv("RUSTFS_ACCESS_KEY", ROOT_USER)
            .withEnv("RUSTFS_SECRET_KEY", ROOT_PASSWORD)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200));

    static {
        RUSTFS.start();
    }

    @DynamicPropertySource
    static void s3Props(DynamicPropertyRegistry registry) {
        registerS3Props(registry, RUSTFS.getHost(), RUSTFS.getMappedPort(9000),
                ROOT_USER, ROOT_PASSWORD, BUCKET);
    }

    @Override
    protected String containerTag() {
        return "rustfs";
    }
}
