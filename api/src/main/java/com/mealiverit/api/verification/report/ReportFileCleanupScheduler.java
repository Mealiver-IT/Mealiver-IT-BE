package com.mealiverit.api.verification.report;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

@Slf4j
@Component
public class ReportFileCleanupScheduler {

    private final Path reportDirectory;
    private final int retentionDays;

    public ReportFileCleanupScheduler(
            @Value("${verification.report.directory:./reports}")
            String reportDirectory,
            @Value("${verification.report.retention-days:30}")
            int retentionDays
    ) {

        this.reportDirectory =
                Path.of(reportDirectory);

        this.retentionDays =
                retentionDays;
    }

    @Scheduled(cron = "0 30 4 * * *")
    public void cleanup() {

        if (!Files.exists(reportDirectory)) {
            return;
        }

        Instant cutoff =
                Instant.now()
                        .minus(
                                retentionDays,
                                ChronoUnit.DAYS
                        );

        try (Stream<Path> files =
                     Files.list(reportDirectory)) {

            files.filter(Files::isRegularFile)
                    .filter(path ->
                            path.getFileName()
                                    .toString()
                                    .endsWith(".html"))
                    .forEach(path -> {

                        try {

                            Instant modifiedAt =
                                    Files.getLastModifiedTime(
                                            path
                                    ).toInstant();

                            if (modifiedAt.isBefore(cutoff)) {

                                Files.delete(path);

                                log.info(
                                        "[ReportCleanup] " +
                                        "오래된 리포트 삭제: {}",
                                        path
                                );
                            }

                        } catch (IOException e) {

                            log.warn(
                                    "[ReportCleanup] " +
                                    "파일 삭제 실패: {}",
                                    path,
                                    e
                            );
                        }
                    });

        } catch (IOException e) {

            log.error(
                    "[ReportCleanup] " +
                    "리포트 디렉터리 조회 실패",
                    e
            );
        }
    }
}