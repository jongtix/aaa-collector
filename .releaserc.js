// Gitmoji + Conventional Commits 파싱을 위한 headerPattern
// - \p{Emoji_Presentation}, \p{Extended_Pictographic}: Unicode 이모지 매칭 (u flag 필수)
// - \uFE0F?: Variation Selector-16 (♻️, 🏗️ 등 multi-codepoint 이모지 지원)
// - 캡처 그룹: (type)(scope)(breaking)(subject)
const HEADER_PATTERN =
    /^(?:[\p{Emoji_Presentation}\p{Extended_Pictographic}]\uFE0F?\s*)?(\w+)(?:\(([\w$.\-*\s]*)\))?(!)??:\s(.*)$/u;

const PARSER_OPTS = {
    headerPattern: HEADER_PATTERN,
    headerCorrespondence: ["type", "scope", "breaking", "subject"],
};

module.exports = {
    branches: ["main"],
    plugins: [
        // 커밋 메시지 분석 → 릴리즈 타입 결정 (feat→minor, fix/perf→patch, !→major)
        // custom 규칙 먼저 확인 → 매칭 없으면 기본 규칙 폴백 (feat, fix, perf, revert, breaking)
        // 기본 규칙에도 없는 타입(chore, docs, style 등)은 릴리즈를 트리거하지 않음
        [
            "@semantic-release/commit-analyzer",
            {
                preset: "conventionalcommits",
                parserOpts: PARSER_OPTS,
                releaseRules: [
                    { type: "feat", release: "minor" },
                    { type: "fix", release: "patch" },
                    { type: "perf", release: "patch" },
                    { breaking: true, release: "major" },
                ],
            },
        ],
        // 릴리즈 노트 자동 생성 (GitHub Release에 포함)
        [
            "@semantic-release/release-notes-generator",
            {
                preset: "conventionalcommits",
                parserOpts: PARSER_OPTS,
            },
        ],
        // GitHub Release 생성 + git 태그 push → docker.yml 트리거
        // 버전은 빌드 시점에 태그에서 -Pversion=으로 주입(SPEC-INFRA-CICD-002 F2) —
        // gradle.properties commit-back(@semantic-release/exec + @semantic-release/git)은 제거됨
        "@semantic-release/github",
    ],
};
