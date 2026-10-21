package com.byd.extend.remote;

/** One HTTP byte range over an original MP4; null header means the complete representation. */
final class HttpByteRange {
    final long start, end, total;
    final boolean partial;

    private HttpByteRange(long start, long end, long total, boolean partial) {
        this.start = start; this.end = end; this.total = total; this.partial = partial;
    }

    static HttpByteRange parse(String header, long size) {
        if (size <= 0) throw new IllegalArgumentException("empty recording");
        if (header == null) return new HttpByteRange(0, size - 1, size, false);
        if (!header.matches("bytes=[0-9]*-[0-9]*"))
            throw new IllegalArgumentException("unsupported byte range");
        String[] parts = header.substring(6).split("-", -1);
        try {
            if (parts[0].isEmpty()) {
                long suffix = Long.parseLong(parts[1]);
                if (suffix <= 0) throw new IllegalArgumentException("empty suffix range");
                return new HttpByteRange(Math.max(0, size - suffix), size - 1, size, true);
            }
            long start = Long.parseLong(parts[0]);
            long end = parts[1].isEmpty() ? size - 1 : Math.min(size - 1, Long.parseLong(parts[1]));
            if (start >= size || end < start) throw new IllegalArgumentException("unsatisfiable byte range");
            return new HttpByteRange(start, end, size, true);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("invalid byte range", malformed);
        }
    }

    long length() { return end - start + 1; }
    String contentRange() { return "bytes " + start + "-" + end + "/" + total; }
}
