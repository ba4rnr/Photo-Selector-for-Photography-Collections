package com.photoselector.io;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.photoselector.testing.TestImages;

class JpegMetadataSegmentsTest {

    @TempDir
    Path tempDir;

    @Test
    void 只保留语义明确的元数据段_跳过APP14和MPF() throws Exception {
        Path jpeg = TestImages.writeJpeg(tempDir.resolve("metadata.jpg"), 30, 20);
        byte[] icc = "ICC_PROFILE\0\u0001\u0001fake-profile"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] mpf = "MPF\0MPF-SHOULD-NOT-BE-COPIED"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] adobe14 = new byte[] {'A', 'd', 'o', 'b', 'e', 0, 100, 0, 0, 0, 0, 0};

        TestImages.insertAppSegment(jpeg, 0xE2, icc);
        TestImages.insertAppSegment(jpeg, 0xE2, mpf);
        TestImages.insertAppSegment(jpeg, 0xEE, adobe14);

        List<byte[]> kept = JpegMetadataSegments.from(jpeg, 30, 20);

        assertTrue(kept.stream().anyMatch(segment ->
                        (segment[1] & 0xFF) == 0xE2
                                && startsWith(segment, 4, "ICC_PROFILE\0"
                                        .getBytes(StandardCharsets.US_ASCII))),
                "ICC 段应当保留");
        assertFalse(kept.stream().anyMatch(segment ->
                        (segment[1] & 0xFF) == 0xE2
                                && startsWith(segment, 4, "MPF\0"
                                        .getBytes(StandardCharsets.US_ASCII))),
                "MPF 段不能保留");
        assertFalse(kept.stream().anyMatch(segment -> (segment[1] & 0xFF) == 0xEE),
                "APP14 不能保留");
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (data.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
