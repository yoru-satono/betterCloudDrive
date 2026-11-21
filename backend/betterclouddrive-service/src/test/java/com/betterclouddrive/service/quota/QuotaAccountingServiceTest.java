package com.betterclouddrive.service.quota;

import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.common.exception.QuotaUnavailableException;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository.Balance;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QuotaAccountingServiceTest {
    private final QuotaAccountingRepository repository = mock(QuotaAccountingRepository.class);
    private final QuotaAccountingService service = new QuotaAccountingService(repository);

    @BeforeEach void ready() { when(repository.ready()).thenReturn(true); }

    @ParameterizedTest @EnumSource(Scope.class)
    void exactQuotaBoundaryIsAllowedButOverflowDoesNotPersist(Scope scope) {
        when(repository.find(scope, 7, true)).thenReturn(Optional.of(new Balance(80, 100, 2)));
        service.change(scope, 7, 20);
        verify(repository).setUsed(scope, 7, 100);
        assertThatThrownBy(() -> service.change(scope, 7, 21)).isInstanceOfSatisfying(
                BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(419001));
        verify(repository, times(1)).setUsed(any(), anyLong(), anyLong());
    }

    @Test void cutoverGatePrecedesMutationAndLock() {
        when(repository.ready()).thenReturn(false);
        assertThatThrownBy(() -> service.change(Scope.PERSONAL, 1, 4)).isInstanceOf(QuotaUnavailableException.class);
        verify(repository, never()).find(any(), anyLong(), anyBoolean());
        verify(repository, never()).setUsed(any(), anyLong(), anyLong());
    }

    @Test void releaseMustNotHideNegativeOrOverflowedAccounting() {
        when(repository.find(Scope.PERSONAL, 1, true)).thenReturn(Optional.of(new Balance(4, 100, 0)));
        assertThatThrownBy(() -> service.change(Scope.PERSONAL, 1, -5)).isInstanceOf(BusinessException.class);
        when(repository.find(Scope.PERSONAL, 1, true)).thenReturn(Optional.of(new Balance(Long.MAX_VALUE, Long.MAX_VALUE, 0)));
        assertThatThrownBy(() -> service.change(Scope.PERSONAL, 1, 1)).isInstanceOf(BusinessException.class);
        verify(repository, never()).setUsed(any(), anyLong(), anyLong());
    }

    @Test void negativeHistoricalBalanceRequiresInspectionRepair() {
        when(repository.find(Scope.TEAM, 3, true)).thenReturn(Optional.of(new Balance(-10, 100, 0)));
        assertThatThrownBy(() -> service.change(Scope.TEAM, 3, 20)).isInstanceOf(BusinessException.class);
        verify(repository, never()).setUsed(any(), anyLong(), anyLong());
    }
}
