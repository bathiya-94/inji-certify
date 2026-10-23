package io.mosip.certify;
import io.mosip.certify.advice.ExceptionHandlerAdvice;
import java.util.Map;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.ServletWebRequest;
import static org.junit.Assert.*;

public class DependencyFailureAdviceTest {
    @Test public void sanitizesBothQueryAndTransactionOpeningFailures() {
        ExceptionHandlerAdvice advice = new ExceptionHandlerAdvice();
        ReflectionTestUtils.setField(advice, "selectedDataProvider", "RouterDataProviderPlugin");
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest("POST", "/v1/certify/issuance/credential"));
        for (Exception failure : new Exception[] {
                new org.springframework.dao.DataAccessResourceFailureException("secret SQL and credentials"),
                new org.springframework.transaction.CannotCreateTransactionException("secret datasource details")}) {
            var response = advice.handleExceptions(failure, request);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
            assertEquals(Map.of("error", "temporarily_unavailable"), response.getBody());
        }
    }
}
