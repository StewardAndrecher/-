package com.campus.market.product;

import java.util.ArrayList;
import tools.jackson.core.*;
import tools.jackson.databind.*;

public class ProductLookupRequestDeserializer extends ValueDeserializer<ProductLookupModels.Request> {
    @Override public ProductLookupModels.Request deserialize(JsonParser parser,DeserializationContext context) {
        if(parser.currentToken()!=JsonToken.START_OBJECT)return invalid(context);
        var ids=new ArrayList<Long>();boolean seen=false;
        while(parser.nextToken()!=JsonToken.END_OBJECT) {
            if(parser.currentToken()!=JsonToken.PROPERTY_NAME||!"productIds".equals(parser.currentName())||seen)return invalid(context);
            seen=true;if(parser.nextToken()!=JsonToken.START_ARRAY)return invalid(context);
            while(parser.nextToken()!=JsonToken.END_ARRAY) {
                if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT||ids.size()>=20)return invalid(context);
                ids.add(parser.getLongValue());
            }
        }
        return new ProductLookupModels.Request(ids);
    }
    private static ProductLookupModels.Request invalid(DeserializationContext context) {
        return context.reportInputMismatch(ProductLookupModels.Request.class,"仅允许productIds：1至20个不重复JSON正整数商品编号");
    }
}
