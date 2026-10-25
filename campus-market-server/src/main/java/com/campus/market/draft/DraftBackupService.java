package com.campus.market.draft;

import com.campus.market.auth.SessionService;
import com.campus.market.media.ImageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.List;
import java.util.Set;
import static com.campus.market.draft.DraftBackupModels.*;

@Service
public class DraftBackupService {
 private static final long MAX_SAFE=9_007_199_254_740_991L;
 private static final Set<String> CATEGORIES=Set.of("UNCLASSIFIED","BOOK","DIGITAL","DAILY","SPORT","OTHER");
 private final DraftBackupRepository repository;
 private final SessionService sessions;
 private final ImageService images;
 public DraftBackupService(DraftBackupRepository repository,SessionService sessions,ImageService images) { this.repository=repository;this.sessions=sessions;this.images=images; }
 @Transactional(readOnly=true)
 public List<Backup> list(String auth) { return repository.list(sessions.currentUser(auth).id()); }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Page page(int page,int size,String keyword,String category,String sort,String auth) {
  long owner=sessions.currentUser(auth).id();
  String normalized=keyword==null?"":keyword.trim();
  if(page<1||page>1_000_000||size<1||size>10||normalized.length()>50) throw new DraftBackupException(400,"INVALID_DRAFT_QUERY","页码须为1～1000000，每页1～10份，标题关键词最多50个字符");
  String categoryCode=category==null?"":category;
  if(!categoryCode.isEmpty()&&!CATEGORIES.contains(categoryCode)) throw new DraftBackupException(400,"INVALID_DRAFT_QUERY","草稿分类无效，请从分类列表中选择");
  DraftBackupSort selectedSort=DraftBackupSort.parse(sort);
  long total=repository.matchingCount(owner,normalized,categoryCode);
  int pages=Math.max(1,(int)((total+size-1)/size));
  int actualPage=Math.min(page,pages);
  return new Page(repository.page(owner,normalized,categoryCode,selectedSort,size,(actualPage-1)*size),total,actualPage,size,pages,normalized,categoryCode,selectedSort.name());
 }
 @Transactional(readOnly=true)
 public Backup read(String id,String auth) {
  long owner=sessions.currentUser(auth).id();validateId(id);
  return repository.read(owner,id).orElseThrow(()->new DraftBackupException(404,"DRAFT_NOT_FOUND","云端备份不存在，请刷新检查"));
 }
 @Transactional
 public Saved save(String id,SaveRequest request,String auth) {
  long owner=sessions.currentUser(auth).id();validateId(id);validate(request);
  repository.lockOwner(owner);
  images.requireOwned(request.content().coverImageId(),owner);
  var details=com.campus.market.product.ProductDetails.validate(request.content().details(),request.content().coverImageId());
  for(String image:details.imageIds())images.requireOwned(image,owner);
  if(request.content().details()!=null&&!details.equals(request.content().details()))throw DraftBackupException.invalid();
  var previous=repository.find(owner,id);
  if(previous.isPresent()) {
   var saved=previous.get();
   if(request.sourceRevision()<saved.sourceRevision()) throw DraftBackupException.conflict();
   if(request.sourceRevision()==saved.sourceRevision()) {
    if(!request.content().equals(saved.content())) throw DraftBackupException.conflict();
    return new Saved(saved,false); // 同一份本机版本重复提交，返回原记录。
   }
   if(saved.version()>=MAX_SAFE) throw DraftBackupException.conflict();
   repository.update(owner,id,request);
  } else {
   if(repository.count(owner)>=10) throw new DraftBackupException(409,"DRAFT_LIMIT","云端最多保存10份草稿，请先管理已有备份");
   repository.insert(owner,id,request);
  }
  return new Saved(repository.find(owner,id).orElseThrow(),previous.isEmpty());
 }
 @Transactional
 public void delete(String id,Long version,String auth) {
  long owner=sessions.currentUser(auth).id();validateId(id);
  if(version==null||version<0||version>MAX_SAFE) throw DraftBackupException.invalid();
  repository.lockOwner(owner);
  var saved=repository.find(owner,id).orElseThrow(()->new DraftBackupException(404,"DRAFT_NOT_FOUND","云端备份不存在，请刷新检查"));
  if(saved.version()!=version||repository.delete(owner,id,version)!=1) throw DraftBackupException.conflict();
 }
 private static void validateId(String id) { if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw DraftBackupException.invalid(); }
 private static void validate(SaveRequest r) {
  if(r==null||r.sourceRevision()==null||r.sourceRevision()<1||r.sourceRevision()>MAX_SAFE||r.content()==null) throw DraftBackupException.invalid();
  var c=r.content();
  if(c.title()==null||c.title().length()>50||c.description()==null||c.description().length()>500||c.priceText()==null||!c.priceText().matches("[0-9.]{0,9}")||c.coverImageId()==null||c.categoryCode()==null||!CATEGORIES.contains(c.categoryCode())) throw DraftBackupException.invalid();
  if(!c.coverImageId().isEmpty()) validateId(c.coverImageId());
  var details=com.campus.market.product.ProductDetails.validate(c.details(),c.coverImageId());
  if(c.title().isBlank()&&c.description().isBlank()&&c.priceText().isEmpty()&&c.coverImageId().isEmpty()&&c.categoryCode().equals("OTHER")&&details.campusCode().equals("UNSPECIFIED")&&details.conditionCode().equals("UNSPECIFIED")&&details.handoverLocation().isBlank()) throw DraftBackupException.invalid();
 }
}
