package com.shipyard.dock.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.shipyard.dock.common.Dict;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * 搭载任务：把某一段按搭载顺序号吊到某船台上。顺序必须按分段编号顺序推进，跳序拦截。
 */
@Entity
@Table(name = "erection")
@Getter
@Setter
public class Erection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 搭载任务号，唯一，service 端生成 ERE-船号-序号 */
    @Column(name = "erection_no", nullable = false, unique = true, length = 24)
    private String erectionNo;

    @Column(name = "vessel_id")
    private Long vesselId;

    @Column(name = "vessel_no", length = 24)
    private String vesselNo;

    @Column(name = "vessel_name", length = 48)
    private String vesselName;

    @Column(name = "block_id")
    private Long blockId;

    @Column(name = "block_code", length = 24)
    private String blockCode;

    /** 搭载顺序号，和分段的 seg_seq 一致 */
    @Column(name = "seg_seq")
    private Integer segSeq;

    @Column(name = "berth_id")
    private Long berthId;

    @Column(name = "berth_no", length = 16)
    private String berthNo;

    @Column(name = "plan_date")
    private LocalDate planDate;

    /** 状态：PLANNED / READY / DONE */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getStatusName() {
        return Dict.erectionStatusName(status);
    }

    @Transient
    public boolean isDone() {
        return "DONE".equals(status);
    }
}
