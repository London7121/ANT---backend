package uz.ant.api.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uz.ant.api.config.AntProperties;

@Component
@ConditionalOnProperty(prefix = "ant.storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalFileStore implements FileStore {
    private final AntProperties props;

    public LocalFileStore(AntProperties props) { this.props = props; }

    @Override
    public void put(String name, Path file, String contentType) throws IOException {
        Files.createDirectories(props.uploadDir());
        Files.move(file, props.uploadDir().resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void delete(String name) throws IOException { Files.deleteIfExists(props.uploadDir().resolve(name)); }

    @Override
    public String url(String name) { return "/uploads/" + name; }
}
