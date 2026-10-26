package io.mosip.certify.erl;

import java.time.LocalDateTime;
import java.time.format.*;
import java.util.*;
import io.mosip.certify.api.exception.LookupValidationException;

public record ErlLookupRequest(String vehicleNumber, LocalDateTime issuedDate) {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);

    public static ErlLookupRequest parse(Map<String, Object> claims) {
        if (claims == null || !claims.keySet().equals(Set.of("vehicle_number", "issued_date")))
            throw new LookupValidationException("ERL lookup requires exactly vehicle_number and issued_date");
        if (!(claims.get("vehicle_number") instanceof String vehicle) || vehicle.isBlank() || vehicle.length() > 50
                || vehicle.codePoints().anyMatch(Character::isISOControl))
            throw new LookupValidationException("Invalid vehicle_number");
        if (!(claims.get("issued_date") instanceof String issued)
                || !issued.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"))
            throw new LookupValidationException("issued_date must be a local timestamp with seconds");
        try { return new ErlLookupRequest(vehicle, LocalDateTime.parse(issued, TIMESTAMP)); }
        catch (DateTimeParseException e) { throw new LookupValidationException("Invalid issued_date"); }
    }
}
