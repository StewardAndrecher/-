package com.campus.market.product;
import java.util.List;
import java.util.Set;
public record ProductDetails(String campusCode,String conditionCode,String handoverLocation,List<String> imageIds) {
 public static final Set<String> CAMPUSES=Set.of("UNSPECIFIED","EAST","WEST","OTHER");
 public static final Set<String> CONDITIONS=Set.of("UNSPECIFIED","NEW","LIKE_NEW","GOOD","FAIR");
 public ProductDetails { if(imageIds!=null)imageIds=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(imageIds)); }
 public static ProductDetails legacy(String cover) {return new ProductDetails("UNSPECIFIED","UNSPECIFIED","",cover==null||cover.isEmpty()?List.of():List.of(cover));}
 public static ProductDetails validate(ProductDetails value,String cover) {
  if(value==null)return legacy(cover);
  if(value.campusCode()==null||!CAMPUSES.contains(value.campusCode())||value.conditionCode()==null||!CONDITIONS.contains(value.conditionCode()))throw new InvalidProductException("请选择有效校区与商品成色");
  String location=value.handoverLocation();
  if(location==null||location.codePointCount(0,location.length())>80||location.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xd800&&c<=0xdfff))throw new InvalidProductException("交接地点最多80个字符，不能包含控制字符");
  var ids=value.imageIds();
  if(ids==null||ids.size()>6||new java.util.HashSet<>(ids).size()!=ids.size()||ids.stream().anyMatch(id->id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))throw new InvalidProductException("商品图片最多6张，图片标识必须有效且不能重复");
  String expected=ids.isEmpty()?null:ids.get(0);
  if(!java.util.Objects.equals(expected,cover==null||cover.isEmpty()?null:cover))throw new InvalidProductException("第一张商品图片必须与封面一致");
  return new ProductDetails(value.campusCode(),value.conditionCode(),location.strip(),ids);
 }
}
