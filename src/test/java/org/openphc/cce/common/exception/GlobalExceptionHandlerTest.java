package org.openphc.cce.common.exception;

import ca.uhn.fhir.parser.DataFormatException;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.common.fhir.UnsupportedExpressionLanguageException;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The advice is shared by every service, so the status each failure maps to is a contract of this
 * library rather than of any one controller.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void missingEntityBecomes404() {
        ResponseEntity<ErrorResponse> response =
                handler.handleEntityNotFound(new EntityNotFoundException("Protocol definition not found: 42"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, response.getBody().getStatus());
        assertEquals("Not Found", response.getBody().getError());
        assertEquals("Protocol definition not found: 42", response.getBody().getMessage());
    }

    @Test
    void badArgumentBecomes400() {
        ResponseEntity<ErrorResponse> response =
                handler.handleIllegalArgument(new IllegalArgumentException("Invalid canonical format"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid canonical format", response.getBody().getMessage());
    }

    @Test
    void illegalStateBecomes409() {
        // Used for "this exists but not in a state that permits the operation" — a retired definition
        // being retired again, or a delete blocked by existing instances.
        ResponseEntity<ErrorResponse> response =
                handler.handleIllegalState(new IllegalStateException("Already retired"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Conflict", response.getBody().getError());
        assertEquals("Already retired", response.getBody().getMessage());
    }

    @Test
    void validationFailureListsEveryOffendingField() throws Exception {
        BindingResult result = new BeanPropertyBindingResult(new Payload(), "payload");
        result.rejectValue("definitionJson", "NotBlank", "must not be blank");
        result.rejectValue("version", "NotNull", "must not be null");
        Method method = Payload.class.getDeclaredMethod("handle", String.class);
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(new MethodParameter(method, 0), result);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        String message = response.getBody().getMessage();
        assertTrue(message.contains("definitionJson: must not be blank"), message);
        assertTrue(message.contains("version: must not be null"), message);
        assertTrue(message.contains("; "), "field errors are joined, not truncated to the first: " + message);
    }

    @Test
    void unsupportedExpressionLanguageBecomes422() {
        // The payload is well-formed and the request understood; the definition asks for an evaluator
        // this deployment does not have, which is a semantic rejection rather than a bad request.
        ResponseEntity<ErrorResponse> response = handler.handleUnsupportedExpression(
                new UnsupportedExpressionLanguageException("text/cql"));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertTrue(response.getBody().getMessage().contains("text/cql"));
    }

    @Test
    void malformedFhirBecomes422() {
        ResponseEntity<ErrorResponse> response =
                handler.handleFhirValidation(new DataFormatException("Unknown resource type"));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertEquals("Unknown resource type", response.getBody().getMessage());
    }

    @Test
    void unexpectedFailureBecomes500WithoutLeakingItsDetail() {
        ResponseEntity<ErrorResponse> response = handler.handleGeneric(
                new RuntimeException("jdbc url jdbc:postgresql://host/ccedb?password=hunter2"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("An unexpected error occurred", response.getBody().getMessage());
        assertFalse(response.getBody().getMessage().contains("hunter2"),
                "internal detail must stay in the log, not the response body");
    }

    @Test
    void everyResponseCarriesTheCorrelationIdWhenOneIsInScope() {
        MDC.put("correlationId", "corr-123");

        assertEquals("corr-123",
                handler.handleIllegalArgument(new IllegalArgumentException("x")).getBody().getCorrelationId());
    }

    @Test
    void correlationIdIsOmittedWhenAbsent() {
        // NON_NULL serialization drops the field entirely rather than emitting a null.
        ErrorResponse body = handler.handleGeneric(new RuntimeException("x")).getBody();

        assertNull(body.getCorrelationId());
        assertNotNull(body.getTimestamp());
    }

    /** Carrier for a real BindingResult target and a method to hang a MethodParameter off. */
    private static class Payload {
        private String definitionJson;
        private String version;

        public String getDefinitionJson() {
            return definitionJson;
        }

        public String getVersion() {
            return version;
        }

        @SuppressWarnings("unused")
        void handle(String body) {
        }
    }
}
