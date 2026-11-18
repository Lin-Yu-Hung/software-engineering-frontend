package com.booking.court;

import com.booking.common.BusinessException;
import com.booking.booking.InventoryRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AdminCourtService {
    private final CourtRepository courts;
    private final Clock clock;
    private final InventoryRepository inventory;

    public AdminCourtService(CourtRepository courts, Clock clock, InventoryRepository inventory) {
        this.courts = courts;
        this.clock = clock;
        this.inventory = inventory;
    }

    public List<AdminCourtResponse> list() {
        return courts.findAllByOrderByDisplayOrderAscNameAsc().stream().map(AdminCourtResponse::from).toList();
    }

    public AdminCourtResponse get(UUID id) {
        return AdminCourtResponse.from(courts.findById(id).orElseThrow(CourtNotFoundException::new));
    }

    @Transactional
    public AdminCourtResponse create(CreateCourtRequest request) {
        requireEditableStatus(request.status());
        Court court = courts.saveAndFlush(new Court(request.name().strip(), request.type().strip(),
                request.status(), request.displayOrder(), now()));
        return AdminCourtResponse.from(court);
    }

    @Transactional
    public AdminCourtResponse update(UUID id, UpdateCourtRequest request) {
        inventory.lock();
        if (request.name() == null && request.type() == null && request.status() == null
                && request.displayOrder() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_UPDATE", "請提供至少一個修改欄位");
        }
        requireEditableStatus(request.status());
        List<Court> locked = courts.lockAll();
        Court court = editable(locked, id, request.expectedVersion());
        protectLastActive(locked, court, request.status());
        if (request.status() != null && request.status() != CourtStatus.ACTIVE) inventory.assertCourtFree(id,clock.instant());
        court.update(request.name(), request.type(), request.status(), request.displayOrder(), now());
        courts.flush();
        return AdminCourtResponse.from(court);
    }

    @Transactional
    public void retire(UUID id, long expectedVersion) {
        inventory.lock();
        List<Court> locked = courts.lockAll();
        Court court = editable(locked, id, expectedVersion);
        protectLastActive(locked, court, CourtStatus.RETIRED);
        inventory.assertCourtFree(id,clock.instant());
        court.update(null, null, CourtStatus.RETIRED, null, now());
        courts.flush();
    }

    private Court editable(List<Court> locked, UUID id, long expectedVersion) {
        Court court = locked.stream().filter(candidate -> candidate.getId().equals(id)).findFirst()
                .orElseThrow(CourtNotFoundException::new);
        if (court.getVersion() != expectedVersion) {
            throw new BusinessException(HttpStatus.CONFLICT, "STALE_VERSION", "資料已被修改，請重新讀取再操作");
        }
        if (court.getStatus() == CourtStatus.RETIRED) {
            throw new BusinessException(HttpStatus.CONFLICT, "COURT_RETIRED", "退役場地不可再修改");
        }
        return court;
    }

    private void protectLastActive(List<Court> locked, Court court, CourtStatus next) {
        if (court.getStatus() == CourtStatus.ACTIVE && next != null && next != CourtStatus.ACTIVE
                && locked.stream().filter(candidate -> candidate.getStatus() == CourtStatus.ACTIVE).count() <= 1) {
            throw new BusinessException(HttpStatus.CONFLICT, "LAST_ACTIVE_COURT", "至少需要保留一個啟用場地");
        }
    }

    private void requireEditableStatus(CourtStatus status) {
        if (status == CourtStatus.RETIRED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "退役場地請使用 DELETE API");
        }
    }

    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
