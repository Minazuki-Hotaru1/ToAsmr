package com.asmrone.app.ui.screen.detail

import com.asmrone.app.ui.component.rememberToastState
import com.asmrone.app.ui.component.ToastHost
import kotlinx.coroutines.launch
import com.asmrone.app.ui.component.viewer.VideoPlayerDialog
import com.asmrone.app.ui.component.viewer.TextViewerDialog
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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

    // 「加入收藏夹」弹窗（详情页的收藏按钮走这条路）
    var pickerOpen by remember { mutableStateOf(false) }
    // 「标记」（/api/review 的收听状态）选择弹窗
    var collectionDialogOpen by remember { mutableStateOf(false) }
    val pickerViewModel: PlaylistPickerViewModel = viewModel()
    val pickerState by pickerViewModel.state.collectAsState()
    val pickerResult by pickerViewModel.result.collectAsState()
    val authState by AuthManager.state.collectAsState()
    // 收藏结果的提示 —— 用项目共享的 Toast 组件（comicApp 那套样式），
    // 不再在这里手写一份（手写的那份没有淡出、也没有与其它页面一致的位置）
    val toastState = rememberToastState()

    // ── 文件树里非音频内容的打开状态 ──────────────────────
    // 图片 / 视频 / 文本走内置查看器（用弹窗承载，不占导航栈 ——
    // 它们是"临时看一眼"，返回键关掉即可）；PDF 交外部 App。
    var imageFile by remember { mutableStateOf<OpenableFile?>(null) }
    var videoFile by remember { mutableStateOf<OpenableFile?>(null) }
    var textFile by remember { mutableStateOf<OpenableFile?>(null) }
    // PDF 正在下载（体积最大实测 17MB，要有进度，不能"点了没反应"）
    var downloadingPdf by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(pickerResult) {
        // 先取到局部变量再判空：pickerResult 是委托属性（by collectAsState），
        // 直接 show(pickerResult) 无法智能转换
        val message = pickerResult
        if (message != null) {
            toastState.show(message)
            pickerViewModel.clearResult()
        }
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
                        TitleBlock(work = work, onOpenWork = onOpenWork)
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
                        item(key = "tags") { TagsBlock(work, onTagClick = onOpenTag) }
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
                                                else -> textFile = openable
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

            // 悬浮玻璃顶栏：返回 + 标题 + 右侧的「标记 / 收藏夹」动作胶囊
            GlassTopBar(
                title = work.sourceId ?: work.title,
                subtitle = work.title.takeIf { work.sourceId != null },
                onBack = onBack,
                backdrop = backdrop,
                actions = {
                    DetailTopActions(
                        status = work.collectionStatus,
                        // 收藏夹状态：进入页面时未知，由弹窗里的成员检查给出准确状态
                        collected = pickerState.workInside && pickerState.selectedId.isNotBlank(),
                        backdrop = backdrop,
                        onOpenCollection = {
                            if (authState.isLoggedIn) {
                                collectionDialogOpen = true
                            } else {
                                viewModel.setCollectionStatus(null)
                            }
                        },
                        onToggleFavorite = {
                            if (authState.isLoggedIn) {
                                pickerOpen = true
                                pickerViewModel.open(work)
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
            title = file.title,
            onDismiss = { videoFile = null }
        )
    }
    textFile?.let { file ->
        TextViewerDialog(file = file, onDismiss = { textFile = null })
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

    // 「我的收藏」状态选择弹窗
    if (collectionDialogOpen && work != null) {
        CollectionStatusDialog(
            title = "收藏状态",
            currentStatus = work.collectionStatus,
            currentRating = work.userRating,
            onConfirm = { status, rating ->
                collectionDialogOpen = false
                if (status == null && rating == null) {
                    // 弹窗里的「移除」：清掉整条标记（评分也一起清）
                    viewModel.setCollectionStatusRemove()
                } else {
                    // ⚠️ 这里必须把 rating 一起传下去。
                    // 之前写成 `{ status, _ -> ... }`，用户选的星级被直接丢弃，
                    // 详情页只会回落到 work.userRating（而它往往来自列表页的缓存、
                    // 根本没有这个字段）→ 结果「选了 5 星但云端收不到评分」。
                    viewModel.setCollectionStatus(status, rating)
                }
            },
            onDismiss = { collectionDialogOpen = false }
        )
    }

    // 「加入收藏夹」弹窗（收藏按钮）
    if (pickerOpen) {
        PlaylistPickerDialog(
            workTitle = work?.title.orEmpty(),
            state = pickerState,
            onSelect = { pickerViewModel.select(it) },
            onConfirm = { pickerViewModel.confirm() },
            onStartCreate = { pickerViewModel.startCreate() },
            onNewNameChange = { pickerViewModel.onNewNameChange(it) },
            onCreateConfirm = { pickerViewModel.createAndAdd() },
            onCancelCreate = { pickerViewModel.cancelCreate() },
            onDeletePlaylist = { playlist ->
                // 弹窗里的删除直接执行（系统收藏夹在弹窗里根本没有删除按钮，
                // PlaylistPickerViewModel 与仓库层还会各拦一次）
                pickerViewModel.deletePlaylist(playlist)
            },
            onDismiss = { pickerOpen = false }
        )
    }

    // 提示宿主：挂在最后，覆盖在整个页面之上（含各弹窗之外）
    Box(modifier = Modifier.fillMaxSize()) {
        ToastHost(
            state = toastState,
            toastOffset = 120.dp,
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
    onOpenWork: (Int) -> Unit
) {
    Column(
        modifier = Modifier.padding(
            start = 20.dp,
            end = 20.dp,
            top = HeroTitleGap
        )
    ) {
        Text(
            text = work.title,
            // 对齐 KikoFlu 详情页标题：16sp / Bold（样式定义见 ui/theme/Type.kt 的 WorkTitleStyle）
            style = WorkTitleStyle,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(TitleMetaGap))

        // 发布日期标签文本（1 年内只显示月日，更早带上年份）
        val releaseTag = remember(work.release) { releaseTagText(work.release) }

        // ── 第一行：作品号 + 发布日期（只读元信息）──
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            work.sourceId?.let { SmallTagChip(text = it, tone = TagTone.Neutral) }
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
 * 顶栏右侧的「标记 / 收藏夹」动作组 —— **同一个液态玻璃胶囊内**并排放两个图标按钮。
 *
 * 为什么把两个动作都收进顶栏、且共用一个玻璃容器：
 *  - 详情页的正文是"作品信息"，动作若压在标题下方会打断阅读节奏；
 *    顶栏是系统层，动作放在这里与页面内容彻底分开；
 *  - 两个动作共用一个胶囊（而不是两个独立玻璃圆钮）：
 *    它们属于同一类操作（对这部作品做点什么），一个容器 + 一条细分隔线
 *    能表达"同一组"，也避免右上方叠两个玻璃块显得碎。
 *
 * 图标含义：
 *  - 书签 = 我的标记（想听 / 在听 / …）；已标记时用状态自己的图标 + 主色；
 *  - 文件夹 = 加入收藏夹；已加入时换成"带对勾"的图标 + 主色。
 */
@Composable
private fun DetailTopActions(
    status: CollectionStatus?,
    collected: Boolean,
    backdrop: Backdrop?,
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
            iconRes = status?.iconRes ?: R.drawable.ic_bookmark_outline_fluent,
            contentDescription = status?.label ?: "标记",
            tint = if (status != null) cs.primary else cs.onSurfaceVariant,
            onClick = onOpenCollection
        )

        // 不画分隔线：两个图标同属一组，留一点空隙即可（也避免多一道竖线）
        Spacer(Modifier.width(2.dp))

        TopBarMiniAction(
            iconRes = if (collected) R.drawable.ic_folder_check_fluent
            else R.drawable.ic_folder_add_fluent,
            contentDescription = if (collected) "已在收藏夹" else "加入收藏夹",
            tint = if (collected) cs.primary else cs.onSurfaceVariant,
            onClick = onToggleFavorite
        )
    }
}

/** 顶栏玻璃胶囊里的单个图标按钮（40dp，玻璃胶囊内不再叠第二层玻璃） */
@Composable
private fun TopBarMiniAction(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
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
 *  - 一行放不下时用 `FlowRow` 自然换行，项与项之间只留固定间距；
 *  - 组与组之间（数据 ↔ 社团/声优）留 16dp，靠间距在视觉上分成两组，不用容器。
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
            horizontalArrangement = Arrangement.spacedBy(20.dp),
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
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetaItem(
                label = "社团",
                value = work.circleName ?: "—",
                link = work.circleId != null,
                onClick = work.circleId?.let { id -> { onOpenCircle(id, work.circleName.orEmpty()) } }
            )
            MetaItem(
                label = "声优",
                // 多位声优时逐个做成链接 —— 合成一整串再整体可点的话，
                // 点进去只能猜是哪一位（之前就是那样）
                links = work.vas.map { va -> va.name to { onOpenVoiceActor(va.id, va.name) } },
                fallback = "—"
            )
        }
    }
}

/** 千分位分隔（固定用 US locale，避免个别区域设置把分隔符换成「.」） */
private fun Int.grouped(): String = "%,d".format(java.util.Locale.US, this)

/**
 * 信息项（与 comicApp 详情页的信息项同一套写法）：
 * 上标签用次要色小字、下数值用正文小字 + Medium 字重。
 * [starIcon] = true 时在数值前加一枚琥珀色星标（评分用）。
 *
 * ## 两种用法
 *
 *  - [value] + [link]：单个值；[link] 为 true 时渲染成**链接样式**（主色 + 下划线）
 *  - [links]：多个值，每个各自可点（如多位声优）——每个之间用 ` / ` 分隔
 *
 * 之所以提供 [links] 而不是让调用方把多个名字拼成一串：拼成一串之后
 * **整串只能跳一个目标**，多位声优时就不知道该跳哪一位。
 * 拆成逐个链接之后，点谁就是谁。
 */
@Composable
private fun MetaItem(
    label: String,
    value: String = "",
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** 单个值是否显示成链接样式 */
    link: Boolean = false,
    /** 多个各自可点的值；非空时忽略 [value] */
    links: List<Pair<String, () -> Unit>> = emptyList(),
    /** [links] 为空时显示的占位文本 */
    fallback: String = "—",
    starIcon: Boolean = false
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))

        if (links.isNotEmpty()) {
            // 多值：`甲 / 乙`，其中每个名字自己是链接
            Row(verticalAlignment = Alignment.CenterVertically) {
                links.forEachIndexed { index, (text, action) ->
                    if (index > 0) {
                        Text(
                            text = " / ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LinkText(text = text, onClick = action)
                }
            }
            return@Column
        }

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
            val shown = value.ifBlank { fallback }
            if (link && onClick != null) {
                LinkText(text = shown, onClick = onClick)
            } else {
                Text(
                    text = shown,
                    style = MaterialTheme.typography.bodySmall,
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
 * 链接样式的文字：主色 + 下划线 + 小幅放大触摸区。
 *
 * 下划线是必须的 —— 只靠颜色的话，在不少主题里与正文差别很小，
 * "这里能点"这件事就传达不出去。
 *
 * ⚠️ 这里**不能**用 `heightIn(min = 48.dp)` 撑触控高度：
 * 文字是顶对齐的，多出来的 28dp 会全部落在文字下方 ——
 * 而「社团 / 声优」这一行正好是信息区的最后一行，
 * 于是标签区上方凭空多出一大块空白（截图里那段明显的空隙）。
 * 现在改为只加 2dp 内边距（触摸高度约 24dp），布局高度回到文字本来的行高。
 */
@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp)
    )
}

/** ④ 标签墙（优先显示官方中文名） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsBlock(work: Work, onTagClick: (id: Int, name: String) -> Unit = { _, _ -> }) {
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
                TagWallChip(text = tag.displayName, onClick = { onTagClick(tag.id, tag.displayName) })
            }
        }
    }
}

/**
 * ⑥ 文件区表头：只有「文件」小标题 + 一行统计摘要。
 *
 * 「展开全部 / 折叠」按钮、「音质分支切换」标签，以及右下角悬浮的
 * 「开始播放」胶囊均已按需求移除 —— 现在靠**点击文件树里的文件夹行**展开/折叠，
 * 点音频行即开始播放并进入播放器。
 */
@Composable
private fun TrackTreeHeader(
    state: DetailUiState,
    onRetry: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(SectionGap))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "文件",
                    // 与「标签」小标题**统一**同一套样式（labelLarge 14sp / onBackground）
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = trackTreeSummary(state.tracks),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

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
