package com.campus.market.notification;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class NotificationReadIdDeserializer extends ValueDeserializer<Long> {
    @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
            return context.reportInputMismatch(Long.class, "通知编号必须为JSON整数");
        return parser.getLongValue();
    }
}
