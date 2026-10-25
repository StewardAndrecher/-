package com.campus.market.governance;
import java.text.Normalizer;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class ContentRules {
    private final List<String> terms;
    public ContentRules(@Value("${market.content.blocked-terms:刷单返利;代开发票;银行卡四件套}") String raw) {
        terms=Arrays.stream(raw.split(";")).map(ContentRules::fold).filter(v->!v.isEmpty()).distinct().toList();
        if(terms.size()>200||terms.stream().anyMatch(t->t.length()>50))throw new IllegalArgumentException("内容规则配置无效");
    }
    private static String fold(String text) {
        String value=Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return value.replaceAll("[\\s\\p{Z}\\p{Cf}]","");
    }
    public void requireAllowed(String... values) {
        String text=String.join("\n",Arrays.stream(values).map(v->v==null?"":v).toList());
        if(text.length()>4000)throw new PlatformException(400,"CONTENT_TOO_LONG","内容过长");
        if(text.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'||c>=0xd800&&c<=0xdfff))
            throw new PlatformException(400,"CONTENT_INVALID","内容包含无效字符");
        String folded=fold(text);
        if(terms.stream().anyMatch(folded::contains))throw new PlatformException(422,"CONTENT_REVIEW_REQUIRED","内容命中平台风险规则，请检查并修改；如有疑问可联系平台复核");
    }
}
