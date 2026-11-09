package com.starkgroup.vms.common.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Stable page wrapper for every list endpoint (guideline 11.2). Spring's own Page JSON is
 * not a stable contract, so it is never returned directly.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
