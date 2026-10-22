package pe.utp.epptrack.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Cliente mínimo de la API de mensajes de Claude (Anthropic). La clave llega por variable de entorno. */
@Component
public class ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);
    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String apiKey;
    private final String modelo;

    public ClaudeClient(ObjectMapper json,
                        @Value("${app.asistente.api-key:}") String apiKey,
                        @Value("${app.asistente.modelo}") String modelo) {
        this.json = json;
        this.apiKey = apiKey;
        this.modelo = modelo;
    }

    public boolean isConfigurado() { return !apiKey.isBlank(); }

    /** @param mensajes lista alternada de {@code {role, content}} que empieza con el usuario */
    public String responder(String sistema, List<Map<String, String>> mensajes) {
        try {
            String cuerpo = json.writeValueAsString(Map.of(
                    "model", modelo, "max_tokens", 1024, "system", sistema, "messages", mensajes));
            HttpRequest req = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(Duration.ofSeconds(45))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("La API de Claude respondió {}", resp.statusCode());
                throw new ReglaNegocioException("El asistente no pudo responder en este momento. Intenta de nuevo.");
            }
            JsonNode texto = json.readTree(resp.body()).path("content").path(0).path("text");
            return texto.asText("").trim();
        } catch (IOException e) {
            log.warn("Error al consultar la API de Claude: {}", e.getMessage());
            throw new ReglaNegocioException("El asistente no pudo responder en este momento. Intenta de nuevo.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ReglaNegocioException("La consulta fue interrumpida.");
        }
    }
}
