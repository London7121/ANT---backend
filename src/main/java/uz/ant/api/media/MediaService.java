package uz.ant.api.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import uz.ant.api.common.ApiException;

/**
 * Yuklangan faylni qayta ishlaydi: rasm — EXIF bo'yicha burish, kenglikni cheklash, formatga o'tkazish (WebP/JPEG/PNG) + kichik preview;
 * video, SVG, GIF, AVIF — o'zgartirilmasdan saqlanadi.
 */
@Service
public class MediaService {
    static {
        ImageIO.scanForPlugins(); // webp-imageio plaginini ro'yxatga oladi
    }

    public static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif", "image/svg+xml");
    public static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/webm", "video/quicktime");
    private static final int THUMB_WIDTH = 480;

    public record Options(int maxWidth, int quality, String format) {}

    private final FileStore store;
    private final MediaRepository repo;
    private final SecureRandom random = new SecureRandom();

    public MediaService(FileStore store, MediaRepository repo) {
        this.store = store;
        this.repo = repo;
    }

    private String uid() {
        byte[] b = new byte[6];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String slug(String original) {
        String base = original == null ? "" : original;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("[^\\w-]+", "_");
        if (base.length() > 40) base = base.substring(0, 40);
        return base.isEmpty() ? "file" : base;
    }

    private static String contentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".avif")) return "image/avif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mov")) return "video/quicktime";
        return "video/mp4";
    }

    /** Vaqtinchalik ishchi fayl (qayta ishlash uchun); omborga `store.put` bilan joylanadi. */
    private static Path temp(String suffix) throws IOException { return Files.createTempFile("ant-upload-", suffix); }

    public Map<String, Object> process(MultipartFile file, Options opts) {
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        try {
            if (VIDEO_TYPES.contains(type)) {
                String ext = type.equals("video/webm") ? ".webm" : type.equals("video/quicktime") ? ".mov" : ".mp4";
                return repo.insert("video", original, store.url(storeAsIs(file, ext)), null, null, null, file.getSize());
            }
            if (type.equals("image/svg+xml") || type.equals("image/gif") || type.equals("image/avif")) {
                String ext = type.equals("image/gif") ? ".gif" : type.equals("image/avif") ? ".avif" : ".svg";
                return repo.insert("image", original, store.url(storeAsIs(file, ext)), null, null, null, file.getSize());
            }
            return processImage(file, original, type, opts);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String storeAsIs(MultipartFile file, String ext) throws IOException {
        String name = slug(file.getOriginalFilename()) + "-" + uid() + ext;
        Path tmp = temp(ext);
        try {
            file.transferTo(tmp);
            store.put(name, tmp, contentType(name));
        } finally {
            Files.deleteIfExists(tmp);
        }
        return name;
    }

    private Map<String, Object> processImage(MultipartFile file, String original, String type, Options opts) throws IOException {
        String fmt = opts.format().equals("original") ? (type.equals("image/png") ? "png" : type.equals("image/jpeg") ? "jpeg" : "webp") : opts.format();
        String ext = fmt.equals("jpeg") ? "jpg" : fmt;
        String name = slug(original) + "-" + uid() + "." + ext;
        String thumbName = "thumb-" + name.substring(0, name.lastIndexOf('.')) + ".webp";
        Path src = temp(".src"), target = temp("." + ext), thumb = temp(".webp");
        boolean mainStored = false;
        try {
            file.transferTo(src);
            int sourceWidth = widthOf(src);
            var builder = Thumbnails.of(src.toFile());
            var sized = sourceWidth > opts.maxWidth() ? builder.width(opts.maxWidth()) : builder.scale(1.0);
            sized = sized.outputFormat(fmt);
            if (!fmt.equals("png")) sized = sized.outputQuality(opts.quality() / 100f);
            sized.toFile(target.toFile());

            BufferedImage out = ImageIO.read(target.toFile());
            Thumbnails.of(target.toFile()).width(Math.min(THUMB_WIDTH, out.getWidth())).outputFormat("webp").outputQuality(0.7f).toFile(thumb.toFile());
            long size = Files.size(target);
            int w = out.getWidth(), h = out.getHeight();
            try {
                store.put(name, target, contentType(name));
                mainStored = true;
                store.put(thumbName, thumb, "image/webp");
            } catch (IOException e) {
                throw new UncheckedIOException(e); // saqlash (ombor) xatosi — rasm xatosi emas, 500 va haqiqiy sabab bilan
            }
            return repo.insert("image", original, store.url(name), store.url(thumbName), w, h, size);
        } catch (IOException | RuntimeException e) {
            if (mainStored) try { store.delete(name); } catch (IOException ignored) { /* tozalash */ }
            if (e instanceof ApiException) throw (ApiException) e;
            if (e instanceof UncheckedIOException u) throw u;
            throw ApiException.badRequest("invalid_image");
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(target);
            Files.deleteIfExists(thumb);
        }
    }

    private static int widthOf(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw ApiException.badRequest("invalid_image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return reader.getWidth(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Faylni ombordan va kutubxonadan o'chiradi. */
    public boolean delete(long id) {
        var item = repo.find(id);
        if (item.isEmpty()) return false;
        for (String key : new String[] {"url", "thumb"}) {
            Object u = item.get().get(key);
            if (u instanceof String s) {
                try {
                    store.delete(s.substring(s.lastIndexOf('/') + 1));
                } catch (IOException ignored) {
                    // fayl allaqachon yo'q bo'lishi mumkin
                }
            }
        }
        return repo.delete(id);
    }
}
