package com.campus.market.order;

/** Identical normalization for original issue text and the administrator's explanation. */
public final class OrderIssueText {
    private OrderIssueText() {}
    public static String normalize(String raw) {
        if(raw==null)throw invalid();
        String text=raw.replace("\r\n","\n").replace('\r','\n');int start=0,end=text.length();
        while(start<end&&space(text.charAt(start)))start++;while(end>start&&space(text.charAt(end-1)))end--;
        String value=text.substring(start,end);int length=value.codePointCount(0,value.length());
        if(length<10||length>1000||value.codePoints().anyMatch(c->(Character.isISOControl(c)&&c!='\n'&&c!='\t')||(c>=0xd800&&c<=0xdfff)))throw invalid();
        return value;
    }
    private static boolean space(char c){return Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0xfeff;}
    private static OrderException invalid(){return new OrderException(400,"INVALID_ORDER_ISSUE_TEXT","说明须为10至1000个字符，不能包含无效或控制字符");}
}
