<p align="center">
  <img src="docs/screenshots/detail.png" width="19%" alt="作品详情" />
  <img src="docs/screenshots/file-tree.png" width="19%" alt="文件树" />
  <img src="docs/screenshots/player.png" width="19%" alt="播放器" />
  <img src="docs/screenshots/library.png" width="19%" alt="收藏与下载" />
</p>

<p align="center">
  <sub>作品详情 · 文件树 · 播放器 · 收藏</sub>
</p>

# ToAsmr

一个为 **asmr.one / Kikoeru 兼容服务端** 写的 Android 客户端。

用 Kotlin 与 Jetpack Compose 从零写成，界面安静、克制：内容退到后面，玻璃质感的顶栏与底栏浮在前面，其余地方只留文字与封面。

它想解决的事情其实很朴素 —— **喜欢的东西应该随时能听**。所以除了在线浏览与播放，它也会把作品完整搬到本地：文件树、音频、歌词、封面，一个不少；断网、换设备，或者有一天服务器不在了，下载下来的那些还在硬盘上，就是普通的音频文件。

> 不是官方客户端。App 本体不含任何音频，所有内容都来自你自己配置的服务器。

## 大致是这样

- **找** —— 首页榜单、关键词检索、标签／声优／社团多条件叠用；知道 RJ 号的话，在搜索框里敲进去就直接到作品，外部链接点开也是。
- **看** —— 完整文件树，文件夹逐个展开，记得你上次看到哪；封面、定价、时长、社团声优、标签墙都摆在一屏里。
- **听** —— 后台播放、锁屏与耳机控制、变速、循环与随机、睡眠定时渐弱收尾；歌词可以外挂也可以来自音频内嵌，还能开成桌面悬浮窗。
- **存** —— 想留的内容可以整部或只挑几轨下到本地：放 App 里由它管理，或者直接落进手机公共目录（默认 `ToAsmr/`），在文件管理器里就是普通文件。
- **亮** —— 十四套主题色，跟随系统或手动锁定明暗，液态玻璃的层次感贯穿全局。

收藏、标记与评分这些和账号绑定的部分也都在：先落本地、联网再补到云端，失败不会丢。

## 构建

需要 Android Studio（AGP 9.2.1 / Gradle 9.4.1）、JDK 17+、Android SDK 36。

```bash
git clone https://github.com/Minazuki-Hotaru1/ToAsmr.git
cd ToAsmr
./gradlew :app:assembleDebug
```

正式包用 `:app:assembleRelease`（R8 + 资源收缩）。签名从 `ASMRONE_KEYSTORE`、`ASMRONE_KEYSTORE_PASSWORD`、`ASMRONE_KEY_ALIAS`、`ASMRONE_KEY_PASSWORD` 四个环境变量读取；不设置就退回 debug 签名 —— 能装能跑，不能发布。密钥不进仓库。

## 关于权限

联网与后台播放是必需的；存储权限与「显示在其他应用上层」只在你要把下载写进公共目录、或想开桌面歌词时才需要，不给也能正常用。`MANAGE_EXTERNAL_STORAGE` 属于敏感权限，因此本项目不上架 Google Play，只以侧载方式分发。

支持的 API 是 asmr.one 与 Kikoeru 那套（内置几个常用主机，也可以填自己的地址）。除此之外只面向 Android；内嵌歌词目前只读 `mp3` 与 `flac`。

## 免责声明

本项目仅供学习与个人使用，不提供、不存储、不分发任何音频内容。所有内容都来自你自己配置的服务器，请自行确认其合法性与站点条款。与 asmr.one、DLsite 及其它内容平台没有任何隶属关系，也未获得其背书。

## 致谢

感谢 [asmr.one](https://asmr.one) 与 Kikoeru 社区，以及 [kyant0](https://github.com/kyant0) 的 Backdrop、Microsoft 的 Fluent 图标、Jetpack Compose、Media3 与 Coil。
