package com.betterclouddrive.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.betterclouddrive.common.dto.PageResult;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.ShareLinkEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import com.betterclouddrive.dal.repository.ShareLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class ShareServiceImplTest {

    @Mock private ShareLinkRepository shareLinkRepository;
    @Mock private FileRepository fileRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ZSetOperations<String, String> zSetOps;
    @InjectMocks private ShareServiceImpl shareService;

    private FileEntity ownedFile(Long id, Long userId) {
        return FileEntity.builder().id(id).userId(userId).fileType("file").isDeleted(false).build();
    }

    private ShareLinkEntity activeShare(String code, Long userId, Long fileId) {
        return ShareLinkEntity.builder()
                .id(1L).userId(userId).fileId(fileId).shareCode(code)
                .isCanceled(false).downloadCount(0).visitCount(0).build();
    }

    @Test
    void createShare_shouldGenerateShareCode() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 1L)));
        when(shareLinkRepository.save(any(ShareLinkEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ShareLinkEntity share = shareService.createShare(1L, 1L, null, null, null);

        assertThat(share.getShareCode()).isNotBlank().hasSize(8);
        assertThat(share.getPasswordHash()).isNull();
    }

    @Test
    void createShare_shouldEncodePasswordWhenProvided() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 1L)));
        when(passwordEncoder.encode("secret")).thenReturn("encoded-secret");
        when(shareLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ShareLinkEntity share = shareService.createShare(1L, 1L, "secret", null, null);

        assertThat(share.getPasswordHash()).isEqualTo("encoded-secret");
    }

    @Test
    void createShare_shouldThrowWhenFileNotFound() {
        when(fileRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> shareService.createShare(1L, 99L, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void createShare_shouldThrowWhenFileNotOwned() {
        when(fileRepository.findById(1L)).thenReturn(Optional.of(ownedFile(1L, 2L)));

        assertThatThrownBy(() -> shareService.createShare(1L, 1L, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listShares_shouldReturnActiveSharingLinks() {
        ShareLinkEntity share = activeShare("abcd1234", 1L, 1L);
        Page<ShareLinkEntity> page = new PageImpl<>(List.of(share), PageRequest.of(0, 20), 1);
        when(shareLinkRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        PageResult<ShareLinkEntity> result = shareService.listShares(1L, 1, 20);

        assertThat(result.getRecords()).hasSize(1);
    }

    @Test
    void cancelShare_shouldMarkAsCanceled() {
        ShareLinkEntity share = activeShare("abcd1234", 1L, 1L);
        when(shareLinkRepository.findById(1L)).thenReturn(Optional.of(share));
        when(shareLinkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        shareService.cancelShare(1L, 1L);

        assertThat(share.getIsCanceled()).isTrue();
    }

    @Test
    void accessShare_shouldReturnFileWhenValid() {
        ShareLinkEntity share = activeShare("code1234", 1L, 10L);
        FileEntity file = ownedFile(10L, 1L);
        when(shareLinkRepository.findByShareCode("code1234")).thenReturn(Optional.of(share));
        when(fileRepository.findById(10L)).thenReturn(Optional.of(file));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        FileEntity result = shareService.accessShare("code1234", null);

        assertThat(result).isSameAs(file);
        verify(zSetOps).incrementScore("share:visits", "code1234", 1);
    }

    @Test
    void accessShare_shouldThrowWhenShareCanceled() {
        ShareLinkEntity share = activeShare("code1234", 1L, 10L);
        share.setIsCanceled(true);
        when(shareLinkRepository.findByShareCode("code1234")).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> shareService.accessShare("code1234", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void accessShare_shouldThrowWhenShareExpired() {
        ShareLinkEntity share = activeShare("code5678", 1L, 10L);
        share.setExpireAt(LocalDateTime.now().minusHours(1));
        when(shareLinkRepository.findByShareCode("code5678")).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> shareService.accessShare("code5678", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void accessShare_shouldThrowWhenPasswordWrong() {
        ShareLinkEntity share = activeShare("code9999", 1L, 10L);
        share.setPasswordHash("encoded-pw");
        when(shareLinkRepository.findByShareCode("code9999")).thenReturn(Optional.of(share));
        when(passwordEncoder.matches("wrong", "encoded-pw")).thenReturn(false);

        assertThatThrownBy(() -> shareService.accessShare("code9999", "wrong"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void accessShare_shouldThrowWhenDownloadLimitExceeded() {
        ShareLinkEntity share = activeShare("codeABCD", 1L, 10L);
        share.setMaxDownloads(3);
        share.setDownloadCount(3);
        when(shareLinkRepository.findByShareCode("codeABCD")).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> shareService.accessShare("codeABCD", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listSharedFiles_shouldReturnSingleFileForFileType() {
        ShareLinkEntity share = activeShare("codeXYZ", 1L, 5L);
        FileEntity sharedFile = ownedFile(5L, 1L);
        when(shareLinkRepository.findByShareCode("codeXYZ")).thenReturn(Optional.of(share));
        when(fileRepository.findById(5L)).thenReturn(Optional.of(sharedFile));

        PageResult<FileEntity> result = shareService.listSharedFiles("codeXYZ", null, 1, 20);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getId()).isEqualTo(5L);
    }
}
