package com.mealiverit.api.verification.report;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SlackNotifier {

    private final RestClient restClient = RestClient.create();

    @Value("${slack.consistency-webhook-url}")
    private String webhookUrl;

    public void send(
    		ConsistencyReport report,
    		String reportUrl
    		) {
        Map<String, Object> payload = Map.of("blocks", buildBlocks(report, reportUrl));

        restClient.post()
            .uri(webhookUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .toBodilessEntity();
    }

    private List<Map<String, Object>> buildBlocks(
    		ConsistencyReport report,
    		String reportUrl
    		) {
        String statusEmoji = report.hasAnomalies() ? ":warning: 이상값 발견" : ":white_check_mark: 정상";

        List<Map<String, Object>> blocks = new ArrayList<>();

        blocks.add(Map.of(
            "type", "header",
            "text", Map.of("type", "plain_text", "text", report.jobName() + " 검증 결과")
        ));

        blocks.add(Map.of(
            "type", "section",
            "text", Map.of("type", "mrkdwn",
                "text", statusEmoji + "\n*실행 시각*: " + report.startTime())
        ));

        
        report.anomalyCounts().forEach((checkType, count) ->
            blocks.add(Map.of(
                "type", "section",
                "text", Map.of("type", "mrkdwn",
                    "text", String.format("`%s` %s: *%d건*", checkType.name(), checkType.label(), count))
            ))
        );
        
        // reportUrl이 없는 호출측(예: StockLossRepairJob - Notion 리포트를 안 만듦)도 있어서,
        // Map.of("url", null)로 NPE가 나지 않게 버튼 블록 자체를 건너뛴다.
        if (reportUrl != null && !reportUrl.isBlank()) {
            blocks.add(Map.of(
                    "type", "actions",
                    "elements", List.of(
                            Map.of(
                                    "type", "button",
                                    "text", Map.of(
                                            "type",
                                            "plain_text",
                                            "text",
                                            "📊 상세 리포트 보기"
                                    ),
                                    "url",
                                    reportUrl
                            )
                    )
            ));
        }

        if (!report.failedSteps().isEmpty()) {
            blocks.add(Map.of(
                "type", "section",
                "text", Map.of("type", "mrkdwn",
                    "text", ":x: *실패한 Step*: " + String.join(", ", report.failedSteps()))
            ));
        }

        return blocks;
    }
}