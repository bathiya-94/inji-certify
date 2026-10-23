package io.mosip.certify.router;
import io.mosip.certify.api.spi.*;
import io.mosip.certify.api.exception.LookupValidationException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RouterDataProviderPluginTest {
    @Test void routesByCurrentCredentialMappingAndSendsOnlyLookupClaims() throws Exception {
        RoutingProperties properties = new RoutingProperties();
        properties.setCredentialMapping(new HashMap<>(Map.of("ERL", "erl", "OTHER", "other")));
        List<Map<String,Object>> received = new ArrayList<>();
        DataProviderRegistration erl = new DataProviderRegistration("erl", input -> {
            received.add(input); return new JSONObject(Map.of("result", "erl"));
        }, input -> { if (!input.containsKey("vehicle_number")) throw new LookupValidationException("Missing lookup"); });
        DataProviderRegistration other = new DataProviderRegistration("other", input -> new JSONObject(Map.of("result", "other")), input -> { });
        RouterDataProviderPlugin router = new RouterDataProviderPlugin(properties, List.of(erl, other));
        Map<String,Object> input = new HashMap<>(Map.of(LookupDataProviderPlugin.CREDENTIAL_CONFIGURATION_ID, "ERL",
            LookupDataProviderPlugin.LOOKUP, Map.of("vehicle_number", "TEST"), "provider", "other", "sub", "forged"));
        assertThat(router.fetchData(input).getString("result")).isEqualTo("erl");
        assertThat(received.getFirst()).containsOnlyKeys("vehicle_number");
        properties.getCredentialMapping().put("ERL", "other");
        assertThat(router.fetchData(input).getString("result")).isEqualTo("other");
        assertThatThrownBy(() -> router.fetchData(Map.of("provider", "erl"))).isInstanceOf(LookupValidationException.class);
    }
    @Test void rejectsMissingRoutesDuplicatesAndInvalidLookup() {
        RoutingProperties properties = new RoutingProperties();
        properties.setCredentialMapping(Map.of("ERL", "missing"));
        assertThatThrownBy(() -> new RouterDataProviderPlugin(properties, List.of())).isInstanceOf(LookupValidationException.class);
        properties.setCredentialMapping(Map.of("ERL", "erl"));
        DataProviderRegistration registration = new DataProviderRegistration("erl", input -> new JSONObject(), input -> {
            throw new LookupValidationException("Invalid lookup");
        });
        assertThatThrownBy(() -> new RouterDataProviderPlugin(properties, List.of(registration, registration)))
            .isInstanceOf(IllegalStateException.class);
        RouterDataProviderPlugin router = new RouterDataProviderPlugin(properties, List.of(registration));
        assertThatThrownBy(() -> router.validateLookup("ERL", Map.of())).isInstanceOf(LookupValidationException.class);
        assertThatThrownBy(() -> router.validateLookup("OTHER", Map.of())).isInstanceOf(LookupValidationException.class);
    }
}
