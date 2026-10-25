package com.campus.market.auth;

import com.campus.market.user.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountRecoveryProtectionTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final SessionService sessions=mock(SessionService.class);
    final SessionRepository repository=mock(SessionRepository.class);
    final PasswordEncoder encoder=mock(PasswordEncoder.class);
    final LoginRateLimiter limiter=mock(LoginRateLimiter.class);
    final AccountRecoveryService service=new AccountRecoveryService(jdbc,sessions,repository,encoder,limiter);
    final LoginRateLimiter.Attempt attempt=new LoginRateLimiter.Attempt("private-key","private-ticket","");
    final AccountRecoveryService.Reset input=new AccountRecoveryService.Reset("student_01","a".repeat(43),"Changed2026!");
    void noWrites(){assertTrue(mockingDetails(jdbc).getInvocations().stream().noneMatch(i->Set.of("update","batchUpdate","execute").contains(i.getMethod().getName())));}
    void attempt(){when(limiter.begin("account-recovery:student_01","127.0.0.1")).thenReturn(attempt);when(jdbc.queryForList("SELECT id FROM users WHERE username=? FOR UPDATE",Long.class,"student_01")).thenReturn(List.of(1L));when(encoder.encode(input.newPassword())).thenReturn("test-only-hash");}
    @Test void unavailableProtectionBlocksResetBeforeDatabaseAccess(){when(limiter.begin("account-recovery:student_01","127.0.0.1")).thenThrow(new LoginProtectionUnavailableException());assertThrows(LoginProtectionUnavailableException.class,()->service.reset(input,"127.0.0.1"));verifyNoInteractions(jdbc);}
    @Test void rateLimitBlocksResetBeforeDatabaseAccess(){when(limiter.begin("account-recovery:student_01","127.0.0.1")).thenThrow(new LoginRateLimitedException(60));assertThrows(LoginRateLimitedException.class,()->service.reset(input,"127.0.0.1"));verifyNoInteractions(jdbc);}
    @Test void failedAttemptMustBeConfirmedAndNeverChangesDatabase(){attempt();when(jdbc.queryForList("SELECT code_hash FROM account_recovery_codes WHERE user_id=? FOR UPDATE",String.class,1L)).thenReturn(List.of("0".repeat(64)));doThrow(new LoginProtectionUnavailableException()).when(limiter).failed(attempt);assertThrows(LoginProtectionUnavailableException.class,()->service.reset(input,"127.0.0.1"));noWrites();}
    @Test void correctCodeStillRequiresProtectionAcknowledgementBeforeAnyMutation()throws Exception {
        attempt();String expected=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("1:"+input.recoveryCode()).getBytes(StandardCharsets.UTF_8)));
        when(jdbc.queryForList("SELECT code_hash FROM account_recovery_codes WHERE user_id=? FOR UPDATE",String.class,1L)).thenReturn(List.of(expected));doThrow(new LoginProtectionUnavailableException()).when(limiter).succeeded(attempt);
        assertThrows(LoginProtectionUnavailableException.class,()->service.reset(input,"127.0.0.1"));noWrites();
    }
    @Test void generatingCodeRequiresProtectionBeforeCredentialCheckingOrWriting(){when(sessions.currentUser("Bearer fixture")).thenReturn(new UserResponse(1,"student_01","同学"));when(limiter.begin("recovery-generate:student_01","127.0.0.1")).thenThrow(new LoginProtectionUnavailableException());assertThrows(LoginProtectionUnavailableException.class,()->service.generate("Bearer fixture",new AccountRecoveryService.Generate("Campus2026!"),"127.0.0.1"));verify(repository).lockUser(1);verifyNoInteractions(jdbc,encoder);}
}
