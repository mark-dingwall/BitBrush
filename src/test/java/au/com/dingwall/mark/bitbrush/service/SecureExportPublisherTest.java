package au.com.dingwall.mark.bitbrush.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SecureExportPublisherTest {

    private static final byte[] CONTENT = "Alice\t1234\nZoë\t0987\n".getBytes(StandardCharsets.UTF_8);
    private static final String STALE_NAME = ".bitbrush-pin-export-11111111-2222-4333-8444-555555555555.tmp";

    @TempDir
    Path root;

    private final SecureExportPublisher publisher = new SecureExportPublisher();
    private Path directory;
    private Path target;

    @BeforeEach
    void usePrivatePosixDirectory() throws IOException {
        assumeTrue(root.getFileSystem().supportedFileAttributeViews().contains("unix"));
        directory = root.resolve("private");
        target = directory.resolve("export.tsv");
    }

    @Test
    void createsOwnerOnlyDirectoryAndExportWithExactBytes() throws Exception {
        publisher.publish(target, CONTENT);

        assertTrue(Arrays.equals(CONTENT, Files.readAllBytes(target)), "Export bytes differ");
        assertEquals("rwx------", permissions(directory));
        assertEquals("rw-------", permissions(target));
        assertEquals(Files.getAttribute(root, "unix:uid"), Files.getAttribute(target, "unix:uid"));
        assertEquals(List.of("export.tsv"), names());
    }

    @Test
    void syncsPrivateTemporaryBeforeHardLinkPublicationAndSyncsDirectoryAfterwards() throws Exception {
        List<String> events = new ArrayList<>();
        try (MockedStatic<FileChannel> channels = observeForces(events, false);
             MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.createLink(eq(target), any(Path.class))).thenAnswer(call -> {
                Path temporary = call.getArgument(1);
                assertEquals("rw-------", permissions(temporary));
                assertTrue(Arrays.equals(CONTENT, Files.readAllBytes(temporary)), "Temporary bytes differ");
                assertTrue(events.contains("file-sync"), "Publication preceded file fsync");
                Object result = call.callRealMethod();
                assertTrue(Files.isSameFile(target, temporary), "Publication did not hard-link the temporary");
                events.add("link");
                return result;
            });

            publisher.publish(target, CONTENT);
        }

        assertTrue(events.indexOf("file-sync") < events.indexOf("link"), "File was not synced before publication");
        assertTrue(events.lastIndexOf("directory-sync") > events.indexOf("link"), "Directory was not synced after publication");
        assertEquals(List.of("export.tsv"), names());
    }

    @Test
    void acceptsAnIdenticalExistingExportWithoutReplacingItsInode() throws Exception {
        privateDirectory();
        privateFile(target, CONTENT);
        Object originalKey = Files.readAttributes(target, "unix:ino").get("ino");

        publisher.publish(target, CONTENT);

        assertEquals(originalKey, Files.readAttributes(target, "unix:ino").get("ino"));
        assertTrue(Arrays.equals(CONTENT, Files.readAllBytes(target)), "Existing export changed");
        assertEquals(List.of("export.tsv"), names());
    }

    @Test
    void repeatedPublicationPreservesTargetNamedLikeARecognizableTemporary() throws Exception {
        target = directory.resolve(STALE_NAME);
        publisher.publish(target, CONTENT);
        Object originalInode = Files.getAttribute(target, "unix:ino");
        privateFile(directory.resolve(".bitbrush-pin-export-aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee.tmp"), new byte[]{1});

        publisher.publish(target, CONTENT);

        assertTrue(Files.isRegularFile(target), "Stale cleanup removed the configured export target");
        assertEquals(originalInode, Files.getAttribute(target, "unix:ino"));
        assertTrue(Arrays.equals(CONTENT, Files.readAllBytes(target)), "Restart changed the configured export target");
        assertEquals(List.of(STALE_NAME), names());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesMismatchedExistingExport(boolean sameLength) throws Exception {
        privateDirectory();
        byte[] original = sameLength ? CONTENT.clone() : new byte[]{1};
        original[0] = 'B';
        privateFile(target, original);

        assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));

        assertTrue(Arrays.equals(original, Files.readAllBytes(target)), "Existing target was overwritten");
        assertEquals(List.of("export.tsv"), names());
    }

    @Test
    void racingTargetWinsWithoutReplacementAndCanBeAcceptedOnRetry() throws Exception {
        AtomicReference<Object> winnerKey = new AtomicReference<>();
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.createLink(eq(target), any(Path.class))).thenAnswer(call -> {
                privateFile(target, CONTENT);
                winnerKey.set(Files.getAttribute(target, "unix:ino"));
                return call.callRealMethod();
            });

            assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));
        }

        assertTrue(Arrays.equals(CONTENT, Files.readAllBytes(target)), "Racing target was overwritten");
        assertEquals(winnerKey.get(), Files.getAttribute(target, "unix:ino"));
        assertEquals(List.of("export.tsv"), names());
        publisher.publish(target, CONTENT);
        assertEquals(winnerKey.get(), Files.getAttribute(target, "unix:ino"));
    }

    @Test
    void rejectsTargetSymlinkWithoutTouchingItsReferent() throws Exception {
        privateDirectory();
        Path referent = root.resolve("unrelated");
        privateFile(referent, CONTENT);
        Files.createSymbolicLink(target, referent);

        assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));

        assertTrue(Files.isSymbolicLink(target));
        assertTrue(Arrays.equals(CONTENT, Files.readAllBytes(referent)), "Symlink referent changed");
        assertEquals(List.of("export.tsv"), names());
    }

    @Test
    void rejectsSymlinkDirectoryWithoutCreatingFilesInReferent() throws Exception {
        Path referent = root.resolve("unrelated");
        Files.createDirectory(referent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createSymbolicLink(directory, referent);

        assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));

        try (var children = Files.list(referent)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void rejectsNonDirectoryParent() throws Exception {
        privateFile(directory, CONTENT);

        assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));

        assertTrue(Arrays.equals(CONTENT, Files.readAllBytes(directory)), "Non-directory parent changed");
    }

    @Test
    void rejectsDirectoryAsTarget() throws Exception {
        privateDirectory();
        Files.createDirectory(target);

        assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));

        assertTrue(Files.isDirectory(target));
        assertEquals(List.of("export.tsv"), names());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rwxr-xr-x", "rwxrwx---", "r-x------"})
    void rejectsNonPrivateOrUnwritableDirectory(String mode) throws Exception {
        privateDirectory();
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(mode));
        try {
            assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));
            assertFalse(Files.exists(target));
            assertEquals(mode, permissions(directory));
        } finally {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rw-r--r--", "rw-rw----", "r--------", "rwx------"})
    void rejectsExistingTargetWithWrongPermissions(String mode) throws Exception {
        privateDirectory();
        privateFile(target, CONTENT);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(mode));

        assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));

        assertEquals(mode, permissions(target));
        assertTrue(Arrays.equals(CONTENT, Files.readAllBytes(target)), "Unsafe target changed");
        assertEquals(List.of("export.tsv"), names());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsDirectoryOrTargetOwnedByAnotherEffectiveUser(boolean targetHasWrongOwner) throws Exception {
        privateDirectory();
        privateFile(target, CONTENT);
        Path wrongOwnerPath = targetHasWrongOwner ? target : directory;
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.readAttributes(eq(wrongOwnerPath), anyString(), any(LinkOption[].class)))
                .thenAnswer(call -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> attributes = new HashMap<>((Map<String, Object>) call.callRealMethod());
                    attributes.computeIfPresent("uid", (key, value) -> ((Number) value).intValue() + 1);
                    return attributes;
                });

            assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));
        }

        assertTrue(Arrays.equals(CONTENT, Files.readAllBytes(target)), "Wrong-owner target changed");
        assertEquals(List.of("export.tsv"), names());
    }

    @Test
    void rejectsRelativeTargetAndRootAsDedicatedDirectory() {
        assertThrows(IOException.class, () -> publisher.publish(Path.of("relative/export.tsv"), CONTENT));
        assertThrows(IOException.class, () -> publisher.publish(root.getRoot().resolve("export.tsv"), CONTENT));
        assertFalse(Files.exists(directory));
    }

    @Test
    void failsClosedOnFilesystemWithoutUnixPermissions() throws Exception {
        URI uri = URI.create("jar:" + root.resolve("unsupported.zip").toUri());
        try (var filesystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path unsupportedTarget = filesystem.getPath("/private/export.tsv");

            assertThrows(IOException.class, () -> publisher.publish(unsupportedTarget, CONTENT));

            assertFalse(Files.exists(unsupportedTarget.getParent()));
        }
    }

    @Test
    void failsClosedWhenHardLinksAreUnsupportedAndRemovesOnlyItsOwnTemporary() throws Exception {
        Path unrelated = directory.resolve("notes.txt");
        Path concurrentTemporary = directory.resolve(STALE_NAME);
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.createLink(eq(target), any(Path.class))).thenAnswer(call -> {
                privateFile(unrelated, new byte[]{1});
                privateFile(concurrentTemporary, new byte[]{2});
                throw new UnsupportedOperationException("test filesystem cannot link");
            });

            assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));
        }

        assertFalse(Files.exists(target));
        assertEquals(List.of(STALE_NAME, "notes.txt"), names());
        publisher.publish(target, CONTENT);
        assertEquals(List.of("export.tsv", "notes.txt"), names());
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(unrelated));
    }

    @Test
    void interruptedWriteLeavesNoExportOrInvocationTemporary() throws Exception {
        try (MockedStatic<FileChannel> channels = observeForces(new ArrayList<>(), true)) {
            assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));
        }

        assertEquals(List.of(), names());
    }

    @Test
    void failedPublicationRemovesItsOwnTemporaryAfterItsModeChanges() throws Exception {
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.createLink(eq(target), any(Path.class))).thenAnswer(call -> {
                Files.setPosixFilePermissions(call.getArgument(1), PosixFilePermissions.fromString("r--------"));
                throw new IOException("test publication failure");
            });

            assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));
        }

        assertEquals(List.of(), names());
    }

    @Test
    void failedPublicationPreservesAReplacementAtItsTemporaryName() throws Exception {
        AtomicReference<Path> replacement = new AtomicReference<>();
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.createLink(eq(target), any(Path.class))).thenAnswer(call -> {
                Path temporary = call.getArgument(1);
                Files.delete(temporary);
                privateFile(temporary, new byte[]{42});
                replacement.set(temporary);
                throw new IOException("test publication failure");
            });

            assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));
        }

        assertEquals(List.of(replacement.get().getFileName().toString()), names());
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(replacement.get()));
        assertFalse(Files.exists(target));
    }

    @Test
    void refusesPublicationWhenDirectoryFsyncIsUnsupported() throws Exception {
        try (MockedStatic<FileChannel> channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
            channels.when(() -> FileChannel.open(eq(directory), anySet(), any(FileAttribute[].class)))
                .thenThrow(new UnsupportedOperationException("test filesystem cannot sync directories"));

            assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));
        }

        assertEquals(List.of(), names());
    }

    @Test
    void directoryFsyncFailureAfterPublicationKeepsTheTargetAndCleansTheTemporary() throws Exception {
        AtomicInteger syncs = new AtomicInteger();
        try (MockedStatic<FileChannel> channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
            channels.when(() -> FileChannel.open(eq(directory), anySet(), any(FileAttribute[].class)))
                .thenAnswer(call -> {
                    FileChannel observed = spy((FileChannel) call.callRealMethod());
                    doAnswer(force -> {
                        if (syncs.incrementAndGet() == 2) throw new IOException("test directory fsync failure");
                        return force.callRealMethod();
                    }).when(observed).force(true);
                    return observed;
                });

            assertThrows(IOException.class, () -> publisher.publish(target, CONTENT));
        }

        assertEquals(List.of("export.tsv"), names());
        assertTrue(Arrays.equals(CONTENT, Files.readAllBytes(target)), "Published export changed after directory fsync failure");
        publisher.publish(target, CONTENT);
        assertEquals(List.of("export.tsv"), names());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryRemovesRecognizablePrivateStaleFilesAndLeavesUnrelatedFiles(boolean targetAlreadyExists) throws Exception {
        privateDirectory();
        if (targetAlreadyExists) privateFile(target, CONTENT);
        privateFile(directory.resolve(STALE_NAME), new byte[]{1});
        privateFile(directory.resolve("notes.txt"), new byte[]{2});
        privateFile(directory.resolve(".bitbrush-pin-export-not-a-uuid.tmp"), new byte[]{3});
        String linkedName = ".bitbrush-pin-export-aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee.tmp";
        Files.createSymbolicLink(directory.resolve(linkedName), directory.resolve("notes.txt"));

        publisher.publish(target, CONTENT);

        assertEquals(List.of(linkedName, ".bitbrush-pin-export-not-a-uuid.tmp", "export.tsv", "notes.txt"), names());
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(directory.resolve("notes.txt")));
        assertTrue(Files.isSymbolicLink(directory.resolve(linkedName)));
    }

    private MockedStatic<FileChannel> observeForces(List<String> events, boolean failFileSync) throws IOException {
        MockedStatic<FileChannel> channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS);
        channels.when(() -> FileChannel.open(any(Path.class), anySet(), any(FileAttribute[].class)))
            .thenAnswer(call -> {
                FileChannel real = (FileChannel) call.callRealMethod();
                FileChannel observed = spy(real);
                Path opened = call.getArgument(0);
                doAnswer(force -> {
                    if (failFileSync && !opened.equals(directory)) throw new IOException("test interrupted write");
                    Object result = force.callRealMethod();
                    events.add(opened.equals(directory) ? "directory-sync" : "file-sync");
                    return result;
                }).when(observed).force(true);
                return observed;
            });
        return channels;
    }

    private void privateDirectory() throws IOException {
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private void privateFile(Path file, byte[] content) throws IOException {
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(file, content, StandardOpenOption.WRITE);
    }

    private String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }

    private List<String> names() throws IOException {
        try (var children = Files.list(directory)) {
            return children.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
