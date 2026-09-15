package io.jenkins.plugins.scmoras;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import jenkins.scm.api.SCMFile;

/**
 * A single entry (file or directory) inside a repository artifact, resolved lazily against the packaged tar.gz
 * by {@link OrasSCMFileSystem}. No network access happens until the entry's type or content is actually needed.
 */
final class OrasSCMFile extends SCMFile {

    private final OrasSCMFileSystem fs;

    /**
     * Root constructor.
     */
    OrasSCMFile(OrasSCMFileSystem fs) {
        this.fs = fs;
    }

    private OrasSCMFile(OrasSCMFile parent, String name, OrasSCMFileSystem fs) {
        super(parent, name);
        this.fs = fs;
    }

    @NonNull
    @Override
    protected SCMFile newChild(@NonNull String name, boolean assumeIsDirectory) {
        return new OrasSCMFile(this, name, fs);
    }

    @NonNull
    @Override
    public Iterable<SCMFile> children() throws IOException {
        String path = getPath();
        String searchPrefix = path.isEmpty() ? "" : path + "/";
        Set<String> immediateNames = new LinkedHashSet<>();
        fs.scan((entry, content) -> {
            String name = withoutTrailingSlash(entry.getName());
            if (!name.equals(path) && name.startsWith(searchPrefix)) {
                String rest = name.substring(searchPrefix.length());
                int slash = rest.indexOf('/');
                immediateNames.add(slash == -1 ? rest : rest.substring(0, slash));
            }
            return true;
        });
        List<SCMFile> children = new ArrayList<>();
        for (String name : immediateNames) {
            children.add(newChild(name, true));
        }
        return children;
    }

    @Override
    public long lastModified() {
        return 0L;
    }

    @NonNull
    @Override
    protected Type type() throws IOException {
        if (isRoot()) {
            return Type.DIRECTORY;
        }
        String path = getPath();
        String dirPrefix = path + "/";
        Type[] result = {Type.NONEXISTENT};
        fs.scan((entry, content) -> {
            String name = withoutTrailingSlash(entry.getName());
            if (name.equals(path)) {
                result[0] = entry.isDirectory() ? Type.DIRECTORY : Type.REGULAR_FILE;
                return false;
            }
            if (name.startsWith(dirPrefix)) {
                result[0] = Type.DIRECTORY;
                return false;
            }
            return true;
        });
        return result[0];
    }

    @NonNull
    @Override
    public InputStream content() throws IOException {
        String path = getPath();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean[] found = {false};
        fs.scan((entry, content) -> {
            String name = withoutTrailingSlash(entry.getName());
            if (name.equals(path) && !entry.isDirectory()) {
                content.transferTo(buffer);
                found[0] = true;
                return false;
            }
            return true;
        });
        if (!found[0]) {
            throw new FileNotFoundException("No such file " + path + " in the repository artifact");
        }
        return new ByteArrayInputStream(buffer.toByteArray());
    }

    private static String withoutTrailingSlash(String name) {
        return name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }
}
