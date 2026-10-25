package com.campus.market.product;
import com.campus.market.auth.SessionService;
import com.campus.market.governance.ContentRules;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class ProductQualityService {
    public record Advice(String code,String message){}
    public record Result(boolean canPublish,List<Advice> suggestions,String notice){}
    private final ProductService products;private final SessionService sessions;private final ContentRules rules;
    public ProductQualityService(ProductService products,SessionService sessions,ContentRules rules){this.products=products;this.sessions=sessions;this.rules=rules;}
    @Transactional public Result check(CreateProductRequest input,String auth){
        var user=sessions.currentUser(auth);var value=products.normalizeCreateProduct(input,user.id());
        rules.requireAllowed(value.title(),value.description(),value.details().handoverLocation());
        var advice=new ArrayList<Advice>();
        if(value.description().codePointCount(0,value.description().length())<20)advice.add(new Advice("DESCRIPTION","建议补充使用情况、瑕疵和配件，描述至少20字"));
        if(value.details().imageIds().isEmpty())advice.add(new Advice("IMAGES","建议上传清晰的实物图片"));
        if("UNSPECIFIED".equals(value.details().campusCode()))advice.add(new Advice("CAMPUS","建议填写校区，方便同学筛选"));
        if("UNSPECIFIED".equals(value.details().conditionCode()))advice.add(new Advice("CONDITION","建议如实填写成色"));
        if(value.details().handoverLocation().isEmpty())advice.add(new Advice("HANDOVER","建议填写公共交接地点"));
        return new Result(true,List.copyOf(advice),"基础格式、图片归属与文本规则检查通过；建议项不阻止发布，也不代表平台担保或审核通过。");
    }
}
