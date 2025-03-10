package com.hkteam.ecommerce_platform.dto.response;

import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class StoreOfProductResponse {
    String id;
    String name;
    String slug;
    String imageUrl;
    Float rating;
}
