package com.campus.market.media;
import com.campus.market.auth.SessionService;
import com.campus.market.admin.AdminAccessService;
import com.campus.market.governance.PlatformException;
import java.util.*;
import java.security.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.transaction.*;
@Service
public class ImageLibraryService {
    public record Image(String id,int width,int height,long bytes,boolean sizeKnown,String createdAt,String trashedAt,long version,boolean referenced){}
    public record Library(long userId,List<Image> items,int page,int totalPages,long total,long chargedBytes,long quotaBytes,long count,int maxCount,String scope){}
    public record Change(Long version){}
    public record Candidate(String id,long version,long bytes){}
    public record Cleanup(List<Candidate> candidates,int retentionDays,String storage,String note){}
    public record Purge(List<Candidate> images){}
    private final JdbcTemplate jdbc;private final SessionService sessions;private final AdminAccessService admins;private final ImageObjectStore store;private final TransactionTemplate afterCommit;
    private final long quota;private final int countLimit;
    public ImageLibraryService(JdbcTemplate jdbc,SessionService sessions,AdminAccessService admins,ImageObjectStore store,PlatformTransactionManager tx,@Value("${market.images.quota-bytes:524288000}")long quota,@Value("${market.images.max-count:200}")int countLimit){
        this.jdbc=jdbc;this.sessions=sessions;this.admins=admins;this.store=store;this.quota=quota;this.countLimit=countLimit;
        if(quota<10485760||quota>10737418240L||countLimit<6||countLimit>10000)throw new IllegalArgumentException("图片配额无效");
        afterCommit=new TransactionTemplate(tx);afterCommit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private long[] usage(long user){return jdbc.queryForObject("SELECT COUNT(*),COALESCE(SUM(COALESCE(s.byte_size,10485760)),0) FROM product_images i LEFT JOIN image_storage_records s ON s.image_id=i.id WHERE i.owner_id=? AND (s.physical_deleted IS NULL OR s.physical_deleted=FALSE)",(r,n)->new long[]{r.getLong(1),r.getLong(2)},user);}
    public void reserve(long user,int bytes){long[] used=usage(user);if(used[0]>=countLimit||used[1]+bytes>quota)throw new PlatformException(413,"IMAGE_QUOTA_EXCEEDED","图片空间或数量已达上限，请到图片库整理；回收站仍占用空间，待保留期结束后由管理员清理");}
    public void record(String id,byte[] bytes){try{jdbc.update("INSERT INTO image_storage_records(image_id,byte_size,content_sha256) VALUES(?,?,?)",id,bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private boolean referenced(String id){
        // Includes immutable publication receipts and server draft backups, not just live covers.
        return jdbc.queryForObject("SELECT (EXISTS(SELECT 1 FROM products WHERE cover_image_id=?) OR EXISTS(SELECT 1 FROM product_gallery WHERE image_id=?) OR EXISTS(SELECT 1 FROM users WHERE avatar_image_id=?) OR EXISTS(SELECT 1 FROM product_publications WHERE cover_image_id=? OR JSON_SEARCH(details_json,'one',?) IS NOT NULL) OR EXISTS(SELECT 1 FROM product_draft_backups WHERE cover_image_id=? OR JSON_SEARCH(details_json,'one',?) IS NOT NULL))",Boolean.class,id,id,id,id,id,id,id);
    }
    @Transactional(readOnly=true) public Library list(String scope,int page,String auth){
        long user=sessions.currentUser(auth).id();if(!Set.of("ACTIVE","TRASH").contains(scope)||page<1||page>1000000)throw new PlatformException(400,"INVALID_MEDIA_QUERY","图片筛选无效");
        String where=" WHERE i.owner_id=? AND s.purged_at IS NULL AND s.trashed_at IS "+(scope.equals("TRASH")?"NOT NULL":"NULL");
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM product_images i LEFT JOIN image_storage_records s ON s.image_id=i.id"+where,Long.class,user);int pages=(int)Math.max(1,(total+11)/12),actual=Math.min(page,pages);
        var images=jdbc.query("SELECT i.id,i.width,i.height,s.byte_size,i.created_at,s.trashed_at,COALESCE(s.version,0) FROM product_images i LEFT JOIN image_storage_records s ON s.image_id=i.id"+where+" ORDER BY i.created_at DESC,i.id DESC LIMIT 12 OFFSET ?",(r,n)->new Image(r.getString(1),r.getInt(2),r.getInt(3),r.getLong(4),r.getObject(4)!=null,r.getString(5),r.getString(6),r.getLong(7),false),user,(actual-1)*12);
        var result=images.stream().map(i->new Image(i.id(),i.width(),i.height(),i.bytes(),i.sizeKnown(),i.createdAt(),i.trashedAt(),i.version(),referenced(i.id()))).toList();
        long[] used=usage(user);return new Library(user,result,actual,pages,total,used[1],quota,used[0],countLimit,scope);
    }
    private long lockImage(String id){if(id==null||!id.matches("[0-9a-f-]{36}"))throw missing();var users=jdbc.queryForList("SELECT owner_id FROM product_images WHERE id=?",Long.class,id);if(users.isEmpty())throw missing();long user=users.get(0);jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,user);jdbc.queryForObject("SELECT id FROM product_images WHERE id=? FOR UPDATE",String.class,id);return user;}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public void change(String id,Change input,boolean restore,String auth){
        long user=sessions.currentUser(auth).id();if(input==null||input.version()==null||input.version()<0)throw new PlatformException(400,"INVALID_IMAGE_VERSION","请刷新图片清单");
        if(lockImage(id)!=user)throw missing();sessions.currentUser(auth);
        var rows=jdbc.queryForList("SELECT version,trashed_at,purged_at FROM image_storage_records WHERE image_id=? FOR UPDATE",id);
        if(rows.isEmpty()){
            // Backfill historic metadata only from the actual, validated object.
            try(var objectInput=store.read(id).getInputStream()){byte[] bytes=objectInput.readAllBytes();record(id,bytes);}catch(java.io.IOException ex){throw missing();}
            rows=jdbc.queryForList("SELECT version,trashed_at,purged_at FROM image_storage_records WHERE image_id=? FOR UPDATE",id);
        }
        var before=rows.get(0);if(before.get("purged_at")!=null)throw missing();
        if(((Number)before.get("version")).longValue()!=input.version())throw conflict();
        if(restore){store.read(id);if(before.get("trashed_at")==null)return;jdbc.update("UPDATE image_storage_records SET trashed_at=NULL,version=version+1 WHERE image_id=?",id);}
        else {if(before.get("trashed_at")!=null)return;if(referenced(id))throw new PlatformException(409,"IMAGE_IN_USE","图片仍被商品、头像、发布回执或云端草稿引用，不能移入回收站");jdbc.update("UPDATE image_storage_records SET trashed_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE image_id=?",id);}
    }
    @Transactional(readOnly=true) public Cleanup cleanup(String auth){admins.requireAdmin(auth);var candidates=jdbc.query("SELECT image_id,version,byte_size FROM image_storage_records WHERE trashed_at<DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 30 DAY) AND physical_deleted=FALSE ORDER BY trashed_at,image_id LIMIT 50",(r,n)->new Candidate(r.getString(1),r.getLong(2),r.getLong(3))).stream().filter(i->!referenced(i.id())).toList();return new Cleanup(candidates,30,"LOCAL","只列出回收站超过30天且无引用的图片；确认清理后无法恢复。旧图片大小未知时按10MB计入配额，未同步的本地草稿不计入服务端引用。");}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public Map<String,Integer> purge(Purge input,String auth){
        admins.requireAdmin(auth);if(input==null||input.images()==null||input.images().isEmpty()||input.images().size()>50||input.images().stream().map(Candidate::id).distinct().count()!=input.images().size())throw new PlatformException(400,"INVALID_MEDIA_CLEANUP","请重新预览清理候选，最多50张");
        var ordered=input.images().stream().sorted(Comparator.comparing(Candidate::id)).toList();
        // Stable owner order prevents deadlocks for a batch spanning multiple users.
        var owners=new TreeSet<Long>();for(var image:ordered){var ids=jdbc.queryForList("SELECT owner_id FROM product_images WHERE id=?",Long.class,image.id());if(ids.isEmpty())throw missing();owners.add(ids.get(0));}
        for(long owner:owners)jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,owner);
        for(var image:ordered){lockImage(image.id());var eligible=jdbc.queryForList("SELECT version FROM image_storage_records WHERE image_id=? AND trashed_at<DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 30 DAY) AND physical_deleted=FALSE FOR UPDATE",Long.class,image.id());if(eligible.isEmpty()||eligible.get(0)!=image.version()||referenced(image.id()))throw conflict();jdbc.update("UPDATE image_storage_records SET purged_at=COALESCE(purged_at,CURRENT_TIMESTAMP(6)),version=version+1 WHERE image_id=?",image.id());}
        // Tombstones commit first. A failed physical delete stays charged and is retryable.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCommit(){for(var image:ordered)try{store.delete(image.id());afterCommit.executeWithoutResult(status->jdbc.update("UPDATE image_storage_records SET physical_deleted=TRUE WHERE image_id=? AND purged_at IS NOT NULL",image.id()));}catch(RuntimeException ex){org.slf4j.LoggerFactory.getLogger(ImageLibraryService.class).warn("图片清理待重试（{}）",ex.getClass().getSimpleName());}}});
        return Map.of("accepted",ordered.size());
    }
    private static PlatformException missing(){return new PlatformException(404,"MEDIA_NOT_FOUND","图片不存在或当前账号不可操作");}
    private static PlatformException conflict(){return new PlatformException(409,"MEDIA_CHANGED","图片状态已变化，请刷新清单或清理预览后再操作");}
}
