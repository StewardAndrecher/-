package com.campus.market.user;
import com.campus.market.auth.SessionService;
import com.campus.market.media.ImageService;
import com.campus.market.product.ProductDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.campus.market.product.ProductVersionDeserializer;
@Service
public class AccountProfileService {
    private final com.campus.market.governance.WriteGovernance governance;
    private final com.campus.market.governance.ContentRules rules;
 public record Profile(long userId,String avatarImageId,String bio,String campusCode,long version) {}
 public record Update(String avatarImageId,String bio,String campusCode,@JsonDeserialize(using=ProductVersionDeserializer.class) Long version) {}
 private final JdbcTemplate jdbc;private final SessionService sessions;private final ImageService images;
 public AccountProfileService(JdbcTemplate jdbc,SessionService sessions,ImageService images,com.campus.market.governance.WriteGovernance governance,com.campus.market.governance.ContentRules rules){this.governance=governance;this.rules=rules;this.jdbc=jdbc;this.sessions=sessions;this.images=images;}
 private Profile read(long user,boolean lock){return jdbc.queryForObject("SELECT id,avatar_image_id,bio,campus_code,profile_version FROM users WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->new Profile(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5)),user);}
 @Transactional(readOnly=true) public Profile get(String auth){return read(sessions.currentUser(auth).id(),false);}
 @Transactional(isolation=Isolation.READ_COMMITTED) public Profile save(Update request,String auth){
  long user=sessions.currentUser(auth).id();
  if(request==null||request.version()==null||request.version()<0||request.version()>9007199254740991L||request.bio()==null||request.bio().codePointCount(0,request.bio().length())>160||request.bio().codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xd800&&c<=0xdfff)||request.campusCode()==null||!ProductDetails.CAMPUSES.contains(request.campusCode())||request.avatarImageId()==null)throw new InvalidUserException("请填写有效资料：简介最多160个字符，并选择校区");
  rules.requireAllowed(request.bio());governance.requireWritable(user,"PROFILE");
  Profile before=read(user,true);sessions.currentUser(auth);
  if(before.version()!=request.version())throw new UserProfileConflictException();
  String avatar=images.requireOwned(request.avatarImageId(),user),bio=request.bio().strip();
  if(java.util.Objects.equals(avatar,before.avatarImageId())&&bio.equals(before.bio())&&request.campusCode().equals(before.campusCode()))return before;
  if(before.version()>=9007199254740991L)throw new UserProfileConflictException();
  if(jdbc.update("UPDATE users SET avatar_image_id=?,bio=?,campus_code=?,profile_version=profile_version+1 WHERE id=? AND profile_version=?",avatar,bio,request.campusCode(),user,request.version())!=1)throw new UserProfileConflictException();
  return read(user,false);
 }
}
