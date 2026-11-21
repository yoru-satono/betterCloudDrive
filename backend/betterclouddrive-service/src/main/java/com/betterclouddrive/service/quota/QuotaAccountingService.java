package com.betterclouddrive.service.quota;

import com.betterclouddrive.common.constant.ApiCode;
import com.betterclouddrive.common.exception.BusinessException;
import com.betterclouddrive.common.exception.QuotaUnavailableException;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository.Balance;
import com.betterclouddrive.dal.repository.QuotaAccountingRepository.Scope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** All callers hold the owner lock before locking individual file/version rows. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class QuotaAccountingService {
    private final QuotaAccountingRepository repository;

    public Balance lock(Scope scope, long owner) {
        if (!repository.ready()) throw new QuotaUnavailableException();
        return repository.find(scope, owner, true).orElseThrow(() -> new BusinessException(ApiCode.NOT_FOUND));
    }

    public void lockForFile(long fileId) {
        repository.fileOwner(fileId).ifPresent(owner -> lock(owner.scope(), owner.id()));
    }

    public void check(Scope scope, long owner, long additional) {
        Balance balance = lock(scope, owner);
        if (additional < 0) throw new BusinessException(ApiCode.BAD_REQUEST);
        long updated = sum(balance.used(), additional);
        if (updated > balance.quota()) throw new BusinessException(ApiCode.STORAGE_QUOTA_EXCEEDED);
    }

    public void change(Scope scope, long owner, long delta) {
        Balance balance = lock(scope, owner);
        long updated = sum(balance.used(), delta);
        if (delta > 0 && updated > balance.quota()) throw new BusinessException(ApiCode.STORAGE_QUOTA_EXCEEDED);
        repository.setUsed(scope, owner, updated);
    }

    private long sum(long used, long delta) {
        try {
            long result = Math.addExact(used, delta);
            if (used < 0 || result < 0) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException error) {
            throw new BusinessException(409, "error.quotaMetadataInvalid");
        }
    }
}
