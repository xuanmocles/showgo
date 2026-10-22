<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Plus } from '@element-plus/icons-vue'
import { getShowDetail } from '@/api/show'
import {
  adminCreateSession,
  adminRecomputeStock,
  adminSessionStatus,
  adminSessions
} from '@/api/admin'

const route = useRoute()
const router = useRouter()
const showId = Number(route.params.showId)
const show = ref(null)
const sessions = ref([])
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = ref(defaultForm())

const STATUS = {
  1: { label: '待开售', type: 'info' },
  2: { label: '售票中', type: 'success' },
  3: { label: '已售罄', type: 'warning' },
  4: { label: '已结束', type: 'info' },
  5: { label: '已取消', type: 'danger' },
  6: { label: '已停售', type: 'warning' }
}

const totalSeats = computed(() =>
  form.value.areas.reduce(
    (sum, a) => sum + (Number(a.rowCount) || 0) * (Number(a.seatPerRow) || 0),
    0
  )
)

const rules = {
  sessionName: [{ required: true, message: '请输入场次名称', trigger: 'blur' }],
  beginTime: [{ required: true, message: '请选择开演时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择散场时间', trigger: 'change' }],
  saleBeginTime: [{ required: true, message: '请选择开售时间', trigger: 'change' }],
  saleEndTime: [{ required: true, message: '请选择停售时间', trigger: 'change' }]
}

const pad = (n) => String(n).padStart(2, '0')
const fmt = (d) =>
  `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes()
  )}:${pad(d.getSeconds())}`

function defaultForm() {
  const now = new Date()
  const begin = new Date(now.getTime() + 3 * 24 * 3600 * 1000)
  begin.setHours(19, 30, 0, 0)
  const end = new Date(begin.getTime() + 2 * 3600 * 1000)
  const saleBegin = new Date(now.getTime() - 24 * 3600 * 1000)
  const saleEnd = new Date(begin.getTime() - 30 * 60 * 1000)
  return {
    showId,
    sessionName: `${pad(begin.getMonth() + 1)}-${pad(begin.getDate())} 19:30`,
    beginTime: fmt(begin),
    endTime: fmt(end),
    saleBeginTime: fmt(saleBegin),
    saleEndTime: fmt(saleEnd),
    status: 2,
    areas: [
      { areaCode: 'A', areaName: 'A区 内场VIP', areaType: 2, price: 380, color: '#f56c6c', rowCount: 8, seatPerRow: 20 },
      { areaCode: 'B', areaName: 'B区 看台', areaType: 1, price: 180, color: '#409eff', rowCount: 12, seatPerRow: 24 }
    ]
  }
}

const load = async () => {
  loading.value = true
  try {
    const [showRes, sessionRes] = await Promise.all([
      getShowDetail(showId),
      adminSessions(showId)
    ])
    show.value = showRes.data
    sessions.value = sessionRes.data || []
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  form.value = defaultForm()
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

const addArea = () => {
  const index = form.value.areas.length
  form.value.areas.push({
    areaCode: String.fromCharCode(65 + index),
    areaName: `${String.fromCharCode(65 + index)}区`,
    areaType: 1,
    price: 180,
    color: '#409eff',
    rowCount: 8,
    seatPerRow: 20
  })
}

const removeArea = (index) => {
  if (form.value.areas.length <= 1) {
    ElMessage.warning('至少保留一个分区')
    return
  }
  form.value.areas.splice(index, 1)
}

const submit = async () => {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  if (totalSeats.value <= 0) {
    ElMessage.warning('座位总数必须大于 0')
    return
  }
  if (totalSeats.value > 50000) {
    ElMessage.warning('单场次座位不能超过 5 万个')
    return
  }
  const codes = new Set()
  for (const area of form.value.areas) {
    if (!area.areaCode || !area.areaName) {
      ElMessage.warning('分区的编码和名称不能为空')
      return
    }
    if (codes.has(area.areaCode)) {
      ElMessage.warning(`分区编码重复：${area.areaCode}`)
      return
    }
    codes.add(area.areaCode)
  }

  saving.value = true
  try {
    await adminCreateSession({
      ...form.value,
      showId,
      areas: form.value.areas.map((area, index) => ({
        areaNo: index + 1,
        areaCode: area.areaCode,
        areaName: area.areaName,
        areaType: area.areaType,
        price: Math.round(Number(area.price) * 100),
        color: area.color,
        rowCount: Number(area.rowCount),
        seatPerRow: Number(area.seatPerRow)
      }))
    })
    ElMessage.success(`场次已创建，${totalSeats.value} 个座位与 Redis 座位图已就绪`)
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

const setStatus = async (row, status) => {
  await adminSessionStatus(row.id, status)
  ElMessage.success('状态已更新')
  load()
}

const recompute = async (row) => {
  const res = await adminRecomputeStock(row.id)
  ElMessage.success(`余票已按座位明细重算：${res.data}`)
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="topbar">
      <div class="left">
        <el-icon class="back" @click="router.push('/admin/shows')"><ArrowLeft /></el-icon>
        <span class="title">场次管理</span>
        <span v-if="show" class="sub">{{ show.name }}</span>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建场次</el-button>
    </header>

    <el-table v-loading="loading" :data="sessions" stripe style="width: 100%">
      <el-table-column prop="id" label="场次ID" width="190" />
      <el-table-column prop="sessionName" label="场次" min-width="140" />
      <el-table-column prop="beginTime" label="开演时间" width="180" />
      <el-table-column label="售票窗口" min-width="300">
        <template #default="{ row }">
          <div class="small">{{ row.saleBeginTime }}</div>
          <div class="small">~ {{ row.saleEndTime }}</div>
        </template>
      </el-table-column>
      <el-table-column label="余票 / 总座位" width="140">
        <template #default="{ row }">
          <span class="stock">{{ row.seatAvailable }}</span> / {{ row.seatTotal }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="STATUS[row.status]?.type" size="small">{{ STATUS[row.status]?.label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="290" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status !== 2" size="small" type="success" link @click="setStatus(row, 2)">
            开售
          </el-button>
          <el-button v-if="row.status === 2" size="small" type="warning" link @click="setStatus(row, 6)">
            停售
          </el-button>
          <el-button size="small" link @click="setStatus(row, 3)">标记售罄</el-button>
          <el-button size="small" type="primary" link @click="recompute(row)">重算余票</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="!loading && !sessions.length" class="empty">
      <el-empty description="这个演出还没有场次，点击右上角新建" />
    </div>

    <el-dialog v-model="dialogVisible" title="新建场次" width="860px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="场次名称" prop="sessionName">
              <el-input v-model="form.sessionName" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-select v-model="form.status" style="width: 100%">
                <el-option v-for="(item, key) in STATUS" :key="key" :label="item.label" :value="Number(key)" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="开演时间" prop="beginTime">
              <el-date-picker
                v-model="form.beginTime"
                type="datetime"
                value-format="YYYY-MM-DD HH:mm:ss"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="散场时间" prop="endTime">
              <el-date-picker
                v-model="form.endTime"
                type="datetime"
                value-format="YYYY-MM-DD HH:mm:ss"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="开售时间" prop="saleBeginTime">
              <el-date-picker
                v-model="form.saleBeginTime"
                type="datetime"
                value-format="YYYY-MM-DD HH:mm:ss"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="停售时间" prop="saleEndTime">
              <el-date-picker
                v-model="form.saleEndTime"
                type="datetime"
                value-format="YYYY-MM-DD HH:mm:ss"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <div class="area-title">
          <span>分区与座位（自动生成座位）</span>
          <el-button size="small" type="primary" plain @click="addArea">添加分区</el-button>
        </div>

        <div v-for="(area, index) in form.areas" :key="index" class="area-row">
          <el-input v-model="area.areaCode" placeholder="编码" style="width: 70px" />
          <el-input v-model="area.areaName" placeholder="分区名称" style="width: 150px" />
          <el-select v-model="area.areaType" style="width: 100px">
            <el-option label="普通" :value="1" />
            <el-option label="VIP" :value="2" />
            <el-option label="无障碍" :value="3" />
          </el-select>
          <el-input-number v-model="area.price" :min="0" :precision="2" :step="50" controls-position="right" style="width: 120px" />
          <span class="unit">元</span>
          <el-input-number v-model="area.rowCount" :min="1" :max="100" controls-position="right" style="width: 100px" />
          <span class="unit">排</span>
          <el-input-number v-model="area.seatPerRow" :min="1" :max="100" controls-position="right" style="width: 100px" />
          <span class="unit">座/排</span>
          <el-color-picker v-model="area.color" size="small" />
          <el-button size="small" type="danger" link @click="removeArea(index)">删除</el-button>
        </div>

        <div class="summary">
          本场次共 <b>{{ totalSeats }}</b> 个座位；创建后会自动写入 Redis 座位图并预热缓存。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">创建场次</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f5f7fa;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 24px;
  background: #1f2b47;
  color: #fff;
}
.left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.back {
  cursor: pointer;
  font-size: 18px;
}
.title {
  font-weight: 600;
}
.sub {
  font-size: 13px;
  opacity: 0.75;
}
.small {
  font-size: 12px;
  color: #666;
}
.stock {
  color: #ff5c39;
  font-weight: 600;
}
.empty {
  padding: 40px 0;
}
.area-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 10px 0;
  font-weight: 600;
  font-size: 14px;
}
.area-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  padding: 10px;
  background: #fafafa;
  border-radius: 8px;
}
.unit {
  font-size: 12px;
  color: #999;
}
.summary {
  margin-top: 12px;
  padding: 10px 12px;
  background: #f0f7ff;
  border-radius: 8px;
  font-size: 13px;
  color: #40628a;
}
</style>
