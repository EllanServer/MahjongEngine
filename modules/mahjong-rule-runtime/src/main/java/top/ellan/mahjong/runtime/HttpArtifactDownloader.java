package top.ellan.mahjong.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;

/** Streaming HTTPS artifact downloader with signed size enforcement. */
public final class HttpArtifactDownloader implements ArtifactDownloader {
    private final HttpClient client;

    public HttpArtifactDownloader(HttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void download(RulePackRegistryEntry entry, Path target)
            throws IOException, InterruptedException, RulePackException {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(target, "target");
        HttpRequest request =
                HttpRequest.newBuilder(entry.artifactUri())
                        .timeout(Duration.ofMinutes(2))
                        .header("Accept", "application/java-archive, application/octet-stream")
                        .GET()
                        .build();
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new RulePackException("Artifact HTTP status " + response.statusCode());
        }
        long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declaredLength >= 0 && declaredLength != entry.sizeBytes()) {
            response.body().close();
            throw new RulePackException("Artifact Content-Length differs from signed registry");
        }
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = response.body();
                OutputStream output =
                        Files.newOutputStream(
                                target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > entry.sizeBytes() || total > 64L * 1024 * 1024) {
                    throw new RulePackException("Artifact exceeds signed size");
                }
                output.write(buffer, 0, read);
            }
        }
        if (total != entry.sizeBytes()) {
            throw new RulePackException("Artifact byte count differs from signed registry");
        }
    }
}
