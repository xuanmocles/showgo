<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import FootBar from '@/components/FootBar.vue'
import { queryShows, queryCategories, queryCities, queryHotSessions } from '@/api/show'

const router = useRouter()
const shows = ref([])
const categories = ref([])
const cities = ref([])
const hotSessions = ref([])
const loading = ref(false)
const query = ref({ category: null, city: null, keyword: '', page: 1, pageSize: 10 })

const categoryName = (code) => categories.value.find((c) => c.code === code)?.name || '演出'
const priceText = (fen) => (fen ? `¥${(fen / 100).toFixed(0)} 起` : '待定')

const load = async () => {
  loading.value = true
  try {
    const res = await queryShows(query.value)
    shows.value = res.data || []
  } finally {
    loading.value = false
  }
}

const loadBase = async () => {
  const [categoryRes, cityRes, hotRes] = await Promise.all([
    queryCategories(),
    queryCities(),
    queryHotSessions(6)
  ])
  categories.value = categoryRes.data || []
  cities.value = cityRes.data || []
  hotSessions.value = hotRes.data || []
}

const pickCategory = (code) => {
  query.value.category = query.value.category === code ? null : code
  query.value.page = 1
  load()
}

const pickCity = (city) => {
  query.value.city = query.value.city === city ? null : city
  query.value.page = 1
  load()
}

const toDetail = (id) => router.push('/show/' + id)
const toSession = (session) => router.push(`/show/${session.showId}/seat/${session.id}`)

onMounted(async () => {
  await loadBase()
  await load()
})
</script>

<template>
  <div class="page">
    <header class="hero">
      <div class="brand">星幕票务</div>
      <div class="slogan">选到好座位，才算真的看了一场演出</div>
      <el-input
        v-model="query.keyword"
        class="search"
        placeholder="搜索演出 / 艺人"
        :prefix-icon="Search"
        @keyup.enter="load"
      />
    </header>

    <section class="block">
      <div class="block-title">分类</div>
      <div class="chips">
        <span
          v-for="item in categories"
          :key="item.code"
          class="chip"
          :class="{ on: query.category === item.code }"
          @click="pickCategory(item.code)"
          >{{ item.name }}</span
        >
      </div>
    </section>

    <section class="block">
      <div class="block-title">城市</div>
      <div class="chips">
        <span
          v-for="city in cities"
          :key="city"
          class="chip"
          :class="{ on: query.city === city }"
          @click="pickCity(city)"
          >{{ city }}</span
        >
      </div>
    </section>

    <section v-if="hotSessions.length" class="block">
      <div class="block-title">正在热销 · 实时榜</div>
      <div class="hot-list">
        <div v-for="session in hotSessions" :key="session.id" class="hot-item" @click="toSession(session)">
          <div class="hot-name">{{ session.showName || session.sessionName }}</div>
          <div class="hot-meta">{{ session.venueName }} · 余票 {{ session.seatAvailable }}</div>
        </div>
      </div>
    </section>

    <section class="block">
      <div class="block-title">全部演出</div>
      <div v-loading="loading" class="show-list">
        <div v-for="show in shows" :key="show.id" class="show-card" @click="toDetail(show.id)">
          <div class="poster">{{ show.name.slice(0, 1) }}</div>
          <div class="info">
            <div class="name">{{ show.name }}</div>
            <div class="meta">
              <el-tag size="small" effect="plain">{{ categoryName(show.category) }}</el-tag>
              <span class="venue">{{ show.city }} · {{ show.venueName }}</span>
            </div>
            <div class="price">{{ priceText(show.minPrice) }}</div>
          </div>
        </div>
        <el-empty v-if="!loading && !shows.length" description="没有找到相关演出" />
      </div>
    </section>

    <FootBar />
    <div class="foot-space" />
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f7f8fa;
}
.hero {
  padding: 28px 20px 26px;
  background: linear-gradient(135deg, #ff7a45, #ff4d4f);
  color: #fff;
}
.brand {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 2px;
}
.slogan {
  margin-top: 6px;
  font-size: 13px;
  opacity: 0.9;
}
.search {
  margin-top: 18px;
}
.block {
  padding: 16px 20px 0;
}
.block-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 10px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  padding: 5px 14px;
  border-radius: 16px;
  background: #fff;
  font-size: 13px;
  color: #555;
  cursor: pointer;
  border: 1px solid #eee;
}
.chip.on {
  background: #fff1eb;
  color: #ff5c39;
  border-color: #ffb199;
}
.hot-list {
  display: flex;
  gap: 10px;
  overflow-x: auto;
  padding-bottom: 6px;
}
.hot-item {
  min-width: 210px;
  padding: 12px;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
}
.hot-name {
  font-size: 14px;
  font-weight: 600;
}
.hot-meta {
  margin-top: 6px;
  font-size: 12px;
  color: #999;
}
.show-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.show-card {
  display: flex;
  gap: 12px;
  padding: 12px;
  background: #fff;
  border-radius: 12px;
  cursor: pointer;
}
.poster {
  width: 72px;
  height: 96px;
  border-radius: 8px;
  background: linear-gradient(160deg, #4b6cb7, #182848);
  color: #fff;
  font-size: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.info {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}
.name {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
}
.meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #999;
}
.price {
  color: #ff5c39;
  font-weight: 600;
}
.foot-space {
  height: 64px;
}
</style>
