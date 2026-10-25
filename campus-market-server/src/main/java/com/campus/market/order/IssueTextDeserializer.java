package com.campus.market.order;
import tools.jackson.core.*;
import tools.jackson.databind.*;
public class IssueTextDeserializer extends ValueDeserializer<String> {
    @Override public String deserialize(JsonParser parser,DeserializationContext context) {
        if(parser.currentToken()!=JsonToken.VALUE_STRING)return context.reportInputMismatch(String.class,"问题类型和说明必须为JSON字符串");
        return parser.getString();
    }
}
