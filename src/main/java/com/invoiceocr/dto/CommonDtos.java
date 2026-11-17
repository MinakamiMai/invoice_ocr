package com.invoiceocr.dto;

import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

public final class CommonDtos {

    private CommonDtos() {
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

        public static <T> PageResponse<T> of(Page<T> page) {
            return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }

        public static <E, R> PageResponse<R> of(Page<E> page, Function<E, R> mapper) {
            return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                    page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
    }

    public record ApiError(String timestamp, int status, String error, String message, String path) {
        public static ApiError of(int status, String error, String message, String path) {
            return new ApiError(LocalDateTime.now().toString(), status, error, message, path);
        }
    }
}
