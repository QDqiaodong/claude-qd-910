<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const props = defineProps({
  params: { type: Object, default: () => ({}) },
  tabKey: { type: String, default: '' }
})
const emit = defineEmits(['openTab'])

const board = ref({ erections: [], lifts: [], cranes: [], blocks: [], craneStats: [], statusMeta: [], maxWindLevel: 6 })
const loading = ref(false)
const dialog = ref(false)

const liftForm = ref({
  erectionId: null,
  craneId: null,
  liftNo: '',
  liftWeightT: 120,
  operator: '刘长顺',
  windLevel: 3,
  remark: ''
})

const erectionForm = ref({ vesselId: null, blockId: null, berthId: null, planDate: '', note: '' })

const erections = computed(() => board.value.erections || [])
const lifts = computed(() => board.value.lifts || [])
const cranes = computed(() => board.value.cranes || [])
const erectionId = computed(() => Number(props.params?.erectionId || 0))
const detailErection = computed(() => erections.value.find((e) => e.id === erectionId.value) || null)
const detailLifts = computed(() =>
  erectionId.value ? lifts.value.filter((l) => l.erectionId === erectionId.value) : []
)

const pickedErection = computed(() => erections.value.find((e) => e.id === liftForm.value.erectionId) || null)
const pickedCrane = computed(() => cranes.value.find((c) => c.id === liftForm.value.craneId) || null)

/** 超额定起重量：前端先算出来，后端也会拦 */
const loadRate = computed(() => {
  const rated = Number(pickedCrane.value?.ratedLoadT || 0)
  const weight = Number(liftForm.value.liftWeightT || 0)
  if (!rated || !weight) return 0
  return Math.round((weight * 1000.0) / rated) / 10
})
const overload = computed(() => pickedCrane.value && loadRate.value > 100)
const nearFull = computed(() => loadRate.value >= 90 && loadRate.value <= 100)

function rateColor(rate) {
  if (rate > 100) return 'var(--fail)'
  if (rate >= 90) return 'var(--warn)'
  return 'var(--pass)'
}

function openErection(erection) {
  emit('openTab', {
    path: '/erection',
    title: erection.erectionNo,
    sub: erection.blockCode,
    params: { erectionId: erection.id }
  })
}

function openBlock(code, id) {
  if (!id) return
  emit('openTab', { path: '/block', title: code, params: { blockId: id } })
}

async function submitLift() {
  if (!liftForm.value.erectionId) {
    ElMessage.error('请选择搭载任务')
    return
  }
  if (overload.value) {
    ElMessage.error('超过额定起重量，禁止起吊')
    return
  }
  loading.value = true
  try {
    const lift = await api.erection.registerLift(liftForm.value.erectionId, liftForm.value)
    ElMessage.success(`吊次 ${lift.liftNo} 已登记，载荷率 ${lift.loadRate}%`)
    liftForm.value.liftNo = ''
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function advance(erection) {
  loading.value = true
  try {
    const updated = await api.erection.advance(erection.id)
    ElMessage.success(`${updated.erectionNo} 状态已更新为「${updated.statusName}」`)
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function createErection() {
  // 船舶由所选分段带出（后端会以分段的归属船为准），顺序号也取自分段
  const block = (board.value.blocks || []).find((b) => b.id === erectionForm.value.blockId)
  const payload = { ...erectionForm.value, vesselId: block ? block.vesselId : null }
  loading.value = true
  try {
    const created = await api.erection.create(payload)
    ElMessage.success(`搭载任务 ${created.erectionNo} 已排程`)
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    board.value = await api.erection.board()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title" v-if="!erectionId">搭载与吊装 · 吊装时间戳序列</span>
      <span class="view-title" v-else>{{ detailErection?.erectionNo || '搭载任务' }}</span>
      <span class="view-tip">左边一条按时间排的吊装流水，右边吊次借记卡：重量超过额定起重量会被拦</span>
    </div>

    <div class="grid-2">
      <!-- 时间戳序列 -->
      <section class="panel">
        <div class="panel-title">
          吊装时间戳序列
          <span class="view-tip">共 {{ lifts.length }} 个吊次</span>
        </div>
        <div class="timeline">
          <div v-for="lift in lifts" :key="lift.id" class="tl-item">
            <div class="tl-dot" :style="{ background: rateColor(lift.loadRate) }"></div>
            <div class="tl-body">
              <div class="tl-head">
                <b>{{ lift.liftNo }}</b>
                <span class="tl-time">{{ lift.liftTime }}</span>
              </div>
              <div class="tl-line">
                <span class="tl-code" @click="openBlock(lift.blockCode, lift.blockId)">{{ lift.blockCode }}</span>
                · {{ lift.craneName }} · 顺序 {{ lift.segSeq }}
              </div>
              <div class="tl-bar">
                <i :style="{ width: Math.min(100, lift.loadRate) + '%', background: rateColor(lift.loadRate) }"></i>
              </div>
              <div class="tl-line dim">
                {{ lift.liftWeightT }} t / 额定 {{ lift.ratedLoadT }} t · 载荷率
                <b :style="{ color: rateColor(lift.loadRate) }">{{ lift.loadRate }}%</b>
                · {{ lift.operator }} · {{ lift.windLevel }} 级风
              </div>
            </div>
          </div>
          <div class="empty-tip" v-if="!lifts.length">还没有吊次记录</div>
        </div>
      </section>

      <!-- 吊次借记卡 -->
      <div>
        <section class="panel">
          <div class="panel-title">吊次借记卡</div>
          <el-form label-width="104px">
            <el-form-item label="搭载任务">
              <el-select v-model="liftForm.erectionId" style="width:100%" placeholder="选择搭载任务">
                <el-option
                  v-for="e in erections" :key="e.id"
                  :label="e.erectionNo + ' · ' + e.blockCode + ' · 顺序 ' + e.segSeq"
                  :value="e.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="起重设备">
              <el-select v-model="liftForm.craneId" style="width:100%" placeholder="选择设备">
                <el-option
                  v-for="c in cranes" :key="c.id"
                  :label="c.craneNo + ' ' + c.craneName + '（额定 ' + c.ratedLoadT + ' t）'"
                  :value="c.id"
                  :disabled="c.status !== 'AVAILABLE'"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="吊次号"><el-input v-model="liftForm.liftNo" placeholder="如 LF-20260501-001" /></el-form-item>
            <el-form-item label="吊装重量">
              <el-input-number v-model="liftForm.liftWeightT" :min="1" />
              <span class="unit">t</span>
            </el-form-item>
            <el-form-item label="司机"><el-input v-model="liftForm.operator" /></el-form-item>
            <el-form-item label="风力">
              <el-input-number v-model="liftForm.windLevel" :min="0" :max="12" />
              <span class="unit">级（>{{ board.maxWindLevel }} 级禁止吊装）</span>
            </el-form-item>
            <el-form-item label="备注"><el-input v-model="liftForm.remark" /></el-form-item>
          </el-form>

          <div class="gauge" v-if="pickedCrane">
            <div class="gauge-top">
              <span>载荷率</span>
              <b :style="{ color: rateColor(loadRate) }">{{ loadRate }}%</b>
            </div>
            <div class="gauge-bar"><i :style="{ width: Math.min(100, loadRate) + '%', background: rateColor(loadRate) }"></i></div>
            <div class="gauge-foot">
              {{ liftForm.liftWeightT }} t / {{ pickedCrane.ratedLoadT }} t（{{ pickedCrane.craneName }}）
            </div>
            <div class="gauge-alert" v-if="overload">超过额定起重量，禁止起吊（后端同样拦截）</div>
            <div class="gauge-warn" v-else-if="nearFull">接近满载，注意试吊</div>
          </div>

          <div class="card-actions">
            <el-button type="primary" :loading="loading" :disabled="overload" @click="submitLift">登记吊次</el-button>
            <span class="view-tip" v-if="pickedErection">{{ pickedErection.blockCode }} · 顺序 {{ pickedErection.segSeq }}</span>
          </div>
        </section>

        <section class="panel">
          <div class="panel-title">按设备类型统计（手写 JPQL）</div>
          <el-table :data="board.craneStats" size="small">
            <el-table-column prop="craneTypeName" label="设备类型" width="100" />
            <el-table-column prop="liftCount" label="吊次数" width="80" />
            <el-table-column prop="weightSum" label="总吨位(t)" width="100" />
            <el-table-column prop="avgLoadRate" label="平均载荷率%" />
          </el-table>
        </section>
      </div>
    </div>

    <!-- 搭载任务 -->
    <section class="panel">
      <div class="panel-title">
        搭载任务
        <span class="view-tip">顺序必须按分段编号顺序推进，跳序会被后端拦截</span>
        <el-button size="small" type="primary" plain style="margin-left:auto" @click="dialog = true">新增搭载任务</el-button>
      </div>
      <el-table :data="erections" size="small">
        <el-table-column prop="erectionNo" label="任务号" width="160" />
        <el-table-column prop="vesselNo" label="船号" width="90" />
        <el-table-column prop="blockCode" label="分段" width="150">
          <template #default="{ row }">
            <span class="tl-code" @click="openBlock(row.blockCode, row.blockId)">{{ row.blockCode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="segSeq" label="顺序" width="70" />
        <el-table-column prop="berthNo" label="船台" width="90" />
        <el-table-column prop="planDate" label="计划日期" width="110" />
        <el-table-column prop="statusName" label="状态" width="90" />
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="openErection(row)">详情</el-button>
            <el-button size="small" type="primary" plain @click="advance(row)">推进</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 详情分页 -->
    <section class="panel" v-if="erectionId && detailErection">
      <div class="panel-title">
        {{ detailErection.erectionNo }} · 吊次明细（{{ detailLifts.length }}）
        <span class="view-tip">{{ detailErection.blockCode }} · 船台 {{ detailErection.berthNo || '-' }} · {{ detailErection.statusName }}</span>
      </div>
      <el-table :data="detailLifts" size="small" v-if="detailLifts.length">
        <el-table-column prop="liftNo" label="吊次号" width="160" />
        <el-table-column prop="craneName" label="设备" />
        <el-table-column prop="liftWeightT" label="重量(t)" width="90" />
        <el-table-column prop="ratedLoadT" label="额定(t)" width="90" />
        <el-table-column prop="liftTime" label="时间" width="160" />
        <el-table-column prop="remark" label="备注" />
      </el-table>
      <div class="empty-tip" v-else>这个任务还没有吊次记录</div>
    </section>

    <el-dialog v-model="dialog" title="新增搭载任务" width="520px">
      <el-form label-width="100px">
        <el-form-item label="分段">
          <el-select v-model="erectionForm.blockId" style="width:100%">
            <el-option
              v-for="b in board.blocks || []" :key="b.id"
              :label="b.blockCode + ' ' + b.blockName + '（' + b.vesselNo + ' 顺序 ' + b.segSeq + '）'"
              :value="b.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="船台">
          <el-select v-model="erectionForm.berthId" style="width:100%">
            <el-option v-for="b in board.berths || []" :key="b.id" :label="b.berthNo + ' ' + b.berthName" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="计划日期"><el-input v-model="erectionForm.planDate" placeholder="2026-05-08" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="erectionForm.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="createErection">排程</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.timeline { position: relative; padding-left: 6px; }
.tl-item { display: flex; gap: 10px; padding-bottom: 12px; }
.tl-dot { width: 10px; height: 10px; border-radius: 50%; margin-top: 4px; flex: 0 0 auto; }
.tl-body { flex: 1 1 auto; border-bottom: 1px dashed var(--line); padding-bottom: 8px; }
.tl-head { display: flex; align-items: baseline; gap: 8px; }
.tl-time { font-size: 11px; color: var(--ink-dim); margin-left: auto; }
.tl-line { font-size: 11.5px; color: var(--ink-soft); margin-top: 2px; }
.tl-line.dim { color: var(--ink-dim); }
.tl-code { color: var(--primary-deep); font-weight: 700; text-decoration: underline dotted; cursor: pointer; }
.tl-bar { height: 5px; background: var(--line); border-radius: 3px; margin: 6px 0 4px; overflow: hidden; }
.tl-bar i { display: block; height: 100%; }

.unit { font-size: 11px; color: var(--ink-dim); margin-left: 6px; }

.gauge { background: var(--primary-wash); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; }
.gauge-top { display: flex; justify-content: space-between; font-size: 12px; }
.gauge-top b { font-size: 17px; }
.gauge-bar { height: 8px; background: #fff; border-radius: 4px; margin-top: 6px; overflow: hidden; }
.gauge-bar i { display: block; height: 100%; }
.gauge-foot { font-size: 11px; color: var(--ink-dim); margin-top: 6px; }
.gauge-alert { margin-top: 6px; font-size: 12px; font-weight: 700; color: var(--fail); }
.gauge-warn { margin-top: 6px; font-size: 12px; font-weight: 700; color: var(--warn); }
.card-actions { display: flex; align-items: center; gap: 10px; margin-top: 12px; }
</style>
