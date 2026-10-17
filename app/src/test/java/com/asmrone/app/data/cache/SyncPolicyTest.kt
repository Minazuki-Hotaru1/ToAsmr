package com.asmrone.app.data.cache

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 同步闸门。
 *
 * ## 目标是**减少服务器压力**，规则就三条
 *
 * ```
 * 每天第一次自动对齐     → 每份数据各一次（「标记」「列表」是两个独立接口）
 * 用户主动刷新           → 不受限制
 * 换账号 / 换服务器      → 不受限制（否则本地为空会一整天空白）
 * ```
 *
 * ## 为什么需要持久化
 *
 * "今天已经匹配过"必须**跨进程存活**：否则杀掉 App 重开就又匹配一次，
 * 达不到"当天之后不再自动匹配"的效果。所以标记落盘
 * （见 `AppPrefs.autoSyncDay`），而且**按数据类别分开存**
 * —— 早先只有一个全局日期，导致「标记」与「列表」共用一个额度：
 * 谁先进谁用掉，另一个当天永远拿不到云端更新。
 */
class SyncPolicyTest {

    /** 内存里的"已对齐日期"，替代 SharedPreferences */
    private val days = mutableMapOf<String, String>()

    private val day = 24L * 60 * 60 * 1000

    /** 与 SyncGate 内部一致的类别派生规则（维度相关，见 SyncGate.domainOf） */
    private fun domainOf(key: String): String {
        val parts = key.split('|')
        val head = if (parts.isNotEmpty() && parts.last().startsWith("page")) parts.dropLast(1) else parts
        val take = when (head.firstOrNull()) { "collection", "playlists" -> 3; else -> 4 }
        return head.take(take).joinToString("|")
    }

    @Before
    fun setUp() {
        days.clear()
        SyncGate.installDayStoreForTest(
            read = { days[it].orEmpty() },
            write = { domain, d -> days[domain] = d }
        )
    }

    @After
    fun tearDown() {
        SyncGate.uninstallDayStoreForTest()
    }

    // ═══════════════ ① 每天只自动一次 ═══════════════

    @Test
    fun `★ 同一天内只自动对齐一次`() = runBlocking {
        var count = 0
        val key = "collection|host|user"

        val first = SyncGate.sync(key, SyncTrigger.DAILY_AUTO) { count++ }
        val second = SyncGate.sync(key, SyncTrigger.DAILY_AUTO) { count++ }
        val third = SyncGate.sync(key, SyncTrigger.DAILY_AUTO) { count++ }

        assertTrue("今天第一次应当放行", first)
        assertFalse("同一天第二次必须被挡下", second)
        assertFalse(third)
        assertEquals("实际只发一次请求", 1, count)
    }

    @Test
    fun `★ 跨过一天后自动机会恢复`() = runBlocking {
        var count = 0
        val key = "collection|host|user"

        assertTrue(SyncGate.sync(key, SyncTrigger.DAILY_AUTO) { count++ })
        assertFalse(SyncGate.sync(key, SyncTrigger.DAILY_AUTO) { count++ })

        // 把标记改成"昨天"，模拟跨天
        days[domainOf(key)] = SyncGate.dayStamp(System.currentTimeMillis() - day)
        assertTrue("新的一天应当重新放行", SyncGate.sync(key, SyncTrigger.DAILY_AUTO) { count++ })
        assertEquals(2, count)
    }

    @Test
    fun `★ 标记是落盘的（跨进程重开也生效）`() {
        // 上一版只记在内存里，杀进程重开就会再对齐一次 —— 达不到"减少压力"的目的
        runBlocking { SyncGate.sync("collection|host|user", SyncTrigger.DAILY_AUTO) { } }

        val stored = days[domainOf("collection|host|user")]
        assertEquals("应当把今天的日期写进存储", SyncGate.dayStamp(), stored)
        assertTrue("日期格式应当是 yyyy-MM-dd", Regex("""^\d{4}-\d{2}-\d{2}$""").matches(stored!!))
    }

    // ═══════════════ ② 两份数据各有机会 ═══════════════

    @Test
    fun `★ 「标记」与「列表」各自拥有每日机会`() = runBlocking {
        // 共用一个额度是早期版本的 bug：谁先进谁用掉，
        // 另一个当天永远拿不到云端更新
        var collection = 0
        var playlists = 0

        SyncGate.sync("collection|host|user", SyncTrigger.DAILY_AUTO) { collection++ }
        SyncGate.sync("playlists|host|user", SyncTrigger.DAILY_AUTO) { playlists++ }

        assertEquals("「标记」应当被放行", 1, collection)
        assertEquals("「列表」也应当被放行", 1, playlists)
    }

    @Test
    fun `★ 同一类别的不同筛选与页码共享一次机会`() = runBlocking {
        // 「全部」与「想听」读的是同一份本地 JSON（各筛选本地派生），
        // 不该各占一次机会 —— 否则切筛选就会打接口
        var count = 0
        SyncGate.sync("collection|host|user|ALL", SyncTrigger.DAILY_AUTO) { count++ }
        SyncGate.sync("collection|host|user|WANT_TO_LISTEN", SyncTrigger.DAILY_AUTO) { count++ }
        SyncGate.sync("collection|host|user|page2", SyncTrigger.DAILY_AUTO) { count++ }
        assertEquals("同一类别的变体应共享机会", 1, count)
    }

    @Test
    fun `★ 不同账号各有自己的每日机会`() = runBlocking {
        var userA = 0
        var userB = 0
        SyncGate.sync("collection|host|alice", SyncTrigger.DAILY_AUTO) { userA++ }
        SyncGate.sync("collection|host|bob", SyncTrigger.DAILY_AUTO) { userB++ }

        assertEquals(1, userA)
        assertEquals("新账号必须也能自动对齐一次", 1, userB)
    }

    @Test
    fun `★ 不同收藏夹各有自己的每日机会`() = runBlocking {
        // playlistWorks 的类别含收藏夹 id（前三段），所以两个收藏夹互不占用
        var a = 0
        var b = 0
        SyncGate.sync("playlistWorks|host|user|pl-A", SyncTrigger.DAILY_AUTO) { a++ }
        SyncGate.sync("playlistWorks|host|user|pl-B", SyncTrigger.DAILY_AUTO) { b++ }
        assertEquals(1, a)
        assertEquals(1, b)
    }

    @Test
    fun `★ 不同服务器各有自己的每日机会`() = runBlocking {
        var h1 = 0
        var h2 = 0
        SyncGate.sync("collection|https://a.com|user", SyncTrigger.DAILY_AUTO) { h1++ }
        SyncGate.sync("collection|https://b.com|user", SyncTrigger.DAILY_AUTO) { h2++ }
        assertEquals(1, h1)
        assertEquals(1, h2)
    }

    // ═══════════════ ③ 用户主动刷新 ═══════════════

    @Test
    fun `★ 用户刷新每次都放行`() = runBlocking {
        var count = 0
        repeat(3) {
            assertTrue(SyncGate.sync("collection|host|user", SyncTrigger.USER_REFRESH) { count++ })
        }
        assertEquals("用户想要最新的就给他", 3, count)
    }

    @Test
    fun `★ 当天的自动机会用掉后，用户刷新仍然有效`() = runBlocking {
        var count = 0
        SyncGate.sync("collection|host|user", SyncTrigger.DAILY_AUTO) { count++ }
        assertFalse(SyncGate.sync("collection|host|user", SyncTrigger.DAILY_AUTO) { count++ })
        assertTrue(SyncGate.sync("collection|host|user", SyncTrigger.USER_REFRESH) { count++ })
        assertEquals(2, count)
    }

    // ═══════════════ ④ 账号变化 ═══════════════

    @Test
    fun `★ 账号变化不受每日机会限制`() = runBlocking {
        var count = 0
        SyncGate.sync("collection|host|user", SyncTrigger.DAILY_AUTO) { count++ }
        assertTrue(
            "换了账号必须能立刻核对（本地是空的，等到明天就一整天空白）",
            SyncGate.sync("collection|host|user", SyncTrigger.ACCOUNT_CHANGED) { count++ }
        )
        assertEquals(2, count)
    }

    // ═══════════════ ⑤ 失败也算"对齐过" ═══════════════

    @Test
    fun `★ 自动路径失败后当天不再自动重试`() = runBlocking {
        // 否则服务端持续故障时每进一次页面就打一次接口 —— 那正是要避免的压力源
        var attempts = 0
        runCatching {
            SyncGate.sync("collection|host|user", SyncTrigger.DAILY_AUTO) {
                attempts++
                throw IllegalStateException("network down")
            }
        }
        assertFalse(
            "失败后当天的机会同样算用掉",
            SyncGate.sync("collection|host|user", SyncTrigger.DAILY_AUTO) { attempts++ }
        )
        assertEquals("只尝试过一次", 1, attempts)

        // 但用户主动刷新还能救
        assertTrue(SyncGate.sync("collection|host|user", SyncTrigger.USER_REFRESH) { attempts++ })
        assertEquals(2, attempts)
    }

    @Test
    fun `★ 请求在途期间再进页面不会重复触发`() = runBlocking {
        var started = 0
        val job = async {
            SyncGate.sync("collection|host|user", SyncTrigger.DAILY_AUTO) {
                started++
                delay(100)
            }
        }
        delay(10)
        assertFalse(
            "在途期间再进页面必须被挡下",
            SyncGate.sync("collection|host|user", SyncTrigger.DAILY_AUTO) { started++ }
        )
        job.await()
        assertEquals(1, started)
    }

    @Test
    fun `异常后必须清掉在途登记`() = runBlocking {
        // 不清的话下一次同类请求会被永久挡住（表现为"刷新按钮再也不工作"）
        runCatching {
            SyncGate.sync("failing", SyncTrigger.USER_REFRESH) {
                throw IllegalStateException("boom")
            }
        }
        assertEquals("异常后不该留下在途登记", 0, SyncGate.inFlightCount())

        var count = 0
        assertTrue(SyncGate.sync("failing", SyncTrigger.USER_REFRESH) { count++ })
        assertEquals(1, count)
    }

    // ═══════════════ ⑥ 单飞 ═══════════════

    @Test
    fun `同一个 key 的并发请求只发一次`() = runBlocking {
        var count = 0
        val a = async {
            SyncGate.sync("same", SyncTrigger.USER_REFRESH) {
                count++
                delay(50)
            }
        }
        delay(10)
        val b = async { SyncGate.sync("same", SyncTrigger.USER_REFRESH) { count++ } }
        a.await()
        b.await()
        assertEquals("单飞应当让并发的第二次等第一次的结果", 1, count)
    }

    @Test
    fun `不同 key 互不阻塞`() = runBlocking {
        var a = 0
        var b = 0
        val ja = async { SyncGate.sync("key-A", SyncTrigger.USER_REFRESH) { a++ } }
        val jb = async { SyncGate.sync("key-B", SyncTrigger.USER_REFRESH) { b++ } }
        ja.await()
        jb.await()
        assertEquals(1, a)
        assertEquals(1, b)
    }

    @Test
    fun `连点刷新不会放大成多次请求`() = runBlocking {
        var count = 0
        val jobs = (1..5).map {
            async {
                SyncGate.sync("spam", SyncTrigger.USER_REFRESH) {
                    count++
                    delay(40)
                }
            }
        }
        jobs.forEach { it.await() }
        assertEquals("狂点最多只产生一次请求", 1, count)
    }

    // ═══════════════ ⑦ reset 不清每日标记 ═══════════════

    @Test
    fun `★ reset 只清在途登记，不清每日标记`() = runBlocking {
        SyncGate.sync("collection|host|alice", SyncTrigger.DAILY_AUTO) { }
        SyncGate.reset()

        assertFalse(
            "切账号不该把 alice 当天的机会还回来（那等于多打一次请求）",
            SyncGate.sync("collection|host|alice", SyncTrigger.DAILY_AUTO) { }
        )
        // 而 bob 本来就没用过，天然拥有机会
        assertTrue(SyncGate.sync("collection|host|bob", SyncTrigger.DAILY_AUTO) { })
    }

    // ═══════════════ ⑧ 触发理由是封闭的 ═══════════════

    @Test
    fun `★ 触发理由只有封闭的三个`() {
        assertEquals(
            "只允许三个理由：每日自动 / 用户刷新 / 账号变化",
            listOf(
                SyncTrigger.DAILY_AUTO,
                SyncTrigger.USER_REFRESH,
                SyncTrigger.ACCOUNT_CHANGED
            ),
            SyncTrigger.entries.toList()
        )
    }

    @Test
    fun `日期串格式是本地自然日`() {
        val s = SyncGate.dayStamp()
        assertTrue("应当形如 yyyy-MM-dd，实际 $s", Regex("""^\d{4}-\d{2}-\d{2}$""").matches(s))
        // 同一毫秒必然得到同一串（判定依赖这一点）
        assertEquals(s, SyncGate.dayStamp(System.currentTimeMillis()))
    }
}
