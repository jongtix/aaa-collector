package com.aaa.collector.dart.external;

import com.aaa.collector.dart.corpcode.CorpCodeEntry;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * OpenDART corpCode.zip API 클라이언트 (SPEC-COLLECTOR-DART-001 REQ-DART-002).
 *
 * <p>api-specs/dart/02-고유번호.md 실측 기준(2026-06-25). ZIP 바이너리 응답 → {@code CORPCODE.xml} 파싱 → {@link
 * CorpCodeEntry} 목록 반환.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DartCorpCodeClient {

    static final String CORP_CODE_PATH = "/api/corpCode.xml";
    static final String CORP_CODE_XML_ENTRY = "CORPCODE.xml";
    private static final DateTimeFormatter MODIFY_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DartProperties dartProperties;

    private final RestClient dartRestClient;

    /**
     * corpCode.zip을 다운로드하고 파싱하여 전체 기업 목록을 반환한다.
     *
     * <p>상장사(stock_code 비어 있지 않음)만 반환하며, 비상장사(stock_code 빈값)는 필터링한다(REQ-DART-002).
     *
     * @return 상장사 CorpCodeEntry 목록
     */
    public List<CorpCodeEntry> fetchListedEntries() {
        log.info("[dart-corpcode] corpCode.zip 다운로드 시작");
        byte[] zipBytes = fetchZipBytes();
        List<CorpCodeEntry> all = parseZip(zipBytes);
        List<CorpCodeEntry> listed = all.stream().filter(CorpCodeEntry::isListed).toList();
        log.info("[dart-corpcode] 파싱 완료 — 전체={}, 상장사={}", all.size(), listed.size());
        return listed;
    }

    private byte[] fetchZipBytes() {
        return dartRestClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path(CORP_CODE_PATH)
                                        .queryParam("crtfc_key", dartProperties.getApiKey())
                                        .build())
                .retrieve()
                .body(byte[].class);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // ZIP/XML 파싱 오류 격리
    private List<CorpCodeEntry> parseZip(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            log.warn("[dart-corpcode] ZIP 응답 null/empty");
            return List.of();
        }

        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            return findAndParseCorpCodeXml(zis);
        } catch (Exception e) {
            log.error("[dart-corpcode] ZIP 파싱 실패: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings(
            "PMD.SignatureDeclareThrowsException") // ZipInputStream.getNextEntry()가 IOException을
    // 던지므로 불가피
    private List<CorpCodeEntry> findAndParseCorpCodeXml(ZipInputStream zis) throws Exception {
        ZipEntry entry = zis.getNextEntry();
        while (entry != null) {
            if (CORP_CODE_XML_ENTRY.equalsIgnoreCase(entry.getName())) {
                return parseXml(zis);
            }
            zis.closeEntry();
            entry = zis.getNextEntry();
        }
        log.warn("[dart-corpcode] {} 엔트리 없음", CORP_CODE_XML_ENTRY);
        return List.of();
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException") // XML 파싱 오류 격리
    private List<CorpCodeEntry> parseXml(InputStream inputStream) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(inputStream);
            doc.getDocumentElement().normalize();

            NodeList items = doc.getElementsByTagName("list");
            List<CorpCodeEntry> result = new ArrayList<>(items.getLength());
            for (int i = 0; i < items.getLength(); i++) {
                Element el = (Element) items.item(i);
                CorpCodeEntry corpCodeEntry = toEntry(el);
                result.add(corpCodeEntry);
            }
            return result;
        } catch (Exception e) {
            log.error("[dart-corpcode] XML 파싱 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private CorpCodeEntry toEntry(Element el) {
        String corpCode = text(el, "corp_code");
        String corpName = text(el, "corp_name");
        String stockCode = text(el, "stock_code");
        LocalDate modifyDate = parseModifyDate(text(el, "modify_date"));
        return new CorpCodeEntry(corpCode, corpName, stockCode, modifyDate);
    }

    private static String text(Element el, String tag) {
        NodeList nodes = el.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return "";
        }
        String value = nodes.item(0).getTextContent();
        return value != null ? value.trim() : "";
    }

    private LocalDate parseModifyDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw, MODIFY_DATE_FMT);
        } catch (DateTimeParseException e) {
            log.debug("[dart-corpcode] modify_date 파싱 실패 — raw={}", raw);
            return null;
        }
    }
}
