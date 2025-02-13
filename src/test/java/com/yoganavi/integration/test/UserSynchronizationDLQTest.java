package com.yoganavi.integration.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootTest
@TestPropertySource(properties = {
    "test.simulate.failure=true"
})
class UserSynchronizationDLQTest {

    private static final Logger log = LoggerFactory.getLogger(UserSynchronizationDLQTest.class);

    private final WebClient userServiceClient = WebClient.builder()
        .baseUrl("http://localhost:8081")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();

    private final WebClient lectureServiceClient = WebClient.builder()
        .baseUrl("http://localhost:8082")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();

    @Test
    void DLQ_이벤트_테스트() {
        String email = "dlq.test@test.com";
        String password = "password123";
        String nickname = "dlqTestUser";

        // 이메일 인증 토큰 요청
        RegisterDto tokenRequest = RegisterDto.builder()
            .email(email)
            .build();

        ResponseEntity<Map> tokenResponse = userServiceClient
            .post()
            .uri("/user/register/token")
            .bodyValue(tokenRequest)
            .retrieve()
            .toEntity(Map.class)
            .block();

        assertThat(tokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 이메일 인증번호 확인
        RegisterDto verifyRequest = RegisterDto.builder()
            .email(email)
            .authnumber(123456)
            .build();

        ResponseEntity<Map> verifyResponse = userServiceClient
            .post()
            .uri("/user/register/check/token")
            .bodyValue(verifyRequest)
            .retrieve()
            .toEntity(Map.class)
            .block();

        assertThat(verifyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 회원가입 수행
        RegisterDto registerRequest = RegisterDto.builder()
            .email(email)
            .password(password)
            .nickname(nickname)
            .teacher(true)
            .build();

        ResponseEntity<Map> registerResponse = userServiceClient
            .post()
            .uri("/user/register")
            .bodyValue(registerRequest)
            .retrieve()
            .toEntity(Map.class)
            .block();
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        log.info("회원가입 응답: {}", registerResponse.getBody());

        // DLQ 메시지 발생 확인 (3번의 재시도 후 DLQ로 이동)
        log.info("DLQ 메시지 발생 확인 시작");
        await()
            .atMost(30, TimeUnit.SECONDS)
            .pollInterval(2, TimeUnit.SECONDS)
            .until(() -> {
                try {
                    ResponseEntity<Map> response = lectureServiceClient
                        .get()
                        .uri("/users/dlq-count")
                        .retrieve()
                        .toEntity(Map.class)
                        .block();

                    log.info("DLQ Count 응답: {}", response.getBody());

                    if (response.getBody() != null
                        && ((Integer) response.getBody().get("count")) > 0) {
                        ResponseEntity<Map> dlqDetailsResponse = lectureServiceClient
                            .get()
                            .uri("/users/dlq-messages")
                            .retrieve()
                            .toEntity(Map.class)
                            .block();

                        log.info("DLQ 메시지 상세 응답: {}", dlqDetailsResponse.getBody());

                        List<Map<String, Object>> dlqMessages =
                            (List<Map<String, Object>>) dlqDetailsResponse.getBody()
                                .get("messages");

                        if (dlqMessages == null || dlqMessages.isEmpty()) {
                            log.info("DLQ 메시지가 없음");
                            return false;
                        }

                        Map<String, Object> dlqMessage = dlqMessages.get(0);
                        log.info("첫 번째 DLQ 메시지: {}", dlqMessage);

                        return true;  // 일단 DLQ에 메시지가 있는지만 확인
                    }

                    log.info("DLQ가 아직 비어있음");
                    return false;
                } catch (Exception e) {
                    log.error("DLQ 확인 중 에러 발생", e);
                    return false;
                }
            });

        // 실패 시뮬레이션 비활성화 및 재처리
        lectureServiceClient
            .post()
            .uri("/test/reset-simulation")
            .retrieve()
            .toEntity(Void.class)
            .block();

        // DLQ 메시지 재처리
        ResponseEntity<Map> retryResponse = lectureServiceClient
            .post()
            .uri("/dlq/retry")
            .retrieve()
            .toEntity(Map.class)
            .block();

        log.info("재처리 응답: {}", retryResponse.getBody());

        // 재처리 결과 검증
        assertThat(retryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> retryResult = retryResponse.getBody();
        log.info("재처리 응답: {}", retryResult);

        // DLQ 메시지가 처리되었거나 남은 메시지가 0이면 성공으로 간주
        boolean isProcessed = ((Integer) retryResult.get("remainingCount") == 0);
        assertThat(isProcessed)
            .withFailMessage("재처리 후에도 DLQ에 메시지가 남아있습니다: %s", retryResult)
            .isTrue();

        Long userId = 1L;
        // 최종 데이터 동기화 확인
        await()
            .atMost(30, TimeUnit.SECONDS)
            .pollInterval(2, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                // 사용자 데이터 검증
                ResponseEntity<Map> response = lectureServiceClient
                    .get()
                    .uri("/test/users/" + userId)
                    .retrieve()
                    .toEntity(Map.class)
                    .block();

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                Map<String, Object> user = response.getBody();
                assertThat(user).isNotNull();
                assertThat(user.get("email")).isEqualTo(email);
                assertThat(user.get("nickname")).isEqualTo(nickname);

                //  이벤트 로그 상태 검증
                ResponseEntity<Map> eventLogResponse = lectureServiceClient
                    .get()
                    .uri("/test/event-logs/" + user.get("userId"))
                    .retrieve()
                    .toEntity(Map.class)
                    .block();

                assertThat(eventLogResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(eventLogResponse.getBody().get("status")).isEqualTo("COMPLETED");
            });

        // DLQ가 비워졌는지 최종 확인
        ResponseEntity<Map> finalDlqResponse = lectureServiceClient
            .get()
            .uri("/users/dlq-count")
            .retrieve()
            .toEntity(Map.class)
            .block();

        assertThat(finalDlqResponse.getBody().get("count")).isEqualTo(0);
    }
}