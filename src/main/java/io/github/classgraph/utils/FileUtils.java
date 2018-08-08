/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.classgraph.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;

/**
 * File utilities.
 */
public class FileUtils {
    /**
     * The current directory path (only reads the current directory once, the first time this field is accessed, so
     * will not reflect subsequent changes to the current directory).
     */
    public static final String CURR_DIR_PATH;

    static {
        String currDirPathStr = "";
        try {
            // The result is moved to currDirPathStr after each step, so we can provide fine-grained debug info and
            // a best guess at the path, if the current dir doesn't exist (#109), or something goes wrong while
            // trying to get the current dir path.
            Path currDirPath = Paths.get("").toAbsolutePath();
            currDirPathStr = currDirPath.toString();
            currDirPath = currDirPath.normalize();
            currDirPathStr = currDirPath.toString();
            currDirPath = currDirPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
            currDirPathStr = currDirPath.toString();
            currDirPathStr = FastPathResolver.resolve(currDirPathStr);
        } catch (final IOException e) {
            throw new RuntimeException("Could not resolve current directory: " + currDirPathStr, e);
        }
        CURR_DIR_PATH = currDirPathStr;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * The minimum filesize at which it becomes more efficient to read a file with a memory-mapped file channel
     * rather than an InputStream.
     */
    public static final int FILECHANNEL_FILE_SIZE_THRESHOLD;

    static {
        switch (VersionFinder.OS) {
        case Linux:
            FILECHANNEL_FILE_SIZE_THRESHOLD = 16384;
            break;
        case Windows:
            // TODO
            FILECHANNEL_FILE_SIZE_THRESHOLD = 16384;
            break;
        case MacOSX:
            // TODO
            FILECHANNEL_FILE_SIZE_THRESHOLD = 16384;
            break;
        default:
            FILECHANNEL_FILE_SIZE_THRESHOLD = 16384;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Read all the bytes in an {@link InputStream}.
     * 
     * @param inputStream
     *            The {@link InputStream}.
     * @param fileSize
     *            The file size, if known, otherwise -1L.
     * @param log
     *            The log.
     * @return The contents of the {@link InputStream} as an Entry consisting of the byte array and number of bytes
     *         used in the array..
     * @throws IOException
     *             If the contents could not be read.
     */
    private static SimpleEntry<byte[], Integer> readAllBytes(final InputStream inputStream, final long fileSize,
            final LogNode log) throws IOException {
        byte[] buf = new byte[DEFAULT_BUFFER_SIZE];
        int bufLength = buf.length;
        int totBytesRead = 0;
        for (int bytesRead;;) {
            while ((bytesRead = inputStream.read(buf, totBytesRead, bufLength - totBytesRead)) > 0) {
                totBytesRead += bytesRead;
            }
            if (bytesRead < 0) {
                // Reached end of stream
                break;
            }
            // Grow buffer, avoiding overflow
            if (bufLength <= MAX_BUFFER_SIZE - bufLength) {
                bufLength = bufLength << 1;
            } else {
                if (bufLength == MAX_BUFFER_SIZE) {
                    throw new IOException("InputStream too large to read");
                }
                bufLength = MAX_BUFFER_SIZE;
            }
            buf = Arrays.copyOf(buf, bufLength);
        }
        return new SimpleEntry<>((bufLength == totBytesRead) ? buf : Arrays.copyOf(buf, totBytesRead),
                totBytesRead);
    }

    private static final int DEFAULT_BUFFER_SIZE = 16384;

    // Some VMs reserve header words in arrays, can't allocate arrays of size Integer.MAX_VALUE
    private static final int MAX_BUFFER_SIZE = Integer.MAX_VALUE - 8;

    /**
     * Read all the bytes in an {@link InputStream} as a byte array.
     * 
     * @param inputStream
     *            The {@link InputStream}.
     * @param fileSize
     *            The file size, if known, otherwise -1L.
     * @param log
     *            The log.
     * @return The contents of the {@link InputStream} as a byte array.
     * @throws IOException
     *             If the contents could not be read.
     */
    public static byte[] readAllBytesAsArray(final InputStream inputStream, final long fileSize, final LogNode log)
            throws IOException {
        final SimpleEntry<byte[], Integer> ent = readAllBytes(inputStream, fileSize, log);
        final byte[] buf = ent.getKey();
        final int bufBytesUsed = ent.getValue();
        return (buf.length == bufBytesUsed) ? buf : Arrays.copyOf(buf, bufBytesUsed);
    }

    /**
     * Read all the bytes in an {@link InputStream} as a String.
     * 
     * @param inputStream
     *            The {@link InputStream}.
     * @param fileSize
     *            The file size, if known, otherwise -1L.
     * @param log
     *            The log.
     * @return The contents of the {@link InputStream} as a String.
     * @throws IOException
     *             If the contents could not be read.
     */
    public static String readAllBytesAsString(final InputStream inputStream, final long fileSize, final LogNode log)
            throws IOException {
        final SimpleEntry<byte[], Integer> ent = readAllBytes(inputStream, fileSize, log);
        final byte[] buf = ent.getKey();
        final int bufBytesUsed = ent.getValue();
        return new String(buf, 0, bufBytesUsed, "UTF-8");
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Produce an {@link InputStream} that is able to read from a {@link ByteBuffer}.
     * 
     * @param byteBuffer
     *            The {@link ByteBuffer}.
     * @return An {@link InputStream} that reads from the {@ByteBuffer}.
     */
    public static InputStream byteBufferToInputStream(final ByteBuffer byteBuffer) {
        // https://stackoverflow.com/questions/4332264/wrapping-a-bytebuffer-with-an-inputstream/6603018#6603018
        return new InputStream() {
            final ByteBuffer buf = byteBuffer;

            @Override
            public int read() throws IOException {
                if (!buf.hasRemaining()) {
                    return -1;
                }
                return buf.get() & 0xFF;
            }

            @Override
            public int read(final byte[] bytes, final int off, final int len) throws IOException {
                if (!buf.hasRemaining()) {
                    return -1;
                }

                final int bytesRead = Math.min(len, buf.remaining());
                buf.get(bytes, off, bytesRead);
                return bytesRead;
            }
        };
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @param path
     *            A file path.
     * @return true if path has a ".class" extension, ignoring case.
     */
    public static boolean isClassfile(final String path) {
        final int len = path.length();
        return len > 6 && path.regionMatches(true, len - 6, ".class", 0, 6);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * @param file
     *            A {@File}.
     * @return true if a file exists and can be read.
     */
    public static boolean canRead(final File file) {
        try {
            return file.canRead();
        } catch (final SecurityException e) {
            return false;
        }
    }
}
