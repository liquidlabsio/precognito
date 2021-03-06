/*
 *
 *  Copyright (c) 2020. Liquidlabs Ltd <info@liquidlabs.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software  distributed under the License is distributed on an "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 *   See the License for the specific language governing permissions and  limitations under the License.
 *
 */

package io.fluidity.services.fixture;

import io.fluidity.services.query.FileMeta;
import io.fluidity.services.server.FileSystemBasedStorageService;
import io.fluidity.util.FileUtil;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class FileSystemBasedStorageServiceTest {

    @BeforeEach
    public void before() {
        System.setProperty(FileSystemBasedStorageService.FLUIDITY_FS_DIR, "./target/data/fs-test");
    }

    @Test
    void uploadAndGet() {
        FileSystemBasedStorageService storage = new FileSystemBasedStorageService();
        byte[] fileContent = "Some bytes".getBytes();

        String filename = "stuff/" + System.currentTimeMillis() + "_myfile.log";
        FileMeta fileMetaData = new FileMeta("tenant", "server", "tags", filename, fileContent, 0, System.currentTimeMillis(), "");
        FileMeta uploaded = storage.upload("region", fileMetaData);

        assertNotNull(uploaded.getStorageUrl());

        byte[] bytes = storage.get("", uploaded.storageUrl, 0);
        Assert.assertNotNull(bytes);
    }

    @Test
    void bulkOperations() throws IOException {

        String testDir = "./target/test-data/storage/bulk" + System.currentTimeMillis();

        FileUtil.writeFile(new File(testDir, "file1.log").getPath(), "data1".getBytes());
        FileUtil.writeFile(new File(testDir, "file2.log").getPath(), "data2".getBytes());
        FileUtil.writeFile(new File(testDir, "file3.log").getPath(), "data2".getBytes());

        FileSystemBasedStorageService storage = new FileSystemBasedStorageService();

        List<FileMeta> fileMetas = storage.importFromStorage("region", "tenant", testDir, "", 30, "", "tags", "");

        Assert.assertEquals(3, fileMetas.size());
        Assert.assertEquals("tags", fileMetas.get(0).getTags());
    }
}