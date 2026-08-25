package com.mealiverit.api.verification.report;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class HtmlReportGenerator {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path reportDirectory;
    private final String baseUrl;

    public HtmlReportGenerator(
            @Value("${verification.report.directory:./reports}")
            String reportDirectory,
            @Value("${verification.report.base-url:http://localhost:8080}")
            String baseUrl
    ) {
        this.reportDirectory =
                Path.of(reportDirectory)
                        .toAbsolutePath()
                        .normalize();

        this.baseUrl = baseUrl;
    }

    /**
     * 검증 결과 HTML 파일 생성
     *
     * @return 생성된 HTML 파일의 절대 경로
     */
    public String generate(ConsistencyReport report) {

        try {
            Files.createDirectories(reportDirectory);

            String fileName =
                    buildFileName(report);

            Path reportFile =
                    reportDirectory.resolve(fileName);

            String html =
                    buildHtml(report);

            Files.writeString(
                    reportFile,
                    html,
                    StandardCharsets.UTF_8
            );

            return reportFile.toString();

        } catch (IOException e) {

            throw new IllegalStateException(
                    "검증 HTML 리포트 생성 실패. " +
                    "jobExecutionId=" +
                    report.jobExecutionId(),
                    e
            );
        }
    }

    /**
     * Slack에서 클릭할 HTML URL 생성
     */
    public String buildPublicUrl(
            ConsistencyReport report
    ) {

        String fileName =
                buildFileName(report);

        return baseUrl.replaceAll("/$", "")
                + "/reports/"
                + fileName;
    }

    private String buildFileName(
            ConsistencyReport report
    ) {

        return String.format(
                "%s_%d.html",
                report.jobName(),
                report.jobExecutionId()
        );
    }

    private String buildHtml(
            ConsistencyReport report
    ) {

        String statusClass =
                report.hasAnomalies()
                        ? "warning"
                        : "success";

        String statusText =
                report.hasAnomalies()
                        ? "이상값 발견"
                        : "정상";

        return """
                <!DOCTYPE html>
                <html lang="ko">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport"
                          content="width=device-width, initial-scale=1.0">

                    <title>쿠폰 정합성 검증 리포트</title>

                    <style>
                        * {
                            box-sizing: border-box;
                        }

                        body {
                            margin: 0;
                            padding: 40px;
                            background: #f5f7fa;
                            color: #1f2937;
                            font-family:
                                -apple-system,
                                BlinkMacSystemFont,
                                "Segoe UI",
                                Arial,
                                sans-serif;
                        }

                        .container {
                            max-width: 1200px;
                            margin: 0 auto;
                        }

                        h1 {
                            margin-bottom: 8px;
                        }

                        .subtitle {
                            color: #6b7280;
                            margin-bottom: 30px;
                        }

                        .summary {
                            display: grid;
                            grid-template-columns:
                                repeat(4, 1fr);
                            gap: 16px;
                            margin-bottom: 30px;
                        }

                        .card {
                            background: white;
                            border-radius: 10px;
                            padding: 20px;
                            box-shadow:
                                0 2px 8px
                                rgba(0, 0, 0, 0.06);
                        }

                        .card-title {
                            color: #6b7280;
                            font-size: 14px;
                            margin-bottom: 8px;
                        }

                        .card-value {
                            font-size: 25px;
                            font-weight: 700;
                        }

                        .success {
                            color: #16a34a;
                        }

                        .warning {
                            color: #dc2626;
                        }

                        .section {
                            background: white;
                            border-radius: 10px;
                            padding: 24px;
                            margin-bottom: 24px;
                            box-shadow:
                                0 2px 8px
                                rgba(0, 0, 0, 0.06);
                        }

                        .section h2 {
                            margin-top: 0;
                            margin-bottom: 20px;
                            font-size: 20px;
                        }

                        table {
                            width: 100%%;
                            border-collapse: collapse;
                        }

                        th,
                        td {
                            padding: 12px;
                            border-bottom:
                                1px solid #e5e7eb;
                            text-align: left;
                            font-size: 14px;
                        }

                        th {
                            background: #f9fafb;
                            font-weight: 600;
                        }

                        .badge {
                            display: inline-block;
                            padding: 4px 9px;
                            border-radius: 999px;
                            font-size: 12px;
                            font-weight: 600;
                        }

                        .badge-success {
                            background: #dcfce7;
                            color: #166534;
                        }

                        .badge-warning {
                            background: #fee2e2;
                            color: #991b1b;
                        }

                        .badge-failed {
                            background: #fee2e2;
                            color: #991b1b;
                        }

                        .badge-completed {
                            background: #dcfce7;
                            color: #166534;
                        }

                        .detail {
                            white-space: pre-wrap;
                            word-break: break-word;
                            max-width: 500px;
                        }

                        .empty {
                            text-align: center;
                            padding: 30px;
                            color: #6b7280;
                        }

                        @media (max-width: 800px) {
                            body {
                                padding: 20px;
                            }

                            .summary {
                                grid-template-columns:
                                    repeat(2, 1fr);
                            }
                        }

                        @media (max-width: 500px) {
                            .summary {
                                grid-template-columns: 1fr;
                            }
                        }
                    </style>
                </head>

                <body>

                <div class="container">

                    <h1>
                        쿠폰 정합성 검증 리포트
                    </h1>

                    <div class="subtitle">
                        %s
                        · Execution ID: %d
                    </div>

                    <!-- ========================= -->
                    <!-- 요약 -->
                    <!-- ========================= -->

                    <div class="summary">

                        <div class="card">
                            <div class="card-title">
                                검증 결과
                            </div>

                            <div class="card-value %s">
                                %s
                            </div>
                        </div>

                        <div class="card">
                            <div class="card-title">
                                전체 이상 건수
                            </div>

                            <div class="card-value %s">
                                %,d건
                            </div>
                        </div>

                        <div class="card">
                            <div class="card-title">
                                검증 처리 건수
                            </div>

                            <div class="card-value">
                                %,d건
                            </div>
                        </div>

                        <div class="card">
                            <div class="card-title">
                                실행 시간
                            </div>

                            <div class="card-value">
                                %s
                            </div>
                        </div>

                    </div>


                    <!-- ========================= -->
                    <!-- 실행 정보 -->
                    <!-- ========================= -->

                    <div class="section">

                        <h2>실행 정보</h2>

                        <table>

                            <tr>
                                <th>Job</th>
                                <td>%s</td>
                            </tr>

                            <tr>
                                <th>Job Execution ID</th>
                                <td>%d</td>
                            </tr>

                            <tr>
                                <th>시작 시간</th>
                                <td>%s</td>
                            </tr>

                            <tr>
                                <th>종료 시간</th>
                                <td>%s</td>
                            </tr>

                            <tr>
                                <th>상태</th>
                                <td>
                                    %s
                                </td>
                            </tr>

                        </table>

                    </div>


                    <!-- ========================= -->
                    <!-- 검증 항목별 결과 -->
                    <!-- ========================= -->

                    <div class="section">

                        <h2>검증 항목별 결과</h2>

                        <table>

                            <thead>
                                <tr>
                                    <th>검증 항목</th>
                                    <th>결과</th>
                                    <th>이상 건수</th>
                                </tr>
                            </thead>

                            <tbody>

                                %s

                            </tbody>

                        </table>

                    </div>


                    <!-- ========================= -->
                    <!-- Step 실행 통계 -->
                    <!-- ========================= -->

                    <div class="section">

                        <h2>Step 실행 통계</h2>

                        <table>

                            <thead>
                                <tr>
                                    <th>Step</th>
                                    <th>상태</th>
                                    <th>실행 시간</th>
                                    <th>Read</th>
                                    <th>Write</th>
                                    <th>Filter</th>
                                </tr>
                            </thead>

                            <tbody>

                                %s

                            </tbody>

                        </table>

                    </div>


                    <!-- ========================= -->
                    <!-- 이상 상세 -->
                    <!-- ========================= -->

                    <div class="section">

                        <h2>이상 상세 내역</h2>

                        %s

                    </div>

                </div>

                </body>
                </html>
                """.formatted(
                escapeHtml(report.jobName()),
                report.jobExecutionId(),

                statusClass,
                statusText,

                report.hasAnomalies()
                        ? "warning"
                        : "success",

                report.totalViolationCount(),

                report.totalVerificationCount(),

                formatDuration(
                        report.durationMillis()
                ),

                escapeHtml(report.jobName()),
                report.jobExecutionId(),

                formatDateTime(
                        report.startTime()
                ),

                formatDateTime(
                        report.endTime()
                ),

                buildStatusBadge(
                        report.status().name()
                ),

                buildAnomalyCountRows(
                        report.anomalyCounts()
                ),

                buildStepRows(
                        report
                ),

                buildAnomalyDetails(
                        report
                )
        );
    }

    /**
     * 검증 항목별 결과 테이블
     */
    private String buildAnomalyCountRows(
            Map<CheckType, Long> anomalyCounts
    ) {

        StringBuilder html =
                new StringBuilder();

        for (CheckType checkType :
                CheckType.values()) {

            long count =
                    anomalyCounts.getOrDefault(
                            checkType,
                            0L
                    );

            boolean normal = count == 0;

            html.append("""
                    <tr>
                        <td>%s</td>
                        <td>
                            <span class="badge %s">
                                %s
                            </span>
                        </td>
                        <td>%d건</td>
                    </tr>
                    """.formatted(
                    escapeHtml(
                            checkType.label()
                    ),

                    normal
                            ? "badge-success"
                            : "badge-warning",

                    normal
                            ? "정상"
                            : "이상 발견",

                    count
            ));
        }

        return html.toString();
    }

    /**
     * Step 실행 통계
     */
    private String buildStepRows(
            ConsistencyReport report
    ) {

        if (report.stepExecutions().isEmpty()) {

            return """
                    <tr>
                        <td colspan="6"
                            class="empty">
                            Step 실행 정보가 없습니다.
                        </td>
                    </tr>
                    """;
        }

        StringBuilder html =
                new StringBuilder();

        report.stepExecutions()
                .forEach(step -> {

                    boolean success =
                            "COMPLETED".equals(
                                    step.status()
                            );

                    html.append("""
                            <tr>

                                <td>%s</td>

                                <td>
                                    <span class="badge %s">
                                        %s
                                    </span>
                                </td>

                                <td>%s</td>

                                <td>%,d</td>

                                <td>%,d</td>

                                <td>%,d</td>

                            </tr>
                            """.formatted(

                            escapeHtml(
                                    step.stepName()
                            ),

                            success
                                    ? "badge-completed"
                                    : "badge-failed",

                            escapeHtml(
                                    step.status()
                            ),

                            formatDuration(
                                    step.durationMillis()
                            ),

                            step.readCount(),

                            step.writeCount(),

                            step.filterCount()
                    ));
                });

        return html.toString();
    }

    /**
     * 실제 이상 상세 내역
     */
    private String buildAnomalyDetails(
            ConsistencyReport report
    ) {

        if (report.anomalyDetails().isEmpty()) {

            return """
                    <div class="empty">
                        발견된 이상 내역이 없습니다.
                    </div>
                    """;
        }

        StringBuilder html =
                new StringBuilder();

        html.append("""
                <table>

                    <thead>
                        <tr>
                            <th>검증 항목</th>
                            <th>Reference ID</th>
                            <th>상세 내용</th>
                            <th>발견 시간</th>
                        </tr>
                    </thead>

                    <tbody>
                """);

        report.anomalyDetails()
                .forEach(detail -> {

                    html.append("""
                            <tr>

                                <td>
                                    %s
                                </td>

                                <td>
                                    %s
                                </td>

                                <td class="detail">
                                    %s
                                </td>

                                <td>
                                    %s
                                </td>

                            </tr>
                            """.formatted(

                            escapeHtml(
                                    detail.checkType()
                                            .label()
                            ),

                            escapeHtml(
                                    detail.referenceId()
                            ),

                            escapeHtml(
                                    detail.detail()
                            ),

                            formatDateTime(
                                    detail.detectedAt()
                            )
                    ));
                });

        html.append("""
                    </tbody>
                </table>
                """);

        return html.toString();
    }

    private String buildStatusBadge(
            String status
    ) {

        boolean success =
                "COMPLETED".equals(status);

        return """
                <span class="badge %s">
                    %s
                </span>
                """.formatted(
                success
                        ? "badge-completed"
                        : "badge-failed",
                escapeHtml(status)
        );
    }

    private String formatDateTime(
            java.time.LocalDateTime dateTime
    ) {

        if (dateTime == null) {
            return "-";
        }

        return dateTime.format(
                DATE_TIME_FORMATTER
        );
    }

    private String formatDuration(
            long millis
    ) {

        if (millis <= 0) {
            return "0초";
        }

        long totalSeconds =
                millis / 1000;

        long hours =
                totalSeconds / 3600;

        long minutes =
                (totalSeconds % 3600) / 60;

        long seconds =
                totalSeconds % 60;

        if (hours > 0) {

            return String.format(
                    "%d시간 %d분 %d초",
                    hours,
                    minutes,
                    seconds
            );
        }

        if (minutes > 0) {

            return String.format(
                    "%d분 %d초",
                    minutes,
                    seconds
            );
        }

        return String.format(
                "%d초",
                seconds
        );
    }

    /**
     * HTML Injection 방지
     */
    private String escapeHtml(
            String value
    ) {

        if (value == null) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}