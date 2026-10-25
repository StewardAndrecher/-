package com.campus.market.favorite;

import com.campus.market.auth.SessionService;
import com.campus.market.product.InvalidProductException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class FavoriteBatchService {
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    public FavoriteBatchService(JdbcTemplate jdbc,SessionService sessions){this.jdbc=jdbc;this.sessions=sessions;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public FavoriteBatchModels.Receipt remove(FavoriteBatchModels.Request request,String authorization) {
        long user=sessions.currentUser(authorization).id();
        if(request==null||request.productIds()==null||request.productIds().isEmpty()||request.productIds().size()>20)throw invalid();
        var sorted=new TreeSet<Long>();
        for(Long id:request.productIds())if(id==null||id<1||id>9007199254740991L||!sorted.add(id))throw invalid();
        // Capture caller order for the receipt; sorted IDs and bound parameters keep mutation scope explicit.
        var requested=List.copyOf(request.productIds());
        String placeholders=String.join(",",Collections.nCopies(sorted.size(),"?"));
        var parameters=new ArrayList<Object>();parameters.add(user);parameters.addAll(sorted);
        int removed=jdbc.update("DELETE FROM product_favorites WHERE user_id=? AND product_id IN ("+placeholders+")",parameters.toArray());
        if(removed<0||removed>requested.size())throw new IllegalStateException("收藏取消数量异常");
        return new FavoriteBatchModels.Receipt(user,requested,removed);
    }
    private static InvalidProductException invalid(){return new InvalidProductException("一次请选择1至20个不重复的有效商品编号");}
}
