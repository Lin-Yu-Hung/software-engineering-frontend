package com.booking.member;

import java.util.List;
import java.util.UUID;

public record MeResponse(UUID id, String email, String name, String phone, List<Role> roles) {
    public static MeResponse from(UserAccount user) {
        return new MeResponse(user.getId(), user.getEmail(), user.getName(), user.getPhone(),
                user.getRoles().stream().sorted().toList());
    }
}
