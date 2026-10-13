package com.campus.market.product;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;

// 第22～24课的回归用例验证原业务；为其PUT请求补齐第25课要求的当前版本。
// 第25课的冲突/版本校验用例直接构造请求，不使用本辅助类。
final class ProductTestRequests {
    static RequestBuilder withCurrentVersion(RequestBuilder builder, JdbcTemplate jdbc) {
        var request = builder.buildRequest(new MockServletContext());
        if (!"PUT".equals(request.getMethod()) || !(builder instanceof MockHttpServletRequestBuilder mutable)) return builder;
        try {
            var mapper = JsonMapper.builder().build();
            var body = mapper.readTree(new String(request.getContentAsByteArray(), StandardCharsets.UTF_8));
            if (!(body instanceof ObjectNode object) || object.has("version")) return builder;
            long id = Long.parseLong(request.getRequestURI().split("/")[3]);
            var values = jdbc.queryForList("SELECT version FROM products WHERE id=?", Long.class, id);
            object.put("version", values.isEmpty() ? 0L : values.get(0));
            mutable.content(mapper.writeValueAsString(object));
        } catch (RuntimeException ignored) {
            // 畸形JSON仍交给原接口返回400。
        }
        return builder;
    }
}
