package top.ellan.mahjong.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** HTTPS registry client with strict status and payload limits. */
public final class HttpRegistrySource implements RegistrySource {
    private final HttpClient client;
    private final URI registryUri;

    public HttpRegistrySource(HttpClient client, URI registryUri) {
        this.client = Objects.requireNonNull(client, "client");
        this.registryUri = Objects.requireNonNull(registryUri, "registryUri");
        if (!"https".equalsIgnoreCase(registryUri.getScheme())) {
            throw new IllegalArgumentException("Registry URL must use HTTPS");
        }
    }

    @Override
    public byte[] fetch() throws IOException, InterruptedException, RulePackException {
        HttpRequest request =
                HttpRequest.newBuilder(registryUri)
                        .timeout(Duration.ofSeconds(20))
                        .header("Accept", "application/json")
                        .GET()
                        .build();
        HttpResponse<byte[]> response =
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new RulePackException("Registry HTTP status " + response.statusCode());
        }
        if (response.body().length > 1_500_000) {
            throw new RulePackException("Registry response exceeds 1.5 MiB");
        }
        return response.body();
    }
}
