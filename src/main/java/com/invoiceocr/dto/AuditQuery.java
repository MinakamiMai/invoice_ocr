package com.invoiceocr.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class AuditQuery {

    private String username;
    private String action;
    private String objectType;
    private String objectId;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private Integer page = 0;
    private Integer size = 20;
}
