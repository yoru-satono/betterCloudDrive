package com.betterclouddrive.service.inspection;

import com.betterclouddrive.storage.StorageInventoryException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ActiveObjectPrefixServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ActiveObjectPrefixService service = new ActiveObjectPrefixService(redis);

    @AfterEach void close() {
        service.close();
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
    }

    @Test void protectsPartsUntilTheBusinessTransactionCompletesWithoutStoringRawPrefixes() {
        String prefix = "uploads/1/fixture/chunks";
        String key = "storage:active-prefix:" + InspectionObservationService.fingerprint(prefix);
        when(redis.opsForValue()).thenReturn(values);
        TransactionSynchronizationManager.initSynchronization();
        var registration = service.register(prefix);
        service.renew();
        verify(values).set(key, "1", Duration.ofSeconds(120));
        assertThat(service.protects(prefix + ".part.1")).isTrue();
        registration.close();
        verify(redis, never()).delete(anyString());
        clearInvocations(values);
        service.renew();
        verify(values).set(key, "1", Duration.ofSeconds(120));
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        clearInvocations(values);
        service.renew();
        verifyNoInteractions(values);
    }

    @Test void leaseWriteFailureDoesNotFailTheCoreUpload() {
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new RedisConnectionFailureException("fixture unavailable")).when(values).set(anyString(), eq("1"), any(Duration.class));
        var registration = service.register("uploads/1/fixture/chunks");
        verifyNoInteractions(redis);
        assertThatCode(service::renew).doesNotThrowAnyException();
        registration.close();
    }

    @Test void unavailableLeaseReadsAreNotInterpretedAsUnownedObjects() {
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("fixture unavailable"));
        assertThatThrownBy(() -> service.protects("uploads/1/fixture/chunks.part.1"))
                .isInstanceOf(StorageInventoryException.class);
        assertThat(service.protects("users/1/file.bin")).isFalse();
    }
}
