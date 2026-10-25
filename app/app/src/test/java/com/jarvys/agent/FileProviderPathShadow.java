package com.jarvys.agent;

import java.io.File;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Uses Android path separators for FileProvider's host-side containment comparison. */
@Implements(className = "androidx.core.content.FileProvider$SimplePathStrategy", isInAndroidSdk = false)
public class FileProviderPathShadow {
    @Implementation protected boolean belongsToRoot(String filePath, String rootPath) {
        filePath = filePath.replace(File.separatorChar, '/');
        rootPath = rootPath.replace(File.separatorChar, '/');
        if (filePath.endsWith("/")) filePath = filePath.substring(0, filePath.length() - 1);
        if (rootPath.endsWith("/")) rootPath = rootPath.substring(0, rootPath.length() - 1);
        return filePath.startsWith(rootPath + '/');
    }
}
