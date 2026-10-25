package com.campus.market.product;
// Only explicitly public profile data is exposed, never recovery data or login credentials.
public record SellerResponse(long id,String nickname,String avatarImageId,String bio,String campusCode) {
 public SellerResponse(long id,String nickname){this(id,nickname,null,"","UNSPECIFIED");}
}
