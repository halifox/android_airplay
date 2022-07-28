package com.halifox.airplay.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class DmapMetadataParserTest {
    @Test
    public void parsesTitleArtistAndAlbum() throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(new byte[8]);
        writeRecord(payload, "minm", "测试歌曲");
        writeRecord(payload, "asar", "测试歌手");
        writeRecord(payload, "asal", "测试专辑");

        Map<String, String> result = DmapMetadataParser.parse(
                payload.toByteArray(),
                payload.size()
        );

        assertEquals("测试歌曲", result.get("minm"));
        assertEquals("测试歌手", result.get("asar"));
        assertEquals("测试专辑", result.get("asal"));
    }

    @Test
    public void ignoresMalformedRecords() {
        byte[] malformed = new byte[] {
                0, 0, 0, 0, 0, 0, 0, 0,
                'm', 'i', 'n', 'm',
                0, 0, 0, 20,
                'x'
        };

        Map<String, String> result = DmapMetadataParser.parse(malformed, malformed.length);

        assertTrue(result.isEmpty());
    }

    private static void writeRecord(
            ByteArrayOutputStream output,
            String tag,
            String value
    ) throws Exception {
        byte[] tagBytes = tag.getBytes(StandardCharsets.US_ASCII);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        output.write(tagBytes);
        output.write((valueBytes.length >>> 24) & 0xff);
        output.write((valueBytes.length >>> 16) & 0xff);
        output.write((valueBytes.length >>> 8) & 0xff);
        output.write(valueBytes.length & 0xff);
        output.write(valueBytes);
    }
}
