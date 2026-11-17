package uz.ant.api.config;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import uz.ant.api.security.AuthInterceptor;
import uz.ant.api.security.RateLimitInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AntProperties props;
    private final AuthInterceptor auth;
    private final RateLimitInterceptor rateLimit;

    public WebConfig(AntProperties props, AuthInterceptor auth, RateLimitInterceptor rateLimit) {
        this.props = props;
        this.auth = auth;
        this.rateLimit = rateLimit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimit).addPathPatterns("/api/**");
        registry.addInterceptor(auth).addPathPatterns("/api/admin/**", "/api/me", "/api/me/**");
    }

    /** Dev: Vite frontend (localhost:5173) dan keladigan so'rovlarga ruxsat. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
        registry.addMapping("/uploads/**")
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "HEAD");
    }

    /** Bosh sahifa: "/" bo'sh resurs yo'li bo'lgani uchun resurs handler uni bermaydi — index.html ga yo'naltiramiz. */
    @Override
    public void addViewControllers(org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry) {
        if (Files.isDirectory(props.frontendPath())) registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Yuklangan fayllar (rasm/video): o'zgarmas nomlar, uzoq kesh. Xavfsizlik sarlavhalari: RequestFilters.
        if (!props.storage().isS3()) registry.addResourceHandler("/uploads/**")
                .addResourceLocations(props.uploadDir().toUri().toString())
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .resourceChain(true).addResolver(new SafeResolver(false));

        // Production: build qilingan frontend (SPA — noma'lum yo'llar index.html ga tushadi)
        if (Files.isDirectory(props.frontendPath())) {
            String dist = props.frontendPath().toUri().toString();
            registry.addResourceHandler("/assets/**").addResourceLocations(dist + "assets/")
                    .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
            registry.addResourceHandler("/**").addResourceLocations(dist)
                    .setCacheControl(CacheControl.noCache())
                    .resourceChain(true).addResolver(new SafeResolver(true));
        }
    }

    /** Nuqta bilan boshlangan fayllarni (dotfiles) bermaydi; /api va /uploads ga SPA fallback qo'llamaydi. */
    static final class SafeResolver extends PathResourceResolver {
        private final boolean spa;

        SafeResolver(boolean spa) { this.spa = spa; }

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            for (String seg : resourcePath.split("/")) if (seg.startsWith(".")) return null;
            if (spa && (resourcePath.startsWith("api/") || resourcePath.equals("api") || resourcePath.startsWith("uploads/"))) return null;
            Resource r = location.createRelative(resourcePath);
            if (r.exists() && r.isReadable() && !resourcePath.isEmpty() && !resourcePath.endsWith("/")) return r;
            return spa ? location.createRelative("index.html") : null;
        }
    }
}
