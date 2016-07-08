/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Luke Hutchison
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
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
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner.ClassMatcher;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassInfo.ClassInfoUnlinked;
import io.github.lukehutch.fastclasspathscanner.classfileparser.ClassfileBinaryParser;
import io.github.lukehutch.fastclasspathscanner.classgraph.ClassGraphBuilder;
import io.github.lukehutch.fastclasspathscanner.classpath.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.matchprocessor.StaticFinalFieldMatchProcessor;
import io.github.lukehutch.fastclasspathscanner.scanner.ScanSpec.ScanSpecPathMatch;
import io.github.lukehutch.fastclasspathscanner.utils.Log;
import io.github.lukehutch.fastclasspathscanner.utils.Log.DeferredLog;

public class RecursiveScanner {
    /**
     * The number of threads to use for parsing classfiles in parallel. Empirical testing shows that on a modern
     * system with an SSD, NUM_THREADS = 5 is a good default. Raising the number of threads too high can actually
     * hurt performance due to storage contention.
     */
    private final int NUM_THREADS = 5;

    /** The classpath finder. */
    private final ClasspathFinder classpathFinder;

    /** The scanspec (whitelisted and blacklisted packages, etc.). */
    private final ScanSpec scanSpec;

    /** The class matchers. */
    private final ArrayList<ClassMatcher> classMatchers;

    /** A map from class name to static final fields to match within the class. */
    private final Map<String, HashSet<String>> classNameToStaticFinalFieldsToMatch;

    /**
     * A map from (className + "." + staticFinalFieldName) to StaticFinalFieldMatchProcessor(s) that should be
     * called if that class name and static final field name is encountered with a static constant initializer
     * during scan.
     */
    private final Map<String, ArrayList<StaticFinalFieldMatchProcessor>> //
    fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors;

    /** A list of file path testers and match processor wrappers to use for file matching. */
    private final List<FilePathTesterAndMatchProcessorWrapper> filePathTestersAndMatchProcessorWrappers = //
            new ArrayList<>();

    /**
     * The set of absolute directory/zipfile paths scanned (after symlink resolution), to prevent the same resource
     * from being scanned twice.
     */
    private final Set<String> previouslyScannedCanonicalPaths = new HashSet<>();

    /**
     * The set of relative file paths scanned (without symlink resolution), to allow for classpath masking of
     * resources (only the first resource with a given relative path should be visible within the classpath, as per
     * Java conventions).
     */
    private final Set<String> previouslyScannedRelativePaths = new HashSet<>();

    /** A map from class name to ClassInfo object for the class. */
    private final Map<String, ClassInfo> classNameToClassInfo = new HashMap<>();

    /** The class graph builder. */
    private ClassGraphBuilder classGraphBuilder;

    /** The total number of regular directories scanned. */
    private final AtomicInteger numDirsScanned = new AtomicInteger();

    /** The total number of jarfile-internal directories scanned. */
    private final AtomicInteger numJarfileDirsScanned = new AtomicInteger();

    /** The total number of regular files scanned. */
    private final AtomicInteger numFilesScanned = new AtomicInteger();

    /** The total number of jarfile-internal files scanned. */
    private final AtomicInteger numJarfileFilesScanned = new AtomicInteger();

    /** The total number of jarfiles scanned. */
    private final AtomicInteger numJarfilesScanned = new AtomicInteger();

    /** The total number of classfiles scanned. */
    private final AtomicInteger numClassfilesScanned = new AtomicInteger();

    /**
     * The latest last-modified timestamp of any file, directory or sub-directory in the classpath, in millis since
     * the Unix epoch. Does not consider timestamps inside zipfiles/jarfiles, but the timestamp of the zip/jarfile
     * itself is considered.
     */
    private long lastModified = 0;

    // -------------------------------------------------------------------------------------------------------------

    /** An interface used to test whether a file's relative path matches a given specification. */
    public static interface FilePathTester {
        public boolean filePathMatches(final File classpathElt, final String relativePathStr);
    }

    /** An interface called when the corresponding FilePathTester returns true. */
    public static interface FileMatchProcessorWrapper {
        public void processMatch(final File classpathElt, final String relativePath, final InputStream inputStream,
                final long fileSize) throws IOException;
    }

    private static class FilePathTesterAndMatchProcessorWrapper {
        FilePathTester filePathTester;
        FileMatchProcessorWrapper fileMatchProcessorWrapper;

        public FilePathTesterAndMatchProcessorWrapper(final FilePathTester filePathTester,
                final FileMatchProcessorWrapper fileMatchProcessorWrapper) {
            this.filePathTester = filePathTester;
            this.fileMatchProcessorWrapper = fileMatchProcessorWrapper;
        }
    }

    public void addFilePathMatcher(final FilePathTester filePathTester,
            final FileMatchProcessorWrapper fileMatchProcessorWrapper) {
        filePathTestersAndMatchProcessorWrappers
                .add(new FilePathTesterAndMatchProcessorWrapper(filePathTester, fileMatchProcessorWrapper));
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Recursive classpath scanner. Pass in a specification of whitelisted packages/jars to scan and blacklisted
     * packages/jars not to scan, where blacklisted entries are prefixed with the '-' character.
     * 
     * Examples of values for scanSpecs:
     * 
     * ["com.x"] => scans the package "com.x" and its sub-packages in all directories and jars on the classpath.
     * 
     * ["com.x", "-com.x.y"] => scans "com.x" and all sub-packages except "com.x.y" in all directories and jars on
     * the classpath.
     * 
     * ["com.x", "-com.x.y", "jar:deploy.jar"] => scans "com.x" and all sub-packages except "com.x.y", but only
     * looks in jars named "deploy.jar" on the classpath (i.e. whitelisting a "jar:" entry prevents non-jar entries
     * from being searched). Note that only the leafname of a jarfile can be specified.
     * 
     * ["com.x", "-jar:irrelevant.jar"] => scans "com.x" and all sub-packages in all directories and jars on the
     * classpath *except* "irrelevant.jar" (i.e. blacklisting a jarfile doesn't prevent directories from being
     * scanned the way that whitelisting a jarfile does).
     * 
     * ["com.x", "jar:"] => scans "com.x" and all sub-packages, but only looks in jarfiles on the classpath, doesn't
     * scan directories (i.e. all jars are whitelisted, and whitelisting jarfiles prevents non-jars (directories)
     * from being scanned).
     * 
     * ["com.x", "-jar:"] => scans "com.x" and all sub-packages, but only looks in directories on the classpath,
     * doesn't scan jarfiles (i.e. all jars are blacklisted.)
     * 
     * @param fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors
     */
    public RecursiveScanner(final ClasspathFinder classpathFinder, final ScanSpec scanSpec,
            final ArrayList<ClassMatcher> classMatchers,
            final Map<String, HashSet<String>> classNameToStaticFinalFieldsToMatch,
            final Map<String, ArrayList<StaticFinalFieldMatchProcessor>> // 
            fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors) {
        this.classpathFinder = classpathFinder;
        this.scanSpec = scanSpec;
        this.classMatchers = classMatchers;
        this.classNameToStaticFinalFieldsToMatch = classNameToStaticFinalFieldsToMatch;
        this.fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors = // 
                fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Class for calling ClassfileBinaryParser in parallel for all relative paths within a classpath element. */
    private abstract class ClassfileBinaryParserCaller implements Callable<Void> {
        final File classpathElt;
        final Queue<String> relativePaths;
        Queue<ClassInfoUnlinked> classInfoUnlinkedOut;
        final DeferredLog log;

        public ClassfileBinaryParserCaller(final File classpathElt, final Queue<String> relativePaths,
                final Queue<ClassInfoUnlinked> classInfoUnlinkedOut, final DeferredLog log) {
            this.classpathElt = classpathElt;
            this.relativePaths = relativePaths;
            this.classInfoUnlinkedOut = classInfoUnlinkedOut;
            this.log = log;
        }

        @Override
        public Void call() {
            try {
                for (String relativePath; (relativePath = relativePaths.poll()) != null;) {
                    final long fileStartTime = System.nanoTime();
                    // Get input stream from classpath element and relative path
                    try (InputStream inputStream = getInputStream(relativePath)) {
                        // Parse classpath binary format, creating a ClassInfoUnlinked object
                        final ClassInfoUnlinked thisClassInfoUnlinked = ClassfileBinaryParser
                                .readClassInfoFromClassfileHeader(relativePath, inputStream,
                                        classNameToStaticFinalFieldsToMatch, scanSpec, log);
                        if (thisClassInfoUnlinked != null) {
                            classInfoUnlinkedOut.add(thisClassInfoUnlinked);
                        }
                    } catch (final IOException e) {
                        if (FastClasspathScanner.verbose) {
                            log.log(4, "Exception while trying to open " + relativePath + ": " + e);
                        }
                    }
                    if (FastClasspathScanner.verbose) {
                        log.log(6, "Parsed classfile " + relativePath, System.nanoTime() - fileStartTime);
                    }
                }
            } finally {
                close();
            }
            return null;
        }

        protected abstract InputStream getInputStream(String relativePath) throws IOException;

        protected void close() {
        }
    }

    /** Dir/file handler. */
    private class DirClassfileBinaryParserCaller extends ClassfileBinaryParserCaller {
        public DirClassfileBinaryParserCaller(final File classpathElt, final Queue<String> relativePaths,
                final Queue<ClassInfoUnlinked> classInfoUnlinkedOut, final DeferredLog log) throws IOException {
            super(classpathElt, relativePaths, classInfoUnlinkedOut, log);
        }

        @Override
        protected InputStream getInputStream(final String relativePath) throws IOException {
            return new FileInputStream(classpathElt.getPath() + File.separator
                    + (File.separatorChar == '/' ? relativePath : relativePath.replace('/', File.separatorChar)));
        }
    }

    /** Jarfile handler. */
    private class JarClassfileBinaryParserCaller extends ClassfileBinaryParserCaller {
        private final ZipFile zipFile;

        public JarClassfileBinaryParserCaller(final File classpathElt, final Queue<String> relativePaths,
                final Queue<ClassInfoUnlinked> classInfoUnlinkedOut, final DeferredLog log) throws IOException {
            super(classpathElt, relativePaths, classInfoUnlinkedOut, log);
            zipFile = new ZipFile(classpathElt);
        }

        @Override
        protected InputStream getInputStream(final String relativePath) throws IOException {
            return zipFile.getInputStream(zipFile.getEntry(relativePath));
        }

        @Override
        public void close() {
            try {
                zipFile.close();
            } catch (final IOException e) {
                // Ignore
            }
        }
    }

    /** Parse whitelisted classfiles in parallel, populating classNameToClassInfo with the results. */
    private void parallelParseClassfiles(final File classpathElt, final Queue<String> relativePaths,
            final ExecutorService executorService, final DeferredLog[] logs) {
        final boolean isDir = classpathElt.isDirectory();
        final Queue<ClassInfoUnlinked> classInfoUnlinked = new ConcurrentLinkedQueue<>();
        final CompletionService<Void> completionService = new ExecutorCompletionService<>(executorService);
        for (int threadIdx = 0; threadIdx < NUM_THREADS; threadIdx++) {
            try {
                final ClassfileBinaryParserCaller caller = isDir
                        ? this.new DirClassfileBinaryParserCaller(classpathElt, relativePaths, classInfoUnlinked,
                                logs[threadIdx])
                        : this.new JarClassfileBinaryParserCaller(classpathElt, relativePaths, classInfoUnlinked,
                                logs[threadIdx]);
                completionService.submit(caller);
            } catch (final Exception e) {
                Log.log(4, "Exception opening classpath element " + classpathElt + ": " + e);
            }
        }
        for (int i = 0; i < NUM_THREADS; i++) {
            logs[i].flush();
            // Completion barrier
            try {
                completionService.take().get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                Log.log(4, "Exception processing classpath element " + classpathElt + ": " + e);
            }
        }
        // Convert ClassInfoUnlinked to linked ClassInfo objects
        for (final ClassInfoUnlinked c : classInfoUnlinked) {
            c.link(classNameToClassInfo);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return true if the canonical path (after resolving symlinks and getting absolute path) for this dir, jarfile
     * or file hasn't been seen before during this scan.
     */
    private boolean previouslyScanned(final File fileOrDir) {
        try {
            // Get canonical path (resolve symlinks, and make the path absolute), then see if this canonical path
            // has been scanned before
            return !previouslyScannedCanonicalPaths.add(fileOrDir.getCanonicalPath());
        } catch (final IOException | SecurityException e) {
            // If something goes wrong while getting the real path, just return true.
            return true;
        }
    }

    /**
     * Return true if the relative path for this file hasn't been seen before during this scan (indicating that a
     * resource at this path relative to the classpath hasn't been scanned).
     */
    private boolean previouslyScanned(final String relativePath) {
        return !previouslyScannedRelativePaths.add(relativePath);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan a directory for matching file path patterns.
     */
    private void scanDir(final File classpathElt, final File dir, final int ignorePrefixLen,
            boolean inWhitelistedPath, final boolean scanTimestampsOnly,
            final Queue<String> classfileRelativePathsToScanOut) {
        if (previouslyScanned(dir)) {
            if (FastClasspathScanner.verbose) {
                Log.log(3, "Reached duplicate directory, ignoring: " + dir);
            }
            return;
        }
        if (FastClasspathScanner.verbose) {
            Log.log(3, "Scanning directory: " + dir);
        }
        updateLastModifiedTimestamp(dir.lastModified());
        numDirsScanned.incrementAndGet();

        final String dirPath = dir.getPath();
        final String dirRelativePath = ignorePrefixLen > dirPath.length() ? "/" //
                : dirPath.substring(ignorePrefixLen).replace(File.separatorChar, '/') + "/";
        final ScanSpecPathMatch matchStatus = scanSpec.pathWhitelistMatchStatus(dirRelativePath);
        if (matchStatus == ScanSpecPathMatch.NOT_WITHIN_WHITELISTED_PATH
                || matchStatus == ScanSpecPathMatch.WITHIN_BLACKLISTED_PATH) {
            // Reached a non-whitelisted or blacklisted path -- stop the recursive scan
            if (FastClasspathScanner.verbose) {
                Log.log(3, "Reached non-whitelisted (or blacklisted) directory: " + dirRelativePath);
            }
            return;
        } else if (matchStatus == ScanSpecPathMatch.WITHIN_WHITELISTED_PATH) {
            // Reached a whitelisted path -- can start scanning directories and files from this point
            inWhitelistedPath = true;
        }

        final long startTime = System.nanoTime();
        final File[] filesInDir = dir.listFiles();
        if (filesInDir == null) {
            if (FastClasspathScanner.verbose) {
                Log.log(4, "Invalid directory " + dir);
            }
            return;
        }
        for (final File fileInDir : filesInDir) {
            if (fileInDir.isDirectory()) {
                if (inWhitelistedPath //
                        || matchStatus == ScanSpecPathMatch.ANCESTOR_OF_WHITELISTED_PATH) {
                    // Recurse into subdirectory
                    scanDir(classpathElt, fileInDir, ignorePrefixLen, inWhitelistedPath, scanTimestampsOnly,
                            classfileRelativePathsToScanOut);
                }
            } else if (fileInDir.isFile()) {
                final String fileInDirRelativePath = dirRelativePath.isEmpty() || "/".equals(dirRelativePath)
                        ? fileInDir.getName() : dirRelativePath + fileInDir.getName();

                // Class can only be scanned if it's within a whitelisted path subtree, or if it is a classfile
                // that has been specifically-whitelisted
                if (!inWhitelistedPath && (matchStatus != ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                        || !scanSpec.isSpecificallyWhitelistedClass(fileInDirRelativePath))) {
                    // Ignore files that are siblings of specifically-whitelisted files, but that are not
                    // themselves specifically whitelisted
                    continue;
                }

                // Make sure file with same absolute path or same relative path hasn't been scanned before.
                // (N.B. don't inline these two different calls to previouslyScanned() into a single expression
                // using "||", because they have intentional side effects)
                final boolean subFilePreviouslyScannedCanonical = previouslyScanned(fileInDir);
                final boolean subFilePreviouslyScannedRelative = previouslyScanned(fileInDirRelativePath);
                if (subFilePreviouslyScannedRelative || subFilePreviouslyScannedCanonical) {
                    if (FastClasspathScanner.verbose) {
                        Log.log(3, "Reached duplicate path, ignoring: " + fileInDirRelativePath);
                    }
                    continue;
                }

                if (FastClasspathScanner.verbose) {
                    Log.log(3, "Found whitelisted file: " + fileInDirRelativePath);
                }
                updateLastModifiedTimestamp(fileInDir.lastModified());

                if (!scanTimestampsOnly) {
                    boolean matchedFile = false;

                    // Store relative paths of any classfiles encountered
                    if (fileInDirRelativePath.endsWith(".class")) {
                        matchedFile = true;
                        classfileRelativePathsToScanOut.add(fileInDirRelativePath);
                        numClassfilesScanned.incrementAndGet();
                    }

                    // Match file paths against path patterns
                    for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : //
                    filePathTestersAndMatchProcessorWrappers) {
                        if (fileMatcher.filePathTester.filePathMatches(classpathElt, fileInDirRelativePath)) {
                            // File's relative path matches.
                            try {
                                matchedFile = true;
                                final long fileStartTime = System.nanoTime();
                                try (FileInputStream inputStream = new FileInputStream(fileInDir)) {
                                    fileMatcher.fileMatchProcessorWrapper.processMatch(classpathElt,
                                            fileInDirRelativePath, inputStream, fileInDir.length());
                                }
                                if (FastClasspathScanner.verbose) {
                                    Log.log(4, "Processed file match " + fileInDirRelativePath,
                                            System.nanoTime() - fileStartTime);
                                }
                            } catch (final Exception e) {
                                if (FastClasspathScanner.verbose) {
                                    Log.log(3, "Reached non-whitelisted (or blacklisted) file, ignoring: "
                                            + fileInDirRelativePath);
                                }
                            }
                        }
                    }
                    if (matchedFile) {
                        numFilesScanned.incrementAndGet();
                    }
                }
            }
        }
        if (FastClasspathScanner.verbose) {
            Log.log(4, "Scanned directory " + dir + " and any subdirectories", System.nanoTime() - startTime);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan a zipfile for matching file path patterns.
     */
    private void scanZipfile(final File classpathElt, final ZipFile zipFile,
            final Queue<String> classfileRelativePathsToScanOut) {
        if (FastClasspathScanner.verbose) {
            Log.log(3, "Scanning jarfile: " + classpathElt);
        }
        final long startTime = System.nanoTime();
        String prevParentRelativePath = null;
        ScanSpecPathMatch prevParentMatchStatus = null;
        for (final Enumeration<? extends ZipEntry> entries = zipFile.entries(); entries.hasMoreElements();) {
            final long entryStartTime = System.nanoTime();
            final ZipEntry zipEntry = entries.nextElement();
            String relativePath = zipEntry.getName();
            if (relativePath.startsWith("/")) {
                // Shouldn't happen with the standard Java zipfile implementation (but just to be safe)
                relativePath = relativePath.substring(1);
            }

            // Ignore directory entries, they are not needed
            if (zipEntry.isDirectory()) {
                if (prevParentMatchStatus == ScanSpecPathMatch.WITHIN_WHITELISTED_PATH) {
                    numJarfileDirsScanned.incrementAndGet();
                    if (FastClasspathScanner.verbose) {
                        numJarfileFilesScanned.incrementAndGet();
                        Log.log(4, "Reached jarfile-internal directory " + relativePath,
                                System.nanoTime() - entryStartTime);
                    }
                }
                continue;
            }

            // Only accept first instance of a given relative path within classpath.
            if (previouslyScanned(relativePath)) {
                if (FastClasspathScanner.verbose) {
                    Log.log(3, "Reached duplicate relative path, ignoring: " + relativePath);
                }
                continue;
            }

            // Get match status of the parent directory if this zipentry file's relative path
            // (or reuse the last match status for speed, if the directory name hasn't changed). 
            final int lastSlashIdx = relativePath.lastIndexOf("/");
            final String parentRelativePath = lastSlashIdx < 0 ? "/" : relativePath.substring(0, lastSlashIdx + 1);
            final ScanSpecPathMatch parentMatchStatus = // 
                    prevParentRelativePath == null || !parentRelativePath.equals(prevParentRelativePath)
                            ? scanSpec.pathWhitelistMatchStatus(parentRelativePath) : prevParentMatchStatus;
            final boolean parentPathChanged = !parentRelativePath.equals(prevParentRelativePath);
            prevParentRelativePath = parentRelativePath;
            prevParentMatchStatus = parentMatchStatus;
            // Class can only be scanned if it's within a whitelisted path subtree, or if it is a classfile
            // that has been specifically-whitelisted
            if (parentMatchStatus != ScanSpecPathMatch.WITHIN_WHITELISTED_PATH
                    && (parentMatchStatus != ScanSpecPathMatch.AT_WHITELISTED_CLASS_PACKAGE
                            || !scanSpec.isSpecificallyWhitelistedClass(relativePath))) {
                if (FastClasspathScanner.verbose && parentPathChanged) {
                    Log.log(3, "Reached non-whitelisted (or blacklisted) file in jar, ignoring: "
                            + parentRelativePath);
                }
                continue;
            }

            if (FastClasspathScanner.verbose) {
                Log.log(3, "Found whitelisted file in jarfile: " + relativePath);
            }

            boolean matchedFile = false;

            // Store relative paths of any classfiles encountered
            if (relativePath.endsWith(".class")) {
                matchedFile = true;
                classfileRelativePathsToScanOut.add(relativePath);
                numClassfilesScanned.incrementAndGet();
            }

            // Match file paths against path patterns
            for (final FilePathTesterAndMatchProcessorWrapper fileMatcher : //
            filePathTestersAndMatchProcessorWrappers) {
                if (fileMatcher.filePathTester.filePathMatches(classpathElt, relativePath)) {
                    // File's relative path matches.
                    try {
                        matchedFile = true;
                        final long fileStartTime = System.nanoTime();
                        try (InputStream inputStream = zipFile.getInputStream(zipEntry)) {
                            fileMatcher.fileMatchProcessorWrapper.processMatch(classpathElt, relativePath,
                                    inputStream, zipEntry.getSize());
                        }
                        if (FastClasspathScanner.verbose) {
                            Log.log(4, "Processed file match " + relativePath, System.nanoTime() - fileStartTime);
                        }
                    } catch (final Exception e) {
                        if (FastClasspathScanner.verbose) {
                            Log.log(3, "Reached non-whitelisted (or blacklisted) file, ignoring: " + relativePath);
                        }
                    }
                }
            }
            if (matchedFile) {
                numJarfileFilesScanned.incrementAndGet();
            }
        }
        if (FastClasspathScanner.verbose) {
            Log.log(4, "Scanned jarfile " + classpathElt, System.nanoTime() - startTime);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Scan the classpath, and call any MatchProcessors on files or classes that match.
     * 
     * @param scanTimestampsOnly
     *            If true, scans the classpath for matching files, and calls any match processors if a match is
     *            identified. If false, only scans timestamps of files.
     */
    private synchronized void scan(final boolean scanTimestampsOnly) {
        if (FastClasspathScanner.verbose) {
            Log.log("FastClasspathScanner version " + FastClasspathScanner.getVersion());
        }
        final long scanStart = System.nanoTime();

        ExecutorService executorService = null;
        try {
            executorService = Executors.newFixedThreadPool(NUM_THREADS);

            // Create one logger per thread, so that log output is not interleaved
            final DeferredLog[] logs = new DeferredLog[NUM_THREADS];
            for (int i = 0; i < NUM_THREADS; i++) {
                logs[i] = new DeferredLog();
            }

            // Get classpath elements
            final List<File> uniqueClasspathElts = classpathFinder.getUniqueClasspathElements();
            if (FastClasspathScanner.verbose) {
                Log.log("Classpath elements: " + classpathFinder.getUniqueClasspathElements());
            }

            // Initialize scan
            previouslyScannedCanonicalPaths.clear();
            previouslyScannedRelativePaths.clear();
            numDirsScanned.set(0);
            numFilesScanned.set(0);
            numJarfileDirsScanned.set(0);
            numJarfileFilesScanned.set(0);
            numJarfilesScanned.set(0);
            numClassfilesScanned.set(0);
            if (!scanTimestampsOnly) {
                classNameToClassInfo.clear();
            }

            if (FastClasspathScanner.verbose) {
                Log.log(1, "Starting scan" + (scanTimestampsOnly ? " (scanning classpath timestamps only)" : ""));
            }

            // Iterate through path elements and recursively scan within each directory and jar for matching paths
            final Queue<String> classfileRelativePathsToScan = new ConcurrentLinkedQueue<>();
            for (final File classpathElt : uniqueClasspathElts) {
                final long eltStartTime = System.nanoTime();
                final String path = classpathElt.getPath();
                final boolean isDirectory = classpathElt.isDirectory();
                final boolean isFile = classpathElt.isFile();
                final boolean isJar = isFile && ClasspathFinder.isJar(path);
                if (!isDirectory && !isFile) {
                    if (FastClasspathScanner.verbose) {
                        Log.log(2, "Skipping non-file/non-dir on classpath: " + classpathElt);
                    }
                    continue;
                }
                if (isFile && !isJar) {
                    if (FastClasspathScanner.verbose) {
                        Log.log(2, "Skipping non-jar file on classpath: " + classpathElt);
                    }
                    continue;
                }
                if (previouslyScanned(classpathElt)) {
                    if (FastClasspathScanner.verbose) {
                        Log.log(3, "Reached duplicate classpath entry, ignoring: " + classpathElt);
                    }
                    continue;
                }
                if (FastClasspathScanner.verbose) {
                    Log.log(2, "Found " + (isDirectory ? "directory" : "jar") + " on classpath: " + path);
                }

                boolean scanned = false;
                if (isDirectory && scanSpec.scanNonJars) {

                    // ---------------------------------------------------------------------------------------------
                    // Scan within a directory tree and call file MatchProcessors on any matches
                    // ---------------------------------------------------------------------------------------------

                    // Scan dirs recursively, looking for matching paths; call FileMatchProcessors on any matches.
                    // Also store relative paths of all whitelisted classfiles in whitelistedClassfileRelativePaths.
                    scanDir(classpathElt, classpathElt, /* ignorePrefixLen = */ path.length() + 1,
                            /* inWhitelistedPath = */ false, scanTimestampsOnly, classfileRelativePathsToScan);
                    scanned = true;

                } else if (isJar && scanSpec.scanJars) {

                    // ---------------------------------------------------------------------------------------------
                    // Scan within a jar/zipfile and call file MatchProcessors on any matches
                    // ---------------------------------------------------------------------------------------------

                    if (!scanSpec.jarIsWhitelisted(classpathElt.getName())) {
                        if (FastClasspathScanner.verbose) {
                            Log.log(3, "Skipping jarfile that did not match whitelist/blacklist criteria: "
                                    + classpathElt.getName());
                        }
                        continue;
                    }

                    // Use the timestamp of the jar/zipfile as the timestamp for all files,
                    // since the timestamps within the zip directory may be unreliable.
                    updateLastModifiedTimestamp(classpathElt.lastModified());
                    numJarfilesScanned.incrementAndGet();

                    if (!scanTimestampsOnly) {
                        // Don't actually scan the contents of the zipfile if we're only scanning timestamps,
                        // since only the timestamp of the zipfile itself will be used.
                        try (ZipFile zipFile = new ZipFile(classpathElt)) {
                            scanZipfile(classpathElt, zipFile, classfileRelativePathsToScan);
                            scanned = true;
                        } catch (final IOException e) {
                            // Ignore, can only be thrown by zipFile.close() 
                        }
                    }
                }

                if (scanned) {
                    if (FastClasspathScanner.verbose) {
                        Log.log(2, "Scanned classpath element " + classpathElt, System.nanoTime() - eltStartTime);
                    }

                    // ---------------------------------------------------------------------------------------------
                    // Parse classfile binary format for all classfiles found in this classpath element,
                    // and populate classNameToClassInfo with ClassInfo objects for each classfile and the
                    // classes they reference (as superclasses, interfaces etc.).
                    // ---------------------------------------------------------------------------------------------

                    long parseTime = System.nanoTime();

                    parallelParseClassfiles(classpathElt, classfileRelativePathsToScan, executorService, logs);

                    if (FastClasspathScanner.verbose) {
                        Log.log(2, "Parsed classfiles for classpath element " + classpathElt,
                                System.nanoTime() - parseTime);
                    }

                } else {
                    if (FastClasspathScanner.verbose) {
                        Log.log(2, "Skipping classpath element " + path);
                    }
                }
            }

            // -----------------------------------------------------------------------------------------------------
            // Build class graph and call class MatchProcessors on any matches
            // -----------------------------------------------------------------------------------------------------

            // After creating ClassInfo objects for each classfile, build the class graph, and run any
            // MatchProcessors on matching classes.
            if (!scanTimestampsOnly) {
                // Build class, interface and annotation graph out of all the ClassInfo objects.
                classGraphBuilder = new ClassGraphBuilder(classNameToClassInfo);

                // Call any class, interface and annotation MatchProcessors
                for (final ClassMatcher classMatcher : classMatchers) {
                    classMatcher.lookForMatches();
                }

                // Call static final field match processors on matching fields
                for (final ClassInfo classInfo : classNameToClassInfo.values()) {
                    if (classInfo.fieldValues != null) {
                        for (final Entry<String, Object> ent : classInfo.fieldValues.entrySet()) {
                            final String fieldName = ent.getKey();
                            final Object constValue = ent.getValue();
                            final String fullyQualifiedFieldName = classInfo.className + "." + fieldName;
                            final ArrayList<StaticFinalFieldMatchProcessor> staticFinalFieldMatchProcessors = //
                                    fullyQualifiedFieldNameToStaticFinalFieldMatchProcessors
                                            .get(fullyQualifiedFieldName);
                            if (staticFinalFieldMatchProcessors != null) {
                                if (FastClasspathScanner.verbose) {
                                    Log.log(1, "Calling MatchProcessor for static final field "
                                            + classInfo.className + "." + fieldName + " = " + constValue);
                                }
                                for (final StaticFinalFieldMatchProcessor staticFinalFieldMatchProcessor : //
                                staticFinalFieldMatchProcessors) {
                                    staticFinalFieldMatchProcessor.processMatch(classInfo.className, fieldName,
                                            constValue);
                                }
                            }
                        }
                    }
                }
            }

            if (FastClasspathScanner.verbose) {
                Log.log(1, "Number of resources scanned: directories: " + numDirsScanned.get() + "; files: "
                        + numFilesScanned.get() + "; jarfiles: " + numJarfilesScanned.get()
                        + "; jarfile-internal directories: " + numJarfileDirsScanned + "; jarfile-internal files: "
                        + numJarfileFilesScanned + "; classfiles: " + numClassfilesScanned);
            }
        } finally {
            if (executorService != null) {
                try {
                    executorService.shutdown();
                } catch (final Exception e) {
                    // Ignore
                }
                executorService = null;
            }
        }
        if (FastClasspathScanner.verbose) {
            Log.log("Finished scan", System.nanoTime() - scanStart);
        }
    }

    /**
     * Scan the classpath, and call any MatchProcessors on files or classes that match.
     * 
     * @param scanTimestampsOnly
     *            If true, scans the classpath for matching files, and calls any match processors if a match is
     *            identified. If false, only scans timestamps of files.
     */
    public void scan() {
        scan(/* scanTimestampsOnly = */false);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the class, interface and annotation graph builder, containing the results of the last full scan, or null
     * if a scan has not yet been completed.
     */
    public ClassGraphBuilder getClassGraphBuilder() {
        return classGraphBuilder;
    }

    // -------------------------------------------------------------------------------------------------------------

    /** Update the last modified timestamp, given the timestamp of a Path. */
    private void updateLastModifiedTimestamp(final long fileLastModified) {
        // Find max last modified timestamp, but don't accept values greater than the current time
        lastModified = Math.max(lastModified, Math.min(System.currentTimeMillis(), fileLastModified));
    }

    /**
     * Returns true if the classpath contents have been changed since scan() was last called. Only considers
     * classpath prefixes whitelisted in the call to the constructor. Returns true if scan() has not yet been run.
     * Much faster than standard classpath scanning, because only timestamps are checked, and jarfiles don't have to
     * be opened.
     */
    public boolean classpathContentsModifiedSinceScan() {
        final long oldLastModified = this.lastModified;
        if (oldLastModified == 0) {
            return true;
        } else {
            scan(/* scanTimestampsOnly = */true);
            final long newLastModified = this.lastModified;
            return newLastModified > oldLastModified;
        }
    }

    /**
     * Returns the maximum "last modified" timestamp in the classpath (in epoch millis), or zero if scan() has not
     * yet been called (or if nothing was found on the classpath).
     * 
     * The returned timestamp should be less than the current system time if the timestamps of files on the
     * classpath and the system time are accurate. Therefore, if anything changes on the classpath, this value
     * should increase.
     */
    public long classpathContentsLastModifiedTime() {
        return this.lastModified;
    }
}
