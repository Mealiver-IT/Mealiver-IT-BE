package com.mealiverit.api.verification.report;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Component
public class NotionReportGenerator {

    private static final String NOTION_VERSION = "2022-06-28";
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final RestClient restClient = RestClient.create();

    @Value("${notion.api.token}")
    private String apiToken;

    @Value("${notion.database-id}")
    private String databaseId;

    /**
     * 검증 결과 Notion 페이지 생성
     *
     * @return 생성된 Notion 페이지 URL
     */
    private static final int NOTION_CHILDREN_LIMIT = 100;

    public String generate(ConsistencyReport report) {

        List<Map<String, Object>> allBlocks = buildChildren(report);

        // 1. 첫 100개 블록으로 페이지 생성
        List<Map<String, Object>> firstBatch =
                allBlocks.subList(
                        0,
                        Math.min(allBlocks.size(), NOTION_CHILDREN_LIMIT)
                );

        Map<String, Object> payload = Map.of(
                "parent", Map.of("database_id", databaseId),
                "properties", buildProperties(report),
                "children", firstBatch
        );

        Map<String, Object> response = restClient.post()
                .uri("https://api.notion.com/v1/pages")
                .header("Authorization", "Bearer " + apiToken)
                .header("Notion-Version", NOTION_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(Map.class);

        String pageId = (String) response.get("id");
        String pageUrl = (String) response.get("url");

        // 2. 나머지 최상위 블록은 100개씩 나눠서 append
        if (allBlocks.size() > NOTION_CHILDREN_LIMIT) {

            List<Map<String, Object>> remaining =
                    allBlocks.subList(NOTION_CHILDREN_LIMIT, allBlocks.size());

            for (int i = 0; i < remaining.size(); i += NOTION_CHILDREN_LIMIT) {

                List<Map<String, Object>> chunk =
                        remaining.subList(
                                i,
                                Math.min(i + NOTION_CHILDREN_LIMIT, remaining.size())
                        );

                appendBlocks(pageId, chunk);
            }
        }

        // 3. 이상 상세: 토글별 표가 99행을 넘으면 나머지 행을 표 블록에 직접 append
        Map<CheckType, List<AnomalyDetail>> grouped =
                report.anomalyDetails().stream()
                        .collect(Collectors.groupingBy(
                                AnomalyDetail::checkType,
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));

        grouped.forEach((checkType, details) -> {
            if (details.size() > NOTION_CHILDREN_LIMIT - 1) {
                List<AnomalyDetail> remaining =
                        details.subList(NOTION_CHILDREN_LIMIT - 1, details.size());
                appendRemainingAnomalyRows(pageId, checkType, remaining);
            }
        });

        return pageUrl;
    }


    private void appendBlocks(String pageId, List<Map<String, Object>> children) {

        restClient.patch()
                .uri("https://api.notion.com/v1/blocks/{pageId}/children", pageId)
                .header("Authorization", "Bearer " + apiToken)
                .header("Notion-Version", NOTION_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("children", children))
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, Object> buildProperties(ConsistencyReport report) {

        return Map.of(
                "이름", Map.of(
                        "title", List.of(
                                Map.of("text", Map.of(
                                        "content", report.jobName()
                                                + " ("
                                                + report.jobExecutionId()
                                                + ")"
                                ))
                        )
                ),
                "상태", Map.of(
                        "select", Map.of(
                                "name", report.hasAnomalies()
                                        ? "이상 발견"
                                        : "정상"
                        )
                ),
                "이상 건수", Map.of(
                        "number", report.totalViolationCount()
                ),
                "실행일시", Map.of(
                        "date", Map.of(
                                "start", report.startTime() != null
                                        ? report.startTime().format(DATE_FORMATTER)
                                        : null
                        )
                )
        );
    }

    private List<Map<String, Object>> buildChildren(ConsistencyReport report) {

        List<Map<String, Object>> blocks = new ArrayList<>();

        // ===== 요약 (콜아웃) =====
        boolean hasAnomalies = report.hasAnomalies();

        blocks.add(callout(
                String.format(
                        "%s\n이상 건수 %,d건(전체 %,d건 중)\n실행 시간 %s",
                        hasAnomalies ? "이상값 발견" : "정상",
                        report.totalViolationCount(),
                        report.totalVerificationCount(),
                        formatDuration(report.durationMillis())
                ),
                hasAnomalies ? "red_background" : "green_background",
                hasAnomalies ? "⚠️" : "✅"
        ));

        // ===== 실행 정보 =====
        // ===== 실행 정보 (표) =====
        blocks.add(heading2("실행 정보"));
        blocks.add(buildExecutionInfoTable(report));

        // ===== 검증 항목별 결과 (표) =====
        blocks.add(heading2("검증 항목별 결과"));
        blocks.add(buildCheckTypeTable(report.anomalyCounts()));

        blocks.add(divider());

        // ===== 스텝 실행 통계 (표) =====
        blocks.add(heading2("스텝 실행 통계"));

        if (report.stepExecutions().isEmpty()) {
            blocks.add(paragraph("Step 실행 정보가 없습니다."));
        } else {
            blocks.add(buildStepTable(report.stepExecutions()));
        }

        // ===== 실패한 Step =====
        if (!report.failedSteps().isEmpty()) {
            blocks.add(heading2("실패한 Step"));
            blocks.add(paragraph(String.join(", ", report.failedSteps())));
        }

        blocks.add(divider());

        // ===== 이상 상세 내역 (CheckType별 토글 + 표) =====
        blocks.add(heading2("이상 상세 내역"));

        if (report.anomalyDetails().isEmpty()) {
            blocks.add(paragraph("발견된 이상 내역이 없습니다."));
        } else {

            Map<CheckType, List<AnomalyDetail>> grouped =
                    report.anomalyDetails().stream()
                            .collect(Collectors.groupingBy(
                                    AnomalyDetail::checkType,
                                    LinkedHashMap::new,
                                    Collectors.toList()
                            ));

            grouped.forEach((checkType, details) ->
                    blocks.add(buildAnomalyToggle(checkType, details))
            );
        }

        return blocks;
    }

    private Map<String, Object> heading2(String text) {
        return Map.of(
                "object", "block",
                "type", "heading_2",
                "heading_2", Map.of(
                        "rich_text", List.of(
                                Map.of("text", Map.of("content", text))
                        )
                )
        );
    }

    private Map<String, Object> paragraph(String text) {
        return Map.of(
                "object", "block",
                "type", "paragraph",
                "paragraph", Map.of(
                        "rich_text", List.of(
                                Map.of("text", Map.of("content", text))
                        )
                )
        );
    }
    
    private Map<String, Object> bulletedItem(String text) {
        return Map.of(
                "object", "block",
                "type", "bulleted_list_item",
                "bulleted_list_item", Map.of(
                        "rich_text", List.of(
                                Map.of("text", Map.of("content", text))
                        )
                )
        );
    }
    
    private Map<String, Object> divider() {
        return Map.of(
                "object", "block",
                "type", "divider",
                "divider", Map.of()
        );
    }

    private Map<String, Object> callout(String text, String color, String emoji) {
        return Map.of(
                "object", "block",
                "type", "callout",
                "callout", Map.of(
                        "rich_text", richText(text),
                        "icon", Map.of("type", "emoji", "emoji", emoji),
                        "color", color
                )
        );
    }

    private List<Map<String, Object>> richText(String text) {
        return List.of(
                Map.of("type", "text", "text", Map.of("content", text))
        );
    }

    private List<Map<String, Object>> richTextColored(String text, String color) {
        return List.of(
                Map.of(
                        "type", "text",
                        "text", Map.of("content", text),
                        "annotations", Map.of("color", color)
                )
        );
    }

    private Map<String, Object> tableRow(List<List<Map<String, Object>>> cells) {
        return Map.of(
                "object", "block",
                "type", "table_row",
                "table_row", Map.of("cells", cells)
        );
    }

    private Map<String, Object> buildCheckTypeTable(Map<CheckType, Long> anomalyCounts) {

        List<Map<String, Object>> rows = new ArrayList<>();

        rows.add(tableRow(List.of(
                richText("검증 항목"),
                richText("결과"),
                richText("이상 건수")
        )));

        anomalyCounts.forEach((checkType, count) -> {
            boolean normal = count == 0;
            rows.add(tableRow(List.of(
                    richText(checkType.label()),
                    richTextColored(
                            normal ? "정상" : "이상 발견",
                            normal ? "green" : "red"
                    ),
                    richText(String.format("%,d건", count))
            )));
        });

        return Map.of(
                "object", "block",
                "type", "table",
                "table", Map.of(
                        "table_width", 3,
                        "has_column_header", true,
                        "has_row_header", false,
                        "children", rows
                )
        );
    }

    private Map<String, Object> buildStepTable(List<StepExecutionSummary> steps) {

        List<Map<String, Object>> rows = new ArrayList<>();

        rows.add(tableRow(List.of(
                richText("Step"),
                richText("상태"),
                richText("실행 시간"),
                richText("스캔 건수"),
                richText("이상 건수")
        )));

        steps.forEach(step -> {
            boolean success = "COMPLETED".equals(step.status());
            rows.add(tableRow(List.of(
                    richText(step.stepName()),
                    richTextColored(step.status(), success ? "green" : "red"),
                    richText(formatDuration(step.durationMillis())),
                    richText(String.format("%,d", step.scannedCount())),
                    richText(String.format("%,d", step.writeCount()))
            )));
        });

        return Map.of(
                "object", "block",
                "type", "table",
                "table", Map.of(
                        "table_width", 5,
                        "has_column_header", true,
                        "has_row_header", false,
                        "children", rows
                )
        );
    }

    private Map<String, Object> buildAnomalyToggle(
            CheckType checkType,
            List<AnomalyDetail> details
    ) {

        List<Map<String, Object>> rows = new ArrayList<>();

        rows.add(tableRow(List.of(
                richText("Reference ID"),
                richText("상세 내용"),
                richText("발견 시간")
        )));

        // 첫 99건만 초기 요청에 포함 (헤더 1행 + 데이터 최대 99행 = 100행 제한 준수)
        details.stream()
                .limit(NOTION_CHILDREN_LIMIT - 1)
                .forEach(detail -> rows.add(tableRow(List.of(
                        richText(detail.referenceId()),
                        richText(detail.detail()),
                        richText(detail.detectedAt().toString())
                ))));

        Map<String, Object> table = Map.of(
                "object", "block",
                "type", "table",
                "table", Map.of(
                        "table_width", 3,
                        "has_column_header", true,
                        "has_row_header", false,
                        "children", rows
                )
        );

        return Map.of(
                "object", "block",
                "type", "toggle",
                "toggle", Map.of(
                        "rich_text", richText(String.format(
                                "%s (%,d건)",
                                checkType.label(),
                                details.size()
                        )),
                        "children", List.of(table)
                )
        );
    }

    @SuppressWarnings("unchecked")
    private void appendRemainingAnomalyRows(
            String pageId,
            CheckType checkType,
            List<AnomalyDetail> remaining
    ) {

        String toggleId = findToggleBlockId(pageId, checkType);
        if (toggleId == null) {
            return;
        }

        String tableId = findTableBlockId(toggleId);
        if (tableId == null) {
            return;
        }

        for (int i = 0; i < remaining.size(); i += NOTION_CHILDREN_LIMIT) {

            List<AnomalyDetail> chunk =
                    remaining.subList(
                            i,
                            Math.min(i + NOTION_CHILDREN_LIMIT, remaining.size())
                    );

            List<Map<String, Object>> rows = chunk.stream()
                    .map(detail -> tableRow(List.of(
                            richText(detail.referenceId()),
                            richText(detail.detail()),
                            richText(detail.detectedAt().toString())
                    )))
                    .toList();

            appendBlocks(tableId, rows);
        }
    }

    @SuppressWarnings("unchecked")
    private String findToggleBlockId(String pageId, CheckType checkType) {

        Map<String, Object> response = restClient.get()
                .uri("https://api.notion.com/v1/blocks/{pageId}/children?page_size=100", pageId)
                .header("Authorization", "Bearer " + apiToken)
                .header("Notion-Version", NOTION_VERSION)
                .retrieve()
                .body(Map.class);

        List<Map<String, Object>> results =
                (List<Map<String, Object>>) response.get("results");

        for (Map<String, Object> block : results) {

            if (!"toggle".equals(block.get("type"))) {
                continue;
            }

            Map<String, Object> toggle = (Map<String, Object>) block.get("toggle");
            List<Map<String, Object>> richText =
                    (List<Map<String, Object>>) toggle.get("rich_text");

            String text = (String) ((Map<String, Object>)
                    richText.get(0).get("text")).get("content");

            if (text.startsWith(checkType.label())) {
                return (String) block.get("id");
            }
        }

        return null;
    }
    private Map<String, Object> buildExecutionInfoTable(ConsistencyReport report) {

        List<Map<String, Object>> rows = new ArrayList<>();

        rows.add(tableRow(List.of(
                richText("Job"),
                richText(report.jobName())
        )));

        rows.add(tableRow(List.of(
                richText("Job Execution ID"),
                richText(String.valueOf(report.jobExecutionId()))
        )));

        rows.add(tableRow(List.of(
                richText("시작 시간"),
                richText(report.startTime() != null
                        ? report.startTime().toString()
                        : "-")
        )));

        rows.add(tableRow(List.of(
                richText("종료 시간"),
                richText(report.endTime() != null
                        ? report.endTime().toString()
                        : "-")
        )));

        boolean success = "COMPLETED".equals(report.status().name());

        rows.add(tableRow(List.of(
                richText("상태"),
                richTextColored(
                        report.status().name(),
                        success ? "green" : "red"
                )
        )));

        return Map.of(
                "object", "block",
                "type", "table",
                "table", Map.of(
                        "table_width", 2,
                        "has_column_header", false,
                        "has_row_header", true,
                        "children", rows
                )
        );
    }

    @SuppressWarnings("unchecked")
    private String findTableBlockId(String toggleId) {

        Map<String, Object> response = restClient.get()
                .uri("https://api.notion.com/v1/blocks/{toggleId}/children", toggleId)
                .header("Authorization", "Bearer " + apiToken)
                .header("Notion-Version", NOTION_VERSION)
                .retrieve()
                .body(Map.class);

        List<Map<String, Object>> results =
                (List<Map<String, Object>>) response.get("results");

        if (results.isEmpty()) {
            return null;
        }

        return (String) results.get(0).get("id");
    }
    
    private String formatDuration(long millis) {

        if (millis <= 0) {
            return "0초";
        }

        long totalSeconds = millis / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        return minutes > 0
                ? String.format("%d분 %d초", minutes, seconds)
                : String.format("%d초", seconds);
    }
}