package com.photoselector.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.core.PhotoGroup;
import com.photoselector.testing.TestImages;

class PhotoFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void 根目录自己的照片和直接子文件夹的照片分成两组() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("照片_Leica_M10"));
        Path loose = TestImages.writeJpeg(root.resolve("根目录.jpg"), 300, 200);
        Path child = Files.createDirectories(root.resolve("095_拍摄"));
        Path childPhoto = TestImages.writeJpeg(child.resolve("子目录.jpg"), 300, 200);
        Path grandchild = Files.createDirectories(child.resolve("101_更深一层"));
        TestImages.writeJpeg(grandchild.resolve("深层.jpg"), 300, 200);

        List<PhotoGroup> groups = PhotoFiles.groupsIn(root);

        assertEquals(2, groups.size(), "根目录照片一组，直接子文件夹照片一组");
        assertEquals(root.getFileName().toString(), groups.get(0).name());
        assertEquals(List.of(loose), groups.get(0).photos());
        assertEquals(child.getFileName().toString(), groups.get(1).name());
        assertEquals(List.of(childPhoto), groups.get(1).photos(),
                "更深一层的子文件夹不会被递归塞进素材条");
    }
}
