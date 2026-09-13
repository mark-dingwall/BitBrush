package au.com.dingwall.mark.bitbrush.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class SecureExportPublisher {

    private static final String TEMPORARY_PREFIX = ".bitbrush-pin-export-";
    private static final Pattern TEMPORARY_NAME = Pattern.compile(
        "\\.bitbrush-pin-export-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp");

    public void publish(Path target, byte[] expectedContent) throws IOException {
        if (!target.isAbsolute() || !target.equals(target.normalize()) || target.getParent() == null
                || target.getParent().getParent() == null) {
            throw new IOException("PIN export requires an absolute file in a dedicated directory");
        }
        if (!target.getFileSystem().supportedFileAttributeViews().containsAll(Set.of("unix", "posix"))) {
            throw new IOException("PIN export requires Unix ownership and POSIX permissions");
        }

        Path directory = target.getParent();
        Path temporary = directory.resolve(TEMPORARY_PREFIX + UUID.randomUUID() + ".tmp");
        boolean createdTemporary = false;
        UnixAttributes temporaryAttributes = null;
        try {
            try {
                Files.createDirectory(directory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (FileAlreadyExistsException ignored) {
                // A restart may reuse the directory, but must validate it without following links.
            }
            UnixAttributes directoryAttributes = attributes(directory);
            if (!directoryAttributes.directory() || directoryAttributes.mode() != 0700) {
                throw new IOException("PIN export directory must be a real directory with mode 0700");
            }

            try (FileChannel output = FileChannel.open(temporary,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                createdTemporary = true;
                temporaryAttributes = attributes(temporary);
                requirePrivateFile(temporaryAttributes, temporaryAttributes.uid());
                // The kernel assigns a newly created file to the effective UID. This avoids
                // trusting user.name or the real UID when validating pre-existing artifacts.
                int effectiveUid = temporaryAttributes.uid();
                if (directoryAttributes.uid() != effectiveUid) {
                    throw new IOException("PIN export directory must belong to the effective process user");
                }

                try (FileChannel directoryChannel = FileChannel.open(directory,
                        Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                    // Refuse unsupported directory fsync before publishing any plaintext.
                    directoryChannel.force(true);
                    UnixAttributes existing = attributesIfPresent(target);
                    if (existing != null) {
                        requirePrivateFile(existing, effectiveUid);
                        requireIdenticalContent(target, expectedContent);
                        removeStaleTemporaries(directory, temporary, target, effectiveUid);
                        directoryChannel.force(true);
                        return;
                    }

                    removeStaleTemporaries(directory, temporary, target, effectiveUid);
                    ByteBuffer bytes = ByteBuffer.wrap(expectedContent);
                    while (bytes.hasRemaining()) output.write(bytes);
                    output.force(true);
                    requireUnchanged(directory, directoryAttributes);
                    requireUnchanged(temporary, temporaryAttributes);

                    // CREATE_NEW on the temporary is not a publication lock. The hard link
                    // atomically fails if any target appeared, including a matching export.
                    Files.createLink(target, temporary);
                    directoryChannel.force(true);
                }
            }
        } catch (UnsupportedOperationException | SecurityException exception) {
            throw new IOException("Filesystem cannot safely publish the PIN export");
        } finally {
            if (createdTemporary) {
                UnixAttributes remaining = attributesIfPresent(temporary);
                if (remaining != null && (temporaryAttributes == null || remaining.sameFile(temporaryAttributes))) {
                    Files.delete(temporary);
                }
            }
        }
    }

    private void requirePrivateFile(UnixAttributes attributes, int effectiveUid) throws IOException {
        if (!attributes.regular() || attributes.mode() != 0600 || attributes.uid() != effectiveUid) {
            throw new IOException("PIN export file must be regular, owned by the effective process user, and mode 0600");
        }
    }

    private void requireIdenticalContent(Path target, byte[] expectedContent) throws IOException {
        try (FileChannel input = FileChannel.open(target, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (input.size() != expectedContent.length) {
                throw new IOException("Existing PIN export content does not match");
            }
            byte[] actual = new byte[expectedContent.length];
            try {
                ByteBuffer bytes = ByteBuffer.wrap(actual);
                while (bytes.hasRemaining()) {
                    if (input.read(bytes) < 0) throw new IOException("Existing PIN export content does not match");
                }
                if (!Arrays.equals(expectedContent, actual) || input.read(ByteBuffer.allocate(1)) != -1) {
                    throw new IOException("Existing PIN export content does not match");
                }
            } finally {
                Arrays.fill(actual, (byte) 0);
            }
        }
    }

    private void removeStaleTemporaries(Path directory, Path currentTemporary, Path target, int effectiveUid) throws IOException {
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (entry.equals(target) || entry.equals(currentTemporary)
                        || !TEMPORARY_NAME.matcher(entry.getFileName().toString()).matches()) {
                    continue;
                }
                UnixAttributes attributes = attributesIfPresent(entry);
                if (attributes != null && attributes.regular() && attributes.mode() == 0600 && attributes.uid() == effectiveUid) {
                    Files.delete(entry);
                }
            }
        }
    }

    private void requireUnchanged(Path path, UnixAttributes expected) throws IOException {
        if (!attributes(path).equals(expected)) {
            throw new IOException("PIN export filesystem entry changed during publication");
        }
    }

    private UnixAttributes attributesIfPresent(Path path) throws IOException {
        try {
            return attributes(path);
        } catch (NoSuchFileException exception) {
            return null;
        }
    }

    private UnixAttributes attributes(Path path) throws IOException {
        Map<String, Object> values = Files.readAttributes(path,
            "unix:mode,uid,ino,dev,isDirectory,isRegularFile", LinkOption.NOFOLLOW_LINKS);
        return new UnixAttributes(((Number) values.get("mode")).intValue() & 07777,
            ((Number) values.get("uid")).intValue(), ((Number) values.get("ino")).longValue(),
            ((Number) values.get("dev")).longValue(), (boolean) values.get("isDirectory"),
            (boolean) values.get("isRegularFile"));
    }

    private record UnixAttributes(int mode, int uid, long inode, long device, boolean directory, boolean regular) {
        boolean sameFile(UnixAttributes other) {
            return inode == other.inode && device == other.device;
        }
    }
}
