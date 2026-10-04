<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const props = defineProps({
  params: { type: Object, default: () => ({}) },
  tabKey: { type: String, default: '' }
})
const emit = defineEmits(['openTab'])

const board = ref({ releases: [], candidates: [] })
const detail = ref(null)
const loading = ref(false)
const acting = ref(false)
/** 带 releaseId 参数进来时只在首次加载定位一次，之后刷新不打断用户当前查看的单 */
const located = ref(false)

const generateDialog = ref(false)
const generateForm = ref({ vesselId: null, operator: '' })
const confirmDialog = ref(false)
const confirmForm = ref({ operator: '' })
const voidDialog = ref(false)
const voidForm = ref({ operator: '', reason: '' })
const reopenDialog = ref(false)
const reopenForm = ref({ operator: '', reason: '' })

const releases = computed(() => board.value.releases || [])
const candidates = computed(() => board.value.candidates || [])
const release = computed(() => detail.value?.release || null)
const snapshot = computed(() => detail.value?.snapshot || null)
const lastCheck = computed(() => detail.value?.lastCheck || null)

/** 当前展示的评估：优先最近一次核验，否则看生成时快照里的评估 */
const evaluation = computed(() => lastCheck.value || snapshot.value)
const blockers = computed(() => evaluation.value?.blockers || [])
const readyNow = computed(() => !!evaluation.value?.ready)
const summary = computed(() => snapshot.value?.summary || {})

/** 可生成放行单的候选：在厂、已上台、没有待确认单 */
const generatable = computed(() => candidates.value.filter((c) => c.berth && !c.openRelease))

function fmt(time) {
  return time ? String(time).replace('T', ' ').slice(0, 19) : '-'
}

function statusTagType(status) {
  if (status === 'CONFIRMED') return 'success'
  if (status === 'VOID') return 'info'
  return 'warning'
}

function verdictTagType(verdict) {
  return verdict === 'READY' ? 'success' : 'danger'
}

function contractTagType(status) {
  if (status === 'SETTLED') return 'success'
  if (status === 'PERFORMING') return 'primary'
  if (status === 'SIGNED') return 'warning'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    board.value = await api.launch.board()
    const want = located.value ? 0 : Number(props.params?.releaseId || 0)
    located.value = true
    if (want) {
      await selectRelease(want)
    } else if (release.value) {
      await selectRelease(release.value.id)
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function selectRelease(id) {
  try {
    detail.value = await api.launch.detail(id)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function openGenerate() {
  generateForm.value = { vesselId: generatable.value[0]?.vessel?.id || null, operator: '' }
  generateDialog.value = true
}

function openGenerateFor(candidate) {
  generateForm.value = { vesselId: candidate.vessel.id, operator: '' }
  generateDialog.value = true
}

async function doGenerate() {
  if (!generateForm.value.vesselId) {
    ElMessage.error('请选择船舶')
    return
  }
  acting.value = true
  try {
    const created = await api.launch.generate(generateForm.value)
    ElMessage.success(`放行单 ${created.releaseNo} 已生成（${created.verdictName}）`)
    generateDialog.value = false
    await load()
    await selectRelease(created.id)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    acting.value = false
  }
}

async function doCheck() {
  acting.value = true
  try {
    detail.value = await api.launch.check(release.value.id)
    const n = blockers.value.length
    if (readyNow.value) {
      ElMessage.success('核验通过：全部条件满足，可确认下水')
    } else {
      ElMessage.warning(`核验未通过：${n} 项阻塞，明细见下方清单`)
    }
    await loadBoardOnly()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    acting.value = false
  }
}

async function doConfirm() {
  acting.value = true
  try {
    const confirmed = await api.launch.confirm(release.value.id, confirmForm.value)
    ElMessage.success(`${confirmed.releaseNo} 已确认：${confirmed.vesselName} 已下水，${confirmed.berthNo} 已释放`)
    confirmDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
    // 确认失败也把后端留痕的核验记录拉回来，阻塞明细保持最新
    await load()
  } finally {
    acting.value = false
  }
}

async function doVoid() {
  acting.value = true
  try {
    await api.launch.voidRelease(release.value.id, voidForm.value)
    ElMessage.success('放行单已作废，快照留档备查')
    voidDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    acting.value = false
  }
}

async function doReopen() {
  acting.value = true
  try {
    const created = await api.launch.reopen(release.value.id, reopenForm.value)
    ElMessage.success(`已按最新数据重开 ${created.releaseNo}（${created.verdictName}）`)
    reopenDialog.value = false
    await load()
    await selectRelease(created.id)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    acting.value = false
  }
}

async function loadBoardOnly() {
  board.value = await api.launch.board()
}

function openVessel(candidate) {
  emit('openTab', {
    path: '/vessel',
    title: candidate.vessel.vesselNo + ' ' + candidate.vessel.vesselName,
    sub: candidate.vessel.ownerName,
    params: { vesselId: candidate.vessel.id }
  })
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">下水放行台</span>
      <span class="view-tip">放行单 = 在台船舶 + 合同 + 分段搭载交验 + 未闭环遗留项的生成时快照；确认后船舶下水、船台同步释放，全程留痕</span>
      <el-button size="small" style="margin-left:auto" :loading="loading" @click="load">刷新</el-button>
      <el-button size="small" type="primary" :disabled="!generatable.length" @click="openGenerate">生成放行单</el-button>
    </div>

    <!-- 在台船舶：谁能开单、谁已有待确认单 -->
    <section class="panel">
      <div class="panel-title">
        在厂船舶放行候选
        <span class="view-tip">在厂且已上台的船才能生成放行单；一条船同时最多一张待确认单（待办归属唯一）</span>
      </div>
      <div class="cand-cards" v-if="candidates.length">
        <div v-for="c in candidates" :key="c.vessel.id" class="cand-card">
          <div class="cand-head">
            <b class="link" @click="openVessel(c)">{{ c.vessel.vesselNo }} {{ c.vessel.vesselName }}</b>
            <el-tag size="small" :type="c.berth ? 'primary' : 'info'" effect="light">
              {{ c.berth ? c.berth.berthNo + ' ' + c.berth.berthName : '未上台' }}
            </el-tag>
          </div>
          <div class="cand-line">
            合同：
            <template v-if="c.contract">
              {{ c.contract.contractNo }}
              <el-tag size="small" :type="contractTagType(c.contract.status)" effect="plain">{{ c.contract.statusName }}</el-tag>
            </template>
            <span v-else class="warn-text">无合同</span>
          </div>
          <div class="cand-actions">
            <template v-if="c.openRelease">
              <el-button size="small" type="warning" plain @click="selectRelease(c.openRelease.id)">
                待确认单 {{ c.openRelease.releaseNo }}
              </el-button>
            </template>
            <el-button v-else size="small" type="primary" plain :disabled="!c.berth" @click="openGenerateFor(c)">
              生成放行单
            </el-button>
          </div>
        </div>
      </div>
      <div class="empty-tip" v-else>当前没有在厂船舶</div>
    </section>

    <!-- 放行单列表 -->
    <section class="panel">
      <div class="panel-title">
        放行单
        <span class="view-tip">共 {{ releases.length }} 张 · 生成时结论与最新核验分列，旧结论不会被新数据覆盖</span>
      </div>
      <el-table :data="releases" size="small" v-if="releases.length"
        :row-class-name="({ row }) => (release && row.id === release.id ? 'on-row' : '')"
        @row-click="(row) => selectRelease(row.id)">
        <el-table-column prop="releaseNo" label="单号" width="140">
          <template #default="{ row }"><b>{{ row.releaseNo }}</b></template>
        </el-table-column>
        <el-table-column label="船舶" min-width="130">
          <template #default="{ row }">{{ row.vesselNo }} {{ row.vesselName }}</template>
        </el-table-column>
        <el-table-column prop="berthNo" label="船台" width="80" />
        <el-table-column prop="contractNo" label="合同" width="120">
          <template #default="{ row }">{{ row.contractNo || '无' }}</template>
        </el-table-column>
        <el-table-column label="分段 搭载/交验" width="120" align="center">
          <template #default="{ row }">{{ row.erectedCount }}/{{ row.blockCount }} · {{ row.surveyOkCount }}/{{ row.blockCount }}</template>
        </el-table-column>
        <el-table-column label="严重未闭环" width="90" align="center">
          <template #default="{ row }">
            <span :class="{ 'warn-text': row.openCriticalCount > 0 }">{{ row.openCriticalCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="生成时结论" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="verdictTagType(row.verdict)" effect="light">{{ row.verdictName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="生成" width="150">
          <template #default="{ row }">{{ row.generatedBy }} · {{ fmt(row.generatedAt) }}</template>
        </el-table-column>
        <el-table-column label="确认放行" width="150">
          <template #default="{ row }">
            <span v-if="row.confirmedAt">{{ row.confirmedBy }} · {{ fmt(row.confirmedAt) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="empty-tip" v-else>还没有放行单，先在上方选一条在台船舶生成</div>
    </section>

    <!-- 放行单详情 -->
    <section class="panel" v-if="release">
      <div class="panel-title">
        放行单 {{ release.releaseNo }}
        <el-tag size="small" :type="statusTagType(release.status)">{{ release.statusName }}</el-tag>
        <el-tag size="small" :type="verdictTagType(release.verdict)" effect="plain">生成时：{{ release.verdictName }}</el-tag>
        <span class="view-tip" v-if="detail.regeneratedFromNo">由 {{ detail.regeneratedFromNo }} 作废重开而来</span>
      </div>

      <div class="rl-grid">
        <div><span class="k">船舶</span><b>{{ release.vesselNo }} {{ release.vesselName }}</b></div>
        <div><span class="k">船台</span><b>{{ release.berthNo }} {{ release.berthName }}</b></div>
        <div><span class="k">合同</span><b>{{ release.contractNo || '无' }}</b></div>
        <div><span class="k">分段 搭载 / 交验</span><b>{{ release.erectedCount }}/{{ release.blockCount }} · {{ release.surveyOkCount }}/{{ release.blockCount }}</b></div>
        <div><span class="k">未闭环遗留项</span><b>{{ release.openPunchCount }}（严重 {{ release.openCriticalCount }}）</b></div>
        <div><span class="k">阻塞项</span><b :class="{ 'warn-text': release.blockerCount > 0 }">{{ release.blockerCount }}</b></div>
      </div>

      <!-- 当前核验结论：待确认单看最新核验（或生成时评估），终态单看生成时快照 -->
      <template v-if="release.status === 'OPEN'">
        <div class="sub-title">
          当前核验结论
          <span class="view-tip">{{ lastCheck ? '最近一次核验 · ' + fmt(release.lastCheckAt) : '尚未重新核验，展示生成时评估' }}</span>
        </div>
        <div v-if="readyNow" class="ok-banner">✓ 全部条件满足：分段搭载交验齐全、严重遗留项闭环、合同非草拟，可确认下水放行</div>
        <div v-else class="blocker-list">
          <div v-for="(b, i) in blockers" :key="i" class="blocker-item">
            <span class="b-type">{{ b.typeName }}</span>
            <span class="b-msg">{{ b.message }}</span>
          </div>
        </div>
      </template>
      <template v-else-if="release.status === 'CONFIRMED'">
        <div class="ok-banner">✓ 已于 {{ fmt(release.confirmedAt) }} 由 {{ release.confirmedBy }} 确认放行：船舶已下水、{{ release.berthNo }} 已释放</div>
      </template>
      <template v-else>
        <div class="void-banner">✕ 已于 {{ fmt(release.voidedAt) }} 由 {{ release.voidedBy }} 作废：{{ release.voidReason }}（快照留档，不作为放行依据）</div>
      </template>

      <!-- 生成时快照：分段 -->
      <div class="sub-title">
        分段搭载与交验 · 生成时快照
        <span class="view-tip">共 {{ summary.blockCount || 0 }} 段 · 快照生成后不再改动</span>
      </div>
      <el-table :data="snapshot?.blocks || []" size="small" v-if="(snapshot?.blocks || []).length">
        <el-table-column prop="blockCode" label="分段编码" width="150" />
        <el-table-column prop="blockName" label="名称" width="110" />
        <el-table-column prop="statusName" label="阶段" width="80" />
        <el-table-column label="搭载" min-width="170">
          <template #default="{ row }">
            <span :class="row.erected ? 'ok-text' : 'warn-text'">{{ row.erected ? '✓ 已完成' : '✗ 未完成' }}</span>
            <span class="dim-text" v-if="row.erectionNo"> · {{ row.erectionNo }} {{ row.erectionStatusName }}</span>
            <span class="dim-text" v-else> · 无搭载任务</span>
          </template>
        </el-table-column>
        <el-table-column label="交验结论" min-width="170">
          <template #default="{ row }">
            <span :class="row.surveyOk ? 'ok-text' : 'warn-text'">{{ row.surveyOk ? '✓ 有效' : '✗ 无有效结论' }}</span>
            <span class="dim-text" v-if="row.latestSurveyNo"> · 最近 {{ row.latestSurveyNo }} {{ row.latestSurveyResultName }}</span>
            <span class="dim-text" v-else> · 尚未报验</span>
          </template>
        </el-table-column>
        <el-table-column label="未闭环遗留" width="110" align="center">
          <template #default="{ row }">
            <span :class="{ 'warn-text': row.openCritical > 0 }">
              {{ row.openPunch }}<template v-if="row.openCritical">（严重 {{ row.openCritical }}）</template>
            </span>
          </template>
        </el-table-column>
      </el-table>
      <div class="empty-tip" v-else>生成时该船还没有分段</div>

      <!-- 生成时快照：未闭环遗留项 -->
      <template v-if="(snapshot?.openPunches || []).length">
        <div class="sub-title">未闭环遗留项 · 生成时快照（{{ snapshot.openPunches.length }}）</div>
        <el-table :data="snapshot.openPunches" size="small">
          <el-table-column prop="punchNo" label="编号" width="160" />
          <el-table-column prop="blockCode" label="分段" width="150" />
          <el-table-column prop="title" label="标题" min-width="150" />
          <el-table-column label="严重度" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.critical ? 'danger' : row.severity === 'MAJOR' ? 'warning' : 'info'" effect="light">{{ row.severityName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="statusName" label="状态" width="90" align="center" />
          <el-table-column prop="owner" label="责任人" width="90" />
          <el-table-column prop="dueDate" label="期限" width="110" />
        </el-table>
      </template>

      <!-- 流转记录 -->
      <div class="sub-title">确认与流转记录</div>
      <div class="record-list">
        <div class="record-item"><span class="k">生成</span>{{ release.generatedBy }} · {{ fmt(release.generatedAt) }}</div>
        <div class="record-item" v-if="release.lastCheckAt">
          <span class="k">最近核验</span>{{ fmt(release.lastCheckAt) }} ·
          {{ lastCheck?.ready ? '通过' : '未通过（' + (lastCheck?.blockers?.length || 0) + ' 项阻塞）' }}
        </div>
        <div class="record-item" v-if="release.voidedAt">
          <span class="k">作废</span>{{ release.voidedBy }} · {{ fmt(release.voidedAt) }} · {{ release.voidReason }}
        </div>
        <div class="record-item" v-if="release.confirmedAt">
          <span class="k">确认放行</span>{{ release.confirmedBy }} · {{ fmt(release.confirmedAt) }}
        </div>
        <div class="record-item" v-if="detail.regeneratedFromNo">
          <span class="k">来源</span>由 {{ detail.regeneratedFromNo }} 作废重开而来，旧单快照留档备查
        </div>
      </div>

      <!-- 操作区 -->
      <div class="rl-actions">
        <template v-if="release.status === 'OPEN'">
          <el-button type="primary" :loading="acting" @click="confirmForm = { operator: '' }; confirmDialog = true">确认下水放行</el-button>
          <el-button plain :loading="acting" @click="doCheck">按最新数据核验</el-button>
          <el-button type="warning" plain :loading="acting" @click="reopenForm = { operator: '', reason: '' }; reopenDialog = true">作废并重开</el-button>
          <el-button type="danger" plain :loading="acting" @click="voidForm = { operator: '', reason: '' }; voidDialog = true">作废</el-button>
          <span class="view-tip">沿用本单继续整改后可重新核验、再确认（按确认时实时数据判定）；重开会保留本单快照作为历史证据</span>
        </template>
        <template v-else-if="release.status === 'VOID'">
          <el-button type="warning" plain :loading="acting" @click="reopenForm = { operator: '', reason: '' }; reopenDialog = true">按最新数据重开</el-button>
          <span class="view-tip">已作废的单不再作为放行依据；重开会生成新的待确认单并指回本单</span>
        </template>
        <template v-else>
          <span class="seal">已放行</span>
          <span class="view-tip">已签确认不可作废；本单与生成时快照作为历史证据永久保留</span>
        </template>
      </div>
    </section>

    <!-- 生成放行单 -->
    <el-dialog v-model="generateDialog" title="生成下水放行单" width="480px">
      <el-form label-width="96px">
        <el-form-item label="船舶">
          <el-select v-model="generateForm.vesselId" style="width:100%" placeholder="选择在厂且已上台的船舶">
            <el-option v-for="c in generatable" :key="c.vessel.id"
              :label="c.vessel.vesselNo + ' ' + c.vessel.vesselName + '（' + c.berth.berthNo + '）'"
              :value="c.vessel.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="操作人"><el-input v-model="generateForm.operator" placeholder="生成人姓名，留痕用" /></el-form-item>
        <div class="view-tip">生成时会把船舶、船台、当前合同、全部分段搭载交验与未闭环遗留项打进快照；有阻塞也会出单，阻塞明细写进单里</div>
      </el-form>
      <template #footer>
        <el-button @click="generateDialog = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="doGenerate">生成</el-button>
      </template>
    </el-dialog>

    <!-- 确认放行 -->
    <el-dialog v-model="confirmDialog" title="确认下水放行" width="480px">
      <el-form label-width="96px">
        <el-form-item label="放行单"><b>{{ release?.releaseNo }}</b>（{{ release?.vesselNo }} {{ release?.vesselName }}）</el-form-item>
        <el-form-item label="操作人"><el-input v-model="confirmForm.operator" placeholder="确认人姓名，留痕用" /></el-form-item>
        <div class="confirm-warn">
          确认将按<b>此刻最新数据</b>重新核验：全部分段完成搭载且有有效合格 / 让步结论、严重遗留项全部闭环、合同非草拟。
          通过后同一事务内完成：船舶推进为已下水、{{ release?.berthNo }} 释放、记录确认人与时间；任一条件不满足则整体不生效。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="confirmDialog = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="doConfirm">确认放行</el-button>
      </template>
    </el-dialog>

    <!-- 作废 -->
    <el-dialog v-model="voidDialog" title="作废放行单" width="480px">
      <el-form label-width="96px">
        <el-form-item label="放行单"><b>{{ release?.releaseNo }}</b></el-form-item>
        <el-form-item label="操作人"><el-input v-model="voidForm.operator" placeholder="作废人姓名，留痕用" /></el-form-item>
        <el-form-item label="作废原因"><el-input v-model="voidForm.reason" type="textarea" :rows="2" placeholder="必填，作为历史证据留存" /></el-form-item>
        <div class="view-tip">作废后快照与作废痕迹留档，本单不再作为放行依据；如需放行可再按最新数据重开</div>
      </el-form>
      <template #footer>
        <el-button @click="voidDialog = false">取消</el-button>
        <el-button type="danger" :loading="acting" @click="doVoid">作废</el-button>
      </template>
    </el-dialog>

    <!-- 作废重开 -->
    <el-dialog v-model="reopenDialog" title="作废并按最新数据重开" width="480px">
      <el-form label-width="96px">
        <el-form-item label="原放行单"><b>{{ release?.releaseNo }}</b>（{{ release?.statusName }}）</el-form-item>
        <el-form-item label="操作人"><el-input v-model="reopenForm.operator" placeholder="操作人姓名，留痕用" /></el-form-item>
        <el-form-item label="原因"><el-input v-model="reopenForm.reason" type="textarea" :rows="2" placeholder="选填，默认「作废重开：按最新数据重新评估」" /></el-form-item>
        <div class="view-tip">原单快照留档备查，新单按最新数据重新评估并指回原单；待确认待办归属转到新单</div>
      </el-form>
      <template #footer>
        <el-button @click="reopenDialog = false">取消</el-button>
        <el-button type="warning" :loading="acting" @click="doReopen">作废并重开</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cand-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 12px;
}
.cand-card {
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  padding: 10px 12px;
  background: #fff;
}
.cand-head { display: flex; align-items: center; gap: 8px; justify-content: space-between; }
.cand-head b { font-size: 13px; }
.cand-line { font-size: 11.5px; color: var(--ink-soft); margin-top: 6px; display: flex; align-items: center; gap: 4px; }
.cand-actions { margin-top: 8px; }

.rl-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 8px 14px;
  margin-bottom: 4px;
}
.rl-grid .k { font-size: 11px; color: var(--ink-dim); display: block; }
.rl-grid b { font-size: 13px; }

.sub-title {
  font-size: 12.5px;
  font-weight: 700;
  color: var(--ink);
  margin: 14px 0 8px;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.ok-banner {
  padding: 9px 12px;
  border-radius: 8px;
  background: #e8f5e9;
  border: 1px solid #c8e6c9;
  color: var(--pass);
  font-size: 12.5px;
  font-weight: 700;
}
.void-banner {
  padding: 9px 12px;
  border-radius: 8px;
  background: #eceff1;
  border: 1px solid var(--line-strong);
  color: var(--ink-soft);
  font-size: 12.5px;
}

.blocker-list { display: flex; flex-direction: column; gap: 6px; }
.blocker-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 8px;
  background: #ffebee;
  border: 1px solid #ffcdd2;
  font-size: 12px;
}
.b-type {
  flex: 0 0 auto;
  padding: 1px 8px;
  border-radius: 6px;
  background: var(--fail);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
}
.b-msg { color: #8e2424; }

.record-list { display: flex; flex-direction: column; gap: 5px; font-size: 12px; color: var(--ink-soft); }
.record-item .k {
  display: inline-block;
  min-width: 64px;
  color: var(--ink-dim);
  font-size: 11px;
}

.rl-actions { display: flex; align-items: center; gap: 10px; margin-top: 14px; flex-wrap: wrap; }

.seal {
  display: inline-block;
  padding: 4px 16px;
  border: 2px solid var(--pass);
  border-radius: 8px;
  color: var(--pass);
  font-size: 15px;
  font-weight: 800;
  letter-spacing: 4px;
  transform: rotate(-4deg);
}

.ok-text { color: var(--pass); font-weight: 700; }
.warn-text { color: var(--fail); font-weight: 700; }
.dim-text { color: var(--ink-dim); font-size: 11px; }
.link { color: var(--primary-deep); text-decoration: underline dotted; cursor: pointer; }

.confirm-warn {
  margin: 0 0 4px 96px;
  padding: 9px 12px;
  border-radius: 8px;
  background: #fff8e1;
  border: 1px solid #ffecb3;
  color: #7a5c00;
  font-size: 12px;
  line-height: 1.7;
}

:deep(.on-row) { background: #eceff1 !important; }
:deep(.el-table__row) { cursor: pointer; }
</style>
