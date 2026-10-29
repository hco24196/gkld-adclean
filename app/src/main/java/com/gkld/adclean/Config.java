package com.gkld.adclean;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 参数表 —— 全部依据公考雷达 6.7.3.1 (com.maidu.gkld) 的实际 APK 逆向结果填写。
 *
 * 分析结论：
 *   1. APK 用网易易盾加固（application = com.netease.nis.wrapper.MyApplication，
 *      classes.dex 只有 30 个壳类），所以不能对 App 自身类提前 findClass，必须等类加载时再 hook；
 *   2. 广告主体是 TopOn(AnyThink) 聚合，域名 *.anythinktech.com，3 个广告位
 *      b218ad913b / b9a394428b / bcb3903995；
 *   3. 同时打包了直接调用的 SDK：穿山甲、优量汇、快手、百度、倍孜、美数、FL(1rtb)、
 *      ubix SSP、Gameley、OPPO(heytap)、华为 ADS。
 */
public final class Config {

    private Config() {
    }

    public static final String TAG = "GkldAdClean";

    /** 模块自身版本，方便在日志里区分装的哪一版 */
    public static final String MODULE_VERSION = "1.7.2";

    /** 本模块适配的 App 版本 */
    public static final String TARGET_VERSION = "6.7.3.1";

    public static final String TARGET_PACKAGE = "com.maidu.gkld";

    public static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(TARGET_PACKAGE));

    public static final boolean DEBUG = true;

    /** 只处理主进程（广告都在主进程） */
    public static final boolean MAIN_PROCESS_ONLY = true;

    /**
     * 安全模式（v1.5 新增）：只打一行日志，不安装任何钩子。
     *
     * 专门用来一次性定性「首次启动闪退到底是不是模块造成的」：
     * 装这个版本的 APK 冷启动 —— 不崩就说明是某个 hook 的问题，还崩就与模块无关。
     * 日常使用请保持 false。
     */
    public static final boolean SAFE_MODE = false;

    /**
     * v1.6 的定案（真机实测驱动）：
     *
     *   safe 版（不装任何钩子）→ 不闪退、应用内广告在。
     *   完整版（全钩子）        → 广告干净、首次启动闪退。
     *
     * 所以崩溃 100% 来自钩子，接下来只保留「不足以让加固壳出事」的那部分，
     * 全部 hook 目标都从「壳敏感区」挪到「业务区」：
     *
     *   删掉 ClassLoader.loadClass 钩子  ← 首启崩的头号嫌疑。易盾首启要在主线程解密
     *                                      106MB dex 并替换 ClassLoader，此时 hook 住
     *                                      loadClass 等于按住它的手术刀，且第二次启动
     *                                      走解密缓存、loadClass 调用量骤降，所以"只有
     *                                      第一次崩"——和现象完全吻合。
     *   删掉 Runtime.exit / Process.killProcess 钩子 ← 易盾有反调试，检测到 exit 被劫持
     *                                      可能直接 native abort（这正解释了"无 Java 栈"）。
     *   删掉 Thread.setDefaultUncaughtExceptionHandler 钩子 ← 同类反调试面。
     *   删掉 View.setVisibility 全局钩子 ← 全 App 每一次可见性变化都过桥，且要等 Banner
     *                                      类加载才装，性价比最低。
     *
     * 留下的三样，全部是「只读参数、命中才动手、不影响壳」：
     *   1. DNS 层拦截（InetAddress）—— 用户 adguardhome 实测同一失败方式有效且不崩；
     *   2. URL/OkHttp 层改写 —— 兜住绕过 DNS 的 HTTPDNS 直连；
     *   3. LayoutInflater.inflate 后按布局名/控件 id 隐藏 —— 纯 after 钩子，只 setVisibility。
     *
     * OkHttp 的安装（需要 findClass）也不再抢在启动期，改为首次 onResume 之后在后台
     * 线程慢慢试，彻底避开壳的解密窗口。
     */

    /**
     * 启动后多久开始在后台尝试装 OkHttp 钩子（毫秒）。
     * 从「第一次 Activity.onResume」起算 —— 那时壳必然已经解密完毕。
     */
    public static final long OKHTTP_INSTALL_DELAY_MS = 2500L;

    /** 后台尝试装 OkHttp 的间隔与次数上限（1000ms × 60 = 1 分钟） */
    public static final long OKHTTP_RETRY_MS = 1000L;

    public static final int OKHTTP_MAX_TRY = 60;

    /**
     * DNS 层拦截（v1.5 新增，推荐）。
     *
     * 命中黑名单域名时直接让 InetAddress 抛 UnknownHostException —— URL 一个字节不动，
     * 与 AdGuard Home 的失败方式完全一致。用户的实测证明这种方式既能去掉开屏广告，
     * 也不会让 SDK 走进未定义分支。
     */
    public static final boolean BLOCK_VIA_DNS = true;

    /**
     * URL 改写兜底（v1.5 调整）。
     *
     * 只把 host/port 换成本机未监听端口，协议、路径、query 全部保留。
     * 主要用来兜住走 HTTPDNS（自己解析 IP 直连）从而绕过 DNS 拦截的 SDK。
     */
    public static final boolean REWRITE_URL = true;

    /**
     * 启动追踪（v1.4 新增）：在启动关键路径打点，定位"首启闪退"到底死在哪一步。
     * 日志形如 `[启动+1234ms] 壳 attachBaseContext 完成`，排查完可关。
     */
    public static final boolean BOOT_TRACE = true;

    /**
     * 类监听是否延后安装（v1.4 新增，首启闪退的关键修复）。
     *
     * 崩溃只在**第一次**启动出现、第二次正常，直接指向加固壳的**首启解密流程**：
     * 首次启动时 MyApplication.attachBaseContext 要在主线程解密 106MB dex 并替换
     * ClassLoader，而 v1.3 及以前是进程一 fork 就挂上 ClassLoader.loadClass ——
     * 等于在壳动手术的时候把手术刀按住，壳直接崩掉；第二次启动走解密缓存，不碰 ClassLoader，
     * 所以没事。这与"只有首启崩"完全吻合。
     *
     * 打开后：类监听改为在主线程 postDelayed 再装。壳解密期间主线程被占满，
     * 这条消息自然排在解密之后执行，等于自动等到壳忙完再动手。
     */
    public static final boolean DEFER_CLASS_WATCHER = true;

    /** 类监听的延后时长（毫秒）。主线程忙时 postDelayed 会自动顺延，不会提前。 */
    public static final long WATCH_DELAY_MS = 800L;

    /** 延后到点但 Application 还没创建好时，每隔多久重试一次 */
    public static final long WATCH_RETRY_MS = 200L;

    /** 最多重试多少次（200ms × 30 = 6 秒），超时就直接装，不能一直等 */
    public static final int WATCH_MAX_RETRY = 30;

    /** 短路 SDK 方法后是否回调监听器的 onError，让 App 立刻走失败分支 */
    public static final boolean NOTIFY_AD_LISTENER = true;

    /** 单个方法的日志上限，防止刷屏 */
    public static final int MAX_LOG_PER_METHOD = 20;

    // ------------------------------------------------------------------ 域名

    /**
     * 广告服务器黑名单（已在 APK 字符串里逐条确认）。
     * 匹配规则：host 完全相等或以 "." + 域名 结尾。
     *
     * 注意：api/wap/static/report.gongkaoleida.com 是 App 自己的业务域名，绝对不要加进来。
     */
    public static final String[] HOSTS = {
            // TopOn 聚合平台（配置下发 + 竞价 + 上报），挡掉后聚合不再返回任何广告
            "anythinktech.com",

            // 穿山甲 / Pangle / GroMore
            "pangolin-sdk-toutiao.com",
            "pangolin-sdk-toutiao-b.com",
            "pangolin-sdk-toutiao1.com",
            "pglstatp-toutiao.com",
            "oceanengine.com",
            "bytesfield.com",
            "bytesfield-b.com",
            "bytesmanager.com",
            "ad.toutiao.com",
            "phoniex.toutiao.com",
            "chengzijianzhan.com",
            "toutiaopage.com",

            // 优量汇 / 腾讯广告
            "gdt.qq.com",
            "pgdt.gtimg.cn",

            // 快手联盟
            "ad.kuaishou.com",
            "e.kuaishou.com",
            "kwaicdn.com",
            "ksadcn.com",

            // 百度联盟
            "mobads.baidu.com",
            "mobads-logs.baidu.com",
            "cpro.baidustatic.com",

            // 倍孜（BeiZi Fusion）
            "adscope.com",

            // 美数（Meishu SDK，素材域名 medproad）
            "medproad.com",

            // FL SaaS / 1rtb
            "1rtb.com",
            "1rtb.net",

            // ubix SSP —— App 的开屏自定义适配器 com.maidu.gkld.ubix.MerakCustomSplashAdapter 走这家
            "ubixioe.com",

            // Gameley 模板广告
            "gameley.com",

            // 阿里妈妈 / Tanx 广告上报
            "statisticslinks.com",
            "tanx.com",
            "adn.alibaba.com",

            // 华为 ADS
            "ppsc.hicloud.com",
            "ads.huawei.com",
    };

    /** 自己抓包发现的补充域名 */
    public static final String[] EXTRA_HOSTS = {
            // "ad.example.com",
    };

    /**
     * 按 path 关键字拦截（针对和业务接口同域的自营广告接口）。
     * 默认关闭：公考雷达的自营广告接口和业务接口都在 api.gongkaoleida.com，误杀代价高。
     */
    public static final boolean BLOCK_PATH_KEYWORDS = false;

    public static final String[] PATH_KEYWORDS = {
            "/api/ad/", "/api/ads/", "/splash/ad"
    };

    // -------------------------------------------------------------- 方法级拦截

    /**
     * {类名, 方法名...}：这些方法被调用时直接短路返回失败。
     * 只放"信息流 / Banner / 插屏"这类——拦了不会卡住 UI。
     *
     * 开屏广告刻意不在这里：聚合 SDK 的 loadAd 被跳过就不会启动内部超时，
     * 万一 App 自己也没兜底计时器，开屏页会一直转圈。开屏交给域名拦截，
     * 连接被拒后 SDK 会立刻走 onAdLoadFail，既快又安全。
     */
    public static final String[][] BLOCK_METHODS = {
            // TopOn 聚合
            {"com.anythink.nativead.api.ATNative", "loadAd", "prepareAd"},
            {"com.anythink.banner.api.ATBannerView", "loadAd"},
            {"com.anythink.interstitial.api.ATInterstitial", "loadAd"},

            // 倍孜
            {"com.beizi.fusion.NativeUnifiedAd", "loadAd"},
            {"com.beizi.fusion.NativeAd", "loadAd"},
            {"com.beizi.fusion.InterstitialAd", "loadAd"},

            // 美数
            {"com.meishu.sdk.core.ad.banner.BannerAdLoader", "loadAd", "load"},
            {"com.meishu.sdk.core.ad.interstitial.InterstitialAdLoader", "loadAd", "load"},
            {"com.meishu.sdk.core.ad.draw.DrawAdLoader", "loadAd", "load"},
            {"com.meishu.sdk.core.ad.recycler.RecyclerMixAdLoader", "loadAd", "load"},
            {"com.meishu.sdk.core.ad.paster.PasterAdLoader", "loadAd", "load"},

            // FL SaaS
            {"com.fl.saas.adx.api.FLBanner", "loadAd", "load"},
            {"com.fl.saas.adx.api.FLInterstitial", "loadAd", "load"},
            {"com.fl.saas.adx.api.mixNative.MixNativeAdManager", "loadAd", "load"},

            // 联盟 SSP
            {"com.alliance.ssp.ad.api.banner.SABannerAd", "loadAd"},
            {"com.alliance.ssp.ad.api.interstitial.SAInterstitialAd", "loadAd"},
            {"com.alliance.ssp.ad.api.expressfeed.SAExpressFeedAd", "loadAd"},

            // Gameley 模板广告
            {"com.gameley.templatesdk.ad.api.TemplateAdLoader", "loadAd", "load"},

            // OPPO 广告
            {"com.heytap.msp.mobad.api.ad.BannerAd", "loadAd"},
            {"com.heytap.msp.mobad.api.ad.InterstitialAd", "loadAd"},
            {"com.heytap.msp.mobad.api.ad.NativeTempletAd", "loadAd"},

            // 百度
            {"com.baidu.mobads.sdk.api.BaiduNativeManager", "loadAd", "loadFeedAd"},
            {"com.baidu.mobads.sdk.api.ExpressInterstitialAd", "loadAd"},
    };

    /**
     * 开屏类：v1.0 只记日志不拦截，实测无效（缓存素材照样播、竞价上报被拦也不影响展示）。
     * v1.1 改成直接短路 loadAd → 再给 show 兜一刀（万一有缓存 ad 对象，跳过展示并结束宿主 Activity）。
     */
    public static final String[] SPLASH_CLASSES = {
            "com.anythink.splashad.api.ATSplashAd",
            "com.beizi.fusion.UnifiedSplashAd",
            "com.beizi.fusion.SplashAd",
            "com.baidu.mobads.sdk.api.SplashAd",
            "com.huawei.hms.ads.splash.SplashAd",
            "com.heytap.msp.mobad.api.ad.SplashAd",
            "com.gameley.templatesdk.ad.api.SplashAd",
            "com.alliance.ssp.ad.api.splash.SASplashAd",
    };

    // ---------------------------------------------------------------- App 层

    /**
     * 自营信息流广告卡片（AdV2Bean 下发的广告位用它渲染）。
     * 绑定数据时把 itemView 设为 GONE + 高度 0，卡片直接消失。
     */
    public static final String[] FEED_VIEW_HOLDERS = {
            "com.maidu.gkld.adapter.ad.AdCenterViewHolder",
    };

    // ------------------------------------------------------------ 布局级

    /**
     * 按布局名隐藏自营运营位（v1.1 新增）。
     *
     * 这些卡片的数据来自业务接口 api.gongkaoleida.com，域名拦截碰不到、类名也无规律，
     * 但布局名稳定（AndResGuard 只混淆文件名，resources.arsc 里的名字保留）：
     *
     *   float_view / float_view_nor / float_iamge_view / float_initention_view
     *       —— 悬浮红包球（含 Lottie 动画），就是首页右下角「买季卡送月卡」那个
     *   item_operation_banner
     *       —— 三行文案的会员促销卡（标题 16sp 加粗 / 副标题 12sp / 价格 14sp），
     *          对应「月末特惠 · VIP买季卡送月卡 139元=4个月」
     *   list_item_third_ad_banner / item_home_ad_banner
     *       —— 列表内和首页的三方广告位（Banner / BannerHomeInfor 容器）
     *
     *   activity_three_ad                          （v1.2 新增）
     *       —— 三方广告落地页，整页都是广告
     *
     * 注意：v1.2 曾误把 home_article_head_card_exam_banner（首页「精选考试」轮播）加进来，
     * 那是正常内容不是广告，v1.3 已移除 —— 不要再加回去。
     */
    public static final String[] LAYOUT_BLOCKLIST = {
            "float_view",
            "float_view_nor",
            "float_iamge_view",
            "float_initention_view",
            "item_operation_banner",
            "list_item_third_ad_banner",
            "item_home_ad_banner",
            "activity_three_ad",
    };

    /**
     * 名字像广告的布局会被打到日志（只记一次），方便下一轮补进黑名单。
     *
     * v1.2：去掉裸 "ad" —— 它会把 head / loading / read / shadow 全部误报（日志里那堆
     * home_article_head_card_xxx 就是这么来的），改成带下划线边界的写法。
     */
    public static final String[] LAYOUT_PROBE_KEYWORDS = {
            "_ad", "ad_", "advert", "splash_ad", "vip", "float",
            "promotion", "operation", "coupon", "red_packet", "banner_entry",
    };

    /**
     * 按「控件 id」隐藏的广告轮播（v1.3 新增）。
     *
     * 这类轮播不是独立布局，而是 fragment_user_center.xml 里直接写的
     * com.maidu.gkld.view.banner.Banner 控件，所以按布局名（inflate）根本抓不到 ——
     * 这也解释了为什么 v1.1/v1.2 的 item_operation_banner 一直没命中。
     *
     * id 取自 resources.arsc（Apk 6.7.3.1，已逐个核对）：
     *   0x7f090183 = id/banner_user_center  ← 「我的」页那条 80dp、带 4dp 轮播点、
     *                                          右上角标「广告」的「月末特惠 限时月抢」
     *   0x7f090177 = id/banner_ad           ← 名字即广告位
     * 注意：不要加 0x7f09017b(id/banner_exam_area)，那是「考试区域」内容轮播。
     */
    public static final int[] BLOCK_VIEW_IDS = {
            0x7f090183,
            0x7f090177,
    };

    /** 承载上面这些轮播的控件类（App 自己的轮播控件，不是第三方 SDK 的） */
    public static final String BANNER_CLASS = "com.maidu.gkld.view.banner.Banner";

    /**
     * 布局追踪（v1.3 新增）：把 App inflate 过的每个布局名都打一条日志（去重，不会刷屏）。
     *
     * 用途：定位还没被盖住的运营位 —— 先看它加载的是哪个布局名，再加进 LAYOUT_BLOCKLIST。
     * v1.4 起默认关闭：App 启动要 inflate 几百个布局，每条日志都在跨进程写 LSPosed 日志文件，
     * 首启本来就在解密 dex，这份额外开销没有理由再留着。
     */
    public static final boolean LAYOUT_TRACE_ALL = false;

    /** 是否隐藏自营信息流广告卡片 */
    public static final boolean HIDE_FEED_AD_ITEM = true;

    /**
     * 视图层总开关（v1.6 新增）。
     *
     * 万一 v1.6 仍然闪退，说明问题出在 inflate / onResume 这两个钩子上，
     * 把这两个开关都关掉就退化成「纯网络层」版本 —— 开屏和第三方 SDK 广告照样干净，
     * 只是「我的」页那条自营广告轮播会回来。改完重打包即可，不需要动其它代码。
     */
    public static final boolean HIDE_LAYOUTS = true;

    public static final boolean HIDE_BY_VIEW_ID = true;

    /**
     * 打开 setVisibility 兜底（v1.7，默认开）。
     *
     * 这是 v1.3 唯一被真机验证过「能让『我的』页那条轮播消失」的手段：直接 hook
     * View.setVisibility，命中广告控件 id 就吞掉那次显示。
     *
     * v1.6 曾用 OnPreDraw 监听替代，实测无效 —— 推测是那个轮播根本不在 inflate
     * 出来的视图树里（App 可能用 addView 动态挂上去），findViewById 从头到尾返回 null，
     * 监听器自然没机会挂。
     *
     * 这里不再需要 Banner 类做 isInstance 判断：0x7f090183 / 0x7f090177 这两个 id
     * 唯一对应那两个控件，按 id 精确匹配就够，不用担心误伤。
     */
    public static final boolean BLOCK_VIEW_ID_VIA_SETVISIBILITY = true;

    /**
     * 启动后是否弹一条 Toast 说明注入状态（v1.7.1 起默认关闭）。
     *
     * v1.7.0 排障期间开着，真机验证通过后关闭。
     * 需要再排查时改回 true 重打包即可。
     */
    public static final boolean SHOW_TOAST = false;
}
