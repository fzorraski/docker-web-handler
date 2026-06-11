package br.com.fzdevx.interfaces.rest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthWebSocketConfiguratorTest {

    private static final String SID = "0efcbec8-518d-4426-8e42-15079bd18f80";

    @Test
    void extractsSessionFromSingleCookie() {
        assertEquals(List.of(SID), AuthWebSocketConfigurator.extractSessionCookies(
                List.of("DWH-SESSION=" + SID)));
    }

    @Test
    void extractsSessionAmongMultipleCookies() {
        assertEquals(List.of(SID), AuthWebSocketConfigurator.extractSessionCookies(
                List.of("_ga=GA1.1.1725157699.1745512690; DWH-SESSION=" + SID + "; foo=bar")));
    }

    /** Regression: GA _ga_* values use $-delimited segments that java.net.HttpCookie.parse
     *  rejected, dropping DWH-SESSION and causing a bogus "Authentication required." */
    @Test
    void extractsSessionDespiteDollarLadenAnalyticsCookies() {
        String header = "_ga=GA1.1.1725157699.1745512690; "
                + "_ga_SR3H6G8EBP=GS2.1.s1777467536$o22$g1$t1777467540$j56$l0$h0; "
                + "DWH-SESSION=" + SID;
        assertEquals(List.of(SID), AuthWebSocketConfigurator.extractSessionCookies(List.of(header)));
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertEquals(List.of(SID), AuthWebSocketConfigurator.extractSessionCookies(
                List.of("  DWH-SESSION =  " + SID + "  ")));
    }

    @Test
    void searchesAcrossMultipleHeaderValues() {
        assertEquals(List.of(SID), AuthWebSocketConfigurator.extractSessionCookies(
                List.of("_ga=GA1.1.x", "DWH-SESSION=" + SID)));
    }

    /** Duplicate cookies (e.g. a stale one scoped to a different path) must all be
     *  returned so the handshake can validate each — a stale dup must not lock out a valid one. */
    @Test
    void returnsAllDuplicateSessionCookies() {
        String stale = "11111111-2222-3333-4444-555555555555";
        assertEquals(List.of(stale, SID), AuthWebSocketConfigurator.extractSessionCookies(
                List.of("DWH-SESSION=" + stale + "; DWH-SESSION=" + SID)));
    }

    @Test
    void returnsEmptyWhenSessionCookieAbsent() {
        assertEquals(List.of(), AuthWebSocketConfigurator.extractSessionCookies(
                List.of("_ga=GA1.1.x; foo=bar")));
    }

    @Test
    void returnsEmptyForNullOrEmptyInput() {
        assertEquals(List.of(), AuthWebSocketConfigurator.extractSessionCookies(null));
        assertEquals(List.of(), AuthWebSocketConfigurator.extractSessionCookies(List.of()));
    }

    @Test
    void ignoresMalformedPairsAndNullHeaders() {
        List<String> headers = new ArrayList<>();
        headers.add(null);
        headers.add("=novalue; nokey; DWH-SESSION=" + SID);
        assertEquals(List.of(SID), AuthWebSocketConfigurator.extractSessionCookies(headers));
    }
}
