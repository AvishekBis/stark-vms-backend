package com.starkgroup.vms.common.util;

import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.starkgroup.vms.common.exception.BadRequestException;

/**
 * Builds safe {@link PageRequest}s for list endpoints: page size capped (TC-11) and sort
 * fields restricted to a per-feature whitelist, so clients can never sort on arbitrary columns.
 */
public final class PageRequests {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private PageRequests() {
    }

    /**
     * @param sortParam     "field,direction" as sent by the client, e.g. "createdAt,desc"; blank means default
     * @param allowedFields API field name mapped to entity property name
     * @param defaultSort   used when sortParam is blank
     */
    public static PageRequest of(int page, int size, String sortParam, Map<String, String> allowedFields,
                                 Sort defaultSort) {
        int cappedSize = Math.min(size, MAX_PAGE_SIZE);
        if ((long) page * cappedSize > Integer.MAX_VALUE) {
            throw new BadRequestException("Page " + page + " is out of range.");
        }
        return PageRequest.of(page, cappedSize, parseSort(sortParam, allowedFields, defaultSort));
    }

    static Sort parseSort(String sortParam, Map<String, String> allowedFields, Sort defaultSort) {
        if (sortParam == null || sortParam.isBlank()) {
            return defaultSort;
        }
        String[] parts = sortParam.split(",");
        if (parts.length > 2) {
            throw new BadRequestException("Sort must have the form 'field,direction'.");
        }
        String field = parts[0].trim();
        String property = allowedFields.get(field);
        if (property == null) {
            throw new BadRequestException("Unknown sort field '" + field + "'. Allowed fields: "
                    + String.join(", ", allowedFields.keySet().stream().sorted().toList()) + ".");
        }
        Sort.Direction direction = parts.length == 2 ? parseDirection(parts[1]) : Sort.Direction.ASC;
        return Sort.by(direction, property);
    }

    private static Sort.Direction parseDirection(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new BadRequestException("Sort direction must be 'asc' or 'desc'.");
        };
    }
}
