package com.shipyard.dock.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.shipyard.dock.common.BizException;
import com.shipyard.dock.common.Dict;
import com.shipyard.dock.domain.Berth;
import com.shipyard.dock.domain.Block;
import com.shipyard.dock.domain.Crane;
import com.shipyard.dock.domain.Erection;
import com.shipyard.dock.domain.Lift;
import com.shipyard.dock.generation.CodeSequenceService;
import com.shipyard.dock.dto.ErectionRequest;
import com.shipyard.dock.dto.LiftRequest;
import com.shipyard.dock.repository.BerthRepository;
import com.shipyard.dock.repository.BlockRepository;
import com.shipyard.dock.repository.CraneRepository;
import com.shipyard.dock.repository.ErectionRepository;
import com.shipyard.dock.repository.LiftRepository;

/**
 * 模块四：搭载与吊装。
 *
 * <p>规则：① 吊次号唯一；② 单次吊装重量必须 &gt; 0 且不得超过所用起重设备的额定起重量；
 * ③ 搭载顺序必须按分段编号顺序推进，跳序拦截；④ 同一吊次重复登记拦截。</p>
 */
@Service
public class ErectionService {

    /** 搭载任务号序号键 */
    private static final String ERECTION_SEQ_KEY = "ERECTION_NO";

    private final ErectionRepository erectionRepository;
    private final LiftRepository liftRepository;
    private final BlockRepository blockRepository;
    private final CraneRepository craneRepository;
    private final BerthRepository berthRepository;

    public ErectionService(ErectionRepository erectionRepository, LiftRepository liftRepository,
            BlockRepository blockRepository, CraneRepository craneRepository, BerthRepository berthRepository) {
        this.erectionRepository = erectionRepository;
        this.liftRepository = liftRepository;
        this.blockRepository = blockRepository;
        this.craneRepository = craneRepository;
        this.berthRepository = berthRepository;
    }

    public List<Erection> list() {
        return erectionRepository.findAllByOrderByIdAsc();
    }

    public Erection get(Long id) {
        return erectionRepository.findById(id)
                .orElseThrow(() -> new BizException("搭载任务不存在：" + id));
    }

    public List<Lift> lifts() {
        return liftRepository.findAllByOrderByLiftTimeDesc();
    }

    @Transactional
    public Erection create(ErectionRequest request) {
        if (request.getVesselId() == null) {
            throw new BizException("搭载任务必须指定船舶");
        }
        if (request.getBlockId() == null) {
            throw new BizException("搭载任务必须指定分段");
        }
        Block block = blockRepository.findById(request.getBlockId())
                .orElseThrow(() -> new BizException("分段不存在：" + request.getBlockId()));
        if (!request.getVesselId().equals(block.getVesselId())) {
            throw new BizException("分段 " + block.getBlockCode() + " 不属于该船，不能编入这条船的搭载计划");
        }
        if (!Boolean.TRUE.equals(block.getPainted())) {
            throw new BizException("分段 " + block.getBlockCode() + " 尚未完成涂装，不允许排搭载任务");
        }
        Erection erection = new Erection();
        erection.setVesselId(block.getVesselId());
        erection.setVesselNo(block.getVesselNo());
        erection.setVesselName(block.getVesselName());
        erection.setBlockId(block.getId());
        erection.setBlockCode(block.getBlockCode());
        erection.setSegSeq(block.getSegSeq());
        erection.setBerthId(request.getBerthId());
        erection.setPlanDate(request.getPlanDate());
        erection.setNote(request.getNote());
        erection.setStatus(request.getStatus() == null ? "PLANNED" : request.getStatus());
        if (!Dict.isValidErectionStatus(erection.getStatus())) {
            throw new BizException("搭载状态非法：" + erection.getStatus());
        }
        // 搭载顺序必须按分段编号顺序推进，跳序拦截
        checkSeq(erection);
        bindBerth(erection);
        erection.setErectionNo(nextErectionNo(erection.getVesselNo()));
        erection.setCreatedAt(LocalDateTime.now());
        return erectionRepository.save(erection);
    }

    @Transactional
    public Erection update(Long id, ErectionRequest request) {
        Erection erection = get(id);
        if (request.getStatus() != null && !request.getStatus().equals(erection.getStatus())
                && !Dict.canTransferErection(erection.getStatus(), request.getStatus())) {
            throw new BizException("搭载状态不能从 " + Dict.erectionStatusName(erection.getStatus())
                    + " 变为 " + Dict.erectionStatusName(request.getStatus()));
        }
        applyPartial(erection, request);
        if (request.getBerthId() != null) {
            bindBerth(erection);
        }
        return erectionRepository.save(erection);
    }

    /** 推进搭载任务：已排程 → 待吊装 → 已完成 */
    @Transactional
    public Erection advance(Long id) {
        Erection erection = get(id);
        if ("DONE".equals(erection.getStatus())) {
            throw new BizException("搭载任务 " + erection.getErectionNo() + " 已完成，无需再推进");
        }
        String next = "PLANNED".equals(erection.getStatus()) ? "READY" : "DONE";
        if ("DONE".equals(next) && liftRepository.findByErectionIdOrderByLiftTimeAsc(id).isEmpty()) {
            throw new BizException("搭载任务 " + erection.getErectionNo() + " 还没有吊次记录，不能标记完成");
        }
        erection.setStatus(next);
        return erectionRepository.save(erection);
    }

    @Transactional
    public void delete(Long id) {
        Erection erection = get(id);
        if (!liftRepository.findByErectionIdOrderByLiftTimeAsc(id).isEmpty()) {
            throw new BizException("搭载任务 " + erection.getErectionNo() + " 已有吊次记录，不能删除");
        }
        erectionRepository.deleteById(id);
    }

    /**
     * 登记一个吊次。
     *
     * <p>校验：吊次号唯一、重量为正且不超过额定起重量、设备可用、风力不超限、同一吊次不重复登记。</p>
     */
    @Transactional
    public Lift registerLift(Long erectionId, LiftRequest request) {
        Erection erection = get(erectionId);
        if (!StringUtils.hasText(request.getLiftNo())) {
            throw new BizException("吊次号不能为空");
        }
        if (liftRepository.existsByLiftNo(request.getLiftNo())) {
            throw new BizException("吊次号已存在：" + request.getLiftNo());
        }
        if (request.getCraneId() == null) {
            throw new BizException("请选择本次吊装使用的起重设备");
        }
        Crane crane = craneRepository.findById(request.getCraneId())
                .orElseThrow(() -> new BizException("起重设备不存在：" + request.getCraneId()));
        if (!crane.isAvailable()) {
            throw new BizException("设备 " + crane.getCraneNo() + " 当前为「"
                    + Dict.craneStatusName(crane.getStatus()) + "」，不能接吊次");
        }
        if (request.getLiftWeightT() == null || request.getLiftWeightT().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("单次吊装重量必须大于 0");
        }
        if (request.getLiftWeightT().compareTo(Dict.MAX_LIFT_WEIGHT) > 0) {
            throw new BizException("吊装重量 " + request.getLiftWeightT() + " t 超过单吊次上限 "
                    + Dict.MAX_LIFT_WEIGHT + " t");
        }
        if (crane.getRatedLoadT() != null && request.getLiftWeightT().compareTo(crane.getRatedLoadT()) > 0) {
            throw new BizException("吊装重量 " + request.getLiftWeightT() + " t 超过 "
                    + crane.getCraneName() + " 额定起重量 " + crane.getRatedLoadT() + " t，禁止起吊");
        }
        if (request.getWindLevel() != null && request.getWindLevel() > Dict.MAX_WIND_LEVEL) {
            throw new BizException("风力 " + request.getWindLevel() + " 级超过 " + Dict.MAX_WIND_LEVEL
                    + " 级，禁止吊装作业");
        }
        // 同一吊次重复登记拦截
        boolean duplicated = liftRepository.findByErectionIdOrderByLiftTimeAsc(erectionId).stream()
                .anyMatch(l -> crane.getId().equals(l.getCraneId())
                        && erection.getBlockId().equals(l.getBlockId()));
        if (duplicated) {
            throw new BizException("吊次重复登记：搭载任务 " + erection.getErectionNo() + " 的分段 "
                    + erection.getBlockCode() + " 已由 " + crane.getCraneNo() + " 登记过吊次");
        }

        Lift lift = new Lift();
        lift.setLiftNo(request.getLiftNo());
        lift.setErectionId(erection.getId());
        lift.setErectionNo(erection.getErectionNo());
        lift.setBlockId(erection.getBlockId());
        lift.setBlockCode(erection.getBlockCode());
        lift.setCraneId(crane.getId());
        lift.setCraneNo(crane.getCraneNo());
        lift.setCraneName(crane.getCraneName());
        lift.setRatedLoadT(crane.getRatedLoadT());
        lift.setLiftWeightT(request.getLiftWeightT());
        lift.setSegSeq(erection.getSegSeq());
        lift.setLiftTime(request.getLiftTime() == null ? LocalDateTime.now() : request.getLiftTime());
        lift.setOperator(request.getOperator());
        lift.setWindLevel(request.getWindLevel());
        lift.setRemark(request.getRemark());
        lift.setCreatedAt(LocalDateTime.now());
        Lift saved = liftRepository.save(lift);

        // 吊次落库后：搭载任务置为已完成，分段自动推进到「搭载」阶段
        erection.setStatus("DONE");
        erectionRepository.save(erection);
        blockRepository.findById(erection.getBlockId()).ifPresent(block -> {
            if ("PAINT".equals(block.getStatus())) {
                block.setStatus("ERECTION");
                blockRepository.save(block);
            }
        });
        return saved;
    }

    /** 吊装时间戳序列视图：搭载任务 + 吊次 + 设备 + 分段 */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Erection> erections = erectionRepository.findAllByOrderByIdAsc();
        List<Lift> lifts = liftRepository.findAllByOrderByLiftTimeDesc();
        data.put("erections", erections);
        data.put("lifts", lifts);
        data.put("cranes", craneRepository.findAllByOrderByIdAsc());
        data.put("blocks", blockRepository.findAllByOrderByIdAsc());
        data.put("berths", berthRepository.findAllByOrderByIdAsc());
        data.put("craneStats", liftRepository.statByCraneType());
        data.put("maxWindLevel", Dict.MAX_WIND_LEVEL);

        List<Map<String, Object>> statusMeta = new ArrayList<>();
        for (String status : Dict.ERECTION_STATUSES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", status);
            item.put("name", Dict.erectionStatusName(status));
            item.put("count", erections.stream().filter(e -> status.equals(e.getStatus())).count());
            statusMeta.add(item);
        }
        data.put("statusMeta", statusMeta);
        return data;
    }

    /** 搭载任务详情：任务 + 吊次 + 分段 + 船台 */
    public Map<String, Object> detail(Long id) {
        Erection erection = get(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("erection", erection);
        data.put("lifts", liftRepository.findByErectionIdOrderByLiftTimeAsc(id));
        blockRepository.findById(erection.getBlockId()).ifPresent(block -> data.put("block", block));
        if (erection.getBerthId() != null) {
            berthRepository.findById(erection.getBerthId()).ifPresent(berth -> data.put("berth", berth));
        }
        return data;
    }

    /** 搭载任务号：ERE-船号-序号 */
    private String nextErectionNo(String vesselNo) {
        String scope = StringUtils.hasText(vesselNo) ? vesselNo : "COMMON";
        long seq = CodeSequenceService.next(ERECTION_SEQ_KEY, scope);
        return "ERE-" + scope + "-" + String.format("%03d", seq);
    }

    /** 搭载顺序必须按分段编号顺序推进：新任务的顺序号 = 该船已排最大顺序号 + 1 */
    private void checkSeq(Erection erection) {
        Optional<Erection> last = erectionRepository.findFirstByVesselIdOrderBySegSeqDesc(erection.getVesselId());
        int expected = last.map(Erection::getSegSeq).orElse(0) + 1;
        if (erection.getSegSeq() == null || erection.getSegSeq() != expected) {
            throw new BizException("搭载顺序跳序：船号 " + erection.getVesselNo() + " 下一段应排顺序号 "
                    + expected + "，当前提交的是 " + erection.getSegSeq()
                    + "（必须按分段编号顺序推进）");
        }
    }

    /** 搭载任务只能挂在该船占用的船台上 */
    private void bindBerth(Erection erection) {
        if (erection.getBerthId() == null) {
            return;
        }
        Berth berth = berthRepository.findById(erection.getBerthId())
                .orElseThrow(() -> new BizException("船台不存在：" + erection.getBerthId()));
        if (!erection.getVesselId().equals(berth.getVesselId())) {
            throw new BizException("船台 " + berth.getBerthNo() + " 上没有船号 " + erection.getVesselNo()
                    + " 的船，不能把它的分段排到这个船台上");
        }
        erection.setBerthNo(berth.getBerthNo());
    }

    private void applyPartial(Erection erection, ErectionRequest request) {
        ifNotNull(request.getSegSeq(), erection::setSegSeq);
        ifNotNull(request.getBerthId(), erection::setBerthId);
        ifNotNull(request.getPlanDate(), erection::setPlanDate);
        ifNotNull(request.getStatus(), erection::setStatus);
        ifNotNull(request.getNote(), erection::setNote);
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
