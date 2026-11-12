package io.mosip.certify.core.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * Accepts only a JSON integer number that fits a Java int. Strings ("10"), fractions (1.5, 10.0) and
 * out-of-range numbers are rejected instead of being coerced. Applied to individual fields so the coercion
 * rules of other endpoints stay unchanged.
 */
public class StrictIntegerDeserializer extends JsonDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token != JsonToken.VALUE_NUMBER_INT) {
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }
        try {
            return parser.getIntValue();
        } catch (IOException e) {
            return (Integer) context.handleWeirdNumberValue(Integer.class, parser.getNumberValue(), "not a valid integer");
        }
    }
}
