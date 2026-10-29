# 公考雷达 去广告模块（LSPosed）

针对 **公考雷达 6.7.3.1**（`com.maidu.gkld`，长沙麦都网络科技）的 LSPosed / EdXposed 模块。
DNS 层拦广告域名 + URL 层兜底 + 布局/控件级隐藏自营运营位。**不 hook 加固壳的启动路径**，首次启动不闪退。

> 个人设备自用。模块不碰业务逻辑、不破解付费内容。App 官方会员本身就有「免开屏广告」权益，
> 愿意支持的话那条路更省心。

---

## 一、先看结论：这个 App 的广告是怎么接的

我下载了官网渠道的最新安装包做的静态分析（165MB，`versionName=6.7.3.1`，MD5 `1381fe7b…683d04`）：

| 项 | 结果 |
|---|---|
| 加固 | **网易易盾**。`application = com.netease.nis.wrapper.MyApplication`，`classes.dex` 里只有 **30 个壳类**，真实代码运行时解密，`libdexvmp.so` 做 VMP |
| 广告中介 | **TopOn / AnyThink** 聚合，域名 `*.anythinktech.com`，3 个广告位 ID：`b218ad913b`、`b9a394428b`、`bcb3903995` |
| 直接集成的 SDK | 穿山甲（`libpanglearmor.so`、`com.bytedance.sdk.openadsdk` 271 个类）、优量汇（`assets/gdt_plugin/gdtadv2.jar` + `com.qq.e` 165 类）、快手（`com.kwad`）、百度（`com.baidu.mobads`）、**倍孜**（`com.beizi.fusion` 2190 类）、**美数**（`com.meishu.sdk` 1503 类）、**FL/1rtb**（`com.fl.saas` 866 类）、**联盟 SSP**（`com.alliance.ssp` 699 类）、**Gameley**（395 类）、OPPO（`com.heytap.msp`）、华为 ADS |
| App 自己的广告类 | `ui.splash.SplashActivity`（开屏）、`ui.splash.ThreeAdActivity`、`ui.splash.RewardVideoAdActivity`（激励视频）、`base.ATSplashExListenerBase`、`manager.AdSystemManager`、`adapter.ad.AdCenterViewHolder`（信息流卡片）、`bean.ad.AdV2Bean`（自营广告数据）、`ubix.MerakCustomSplashAdapter`（开屏的自定义适配器） |
| 自营广告接口 | `api/v1/getUserOpenCreenAdStatus`、`api/v1/updateUserOpenCreenAdStatus`，走业务域名 `api.gongkaoleida.com` |

**三个关键判断（v1.6 定稿）：**

1. **App 被加固，绝对不能碰壳的启动路径。** 常规模块在 `handleLoadPackage` 里直接找
   `com.maidu.gkld.xxx` 会抢在易盾解密前触发类加载。v1.1~v1.5 用「hook `ClassLoader.loadClass`」
   来绕这件事 —— 这正是首启闪退的根因：易盾首启要在主线程解密 106MB dex 并替换 ClassLoader，
   `loadClass` 被 hook 住等于按住它的手术刀；第二次启动走解密缓存、类加载量骤降，所以不崩。
   → v1.6 彻底删掉这个钩子，改用时序规避（见下）。
2. **开屏广告靠 DNS 拦截，不靠短路 `loadAd`。** 聚合 SDK 的 `loadAd` 被整个跳过，它内部的
   超时计时器就不会启动，开屏页会一直转圈。而让域名解析失败（`UnknownHostException`），
   SDK 走的是自己写好的「无网络」分支，又快又安全 —— 这也是 AdGuard Home 的做法。
3. **不要 hook `Runtime.exit` / `Process.killProcess` / `setDefaultUncaughtExceptionHandler`。**
   这三样是反调试的重点监视对象，被劫持后壳可能直接 native `abort()`，表现出来就是
   「进程消失、没有 Java 堆栈」，和最早那次崩溃的现象一模一样。

---

## 二、模块做了三件事

| 层 | 文件 | 具体动作 |
|---|---|---|
| **DNS 拦截**（主力） | `hook/DnsHooks.java` + `BlockList.java` | hook `InetAddress.getAllByName/getByName`，命中黑名单就让解析失败（`UnknownHostException`）。URL 一个字节不动，和 AdGuard Home 的失败方式完全一致 |
| **URL 兜底** | `hook/HttpHooks.java` | 走 HTTPDNS / IP 直连、绕开了 DNS 的 SDK，在 `URL.openConnection` 和 `OkHttpClient.newCall` 上把 host/port 换成本机未监听端口，**协议、路径、query 全部保留** |
| **运营位隐藏** | `hook/ViewHooks.java` | `LayoutInflater.inflate` 的 **after** 钩子里，按布局名藏悬浮球/会员卡/列表广告位，按控件 id（`banner_user_center`、`banner_ad`）锁死「我的」页广告轮播 —— 只 `setVisibility(GONE)`，不拦截原方法 |

三样共同点：**只读参数，命中才动手，绝不改变控件的加载过程**。这也是不闪退的前提。

域名黑名单（**都是在这个 APK 的字符串里逐条确认过的**，不是抄的通用列表）：

```
TopOn      anythinktech.com
穿山甲      pangolin-sdk-toutiao.com / -b / 1、pglstatp-toutiao.com、oceanengine.com、
           bytesfield.com、bytesfield-b.com、bytesmanager.com、ad.toutiao.com、
           phoniex.toutiao.com、chengzijianzhan.com、toutiaopage.com
优量汇      gdt.qq.com、pgdt.gtimg.cn
快手        ad.kuaishou.com、e.kuaishou.com、kwaicdn.com、ksadcn.com
百度        mobads.baidu.com、mobads-logs.baidu.com、cpro.baidustatic.com
倍孜        adscope.com
美数        medproad.com
FL/1rtb    1rtb.com、1rtb.net
ubix SSP   ubixioe.com          ← 开屏自定义适配器 MerakCustomSplashAdapter 走这家
Gameley    gameley.com
阿里妈妈     statisticslinks.com、tanx.com、adn.alibaba.com
华为        ppsc.hicloud.com、ads.huawei.com
```

**没有**加进去的：`gongkaoleida.com` / `gkld.top` 全系（App 业务域名）、bugly、umeng、极光、微信、
以及 `e.qq.com`（会影响 QQ 登录/分享，代价大于收益）。

---

## 三、构建

**已经构建好了**：`gkld-adclean-v1.7.1.apk`（v2/v3 签名，`minSdk 26 / targetSdk 34`），
直接拿去装，不用再编译。签名用的是自签证书，同一份 `libs/debug.keystore`，
可以直接覆盖安装 v1.0.0 ~ v1.7.0（versionCode 已升到 10）。

**Android Studio**：`File → Open` 选 `gkld-adclean`，同步后 `Build → Build APK(s)`，
产物 `app/build/outputs/apk/debug/app-debug.apk`。

**命令行（不装 Gradle）**：`tools/build-apk.sh` 走 aapt2 + javac + d8 + apksigner，
只要有 Android SDK（build-tools 34.0.0 + platform 34）和 JDK 17 就行：
```bash
ANDROID_SDK_ROOT=$HOME/Android/Sdk ./tools/build-apk.sh            # 默认输出到上级目录
ANDROID_SDK_ROOT=$HOME/Android/Sdk ./tools/build-apk.sh /tmp/x.apk # 或指定路径
```
脚本会自动从阿里云镜像拉 `de.robv.android.xposed:api:82` 到 `libs/`（只在编译期用，不进 APK），
首次运行会生成签名用的 `libs/debug.keystore`。

依赖：`de.robv.android.xposed:api:82`（官方仓库 `https://api.xposed.info/`，国内拉不动就用上面的镜像）。

## 四、安装与启用

1. 手机已 root，装好 LSPosed（Magisk / KernelSU / APatch 模块）。
2. 装本模块 `gkld-adclean-v1.5.0.apk`，LSPosed「模块」里启用，作用域勾 **公考雷达**（`AndroidManifest` 已预设）。
3. **强行停止**公考雷达再打开 —— 模块只在新进程启动时生效。

## 五、验证生效

LSPosed 管理器 → 日志 → 搜 `GkldAdClean`，正常长这样：

```
[GkldAdClean] I 命中目标: com.maidu.gkld（模块适配版本 6.7.3.1）
[GkldAdClean] I OkHttp 钩子已安装
[GkldAdClean] I 类监听已就绪，待接管 30 个类
[GkldAdClean] I 已接管 com.anythink.nativead.api.ATNative → 2 个方法
[GkldAdClean] I OkHttp.newCall 钩子已安装        ← v1.1 关键：这次真的装上了
[GkldAdClean] I 拦截 OkHttp: https://cn-api.anythinktech.com/api/ad/union/...
[GkldAdClean] I 拦截开屏加载: com.beizi.fusion.SplashAd#loadAd
[GkldAdClean] I 隐藏运营位布局: item_operation_banner
[GkldAdClean] I 隐藏运营位布局: float_view
```

判读方法：
- **`OkHttp.newCall 钩子已安装` 这行是分水岭**。没有它 = 所有走 OkHttp 的广告（TopOn、穿山甲）都拦不住，只有 URLConnection 那部分生效。
- 有 `拦截 OkHttp: …anythinktech.com…` 说明聚合请求真的被掐了；
- 有 `隐藏运营位布局: xxx` 说明自营运营位被隐藏；如果还有没盖住的卡片，看 `疑似广告布局（未隐藏…）` 那几行，把名字加进 `Config.LAYOUT_BLOCKLIST`；
- 完全没有 `已接管 …` 说明类没被加载到（大概率 App 更新换了 SDK），走第六节。

## 六、App 更新后失效怎么办

1. 先看日志有没有 `命中目标`。没有 → 作用域没勾或没强停。
2. 有 `命中目标` 但没有 `已接管` → 拿新版 APK 重新看 `Config.SDK_CLASSES` 对应的类还在不在：
   ```bash
   # 直接在一堆 dex 里搜类名，比 jadx 快得多（加固包也能搜到字符串）
   strings classes*.dex | grep -E 'Lcom/(anythink|beizi|meishu|fl/saas|kwad)/.*Splash'
   ```
3. 有新广告服务器 → 抓包（Reqable / mitmproxy / HttpCanary）冷启动一次，
   把新域名加进 `Config.HOSTS` 或 `EXTRA_HOSTS`，重新编译即可。

## 七、已知局限

- **自营广告**（走业务接口下发）：域名和 SDK 都碰不到。v1.1 起用 `ViewHooks` 按**布局名**隐藏
  （`item_operation_banner` 会员促销卡、`float_view` 悬浮红包球等），代价是列表里可能留一条窄缝。
  如果某张运营位没被盖住，日志里会出现 `疑似广告布局（未隐藏…）`，把那个名字加进
  `Config.LAYOUT_BLOCKLIST` 即可，不用改代码。
- **激励视频**（`RewardVideoAdActivity`）故意没拦：那是用户主动"看广告换权益"的页面，拦了等于砍功能。
- **加固**意味着所有 App 自身方法的 hook 都依赖"类加载后被我们接管"这一时机；
  如果某次更新把 SDK 打进 VMP 保护范围，方法级 hook 可能失效，此时只剩域名拦截生效（通常也够）。
- 静态分析能做到"配置对不对"，能不能真的拦住必须在真机上看日志，我这边没有设备可验证。

## 八、文件地图

```
app/src/main/java/com/gkld/adclean/
├── MainHook.java              入口（assets/xposed_init 登记）
├── Config.java                ★ 所有参数：域名、布局黑名单、广告控件 id、开关
├── BlockList.java             域名匹配 + 缓存
├── Log.java                   日志（LSPosed 日志 + logcat）
├── hook/DnsHooks.java         DNS 层拦截（主力，v1.5 起）
├── hook/HttpHooks.java        URL / OkHttp 兜底改写
├── hook/ViewHooks.java        inflate 后按布局名 / 控件 id 隐藏运营位
└── ui/MainActivity.java       说明页

legacy-hooks/                  v1.1~v1.5 用过、v1.6 已下线的钩子（不参与编译，留档）
├── ClassWatcher.java          ClassLoader.loadClass 监听 —— 首启闪退的根因
├── SdkHooks.java              SDK 方法短路 / 开屏拦截
├── FeedHooks.java             信息流广告卡片
├── CrashGuard.java            崩溃捕获
├── ExitGuard.java             Runtime.exit 监控
├── BootTrace.java             启动打点
└── AdListenerNotifier.java    尽力回调 onError
```

## 九、v1.1.0 修复记录（对照 2026-10-29 17:27 的真机日志）

第一版装上后"看着生效、实际没拦住"，日志给了三条明确证据，对应三个独立缺陷：

| # | 日志证据 | 根因 | 修复 |
|---|---|---|---|
| 1 | `没找到 okhttp3，跳过…ClassNotFoundException` | OkHttp 钩子只在 `handleLoadPackage` 里试**一次**。加固包的 okhttp3 是懒加载的，那一刻还没加载，失败后永久放弃 → TopOn / 穿山甲 的请求全走 OkHttp，一条没拦 | `HttpHooks.tryInstallOkHttp()` 幂等可重试：类加载监听遇到 `okhttp3.*` 就装、三轮延时扫描各补一次；拦截点从 `Request$Builder.build()` 换成 **`OkHttpClient.newCall(Request)`**，同步/异步、`url(String)`/`HttpUrl.parse` 拼出来的请求全都经过它 |
| 2 | 5 次 `开屏请求 → SplashAd#loadAd（交给域名拦截）`，`bid/fail`、`bid/win` 都拦住了，广告照播 | 只拦上报/竞价域名不影响 SDK 用**本地缓存**素材出广告；v1.0 对开屏只"监听"不拦 | `hookSplash()` 直接短路 `loadAd/loadSplashAd` 并回调失败；再给 `show()` 兜一刀，宿主是 Splash 页时 300ms 后替它 `finish()`，防止停在开屏页等不来的回调 |
| 3 | 拦截列表里全是 `ubixioe.com` / `medproad.com`，没有任何 `gongkaoleida.com` 的请求 | 截图圈出来的"月末特惠 / VIP买季卡送月卡"是**自营运营位**，数据走业务域名，域名和 SDK 都碰不到 | 新增 `ViewHooks`，按**布局名**隐藏：`item_operation_banner`（三行文案促销卡）、`float_view*`（悬浮红包球）、`list_item_third_ad_banner` / `item_home_ad_banner`（三方广告位） |

另外两个顺手修的坑：

- **`findClass` 递归**：OkHttp 钩子从 `loadClass` 回调里调用 `findClass`，会再次触发 `loadClass`，
  加了 `installing` 重入标记，否则开机会无限递归。
- **监听器挖不出来**：倍孜的 `SplashAd.loadAd()` 不带监听器参数（用 `setAdInteractionListener` 挂在对象上），
  所以除了扫入参，还反射遍历 `thisObject` 的字段找监听器再回调 `onError`。

验证方式（装 v1.1.0 后冷启动，日志搜 `GkldAdClean`）：

```
命中目标: com.maidu.gkld（模块 v1.1.0 / 适配 App 6.7.3.1）
OkHttp.newCall 钩子已安装                 ← 这行出现才算真的修好了
拦截 OkHttp: https://cn-api.anythinktech.com/api/ad/union/...
拦截开屏加载: com.beizi.fusion.SplashAd#loadAd
隐藏运营位布局: item_operation_banner
```

如果仍然有广告，日志里找 `疑似广告布局（未隐藏…）`，把它加进 `Config.LAYOUT_BLOCKLIST` 重新打包即可。

## 十、v1.2.0 修复记录（对照 2026-10-29 17:45 的真机日志）

用户反馈：悬浮窗广告没了、开屏没了，但**首页轮播还在**；而且**首次启动闪退、二次启动白屏很久**。

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 首页「精选考试」轮播仍在 | 轮播容器是 `home_article_head_card_exam_banner`（内部 `com.maidu.gkld.view.banner.BannerHomeInfor`，item 为 `..._exam_big/_small`），不在黑名单里。日志里其实已经把它列为「疑似广告布局（未隐藏）」 | 加进 `LAYOUT_BLOCKLIST`，同时剔除 `activity_three_ad`（三方广告落地页） |
| 2 | 首次启动闪退 | `ViewHooks` 的 inflate 回调里如果抛异常，会**直接抛回 inflate 调用方**（`XposedHelpers` 的 after 钩子不吞异常）→ 宿主崩在布局阶段 | 整个回调包 `try-catch`，任何异常只在内部吞掉 |
| 3 | 二次启动白屏很久 | ① 延时扫描跑在**主线程**，1.5s/4s/8s 三轮各做 30+ 次 `Class.forName`，失败要抛异常填栈，正好卡在首页绘制前后；② `loadClass` 两个重载都挂了钩子，每次类加载回调两遍；③ 每个 okhttp3.* 类加载都 `findClass` 一次并抛 `ClassNotFoundException`（几百个类） | ① 扫描挪到后台线程（`MIN_PRIORITY`）；② 只挂 `loadClass(String, boolean)`，并在 60 秒后自动熔断；③ okhttp 直接用 `loadClass` 已返回的 `Class` 安装钩子，`findClass` 试错上限 3 次 |
| 4 | 日志里 `NoSuchFieldError: AdCenterViewHolder#itemView` 反复出现 | `FeedHooks` hook 了全局 `RecyclerView.Adapter.bindViewHolder`，每次绑定都反射取字段并抛异常 | 去掉全局绑定钩子，只 hook 该 ViewHolder 的构造；`itemView` 改为沿继承链找字段，找不到只记一次日志 |
| 5 | 日志刷出一堆 `home_article_head_card_xxx` 误报 | 探测关键字用了裸 `"ad"`，把 `head`/`loading`/`read` 全命中了 | 改成 `_ad` / `ad_` / `advert` 这类带边界的写法，误报几乎清零 |

性能前后对比（同一台设备冷启动，日志时间戳）：

- v1.1：`命中目标` 之后到首页可见约 9 秒（期间 3 轮主线程扫描）
- v1.2：扫描全部在后台，主线程只做一次 `postDelayed` 触发

### 为什么轮播只按布局名藏、不按类名藏

`BannerHomeInfor` 这个类被 **10 个布局**引用（`home_article_head_card_view`、`item_home_ad_banner` 等），
按类名一律 GONE 会把首页正文一起干掉。布局名只对应那一个轮播容器，所以只藏
`home_article_head_card_exam_banner`。

### 装完怎么确认

日志第一行会显示 `模块 v1.2.0`（旧版只有「模块适配版本 6.7.3.1」，一眼可分）。然后找：

```
隐藏运营位布局: home_article_head_card_exam_banner   ← 轮播没了
布局拦截已就绪，黑名单 9 个
```

如果轮播还在，看有没有 `疑似广告布局: xxx`，把它加进 `Config.LAYOUT_BLOCKLIST` 重打包。

## 十一、v1.3.0 修复记录（对照 2026-10-29 17:56 的反馈）

两处纠错 + 一个诊断工具。

### 1. 首页轮播：撤掉（v1.2 加错了）

v1.2 把 `home_article_head_card_exam_banner` 当成广告加进黑名单，实际那是首页「精选考试」的
**正常内容轮播**。v1.3 已从 `LAYOUT_BLOCKLIST` 移除。

### 2. 「我的」页广告轮播：按控件 id 拦

之前一直没命中，是因为**它根本不是独立布局**：

`fragment_user_center.xml`（`res/Wsx.xml`）里直接写了两个
`com.maidu.gkld.view.banner.Banner` 控件，没有单独的 layout 文件，
所以 `LayoutInflater.inflate` 钩子永远看不到它们 —— v1.1/v1.2 的
`item_operation_banner` 自然一次都没命中过。

从 `resources.arsc` 里核对到的 id：

| id | 资源名 | 说明 |
|---|---|---|
| `0x7f090183` | `id/banner_user_center` | 80dp 高、`indicator_*` 4dp 轮播点 —— 就是「月末特惠 限时月抢！VIP买季卡送月卡」那条，右上角有「广告」角标 |
| `0x7f090177` | `id/banner_ad` | 名字即广告位 |
| ~~`0x7f09017b`~~ | `id/banner_exam_area` | 74dp，考试区域**内容**轮播，**不要拦** |

做法：这两个 Banner 在 XML 里默认 `visibility=GONE`，App 拿到广告数据后才调
`setVisibility(VISIBLE)`。所以挂 `View.setVisibility` 的 before，命中 id 就 `setResult(null)`
直接吞掉那次调用，控件永远保持 GONE。同时用 `bannerCls.isInstance()` 先过滤，
非 Banner 控件零开销。

### 3. 闪退：加崩溃捕获

上一版「首次闪退」的归因（inflate 回调异常）只是推测，日志里没有堆栈。
v1.3 加了 `CrashGuard`：hook `Thread.setDefaultUncaughtExceptionHandler`，把 App 的
处理器包一层 —— 崩溃时先写完整堆栈到 LSPosed 日志，再原样转交，不影响 App 自己的崩溃上报。

日志里会是：

```
[GkldAdClean] !!! 崩溃[main]: java.lang.XXX
	at com.gkld.adclean.hook.XXX...     ← 模块的锅
	at com.netease.nis.wrapper...       ← 加固壳的锅
```

**要区分是不是模块引起的，做一次对照**：LSPosed 里把本模块的作用域取消勾选 → 强行停止公考雷达
→ 冷启动。如果照样第一次闪退，那就是 App/加固壳自己的问题，跟模块无关。

### 布局追踪（临时）

`Config.LAYOUT_TRACE_ALL = true` 会把这个进程 inflate 过的**每个布局名**都打一条
`布局: xxx`（去重）。定位完剩下的运营位后改成 `false` 即可。

## 十二、v1.4.0 修复记录（对照 2026-10-29 18:07 的反馈）

用户反馈：**广告全部清干净了**，但**首次启动依旧闪退**，第二次能进。

### 关键证据：崩的那一次，日志是空的

翻 18:01–18:03 的 LSPosed 日志，只有一次 `com.maidu.gkld` 进程记录：

```
18:02:58.682  模块装载
18:02:58.939  命中目标: com.maidu.gkld（模块 v1.3.0 ...）
18:02:58.941  崩溃捕获已就绪
...
18:03:00.472  已接管 com.xxx  ← 全部正常，5ms 装完，零错误
```

也就是说，18:02:58 那次是**第二次启动**。而第一次（闪退的那次）在整个日志里
**连一行都没有** —— 不只是没有崩溃堆栈，是连 `命中目标` 都没有。

这一点很关键：它排除了"模块 hook 抛异常导致崩溃"这个方向（那样至少能留下 `命中目标`），
把范围缩小到两种：

1. 进程在 LSPosed 注入完成前就死了；
2. **native 崩溃**（SIGSEGV / SIGABRT），日志来不及 flush 就被 SIGKILL。

### 为什么只崩第一次：壳的首启解密

App 是网易易盾加固，`classes.dex` 106MB 但只声明 30 个类，真实代码在
`MyApplication.attachBaseContext` 里由 `libdexvmp.so`（native）解密，并**替换 ClassLoader**。
解密结果会落盘缓存，所以：

- **第一次启动**：走解密流程，要动 ClassLoader、要改 ART 的方法表；
- **第二次启动**：直接读缓存，几乎不碰 ClassLoader。

而 v1.3 及以前，模块在 `handleLoadPackage` 里**立刻**挂上了
`ClassLoader.loadClass` 的钩子 —— 等于在壳动手术时按住手术刀。
壳的 native 层如果扫描到方法入口被改，最典型的处理就是直接 abort 进程，
表现就是"闪退且无 Java 堆栈"。

这与"只有第一次崩、第二次正常"完全吻合，也是目前唯一能同时解释
**「广告确实生效」+「只有首启崩」+「日志全空」** 三个事实的假设。

### v1.4 的改法：等壳忙完再动手

| 时机 | v1.3 | v1.4 |
|---|---|---|
| `handleLoadPackage`（进程刚 fork） | 立刻挂 `loadClass` | **不挂**，只装打点 + 网络 + inflate |
| 启动后 800ms + Application 就绪 | — | 才挂 `loadClass` |

两道闸门：

1. `postDelayed(800ms)`。壳首启在主线程同步解密，这条消息会被 MessageQueue
   **自动排到解密之后**（不写死 sleep，慢机器自动等更久）；
2. 到点后再确认 `ActivityThread.mInitialApplication != null`，即壳把 ClassLoader
   换完、Application 真建出来了。没到就 200ms 后重试，最多 6 秒。

`HttpHooks` / `ViewHooks` 保持立即安装 —— 它们 hook 的是 `URL.openConnection`
和 `LayoutInflater.inflate`，壳解密流程用不到，且开屏广告要靠它们第一时间拦住。

### 顺带修的两个问题

- **延时扫描会永不启动**：v1.3 靠 hook `Application.attachBaseContext` 触发扫描，
  而延后安装时这个方法早就调完了。v1.4 改成 `install()` 里直接用 `postDelayed` 排三轮。
- **ClassLoader 取错**：壳解密后会把 Application 的 ClassLoader 换成指向解密 dex 的
  `DexClassLoader`，原来的 `lpparam.classLoader` 扫不到 `com.maidu.*`。
  v1.4 每轮扫描前从 `ActivityThread.mInitialApplication` 重新取一次。
- `LAYOUT_TRACE_ALL` 默认改为 `false`。首启要 inflate 几百个布局，每条 `布局: xxx`
  都是跨进程写日志，这份开销没理由留在正式版里（需要定位新广告位时再打开）。

### 新增两个"抓现场"工具

**BootTrace** —— 启动面包屑，进程猝死也能看出死在哪一步：

```
[启动+0ms]    handleBindApplication 进入（加固壳开始工作）
[启动+1830ms] 壳 attachBaseContext 完成（dex 解密通常在这一步之后）
[启动+2100ms] handleBindApplication 返回（Application 已创建）
[启动+2150ms] Application.onCreate 进入
[启动+2400ms] Application.onCreate 返回
[启动+2900ms] Activity 创建完成: com.maidu.gkld.ui.SplashActivity
```

**ExitGuard** —— 壳反注入最常见的手段不是抛异常而是**自己杀进程**。
挂住 `Runtime.exit/halt`、`Process.killProcess`，在进程咽气前把调用栈写进日志
（只记录不拦截，原方法照常执行）。

### 装完怎么判断

日志第一行应是 `模块 v1.4.0`。然后分三种情况：

| 日志表现 | 结论 | 下一步 |
|---|---|---|
| 有 `命中目标` + 完整的 `[启动+xms]` 序列，没有 `!!! 崩溃` / `进程被结束` | 模块已排除嫌疑，闪退是 App/壳自身问题 | 关掉模块作用域冷启动对照，若仍崩就是壳的锅 |
| `!!! 进程被结束: Runtime.exit` 且栈里有 `com.netease.nis.*` | 壳检测到注入，主动自杀 | 见下节"预案" |
| 一条 `[启动` 都没有，也没有 `命中目标` | 崩在 LSPosed 注入之前 / native 崩溃 | 只能抓 tombstone，模块无能为力 |

### 预案：如果 v1.4 还崩

说明壳把 `HttpHooks`/`ViewHooks` 的钩子也算作入侵痕迹。那就把
`Config.DEFER_CLASS_WATCHER` 换成"全部延后"：在 `MainHook` 里把
`ViewHooks` / `HttpHooks` 一并挪进 `runClassWatcher()`，代价是开屏广告可能漏一次
（首屏几百毫秒内来不及装网络钩子），但启动一定干净。

## 十三、v1.5.0：换掉拦截方式 + 一次性定性（对照 2026-10-29 18:18 的日志）

### 这次日志推翻了之前的结论

v1.4 的 BootTrace 完整打出来了，第一次启动（pid 6211）的日志是：

```
18:17:36.333  命中目标（模块 v1.4.0）
18:17:36.335  启动追踪已就绪
18:17:36.338  布局拦截已就绪
18:17:36.339  URLConnection 钩子已安装
18:17:36.745  [启动+411ms] Application.onCreate 进入 / 返回
18:17:37.433  开始安装类监听（Application 已就绪）
18:17:37.434  类监听已就绪，待接管 33 个类
18:17:37.848  疑似广告布局: fl_adx_view_spread_skipview / fl_adx_spread / ...
18:17:38.016  拦截 URLConnection: https://adx-data-u1.ubixioe.com/tx
              ← 进程到这里就没了，没有任何后续
18:17:39.850  新进程 7073 启动（第二次，一切正常）
```

**结论：模块把所有钩子都装完了，之后又活了 0.6 秒才死。**
不是"装钩子时崩"，而是"装完之后，App 自己在渲染开屏广告时崩"。

而且：

- 没有 `!!! 崩溃`（CrashGuard 没抓到 Java 异常）
- 没有 `进程被结束: Runtime.exit`（ExitGuard 没抓到自杀）
- kmsg / dropbox 里也没有 tombstone

三者皆无 → **native 崩溃（SIGSEGV/SIGABRT）**，Java 层和 LSPosed 都看不见。

另一个决定性差异：第一次进程**始终没有打出 `Activity 创建完成`**，第二次打出来了。
说明第一次是在 SplashActivity 的 onCreate 里 inflate 开屏广告布局（`fl_adx_spread*`，
FL/1rtb 的开屏）时挂掉的。

### 你提到 AdGuard Home，这句话点破了关键

AdGuard Home 是 DNS 层拦截：域名解析不出来，**URL 一个字节都不动**，SDK 收到的是
标准的 `UnknownHostException`，走它自己写好的「域名不可达」分支。

而我们之前干的是：

```
原始:  https://adx-cfg-u1.ubixioe.com/mob/sdk/v3/init?sv=2.10.2.11&aid=52806
改写:  http://127.0.0.1:1/__adblock__
```

协议 https → http、host 变 IP、路径和 query 全丢。SDK 拿到一个面目全非的 URL，
再收到 ConnectException，很容易走进它没写过的分支 —— 这类分支恰恰是最容易空指针
或 native abort 的地方。

### v1.5 的两处改动

**1. 主拦截方式改成 DNS 层（新增 `DnsHooks`）**

hook `java.net.InetAddress.getAllByName` / `getByName`，命中黑名单就直接
`param.setThrowable(new UnknownHostException(host))`。URL、Header、请求体全部不动，
失败方式和 AdGuard Home 完全一致。

**2. URL 改写降级为兜底，且只换 host/port**

只用来兜住走 HTTPDNS（自己解析 IP 直连）绕过 DNS 的 SDK：

```
原始:  https://adx-data-u1.ubixioe.com/tx
改写:  https://127.0.0.1:1/tx        ← scheme、path、query 都还在
```

### 顺手修的诊断缺陷

`BootTrace` 原来把点打在 `Application.attachBaseContext` 上，日志里一直是：

```
D attachBaseContext 打点失败: NoSuchMethodError: android.app.Application#attachBaseContext
```

因为 `Application` 自己没覆盖这个方法，实现在 `ContextWrapper` 上。已改。这个点很关键 ——
它能告诉我们壳到底在哪一步做完解密。

### 一劳永逸的定性办法：safe 版

`gkld-adclean-v1.5.0-safe.apk`（versionCode 7）**只写一行日志，不装任何钩子**：

```
安全模式：本版本不安装任何钩子，仅用于确认闪退是否与模块有关
```

装上它冷启动一次：

| 结果 | 结论 | 下一步 |
|---|---|---|
| **不闪退** | 是某个 hook 引起的 | 回装 1.5.0 正式版，若还崩我再二分（先关视图层，再关类层） |
| **照样闪退** | 与模块的 hook 无关，是 LSPosed 注入/加载模块，或 App + 加固壳自身 | 模块这边无解，建议直接用 AdGuard Home 那套（你已验证可行） |

注意：safe 版和正式版同包名，会互相覆盖，测完记得装回来。

### 一个值得注意的现象

从时间线看，每次**装新版本模块**后的第一次冷启动都会崩，第二次就正常：

- 18:02:22 安装 v1.3.0 → 18:02:58 启动成功（中间那次崩了）
- 18:11 左右安装 v1.4.0 → 18:17:36 崩 → 18:17:40 成功

如果是这个规律，那原因可能在 **LSPosed 在新装模块后第一次注入时要重新加载/校验模块 dex**，
这一步会碰目标进程的 ClassLoader，跟加固壳撞上。如果是这样，safe 版也会崩 ——
正好可以用它验证。

---

## 十四、v1.6.0：safe 版给出答案，把钩子砍到只剩安全的那几样

### 你的测试结果就是结论

| 版本 | 钩子 | 首启闪退 | 应用内广告 |
|---|---|---|---|
| v1.5.0 | 全套 | **崩** | 干净 |
| v1.5.0-safe | 一个都不装 | **不崩** | 还在 |

两行对照把话说死了：**崩溃完全由模块的钩子造成，和 LSPosed 注入、和加固壳自己都没关系。**
上一版结尾那个「LSPosed 新装模块首次注入」的猜测可以划掉了 —— safe 版同样是新装、同样被注入，
但它不崩。

顺带说明，safe 版没看到开屏广告只是这一次的填充率，它在代码里 `handleLoadPackage` 打完日志就
`return` 了，一个域名都没挡。开屏这块还是得靠 v1.6 的 DNS 层（也就是你 AdGuard Home 验证过的那个方式）。

### 逐个交代：删了谁、为什么

| 钩子 | 处置 | 理由 |
|---|---|---|
| `ClassLoader.loadClass` | **删** | 首启崩的头号嫌疑。易盾首启在主线程解密 106MB dex 并**替换 ClassLoader**，此时 hook 住 loadClass 等于按住它的手术刀；第二次启动走解密缓存、类加载量骤降，所以只有第一次崩 —— 和你观察到的规律完全吻合 |
| `Runtime.exit` / `Process.killProcess` | **删** | 反调试的重点监视对象，被劫持后壳可能直接 native `abort()`。这正好解释「进程消失、日志里没有 Java 堆栈」 |
| `Thread.setDefaultUncaughtExceptionHandler` | **删** | 同上，且真崩在 native 时它根本抓不到，留着只有风险没有收益 |
| `ContextWrapper.attachBaseContext` | **删** | 只是启动打点，已经不需要；少一个 hook 面 |
| `View.setVisibility`（全局） | **删** | 全 App 每一次可见性变化都要过桥，还要等 Banner 类加载才装得上（依赖上面那个 loadClass 钩子）。成本最高、收益最低 |
| `SdkHooks`（loadAd 短路 / 开屏 show 兜底） | **删** | 依赖 ClassWatcher 才能装。开屏交给 DNS 层即可，v1.5 的日志也显示域名拦截对开屏是有效的 |
| `FeedHooks`（信息流卡片） | **删** | 同上，依赖 ClassWatcher；对应位置改由布局名黑名单兜 |
| `InetAddress.getAllByName/getByName` | **留** | 只比对 host 字符串，命中才抛异常。AdGuard Home 同款失败方式，你实测不崩 |
| `URL.openConnection` | **留** | 兜住绕开 DNS 的 IP 直连，只改 host/port |
| `OkHttpClient.newCall` | **留，但延后装** | 见下 |
| `LayoutInflater.inflate`（after） | **留** | 原方法照常执行，只在返回后看一眼布局名/控件 id |
| `Activity.onResume`（after） | **新增** | 重扫 decorView 补挂漏网控件；并作为 OkHttp 延时安装的起跑线 |

### 关键设计：用「时序」代替「入侵」

之前所有的麻烦都来自一个错误思路 —— 想尽早拿到 App 的类，于是去 hook 类加载。
v1.6 换成两个不入侵的做法：

**1. OkHttp 钩子延后到「首次 onResume + 2.5 秒」，在后台线程慢慢试。**

```
第一次 Activity.onResume
   └─ 后台线程 sleep 2500ms
        └─ 每秒 Class.forName("okhttp3.OkHttpClient")，最多试 60 次
             └─ 找到就装 newCall + Request$Builder.build
```

那时加固壳必然已经解密完毕（Activity 都起来了），主动触发类加载完全安全。
找不到也不要紧：DNS 层已经把绝大多数请求挡在解析阶段了，这只是给「自己解析 IP 直连」的 SDK 兜底。

**2. 「我的」页轮播改成只锁那一个控件。**

v1.3~v1.5 是挂 `View.setVisibility` 的全局钩子 —— 全 App 每次显隐都过一遍，
还要等 `Banner` 类加载。v1.6 改成：

```
inflate 返回后 → root.findViewById(0x7f090183) 拿到那个轮播
              → setVisibility(GONE)
              → 给它单独挂一个 OnPreDraw 监听
                   （每帧绘制前检查，一旦被 App 设回 VISIBLE 就立刻压回 GONE）
```

影响面从一个 App 收缩到 2 个控件（`banner_user_center`、`banner_ad`），
效果和以前一样 —— App 之后每次 `setVisibility(VISIBLE)` 都会在下一帧之前被撤销。

### 装完怎么验证

LSPosed 日志里按顺序应该看到这 5 行（少一行都说明那一层没装上）：

```
[GkldAdClean] I 命中目标: com.maidu.gkld（模块 v1.6.0 / 适配 App 6.7.3.1）
[GkldAdClean] I DNS 拦截已装: InetAddress.getAllByName
[GkldAdClean] I DNS 拦截已装: InetAddress.getByName
[GkldAdClean] I URLConnection 钩子已安装
[GkldAdClean] I 布局拦截已就绪，黑名单 8 个
[GkldAdClean] I Activity.onResume 监听已就绪
```

然后进「我的」页，应该看到：

```
[GkldAdClean] I 锁定广告控件: id=0x7f090183 (banner_user_center)
```

**和以前最大的区别：这次不应该有任何「类监听」「loadClass」「启动+xxxms」的日志。**
如果还看到它们，说明装的是旧版本。

### 兜底：模块自己会「认怂」

即使 v1.6 还有某种边角崩法，也不需要你再当小白鼠反复试。模块内置了一个极简看门狗
（`Watchdog.java`）：

```
进程起来     → 往 /data/data/com.maidu.gkld/files/adclean.state 写 boot=1
撑过 5 秒    → 改写 boot=0
真崩了       → 下一行永远执行不到，文件停在 boot=1
下次启动     → 读到 boot=1，自动降级为纯网络层（不装 inflate / onResume）
```

日志里会明确写 `上次启动异常中断 → 本次降级为纯网络层`。
也就是说：**最坏情况是崩一次，第二次起就能正常用**，不用等我出新包。

想恢复完整体验：设置里清除公考雷达的应用数据（或删掉那个 state 文件）再启动即可。

### 如果 v1.6 还崩（预期不会）

说明问题出在视图层（`inflate` 或 `onResume`），把这两个也关掉即可 ——
`Config.HIDE_LAYOUTS` / `Config.HIDE_BY_VIEW_ID` 两个开关，关掉后只剩纯网络层，
开屏和所有第三方 SDK 广告依然干净，只是「我的」页那条自营轮播会回来。
到时候告诉我，我再做一版纯网络层的包。

---

## 十五、v1.7.0：先证明自己活着，再去广告

### 你这句话纠正了一个大前提

> 出现一次以后，过一段时间内都不会再出现首屏广告。

这一点直接推翻了 v1.6 的验证逻辑。之前看到 safe 版"没开屏广告"，我得出的结论是
"D​NS 层有效且不崩"，其实那只是频控在起作用 —— safe 版一个域名都没挡。
换句话说：**「这次没看到开屏广告」从来不能作为任何一版的成功证据**。

以后测首屏广告请用这个流程：

```
设置 → 应用 → 公考雷达 → 存储 → 清除数据   （同时清掉广告频控和本模块的降级标记）
→ 冷启动一次，看有没有开屏
```

清数据会一起清掉登录态，介意的话就等频控自然过期再测。

### v1.6 到底哪里没做到

两个独立的漏洞，正好对应你看到的两个现象：

| 现象 | 原因 |
|---|---|
| 首屏广告回来了 | OkHttp 钩子排在「首次 onResume **之后 2.5 秒**」才装。而开屏广告在 `Application.onCreate` → `SplashActivity.onCreate` 这几十毫秒里就把请求发出去了 —— **钩子装好时广告素材已经下载完、正在播** |
| 应用内广告没去掉 | 轮播改用 OnPreDraw 监听，前提是 `root.findViewById(0x7f090183)` 能拿到控件。实测拿不到 —— 那个轮播很可能是 App 用 `addView` 动态挂上去的，不在 inflate 出来的子树里，监听器从头到尾没挂上 |

### v1.7 的三处改动

**1. 换时机：hook `Instrumentation.callApplicationOnCreate`**

这是 framework 里"调用 `Application.onCreate`"的唯一入口 —— 晚于壳解密、早于任何
Activity 创建。在这里拿到 `Application` 之后立刻在后台线程抢装 OkHttp
（100ms × 20 次）：

```
Application.onCreate 执行完
  → 后台线程：Class.forName("okhttp3.OkHttpClient")  ← 网络库此时必然已加载
  → 装上 newCall 钩子
  → SplashActivity.onCreate 发开屏请求  ← 已经被拦
```

注意仍然是 `Class.forName` 主动探测，**没有**回去 hook `ClassLoader.loadClass`。
v1.5 的闪退教训不重复。

**2. 恢复 `View.setVisibility` 拦截（按 id，不依赖 App 类）**

这就是 v1.3 唯一验证过「能让轮播消失」的手段。之前的版本要先用 `Banner` 类做
`isInstance` 判断才敢拦，其实没必要 —— `0x7f090183` / `0x7f090177` 这两个 id 唯一
对应那两个广告控件，按 id 精确匹配就够，也不会误伤首页「精选考试」轮播
（`banner_exam_area` = `0x7f09017b`）。

**3. 启动弹 Toast**

```
公考雷达去广告 v1.7.0 · 已注入
公考雷达去广告 v1.7.0 · 降级模式（仅网络层）   ← 看门狗触发时
```

延后 1.5 秒弹，避免被开屏窗口盖住。**这一条是这次真正的重点** —— 前面几轮排查里，
最耗时的永远是"到底装上没有"。

### 装上后按这个顺序看

| 现象 | 含义 | 下一步 |
|---|---|---|
| **有 Toast** | 模块确实被 LSPosed 加载了 | 看广告有没有少；没少就继续看下面的日志 |
| **没 Toast** | 模块根本没被注入 | 见下方清单，跟代码无关 |
| 有 Toast 但日志里没有 `setVisibility 拦截已装` | 视图层安装失败 | 把日志发我 |

LSPosed 日志里应该出现（按顺序）：

```
命中目标: com.maidu.gkld（模块 v1.7.0 / 适配 App 6.7.3.1）
Application 创建监听已就绪
DNS 拦截已装: InetAddress.getAllByName
DNS 拦截已装: InetAddress.getByName
URLConnection 钩子已安装
布局拦截已就绪，黑名单 8 个
setVisibility 拦截已装（按控件 id）
Activity.onResume 监听已就绪
Application 已创建: com.maidu.gkld.xxx（壳是否已替换：true）
OkHttp 钩子已在启动早期装好（第 0 次尝试）
```

最后两行最关键：`OkHttp 钩子已在启动早期装好` 出现，说明开屏广告这条链被堵住了。

### 如果 Toast 都没弹

那就跟拦截逻辑无关了，按这个清单查：

1. LSPosed → 模块 → 公考雷达去广告 → **启用**开关是否打开；
2. 同一页 → **作用域**里「公考雷达」是否勾选（覆盖安装后偶尔会掉）；
3. 改完作用域要**强制停止公考雷达**再启动，LSPosed 只在进程启动时注入；
4. LSPosed 日志里搜 `GkldAdClean`，一条都没有 = 没注入；
5. 极少数机型需要重启一次手机才认新装的模块。

另外 v1.7 顺手排掉了一个更隐蔽的坑：`AndroidManifest.xml` 里的
**`xposedminversion` 从 93 降到了 82**。这个值写的是"要求框架 API 至少多少"，
写到 93 意味着 API 低于 93 的 LSPosed 会直接判定模块不可用、**整个不加载** ——
现象和"装了没反应"一模一样，而日志里只会留一行框架自己的报错，很容易被忽略。
我们只用到 r82 就有的接口，把门槛写高没有任何收益。

先把这几条排掉，再看代码层。

---

## 十六、v1.7.0 定稿（2026-10-29 21:28 真机验证通过）

用户清数据重登后复测：**开屏广告无、应用内广告无、无闪退**。
清数据这一步同时重置了广告频控，所以这次的结果是可信的 —— 不是被频控掩盖。

至此 v1.1~v1.7 一路踩的坑收敛成三条经验，后面如果 App 升级导致失效，按这三条排查：

1. **失效的是开屏** → 先确认域名黑名单是不是跟不上新 SDK / 新域名
   （日志里看 `拦截 DNS:` 有没有出现；没有就抓包补 `Config.HOSTS`）；
2. **失效的是自营运营位**（悬浮窗 / 会员卡 / 轮播） → 布局名或控件 id 变了，
   用 `adb shell aapt2 dump resources` 查新包的 `resources.arsc`，更新
   `Config.LAYOUT_BLOCKLIST` / `Config.BLOCK_VIEW_IDS`；
3. **又出现首启闪退** → 大概率是有人把 `ClassLoader.loadClass` 的钩子加回来了，
   还有 `Runtime.exit` 也别碰 —— 这两个是易盾最敏感的位置。

遗留的可选项（不影响使用）：

- 启动 Toast 目前开着，稳定用一段时间后可把 `Config.SHOW_TOAST` 改为 `false` 重打包关掉；
- 「我的」页轮播被隐藏后原地可能留一条空位，介意的话可以再调父容器高度；
- App 大版本更新后 `0x7f090183` / `0x7f090177` 这类资源 id 可能变化，先按第 2 条排查。


---

## 十七、v1.7.2：Toast 关闭 + 隐藏桌面图标（v1.7.1 因 activity-alias 撤回）

真机复测通过后收尾的两个改动：

**1. 启动 Toast 关闭** —— `Config.SHOW_TOAST = false`。排障期已结束，需要时改回 true 重打包。

**2. 模块设置页新增「隐藏桌面图标」开关。**

原理：桌面上唯一的 LAUNCHER 入口就是 MainActivity 本体，开关直接切换它的
enabled 状态：

```
打开开关 → PackageManager 禁用 MainActivity → 桌面图标消失
关闭开关 → 重新启用                        → 图标回来
```

> **v1.7.1 教训**：曾把入口换成 `activity-alias` 试图做到"隐藏后仍可从其它入口唤回"，
> 结果 aapt2 打出的 APK 在 badging 里没有 `launchable-activity` 行，部分安装器直接
> 拒装 / 桌面无图标 —— v1.7.1 装不上的原因就是它。回退到禁用本体，兼容性优先。

隐藏后找回入口：

| 方式 | 操作 |
|---|---|
| root 终端（推荐） | `pm enable com.gkld.adclean/.ui.MainActivity` |
| 兜底 | 卸载重装 APK —— 组件启用状态随卸载清除，重装回默认显示 |

注意：LSPosed 管理器的模块页入口对**已禁用**的组件无效，别指望从那里点开。

开关状态读的是系统（`PackageManager.getComponentEnabledSetting`），不自己存偏好，
卸载重装自动回默认。
