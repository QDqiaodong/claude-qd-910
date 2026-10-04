package com.shipyard.dock.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shipyard.dock.common.BizException;
import com.shipyard.dock.common.Dict;
import com.shipyard.dock.domain.Berth;
import com.shipyard.dock.domain.Block;
import com.shipyard.dock.domain.Contract;
import com.shipyard.dock.domain.Erection;
import com.shipyard.dock.domain.LaunchRelease;
import com.shipyard.dock.domain.PunchItem;
import com.shipyard.dock.domain.Survey;
import com.shipyard.dock.domain.Vessel;
import com.shipyard.dock.dto.LaunchReleaseRequest;
import com.shipyard.dock.generation.CodeSequenceService;
import com.shipyard.dock.repository.BerthRepository;
import com.shipyard.dock.repository.BlockRepository;
import com.shipyard.dock.repository.ContractRepository;
import com.shipyard.dock.repository.ErectionRepository;
import com.shipyard.dock.repository.LaunchReleaseRepository;
import com.shipyard.dock.repository.PunchItemRepository;
import com.shipyard.dock.repository.SurveyRepository;
import com.shipyard.dock.repository.VesselRepository;

/**
 * 模块六：下水放行台。
 *
 * <p>放行规则（生成与确认时都按同一套实时数据评估，任一不满足即阻塞并点名具体对象）：<br>
 * ① 船舶在厂且当前在台；② 全部分段完成搭载（已进入合拢 / 交验阶段，或有已完成的搭载任务）
 * 且取得有效的合格或让步结论；③ 严重（CRITICAL）遗留项全部闭环；④ 当前合同不是草拟状态。</p>
 *
 * <p>一致性约定：<br>
 * · 生成时快照 {@code snapshotJson} 落库后不再改动，确认时的核验结果另存 {@code lastCheckJson}，
 *   旧结论不会被新数据悄悄覆盖；<br>
 * · 确认走行锁 + 单事务：船舶下水、船台释放、放行单确认同生共死，重复点击幂等返回同一结果；<br>
 * · 已确认的放行单不可作废；作废单保留快照作为历史证据，重开只生成新单并指回旧单，
 *   一条船同时最多一张待确认单（待办归属唯一）。</p>
 */
@Service
public class LaunchService {

    /** 放行单号序号键 */
    private static final String RELEASE_SEQ_KEY = "RELEASE_NO";

    private final LaunchReleaseRepository releaseRepository;
    private final VesselRepository vesselRepository;
    private final BerthRepository berthRepository;
    private final ContractRepository contractRepository;
    private final BlockRepository blockRepository;
    private final ErectionRepository erectionRepository;
    private final SurveyRepository surveyRepository;
    private final PunchItemRepository punchItemRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public LaunchService(LaunchReleaseRepository releaseRepository, VesselRepository vesselRepository,
            BerthRepository berthRepository, ContractRepository contractRepository,
            BlockRepository blockRepository, ErectionRepository erectionRepository,
            SurveyRepository surveyRepository, PunchItemRepository punchItemRepository,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.releaseRepository = releaseRepository;
        this.vesselRepository = vesselRepository;
        this.berthRepository = berthRepository;
        this.contractRepository = contractRepository;
        this.blockRepository = blockRepository;
        this.erectionRepository = erectionRepository;
        this.surveyRepository = surveyRepository;
        this.punchItemRepository = punchItemRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public LaunchRelease get(Long id) {
        return releaseRepository.findById(id)
                .orElseThrow(() -> new BizException("放行单不存在：" + id));
    }

    public List<LaunchRelease> list() {
        return releaseRepository.findAllByOrderByIdDesc();
    }

    /** 放行台总览：全部放行单 + 在厂船舶的放行候选（船、船台、当前合同、待确认单） */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<LaunchRelease> releases = releaseRepository.findAllByOrderByIdDesc();
        data.put("releases", releases);

        List<Berth> berths = berthRepository.findAllByOrderByIdAsc();
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Vessel vessel : vesselRepository.findAllByOrderByIdAsc()) {
            if (!"IN_YARD".equals(vessel.getStatus())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("vessel", vessel);
            item.put("berth", berths.stream()
                    .filter(b -> vessel.getId().equals(b.getVesselId())).findFirst().orElse(null));
            item.put("contract", pickCurrent(contractRepository.findByVesselIdOrderByIdAsc(vessel.getId())));
            item.put("openRelease", releases.stream()
                    .filter(r -> r.isOpen() && vessel.getId().equals(r.getVesselId())).findFirst().orElse(null));
            candidates.add(item);
        }
        data.put("candidates", candidates);
        return data;
    }

    /** 放行单详情：单据 + 生成时快照 + 最近一次核验，新旧结论都摆出来 */
    public Map<String, Object> detail(Long id) {
        LaunchRelease release = get(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("release", release);
        data.put("snapshot", readJson(release.getSnapshotJson()));
        data.put("lastCheck", readJson(release.getLastCheckJson()));
        if (release.getRegeneratedFrom() != null) {
            releaseRepository.findById(release.getRegeneratedFrom())
                    .ifPresent(prev -> data.put("regeneratedFromNo", prev.getReleaseNo()));
        }
        return data;
    }

    /** 生成放行单：把当前在台船舶、合同、分段、遗留项打成一张带快照的单 */
    @Transactional
    public LaunchRelease generate(LaunchReleaseRequest request) {
        requireOperator(request.getOperator());
        if (request.getVesselId() == null) {
            throw new BizException("请选择要生成放行单的船舶");
        }
        Vessel vessel = vesselRepository.findById(request.getVesselId())
                .orElseThrow(() -> new BizException("船舶不存在：" + request.getVesselId()));
        if (!"IN_YARD".equals(vessel.getStatus())) {
            throw new BizException("船舶 " + vessel.getVesselNo() + " 当前状态为「"
                    + Dict.vesselStatusName(vessel.getStatus()) + "」，只有在厂船舶才能生成下水放行单");
        }
        Berth berth = berthRepository.findFirstByVesselIdOrderByIdAsc(vessel.getId())
                .orElseThrow(() -> new BizException("船舶 " + vessel.getVesselNo()
                        + " 当前不在任何船台 / 船坞上，无法安排下水放行"));
        releaseRepository.findFirstByVesselIdAndStatusOrderByIdDesc(vessel.getId(), "OPEN")
                .ifPresent(open -> {
                    throw new BizException("该船已存在待确认的放行单 " + open.getReleaseNo()
                            + "：可沿用该单继续整改后直接确认，或作废后按最新数据重开");
                });
        Evaluation evaluation = evaluate(vessel, berth);
        LaunchRelease release = new LaunchRelease();
        fill(release, vessel, berth, evaluation, request.getOperator(), null);
        return releaseRepository.save(release);
    }

    /** 重新核验：沿用旧单继续整改时，按最新数据评估并把结果写进 lastCheck（不动生成时快照） */
    @Transactional
    public Map<String, Object> check(Long id) {
        LaunchRelease release = releaseRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("放行单不存在：" + id));
        if (!release.isOpen()) {
            throw new BizException("放行单 " + release.getReleaseNo() + " 已处于「"
                    + release.getStatusName() + "」，只有待确认的单才能重新核验");
        }
        Vessel vessel = vesselRepository.findById(release.getVesselId())
                .orElseThrow(() -> new BizException("船舶不存在：" + release.getVesselId()));
        Berth berth = berthRepository.findFirstByVesselIdOrderByIdAsc(vessel.getId()).orElse(null);
        Evaluation evaluation = evaluate(vessel, berth);
        release.setLastCheckAt(LocalDateTime.now());
        release.setLastCheckJson(writeJson(evaluation.snapshot));
        releaseRepository.save(release);
        return detail(id);
    }

    /**
     * 确认下水放行：行锁 + 单事务。
     *
     * <p>① 已确认的单直接返回原结果（多人重复点击幂等，只会有一个生效结果）；
     * ② 按确认时刻的实时数据重新核验，不满足则只落核验记录、不碰船舶与船台；
     * ③ 通过则在同一事务里把船舶推进为已下水、释放船台、确认放行单并记录操作者与时间，
     * 任何一步失败整体回滚，不留半完成状态。</p>
     */
    public LaunchRelease confirm(Long id, LaunchReleaseRequest request) {
        requireOperator(request.getOperator());
        Object outcome = transactionTemplate.execute(status -> {
            LaunchRelease release = releaseRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new BizException("放行单不存在：" + id));
            if (release.isConfirmed()) {
                return release;
            }
            if (release.isVoid()) {
                throw new BizException("放行单 " + release.getReleaseNo()
                        + " 已作废，不能确认；如需放行请按最新数据重开");
            }
            Vessel vessel = vesselRepository.findById(release.getVesselId())
                    .orElseThrow(() -> new BizException("船舶不存在：" + release.getVesselId()));
            Berth berth = berthRepository.findFirstByVesselIdOrderByIdAsc(vessel.getId()).orElse(null);
            Evaluation evaluation = evaluate(vessel, berth);
            release.setLastCheckAt(LocalDateTime.now());
            release.setLastCheckJson(writeJson(evaluation.snapshot));
            if (!evaluation.ready) {
                // 核验不通过：提交核验记录供追溯，随后由外层抛业务异常，船舶与船台分毫不动
                releaseRepository.save(release);
                return new Blocked(evaluation.blockerMessages());
            }
            vessel.setStatus("LAUNCHED");
            vesselRepository.save(vessel);
            if (berth != null) {
                berth.setVesselId(null);
                berth.setVesselName(null);
                berth.setStatus("IDLE");
                berth.setOccupiedAt(null);
                berthRepository.save(berth);
            }
            release.setStatus("CONFIRMED");
            release.setConfirmedBy(request.getOperator());
            release.setConfirmedAt(LocalDateTime.now());
            return releaseRepository.save(release);
        });
        if (outcome instanceof Blocked blocked) {
            throw new BizException("放行条件不满足，本次核验已留痕：" + String.join("；", blocked.messages()));
        }
        return (LaunchRelease) outcome;
    }

    /** 作废：待确认单转为已作废，快照与作废痕迹留档；已签确认的单不可作废 */
    @Transactional
    public LaunchRelease voidRelease(Long id, LaunchReleaseRequest request) {
        requireOperator(request.getOperator());
        if (!StringUtils.hasText(request.getReason())) {
            throw new BizException("作废必须填写作废原因，作为历史证据留存");
        }
        LaunchRelease release = releaseRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("放行单不存在：" + id));
        if (release.isConfirmed()) {
            throw new BizException("放行单 " + release.getReleaseNo()
                    + " 已确认放行（船舶已下水、船台已释放），已签确认不可作废，作为历史证据保留");
        }
        if (release.isVoid()) {
            throw new BizException("放行单 " + release.getReleaseNo() + " 已是作废状态，无需重复作废");
        }
        release.setStatus("VOID");
        release.setVoidedBy(request.getOperator());
        release.setVoidedAt(LocalDateTime.now());
        release.setVoidReason(request.getReason());
        return releaseRepository.save(release);
    }

    /**
     * 作废重开：旧单（待确认的先作废）保留快照作为历史证据，
     * 按最新数据生成新的待确认单并指回旧单；待办归属随之转到新单。
     */
    @Transactional
    public LaunchRelease reopen(Long id, LaunchReleaseRequest request) {
        requireOperator(request.getOperator());
        LaunchRelease old = releaseRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("放行单不存在：" + id));
        if (old.isConfirmed()) {
            throw new BizException("放行单 " + old.getReleaseNo()
                    + " 已确认放行，不能作废重开；已签确认作为历史证据永久保留");
        }
        if (old.isOpen()) {
            old.setStatus("VOID");
            old.setVoidedBy(request.getOperator());
            old.setVoidedAt(LocalDateTime.now());
            old.setVoidReason(StringUtils.hasText(request.getReason())
                    ? request.getReason() : "作废重开：按最新数据重新评估");
            releaseRepository.save(old);
        }
        Vessel vessel = vesselRepository.findById(old.getVesselId())
                .orElseThrow(() -> new BizException("船舶不存在：" + old.getVesselId()));
        if (!"IN_YARD".equals(vessel.getStatus())) {
            throw new BizException("船舶 " + vessel.getVesselNo() + " 当前状态为「"
                    + Dict.vesselStatusName(vessel.getStatus()) + "」，不能重开放行单");
        }
        Berth berth = berthRepository.findFirstByVesselIdOrderByIdAsc(vessel.getId())
                .orElseThrow(() -> new BizException("船舶 " + vessel.getVesselNo()
                        + " 当前不在任何船台 / 船坞上，无法重开放行单"));
        Evaluation evaluation = evaluate(vessel, berth);
        LaunchRelease release = new LaunchRelease();
        fill(release, vessel, berth, evaluation, request.getOperator(), old.getId());
        return releaseRepository.save(release);
    }

    // ---------------- 评估：生成与确认共用同一套规则 ----------------

    /** 评估结果：快照（含阻塞明细）+ 是否可放行 */
    private static final class Evaluation {
        private final boolean ready;
        private final Map<String, Object> snapshot;

        private Evaluation(boolean ready, Map<String, Object> snapshot) {
            this.ready = ready;
            this.snapshot = snapshot;
        }

        @SuppressWarnings("unchecked")
        private List<String> blockerMessages() {
            List<Map<String, Object>> blockers =
                    (List<Map<String, Object>>) snapshot.getOrDefault("blockers", List.of());
            List<String> messages = new ArrayList<>();
            for (Map<String, Object> blocker : blockers) {
                messages.add(String.valueOf(blocker.get("message")));
            }
            return messages;
        }
    }

    /** 确认时核验不通过的内部结果（核验记录照常提交，阻塞明细由外层转成业务异常） */
    private record Blocked(List<String> messages) {
    }

    /**
     * 按实时数据评估放行条件，并把评估对象整体打成快照。
     * 任一条件不满足都往 blockers 里写一条点名具体对象的明细，不只给汇总数字。
     */
    private Evaluation evaluate(Vessel vessel, Berth berth) {
        List<Map<String, Object>> blockers = new ArrayList<>();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("checkedAt", LocalDateTime.now());

        Map<String, Object> vesselSnap = new LinkedHashMap<>();
        vesselSnap.put("id", vessel.getId());
        vesselSnap.put("vesselNo", vessel.getVesselNo());
        vesselSnap.put("vesselName", vessel.getVesselName());
        vesselSnap.put("status", vessel.getStatus());
        vesselSnap.put("statusName", Dict.vesselStatusName(vessel.getStatus()));
        snapshot.put("vessel", vesselSnap);
        if (!"IN_YARD".equals(vessel.getStatus())) {
            blocker(blockers, "VESSEL", "船舶", vessel.getId(), vessel.getVesselNo(),
                    "船舶当前状态为「" + Dict.vesselStatusName(vessel.getStatus())
                            + "」，只有在厂船舶能确认下水");
        }
        if (berth == null) {
            blocker(blockers, "BERTH", "船台", null, "-",
                    "船舶当前不在任何船台 / 船坞上，无法完成下水与船台释放");
        } else {
            Map<String, Object> berthSnap = new LinkedHashMap<>();
            berthSnap.put("id", berth.getId());
            berthSnap.put("berthNo", berth.getBerthNo());
            berthSnap.put("berthName", berth.getBerthName());
            berthSnap.put("status", berth.getStatus());
            berthSnap.put("statusName", Dict.berthStatusName(berth.getStatus()));
            snapshot.put("berth", berthSnap);
        }

        Contract contract = pickCurrent(contractRepository.findByVesselIdOrderByIdAsc(vessel.getId()));
        if (contract == null) {
            blocker(blockers, "CONTRACT", "合同", null, "-",
                    "该船名下没有任何修造合同，无法判定合同状态");
        } else {
            Map<String, Object> contractSnap = new LinkedHashMap<>();
            contractSnap.put("id", contract.getId());
            contractSnap.put("contractNo", contract.getContractNo());
            contractSnap.put("contractType", contract.getContractType());
            contractSnap.put("contractTypeName", Dict.contractTypeName(contract.getContractType()));
            contractSnap.put("status", contract.getStatus());
            contractSnap.put("statusName", Dict.contractStatusName(contract.getStatus()));
            snapshot.put("contract", contractSnap);
            if ("DRAFT".equals(contract.getStatus())) {
                blocker(blockers, "CONTRACT", "合同", contract.getId(), contract.getContractNo(),
                        "合同 " + contract.getContractNo() + " 仍为「草拟」，未正式签订，不允许下水");
            }
        }

        List<Block> blocks = blockRepository.findByVesselIdOrderBySegSeqAsc(vessel.getId());
        if (blocks.isEmpty()) {
            blocker(blockers, "BLOCK", "分段", null, "-",
                    "该船还没有任何分段，不具备整船下水条件");
        }
        List<Map<String, Object>> blockSnaps = new ArrayList<>();
        List<Map<String, Object>> punchSnaps = new ArrayList<>();
        int erectedCount = 0;
        int surveyOkCount = 0;
        int openPunchCount = 0;
        int openCriticalCount = 0;
        for (Block block : blocks) {
            List<Erection> erections = erectionRepository.findByBlockIdOrderByIdAsc(block.getId());
            Erection latestErection = erections.isEmpty() ? null : erections.get(erections.size() - 1);
            // 完成搭载：分段已进入合拢 / 交验阶段，或存在已完成的搭载任务
            boolean erected = Dict.blockStatusIndex(block.getStatus()) >= Dict.blockStatusIndex("CLOSURE")
                    || erections.stream().anyMatch(e -> "DONE".equals(e.getStatus()));
            List<Survey> surveys = surveyRepository.findByBlockIdOrderBySurveyDateDesc(block.getId());
            Survey latestSurvey = surveys.isEmpty() ? null : surveys.get(0);
            // 有效结论：至少一单合格或让步
            boolean surveyOk = surveys.stream()
                    .anyMatch(s -> "PASS".equals(s.getResult()) || "CONCESSION".equals(s.getResult()));
            List<PunchItem> opens = punchItemRepository.findByBlockIdAndStatusNotOrderByIdAsc(block.getId(), "CLOSED");
            long critical = opens.stream().filter(p -> "CRITICAL".equals(p.getSeverity())).count();

            if (!erected) {
                blocker(blockers, "BLOCK", "分段搭载", block.getId(), block.getBlockCode(),
                        "分段 " + block.getBlockCode() + "（" + block.getBlockName() + "）尚未完成搭载：当前阶段「"
                                + Dict.blockStatusName(block.getStatus()) + "」"
                                + (latestErection == null ? "，且没有搭载任务"
                                        : "，搭载任务 " + latestErection.getErectionNo() + " 为「"
                                                + Dict.erectionStatusName(latestErection.getStatus()) + "」"));
            }
            if (!surveyOk) {
                blocker(blockers, "SURVEY", "分段交验", block.getId(), block.getBlockCode(),
                        "分段 " + block.getBlockCode() + "（" + block.getBlockName() + "）没有有效的合格 / 让步结论"
                                + (latestSurvey == null ? "：尚未报验"
                                        : "：最近报验 " + latestSurvey.getSurveyNo() + " 结论为「"
                                                + Dict.surveyResultName(latestSurvey.getResult()) + "」"));
            }
            for (PunchItem punch : opens) {
                if ("CRITICAL".equals(punch.getSeverity())) {
                    blocker(blockers, "PUNCH", "严重遗留项", punch.getId(), punch.getPunchNo(),
                            "严重遗留项 " + punch.getPunchNo() + "「" + punch.getTitle() + "」未闭环：分段 "
                                    + punch.getBlockCode() + "，责任人 "
                                    + (punch.getOwner() == null ? "-" : punch.getOwner()) + "，当前「"
                                    + Dict.punchStatusName(punch.getStatus()) + "」");
                }
                Map<String, Object> punchSnap = new LinkedHashMap<>();
                punchSnap.put("punchId", punch.getId());
                punchSnap.put("punchNo", punch.getPunchNo());
                punchSnap.put("blockCode", punch.getBlockCode());
                punchSnap.put("title", punch.getTitle());
                punchSnap.put("severity", punch.getSeverity());
                punchSnap.put("severityName", Dict.punchSeverityName(punch.getSeverity()));
                punchSnap.put("status", punch.getStatus());
                punchSnap.put("statusName", Dict.punchStatusName(punch.getStatus()));
                punchSnap.put("owner", punch.getOwner());
                punchSnap.put("dueDate", punch.getDueDate());
                punchSnap.put("critical", "CRITICAL".equals(punch.getSeverity()));
                punchSnaps.add(punchSnap);
            }

            erectedCount += erected ? 1 : 0;
            surveyOkCount += surveyOk ? 1 : 0;
            openPunchCount += opens.size();
            openCriticalCount += (int) critical;

            Map<String, Object> blockSnap = new LinkedHashMap<>();
            blockSnap.put("blockId", block.getId());
            blockSnap.put("blockCode", block.getBlockCode());
            blockSnap.put("blockName", block.getBlockName());
            blockSnap.put("status", block.getStatus());
            blockSnap.put("statusName", Dict.blockStatusName(block.getStatus()));
            blockSnap.put("erected", erected);
            blockSnap.put("erectionNo", latestErection == null ? null : latestErection.getErectionNo());
            blockSnap.put("erectionStatus", latestErection == null ? null : latestErection.getStatus());
            blockSnap.put("erectionStatusName",
                    latestErection == null ? null : Dict.erectionStatusName(latestErection.getStatus()));
            blockSnap.put("surveyOk", surveyOk);
            blockSnap.put("latestSurveyNo", latestSurvey == null ? null : latestSurvey.getSurveyNo());
            blockSnap.put("latestSurveyResult", latestSurvey == null ? null : latestSurvey.getResult());
            blockSnap.put("latestSurveyResultName",
                    latestSurvey == null ? null : Dict.surveyResultName(latestSurvey.getResult()));
            blockSnap.put("openPunch", opens.size());
            blockSnap.put("openCritical", (int) critical);
            blockSnaps.add(blockSnap);
        }
        snapshot.put("blocks", blockSnaps);
        snapshot.put("openPunches", punchSnaps);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("blockCount", blocks.size());
        summary.put("erectedCount", erectedCount);
        summary.put("surveyOkCount", surveyOkCount);
        summary.put("openPunchCount", openPunchCount);
        summary.put("openCriticalCount", openCriticalCount);
        summary.put("blockerCount", blockers.size());
        snapshot.put("summary", summary);
        snapshot.put("blockers", blockers);
        snapshot.put("ready", blockers.isEmpty());
        return new Evaluation(blockers.isEmpty(), snapshot);
    }

    private static void blocker(List<Map<String, Object>> blockers, String type, String typeName,
            Long refId, String refNo, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("typeName", typeName);
        item.put("refId", refId);
        item.put("refNo", refNo);
        item.put("message", message);
        blockers.add(item);
    }

    /** 当前合同：最新一份未结算的；全部已结算则取最新一份 */
    private static Contract pickCurrent(List<Contract> contracts) {
        Contract current = null;
        for (Contract contract : contracts) {
            if (!"SETTLED".equals(contract.getStatus())) {
                current = contract;
            }
        }
        if (current == null && !contracts.isEmpty()) {
            current = contracts.get(contracts.size() - 1);
        }
        return current;
    }

    /** 生成 / 重开共用：按评估结果填单（单号、快照、汇总列、生成人） */
    private void fill(LaunchRelease release, Vessel vessel, Berth berth, Evaluation evaluation,
            String operator, Long regeneratedFrom) {
        release.setReleaseNo(nextReleaseNo(vessel.getVesselNo()));
        release.setVesselId(vessel.getId());
        release.setVesselNo(vessel.getVesselNo());
        release.setVesselName(vessel.getVesselName());
        release.setBerthId(berth.getId());
        release.setBerthNo(berth.getBerthNo());
        release.setBerthName(berth.getBerthName());
        Object contract = evaluation.snapshot.get("contract");
        if (contract instanceof Map<?, ?> contractMap) {
            release.setContractId((Long) contractMap.get("id"));
            release.setContractNo((String) contractMap.get("contractNo"));
        }
        release.setStatus("OPEN");
        release.setVerdict(evaluation.ready ? "READY" : "BLOCKED");
        Map<?, ?> summary = (Map<?, ?>) evaluation.snapshot.get("summary");
        release.setBlockCount((Integer) summary.get("blockCount"));
        release.setErectedCount((Integer) summary.get("erectedCount"));
        release.setSurveyOkCount((Integer) summary.get("surveyOkCount"));
        release.setOpenPunchCount((Integer) summary.get("openPunchCount"));
        release.setOpenCriticalCount((Integer) summary.get("openCriticalCount"));
        release.setBlockerCount((Integer) summary.get("blockerCount"));
        release.setSnapshotJson(writeJson(evaluation.snapshot));
        release.setRegeneratedFrom(regeneratedFrom);
        release.setGeneratedBy(operator);
        release.setGeneratedAt(LocalDateTime.now());
        release.setCreatedAt(LocalDateTime.now());
    }

    /** 放行单号：LR-船号-序号（序号按船号分键自增，并发安全） */
    private String nextReleaseNo(String vesselNo) {
        long seq = CodeSequenceService.next(RELEASE_SEQ_KEY, vesselNo);
        return "LR-" + vesselNo + "-" + String.format("%02d", seq);
    }

    private static void requireOperator(String operator) {
        if (!StringUtils.hasText(operator)) {
            throw new BizException("请填写操作人姓名，放行与作废都要留痕");
        }
    }

    private String writeJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new BizException("放行单快照序列化失败：" + e.getMessage());
        }
    }

    private Map<String, Object> readJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new BizException("放行单快照解析失败：" + e.getMessage());
        }
    }
}
