package org.elibanking.accountservice.dto;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.math.BigDecimal;

/**
 * Money in responses is a JSON string with at least two decimal places, such as "1500.00" (wallet-plan D3). Requests still
 * accept a number or a string. A value is never rounded: a value with more decimals is written as it is.
 */
public final class MoneyJson {

    private MoneyJson() {
    }

    public static String plain(BigDecimal amount) {
        return (amount.scale() < 2 ? amount.setScale(2) : amount).toPlainString();
    }

    /** The serializer named by {@code @JsonSerialize(using = MoneyJson.Serializer.class)} on a money field. */
    public static final class Serializer extends ValueSerializer<BigDecimal> {

        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(plain(value));
        }
    }
}
