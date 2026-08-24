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

    public void send(ConsistencyReport report) {
        Map<String, Object> payload = Map.of("blocks", buildBlocks(report));

        restClient.post()
            .uri(webhookUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .toBodilessEntity();
    }

    private List<Map<String, Object>> buildBlocks(ConsistencyReport report) {
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