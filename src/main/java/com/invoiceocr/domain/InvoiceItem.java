package com.invoiceocr.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "invoice_item")
@Getter
@Setter
@NoArgsConstructor
public class InvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false, length = 36)
    private String invoiceId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "item_name", length = 500)
    private String itemName;

    @Column(length = 200)
    private String specification;

    @Column(length = 50)
    private String unit;

    @Column(precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", precision = 18, scale = 4)
    private BigDecimal unitPrice;

    @Column(precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "tax_rate", length = 32)
    private String taxRate;

    @Column(name = "tax_amount", precision = 18, scale = 2)
    private BigDecimal taxAmount;
}
