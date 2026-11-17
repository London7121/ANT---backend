package uz.ant.api.media;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import uz.ant.api.common.ApiException;
import uz.ant.api.security.RequirePermission;

@RestController
@RequestMapping("/api/admin/media")
@SecurityRequirement(name = "bearer")
@Tag(name = "Admin: media kutubxonasi")
public class MediaController {
    private static final List<String> FORMATS = List.of("webp", "jpeg", "png", "original");
    private final MediaService service;
    private final MediaRepository repo;

    public MediaController(MediaService service, MediaRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    private static int clamp(String value, int fallback, int min, int max) {
        int v;
        try {
            v = Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            v = 0;
        }
        if (v == 0) v = fallback;
        return Math.min(Math.max(v, min), max);
    }

    @GetMapping
    @RequirePermission("media.view")
    @Operation(summary = "Media ro'yxati (yangisi birinchi)")
    public List<Map<String, Object>> list() { return repo.list(); }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission("media.create")
    @Operation(summary = "Rasm/video yuklash",
            description = "Rasm: maxWidth (64–5000, standart 2000), quality (40–100, standart 82), format (webp|jpeg|png|original, standart webp). Video 300 MB gacha (mp4/webm/mov). SVG/GIF/AVIF o'zgartirilmaydi.")
    public Map<String, Object> upload(@RequestPart(name = "file", required = false) MultipartFile file,
                                      @RequestParam(required = false) String maxWidth,
                                      @RequestParam(required = false) String quality,
                                      @RequestParam(required = false) String format) {
        String type = file == null || file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (file == null || file.isEmpty() || !(MediaService.IMAGE_TYPES.contains(type) || MediaService.VIDEO_TYPES.contains(type)))
            throw ApiException.badRequest("file_required_or_type_not_allowed");
        var options = new MediaService.Options(clamp(maxWidth, 2000, 64, 5000), clamp(quality, 82, 40, 100), format != null && FORMATS.contains(format) ? format : "webp");
        return service.process(file, options);
    }

    @DeleteMapping("/{id}")
    @RequirePermission("media.delete")
    @Operation(summary = "Faylni diskdan va kutubxonadan o'chirish")
    public Map<String, Object> delete(@PathVariable long id) {
        if (!service.delete(id)) throw ApiException.notFound();
        return Map.of("ok", true);
    }
}
