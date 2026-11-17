package com.example.orders.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Serves the web app (built from ../order-platform-web into static/) on the same origin as the API.
 *
 * The app has its own routes (/shop, /admin/orders/…), so any path that is not a file and not
 * part of the API is answered with index.html and the app shows the right screen, also after a
 * reload. Built files carry a content hash in their name and are cached for a year; index.html is
 * always checked again, so a new release reaches users at once.
 */
@Configuration
public class WebAppConfig implements WebMvcConfigurer {

    /** Paths the web app must never answer: a missing API route must stay a 404, not become the app. */
    static final List<String> SERVER_PATHS = List.of("api/", "actuator/", "lra-participant/", "graphql", "graphiql");

    private static final Resource INDEX = new ClassPathResource("static/index.html");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource file = location.createRelative(resourcePath);
                        if (!resourcePath.isEmpty() && file.exists() && file.isReadable()) {
                            return file;
                        }
                        return isAppRoute(resourcePath) && INDEX.exists() ? INDEX : null;
                    }
                });
    }

    /** A screen of the app such as "admin/orders/ORD-1", not a missing file ("logo.png") or a server path. */
    static boolean isAppRoute(String path) {
        if (SERVER_PATHS.stream().anyMatch(path::startsWith)) {
            return false;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        // Order ids contain dots only rarely; files always have an extension such as .js or .png
        return !last.matches(".*\\.[A-Za-z0-9]{1,5}$");
    }
}
