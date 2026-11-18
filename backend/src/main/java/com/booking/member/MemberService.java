package com.booking.member;

import com.booking.common.BusinessException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {
    private final UserAccountRepository users;
    private final Clock clock;

    public MemberService(UserAccountRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MeResponse get(UUID id) { return MeResponse.from(find(id)); }

    @Transactional
    public MeResponse update(UUID id, ProfileRequest request) {
        if (request.name() == null && request.phone() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_UPDATE", "至少提供姓名或電話");
        }
        UserAccount user = find(id);
        user.updateProfile(request.name() == null ? null : request.name().strip(),
                request.phone() == null ? null : request.phone().strip(), clock.instant());
        return MeResponse.from(user);
    }

    private UserAccount find(UUID id) {
        return users.findById(id).filter(UserAccount::isEnabled)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED,
                        "UNAUTHENTICATED", "請重新登入"));
    }
}
