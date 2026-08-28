package com.betterclouddrive.web.dto.response;

import com.betterclouddrive.dal.entity.FileEntity;
import com.betterclouddrive.dal.entity.ShareLinkEntity;
import com.betterclouddrive.dal.repository.FileRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShareLinkResponseMapperTest {

    @Test
    void mapBuildsCanonicalUrlAndEpochMetadata() {
        FileRepository files = mock(FileRepository.class);
        FileEntity file = FileEntity.builder().id(7L).fileName("report.docx").fileType("file").fileSize(42L).build();
        when(files.findById(7L)).thenReturn(Optional.of(file));
        LocalDateTime expiry = LocalDateTime.of(2026, 9, 1, 12, 30);
        ShareLinkEntity share = share(1L, 7L, "abc12345");
        share.setExpireAt(expiry);

        ShareLinkResponse response = new ShareLinkResponseMapper(files, " https://drive.example.com/// ").map(share);

        assertThat(response.getShareUrl()).isEqualTo("https://drive.example.com/s/abc12345");
        assertThat(response.getExpireAtEpochMs()).isEqualTo(expiry.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        assertThat(response.getFileName()).isEqualTo("report.docx");
        assertThat(response.getFileType()).isEqualTo("file");
        assertThat(response.getFileSize()).isEqualTo(42L);
    }

    @Test
    void mapAllLoadsFileMetadataInOneBatchAndAllowsMissingFiles() {
        FileRepository files = mock(FileRepository.class);
        FileEntity file = FileEntity.builder().id(7L).fileName("present.txt").fileType("file").fileSize(3L).build();
        FileEntity deleted = FileEntity.builder().id(9L).fileName("deleted.txt").fileType("file").fileSize(4L).isDeleted(true).build();
        when(files.findAllById(List.of(7L, 8L, 9L))).thenReturn(List.of(file, deleted));
        ShareLinkResponseMapper mapper = new ShareLinkResponseMapper(files, "http://localhost:3000/");

        List<ShareLinkResponse> responses = mapper.mapAll(List.of(
                share(1L, 7L, "present1"), share(2L, 8L, "missing1"), share(3L, 9L, "deleted1")));

        assertThat(responses).extracting(ShareLinkResponse::getFileName).containsExactly("present.txt", null, null);
        verify(files).findAllById(List.of(7L, 8L, 9L));
    }

    private ShareLinkEntity share(Long id, Long fileId, String code) {
        return ShareLinkEntity.builder().id(id).userId(1L).fileId(fileId).shareCode(code)
                .isCanceled(false).visitCount(0).downloadCount(0).build();
    }
}
