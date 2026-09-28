package com.shipyard.dock.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
 * 吊次：一次真实的起吊记录。吊次号唯一，重量不得超过所用设备的额定起重量。
 */
@Entity
@Table(name = "lift")
@Getter
@Setter
public class Lift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 吊次号，唯一 */
    @Column(name = "lift_no", nullable = false, unique = true, length = 24)
    private String liftNo;

    @Column(name = "erection_id")
    private Long erectionId;

    @Column(name = "erection_no", length = 24)
    private String erectionNo;

    @Column(name = "block_id")
    private Long blockId;

    @Column(name = "block_code", length = 24)
    private String blockCode;

    @Column(name = "crane_id")
    private Long craneId;

    @Column(name = "crane_no", length = 16)
    private String craneNo;

    @Column(name = "crane_name", length = 48)
    private String craneName;

    /** 当时设备的额定起重量（吨） */
    @Column(name = "rated_load_t", precision = 10, scale = 2)
    private BigDecimal ratedLoadT;

    /** 本次吊装重量（吨），必须 > 0 且 <= 额定起重量 */
    @Column(name = "lift_weight_t", precision = 10, scale = 2)
    private BigDecimal liftWeightT;

    @Column(name = "seg_seq")
    private Integer segSeq;

    @Column(name = "lift_time")
    private LocalDateTime liftTime;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "wind_level")
    private Integer windLevel;

    @Column(name = "remark", length = 64)
    private String remark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 载荷率 %：吊装重量 / 额定起重量 */
    @Transient
    public double getLoadRate() {
        if (ratedLoadT == null || ratedLoadT.signum() <= 0 || liftWeightT == null) {
            return 0.0;
        }
        return Math.round(liftWeightT.doubleValue() * 1000.0 / ratedLoadT.doubleValue()) / 10.0;
    }

    /** 超载：吊装重量超过额定起重量 */
    @Transient
    public boolean isOverload() {
        return ratedLoadT != null && liftWeightT != null && liftWeightT.compareTo(ratedLoadT) > 0;
    }

    /** 接近满载（>= 90%） */
    @Transient
    public boolean isNearFull() {
        return getLoadRate() >= 90.0;
    }
}
