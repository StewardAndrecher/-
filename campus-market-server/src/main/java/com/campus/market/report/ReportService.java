package com.campus.market.report;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.dao.DuplicateKeyException;
import com.campus.market.auth.SessionService;
import com.campus.market.admin.AdminAccessService;
import com.campus.market.product.*;
import static com.campus.market.report.ReportModels.*;
@Service
public class ReportService {
    private final com.campus.market.notification.NotificationEvents notifications;
    private final ReportRepository reports;private final ProductRepository products;private final ProductModerationRepository reviews;
    private final ProductModerationService moderation;private final SessionService sessions;private final AdminAccessService admins;
    public ReportService(ReportRepository reports,ProductRepository products,ProductModerationRepository reviews,ProductModerationService moderation,SessionService sessions,AdminAccessService admins,com.campus.market.notification.NotificationEvents notifications){this.notifications=notifications;this.reports=reports;this.products=products;this.reviews=reviews;this.moderation=moderation;this.sessions=sessions;this.admins=admins;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt create(long productId,Create request,String auth){
        long actor=sessions.currentUser(auth).id();id(productId);if(request==null)throw bad("请填写举报内容");
        String category=request.category(),reason=text(request.reason(),300),key=key(request.requestId());
        if(category==null||!Set.of("MISLEADING","PROHIBITED","SPAM","OTHER").contains(category))throw bad("举报类型不正确");
        var product=products.findByIdForUpdate(productId).orElseThrow(()->missing());
        var old=reports.request(actor,key);
        if(old.isPresent()) {var t=old.get().ticket();if(t.productId()!=productId||!t.category().equals(category)||!t.reason().equals(reason))throw conflict("请求标识已用于不同举报");return new Receipt(t,true);}
        if(product.status()!=ProductStatus.ON_SALE)throw missing();
        if(product.seller()!=null&&product.seller().id()==actor)throw new ReportException(403,"不能举报自己发布的商品");
        if(reports.pending(productId,actor))throw conflict("你已举报此商品，请在我的举报查看处理进度");
        try {long report=reports.insert(actor,product,category,reason,key);return new Receipt(stored(report,false).ticket(),false);}
        catch(DuplicateKeyException e){throw conflict("举报已提交或请求标识已使用，请刷新我的举报");}
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page mine(int page,int size,String state,String auth){long actor=sessions.currentUser(auth).id();return page(actor,page,size,state);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Ticket mineDetail(long reportId,String auth){long actor=sessions.currentUser(auth).id();var row=stored(reportId,false);if(row.reporterId()!=actor)throw missing();return row.ticket();}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page adminPage(int page,int size,String state,String auth){admins.requireAdmin(auth);return page(null,page,size,state);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public AdminDetail adminDetail(long reportId,String auth){admins.requireAdmin(auth);var row=stored(reportId,false);return new AdminDetail(row.ticket(),reports.reporter(row.reporterId()),reviews.item(row.ticket().productId()));}
    private Page page(Long actor,int page,int size,String state){
        if(page<1||size<1||size>20||state==null||!Set.of("","OPEN","DISMISSED","ACTIONED").contains(state))throw bad("举报筛选条件不正确");
        return new Page(reports.page(actor,state,size,((long)page-1)*size),reports.count(actor,state),page,size);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt resolve(long reportId,Resolve request,String auth){
        long actor=admins.requireAdmin(auth).id();if(request==null)throw bad("请填写处理结果");
        String action=request.action(),reason=text(request.reason(),300),key=key(request.requestId());
        if(action==null||!Set.of("DISMISS","BLOCK").contains(action)||request.version()==null||request.version()<0||request.version()>1)throw bad("处理操作或工单版本不正确");
        if(action.equals("BLOCK")){if(request.productVersion()==null||request.productVersion()<0||request.productVersion()>=9_007_199_254_740_991L||reason.length()>200)throw bad("下架需要有效商品版本和2～200字符原因");}
        else if(request.productVersion()!=null)throw bad("驳回举报不应提交商品版本");
        var initial=stored(reportId,false);long productId=initial.ticket().productId();
        products.findByIdForUpdate(productId).orElseThrow(()->missing()); // Keep product -> ticket -> order lock order consistent.
        var row=stored(reportId,true);var ticket=row.ticket();
        if(!ticket.status().equals("OPEN")){
            String expected=action.equals("BLOCK")?"ACTIONED":"DISMISSED";
            if(Objects.equals(row.resolverId(),actor)&&key.equals(row.resolutionRequestId())&&ticket.status().equals(expected)&&reason.equals(ticket.resolution())&&request.version()==0&&Objects.equals(request.productVersion(),row.expectedProductVersion()))return new Receipt(ticket,true);
            throw conflict("举报已被处理，请刷新查看结果");
        }
        if(request.version()!=ticket.version())throw conflict("举报状态已更新，请刷新");
        Long event=null;
        if(action.equals("BLOCK")){
            var result=moderation.adminAction(productId,new ProductModerationModels.Action("BLOCK",reason,request.productVersion(),key),auth);
            if(result.replayed())throw conflict("该请求标识已用于其他审核操作，请刷新核对");
            event=result.detail().item().moderation().lastEventId();
        }
        try {if(reports.resolve(reportId,actor,action.equals("BLOCK")?"ACTIONED":"DISMISSED",reason,key,request.productVersion(),event)!=1)throw conflict("举报状态已更新，请刷新");}
        catch(DuplicateKeyException e){throw conflict("请求标识已用于其他举报处理");}
        notifications.report(reportId,row.reporterId(),action,ticket.snapshot().title(),reason);
        return new Receipt(stored(reportId,false).ticket(),false);
    }
    private Stored stored(long id,boolean lock){id(id);return reports.find(id,lock).orElseThrow(()->missing());}
    private void id(long value){if(value<1||value>9_007_199_254_740_991L)throw bad("编号不正确");}
    private String key(String value){try{String key=UUID.fromString(value).toString();if(!key.equals(value))throw new IllegalArgumentException();return key;}catch(IllegalArgumentException|NullPointerException e){throw bad("请求标识必须为规范UUID");}}
    private String text(String value,int max){String s=value==null?"":value.strip();if(s.length()<2||s.length()>max||s.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw bad("说明需要2～"+max+"个字符");return s;}
    private ReportException bad(String message){return new ReportException(400,message);}
    private ReportException missing(){return new ReportException(404,"举报或商品不存在或无权访问");}
    private ReportException conflict(String message){return new ReportException(409,message);}
}
