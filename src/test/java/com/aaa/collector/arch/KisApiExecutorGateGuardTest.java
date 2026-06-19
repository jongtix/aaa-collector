package com.aaa.collector.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.aaa.collector.kis.KisApiExecutor;
import com.aaa.collector.kis.KisApiResponse;
import com.aaa.collector.kis.token.KisAccountCredential;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.net.URI;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriBuilder;

/**
 * KIS 게이트-전용 호출 가드 테스트 (SPEC-COLLECTOR-KISGATE-001 REQ-KISGATE-008, AC-2).
 *
 * <p>규칙: {@link KisApiExecutor#executeGet}(3-arg·4-arg 두 오버로드 모두)는 {@code
 * com.aaa.collector.kis.gate} 패키지 내부 클래스(즉 {@link
 * com.aaa.collector.kis.gate.GuardedKisExecutor})에서만 직접 호출될 수 있다. 그 외 어떤 클래스도 {@code executeGet}을
 * 직접 호출하면 빌드가 실패한다. {@code executeGet}은 {@code public}으로 유지되며(DP1=B), 가시성 축소 대신 본 ArchUnit 가드로 게이트
 * 경유를 강제한다.
 *
 * <p><b>구현 방식 선택(DSL vs ClassFileImporter)</b>: {@link Tier1InsertIgnoreGuardTest}는 {@code @Query}
 * 문자열 내용 매칭이라는 빌트인 술어 부재 때문에 {@link ClassFileImporter} 수동 스캔을 택했다. 본 규칙은 그와 달리 "메서드 호출 의존성"이라는
 * ArchUnit fluent DSL이 직접 표현하는 1급 개념이므로 {@code noClasses().should().callMethodWhere(...)} DSL을
 * 채택한다. 두 오버로드는 메서드명({@code executeGet})으로 함께 매칭한다(D-MI2 — 호출 가드 관점에서 두 시그니처를 구별할 필요 없음). 신규 의존성 0:
 * ArchUnit 1.4.2는 이미 {@code testImplementation}으로 존재한다(REQ-KISGATE-004).
 *
 * <p><b>RED→GREEN 입증(AC-2)</b>:
 *
 * <ul>
 *   <li><b>RED</b>: 마이그레이션 전(M2 이전)에는 패턴 A 맨몸 호출({@code NewsTitleCollectionService} 등 3-arg)과 패턴
 *       B/C({@code BatchRestExecutor}/{@code KisWatchlistClient} 등 4-arg)가 {@code kis.gate} 외부에서
 *       {@code executeGet}을 직접 호출했다 → 규칙 위반으로 빌드 실패. M2~M6 이전 완료 후 유일한 직접 호출자는 {@code
 *       GuardedKisExecutor}(kis.gate 내부)뿐이다.
 *   <li>본 테스트는 그 RED 상태를 두 오버로드 각각의 in-test fixture({@link Fixtures.ThreeArgCaller}/{@link
 *       Fixtures.FourArgCaller}, 모두 {@code com.aaa.collector.arch} = kis.gate 외부)로 재현하고, 규칙이 두 위반을
 *       모두 탐지함을 단언한다.
 *   <li><b>GREEN</b>: 실제 프로덕션 코드({@code DoNotIncludeTests})에 대해 규칙을 {@code check()}하면 위반이 0건이다.
 * </ul>
 *
 * <p>Spring 컨텍스트 불필요 — 순수 클래스파일 스캔이므로 {@code @SpringBootTest} 없이 실행된다.
 */
@DisplayName("KIS executeGet 게이트-전용 호출 가드 (REQ-KISGATE-008, AC-2)")
class KisApiExecutorGateGuardTest {

    /** 게이트 패키지(이 패키지 내부 클래스만 executeGet 직접 호출 허용). */
    private static final String GATE_PACKAGE = "com.aaa.collector.kis.gate";

    /**
     * {@code KisApiExecutor.executeGet}(두 오버로드) 호출을 메서드명·소유 타입으로 매칭하는 술어. 메서드명만으로 3-arg/4-arg를 함께
     * 포착한다(D-MI2).
     */
    private static final DescribedPredicate<JavaMethodCall> CALL_TO_EXECUTE_GET =
            DescribedPredicate.describe(
                    "KisApiExecutor.executeGet 직접 호출 (3-arg·4-arg)",
                    call ->
                            "executeGet".equals(call.getTarget().getName())
                                    && call.getTarget()
                                            .getOwner()
                                            .isAssignableTo(KisApiExecutor.class));

    /**
     * 게이트-전용 호출 규칙. {@code kis.gate} 외부 클래스는 {@code executeGet}을 직접 호출할 수 없다.
     *
     * <p>두 가지 정당한 호출은 규칙 대상에서 제외된다:
     *
     * <ul>
     *   <li>{@code GuardedKisExecutor}(kis.gate 내부) — 게이트가 4-arg를 정상 호출한다.
     *   <li>{@link KisApiExecutor} 자기 자신 — 3-arg 편의 오버로드가 firstCredential을 채워 4-arg로 위임하는 내부 오버로드
     *       호출(self-delegation)은 "외부 직접 호출"이 아니다.
     * </ul>
     */
    private static final ArchRule GATE_ONLY_EXECUTE_GET =
            noClasses()
                    .that()
                    .resideOutsideOfPackage(GATE_PACKAGE)
                    .and()
                    .areNotAssignableTo(KisApiExecutor.class)
                    .should()
                    .callMethodWhere(CALL_TO_EXECUTE_GET)
                    .as(
                            "com.aaa.collector.kis.gate 외부 클래스(KisApiExecutor 자기 위임 제외)는"
                                    + " KisApiExecutor.executeGet을 직접 호출할 수 없다 (REQ-KISGATE-008 —"
                                    + " GuardedKisExecutor 게이트 경유 강제)")
                    .because(
                            "executeGet은 lease+throttle+retry 보호 없이 KIS REST를 직접 때린다. 게이트 외부 직접 호출은"
                                    + " rate-limit 폭주·키 소진 미처리를 유발하므로 빌드 단계에서 차단한다 (DP1=B: public"
                                    + " 유지 + ArchUnit 가드).");

    @Test
    @DisplayName("GREEN — 프로덕션 코드: executeGet 직접 호출자는 GuardedKisExecutor(kis.gate)뿐이다")
    void productionCode_onlyGateCallsExecuteGet_passes() {
        // Act & Assert — 프로덕션 클래스만 스캔(테스트 fixture 제외). 위반 시 check()가 AssertionError를 던진다.
        // M2~M6 이전 완료로 GREEN. (importer 결과를 지역 변수로 받지 않고 인라인 — PMD LooseCoupling 회피)
        GATE_ONLY_EXECUTE_GET.check(
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("com.aaa.collector"));
    }

    @Test
    @DisplayName("RED 입증 — kis.gate 외부의 3-arg·4-arg 위반 호출을 규칙이 모두 탐지한다 (AC-2, D-MI2)")
    void rule_detectsBothOverloadViolations_outsideGate() {
        // Act — fixture 포함 스캔(arch 패키지 = kis.gate 외부, 마이그레이션 전 RED 재현)을 throw 없이 평가한다.
        EvaluationResult result =
                GATE_ONLY_EXECUTE_GET.evaluate(
                        new ClassFileImporter().importPackages("com.aaa.collector.arch"));
        List<String> violations = result.getFailureReport().getDetails();

        // Assert — RED: 위반이 존재하고, 3-arg/4-arg 두 fixture가 각각 탐지된다.
        assertThat(result.hasViolation())
                .as("kis.gate 외부의 executeGet 직접 호출은 규칙 위반으로 탐지되어야 한다 (RED 입증)")
                .isTrue();
        assertThat(violations)
                .as("3-arg executeGet 외부 호출(ThreeArgCaller)이 위반으로 보고되어야 한다 (D-MI2)")
                .anyMatch(detail -> detail.contains("ThreeArgCaller"));
        assertThat(violations)
                .as("4-arg executeGet 외부 호출(FourArgCaller)이 위반으로 보고되어야 한다 (D-MI2)")
                .anyMatch(detail -> detail.contains("FourArgCaller"));
    }

    /**
     * RED 입증용 fixture — {@code com.aaa.collector.arch}(= kis.gate 외부)에서 {@code executeGet} 두 오버로드를
     * 각각 직접 호출한다. 이 클래스들은 실제로 실행되지 않으며(클래스파일 스캔 대상일 뿐) 마이그레이션 전 외부 직접 호출 패턴을 재현한다.
     *
     * <p>프로덕션 GREEN 테스트는 {@code DoNotIncludeTests}로 fixture를 제외하므로 본 fixture가 프로덕션 가드를 오염시키지 않는다.
     */
    @SuppressWarnings("unused")
    static final class Fixtures {

        private Fixtures() {}

        /** 마이그레이션 전 패턴 A(맨몸 3-arg) 외부 직접 호출 재현. */
        static final class ThreeArgCaller {
            private final KisApiExecutor executor;

            ThreeArgCaller(KisApiExecutor executor) {
                this.executor = executor;
            }

            StubResponse callThreeArg() {
                Function<UriBuilder, URI> uriCustomizer = b -> URI.create("/test");
                return executor.executeGet(uriCustomizer, "TR001", StubResponse.class);
            }
        }

        /** 마이그레이션 전 패턴 B/C(멀티키 4-arg) 외부 직접 호출 재현. */
        static final class FourArgCaller {
            private final KisApiExecutor executor;

            FourArgCaller(KisApiExecutor executor) {
                this.executor = executor;
            }

            StubResponse callFourArg() {
                KisAccountCredential credential =
                        new KisAccountCredential("isa", "11111111", "appkey", "appsecret");
                Function<UriBuilder, URI> uriCustomizer = b -> URI.create("/test");
                return executor.executeGet(credential, uriCustomizer, "TR001", StubResponse.class);
            }
        }

        /** 최소 {@link KisApiResponse} 스텁(fixture 호출의 반환 타입). */
        static final class StubResponse implements KisApiResponse {
            @Override
            public String rtCd() {
                return "0";
            }

            @Override
            public String msgCd() {
                return "00000";
            }

            @Override
            public String msg1() {
                return "OK";
            }
        }
    }
}
