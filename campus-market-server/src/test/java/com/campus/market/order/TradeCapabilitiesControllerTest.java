package com.campus.market.order;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
class TradeCapabilitiesControllerTest {
    @Test void simulatedModeIsExplicitAndNeverClaimsRealPayment(){var c=new TradeCapabilitiesController(true).capabilities();var body=c.getBody();assertNotNull(body);assertEquals("SIMULATED",body.paymentMode());assertTrue(body.orderCreationEnabled());assertTrue(body.simulatedPaymentEnabled());assertTrue(body.message().contains("不发生真实扣款"));assertEquals("no-store",c.getHeaders().getCacheControl());}
    @Test void unavailableChannelBlocksNewOrdersAndSimulation(){var body=new TradeCapabilitiesController(false).capabilities().getBody();assertNotNull(body);assertEquals("UNAVAILABLE",body.paymentMode());assertFalse(body.orderCreationEnabled());assertFalse(body.simulatedPaymentEnabled());}
    @Test void readOnlyEndpointWorksWithoutSessionAndDoesNotSetCookie()throws Exception{var mvc=MockMvcBuilders.standaloneSetup(new TradeCapabilitiesController(false)).build();var r=mvc.perform(get("/api/trade-capabilities").header("Authorization","Bearer not-a-session")).andReturn().getResponse();assertEquals(200,r.getStatus());assertNull(r.getHeader("Set-Cookie"));assertEquals("no-store",r.getHeader("Cache-Control"));assertTrue(r.getContentAsString().contains("\"orderCreationEnabled\":false"));}
}
