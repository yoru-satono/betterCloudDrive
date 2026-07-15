package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.FileEntity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@ContextConfiguration(classes = FileRepositoryIT.TestApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class FileRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("bcd_repository_test")
            .withUsername("bcd_test")
            .withPassword("bcd_test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @jakarta.annotation.Resource
    private FileRepository repository;

    @Test
    void personalQueriesIsolateOwnersTeamsAndSoftDeletedFiles() {
        repository.save(file(1L, null, null, "visible.txt", false));
        repository.save(file(2L, null, null, "other-user.txt", false));
        repository.save(file(1L, 8L, null, "team.txt", false));
        repository.save(file(1L, null, null, "deleted.txt", true));

        var files = repository.findByUserIdAndParentIdIsNullAndIsDeletedFalseOrderByFileTypeAscFileNameAsc(1L);

        assertThat(files).extracting(FileEntity::getFileName).containsExactly("visible.txt");
        assertThat(repository.findByUserIdAndIsDeletedTrue(1L))
                .extracting(FileEntity::getFileName).containsExactly("deleted.txt");
    }

    @Test
    void rootNameAndExpiredDeletionQueriesRespectTheirBoundaries() {
        repository.save(file(1L, null, null, "Report.TXT", false));
        repository.save(file(1L, null, 99L, "Report.TXT", false));
        repository.save(file(1L, null, null, "old.txt", true, LocalDateTime.now().minusDays(31)));
        repository.save(file(1L, null, null, "recent.txt", true, LocalDateTime.now()));

        assertThat(repository.findRootByName(1L, "Report.TXT")).isPresent();
        assertThat(repository.findByUserIdAndIsDeletedFalseAndFileNameContainingIgnoreCase(1L, "report"))
                .hasSize(2);
        assertThat(repository.findExpiredDeletedFiles(LocalDateTime.now().minusDays(30),
                org.springframework.data.domain.PageRequest.of(0, 10)))
                .extracting(FileEntity::getFileName).containsExactly("old.txt");
    }

    private static FileEntity file(Long userId, Long teamId, Long parentId, String name, boolean deleted) {
        return file(userId, teamId, parentId, name, deleted, deleted ? LocalDateTime.now().minusDays(1) : null);
    }

    private static FileEntity file(Long userId, Long teamId, Long parentId, String name,
                                   boolean deleted, LocalDateTime deletedAt) {
        return FileEntity.builder().userId(userId).teamId(teamId).parentId(parentId).fileName(name)
                .fileType("file").fileSize(1L).storagePath("test/" + userId + "/" + name)
                .isDeleted(deleted).deletedAt(deletedAt).build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.betterclouddrive.dal.entity")
    @EnableJpaRepositories("com.betterclouddrive.dal.repository")
    static class TestApplication {}
}
