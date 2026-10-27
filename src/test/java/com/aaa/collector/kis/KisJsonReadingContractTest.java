package com.aaa.collector.kis;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aaa.collector.common.retry.Sleeper;
import com.aaa.collector.common.safemode.SafeModeManager;
import com.aaa.collector.kis.holiday.KisHolidayResponse;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.aaa.collector.kis.token.KisTokenService;
import com.aaa.collector.kis.websocket.AesKey;
import com.aaa.collector.kis.websocket.KisMarketSchedule;
import com.aaa.collector.kis.websocket.KisTickPublisher;
import com.aaa.collector.kis.websocket.KisWebSocketMessageHandler;
import com.aaa.collector.kis.websocket.KisWebSocketSession;
import com.aaa.collector.kis.websocket.ParsedTick;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import org.json.JSONException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

/**
 * KIS 직접 생성 매퍼의 JSON 판독·생성 골든 (SPEC-INFRA-BOOT4-001 REQ-BOOT4-011, AC-BOOT4-011).
 *
 * <p>{@code KisApiExecutor}·{@code KisWebSocketSession}·{@code KisWebSocketMessageHandler}는 Spring
 * 관리 매퍼가 아니라 클래스 안에서 직접 만든 정적 매퍼를 쓴다. Boot 4 전환에서 이 매퍼들을 Jackson 3로 옮겨도 판독 결과와 생성 JSON이 같아야 하므로, 운영
 * 클래스를 그대로 거쳐 값·JSON 의미 수준으로 단언한다(Jackson 타입 미사용).
 */
@DisplayName("KIS 직접 매퍼 계약 — 오류 코드 판독·WebSocket 승인 메시지·제어 메시지 판독")
class KisJsonReadingContractTest {

    @Nested
    @DisplayName("KisApiExecutor — 5xx 응답 본문의 msg_cd 판독")
    class ApiErrorCode {

        private WireMockServer wireMock;
        private KisApiExecutor executor;
        private KisAccountCredential credential;

        @BeforeEach
        void setUp() {
            wireMock = new WireMockServer(wireMockConfig().dynamicPort());
            wireMock.start();
            KisTokenService tokenService = mock(KisTokenService.class);
            when(tokenService.getValidToken(anyString())).thenReturn("contract-token");
            RestClient restClient = RestClient.builder().baseUrl(wireMock.baseUrl()).build();
            executor = new KisApiExecutor(restClient, null, tokenService);
            credential = new KisAccountCredential("contract", "12345678", "app-key", "app-secret");
        }

        @AfterEach
        void tearDown() {
            wireMock.stop();
        }

        private void respond500(String body) {
            wireMock.stubFor(
                    get(urlPathEqualTo("/uapi/contract"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(500)
                                            .withHeader(
                                                    "Content-Type",
                                                    "application/json; charset=utf-8")
                                            .withBody(body)));
        }

        private void call() {
            executor.executeGet(
                    credential,
                    builder -> builder.path("/uapi/contract").build(),
                    "CTCA0903R",
                    KisHolidayResponse.class);
        }

        @Test
        @DisplayName("msg_cd=EGW00201이면 KisRateLimitException")
        void rateLimitCodeIsRecognized() {
            respond500("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\",\"msg1\":\"초당 거래건수를 초과하였습니다.\"}");

            assertThatThrownBy(this::call)
                    .isInstanceOf(KisRateLimitException.class)
                    .extracting("alias")
                    .isEqualTo("contract");
        }

        @Test
        @DisplayName("다른 msg_cd면 RestClientResponseException(영구 오류)")
        void otherCodeIsPermanentError() {
            respond500("{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00123\",\"msg1\":\"기간이 만료된 token 입니다.\"}");

            assertThatThrownBy(this::call)
                    .isExactlyInstanceOf(RestClientResponseException.class)
                    .hasMessageContaining("EGW00123");
        }

        @Test
        @DisplayName("JSON이 아닌 본문은 판독 실패로 영구 오류 경로")
        void nonJsonBodyIsPermanentError() {
            respond500("<html>Internal Server Error</html>");

            assertThatThrownBy(this::call).isExactlyInstanceOf(RestClientResponseException.class);
        }
    }

    @Nested
    @DisplayName("KisWebSocketSession — 구독 요청(승인 키) 메시지 생성")
    class SubscribeMessage {

        private WebSocketSession rawSession;
        private KisWebSocketSession session;

        @BeforeEach
        void setUp() {
            rawSession = mock(WebSocketSession.class);
            when(rawSession.isOpen()).thenReturn(true);
            WebSocketClient webSocketClient = mock(WebSocketClient.class);
            when(webSocketClient.execute(any(), any(WebSocketHttpHeaders.class), any(URI.class)))
                    .thenReturn(CompletableFuture.completedFuture(rawSession));
            session =
                    new KisWebSocketSession(
                            "contract",
                            "approval-key-123",
                            webSocketClient,
                            mock(KisWebSocketMessageHandler.class),
                            mock(KisMarketSchedule.class),
                            mock(SafeModeManager.class),
                            mock(Sleeper.class),
                            Clock.systemDefaultZone());
            session.connect("ws://localhost:21000");
        }

        private String sentPayload() throws IOException {
            ArgumentCaptor<WebSocketMessage<?>> captor =
                    ArgumentCaptor.forClass(WebSocketMessage.class);
            verify(rawSession).sendMessage(captor.capture());
            return ((TextMessage) captor.getValue()).getPayload();
        }

        @Test
        @DisplayName("구독(tr_type=1) 메시지 JSON이 3.5와 같다")
        void subscribeJson() throws IOException, JSONException {
            session.subscribe("H0STCNT0", "005930");

            JSONAssert.assertEquals(
                    """
                    {"header":{"approval_key":"approval-key-123","custtype":"P",\
                    "tr_type":"1","content-type":"utf-8"},\
                    "body":{"input":{"tr_id":"H0STCNT0","tr_key":"005930"}}}""",
                    sentPayload(),
                    JSONCompareMode.STRICT);
        }

        @Test
        @DisplayName("구독 해제(tr_type=2) 메시지 JSON이 3.5와 같다")
        void unsubscribeJson() throws IOException, JSONException {
            session.unsubscribe("HDFSCNT0", "DNASAAPL");

            JSONAssert.assertEquals(
                    """
                    {"header":{"approval_key":"approval-key-123","custtype":"P",\
                    "tr_type":"2","content-type":"utf-8"},\
                    "body":{"input":{"tr_id":"HDFSCNT0","tr_key":"DNASAAPL"}}}""",
                    sentPayload(),
                    JSONCompareMode.STRICT);
        }
    }

    @Nested
    @DisplayName("KisWebSocketMessageHandler — 제어 메시지(Type B)·틱 메시지(Type A) 판독")
    class ControlMessage {

        private KisTickPublisher tickPublisher;
        private KisWebSocketMessageHandler handler;
        private WebSocketSession wsSession;

        @BeforeEach
        void setUp() {
            tickPublisher = mock(KisTickPublisher.class);
            handler =
                    new KisWebSocketMessageHandler(
                            "contract", tickPublisher, mock(SafeModeManager.class));
            wsSession = mock(WebSocketSession.class);
        }

        @Test
        @DisplayName("구독 성공 응답의 output.iv·key를 AES 키로 저장한다")
        void subscriptionSuccessRegistersAesKey() throws Exception {
            // Arrange
            handler.recordPending(
                    "H0STCNT0", "005930", KisWebSocketMessageHandler.Direction.SUBSCRIBE);
            String raw =
                    """
                    {"header":{"tr_id":"H0STCNT0","tr_key":"005930","encrypt":"N"},\
                    "body":{"rt_cd":"0","msg_cd":"OPSP0000","msg1":"SUBSCRIBE SUCCESS",\
                    "output":{"iv":"0123456789abcdef","key":"abcdefghijklmnopabcdefghijklmnop"}}}""";

            // Act
            handler.handleMessage(wsSession, new TextMessage(raw));

            // Assert
            assertThat(handler.getAesKey("H0STCNT0"))
                    .isEqualTo(new AesKey("0123456789abcdef", "abcdefghijklmnopabcdefghijklmnop"));
        }

        @Test
        @DisplayName("승인 키 인증 실패 응답은 재발급 콜백을 호출한다")
        void authFailureTriggersReissue() throws Exception {
            // Arrange
            Runnable reissue = mock(Runnable.class);
            handler.setApprovalKeyReissueCallback(reissue);
            handler.recordPending(
                    "H0STCNT0", "005930", KisWebSocketMessageHandler.Direction.SUBSCRIBE);
            String raw =
                    """
                    {"header":{"tr_id":"H0STCNT0","tr_key":"005930","encrypt":"N"},\
                    "body":{"rt_cd":"1","msg_cd":"OPSP8996","msg1":"invalid approval : NOT FOUND"}}""";

            // Act
            handler.handleMessage(wsSession, new TextMessage(raw));

            // Assert
            verify(reissue).run();
        }

        @Test
        @DisplayName("PINGPONG은 원문 바이트 그대로 Pong으로 돌려보낸다")
        void pingPongEchoesRawPayload() throws Exception {
            // Arrange
            String raw = "{\"header\":{\"tr_id\":\"PINGPONG\",\"datetime\":\"20260619093015\"}}";
            ArgumentCaptor<WebSocketMessage<?>> captor =
                    ArgumentCaptor.forClass(WebSocketMessage.class);

            // Act
            handler.handleMessage(wsSession, new TextMessage(raw));

            // Assert
            verify(wsSession).sendMessage(captor.capture());
            assertThat(captor.getValue()).isInstanceOf(PongMessage.class);
            assertThat(((PongMessage) captor.getValue()).getPayload())
                    .isEqualTo(ByteBuffer.wrap(raw.getBytes(StandardCharsets.UTF_8)));
            verify(tickPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("비암호화 Type A 틱은 trId·trKey·data·국내 여부로 판독된다")
        void plainTickIsParsed() throws Exception {
            // Arrange
            ArgumentCaptor<ParsedTick> captor = ArgumentCaptor.forClass(ParsedTick.class);

            // Act
            handler.handleMessage(
                    wsSession, new TextMessage("0|H0STCNT0|001|005930^093015^71500^2^500"));

            // Assert
            verify(tickPublisher).publish(captor.capture());
            ParsedTick tick = captor.getValue();
            assertThat(tick)
                    .extracting("trId", "trKey", "data", "isDomestic")
                    .containsExactly("H0STCNT0", "005930", "005930^093015^71500^2^500", true);
            assertThat(tick.traceId()).isNotBlank();
        }
    }
}
