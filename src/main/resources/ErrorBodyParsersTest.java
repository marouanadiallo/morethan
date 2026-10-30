package lu.pgd.dri.saga.web.config.feign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lu.pgd.dri.saga.dto.exception.ErrorResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Les parseurs sont de simples fonctions JSON -> ErrorResponse : on les teste avec
 * des bodies réels (copiés des logs) et le même ObjectMapper que Spring Boot.
 */
class ErrorBodyParsersTest {

    // Comme Spring Boot : JavaTimeModule, mixin ProblemDetail, FAIL_ON_UNKNOWN_PROPERTIES=false
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Nested
    class Saga {

        // ⚠️ adapte le constructeur à ta version (FeignClientsProperties ou constante de préfixe)
        private final SameFormatErrorBodyParser parser = new SameFormatErrorBodyParser(objectMapper);

        @Test
        void supports_saga_clients_only() {
            assertThat(parser.supports("saga-service-task")).isTrue();
            assertThat(parser.supports("cronos-service")).isFalse();
        }

        @Test
        void parses_business_error() throws Exception {
            String body = """
                    {"timestamp":"2026-10-28T16:27:53.361Z","status":400,
                     "code":"TASK_STATUS_EXECUTED",
                     "error":"Une tâche au statut exécuté ne peut pas être modifiée"}""";

            ErrorResponse result = parser.parse(body);

            assertThat(result.getStatus()).isEqualTo(400);
            assertThat(result.getCode()).isEqualTo("TASK_STATUS_EXECUTED");
            assertThat(result.getError()).startsWith("Une tâche");
            assertThat(result.getErrors()).isNull();
        }

        @Test
        @SuppressWarnings("unchecked")
        void parses_validation_error_and_keeps_errors_map() throws Exception {
            String body = """
                    {"timestamp":"2026-10-29T12:23:07.673Z","status":400,
                     "errors":{"title":{"code":"must not be blank","message":"must not be blank","value":""}}}""";

            ErrorResponse result = parser.parse(body);

            assertThat(result.getCode()).isNull();
            Map<String, Object> title = (Map<String, Object>) result.getErrors().get("title");
            assertThat(title).containsEntry("code", "must not be blank");
        }

        @Test
        void ignores_unknown_fields() throws Exception {
            String body = """
                    {"status":400,"code":"X","newFieldAddedLater":true}""";

            assertThat(parser.parse(body).getCode()).isEqualTo("X");
        }

        @Test
        void throws_on_invalid_json() {
            assertThatThrownBy(() -> parser.parse("<html>Bad Gateway</html>"))
                    .isInstanceOf(JsonProcessingException.class);
        }
    }

    @Nested
    class Cronos {

        private final CronosServiceErrorBodyParser parser = new CronosServiceErrorBodyParser(objectMapper);

        @Test
        void parses_api_error_response_with_details() throws Exception {
            String body = """
                    {"timestamp":"2026-10-29T10:00:00","status":400,"error":"Bad Request",
                     "message":"Invalid case","path":"/cases","details":["number is required"]}""";

            ErrorResponse result = parser.parse(body);

            assertThat(result.getStatus()).isEqualTo(400);
            assertThat(result.getError()).isEqualTo("Invalid case : number is required");
        }
    }

    @Nested
    class Cecima {

        private final CecimaErrorBodyParser parser = new CecimaErrorBodyParser(objectMapper);

        @Test
        void parses_problem_detail_with_custom_code() throws Exception {
            String body = """
                    {"type":"about:blank","title":"Bad Request","status":422,
                     "detail":"Person not found","code":"PERSON_NOT_FOUND"}""";

            ErrorResponse result = parser.parse(body);

            assertThat(result.getStatus()).isEqualTo(422);
            assertThat(result.getCode()).isEqualTo("PERSON_NOT_FOUND");
            assertThat(result.getError()).isEqualTo("Person not found");
        }

        @Test
        void falls_back_to_title_when_no_detail() throws Exception {
            String body = """
                    {"title":"Bad Request","status":400}""";

            assertThat(parser.parse(body).getError()).isEqualTo("Bad Request");
        }
    }
}
