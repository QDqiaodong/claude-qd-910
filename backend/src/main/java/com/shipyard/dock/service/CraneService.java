package com.shipyard.dock.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.shipyard.dock.common.BizException;
import com.shipyard.dock.common.Dict;
import com.shipyard.dock.domain.Crane;
import com.shipyard.dock.dto.CraneRequest;
import com.shipyard.dock.repository.CraneRepository;
import com.shipyard.dock.repository.LiftRepository;

/**
 * 起重设备：吊次登记的额定起重量来源。检修 / 停用的设备不允许接吊次。
 */
@Service
public class CraneService {

    private final CraneRepository craneRepository;
    private final LiftRepository liftRepository;

    public CraneService(CraneRepository craneRepository, LiftRepository liftRepository) {
        this.craneRepository = craneRepository;
        this.liftRepository = liftRepository;
    }

    public List<Crane> list() {
        return craneRepository.findAllByOrderByIdAsc();
    }

    public Crane get(Long id) {
        return craneRepository.findById(id)
                .orElseThrow(() -> new BizException("起重设备不存在：" + id));
    }

    @Transactional
    public Crane create(CraneRequest request) {
        if (!StringUtils.hasText(request.getCraneNo())) {
            throw new BizException("设备编号不能为空");
        }
        if (craneRepository.existsByCraneNo(request.getCraneNo())) {
            throw new BizException("设备编号已存在：" + request.getCraneNo());
        }
        if (!Dict.isValidCraneType(request.getCraneType())) {
            throw new BizException("设备类型非法（龙门吊 / 塔吊 / 履带吊 / 浮吊 四选一）：" + request.getCraneType());
        }
        Crane crane = new Crane();
        apply(crane, request);
        if (crane.getStatus() == null) {
            crane.setStatus("AVAILABLE");
        }
        if (!Dict.isValidCraneStatus(crane.getStatus())) {
            throw new BizException("设备状态非法：" + crane.getStatus());
        }
        checkRatedLoad(crane);
        crane.setCreatedAt(LocalDateTime.now());
        return craneRepository.save(crane);
    }

    @Transactional
    public Crane update(Long id, CraneRequest request) {
        Crane crane = get(id);
        if (StringUtils.hasText(request.getCraneNo()) && !request.getCraneNo().equals(crane.getCraneNo())
                && craneRepository.existsByCraneNo(request.getCraneNo())) {
            throw new BizException("设备编号已存在：" + request.getCraneNo());
        }
        if (request.getCraneType() != null && !Dict.isValidCraneType(request.getCraneType())) {
            throw new BizException("设备类型非法（龙门吊 / 塔吊 / 履带吊 / 浮吊 四选一）：" + request.getCraneType());
        }
        if (request.getStatus() != null && !Dict.isValidCraneStatus(request.getStatus())) {
            throw new BizException("设备状态非法：" + request.getStatus());
        }
        applyPartial(crane, request);
        checkRatedLoad(crane);
        return craneRepository.save(crane);
    }

    @Transactional
    public void delete(Long id) {
        Crane crane = get(id);
        int lifts = liftRepository.findByCraneIdOrderByLiftTimeAsc(id).size();
        if (lifts > 0) {
            throw new BizException("设备 " + crane.getCraneNo() + " 已有 " + lifts + " 个吊次记录，不能删除");
        }
        craneRepository.deleteById(id);
    }

    private void apply(Crane crane, CraneRequest request) {
        crane.setCraneNo(request.getCraneNo());
        crane.setCraneName(request.getCraneName());
        crane.setCraneType(request.getCraneType());
        crane.setRatedLoadT(request.getRatedLoadT());
        crane.setSpanM(request.getSpanM());
        crane.setLiftHeightM(request.getLiftHeightM());
        crane.setStatus(request.getStatus());
        crane.setLocation(request.getLocation());
        crane.setNote(request.getNote());
    }

    private void applyPartial(Crane crane, CraneRequest request) {
        ifNotNull(request.getCraneNo(), crane::setCraneNo);
        ifNotNull(request.getCraneName(), crane::setCraneName);
        ifNotNull(request.getCraneType(), crane::setCraneType);
        ifNotNull(request.getRatedLoadT(), crane::setRatedLoadT);
        ifNotNull(request.getSpanM(), crane::setSpanM);
        ifNotNull(request.getLiftHeightM(), crane::setLiftHeightM);
        ifNotNull(request.getStatus(), crane::setStatus);
        ifNotNull(request.getLocation(), crane::setLocation);
        ifNotNull(request.getNote(), crane::setNote);
    }

    private void checkRatedLoad(Crane crane) {
        if (crane.getRatedLoadT() == null || crane.getRatedLoadT().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("额定起重量必须大于 0");
        }
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
