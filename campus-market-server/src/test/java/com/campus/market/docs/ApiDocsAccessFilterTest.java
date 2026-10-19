package com.campus.market.docs;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
class ApiDocsAccessFilterTest {
    MockHttpServletResponse run(boolean enabled,String path,String method,String peer,String host,String origin) throws Exception {
        var request=new MockHttpServletRequest(method,path);request.setRemoteAddr(peer);request.setServerName(host);request.addHeader("Host",host+":8080");
        if(origin!=null)request.addHeader("Origin",origin);
        var response=new MockHttpServletResponse();
        new ApiDocsAccessFilter(enabled).doFilter(request,response,(q,r)->((jakarta.servlet.http.HttpServletResponse)r).setStatus(202));
        return response;
    }
    @Test void disabledBlocksEveryDocumentAndAssetAlias()throws Exception {
        for(String p:new String[]{"/docs.html","/api-docs","/api-docs.yaml","/api-docs/swagger-config","/swagger-ui/index.html","/swagger-ui.html","/v3/api-docs","/webjars/swagger-ui/swagger-ui.js"})
            assertEquals(404,run(false,p,"GET","127.0.0.1","localhost",null).getStatus(),p);
    }
    @Test void allowsLocalGetAndHead()throws Exception {for(String method:new String[]{"GET","HEAD"})assertEquals(202,run(true,"/api-docs",method,"127.0.0.1","localhost",null).getStatus());}
    @Test void allowsIpv6Loopback()throws Exception {assertEquals(202,run(true,"/api-docs","GET","::1","[::1]",null).getStatus());}
    @Test void rejectsLanPeer()throws Exception {assertEquals(403,run(true,"/api-docs","GET","192.168.1.2","localhost",null).getStatus());}
    @Test void rejectsForeignHost()throws Exception {assertEquals(403,run(true,"/api-docs","GET","127.0.0.1","evil.example",null).getStatus());}
    @Test void validatesOrigin()throws Exception {
        assertEquals(403,run(true,"/api-docs","GET","127.0.0.1","localhost","https://evil.example").getStatus());
        assertEquals(202,run(true,"/api-docs","GET","127.0.0.1","localhost","http://localhost:8080").getStatus());
    }
    @Test void rejectsMutationMethods()throws Exception {var r=run(true,"/api-docs","POST","127.0.0.1","localhost",null);assertEquals(405,r.getStatus());assertEquals("GET, HEAD",r.getHeader("Allow"));}
    @Test void doesNotAffectBusinessRequests()throws Exception {for(boolean enabled:new boolean[]{true,false})assertEquals(202,run(enabled,"/api/products","POST","192.168.1.2","campus.example",null).getStatus());}
    @Test void disablesBrowserCaching()throws Exception {var r=run(true,"/api-docs","GET","127.0.0.1","localhost",null);assertEquals("no-store",r.getHeader("Cache-Control"));assertEquals("nosniff",r.getHeader("X-Content-Type-Options"));}
    @Test void ignoresSpoofedForwardedHeadersAndHandlesContextPath()throws Exception {
        var q=new MockHttpServletRequest("GET","/campus/api-docs");q.setContextPath("/campus");q.setRemoteAddr("192.168.1.2");q.setServerName("localhost");q.addHeader("X-Forwarded-For","127.0.0.1");
        var r=new MockHttpServletResponse();new ApiDocsAccessFilter(true).doFilter(q,r,(a,b)->fail("Forwarded header must not bypass guard"));assertEquals(403,r.getStatus());
    }
}
