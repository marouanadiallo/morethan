package lu.pgd.dri.saga.web.config.feign;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.RetryableException;
import feign.Target;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lu.pgd.dri.saga.dto.exception.ErrorResponse;
import lu.pgd.dri.saga.web.exception.ExternalSystemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test unitaire pur : aucune app Spring, aucun serveur.
 * On fabrique une feign.Response à la main et on vérifie ce que le decoder en fait.
 */
class ExternalSystemErrorDecoderTest {

    private static final String SYSTEM = "saga-service-task";
    private static final String METHOD_KEY = "TaskClient#putTask(Long,UpdateTaskRequest)";

    private ErrorBodyParser sagaParser;
    private ExternalSystemErrorDecoder decoder;

    @BeforeEach
    void setUp() {
        sagaParser = mock(ErrorBodyParser.class);
        when(sagaParser.supports(SYSTEM)).thenReturn(true);
        decoder = new ExternalSystemErrorDecoder(List.of(sagaParser));
    }

    @Test
    void should_wrap_400_and_keep_parsed_body() throws Exception {
        ErrorResponse parsed = ErrorResponse.builder().status(400).code("TASK_STATUS_EXECUTED").build();
        when(sagaParser.parse(anyString())).thenReturn(parsed);

        Exception result = decoder.decode(METHOD_KEY, response(SYSTEM, 400, "{\"code\":\"TASK_STATUS_EXECUTED\"}"));

        assertThat(result).isInstanceOf(ExternalSystemException.class);
        ExternalSystemException ex = (ExternalSystemException) result;
        assertThat(ex.getSystem()).isEqualTo(SYSTEM);
        assertThat(ex.getStatus()).isEqualTo(400);
        assertThat(ex.getRawBody()).contains("TASK_STATUS_EXECUTED");
        assertThat(ex.getParsedBody()).isSameAs(parsed);
    }

    @Test
    void should_keep_raw_body_when_no_parser_supports_system() {
        Exception result = decoder.decode("RnpmClient#get(Long)", response("rnpm", 400, "{\"foo\":\"bar\"}"));

        ExternalSystemException ex = (ExternalSystemException) result;
        assertThat(ex.getSystem()).isEqualTo("rnpm");
        assertThat(ex.getParsedBody()).isNull();
        assertThat(ex.getRawBody()).isEqualTo("{\"foo\":\"bar\"}");
    }

    @Test
    void should_fallback_to_null_when_parser_fails() throws Exception {
        when(sagaParser.parse(anyString())).thenThrow(new JsonProcessingException("boom") {});

        Exception result = decoder.decode(METHOD_KEY, response(SYSTEM, 400, "not json"));

        ExternalSystemException ex = (ExternalSystemException) result;
        assertThat(ex.getParsedBody()).isNull();
        assertThat(ex.getRawBody()).isEqualTo("not json");
    }

    @Test
    void should_not_call_parser_when_body_is_empty() {
        Exception result = decoder.decode(METHOD_KEY, response(SYSTEM, 400, null));

        assertThat(((ExternalSystemException) result).getParsedBody()).isNull();
    }

    @Test
    void should_let_retryable_exception_through() {
        // ErrorDecoder.Default renvoie une RetryableException si Retry-After est présent
        Response response = response(SYSTEM, 503, "{}", Map.of("Retry-After", List.of("1")));

        Exception result = decoder.decode(METHOD_KEY, response);

        assertThat(result).isInstanceOf(RetryableException.class);
    }

    // ---------- helpers ----------

    private static Response response(String system, int status, String body) {
        return response(system, status, body, Map.of());
    }

    private static Response response(String system, int status, String body,
                                     Map<String, Collection<String>> headers) {
        // feignTarget porte le nom du @FeignClient, lu par systemOf()
        RequestTemplate template = new RequestTemplate();
        template.feignTarget(new Target.HardCodedTarget<>(Object.class, system, "http://localhost"));

        Request request = Request.create(Request.HttpMethod.PUT, "http://localhost/tasks/1",
                Map.of(), null, UTF_8, template);

        Response.Builder builder = Response.builder()
                .status(status)
                .reason("reason")
                .request(request)
                .headers(headers);
        if (body != null) {
            builder.body(body, UTF_8);
        }
        return builder.build();
    }
}
