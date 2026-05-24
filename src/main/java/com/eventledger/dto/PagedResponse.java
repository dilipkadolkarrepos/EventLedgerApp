package com.eventledger.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable, schema-controlled wrapper for paginated API responses.
 * <p>
 * Exposing Spring's own {@link Page} directly would couple the JSON contract to
 * Spring Data internals and serialise ~15 extra fields the client does not need.
 * This DTO exposes exactly the six fields a consumer requires to drive a paginator.
 */
public class PagedResponse<T> {

    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final boolean last;

    private PagedResponse(List<T> content, int page, int size,
                           long totalElements, int totalPages, boolean last) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.last = last;
    }

    /** Converts a Spring Data {@link Page} into this DTO in one call. */
    public static <T> PagedResponse<T> from(Page<T> springPage) {
        return new PagedResponse<>(
                springPage.getContent(),
                springPage.getNumber(),
                springPage.getSize(),
                springPage.getTotalElements(),
                springPage.getTotalPages(),
                springPage.isLast()
        );
    }

    public List<T> getContent()       { return content; }
    public int getPage()              { return page; }
    public int getSize()              { return size; }
    public long getTotalElements()    { return totalElements; }
    public int getTotalPages()        { return totalPages; }
    public boolean isLast()           { return last; }
}
