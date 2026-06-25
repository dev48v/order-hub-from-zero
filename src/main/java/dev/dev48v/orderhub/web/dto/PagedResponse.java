package dev.dev48v.orderhub.web.dto;

import org.springframework.data.domain.Page;

import java.util.List;

// Day 6 — a small, stable envelope for a page of results.
// WHY: we deliberately do NOT serialize Spring Data's Page<T> straight to JSON — its shape is
// an implementation detail that has shifted between versions and leaks internals. This record
// is our own API contract: the mapped content plus exactly the metadata a client needs to
// paginate (which page, how big, totals). The static factory keeps the Page -> DTO mapping
// in one place, mirroring OrderResponse.from(...).
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
