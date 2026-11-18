package com.booking.court;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface CourtRepository extends JpaRepository<Court, UUID> {
    List<Court> findByStatusOrderByDisplayOrderAscNameAsc(CourtStatus status);
    Optional<Court> findByIdAndStatus(UUID id, CourtStatus status);
    List<Court> findAllByOrderByDisplayOrderAscNameAsc();

    // Lock in a consistent order so two admins cannot disable the last two courts concurrently.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Court c order by c.id")
    List<Court> lockAll();
}
