/*
 * Copyright (c) Microsoft Corporation.
 *  All rights reserved.
 *
 */

package microsoft.dagx.transfer.nifi.processors;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3Object;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static microsoft.dagx.transfer.nifi.processors.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CI", matches = "true")
public class PutS3ObjectTest extends AbstractS3Test {
    private AWSCredentials credentials;

    @BeforeEach
    void setup() {
        credentials = getCredentials();
    }

    @Test
    void upload() throws IOException {
        String key = "test-file.txt";
        final TestRunner runner = TestRunners.newTestRunner(new PutS3Object());

        runner.setProperty(Properties.REGION, REGION);
        runner.setProperty(Properties.BUCKET, BUCKET_NAME);
        runner.setProperty(Properties.OBJECT_KEYS, key);
        runner.setProperty(Properties.ACCESS_KEY_ID, credentials.getAWSAccessKeyId());
        runner.setProperty(Properties.SECRET_ACCESS_KEY, credentials.getAWSSecretKey());


        final Map<String, String> attrs = new HashMap<>();
        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);
        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);
        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);

        runner.run(3);

        runner.assertAllFlowFilesTransferred(Properties.REL_SUCCESS, 3);
    }

    @Test
    void upload_whenAlreadyExists() throws IOException {
        String key = "test-file.txt";
        PutObjectResult response = putTestFile(key, getFileFromResourceName(SAMPLE_FILE_RESOURCE_NAME));

        final TestRunner runner = TestRunners.newTestRunner(new PutS3Object());

        runner.setProperty(Properties.REGION, REGION);
        runner.setProperty(Properties.BUCKET, BUCKET_NAME);
        runner.setProperty(Properties.OBJECT_KEYS, key);
        runner.setProperty(Properties.ACCESS_KEY_ID, credentials.getAWSAccessKeyId());
        runner.setProperty(Properties.SECRET_ACCESS_KEY, credentials.getAWSSecretKey());


        final Map<String, String> attrs = new HashMap<>();
        String newContent = "this is another content!";
        runner.enqueue(newContent, attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(Properties.REL_SUCCESS, 1);

        S3Object newS3Object = fetchTestFile(BUCKET_NAME, key);
        assertThat(new String(newS3Object.getObjectContent().readAllBytes())).isEqualTo(newContent);
        assertThat(newS3Object.getObjectMetadata().getETag()).isNotEqualTo(response.getETag());
    }


}