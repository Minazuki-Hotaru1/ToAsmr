package com.asmrone.app.ui.screen.detail

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import com.asmrone.app.ui.component.ACTION_TOAST_DURATION_MS
import com.asmrone.app.ui.component.rememberLocalStorageRequester
import com.asmrone.app.ui.component.rememberToastState
import com.asmrone.app.ui.component.ToastHost
import kotlinx.coroutines.launch
import com.asmrone.app.ui.component.viewer.VideoPlayerDialog
import com.asmrone.app.ui.component.viewer.TextViewerState
import com.asmrone.app.ui.component.viewer.ImageViewerDialog
import com.asmrone.app.ui.component.viewer.ExternalFileOpener
import com.asmrone.app.data.model.TrackKind
import com.asmrone.app.data.model.OpenableFile
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asmrone.app.R
import com.asmrone.app.data.auth.AuthManager
import com.asmrone.app.data.model.CollectionStatus
import com.asmrone.app.data.model.LanguageEdition
import com.asmrone.app.data.model.Track
import com.asmrone.app.data.model.sameFolderQueue
import com.asmrone.app.data.model.Work
import com.asmrone.app.data.repo.AsmrRepository
import com.asmrone.app.data.sync.PendingSync
import com.asmrone.app.player.AudioController
import com.asmrone.app.ui.component.CoverImage
import com.asmrone.app.ui.component.FileTreeRow
import com.asmrone.app.ui.component.GlassTopBar
import com.asmrone.app.ui.component.rememberStatusBarTopDp
import com.asmrone.app.ui.component.SmallTagChip
import com.asmrone.app.ui.component.TagTone
import com.asmrone.app.ui.component.TagWallChip
import com.asmrone.app.ui.component.flattenVisibleTree
import com.asmrone.app.ui.component.trackTreeSummary
import com.asmrone.app.ui.error.MarkFailureCard
import com.asmrone.app.ui.screen.library.CollectionStatusDialog
import com.asmrone.app.ui.screen.library.PlaylistPickerDialog
import com.asmrone.app.ui.screen.library.PlaylistPickerViewModel
import com.asmrone.app.ui.screen.download.DownloadPickerSheet
import com.asmrone.app.ui.theme.ErrorColor
import com.asmrone.app.ui.component.LiquidCapsule
import com.asmrone.app.ui.component.backdropButton
import com.asmrone.app.ui.theme.Liquid
import com.asmrone.app.ui.theme.RatingStar
import com.asmrone.app.ui.theme.Sizes
import com.asmrone.app.ui.theme.WorkTitleStyle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 作品详情页（单页统一信息）。
 *
 * 设计依据（实测原站与 KikoFlu）：
 *  - 原站音声作品**没有评论区**，因此这里不做「详情 / 评论」分区，
 *    标题、信息表、标签、**文件树**、简介全部排在同一个滚动页面里；
 *  - 原站作品是**压缩包目录树**（`mp3/`、`wav/`、`03_omake/01_wav（音声のみ）/…`），
 *    因此文件区忠实还原文件夹层级，可逐层展开/折叠；
 *  - 同一个音声通常同时有 mp3 与 wav 两套目录，所以文件区顶部提供**音质分支切换**，
 *    播放器只会收到选中的那一条分支，不会把两个版本混播。
 */
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    workId: Int,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    /** 打开另一个作品（点语言版本链接时用，跳到该语言版本的详情页） */
    onOpenWork: (Int) -> Unit,
    /**
     * 点标签 → **直达**该标签的作品页。
     *
     * 带上名字是因为那个页面要**自包含**（顶栏得显示"按什么筛的"），
     * 而名字可能是译名、未必能从 id 反查出来。
     */
    onOpenTag: (id: Int, name: String) -> Unit = { _, _ -> },
    /** 点声优 → 直达该声优的作品页（id 是 UUID） */
    onOpenVoiceActor: (id: String, name: String) -> Unit = { _, _ -> },
    /** 点社团 → 直达该社团的作品页 */
    onOpenCircle: (id: Int, name: String) -> Unit = { _, _ -> }
) {
    LaunchedEffect(workId) { viewModel.load(workId) }

    val state by viewModel.state.collectAsState()
    val backdrop = rememberLayerBackdrop()
    val playerState by AudioController.state.collectAsState()
    val listState = rememberLazyListState()

    val density = LocalDensity.current
    val statusTopDp = rememberStatusBarTopDp()
    var barHeight by remember {
        mutableIntStateOf(with(density) { (statusTopDp + Liquid.TOP_BAR_HEIGHT).roundToPx() })
    }

    // 只在展开状态或树本身变化时重新拍平（未展开的子树完全不遍历）
    val treeRows = remember(state.tracks, state.expanded) {
        flattenVisibleTree(state.tracks, state.expanded)
    }

    val work = state.work
    val playbackList = state.playbackList

    // 「加入收藏夹」弹窗：收藏本身不再弹窗（直接写进默认收藏夹），
    // 这里只在用户点提示里的「修改」时才打开
    var pickerOpen by remember { mutableStateOf(false) }
    // 「下载」选择弹窗（下半屏文件树 + 勾选）
    var downloadOpen by remember { mutableStateOf(false) }
    // 「标记」（/api/review 的收听状态）选择弹窗
    var collectionDialogOpen by remember { mutableStateOf(false) }
    // 这次弹窗是不是"快捷评分"打开的：那种情况下用户点「取消」等于撤销刚才那一下
    // （见 [DetailViewModel.revertQuickRate]）；已经标记过的作品打开时不做任何回滚。
    var collectionDialogFromQuick by remember { mutableStateOf(false) }
    // 收藏弹窗同理：从"快捷收藏"的提示里进来的，点「取消」要撤销那次收藏
    var pickerFromQuick by remember { mutableStateOf(false) }
    val pickerViewModel: PlaylistPickerViewModel = viewModel()
    val pickerState by pickerViewModel.state.collectAsState()
    val pickerResult by pickerViewModel.result.collectAsState()
    // 直接收藏（不弹窗）的状态与一次性提示
    val quickCollect by pickerViewModel.quick.collectAsState()
    val quickEvent by pickerViewModel.quickEvent.collectAsState()
    val authState by AuthManager.state.collectAsState()
    // 收藏结果的提示 —— 用项目共享的 Toast 组件（comicApp 那套样式），
    // 不再在这里手写一份（手写的那份没有淡出、也没有与其它页面一致的位置）
    val toastState = rememberToastState()

    // ── 文件树里非音频内容的打开状态 ──────────────────────
    // 图片 / 视频走内置弹窗查看器（不占导航栈 —— 它们是"临时看一眼"）；
    // 文本交给 MainActivity 的覆盖层页面（`TextViewerState`），PDF 交外部 App。
    var imageFile by remember { mutableStateOf<OpenableFile?>(null) }
    var videoFile by remember { mutableStateOf<OpenableFile?>(null) }
    // PDF 正在下载（体积最大实测 17MB，要有进度，不能"点了没反应"）
    var downloadingPdf by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 长按复制 ─────────────────────────────────────────
    // 写剪贴板、震动、提示三件事绑在**同一个**动作里，调用点只负责给文本与提示语。
    // 拆开写很容易漏掉一半：复制成功却没震动、或者震了却没写进剪贴板。
    // 提示语由调用点给（"已复制RJ号" / "已复制标签"），因为用户需要知道复制到的是什么，
    // 统一写"已复制"在连续复制两种内容时分不清。
    // 用新的 LocalClipboard（Clipboard.setClipEntry 是挂起函数，所以要 scope）；
    // 旧的 LocalClipboardManager 已被标记废弃，本工程要求零警告。
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val copyText: (String, String) -> Unit = { text, message ->
        if (text.isNotBlank()) {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("AsmrOne", text)))
            }
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            toastState.show(message)
        }
    }

    // 「存到本地文件夹」需要存储权限。下载按钮点下去发现没权限时，ViewModel 会置
    // downloadNeedsPermission，这里负责真的把用户带到授权页 —— 以前这条路是断的：
    // 下载器把任务标成失败，而失败的任务在下载页被 isActive 过滤掉，用户什么都看不到。
    val requestLocalAccess = rememberLocalStorageRequester(
        onResult = { granted ->
            if (granted) viewModel.onLocalPermissionGranted()
            else viewModel.onLocalPermissionDenied()
        },
        onFailure = { viewModel.onLocalPermissionDenied("打不开系统权限页面，请到「设置 → 应用 → 权限」里手动开启") }
    )
    LaunchedEffect(state.downloadNeedsPermission) {
        if (state.downloadNeedsPermission) requestLocalAccess()
    }

    LaunchedEffect(pickerResult) {
        // 先取到局部变量再判空：pickerResult 是委托属性（by collectAsState），
        // 直接 show(pickerResult) 无法智能转换
        val message = pickerResult
        if (message != null) {
            // 提交完就关弹窗：结果提示挂在页面上（含中途失败的 error，此时弹窗仍在）。
            // 空串表示"没有任何改动"，关掉就行，不用再弹一条提示。
            pickerOpen = false
            if (message.isNotEmpty()) toastState.show(message)
            pickerViewModel.clearResult()
        }
    }

    // 打开详情页时先问本地：这部作品在不在默认收藏夹里（只读缓存，不发请求）
    LaunchedEffect(work?.id) {
        work?.let { pickerViewModel.prepareQuick(it.id) }
    }

    // 直接收藏的提示：带「修改」按钮，点了才打开收藏夹选择弹窗
    LaunchedEffect(quickEvent) {
        val event = quickEvent ?: return@LaunchedEffect
        if (event.canModify) {
            toastState.show(event.message, actionLabel = "修改") {
                // 用户要换收藏夹：先别发刚才那次，等他选完（见 PendingSync）
                PendingSync.pause(QUICK_COLLECT_KEY)
                pickerFromQuick = true
                pickerOpen = true
                // 把"还没发出去"的那个收藏夹带进弹窗：它在基线之外，
                // 所以用户即使什么都不改、直接点「完成」，这笔收藏也会被补发。
                work?.let {
                    pickerViewModel.open(
                        it,
                        unsyncedPlaylistId = pickerViewModel.pendingQuickCollect()?.playlistId
                    )
                }
            }
        } else {
            toastState.show(event.message)
        }
        pickerViewModel.clearQuickEvent()
    }

    // 来自 ViewModel 的一次性提示（如"没有能打开 PDF 的应用"）
    //
    // 用 toastSeq 而不是文案本身做 key：同一条提示连续出现两次时，
    // 文案没变，只靠它判断不出"又发生了一次"。
    LaunchedEffect(state.toastSeq) {
        state.toastMessage?.let {
            toastState.show(it)
            viewModel.consumeToast()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (work == null) {
            // 冷启动、且列表页缓存里没有这部作品时的兜底
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.metaError != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "加载失败",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = state.metaError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { viewModel.refresh() }) { Text("重试") }
                    }
                } else {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .layerBackdrop(backdrop)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = with(density) { barHeight.toDp() } + Liquid.CONTENT_TOP_GAP,
                        // 悬浮「开始播放」胶囊已移除；底部只在迷你播放器出现时才需要让位
                        bottom = if (playerState.isActive) Liquid.CONTENT_BOTTOM_INSET else 32.dp
                    )
                ) {
                    // ① 头图
                    item(key = "hero") { HeroCover(work) }

                    // ② 标题 + 角标（动作在顶栏，见 GlassTopBar 的 actions）
                    item(key = "title") {
                        TitleBlock(work = work, onOpenWork = onOpenWork, onCopy = { rj -> copyText(rj, "已复制RJ号") })
                    }

                    // ②b 标记失败的**详细**报错（分类原因 + 建议 + 原始详情 + 重试）
                    state.collectionFailure?.let { failure ->
                        item(key = "collection_failure") {
                            MarkFailureCard(
                                failure = failure,
                                onRetry = { viewModel.retryCollectionWrite() },
                                onDismiss = { viewModel.dismissCollectionFailure() }
                            )
                        }
                    }

                    // ③ 统计（社团 / 声优 / 发行 / 时长 / 销量 / 评分）
                    item(key = "stats") {
                        StatsBlock(
                            work = work,
                            onOpenVoiceActor = onOpenVoiceActor,
                            onOpenCircle = onOpenCircle
                        )
                    }

                    // ④ 标签
                    if (work.tags.isNotEmpty()) {
                        item(key = "tags") { TagsBlock(work, onTagClick = onOpenTag, onCopyTag = { tag -> copyText(tag, "已复制标签") }) }
                    }

                    // ⑤ 文件树（文件夹嵌套）
                    item(key = "tree_header") {
                        TrackTreeHeader(
                            state = state,
                            onRetry = { viewModel.refresh() }
                        )
                    }

                    items(treeRows, key = { it.key }) { row ->
                        val node = row.node
                        val isPlayingNode = playerState.workId == work.id &&
                            playerState.currentTrack?.path == node.path
                        FileTreeRow(
                            row = row,
                            expanded = node.path in state.expanded,
                            playing = isPlayingNode && playerState.isPlaying,
                            // 展开/收起的动画：被插入/移除的行淡入淡出，
                            // 其**下方的行平滑地向下/向上移动**（靠 LazyColumn 的 placement 动画）。
                            modifier = Modifier.animateItem(),
                            onClick = {
                                if (node.children.isNotEmpty()) {
                                    viewModel.toggleFolder(node.path)
                                    return@FileTreeRow
                                }

                                // ⚠️ 判定顺序要紧：`.mp4` 的 type 是 `audio` 且
                                // isPlayable 为真，但它是**视频**（实测单个到 1.8GB）。
                                // 若先判 isPlayable，它会进音频播放器 —— 表现为黑屏或直接失败。
                                // 所以先问 TrackKind（它把扩展名看得比 type 重）。
                                val kind = TrackKind.of(node)
                                when (kind) {
                                    TrackKind.AUDIO -> {
                                        if (node.isPlayable) {
                                            // 队列收窄到"这一轨所在的文件夹"（跨目录歌词
                                            // 匹配不依赖它，见 sameFolderQueue 的说明）
                                            val queue = playbackList.sameFolderQueue(node.path)
                                            val index = queue.indexOfFirst { it.path == node.path }
                                            if (index >= 0) {
                                                AudioController.playWork(
                                                    work = work,
                                                    coverUrl = work.coverUrl(AsmrRepository.host),
                                                    tracks = queue,
                                                    startIndex = index
                                                )
                                                onOpenPlayer()
                                            }
                                        }
                                    }

                                    TrackKind.IMAGE, TrackKind.VIDEO,
                                    TrackKind.TEXT, TrackKind.SUBTITLE -> {
                                        val openable = viewModel.openableOf(node)
                                        if (openable == null) {
                                            viewModel.notify("这个文件没有可用的地址")
                                        } else {
                                            when (kind) {
                                                TrackKind.IMAGE -> imageFile = openable
                                                TrackKind.VIDEO -> videoFile = openable
                                                else -> TextViewerState.open(openable)
                                            }
                                        }
                                    }

                                    TrackKind.PDF -> {
                                        // 交外部阅读器。要先落到本地文件，
                                        // 再经 FileProvider 换成可授权的 content:// URI。
                                        val openable = viewModel.openableOf(node)
                                        if (openable == null) {
                                            viewModel.notify("这个文件没有可用的地址")
                                        } else if (!downloadingPdf) {
                                            downloadingPdf = true
                                            scope.launch {
                                                val file = viewModel.downloadForExternalOpen(context, openable)
                                                downloadingPdf = false
                                                if (file == null) {
                                                    viewModel.notify("文件下载失败，请检查网络")
                                                } else if (!ExternalFileOpener.open(context, file, openable.title)) {
                                                    // 静默失败最糟：用户会反复点
                                                    viewModel.notify("没有能打开 PDF 的应用")
                                                }
                                            }
                                        }
                                    }

                                    TrackKind.FOLDER -> Unit

                                    TrackKind.UNSUPPORTED -> viewModel.notify(
                                        "暂不支持打开「${node.title.substringAfterLast('.')}」这种文件"
                                    )
                                }
                            }
                        )
                    }

                    // ⑦ 简介（原站多数作品没有，自建服务端可能有）
                    if (!work.description.isNullOrBlank()) {
                        item(key = "description") { DescriptionBlock(work) }
                    }
                }
            }

            // 悬浮玻璃顶栏：返回 + 标题 + 右侧的「标记 / 收藏夹 / 下载」动作胶囊
            GlassTopBar(
                title = work.sourceId ?: work.title,
                subtitle = work.title.takeIf { work.sourceId != null },
                onBack = onBack,
                backdrop = backdrop,
                actions = {
                    DetailTopActions(
                        status = work.collectionStatus,
                        // 标记过 = 有状态，或者至少打过星（快捷标记只写星级、不写状态）
                        marked = work.collectionStatus != null || work.userRating != null,
                        // 收藏夹状态：只认本地缓存给出的答案；本地没有该收藏夹的内容时
                        // 是 null（未知），按未收藏画 —— 不为一个图标去联网。
                        collected = quickCollect.inside == true,
                        backdrop = backdrop,
                        // 下载入口原先在「文件」标题行右侧，是一段纯文字按钮；
                        // 详情页的动作统一收进顶栏胶囊后，这里只剩图标。
                        downloaded = state.isDownloaded,
                        downloadEnabled = state.pickerTree.isNotEmpty(),
                        onDownload = { downloadOpen = true },
                        onOpenCollection = {
                            if (authState.isLoggedIn) {
                                // 已标记过（有状态或打过星）→ 打开弹窗让他改分数/状态，
                                // 不再"再点一下就取消"：标记这东西一旦有了，误触清掉很难恢复。
                                if (work.collectionStatus != null || work.userRating != null) {
                                    collectionDialogFromQuick = false
                                    collectionDialogOpen = true
                                } else {
                                    // 没标记过 → 点一下直接记 5 星、**不动状态**（见 DetailViewModel.quickRate）；
                                    // 想改分数或状态就点提示里的「修改」，与旁边那枚收藏按钮同一套节奏：
                                    // 先落地、再给一个反悔入口。
                                    viewModel.quickRate()
                                    viewModel.pendingQuickRate()?.let { request ->
                                        PendingSync.hold(QUICK_RATE_KEY) {
                                            viewModel.sendQuickRate(request)
                                        }
                                        // 固定等待：点完 6 秒后才发（提示 4 秒 + 1 秒反应 + 1 秒缓冲）。
                                        // 不跟提示的动画/消失事件绑定 —— 用户提前退出详情页也照样发得出去。
                                        PendingSync.schedule(QUICK_RATE_KEY, QUICK_SEND_DELAY_MS)
                                    }
                                    toastState.show("已评分 5 星", actionLabel = "修改") {
                                        // 用户要改分数/状态：先别发，等他在弹窗里选
                                        PendingSync.pause(QUICK_RATE_KEY)
                                        collectionDialogFromQuick = true
                                        collectionDialogOpen = true
                                    }
                                }
                            } else {
                                viewModel.setCollectionStatus(null)
                            }
                        },
                        onToggleFavorite = {
                            if (authState.isLoggedIn) {
                                if (quickCollect.inside == true) {
                                    // 已在某个收藏夹里 → 打开多选弹窗让他增删，
                                    // 不直接"取消收藏"（那可能把他放在别的收藏夹里的同一作品也弄没）
                                    pickerFromQuick = false
                                    pickerOpen = true
                                    pickerViewModel.open(work)
                                } else {
                                    // 第一次收藏 → 直接写进默认收藏夹（上次选的 / 「我喜欢的」），
                                    // 不弹窗；想换收藏夹点提示里的「修改」。
                                    pickerViewModel.quickCollect(work)
                                    pickerViewModel.pendingQuickCollect()?.let { request ->
                                        PendingSync.hold(QUICK_COLLECT_KEY) {
                                            pickerViewModel.sendQuickCollect(request)
                                        }
                                        // 固定等待：点完 6 秒后才发（提示 4 秒 + 1 秒反应 + 1 秒缓冲）——
                                        // 不跟提示的动画/消失事件绑定，提前退出详情页也照样发得出去
                                        PendingSync.schedule(QUICK_COLLECT_KEY, QUICK_SEND_DELAY_MS)
                                    }
                                }
                            } else {
                                toastState.show("请先到「设置 → 账号」登录后再收藏")
                            }
                        }
                    )
                },
                modifier = Modifier.onSizeChanged { barHeight = it.height }
            )

            // 注：原先右下角悬浮的「开始播放 / 继续播放」胶囊已按需求移除。
            //     现在从**文件列表**点任意音频即可开始播放（点文件夹则展开/折叠）。
        }
    }

    // ── 文件树里非音频内容的查看器（弹窗承载）──────────────
    // 用弹窗而不是导航到新页面：用户点开是"临时看一眼"，
    // 返回键或关闭按钮直接回到详情页，不该往导航栈里塞一层。
    imageFile?.let { file ->
        ImageViewerDialog(file = file, onDismiss = { imageFile = null })
    }
    videoFile?.let { file ->
        VideoPlayerDialog(
            url = file.url,
            onDismiss = { videoFile = null }
        )
    }
    // PDF 下载中：给它一个明确的进度反馈。
    // 实测 PDF 最大 17MB，若不给反馈用户会以为点了没反应而反复点。
    if (downloadingPdf) {
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text("正在下载，准备打开…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    // 「下载」选择弹窗：只列**还没下完**的内容，已下载的会显示「已下载」并置灰
    if (downloadOpen) {
        DownloadPickerSheet(
            workTitle = state.work?.title.orEmpty(),
            tree = state.pickerTree,
            downloadedPaths = state.downloadedPaths,
            onDownload = { paths -> viewModel.downloadSelected(paths) },
            onDismiss = { downloadOpen = false }
        )
    }

    // 「我的收藏」状态选择弹窗
    if (collectionDialogOpen && work != null) {
        CollectionStatusDialog(
            title = "收藏状态",
            workTitle = work.title,
            currentStatus = work.collectionStatus,
            currentRating = work.userRating,
            // 只有"打开之前就已经标记过"才有东西可删；快捷评分打开时
            // 「取消」本身就是撤销，不需要再给一个「删除」。
            canRemove = !collectionDialogFromQuick,
            onRemove = {
                collectionDialogOpen = false
                // 删除 = 评分与状态整条移除（本地先行，网络后台补）
                PendingSync.clear(QUICK_RATE_KEY)
                viewModel.dropPendingQuickRate()
                viewModel.setCollectionStatusRemove()
                toastState.show("已移除标记")
            },
            onConfirm = { status, rating ->
                collectionDialogOpen = false
                // 以弹窗的选择为准：刚才那笔"快捷 5 星"作废（这次 setCollectionStatus 会自己发请求）
                PendingSync.clear(QUICK_RATE_KEY)
                viewModel.dropPendingQuickRate()
                if (status == null && rating == null) {
                    // 弹窗里的「删除」：清掉整条标记（评分也一起清）
                    viewModel.setCollectionStatusRemove()
                    toastState.show("已移除标记")
                } else {
                    // ⚠️ 这里必须把 rating 一起传下去。
                    // 之前写成 `{ status, _ -> ... }`，用户选的星级被直接丢弃，
                    // 详情页只会回落到 work.userRating（而它往往来自列表页的缓存、
                    // 根本没有这个字段）→ 结果「选了 5 星但云端收不到评分」。
                    viewModel.setCollectionStatus(status, rating)
                    toastState.show("已更新标记")
                }
            },
            onDismiss = {
                collectionDialogOpen = false
                if (collectionDialogFromQuick) {
                    // 取消 = 什么都不做：刚才那次快捷评分整条撤销，也不发请求
                    PendingSync.clear(QUICK_RATE_KEY)
                    viewModel.pendingQuickRate()?.let { viewModel.revertQuickRate(it) }
                    viewModel.dropPendingQuickRate()
                    toastState.show("已撤销这次标记")
                }
            }
        )
    }

    // 「加入收藏夹」弹窗：只有点提示里的「修改」才打开（收藏本身不再弹窗）
    if (pickerOpen) {
        PlaylistPickerDialog(
            workTitle = work?.title.orEmpty(),
            state = pickerState,
            // 只有"打开之前就已经收藏过"才显示「删除」：快捷收藏打开的弹窗里
            // 「取消」就是撤销那次收藏。
            canRemove = !pickerFromQuick,
            onToggle = { pickerViewModel.toggle(it.id) },
            onConfirm = {
                // 以弹窗的选择为准：快捷收藏那笔作废（confirm 会自己发写请求）
                PendingSync.clear(QUICK_COLLECT_KEY)
                pickerViewModel.dropPendingQuickCollect()
                pickerViewModel.confirm()
            },
            onRemove = {
                // 删除 = 从所有收藏夹移出（作品与收藏夹是多对多）
                PendingSync.clear(QUICK_COLLECT_KEY)
                pickerViewModel.dropPendingQuickCollect()
                pickerViewModel.removeFromAllPlaylists()
            },
            onStartCreate = { pickerViewModel.startCreate() },
            onNewNameChange = { pickerViewModel.onNewNameChange(it) },
            onCreateConfirm = {
                PendingSync.clear(QUICK_COLLECT_KEY)
                pickerViewModel.dropPendingQuickCollect()
                pickerViewModel.createAndAdd()
            },
            onCancelCreate = { pickerViewModel.cancelCreate() },
            onDismiss = {
                pickerOpen = false
                if (pickerFromQuick) {
                    // 取消 = 什么都不做：撤销刚才那次快捷收藏，也不发请求
                    PendingSync.clear(QUICK_COLLECT_KEY)
                    pickerViewModel.pendingQuickCollect()?.let { pickerViewModel.revertQuickCollect(it) }
                    pickerViewModel.dropPendingQuickCollect()
                    toastState.show("已撤销这次收藏")
                }
            }
        )
    }

    // 提示宿主：挂在最后，覆盖在整个页面之上（含各弹窗之外）
    Box(modifier = Modifier.fillMaxSize()) {
        ToastHost(
            state = toastState,
            // 固定位置，不随迷你播放器出现/消失而跳动（见 Liquid.TOAST_OFFSET）
            toastOffset = Liquid.TOAST_OFFSET,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/** 详情页头图上方两角的圆角半径 —— **想调圆角大小就改这一个值** */
private val HeroTopCorner = 16.dp

/** 头图左右留白 —— **想放大/缩小图片在这个界面上的占比就改这里**（越小图越大） */
private val HeroHorizontalPadding = 20.dp

/** 头图与下方标题之间的间隔 —— **想拉开/收紧这段留白就改这里** */
private val HeroTitleGap = 16.dp

/** 标题与元信息标签（RJ 号 / 日期）之间的间距 */
private val TitleMetaGap = 8.dp

// ─────────────────────────── 分区节奏（固定间距） ───────────────────────────

/**
 * **分区之间**的固定外间距。
 *
 * 详情页按内容分为几大块：① 标题/标签式信息（RJ 号、日期、分级…）、
 * ② 信息表（评分 / 销量 / 价格 / 时长 + 社团 / 声优）、③ 标签墙，之后还有文件、简介。
 * 这些**块与块之间**统一用 [SectionGap]，节奏才稳定。
 */
private val SectionGap = 20.dp

/**
 * **分区内部**的间距（同类信息之间，例如信息表里「评分那一行」到「社团那一行」）。
 * 恒小于 [SectionGap] —— 内紧外松，视觉上才能既"成组"又"分区"。
 */
private val InnerGap = 10.dp

/**
 * 信息项之间的**横向**间距（评分 / 销量 / 价格 / 时长 之间，以及 社团 / 声优 之间）。
 *
 * 20dp 时一行空得太散，四个数据项被推得满屏都是；14dp 既把它们收拢成"一组数据"，
 * 又不会糊在一起（标签是小字，靠间距分项）。两行共用这一个值，节奏才一致。
 */
private val MetaItemGap = 14.dp

/**
 * 社团 / 声优两列的**左列宽度占比**（占去掉间隙后的可用宽度）。
 *
 * 定成固定比例而不是按内容算：左列宽一旦跟着社团名长度走，声优那列的
 * 起始位置就会**每一部作品都不一样** —— 社团叫「把你淫普淫普掉」时声优被推到很右边，
 * 叫「Whisp」时又缩回来。同一个用户连着看两部，会觉得这两行"没对齐"。
 *
 * 0.38 的取法：常见社团名（`Whisp`、`ミッケのニコラ`）在 360dp 屏上刚好放得下，
 * 声优一列还剩约 200dp，够放两三个名字再折行。
 */
private const val CIRCLE_COLUMN_FRACTION = 0.38f

/**
 * ① 头图。
 *
 * 按需求：
 *  - **不带任何背景**（没有模糊底图，也没有底部延伸渐变），图片自己裁切填满；
 *  - 比例与卡片统一为 **4:3**；
 *  - 左右各留 [HeroHorizontalPadding]，**缩小它在界面上的占比**，
 *    同时与下方信息区的 20dp 边距对齐；
 *  - **上方两角圆角**，下方两角保持直角。
 */
@Composable
private fun HeroCover(work: Work) {
    CoverImage(
        url = work.coverUrl(AsmrRepository.host),
        contentDescription = work.title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HeroHorizontalPadding)
            .aspectRatio(Sizes.CARD_IMAGE_ASPECT),
        shape = RoundedCornerShape(topStart = HeroTopCorner, topEnd = HeroTopCorner),
        // 没有模糊底图了，所以让图片裁切填满整块区域
        contentScale = ContentScale.Crop,
        showBackdrop = false
    )
}

/**
 * ② 标题（图片下方那一个）+ 作品号 / 分级 / 字幕角标。
 *
 * 「标记 / 收藏夹」两个动作**不在这里** —— 它们放在顶部玻璃栏里
 * （见 [DetailTopActions]），标题块只负责"这部作品是什么"。
 */
@Composable
private fun TitleBlock(
    work: Work,
    onOpenWork: (Int) -> Unit,
    onCopy: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier.padding(
            start = 20.dp,
            end = 20.dp,
            top = HeroTitleGap
        )
    ) {
        // 标题可长按选中：作品名里常有需要单独查的假名/社团后缀，
        // 能选中复制比整串复制有用。选中态由系统提供（含放大镜与拖柄）。
        SelectionContainer {
            Text(
                text = work.title,
                // 对齐 KikoFlu 详情页标题：16sp / Bold（样式定义见 ui/theme/Type.kt 的 WorkTitleStyle）
                style = WorkTitleStyle,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(Modifier.height(TitleMetaGap))

        // 发布日期标签文本（1 年内只显示月日，更早带上年份）
        val releaseTag = remember(work.release) { releaseTagText(work.release) }

        // ── 第一行：作品号 + 发布日期（只读元信息）──
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            work.sourceId?.let { rj ->
                // RJ 号整串就是要复制的东西（拿去 DLsite 搜、发给别人），
                // 长按直接复制，不走"先选中再拖柄"那两步。
                SmallTagChip(
                    text = rj,
                    tone = TagTone.Neutral,
                    modifier = Modifier.pointerInput(rj) {
                        detectTapGestures(onLongPress = { onCopy(rj) })
                    }
                )
            }
            releaseTag?.let { SmallTagChip(text = it, tone = TagTone.Neutral) }
        }

        // ── 第二行：分级 / 字幕 / 多语种 + 语言版本链接 ──
        //    语言版本只列「站内实际收录」的其它版本（other_language_editions_in_db），
        //    与网页端一致；点链接跳到该语言版本的详情页。
        val hasMetaRow = !work.isAdult || work.hasSubtitle ||
            work.hasMultipleLanguages || work.languageEditions.isNotEmpty()
        if (hasMetaRow) {
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 只保留「全年龄」；成人作品不做年龄标记（R18 标签已按需求删除）
                if (!work.isAdult) {
                    SmallTagChip(text = "全年龄", tone = TagTone.Neutral)
                }
                if (work.hasSubtitle) {
                    SmallTagChip(text = "含字幕", tone = TagTone.Subtitle)
                }
                if (work.hasMultipleLanguages) {
                    SmallTagChip(text = "多语种", tone = TagTone.Primary)
                }
                work.languageEditions.forEach { edition ->
                    LanguageEditionText(edition = edition, onOpenWork = onOpenWork)
                }
            }
        }
    }
}

/**
 * 发布日期标签的文本。
 *
 * 规则（滚动一年窗口）：以今天为基准，**不早于「去年今天」**的只显示 `MM/dd`，
 * 更早的补上年份显示 `yyyy/MM/dd`。
 * 例：今天是 10/15 时 —— 去年 10/15 及之后的作品显示 `03/08`；更早的显示 `2019/01/13`。
 *
 * 解析不了日期的（接口值异常）返回原始字符串；没有日期返回 null。
 */
private fun releaseTagText(
    release: String?,
    today: java.time.LocalDate = java.time.LocalDate.now()
): String? {
    val raw = release?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val date = runCatching { java.time.LocalDate.parse(raw.take(10)) }.getOrNull()
        ?: return raw
    val locale = java.util.Locale.US
    return if (date.isBefore(today.minusYears(1))) {
        "%04d/%02d/%02d".format(locale, date.year, date.monthValue, date.dayOfMonth)
    } else {
        "%02d/%02d".format(locale, date.monthValue, date.dayOfMonth)
    }
}

/**
 * 站内收录的另一个语言版本 —— **统一是链接样式**（主色 + 下划线），
 * 点击跳转到该语言版本的详情页。
 *
 * 之所以这里不再区分"当前版本/不可点"：数据源只包含**站内存在**的其它版本，
 * 当前正在看的这一版不会出现在列表里。
 */
@Composable
private fun LanguageEditionText(
    edition: LanguageEdition,
    onOpenWork: (Int) -> Unit
) {
    Text(
        text = edition.label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        maxLines = 1,
        modifier = Modifier
            .clickable { onOpenWork(edition.workId) }
            .padding(vertical = 2.dp)
    )
}

/**
 * 快捷评分 / 收藏在 [PendingSync] 里的键。
 *
 * 两个 key 分开：用户可能先标记再收藏，两条提示会先后出现，
 * 各自的倒计时不能被对方顶掉。
 */
private const val QUICK_RATE_KEY = "detail-quick-rate"

/** 快捷收藏在 [PendingSync] 里的键 */
private const val QUICK_COLLECT_KEY = "detail-quick-collect"

/**
 * 快捷操作的请求延迟：点完 [ACTION_TOAST_DURATION_MS]（4 秒提示）
 * + 1 秒反应时间 + 1 秒缓冲，一共 6 秒。
 *
 * 计时从点击那一刻起算，**不跟提示的动画/消失事件绑定** ——
 * 提示是界面的东西，用户可能在它还在的时候就把页面退了，
 * 那时不该因为"没等到消失"而把这次收藏/标记永远留在本地。
 */
private const val QUICK_SEND_DELAY_MS = ACTION_TOAST_DURATION_MS + 2_000L

/**
 * 顶栏右侧的「标记 / 收藏夹 / 下载」动作组 —— **同一个液态玻璃胶囊内**并排三个图标按钮。
 *
 * 为什么把动作都收进顶栏、且共用一个玻璃容器：
 *  - 详情页的正文是"作品信息"，动作若压在标题下方会打断阅读节奏；
 *    顶栏是系统层，动作放在这里与页面内容彻底分开；
 *  - 三个动作共用一个胶囊（而不是三个独立玻璃圆钮）：
 *    它们属于同一类操作（对这部作品做点什么），一个容器就能表达"同一组"，
 *    也避免右上方叠三块玻璃显得碎；
 *  - 按钮之间**不留空隙**：36dp 的触摸框已经在图标两侧各留了 8dp，
 *    再叠 Spacer 会显得松散，三个图标就不像一组了。
 *
 * 图标含义：
 *  - 书签 = 我的标记（想听 / 在听 / …）；已标记时用状态自己的图标 + 主色，
 *    只有星级没有状态时换成**实心书签**（ic_bookmark_filled_fluent）+ 主色 ——
 *    线框/实心与颜色一起表达"这条已经标记过了"，和收藏夹按钮的对勾同一套语言；
 *  - 文件夹 = 加入收藏夹；已加入时换成"带对勾"的图标 + 主色；
 *  - 箭头落盘 = 下载（原「文件」标题行右侧的文字按钮搬到这里）；已下载时用主色，
 *    文件树还没到时置灰但保留位置。
 */
@Composable
private fun DetailTopActions(
    status: CollectionStatus?,
    /** 标记过（有状态或打过星）—— 书签显示为实心 + 主色 */
    marked: Boolean,
    collected: Boolean,
    backdrop: Backdrop?,
    /** 已经下过（含下了一部分）—— 下载图标转主色，语义变成「继续下载」 */
    downloaded: Boolean,
    /** 文件树到了才允许点：没树的时候选择器是空的，点了只会让人困惑 */
    downloadEnabled: Boolean,
    onDownload: () -> Unit,
    onOpenCollection: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .backdropButton(backdrop = backdrop, shape = LiquidCapsule)
            .heightIn(min = Liquid.CONTROL_HEIGHT)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TopBarMiniAction(
            // 有状态时用状态自己的图标；只有星级（快捷标记）时用实心书签，
            // 一眼能看出"已经标记过"，不必点开弹窗确认。
            iconRes = status?.iconRes
                ?: if (marked) R.drawable.ic_bookmark_filled_fluent
                else R.drawable.ic_bookmark_outline_fluent,
            contentDescription = status?.label ?: if (marked) "已标记" else "标记",
            tint = if (status != null || marked) cs.primary else cs.onSurfaceVariant,
            onClick = onOpenCollection
        )

        // 图标之间不放 Spacer：36dp 的触摸框在图标两侧各留了 8dp，
        // 视觉间距已经够（约 16dp）；再叠空隙三个图标就不像一组了。
        TopBarMiniAction(
            iconRes = if (collected) R.drawable.ic_folder_check_fluent
            else R.drawable.ic_folder_add_fluent,
            contentDescription = if (collected) "已在收藏夹" else "加入收藏夹",
            tint = if (collected) cs.primary else cs.onSurfaceVariant,
            onClick = onToggleFavorite
        )

        TopBarMiniAction(
            iconRes = R.drawable.ic_arrow_download_fluent,
            contentDescription = if (downloaded) "继续下载" else "下载",
            tint = when {
                downloaded -> cs.primary
                downloadEnabled -> cs.onSurfaceVariant
                else -> cs.onSurfaceVariant.copy(alpha = 0.38f)
            },
            enabled = downloadEnabled,
            onClick = onDownload
        )
    }
}

/** 顶栏玻璃胶囊里的单个图标按钮（36dp 触摸框，玻璃胶囊内不再叠第二层玻璃） */
@Composable
private fun TopBarMiniAction(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * ③ 信息表 —— 样式对齐 **comicApp 漫画详情页**的做法。
 *
 * 关键点（之前做成了等宽分列，显得过于工整、留白也多，这里改掉）：
 *  - 每一项都**按内容取自然宽度**，不设 `weight` —— 所以左右不会对齐成一列列的格子；
 *  - 一行放不下时用 `FlowRow` 自然换行，项与项之间统一留 [MetaItemGap]；
 *  - 组与组之间（数据 ↔ 社团/声优）留 [InnerGap]，靠间距在视觉上分成两组，不用容器；
 *  - 两行**共用一套基线**（见 [MetaItem]）：一行里可能同时有拉丁字母和中日文，
 *    字体度量不同，不统一基线的话名字会一高一低。
 *
 * 数据项（**单行**）：评分 / 销量 / 价格 / 时长。
 * （「评论数」已移除；「发行时间」改为 RJ 号那一行的日期标签。）
 */
@Composable
private fun StatsBlock(
    work: Work,
    onOpenVoiceActor: (id: String, name: String) -> Unit = { _, _ -> },
    onOpenCircle: (id: Int, name: String) -> Unit = { _, _ -> }
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(SectionGap))

        // 数据行：评分 / 销量 / 价格 / 时长
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MetaItemGap),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetaItem(
                label = "评分",
                value = if (work.rate > 0) "%.2f".format(work.rate) else "—",
                starIcon = true
            )
            MetaItem("销量", work.dlCount.grouped())
            MetaItem("价格", if (work.price > 0) "¥" + work.price.grouped() else "免费")
            MetaItem("时长", work.durationText.takeIf { it != "--:--" } ?: "—")
        }

        // 分区内部：数据行 ↔ 社团/声优行 靠拢（[InnerGap]），两块读起来是一个整体
        Spacer(Modifier.height(InnerGap))
        // 社团与声优都做成**链接样式**（主色 + 下划线）并可点 → 按它搜索。
        //
        // 为什么用下划线而不是只靠颜色：颜色在很多主题下与正文差别不大，
        // 而"能点"这件事必须一眼看出来 —— 用户不会去试每一个字。
        CreditsRow(
            circleName = work.circleName,
            circleId = work.circleId,
            onOpenCircle = onOpenCircle,
            voiceActors = work.vas.map { va -> va.name to { onOpenVoiceActor(va.id, va.name) } }
        )
    }
}

/** 千分位分隔（固定用 US locale，避免个别区域设置把分隔符换成「.」） */
private fun Int.grouped(): String = "%,d".format(java.util.Locale.US, this)

/**
 * 数据项：上标签用次要色小字、下数值用正文小字 + Medium 字重。
 * [starIcon] = true 时在数值前加一枚琥珀色星标（评分用）。
 *
 * 只服务「评分 / 销量 / 价格 / 时长」这一行 —— 社团 / 声优要链接、要跨字体对齐基线，
 * 走的是 [CreditsRow]。
 *
 * ## 基线
 *
 * 这一行里偶尔也会混进中日文（`免费`、占位 `—`），它与数字走的是两套 fallback 字体
 * （Noto Sans CJK vs Roboto），顶对齐时会低 2~3dp。所以用 [TextMeasurer] 量出两者的
 * 第一行基线差，给矮的那个补上顶部留白，让同一行的数值落在同一条基线上。
 */
@Composable
private fun MetaItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    starIcon: Boolean = false
) {
    val valueStyle = MaterialTheme.typography.bodySmall
    val measurer = rememberTextMeasurer()
    // 基准取中日文的基线：fallback 字体里它的 ascent 最大，其余字体只会更矮
    val baselinePad = with(LocalDensity.current) {
        (
            rememberMetaBaseline(measurer, valueStyle, "声") -
                rememberMetaBaseline(measurer, valueStyle, value)
            ).coerceAtLeast(0f).toDp()
    }

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))

        // 按实测基线补齐顶部留白 —— 见上面的「基线」
        Box(modifier = Modifier.padding(top = baselinePad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (starIcon) {
                    Icon(
                        painter = painterResource(R.drawable.ic_star_fluent),
                        contentDescription = null,
                        tint = RatingStar,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    text = value,
                    style = valueStyle,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 社团 / 声优：两列「上标签、下名字」，两列的名字**落在同一条基线上**。
 *
 * ## 为什么必须自己排（这里前后改了三版）
 *
 * 两个名字的字体常常不是同一套：`Tower of Babel` 走 Roboto、
 * `兎月りりむ。` 走 Noto Sans CJK。两者 ascent 相差 0.2em 以上、行盒高度不同，
 * 顶部对齐之后字形基线就错开 2~3dp —— 看起来是「声优的名字比社团的低了一截」，
 * 反过来（社团是中日文、声优是拉丁）则反着低。
 *
 * 试过三种"绕过字体度量"的写法，都只能把误差压到 1dp 左右，肉眼仍然看得出来：
 * ① 同高盒子居中；② 同高盒子贴底；③ 用 [TextMeasurer] 量出偏移补顶部留白。
 * 所以这里换成**自定义布局**：直接读两段文字**真实排版**出来的
 * `firstBaseline`，取较大者作共同基线，把矮的那个整体下移差值。
 * 剩下的误差只来自像素取整，与字体、字号、系统字体缩放都无关。
 *
 * ## 点击与选区
 *
 * 值里可能不止一个名字（多位声优），每个名字都要能单独跳转 —— 所以名字是**一个个**
 * 独立的 `Text`（声优之间用 `/` 分隔），各自挂 `Modifier.clickable`。
 *
 * 另一个硬要求是长按能选中文字，所以整块包在 [SelectionContainer] 里。
 * 这两件事有冲突：`AnnotatedString` + `LinkAnnotation` 虽然也能做"多段可点"，
 * 但带链接注释的 `Text` 会自己装一层 pointerInput 处理链接点击，把按下事件消费掉，
 * 外层 [SelectionContainer] 就再也起不来选区（表现为"长按没反应"）。
 * 拆成独立 `Text` + [Modifier.clickable] 之后，点按照旧、长按完整交给选区。
 */
@Composable
private fun CreditsRow(
    circleName: String?,
    circleId: Int?,
    onOpenCircle: (id: Int, name: String) -> Unit,
    voiceActors: List<Pair<String, () -> Unit>>
) {
    val labelStyle = MaterialTheme.typography.labelSmall
    val valueStyle = MaterialTheme.typography.bodySmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = MaterialTheme.colorScheme.onBackground
    // 链接样式：主色 + 下划线 + Medium（颜色在很多主题下与正文差别不大，
    // 而下划线能让"这里能点"一眼看出来，用户不会去试每一个字）
    val linkStyle = valueStyle.copy(
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        textDecoration = TextDecoration.Underline
    )

    // 长按选中、点按搜索 —— 两件事要同时成立。
    //
    // ⚠️ 这里**不能**用 `AnnotatedString` + `LinkAnnotation` 来做点击：带链接注释的
    // Text 会自己装一层 pointerInput 处理链接点击，它把按下事件消费掉之后，
    // SelectionContainer 就再也起不来选区（表现就是"长按社团/声优没反应"）。
    // 改成"每个名字一个可点 Text"，点按由 Modifier.clickable 派发，
    // 长按则完整地交给外层 SelectionContainer。
    //
    // ⚠️ 而 SelectionContainer 又必须包在**整行外面**，不能包在每个值外面：
    // 它内部是一个普通 Layout，不往外传对齐线 —— `Placeable.get(FirstBaseline)`
    // 拿到 `AlignmentLine.Unspecified`，下面的基线对齐就只能退回"按高度估算"。
    // 同一个 bodySmall 样式下两列盒子一样高 ⇒ 估算值也一样 ⇒ 偏移恒为 0，
    // 基线从来没真正对上过；表现为中日文那列整体低 3dp（字体的 ascent 更大），
    // 也就是"有没有片假名，内容高低就不一样"。包在外面时被测量的是 Text 本身，
    // 基线是真的。
    SelectionContainer {
        Layout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                Text("社团", style = labelStyle, color = labelColor, maxLines = 1)
                CircleValue(
                    circleName = circleName,
                    circleId = circleId,
                    valueStyle = valueStyle,
                    linkStyle = linkStyle,
                    valueColor = valueColor,
                    onOpenCircle = onOpenCircle
                )
                Text("声优", style = labelStyle, color = labelColor, maxLines = 1)
                VoiceActorValue(
                    voiceActors = voiceActors,
                    valueStyle = valueStyle,
                    linkStyle = linkStyle,
                    valueColor = valueColor
                )
            }
        ) { measurables, constraints ->
        val gapPx = MetaItemGap.roundToPx()
        val labelGapPx = 2.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val label1 = measurables[0].measure(loose)
        val label2 = measurables[2].measure(loose)
        // 两列宽度**固定**成比例，不看内容 —— 声优永远从同一条竖线开始。
        //
        // 旧写法按社团名的 maxIntrinsicWidth 决定左列（还夹在"至少给声优留 1/4"
        // 的范围里），于是左列宽 = f(社团名长度)：短名时声优贴过来、长名时被推远。
        // 同一屏里看不出来，换一部作品再看就明显"不同步"。
        //
        // ⚠️ 同一个 Measurable 只能 measure 一次（否则抛
        // `measure() may not be called multiple times`），所以左列宽必须在
        // measure 之前就用常量算好 —— 这也是固定占比的另一个好处。
        val available = (constraints.maxWidth - gapPx).coerceAtLeast(1)
        val col1 = maxOf(label1.width, (available * CIRCLE_COLUMN_FRACTION).toInt())
        val x2 = col1 + gapPx
        val value1 = measurables[1].measure(Constraints(maxWidth = col1))
        val value2 = measurables[3].measure(
            Constraints(maxWidth = (constraints.maxWidth - x2).coerceAtLeast(1))
        )

        val labelHeight = maxOf(label1.height, label2.height)
        val valueTop = labelHeight + labelGapPx
        val baseline1 = value1.usableFirstBaseline()
        val baseline2 = value2.usableFirstBaseline()
        // 共同基线 = 两者里更靠下的那条，矮的那个整体下移差值
        val target = maxOf(baseline1, baseline2)
        val height = valueTop + maxOf(
            target - baseline1 + value1.height,
            target - baseline2 + value2.height
        )
        val width = maxOf(col1, x2 + maxOf(label2.width, value2.width))
            .coerceAtMost(constraints.maxWidth)
            .coerceAtLeast(constraints.minWidth)

        layout(width, height) {
            label1.place(0, 0)
            label2.place(x2, 0)
            value1.place(0, valueTop + (target - baseline1))
            value2.place(x2, valueTop + (target - baseline2))
        }
        }
    }
}

/** 社团名：能点就点，没有 id 时只当普通文本 */
@Composable
private fun CircleValue(
    circleName: String?,
    circleId: Int?,
    valueStyle: TextStyle,
    linkStyle: TextStyle,
    valueColor: Color,
    onOpenCircle: (id: Int, name: String) -> Unit
) {
    if (circleId == null || circleName.isNullOrBlank()) {
        Text(
            text = circleName ?: "—",
            style = valueStyle,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    } else {
        Text(
            text = circleName,
            style = linkStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                // 关掉水波纹：这行是纯文本链接，涟漪会从字缝里透出来
                indication = null
            ) { onOpenCircle(circleId, circleName) }
        )
    }
}

/**
 * 声优：0 位给占位符，1 位直接一个 Text，多位走自写的流式排版。
 *
 * 三种情况都必须是"一个能被 measure 的子节点"，且都要能报出第一行基线，
 * 否则 [CreditsRow] 那边的基线对齐又要退化成估算。
 */
@Composable
private fun VoiceActorValue(
    voiceActors: List<Pair<String, () -> Unit>>,
    valueStyle: TextStyle,
    linkStyle: TextStyle,
    valueColor: Color
) {
    when {
        voiceActors.isEmpty() -> Text("—", style = valueStyle, color = valueColor)
        voiceActors.size == 1 -> Text(
            text = voiceActors.first().first,
            style = linkStyle,
            maxLines = 1,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { voiceActors.first().second() }
        )
        else -> VoiceActorFlow(
            voiceActors = voiceActors,
            valueStyle = valueStyle,
            linkStyle = linkStyle,
            valueColor = valueColor
        )
    }
}

/**
 * 多位声优的流式排版：名字一个个排，放不下就换行。
 *
 * 为什么不用 `FlowRow`：它不对外报第一行基线，[CreditsRow] 就没法把这一列和
 * 社团那一列对齐。这里自己排，顺手把"首行基线"通过 `alignmentLines` 报出去。
 */
@Composable
private fun VoiceActorFlow(
    voiceActors: List<Pair<String, () -> Unit>>,
    valueStyle: TextStyle,
    linkStyle: TextStyle,
    valueColor: Color
) {
    Layout(
        content = {
            voiceActors.forEachIndexed { index, (name, action) ->
                if (index > 0) {
                    Text(" / ", style = valueStyle, color = valueColor, maxLines = 1)
                }
                Text(
                    text = name,
                    style = linkStyle,
                    maxLines = 1,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { action() }
                )
            }
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val maxWidth = constraints.maxWidth
        val xs = IntArray(placeables.size)
        val ys = IntArray(placeables.size)
        var rowStart = 0
        var x = 0
        var y = 0
        var rowBaseline = 0
        var rowHeight = 0
        var firstRowBaseline = -1
        var usedWidth = 0

        // 一行排完（或到末尾）时才落位：行内先按基线对齐，再整行下移
        fun placeRow(endExclusive: Int, rowTop: Int) {
            for (i in rowStart until endExclusive) {
                ys[i] = rowTop + (rowBaseline - placeables[i].usableFirstBaseline())
            }
            if (firstRowBaseline < 0) firstRowBaseline = rowTop + rowBaseline
        }

        placeables.forEachIndexed { index, placeable ->
            if (x > 0 && x + placeable.width > maxWidth) {
                placeRow(index, y)
                usedWidth = maxOf(usedWidth, x)
                y += rowHeight
                rowStart = index
                x = 0
                rowBaseline = 0
                rowHeight = 0
            }
            xs[index] = x
            x += placeable.width
            rowBaseline = maxOf(rowBaseline, placeable.usableFirstBaseline())
            rowHeight = maxOf(rowHeight, placeable.height)
        }
        placeRow(placeables.size, y)
        usedWidth = maxOf(usedWidth, x)

        layout(
            width = usedWidth.coerceAtMost(maxWidth).coerceAtLeast(constraints.minWidth),
            height = y + rowHeight,
            // 报出去的这一条就是"这一列的第一行基线"，外层据此与社团列对齐
            alignmentLines = mapOf(FirstBaseline to firstRowBaseline)
        ) {
            placeables.forEachIndexed { index, placeable -> placeable.place(xs[index], ys[index]) }
        }
    }
}

/**
 * 取真实排版的第一行基线。
 *
 * `> 0` 而不是 `>= 0`：文本的第一行基线不可能是 0，拿到 0 说明这个节点根本没提供
 * 这条对齐线（`AlignmentLine.Unspecified` 是 Int.MIN_VALUE，但过程中也可能出现 0），
 * 当成有效值会让两个值错开整整一个 ascent —— 正是"中日文那列低 3dp"的由来。
 */
private fun Placeable.usableFirstBaseline(): Int {
    val baseline = get(FirstBaseline)
    return if (baseline > 0) baseline else (height * 0.8f).toInt()
}

/**
 * 量出 [text] 在 [style] 下的**第一行**基线（px），用于给同一行里"更矮的字体"补顶部留白。
 *
 * 用 [TextMeasurer] 而不是布局结果里的 `firstBaseline`：这里量的时候是同一个 style、
 * 同一套字体回退，量出来的基线与真正渲染时完全一致；而布局结果只在
 * [CreditsRow] 那种自己 measure 的地方才拿得到。
 */
@Composable
private fun rememberMetaBaseline(measurer: TextMeasurer, style: TextStyle, text: String): Float =
    remember(measurer, style, text) {
        measurer.measure(AnnotatedString(text), style = style).firstBaseline
    }

/** ④ 标签墙（优先显示官方中文名） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsBlock(
    work: Work,
    onTagClick: (id: Int, name: String) -> Unit = { _, _ -> },
    onCopyTag: (String) -> Unit = {}
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(SectionGap))
        Text(
            text = "标签",
            // 小标题样式：比「评分 / 销量」那类信息标签更大更实
            // （labelLarge 14sp / onBackground ↔ 信息标签 labelSmall 11sp / onSurfaceVariant），
            // 但仍属标签字族，观感不会跳脱。
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        // 间距对齐 comicApp 的标签墙：小标题 ↔ 标签 6dp，标签之间横 8dp / 纵 6dp
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            work.tags.forEach { tag ->
                TagWallChip(
                    text = tag.displayName,
                    onClick = { onTagClick(tag.id, tag.displayName) },
                    // 点按 = 按这个标签搜；长按 = 复制标签名（拿去别处搜或记下来）
                    onLongClick = { onCopyTag(tag.displayName) }
                )
            }
        }
    }
}

/**
 * ⑥ 文件区表头：只有「文件」小标题 + 一行统计摘要。
 *
 * 「展开全部 / 折叠」按钮、「音质分支切换」标签、右下角悬浮的「开始播放」胶囊、
 * 以及原先放在这一行的**文字下载按钮**均已移除 ——
 * 下载入口和「标记 / 收藏夹」一起收进了顶栏胶囊（见 [DetailTopActions]），
 * 这样详情页的动作只有一个去处，文件区标题行也不会被按钮挤成两行。
 */
@Composable
private fun TrackTreeHeader(
    state: DetailUiState,
    onRetry: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(SectionGap))
        Text(
            text = "文件",
            // 与「标签」小标题**统一**同一套样式（labelLarge 14sp / onBackground）
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(2.dp))
        Text(
            // 下载过的作品只显示本地内容，这时摘要要说清楚"这是本地的那部分"，
            // 否则用户会以为文件丢了
            text = if (state.isDownloaded) {
                "已下载 ${state.downloadedPaths.size} 个文件 · ${trackTreeSummary(state.tracks)}"
            } else {
                trackTreeSummary(state.tracks)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 树加载中 / 加载失败
        when {
            state.tracks.isEmpty() && state.tracksError != null -> {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "文件列表获取失败：${state.tracksError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorColor,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }

            state.tracks.isEmpty() -> {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "正在获取文件列表…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

/** ⑦ 简介 */
@Composable
private fun DescriptionBlock(work: Work) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(SectionGap))
        Text(
            text = "简介",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = work.description ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(16.dp))
    }
}
