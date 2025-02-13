package com.yoganavi.integration.test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;


import java.util.concurrent.TimeUnit;


@SpringBootTest
class UserSynchronizationTest {

    private static final Logger log = LoggerFactory.getLogger(UserSynchronizationTest.class);


    private final WebClient userServiceClient = WebClient.builder()
        .baseUrl("http://localhost:8081")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();

    private final WebClient lectureServiceClient = WebClient.builder()
        .baseUrl("http://localhost:8082")
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();

    @Test
    void 회원가입_동기화_테스트() {
        String email = "test@test.com";
        String password = "password123";
        String nickname = "testUser";

        // 이메일 인증 요청
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

        // 회원가입 요청
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

        // Lecture 서비스 동기화 확인
        await()
            .atMost(5, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ResponseEntity<Map> response = lectureServiceClient
                    .get()
                    .uri("/test/email/" + email)
                    .retrieve()
                    .toEntity(Map.class)
                    .block();

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                Map<String, Object> user = response.getBody();
                assertThat(user).isNotNull();
                assertThat(user.get("email")).isEqualTo(email);
                assertThat(user.get("nickname")).isEqualTo(nickname);
            });
    }

    @Test
    void 회원가입_실패_테스트() {
        // Given: 잘못된 이메일 형식
        Map<String, Object> request = Map.of(
            "email", "invalid-email",
            "password", "password123",
            "nickname", "testUser",
            "teacher", true
        );

        // When & Then: 회원가입 실패 확인
        ResponseEntity<Map> response = userServiceClient
            .post()
            .uri("/user/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .onStatus(
                status -> status.equals(HttpStatus.BAD_REQUEST),
                clientResponse -> Mono.empty()
            )
            .toEntity(Map.class)
            .block();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 이메일_중복_회원가입_테스트() {
        // Given: 첫 번째 회원가입
        Map<String, Object> request = Map.of(
            "email", "duplicate@test.com",
            "password", "password123",
            "nickname", "testUser1",
            "teacher", true
        );

        ResponseEntity<Map> firstResponse = userServiceClient
            .post()
            .uri("/user/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .toEntity(Map.class)
            .block();

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // When: 같은 이메일로 두 번째 회원가입 시도
        Map<String, Object> duplicateRequest = Map.of(
            "email", "duplicate@test.com",
            "password", "password456",
            "nickname", "testUser2",
            "teacher", false
        );

        // Then: 중복 가입 실패 확인
        ResponseEntity<Map> duplicateResponse = userServiceClient
            .post()
            .uri("/user/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(duplicateRequest)
            .retrieve()
            .onStatus(
                status -> status.equals(HttpStatus.BAD_REQUEST),
                clientResponse -> Mono.empty()
            )
            .toEntity(Map.class)
            .block();

        assertThat(duplicateResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<Map> 회원가입_요청_실행(String email, String password, String nickname) {
        // 이메일 인증 요청
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

        // 회원가입 요청
        RegisterDto registerRequest = RegisterDto.builder()
            .email(email)
            .password(password)
            .nickname(nickname)
            .teacher(true)
            .build();

        return userServiceClient
            .post()
            .uri("/user/register")
            .bodyValue(registerRequest)
            .retrieve()
            .toEntity(Map.class)
            .block();
    }

    private void 동기화_확인(String email, String nickname) {
        await()
            .atMost(5, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ResponseEntity<Map> response = lectureServiceClient
                    .get()
                    .uri("/test/email/" + email)
                    .retrieve()
                    .toEntity(Map.class)
                    .block();

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                Map<String, Object> user = response.getBody();
                assertThat(user).isNotNull();
                assertThat(user.get("email")).isEqualTo(email);
                assertThat(user.get("nickname")).isEqualTo(nickname);
            });
    }

    @Test
    void 동시_다중_회원가입_테스트() throws InterruptedException {
        int userCount = 100;
        CountDownLatch latch = new CountDownLatch(userCount);
        ExecutorService executorService = Executors.newFixedThreadPool(10);
        List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();

        for (int i = 0; i < userCount; i++) {
            final String email = "concurrent" + i + "@test.com";
            final String nickname = "concurrent" + i;

            futures.add(executorService.submit(() -> {
                try {
                    return 회원가입_요청_실행(email, "password123", nickname);
                } finally {
                    latch.countDown();
                }
            }));
        }

        latch.await(30, TimeUnit.SECONDS);

        // 모든 사용자 동기화 확인
        for (int i = 0; i < userCount; i++) {
            final String email = "concurrent" + i + "@test.com";
            final String nickname = "concurrent" + i;
            동기화_확인(email, nickname);
        }

        executorService.shutdown();
    }

    @Test
    void 부분_실패_보상_트랜잭션_테스트() {
        // 잘못된 데이터로 회원가입
        String email = "invalid.user@test.com";
        String nickname = "이름이너무너무너무너무너무너무너무너무너무너무너무너무너무너무길어서ㅓㅓㅓㅓㅓㅓㅓㅓㅓㅓㅓㅓㅓㅓㅓㅓㅓㅓㅓㅓㅓ실패";

        RegisterDto registerRequest = RegisterDto.builder()
            .email(email)
            .password("password123")
            .nickname(nickname)
            .teacher(true)
            .build();

        ResponseEntity<Map> response = userServiceClient
            .post()
            .uri("/user/register")
            .bodyValue(registerRequest)
            .retrieve()
            .onStatus(
                status -> status.equals(HttpStatus.BAD_REQUEST),
                clientResponse -> Mono.empty()
            )
            .toEntity(Map.class)
            .block();

        // 실패 확인
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 보상 트랜잭션으로 인해 데이터가 없어야 함
        await()
            .atMost(5, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                ResponseEntity<Map> checkResponse = lectureServiceClient
                    .get()
                    .uri("/test/email/" + email)
                    .retrieve()
                    .onStatus(
                        status -> status.equals(HttpStatus.NOT_FOUND),
                        clientResponse -> Mono.empty()
                    )
                    .toEntity(Map.class)
                    .block();

                assertThat(checkResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            });
    }

    @Test
    void 중복_이벤트_멱등성_테스트() {
        String email = "idempotent@test.com";
        String nickname = "idempotent";

        // 동일한 회원가입 요청을 3번 실행
        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map> response = 회원가입_요청_실행(email, "password123", nickname);
            if (i == 0) {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            } else {
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            }
        }

        // lecture 서비스에도 한 번만 동기화되었는지 확인
        동기화_확인(email, nickname);
    }

}