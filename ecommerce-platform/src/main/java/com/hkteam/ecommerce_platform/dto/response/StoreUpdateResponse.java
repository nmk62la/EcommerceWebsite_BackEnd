package com.hkteam.ecommerce_platform.dto.response;

import java.time.Instant;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class StoreUpdateResponse {
    String id;
    String name;
    String bio;
    Float rating;
    String username;
    String defaultAddress;
    Integer totalProduct;
    Instant createdAt;
    Long defaultAddressId;
}
