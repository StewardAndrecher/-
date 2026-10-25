package com.campus.market.product;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductFeedCursorTest {
    String raw(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));}
    String token(){return new ProductFeedCursor(1700000000,42).encode("教材","BOOK");}
    @Test void roundTripsOpaquePositionWithChineseFilter(){assertEquals(new ProductFeedCursor(1700000000,42),ProductFeedCursor.decode(token(),"教材","BOOK"));}
    @Test void missingTokenStartsAtTheBeginning(){assertNull(ProductFeedCursor.decode("","",""));assertNull(ProductFeedCursor.decode(null,"",""));}
    @Test void bindsBothKeywordAndCategory(){assertThrows(InvalidProductException.class,()->ProductFeedCursor.decode(token(),"台灯","BOOK"));assertThrows(InvalidProductException.class,()->ProductFeedCursor.decode(token(),"教材","DAILY"));}
    @Test void rejectsMalformedAndOversizedTokens(){for(String value:new String[]{"???","a","a".repeat(257)})assertThrows(InvalidProductException.class,()->ProductFeedCursor.decode(value,"",""));}
    @Test void rejectsUnsupportedVersion(){String payload=new String(Base64.getUrlDecoder().decode(token()),StandardCharsets.US_ASCII);assertThrows(InvalidProductException.class,()->ProductFeedCursor.decode(raw(payload.replaceFirst("1\\|","2|")),"教材","BOOK"));}
    @Test void rejectsZeroAndUnsafeIds(){assertThrows(InvalidProductException.class,()->new ProductFeedCursor(1700000000,0).encode("",""));assertThrows(InvalidProductException.class,()->new ProductFeedCursor(1700000000,9007199254740992L).encode("",""));}
    @Test void rejectsOutOfTimestampRange(){assertThrows(InvalidProductException.class,()->new ProductFeedCursor(0,1).encode("",""));assertThrows(InvalidProductException.class,()->new ProductFeedCursor(2147483648L,1).encode("",""));}
    @Test void rejectsPaddingAndAlternativeRepresentations(){assertThrows(InvalidProductException.class,()->ProductFeedCursor.decode(token()+"=","教材","BOOK"));}
    @Test void rejectsExtraPayloadFields(){String value=new String(Base64.getUrlDecoder().decode(token()),StandardCharsets.US_ASCII)+"|extra";assertThrows(InvalidProductException.class,()->ProductFeedCursor.decode(raw(value),"教材","BOOK"));}
    @Test void contextDelimitersCannotChangeTheFilterBinding(){String value=new ProductFeedCursor(1700000000,42).encode("a|b","");assertThrows(InvalidProductException.class,()->ProductFeedCursor.decode(value,"a","BOOK"));}
}
