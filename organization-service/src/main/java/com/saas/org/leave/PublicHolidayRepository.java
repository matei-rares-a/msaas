package com.saas.org.leave;

import com.saas.shared.tenancy.TenantScopedRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PublicHolidayRepository extends TenantScopedRepository<PublicHoliday, UUID> {

    List<PublicHoliday> findAllByHolidayDateBetweenOrderByHolidayDate(LocalDate from, LocalDate to);

    Optional<PublicHoliday> findByHolidayDate(LocalDate date);
}
