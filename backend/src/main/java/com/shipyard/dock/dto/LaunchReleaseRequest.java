package com.shipyard.dock.dto;

import lombok.Data;

/** 下水放行单操作入参：生成 / 确认 / 作废 / 重开共用 */
@Data
public class LaunchReleaseRequest {

    /** 生成放行单时必填：目标船舶 */
    private Long vesselId;

    /** 操作人（生成人 / 确认人 / 作废人），必填留痕 */
    private String operator;

    /** 作废 / 重开时填写的原因 */
    private String reason;
}
