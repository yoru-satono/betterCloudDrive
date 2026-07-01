package com.betterclouddrive.scheduler.task;

import com.betterclouddrive.dal.entity.TeamEntity;
import com.betterclouddrive.dal.repository.TeamRepository;
import com.betterclouddrive.service.TeamPurgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TeamCleanupTask {

    private static final int BATCH_SIZE = 100;

    private final TeamRepository teamRepository;
    private final TeamPurgeService teamPurgeService;

    @Value("${drive.team.cleanup-retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "${drive.team.cleanup-cron:0 30 3 * * ?}")
    public void cleanupExpiredDeletedTeams() {
        log.info("Starting deleted team cleanup...");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime legacyDeletedCutoff = now.minusDays(retentionDays);
        List<TeamEntity> teams = teamRepository.findExpiredDeletedTeams(now, legacyDeletedCutoff, PageRequest.of(0, BATCH_SIZE));
        int cleaned = 0;

        while (!teams.isEmpty()) {
            int cleanedInBatch = 0;
            for (TeamEntity team : teams) {
                try {
                    teamPurgeService.purgeTeam(team.getId());
                    cleaned++;
                    cleanedInBatch++;
                } catch (Exception e) {
                    log.error("Failed to cleanup deleted team: {}", team.getId(), e);
                }
            }
            log.info("Deleted team cleanup progress: {} teams cleaned", cleaned);
            if (cleanedInBatch == 0) {
                log.warn("Deleted team cleanup stopped because no team in the current batch could be cleaned");
                break;
            }
            teams = teamRepository.findExpiredDeletedTeams(now, legacyDeletedCutoff, PageRequest.of(0, BATCH_SIZE));
        }

        log.info("Deleted team cleanup completed. Total cleaned: {}", cleaned);
    }
}
