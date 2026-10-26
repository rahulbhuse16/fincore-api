package com.fincore.platform.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/** Builds a bounded Pageable. Sort fields are whitelisted so clients cannot sort by arbitrary columns. */
public final class Pagination {
    public static final int MAX_PAGE_SIZE = 100;

    private Pagination() {}

    /** @param sort "field,asc" or "field,desc" (direction optional, default desc) */
    public static Pageable of(int page, int size, String sort, Set<String> allowedFields, String defaultField) {
        if (page < 0) {
            throw ApiException.badRequest("invalid-page", "page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("invalid-page-size", "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String field = defaultField;
        Sort.Direction direction = Sort.Direction.DESC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            field = parts[0].trim();
            if (parts.length > 1) {
                direction = "asc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.ASC
                        : "desc".equalsIgnoreCase(parts[1].trim()) ? Sort.Direction.DESC : null;
                if (direction == null) {
                    throw ApiException.badRequest("invalid-sort", "sort direction must be asc or desc");
                }
            }
        }
        if (!allowedFields.contains(field)) {
            throw ApiException.badRequest("invalid-sort", "sortable fields: " + allowedFields);
        }
        // id as a tiebreaker keeps page boundaries stable
        return PageRequest.of(page, size, Sort.by(direction, field).and(Sort.by(direction, "id")));
    }
}
