package com.shipyard.dock.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

/** 吊次登记入参 */
@Data
public class LiftRequest {

    private String liftNo;
    private Long erectionId;
    private Long craneId;
    private BigDecimal liftWeightT;
    private LocalDateTime liftTime;
    private String operator;
    private Integer windLevel;
    private String remark;
}
