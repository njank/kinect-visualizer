package at.njank.kinect.lwjgl3;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class NativeLoader {
    public static void load(String libName) {
        libName = System.mapLibraryName(libName);
        try {
            Path jarDir = Path.of(NativeLoader.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI())
                .getParent();

            Path target = jarDir.resolve(libName);
            if (!Files.exists(target)) {
                try (InputStream in = NativeLoader.class.getResourceAsStream("/" + libName)) {
                    if (in == null) throw new RuntimeException("Not found in JAR: " + libName);
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            System.load(target.toAbsolutePath().toString());
        } catch (URISyntaxException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}
