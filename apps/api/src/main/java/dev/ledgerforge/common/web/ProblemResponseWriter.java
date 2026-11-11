package dev.ledgerforge.common.web;

import dev.ledgerforge.common.error.ErrorCode;
import dev.ledgerforge.common.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Writes problem+json responses from servlet filters, which run outside Spring MVC advice. */
@Component
public class ProblemResponseWriter {

    private final ObjectMapper objectMapper;

    public ProblemResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, ErrorCode code, String detail) throws IOException {
        write(response, code, detail, Map.of());
    }

    public void write(HttpServletResponse response, ErrorCode code, String detail, Map<String, Object> props)
            throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.problem(code, detail, props));
    }
}
