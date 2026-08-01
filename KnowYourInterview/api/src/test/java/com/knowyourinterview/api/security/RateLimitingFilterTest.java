package com.knowyourinterview.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for which address a request gets bucketed under — the part of the limiter that
 * turned out to be wrong in production rather than merely approximate.
 * <p>
 * Behind Railway's edge, {@code getRemoteAddr()} is the proxy for every request, so a
 * remote-addr-keyed limiter counts the entire user base into one bucket: 10 logins per
 * minute globally instead of per person. Trusting X-Forwarded-For unconditionally would be
 * the opposite failure — any client can set that header, so the limiter would become free to
 * bypass. Hence the flag, and hence these tests covering both settings.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitingFilterTest {

    private static final String LOGIN_PATH = "/api/v1/auth/login";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        // lenient: the "unlimited path" test never touches Redis at all, and the 429 test
        // re-stubs the counter — neither should trip strict-stub checking.
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.increment(anyString())).thenReturn(1L);
    }

    private MockHttpServletRequest loginRequest(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", LOGIN_PATH);
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    private String keyUsedFor(RateLimitingFilter filter, MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).increment(key.capture());
        return key.getValue();
    }

    @Test
    void keysOnRemoteAddrWhenForwardedHeadersArentTrusted() throws Exception {
        RateLimitingFilter filter = new RateLimitingFilter(redisTemplate, false);

        String key = keyUsedFor(filter, loginRequest("203.0.113.7", null));

        assertThat(key).isEqualTo("ratelimit:" + LOGIN_PATH + ":203.0.113.7");
    }

    /** The security half: with the flag off, a client that sends its own X-Forwarded-For
     * gains nothing — otherwise rotating that header would make the limiter free to bypass. */
    @Test
    void ignoresASpoofedForwardedHeaderWhenTheFlagIsOff() throws Exception {
        RateLimitingFilter filter = new RateLimitingFilter(redisTemplate, false);

        String key = keyUsedFor(filter, loginRequest("203.0.113.7", "10.0.0.1"));

        assertThat(key).isEqualTo("ratelimit:" + LOGIN_PATH + ":203.0.113.7");
    }

    /** The availability half: with a trusted proxy in front, the left-most hop is the real
     * client, and each one gets its own bucket instead of sharing the proxy's. */
    @Test
    void keysOnTheOriginalClientWhenForwardedHeadersAreTrusted() throws Exception {
        RateLimitingFilter filter = new RateLimitingFilter(redisTemplate, true);

        String key = keyUsedFor(filter, loginRequest("10.0.0.99", "198.51.100.4, 10.0.0.99"));

        assertThat(key).isEqualTo("ratelimit:" + LOGIN_PATH + ":198.51.100.4");
    }

    /** A request that reaches the app without passing through the proxy still gets limited,
     * rather than falling through on an empty key. */
    @Test
    void fallsBackToRemoteAddrWhenTrustedButTheHeaderIsAbsent() throws Exception {
        RateLimitingFilter filter = new RateLimitingFilter(redisTemplate, true);

        String key = keyUsedFor(filter, loginRequest("10.0.0.99", null));

        assertThat(key).isEqualTo("ratelimit:" + LOGIN_PATH + ":10.0.0.99");
    }

    /** Two different clients behind the same proxy must not share a bucket — this is the
     * exact behaviour that was broken before the flag existed. */
    @Test
    void separatesTwoClientsSharingAProxy() throws Exception {
        RateLimitingFilter filter = new RateLimitingFilter(redisTemplate, true);
        List<String> keys = new ArrayList<>();

        for (String client : List.of("198.51.100.4", "198.51.100.5")) {
            filter.doFilter(
                    loginRequest("10.0.0.99", client + ", 10.0.0.99"),
                    new MockHttpServletResponse(),
                    new MockFilterChain());
        }
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).increment(captor.capture());
        keys.addAll(captor.getAllValues());

        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    void leavesUnlimitedPathsAlone() throws Exception {
        RateLimitingFilter filter = new RateLimitingFilter(redisTemplate, false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/experiences");
        request.setRemoteAddr("203.0.113.7");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(valueOperations, never()).increment(any(String.class));
    }

    @Test
    void rejectsWithA429OnceTheWindowsBudgetIsSpent() throws Exception {
        // Login's budget is 10/minute — the 11th call in the window is over it.
        when(valueOperations.increment(anyString())).thenReturn(11L);
        RateLimitingFilter filter = new RateLimitingFilter(redisTemplate, false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(loginRequest("203.0.113.7", null), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        // The request must not reach the rest of the chain — that's the whole point.
        assertThat(chain.getRequest()).isNull();
    }
}
