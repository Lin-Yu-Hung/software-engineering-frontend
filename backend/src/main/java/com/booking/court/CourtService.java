package com.booking.court;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CourtService {
    private final CourtRepository repository;

    public CourtService(CourtRepository repository) {
        this.repository = repository;
    }

    public List<CourtResponse> listActiveCourts() {
        return repository.findByStatusOrderByDisplayOrderAscNameAsc(CourtStatus.ACTIVE)
                .stream().map(CourtResponse::from).toList();
    }

    public CourtResponse getActiveCourt(UUID id) {
        return repository.findByIdAndStatus(id, CourtStatus.ACTIVE)
                .map(CourtResponse::from).orElseThrow(CourtNotFoundException::new);
    }
}
