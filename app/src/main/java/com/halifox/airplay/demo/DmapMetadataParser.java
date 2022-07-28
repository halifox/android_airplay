package com.halifox.airplay.demo;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses the small DMAP metadata payload sent by an AirPlay audio sender.
 *
 * <p>The native library forwards the payload without interpreting it. The
 * payload used by common AirPlay senders starts with an eight-byte header,
 * followed by four-byte tag, four-byte big-endian length, and value records.</p>
 */
public final class DmapMetadataParser {
    private static final Charset TAG_CHARSET = StandardCharsets.US_ASCII;
    private static final Charset VALUE_CHARSET = StandardCharsets.UTF_8;

    private DmapMetadataParser() {
    }

    public static Map<String, String> parse(byte[] data, int length) {
        if (data == null || length <= 0) {
            return Collections.emptyMap();
        }

        int safeLength = Math.min(length, data.length);
        int firstOffset = safeLength >= 8 ? 8 : 0;
        Map<String, String> result = parseRecords(data, firstOffset, safeLength);

        // A few senders omit the outer eight-byte header.
        if (result.isEmpty() && firstOffset != 0) {
            result = parseRecords(data, 0, safeLength);
        }
        return result.isEmpty() ? Collections.emptyMap() : result;
    }

    private static Map<String, String> parseRecords(byte[] data, int offset, int end) {
        Map<String, String> result = new LinkedHashMap<>();

        while (offset + 8 <= end) {
            String tag = new String(data, offset, 4, TAG_CHARSET);
            int valueLength = readInt(data, offset + 4);
            offset += 8;

            if (valueLength < 0 || valueLength > end - offset) {
                break;
            }

            if ("minm".equals(tag) || "asar".equals(tag) || "asal".equals(tag)) {
                String value = new String(data, offset, valueLength, VALUE_CHARSET)
                        .replace("\u0000", "")
                        .trim();
                if (!value.isEmpty()) {
                    result.put(tag, value);
                }
            }
            offset += valueLength;
        }
        return result;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }
}
