/*
 *  Copyright (c) 2022 Microsoft Corporation
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Microsoft Corporation - Initial implementation
 *
 */

package org.eclipse.dataspaceconnector.azure.dataplane.azurestorage.pipeline;

import com.github.javafaker.Faker;
import org.eclipse.dataspaceconnector.azure.blob.core.AzureBlobStoreSchema;
import org.eclipse.dataspaceconnector.azure.blob.core.adapter.BlobAdapter;
import org.eclipse.dataspaceconnector.azure.blob.core.api.BlobStoreApi;
import org.eclipse.dataspaceconnector.dataplane.spi.pipeline.DataSource.Part;
import org.eclipse.dataspaceconnector.dataplane.spi.pipeline.InputStreamDataSource;
import org.eclipse.dataspaceconnector.spi.monitor.Monitor;
import org.eclipse.dataspaceconnector.spi.types.domain.transfer.DataFlowRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.dataspaceconnector.azure.blob.core.AzureStorageTestFixtures.createAccountName;
import static org.eclipse.dataspaceconnector.azure.blob.core.AzureStorageTestFixtures.createBlobName;
import static org.eclipse.dataspaceconnector.azure.blob.core.AzureStorageTestFixtures.createContainerName;
import static org.eclipse.dataspaceconnector.azure.blob.core.AzureStorageTestFixtures.createRequest;
import static org.eclipse.dataspaceconnector.azure.blob.core.AzureStorageTestFixtures.createSharedAccessSignature;
import static org.eclipse.dataspaceconnector.azure.dataplane.azurestorage.pipeline.TestFunctions.sharedAccessSignatureMatcher;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AzureStorageDataSinkTest {

    static Faker faker = new Faker();
    Monitor monitor = mock(Monitor.class);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    BlobStoreApi blobStoreApi = mock(BlobStoreApi.class);
    DataFlowRequest.Builder request = createRequest(AzureBlobStoreSchema.TYPE);

    String accountName = createAccountName();
    String containerName = createContainerName();
    String sharedAccessSignature = createSharedAccessSignature();

    String blobName = createBlobName();
    String content = faker.lorem().sentence();

    Exception exception = new TestCustomException(faker.lorem().sentence());

    AzureStorageDataSink dataSink = AzureStorageDataSink.Builder.newInstance()
            .accountName(accountName)
            .containerName(containerName)
            .sharedAccessSignature(sharedAccessSignature)
            .requestId(request.build().getId())
            .blobStoreApi(blobStoreApi)
            .executorService(executor)
            .monitor(monitor)
            .build();
    BlobAdapter destination = mock(BlobAdapter.class);
    BlobAdapter completionMarker = mock(BlobAdapter.class);
    InputStreamDataSource part = new InputStreamDataSource(blobName, new ByteArrayInputStream(content.getBytes(UTF_8)));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    OutputStream completionMarkerOutput = mock(OutputStream.class);

    @BeforeEach
    void setUp() {
        when(destination.getOutputStream()).thenReturn(output);
        when(blobStoreApi.getBlobAdapter(
                eq(accountName),
                eq(containerName),
                eq(blobName),
                sharedAccessSignatureMatcher(sharedAccessSignature)))
                .thenReturn(destination);

        when(completionMarker.getOutputStream()).thenReturn(completionMarkerOutput);
        when(blobStoreApi.getBlobAdapter(
                eq(accountName),
                eq(containerName),
                argThat(s -> s.endsWith(".complete")),
                sharedAccessSignatureMatcher(sharedAccessSignature)))
                .thenReturn(completionMarker);
    }

    @Test
    void transferParts_succeeds() {
        var result = dataSink.transferParts(List.of(part));
        assertThat(result.succeeded()).isTrue();
        assertThat(output.toString(UTF_8)).isEqualTo(content);
    }

    @Test
    void transferParts_whenBlobClientCreationFails_fails() {
        when(blobStoreApi.getBlobAdapter(
                eq(accountName),
                eq(containerName),
                eq(blobName),
                sharedAccessSignatureMatcher(sharedAccessSignature)))
                .thenThrow(exception);
        assertThatTransferPartsFails(part, "Error creating blob for %s on account %s", blobName, accountName);
    }

    @Test
    void transferParts_whenWriteFails_fails() {
        when(destination.getOutputStream()).thenThrow(exception);
    }

    @Test
    void transferParts_whenReadFails_fails() {
        when(destination.getOutputStream()).thenThrow(exception);
        Part part = mock(Part.class);
        when(part.openStream()).thenThrow(exception);
        when(part.name()).thenReturn(blobName);
        assertThatTransferPartsFails(part, "Error reading blob %s", blobName);
    }

    @Test
    void transferParts_whenTransferFails_fails() throws Exception {
        InputStream input = mock(InputStream.class);
        when(input.transferTo(output)).thenThrow(exception);
        Part part = mock(Part.class);
        when(part.openStream()).thenReturn(input);
        when(part.name()).thenReturn(blobName);
        assertThatTransferPartsFails(part, "Error transferring blob for %s on account %s", blobName, accountName);
    }

    @Test
    void complete() throws IOException {
        dataSink.complete();
        verify(blobStoreApi).getBlobAdapter(
                eq(accountName),
                eq(containerName),
                argThat(s -> s.endsWith(".complete")),
                sharedAccessSignatureMatcher(sharedAccessSignature));
        verify(completionMarkerOutput).close();
    }

    private void assertThatTransferPartsFails(Part part, String logMessage, Object... args) {
        String message = format(logMessage, args);
        var result = dataSink.transferParts(List.of(part));
        assertThat(result.failed()).isTrue();
        assertThat(result.getFailureMessages()).containsExactly(message);
        verify(monitor).severe(message, exception);
    }
}