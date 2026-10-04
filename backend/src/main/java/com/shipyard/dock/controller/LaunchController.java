package com.shipyard.dock.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shipyard.dock.common.ApiResponse;
import com.shipyard.dock.domain.LaunchRelease;
import com.shipyard.dock.dto.LaunchReleaseRequest;
import com.shipyard.dock.service.LaunchService;

/**
 * 模块六：下水放行台。
 *
 * <p>一张放行单 = 在台船舶 + 船台 + 当前合同 + 全部分段搭载交验 + 未闭环遗留项的快照；
 * 确认放行后船舶下水、船台同步释放，全程留痕可追溯。</p>
 */
@RestController
@RequestMapping("/api/launch")
public class LaunchController {

    private final LaunchService launchService;

    public LaunchController(LaunchService launchService) {
        this.launchService = launchService;
    }

    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(launchService.board());
    }

    @GetMapping("/releases")
    public ApiResponse<List<LaunchRelease>> list() {
        return ApiResponse.success(launchService.list());
    }

    @GetMapping("/releases/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(launchService.detail(id));
    }

    /** 生成放行单（一船同时最多一张待确认单） */
    @PostMapping("/releases")
    public ApiResponse<LaunchRelease> generate(@RequestBody LaunchReleaseRequest request) {
        return ApiResponse.success("放行单已生成", launchService.generate(request));
    }

    /** 沿用旧单继续整改：按最新数据重新核验，结果写进核验记录 */
    @PostMapping("/releases/{id}/check")
    public ApiResponse<Map<String, Object>> check(@PathVariable Long id) {
        return ApiResponse.success("已按最新数据核验", launchService.check(id));
    }

    /** 确认下水放行：同一事务内船舶下水 + 船台释放 + 放行单确认 */
    @PostMapping("/releases/{id}/confirm")
    public ApiResponse<LaunchRelease> confirm(@PathVariable Long id, @RequestBody LaunchReleaseRequest request) {
        LaunchRelease release = launchService.confirm(id, request);
        return ApiResponse.success("放行单 " + release.getReleaseNo() + " 已确认：船舶已下水、船台已释放", release);
    }

    /** 作废：保留快照与作废痕迹，已签确认的单不可作废 */
    @PostMapping("/releases/{id}/void")
    public ApiResponse<LaunchRelease> voidRelease(@PathVariable Long id, @RequestBody LaunchReleaseRequest request) {
        return ApiResponse.success("放行单已作废，快照留档备查", launchService.voidRelease(id, request));
    }

    /** 作废重开：旧单留档，按最新数据生成新的待确认单 */
    @PostMapping("/releases/{id}/reopen")
    public ApiResponse<LaunchRelease> reopen(@PathVariable Long id, @RequestBody LaunchReleaseRequest request) {
        return ApiResponse.success("已按最新数据重开放行单", launchService.reopen(id, request));
    }
}
