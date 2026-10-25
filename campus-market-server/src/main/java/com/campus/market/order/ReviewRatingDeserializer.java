package com.campus.market.order;

import tools.jackson.core.*;
import tools.jackson.databind.*;

public class ReviewRatingDeserializer extends ValueDeserializer<Long> {
    @Override public Long deserialize(JsonParser parser, DeserializationContext context) {
        if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(Long.class,"评分必须为1至5的JSON整数");
        }
        return parser.getLongValue();
    }
}
