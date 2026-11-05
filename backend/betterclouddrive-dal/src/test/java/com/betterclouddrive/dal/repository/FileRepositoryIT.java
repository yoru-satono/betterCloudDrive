package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.FavoriteEntity;
import com.betterclouddrive.dal.entity.FileTagEntity;
import com.betterclouddrive.dal.entity.TagEntity;
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
    @jakarta.annotation.Resource
    private FavoriteRepository favoriteRepository;
    @jakarta.annotation.Resource
    private FileTagRepository fileTagRepository;
    @jakarta.annotation.Resource
    private TagRepository tagRepository;

    @Test
    void instantCandidatesMatchCaseInsensitiveDigestAndExactSize() {
        var matching = file(1L, null, null, "matching", false);
        matching.setMd5Hash("A".repeat(32));
        matching.setFileSize(5L);
        repository.save(matching);
        var wrongSize = file(2L, null, null, "wrong-size", false);
        wrongSize.setMd5Hash("a".repeat(32));
        wrongSize.setFileSize(6L);
        repository.save(wrongSize);
        var deleted = file(3L, null, null, "deleted", true);
        deleted.setMd5Hash("a".repeat(32));
        deleted.setFileSize(5L);
        repository.save(deleted);
        assertThat(repository.findByMd5HashIgnoreCaseAndFileSizeAndIsDeletedFalseOrderByIdDesc("a".repeat(32), 5L))
                .extracting(FileEntity::getFileName).containsExactly("matching");
    }

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

    @Test
    void teamOrganizationQueriesAreScopedAndStableAndSupportAtomicReplacementDelete() {
        FileEntity first = repository.save(file(1L, 7L, null, "first.txt", false));
        FileEntity second = repository.save(file(1L, 7L, null, "second.txt", false));
        FileEntity foreign = repository.save(file(1L, 8L, null, "foreign.txt", false));
        LocalDateTime sameTime = LocalDateTime.of(2026, 9, 8, 0, 0);
        favoriteRepository.save(FavoriteEntity.builder().userId(1L).fileId(first.getId()).createdAt(sameTime).build());
        favoriteRepository.save(FavoriteEntity.builder().userId(1L).fileId(second.getId()).createdAt(sameTime).build());
        favoriteRepository.save(FavoriteEntity.builder().userId(1L).fileId(foreign.getId()).createdAt(sameTime).build());

        assertThat(favoriteRepository.findTeamFavoriteFiles(1L, 7L))
                .extracting(FileEntity::getId).containsExactly(second.getId(), first.getId());

        TagEntity beta = tagRepository.save(TagEntity.builder()
                .userId(1L).teamId(7L).tagName("beta").color("#00d4aa").createdAt(sameTime).build());
        TagEntity alpha = tagRepository.save(TagEntity.builder()
                .userId(1L).teamId(7L).tagName("Alpha").color("#60a5fa").createdAt(sameTime).build());
        TagEntity otherTeam = tagRepository.save(TagEntity.builder()
                .userId(1L).teamId(8L).tagName("foreign").color("#a78bfa").createdAt(sameTime).build());
        fileTagRepository.save(FileTagEntity.builder()
                .userId(1L).teamId(7L).fileId(first.getId()).tagId(beta.getId()).createdAt(sameTime).build());
        fileTagRepository.save(FileTagEntity.builder()
                .userId(1L).teamId(7L).fileId(first.getId()).tagId(alpha.getId()).createdAt(sameTime).build());
        fileTagRepository.save(FileTagEntity.builder()
                .userId(1L).teamId(7L).fileId(second.getId()).tagId(beta.getId()).createdAt(sameTime).build());
        fileTagRepository.save(FileTagEntity.builder()
                .userId(1L).teamId(8L).fileId(foreign.getId()).tagId(otherTeam.getId()).createdAt(sameTime).build());

        assertThat(fileTagRepository.findTeamTagsForFile(7L, first.getId()))
                .extracting(TagEntity::getTagName).containsExactly("Alpha", "beta");
        assertThat(fileTagRepository.findTeamTaggedFiles(7L, beta.getId()))
                .extracting(FileEntity::getId).containsExactly(second.getId(), first.getId());

        fileTagRepository.deleteByTeamIdAndFileId(7L, first.getId());
        fileTagRepository.flush();
        assertThat(fileTagRepository.findTeamTagsForFile(7L, first.getId())).isEmpty();
        assertThat(fileTagRepository.findTeamTagsForFile(8L, foreign.getId()))
                .extracting(TagEntity::getId).containsExactly(otherTeam.getId());
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
