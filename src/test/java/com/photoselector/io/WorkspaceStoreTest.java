package com.photoselector.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.Workspace;
import com.photoselector.testing.TestImages;

class WorkspaceStoreTest {

    @TempDir
    Path tempDir;

    private WorkspaceStore store() {
        return new WorkspaceStore(tempDir.resolve("config").resolve("workspace.properties"));
    }

    @Test
    void 没存过就是空() {
        assertTrue(store().load().isEmpty());
    }

    @Test
    void 存下去再读回来是同一个工作区() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("素材"));
        Path skipA = Files.createDirectories(root.resolve("001_不要的"));
        Path skipB = Files.createDirectories(root.resolve("002_也不要"));
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        Workspace saved = new Workspace(root, Set.of(skipA, skipB), target);

        store().save(saved);
        Workspace loaded = store().load().orElseThrow();

        assertEquals(root, loaded.sourceRoot());
        assertEquals(target, loaded.targetFolder());
        assertEquals(Set.of(skipA, skipB), loaded.excludedFolders());
        assertTrue(loaded.isIncluded(root.resolve("003_要的")));
    }

    @Test
    void 剔除项指向的文件夹不在了就忽略这一条() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("素材"));
        Path gone = Files.createDirectories(root.resolve("待删"));
        Path kept = Files.createDirectories(root.resolve("留着"));
        store().save(new Workspace(root, Set.of(gone, kept), null));

        // 之后那个文件夹被删了
        Files.delete(gone);

        Workspace loaded = store().load().orElseThrow();
        assertEquals(Set.of(kept), loaded.excludedFolders(), "不存在的剔除项应当被忽略");
        assertTrue(loaded.isIncluded(gone), "被忽略之后它等于重新纳入范围");
    }

    @Test
    void 目标文件夹没选过时读回来是空的() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("素材"));
        store().save(Workspace.of(root));

        assertEquals(null, store().load().orElseThrow().targetFolder());
    }

    @Test
    void 上次浏览的照片会跟着工作区一起保存() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("素材"));
        Path target = Files.createDirectories(tempDir.resolve("成片"));
        Path photo = TestImages.writeJpeg(root.resolve("last.jpg"), 100, 100);
        Workspace saved = new Workspace(root, Set.of(), target, photo);

        store().save(saved);

        assertEquals(photo.toAbsolutePath().normalize(),
                store().load().orElseThrow().lastViewedPhoto());
    }

    @Test
    void 配置文件被写坏了也只会当成没配过() throws Exception {
        Path config = tempDir.resolve("config").resolve("workspace.properties");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "这不是一个 properties 文件\u0000\u0001乱码");

        assertTrue(new WorkspaceStore(config).load().isEmpty(), "读不出来就当没配过，别让程序起不来");
    }

    @Test
    void 配置是纯文本_可以打开看() throws Exception {
        Path config = tempDir.resolve("config").resolve("workspace.properties");
        Path root = Files.createDirectories(tempDir.resolve("素材"));
        Files.createDirectories(root.resolve("A"));
        Files.createDirectories(root.resolve("B"));

        new WorkspaceStore(config).save(
                new Workspace(root, Set.of(root.resolve("A"), root.resolve("B")), null));

        String text = Files.readString(config);
        assertTrue(text.contains("sourceRoot="), "根目录要能一眼看到");
        assertTrue(text.contains("excluded.0="), "剔除项按序号排列，打开就能改");
        assertTrue(text.contains("excluded.1="));
    }
}
