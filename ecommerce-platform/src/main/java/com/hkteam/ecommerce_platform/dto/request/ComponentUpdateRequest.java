package com.hkteam.ecommerce_platform.dto.request;

import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class ComponentUpdateRequest {
    @Size(min = 2, max = 50, message = "MIN_MAX_INVALID")
    String name;

    boolean required;
}
