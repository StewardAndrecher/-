package com.campus.market.auth;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final DefaultRedisScript<String> RESERVE = script("redis/login-reserve.lua");
    private static final DefaultRedisScript<String> FINISH = script("redis/login-finish.lua");
    private final boolean enabled;
    private final String prefix;
    private final int accountLimit;
    private final int accountSeconds;
    private final int ipLimit;
    private final int ipSeconds;
    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate redis;
    private final AtomicLong retryAfter = new AtomicLong(System.nanoTime());

    public record Attempt(String key, String ticket, String previousFailures) {}

    public LoginRateLimiter(
            @Value("${market.login-protection.enabled:true}") boolean enabled,
            @Value("${market.login-protection.host:127.0.0.1}") String host,
            @Value("${market.login-protection.port:6380}") int port,
            @Value("${market.login-protection.password:}") String password,
            @Value("${market.login-protection.prefix:campus:login:v1:}") String prefix,
            @Value("${market.login-protection.account-limit:5}") int accountLimit,
            @Value("${market.login-protection.account-window-seconds:900}") int accountSeconds,
            @Value("${market.login-protection.ip-limit:30}") int ipLimit,
            @Value("${market.login-protection.ip-window-seconds:60}") int ipSeconds) {
        if (!prefix.matches("[a-zA-Z0-9:_-]{1,80}") || port < 1 || port > 65535
                || accountLimit < 1 || accountLimit > 20 || ipLimit < 1 || ipLimit > 10000
                || accountSeconds < 1 || accountSeconds > 86400 || ipSeconds < 1 || ipSeconds > 86400) {
            throw new IllegalArgumentException("登录保护配置无效");
        }
        this.enabled=enabled; this.prefix=prefix; this.accountLimit=accountLimit;
        this.accountSeconds=accountSeconds; this.ipLimit=ipLimit; this.ipSeconds=ipSeconds;
        if (!enabled) { this.factory=null; this.redis=null; return; }
        var standalone=new RedisStandaloneConfiguration(host,port);
        standalone.setPassword(RedisPassword.of(password));
        var options=ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build();
        var client=LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200))
                .shutdownTimeout(Duration.ofMillis(100)).clientOptions(options).build();
        // 独立连接不注册为默认RedisConnectionFactory，避免替换商品缓存的自动配置。
        factory=new LettuceConnectionFactory(standalone,client);
        factory.afterPropertiesSet(); factory.start();
        redis=new StringRedisTemplate(factory);
    }

    public Attempt begin(String username, String remoteAddress) {
        if (!enabled) return new Attempt("", "", "");
        String ip=canonicalIp(remoteAddress);
        String ipDigest=digest(ip);
        String ipKey=prefix+"{"+ipDigest+"}:ip";
        String accountKey=prefix+"{"+ipDigest+"}:account:"+digest(ip+"\0"+username);
        String ticket=UUID.randomUUID().toString();
        String result=execute(RESERVE,List.of(ipKey,accountKey),String.valueOf(ipLimit),
                String.valueOf(ipSeconds*1000L),String.valueOf(accountLimit),
                String.valueOf(accountSeconds*1000L),ticket);
        if (result.startsWith("LIMIT:")) {
            try {
                long milliseconds=Long.parseLong(result.substring(6));
                throw new LoginRateLimitedException(Math.max(1,(milliseconds+999)/1000));
            } catch (NumberFormatException broken) { throw new LoginProtectionUnavailableException(); }
        }
        if (!result.startsWith("OK:")) throw new LoginProtectionUnavailableException();
        return new Attempt(accountKey,ticket,result.substring(3));
    }

    public void failed(Attempt attempt) { finish(attempt,"failure"); }
    public void succeeded(Attempt attempt) { finish(attempt,"success"); }
    public void aborted(Attempt attempt) { finish(attempt,"abort"); }

    private void finish(Attempt attempt,String outcome) {
        if (!enabled) return;
        String result=execute(FINISH,List.of(attempt.key()),attempt.ticket(),outcome,attempt.previousFailures());
        if (!"OK".equals(result) && !"EXPIRED".equals(result)) throw new LoginProtectionUnavailableException();
    }

    private String execute(DefaultRedisScript<String> script,List<String> keys,String... arguments) {
        if (System.nanoTime()-retryAfter.get()<0) throw new LoginProtectionUnavailableException();
        try {
            String result=redis.execute(script,keys,(Object[])arguments);
            if (result==null) throw new IllegalStateException("登录保护脚本没有返回结果");
            return result;
        } catch (RuntimeException failure) {
            long now=System.nanoTime(),previous=retryAfter.get();
            if (now-previous>=0 && retryAfter.compareAndSet(previous,now+Duration.ofSeconds(5).toNanos())) {
                log.warn("登录保护Redis暂不可用，暂停新登录5秒（{}）",failure.getClass().getSimpleName());
            }
            throw new LoginProtectionUnavailableException();
        }
    }

    private static DefaultRedisScript<String> script(String path) {
        var script=new DefaultRedisScript<String>();
        script.setLocation(new ClassPathResource(path));script.setResultType(String.class);return script;
    }

    private static String canonicalIp(String value) {
        try {
            if (value==null || value.length()>64) throw new IllegalArgumentException();
            if (value.contains(":")) {
                if (!value.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException();
            } else {
                if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IllegalArgumentException();
                for (String part:value.split("\\.")) {
                    if (Integer.parseInt(part)>255 || (part.length()>1 && part.startsWith("0"))) throw new IllegalArgumentException();
                }
            }
            return InetAddress.getByName(value).getHostAddress();
        } catch (Exception invalid) { throw new LoginProtectionUnavailableException(); }
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override public void destroy() { if (factory!=null) factory.destroy(); }
}
