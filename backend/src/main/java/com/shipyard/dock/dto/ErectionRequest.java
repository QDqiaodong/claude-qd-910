package com.shipyard.dock.dto;

import java.time.LocalDate;

import lombok.Data;

/** 搭载任务新建与局部更新入参 */
@Data
public class ErectionRequest {

    private Long vesselId;
    private Long blockId;
    private Integer segSeq;
    private Long berthId;
    private LocalDate planDate;
    private String status;
    private String note;
}
