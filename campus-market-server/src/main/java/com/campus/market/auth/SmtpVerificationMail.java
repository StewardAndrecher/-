package com.campus.market.auth;
import com.campus.market.governance.PlatformException;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
@Component
public class SmtpVerificationMail implements VerificationMail {
    private final boolean enabled;
    private final JavaMailSenderImpl sender;
    private final String from;
    public SmtpVerificationMail(@Value("${market.email.enabled:false}")boolean enabled,
            @Value("${market.email.host:}")String host,@Value("${market.email.port:587}")int port,
            @Value("${market.email.username:}")String username,@Value("${market.email.password:}")String password,
            @Value("${market.email.from:}")String from,@Value("${market.email.ssl:false}")boolean ssl,
            @Value("${market.email.allow-loopback-plaintext:false}")boolean loopbackTest) {
        this.enabled=enabled;this.from=from;sender=new JavaMailSenderImpl();
        if(!enabled)return;
        if(host.isBlank()||host.contains("\n")||port<1||port>65535||!EmailBindingService.validEmail(from)||(!loopbackTest&&(username.isBlank()||password.isBlank())))throw new IllegalArgumentException("邮件配置不完整");
        if(loopbackTest&&!"127.0.0.1".equals(host))throw new IllegalArgumentException("明文邮件只允许显式配置的本机隔离验收");
        sender.setHost(host);sender.setPort(port);sender.setUsername(username);sender.setPassword(password);sender.setDefaultEncoding("UTF-8");
        Properties p=sender.getJavaMailProperties();
        p.setProperty("mail.smtp.auth",Boolean.toString(!loopbackTest));
        p.setProperty("mail.smtp.ssl.enable",Boolean.toString(ssl));
        p.setProperty("mail.smtp.ssl.checkserveridentity","true");
        p.setProperty("mail.smtp.starttls.enable",Boolean.toString(!ssl&&!loopbackTest));
        p.setProperty("mail.smtp.starttls.required",Boolean.toString(!ssl&&!loopbackTest));
        p.setProperty("mail.smtp.connectiontimeout","3000");p.setProperty("mail.smtp.timeout","3000");p.setProperty("mail.smtp.writetimeout","3000");
    }
    public boolean available(){return enabled;}
    public void send(String recipient,String code,String purpose){
        if(!enabled)throw new PlatformException(503,"EMAIL_NOT_CONFIGURED","邮件服务尚未配置，请继续使用已保存的恢复码");
        SimpleMailMessage message=new SimpleMailMessage();message.setFrom(from);message.setTo(recipient);
        message.setSubject("校园集市 · 账号安全验证码");
        message.setText("你正在执行校园集市账号安全操作（"+purpose+"）。\n验证码："+code+"\n10分钟内有效，仅限使用一次。不要向任何人提供验证码。\n如非本人操作，请忽略本邮件并检查账号安全。");
        try{sender.send(message);}catch(RuntimeException failure){throw new PlatformException(503,"EMAIL_DELIVERY_UNAVAILABLE","邮件暂时无法发送，请稍后重试；不会显示验证码或自动重复发送");}
    }
}
