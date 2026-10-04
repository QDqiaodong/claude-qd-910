package com.shipyard.dock.domain;

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
 * 下水放行单：面向船台调度与质量人员的放行凭证。
 *
 * <p>生成时把「在台船舶 + 船台 + 当前合同 + 全部分段的搭载与交验进度 + 未闭环遗留项 +
 * 评估出的阻塞明细」整体打进 {@code snapshotJson} 快照，落库后不再改动，
 * 让后续能解释当时为什么允许或拒绝下水。</p>
 *
 * <p>状态流转：待确认 → 已放行 / 已作废。已放行与已作废都是终态：
 * 已签确认不可作废，作废单保留快照作为历史证据，重开只会生成新单并指回旧单。</p>
 */
@Entity
@Table(name = "launch_release")
@Getter
@Setter
public class LaunchRelease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 放行单号，唯一：LR-船号-序号 */
    @Column(name = "release_no", nullable = false, unique = true, length = 24)
    private String releaseNo;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "vessel_no", length = 24)
    private String vesselNo;

    @Column(name = "vessel_name", length = 48)
    private String vesselName;

    /** 生成时船舶所在船台 */
    @Column(name = "berth_id")
    private Long berthId;

    @Column(name = "berth_no", length = 16)
    private String berthNo;

    @Column(name = "berth_name", length = 48)
    private String berthName;

    /** 生成时判定的当前合同（最新一份未结算的；全部已结算则取最新一份） */
    @Column(name = "contract_id")
    private Long contractId;

    @Column(name = "contract_no", length = 24)
    private String contractNo;

    /** 状态：OPEN 待确认 / CONFIRMED 已放行 / VOID 已作废 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 生成时评估结论：READY 可放行 / BLOCKED 有阻塞（历史结论，确认成功也不会回写） */
    @Column(name = "verdict", length = 16)
    private String verdict;

    @Column(name = "block_count")
    private Integer blockCount;

    @Column(name = "erected_count")
    private Integer erectedCount;

    @Column(name = "survey_ok_count")
    private Integer surveyOkCount;

    @Column(name = "open_punch_count")
    private Integer openPunchCount;

    @Column(name = "open_critical_count")
    private Integer openCriticalCount;

    @Column(name = "blocker_count")
    private Integer blockerCount;

    /** 生成时关键快照（JSON），落库后不再改动 */
    @Column(name = "snapshot_json", columnDefinition = "TEXT")
    private String snapshotJson;

    /** 最近一次核验时间（重新核验 / 确认时按实时数据评估并留痕） */
    @Column(name = "last_check_at")
    private LocalDateTime lastCheckAt;

    /** 最近一次核验结果（JSON），与生成时快照分开存放，新旧结论互不覆盖 */
    @Column(name = "last_check_json", columnDefinition = "TEXT")
    private String lastCheckJson;

    /** 若本单由旧单作废重开而来，记录旧单 id，串起历史证据链 */
    @Column(name = "regenerated_from")
    private Long regeneratedFrom;

    @Column(name = "generated_by", length = 32)
    private String generatedBy;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "voided_by", length = 32)
    private String voidedBy;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    @Column(name = "void_reason", length = 128)
    private String voidReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getStatusName() {
        return Dict.releaseStatusName(status);
    }

    @Transient
    public String getVerdictName() {
        return Dict.releaseVerdictName(verdict);
    }

    @Transient
    public boolean isOpen() {
        return "OPEN".equals(status);
    }

    @Transient
    public boolean isConfirmed() {
        return "CONFIRMED".equals(status);
    }

    @Transient
    public boolean isVoid() {
        return "VOID".equals(status);
    }
}
