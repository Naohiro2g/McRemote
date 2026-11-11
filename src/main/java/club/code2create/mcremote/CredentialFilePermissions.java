package club.code2create.mcremote;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Owner-only SQLite bundles on POSIX; never chmod pre-existing directories or change ownership. */
final class CredentialFilePermissions {
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final AtomicBoolean REPORTED_NATIVE_ACCESS = new AtomicBoolean();

    private CredentialFilePermissions() {}

    static void createDirectories(Path directory) throws IOException {
        try {
            requireDirectory(directory);
            return; // Existing parent may be a shared directory or a volume root.
        } catch (NoSuchFileException missing) {
            Path parent = directory.getParent();
            if (parent == null) throw missing;
            createDirectories(parent);
        }
        boolean posix = view(directory.getParent()) != null;
        try {
            if (posix) Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
            else Files.createDirectory(directory);
        } catch (FileAlreadyExistsException racedCreation) {
            requireDirectory(directory);
            return; // Do not claim ownership of another creator's directory.
        }
        // Restore owner bits even under a restrictive umask; never widen group/other bits.
        if (posix) restrict(view(directory), DIRECTORY_MODE);
    }

    static void createFile(Path file) throws IOException {
        if (view(file.getParent()) != null) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE_MODE));
        } else {
            Files.createFile(file);
        }
        restrictFile(file);
    }

    static void restrictFile(Path file) throws IOException {
        CredentialStore.requireRegularFile(file, "SQLite credential file");
        restrict(view(file), FILE_MODE);
    }

    private static void requireDirectory(Path directory) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.isSymbolicLink()) {
            throw new IOException("SQLite credential parent must be a non-symlink directory");
        }
    }

    private static PosixFileAttributeView view(Path path) {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null && REPORTED_NATIVE_ACCESS.compareAndSet(false, true)) {
            Logger.getLogger("McR_CredentialService").info(
                    "Credential POSIX file modes unavailable; retaining platform-native access controls");
        }
        return view;
    }

    // Separate for deterministic failure/read-back tests without mocking the filesystem globally.
    static void restrict(PosixFileAttributeView view, Set<PosixFilePermission> mode) throws IOException {
        if (view == null) return; // Windows and other non-POSIX filesystems retain their native ACL model.
        try {
            if (!view.readAttributes().permissions().equals(mode)) view.setPermissions(mode);
            if (!view.readAttributes().permissions().equals(mode)) {
                throw new IOException("SQLite credential owner-only permissions could not be established");
            }
        } catch (UnsupportedOperationException | SecurityException failure) {
            throw new IOException("SQLite credential permission enforcement failed", failure);
        }
    }
}
