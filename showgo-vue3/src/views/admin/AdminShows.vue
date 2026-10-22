<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Search } from '@element-plus/icons-vue'
import {
  adminDeleteShow,
  adminLogout,
  adminSaveShow,
  adminShowStatus,
  adminShows,
  adminVenues,
  clearAdminToken
} from '@/api/admin'

const router = useRouter()
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const venues = ref([])
const query = ref({ keyword: '', status: null, page: 1, pageSize: 10 })

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = ref(defaultForm())

const CATEGORIES = [
  { value: 1, label: '演唱会' },
  { value: 2, label: '话剧歌剧' },
  { value: 3, label: '音乐节' },
  { value: 4, label: '体育赛事' },
  { value: 5, label: '脱口秀' },
  { value: 6, label: '儿童亲子' }
]

const STATUS = {
  1: { label: '上架', type: 'success' },
  2: { label: '下架', type: 'info' },
  3: { label: '已结束', type: 'warning' }
}

const rules = {
  name: [{ required: true, message: '请输入演出名称', trigger: 'blur' }],
  venueId: [{ required: true, message: '请选择场馆', trigger: 'change' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }]
}

const venueName = computed(() => {
  const map = new Map(venues.value.map((v) => [v.id, `${v.city} · ${v.name}`]))
  return (id) => map.get(id) || id
})

function defaultForm() {
  return {
    id: null,
    name: '',
    venueId: null,
    category: 2,
    artist: '',
    poster: '',
    description: '',
    durationMinutes: 120,
    minPrice: 180,
    status: 1
  }
}

const load = async () => {
  loading.value = true
  try {
    const res = await adminShows(query.value)
    rows.value = res.data || []
    total.value = Number(res.total || 0)
  } finally {
    loading.value = false
  }
}

const search = () => {
  query.value.page = 1
  load()
}

const openCreate = () => {
  form.value = defaultForm()
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

const openEdit = (row) => {
  form.value = {
    id: row.id,
    name: row.name,
    venueId: row.venueId,
    category: row.category,
    artist: row.artist,
    poster: row.poster,
    description: row.description,
    durationMinutes: row.durationMinutes,
    minPrice: (Number(row.minPrice) || 0) / 100,
    status: row.status
  }
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

const save = async () => {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  if (!(Number(form.value.minPrice) >= 0)) {
    ElMessage.warning('最低票价不能为负')
    return
  }
  saving.value = true
  try {
    await adminSaveShow({
      ...form.value,
      minPrice: Math.round(Number(form.value.minPrice) * 100)
    })
    ElMessage.success(form.value.id ? '已保存' : '已创建')
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

const toggleStatus = async (row) => {
  const next = row.status === 1 ? 2 : 1
  await adminShowStatus(row.id, next)
  ElMessage.success(next === 1 ? '已上架' : '已下架')
  load()
}

const remove = async (row) => {
  await ElMessageBox.confirm(`确定删除「${row.name}」吗？有场次的演出不能删除。`, '删除演出', {
    type: 'warning'
  })
  await adminDeleteShow(row.id)
  ElMessage.success('已删除')
  load()
}

const goSessions = (row) => router.push(`/admin/shows/${row.id}/sessions`)

const logout = async () => {
  try {
    await adminLogout()
  } catch {
    // token 已失效也允许退出
  }
  clearAdminToken()
  router.replace('/admin/login')
}

onMounted(async () => {
  const venueRes = await adminVenues()
  venues.value = venueRes.data || []
  await load()
})
</script>

<template>
  <div class="page">
    <header class="topbar">
      <div class="title">星幕票务 · 演出管理</div>
      <div class="actions">
        <el-button text @click="router.push('/index')">用户端</el-button>
        <el-button text @click="logout">退出登录</el-button>
      </div>
    </header>

    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索演出名 / 艺人"
        clearable
        :prefix-icon="Search"
        style="width: 240px"
        @keyup.enter="search"
      />
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 130px">
        <el-option v-for="(item, key) in STATUS" :key="key" :label="item.label" :value="Number(key)" />
      </el-select>
      <el-button @click="search">查询</el-button>
      <div class="spacer" />
      <el-button type="primary" :icon="Plus" @click="openCreate">新建演出</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" stripe style="width: 100%">
      <el-table-column prop="id" label="ID" width="190" />
      <el-table-column prop="name" label="演出名称" min-width="220" show-overflow-tooltip />
      <el-table-column label="场馆" min-width="190">
        <template #default="{ row }">{{ venueName(row.venueId) }}</template>
      </el-table-column>
      <el-table-column label="分类" width="100">
        <template #default="{ row }">
          {{ CATEGORIES.find((c) => c.value === row.category)?.label || row.category }}
        </template>
      </el-table-column>
      <el-table-column label="最低价" width="100">
        <template #default="{ row }">¥{{ (Number(row.minPrice) / 100).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="STATUS[row.status]?.type" size="small">{{ STATUS[row.status]?.label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="300" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="goSessions(row)">场次</el-button>
          <el-button size="small" link @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link @click="toggleStatus(row)">
            {{ row.status === 1 ? '下架' : '上架' }}
          </el-button>
          <el-button size="small" type="danger" link @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @current-change="load"
        @size-change="search"
      />
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="form.id ? '编辑演出' : '新建演出'"
      width="620px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="演出名称" prop="name">
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="场馆" prop="venueId">
          <el-select v-model="form.venueId" placeholder="请选择场馆" style="width: 100%">
            <el-option
              v-for="venue in venues"
              :key="venue.id"
              :label="`${venue.city} · ${venue.name}`"
              :value="venue.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="分类" prop="category">
          <el-select v-model="form.category" style="width: 100%">
            <el-option v-for="item in CATEGORIES" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="艺人/剧团">
          <el-input v-model="form.artist" maxlength="128" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="2048" />
        </el-form-item>
        <el-form-item label="时长(分钟)">
          <el-input-number v-model="form.durationMinutes" :min="1" :max="1440" />
        </el-form-item>
        <el-form-item label="最低价(元)">
          <el-input-number v-model="form.minPrice" :min="0" :precision="2" :step="10" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">上架</el-radio>
            <el-radio :value="2">下架</el-radio>
            <el-radio :value="3">已结束</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
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
.title {
  font-size: 16px;
  font-weight: 600;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 24px;
}
.spacer {
  flex: 1;
}
.pagination {
  display: flex;
  justify-content: flex-end;
  padding: 16px 24px;
}
</style>
