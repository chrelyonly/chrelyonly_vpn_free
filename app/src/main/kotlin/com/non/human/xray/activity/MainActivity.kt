package com.non.human.xray.activity

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cn.hutool.http.HttpRequest
import com.non.human.xray.data.OutboundNode
import com.non.human.xray.data.Subscription
import com.non.human.xray.parser.SubscriptionParser
import com.non.human.xray.service.XrayVpnService
import com.non.human.xray.store.AppRepository

class MainActivity : AppCompatActivity() {
    private lateinit var repository: AppRepository
    private lateinit var content: FrameLayout
    private lateinit var homeButton: Button
    private lateinit var nodesButton: Button

    private var selectedTab = 0
    private var activePageView: View? = null
    private var tabSwitchAnimationToken = 0
    private val pageCache = mutableMapOf<Int, View>()
    private var pendingStartNode: OutboundNode? = null
    private var currentStatus = VpnStatus()
    private var nodeKeyword = ""
    private var vpnActionInProgress = false
    private var vpnActionTarget: Boolean? = null
    private var vpnTapLocked = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val previousStatus = currentStatus
            val nextStatus = VpnStatus(
                vpn = intent?.getBooleanExtra("vpn", false) == true,
            )
            currentStatus = nextStatus
            var finishedPendingAction = false
            if (vpnActionTarget == nextStatus.vpn) {
                vpnActionInProgress = false
                vpnActionTarget = null
                vpnTapLocked = false
                finishedPendingAction = true
            }
            if (selectedTab == TAB_HOME && (previousStatus != nextStatus || finishedPendingAction)) {
                render()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AppRepository(this).also { it.load() }
        currentStatus = currentStatus.copy(vpn = isServiceRunning(XrayVpnService::class.java))
        requestNotificationPermissionIfNeeded()
        buildRoot()
        registerAppReceiver(statusReceiver, IntentFilter(ACTION_STATUS))
        render()
        autoUpdateBuiltInSubscription()
    }



    override fun onDestroy() {
        runCatching { unregisterReceiver(statusReceiver) }
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            val node = pendingStartNode
            pendingStartNode = null
            if (resultCode == RESULT_OK && node != null) {
                runVpnTransition(targetVpn = true) { startVpn(node) }
            } else {
                vpnTapLocked = false
                vpnActionInProgress = false
                vpnActionTarget = null
                toast("未授予 VPN 权限")
            }
        }
    }

    private fun buildRoot() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = appBackground()
            fitsSystemWindows = true
        }

        content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = glassBackground(18)
            elevation = dp(6).toFloat()
        }
        homeButton = navButton("首页", TAB_HOME)
        nodesButton = navButton("节点", TAB_NODES)
        nav.addView(homeButton)
        nav.addView(nodesButton)

        val navWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(8))
            background = appBackground()
            addView(nav)
            addView(
                appInfoFooter(),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(5)
                },
            )
        }

        root.addView(content)
        root.addView(navWrap)
        setContentView(root)
    }

    private fun appInfoFooter(): TextView {
        return TextView(this).apply {
            text = appInfoText()
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_SOFT_TEXT)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = 0.82f
        }
    }

    private fun appInfoText(): String {
        return runCatching {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            "版本 v${packageInfo.versionName ?: "-"} ($versionCode) · 包名 $packageName"
        }.getOrElse {
            "包名 $packageName"
        }
    }

    private fun navButton(label: String, tab: Int): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            setOnClickListener {
                switchTab(tab)
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }
    }

    private fun switchTab(tab: Int, refreshTarget: Boolean = false) {
        if (tab !in TAB_HOME..TAB_NODES) return
        if (tab == selectedTab) {
            render(forceRebuild = refreshTarget)
            return
        }

        val previousTab = selectedTab
        selectedTab = tab
        render(forceRebuild = refreshTarget, animate = true, previousTab = previousTab)
    }

    private fun render(
        forceRebuild: Boolean = true,
        animate: Boolean = false,
        previousTab: Int = selectedTab,
    ) {
        updateNavState()
        val nextPage = pageForTab(selectedTab, forceRebuild)
        val currentPage = activePageView ?: content.getChildAt(0)
        if (currentPage === nextPage && nextPage.parent === content) return

        if (!animate || currentPage == null || content.width <= 0) {
            showPageImmediately(nextPage)
            return
        }

        animatePageSwitch(currentPage, nextPage, previousTab)
    }

    private fun pageForTab(tab: Int, forceRebuild: Boolean): View {
        if (forceRebuild) {
            invalidatePage(tab)
        }
        return pageCache.getOrPut(tab) {
            buildPage(tab).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isClickable = true
            }
        }
    }

    private fun buildPage(tab: Int): View {
        return when (tab) {
            TAB_NODES -> nodesPage()
            else -> homePage()
        }
    }

    private fun showPageImmediately(page: View) {
        tabSwitchAnimationToken++
        page.animate().cancel()
        detachFromParent(page)
        content.removeAllViews()
        page.translationX = 0f
        page.alpha = 1f
        page.setLayerType(View.LAYER_TYPE_NONE, null)
        content.addView(page)
        activePageView = page
    }

    private fun animatePageSwitch(currentPage: View, nextPage: View, previousTab: Int) {
        val token = ++tabSwitchAnimationToken
        val width = content.width.toFloat()
        val direction = if (selectedTab > previousTab) 1f else -1f
        val interpolator = DecelerateInterpolator(1.7f)

        currentPage.animate().cancel()
        nextPage.animate().cancel()
        detachFromParent(nextPage)

        nextPage.translationX = width * direction
        nextPage.alpha = 0.98f
        nextPage.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        currentPage.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        content.addView(nextPage)
        nextPage.bringToFront()
        activePageView = nextPage

        currentPage.animate()
            .translationX(-width * 0.26f * direction)
            .alpha(0.72f)
            .setDuration(TAB_SWITCH_ANIMATION_MS)
            .setInterpolator(interpolator)
            .withEndAction {
                if (token != tabSwitchAnimationToken) return@withEndAction
                detachFromParent(currentPage)
                currentPage.translationX = 0f
                currentPage.alpha = 1f
                currentPage.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            .start()

        nextPage.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(TAB_SWITCH_ANIMATION_MS)
            .setInterpolator(interpolator)
            .withEndAction {
                if (token != tabSwitchAnimationToken) return@withEndAction
                nextPage.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            .start()
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun invalidatePage(tab: Int) {
        val oldPage = pageCache.remove(tab) ?: return
        oldPage.animate().cancel()
        detachFromParent(oldPage)
        if (activePageView === oldPage) {
            activePageView = null
        }
    }

    private fun invalidateAllPages() {
        pageCache.keys.toList().forEach(::invalidatePage)
    }

    private fun updateNavState() {
        listOf(homeButton to TAB_HOME, nodesButton to TAB_NODES)
            .forEach { (button, tab) ->
                val selected = selectedTab == tab
                button.setTextColor(if (selected) COLOR_BRAND else COLOR_SOFT_TEXT)
                button.background = if (selected) {
                    rounded(COLOR_NAV_INDICATOR, 12, COLOR_GLASS_BORDER)
                } else {
                    rounded(Color.TRANSPARENT, 12)
                }
            }
    }

    private fun homePage(): View {
        val node = repository.selectedNode
        return scroll(horizontal = 18, top = 14, bottom = 24) {
            addView(heroCard())
//            addGap(14)
//            addView(proxyModePanel())
            addGap(22)
            addView(connectionCore(node))
        }
    }

    private fun heroCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = gradientRounded(
                intArrayOf(COLOR_SURFACE, COLOR_HERO_MID_GLASS, COLOR_HERO_END_GLASS),
                18,
                COLOR_GLASS_BORDER,
            )
            elevation = dp(4).toFloat()

            addView(kawaiiRibbon("仙界庇佑", "已开启宗门护宗大阵"))
            addGap(12)
            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(context).apply {
                        text = "宗门内院"
                        textSize = 26f
                        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                        setTextColor(COLOR_INK)
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(stickerBadge("抵御域外邪魔"))
            }
            addView(titleRow)
        }
    }



    private fun connectionCore(node: OutboundNode?): View {
        return panel(radius = 18, padding = 18) {
            orientation = LinearLayout.VERTICAL
            addView(kawaiiRibbon("仙界庇佑", "自动共享传送点：  本机IP：10808"))
            addGap(6)

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    View(context).apply {
                        background = rounded(if (currentStatus.vpn) COLOR_SUCCESS else COLOR_SOFT_TEXT, 999)
                    },
                    LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) },
                )
                addView(
                    label(if (currentStatus.vpn) "隧道运行中" else "隧道待启动", 18f, true, COLOR_INK, maxLines = 1),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    pill(if (repository.proxyMode == 0) "天道庇佑" else "宗门大恐怖庇佑"),
                )
            }
            addView(header)
            addGap(12)

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = gradientRounded(
                        intArrayOf(COLOR_SURFACE, COLOR_STRAWBERRY_MILK),
                        14,
                        COLOR_GLASS_BORDER,
                    )
                    addView(cuteAccentDots())
                    addGap(8)
                    addView(label("宗门传送点", 11f, true, COLOR_SOFT_TEXT, maxLines = 1))
                    addGap(4)
                    addView(label(node?.name ?: "未选择传送点", 15f, true, COLOR_INK, maxLines = 2))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addGap(14)

            addView(
                vpnActionButton(),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)),
            )
            if (currentStatus.vpn && !vpnActionInProgress) {
                addGap(10)
                addView(
                    actionButton("测试连通性", false) { testConnectivity() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)),
                )
            }
            addGap(10)
            addView(
                label(
                    when {
                        vpnActionInProgress && vpnActionTarget == true -> "消灭小恐怖中..."
                        vpnActionInProgress -> "正在赶回宗门..."
                        currentStatus.vpn -> "自动清除小恐怖中..."
                        else -> "小恐怖出没,快使用宗门大阵"
                    },
                    12f,
                    true,
                    COLOR_SOFT_TEXT,
                    gravity = Gravity.CENTER,
                    maxLines = 1,
                )
            )
        }
    }

    private fun testConnectivity() {
        if (!currentStatus.vpn) {
            toast("请先连接后再测试")
            return
        }
        startService(Intent(this, XrayVpnService::class.java).apply { action = "TEST_SPEED" })
    }

    private fun vpnActionButton(): View {
        val transitioning = vpnActionInProgress
        val targetVpn = vpnActionTarget ?: currentStatus.vpn
        val dangerAction = if (transitioning) !targetVpn else currentStatus.vpn
        val buttonText = when {
            transitioning && targetVpn -> "护宗大阵开启中..."
            transitioning -> "关闭护宗大阵..."
            currentStatus.vpn -> "正在自动消灭危险因素...."
            else -> "小恐怖正在侵蚀你的灵魂"
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            isEnabled = !transitioning && !vpnTapLocked
            background = gradientRounded(
                if (dangerAction) {
                    intArrayOf(COLOR_DANGER, COLOR_BRAND_DARK)
                } else {
                    intArrayOf(COLOR_BRAND, COLOR_BRAND_DARK)
                },
                999,
                if (dangerAction) COLOR_DANGER else COLOR_BRAND,
            )

            if (transitioning) {
                addView(
                    ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                        isIndeterminate = true
                        indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
                    },
                    LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) },
                )
            }

            addView(
                TextView(context).apply {
                    text = buttonText
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    maxLines = 1
                },
            )

            setOnClickListener {
                if (!vpnActionInProgress && !vpnTapLocked) {
                    animateVpnButtonTap(this)
                }
            }
        }
    }

    private fun nodesPage(): View {
        val subscription = repository.activeSubscription
        val nodes = subscription?.nodes.orEmpty()
        val filteredNodes = nodes.mapIndexedNotNull { index, node ->
            if (
                nodeKeyword.isBlank() ||
                node.name.contains(nodeKeyword, ignoreCase = true) ||
                node.protocol.contains(nodeKeyword, ignoreCase = true)
            ) {
                index to node
            } else {
                null
            }
        }

        return scroll(horizontal = 14, top = 12, bottom = 24) {
            addView(pageTitle("列表"))
            addGap(8)
            addView(
                actionButton("更新内置订阅", true) { autoUpdateBuiltInSubscription() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)),
            )
            addGap(8)

            if (subscription == null) {
                addView(emptyPanel("还没有灵卷", "正在使用内置订阅，更新后会自动解析节点。"))
                return@scroll
            }

            addView(subscriptionHeader(subscription.name))
            addGap(8)
            addGap(10)

            if (filteredNodes.isEmpty()) {
                addView(emptyPanel("没有匹配的节点", "可以调整搜索词，或重新同步订阅后再试。"))
            } else {
                addView(nodeGrid(filteredNodes))
            }
        }
    }

    private fun pageTitle(title: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(COLOR_SURFACE, 16, COLOR_GLASS_BORDER)
            elevation = dp(2).toFloat()
            addView(cuteDot(COLOR_BRAND), LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
            addView(
                TextView(context).apply {
                    text = title
                    textSize = 21f
                    typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                    setTextColor(COLOR_INK)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(stickerBadge("MOE"))
        }
    }

    private fun subscriptionHeader(name: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(COLOR_SURFACE, 14, COLOR_GLASS_BORDER)
            elevation = dp(2).toFloat()

            addView(
                TextView(context).apply {
                    text = "◎"
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLOR_BRAND)
                    gravity = Gravity.CENTER
                    background = rounded(COLOR_GLASS, 10, COLOR_GLASS_BORDER)
                },
                LinearLayout.LayoutParams(dp(28), dp(28)),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label("当前灵卷", 11f, true, COLOR_SOFT_TEXT))
                    addView(label(name, 12f, true, COLOR_INK, maxLines = 1))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                },
            )
        }
    }


    private fun nodeGrid(nodes: List<Pair<Int, OutboundNode>>): View {
        return GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = false
            nodes.forEach { (originalIndex, node) ->
                addView(nodeCard(node, originalIndex))
            }
        }
    }

    /**
     * 节点卡片
     */
    private fun nodeCard(node: OutboundNode, originalIndex: Int): View {
        val selected = originalIndex == repository.selectedNodeIndex
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = if (selected) {
                gradientRounded(intArrayOf(COLOR_NAV_INDICATOR, COLOR_SURFACE), 14, COLOR_BRAND)
            } else {
                rounded(COLOR_SURFACE, 14, COLOR_GLASS_BORDER)
            }
            elevation = dp(if (selected) 4 else 2).toFloat()
            setOnClickListener {
                repository.selectNode(originalIndex)
                repository.selectedNode?.takeIf { currentStatus.vpn }?.let { restartCore(it) }
                invalidatePage(TAB_HOME)
                render()
            }

            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(node.name, 9.2f, true, COLOR_INK, maxLines = 1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
//                addView(stickerBadge(if (selected) "激活" else ""))
            }
            addView(titleRow)
            addGap(5)
            layoutParams = GridLayout.LayoutParams().apply {
                width = ((resources.displayMetrics.widthPixels - dp(40)) / 2)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }
    }

    private fun subscriptionsPage(): View {
        return scroll(horizontal = 14, top = 12, bottom = 24) {
            addView(pageTitle("订阅中心"))
            addGap(8)
            addView(subscriptionTopActions())
            addGap(8)
            addView(subscriptionHero())
            addGap(10)

            if (repository.subscriptions.isEmpty()) {
                addView(emptyPanel("还没有灵卷", "添加订阅链接或粘贴配置，导入后会自动解析为节点。", "立即导入") {
                    showAddSubscriptionDialog()
                })
            } else {
                repository.subscriptions.forEachIndexed { index, subscription ->
                    addView(subscriptionCard(index, subscription))
                }
            }
        }
    }

    private fun subscriptionTopActions(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(1), dp(1), dp(1), dp(1))
            addView(
                actionButton("添加订阅", true) { showAddSubscriptionDialog() },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) },
            )
            addView(
                actionButton("同步全部", false) { syncAllSubscriptions() },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) },
            )
        }
    }

    private fun showAddSubscriptionDialog() {
        val nameInput = EditText(this).apply {
            hint = "订阅名称"
            isSingleLine = true
            textSize = 14f
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_SOFT_TEXT)
            background = rounded(COLOR_INPUT_GLASS, 14, COLOR_GLASS_BORDER)
            setPadding(dp(14), 0, dp(14), 0)
        }
        val contentInput = EditText(this).apply {
            hint = "订阅 URL 或分享链接"
            isSingleLine = false
            minLines = 3
            maxLines = 8
            textSize = 14f
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_SOFT_TEXT)
            background = rounded(COLOR_INPUT_GLASS, 14, COLOR_GLASS_BORDER)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(2))
            addView(nameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addGap(12)
            addView(contentInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("添加订阅")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                hideKeyboard()
                val raw = contentInput.text.toString().trim()
                val name = nameInput.text.toString().trim().ifBlank { "订阅 ${repository.subscriptions.size + 1}" }
                if (raw.isBlank()) {
                    toast("请输入订阅 URL 或分享链接")
                    return@setOnClickListener
                }
                dialog.dismiss()
                if (raw.startsWith("http://") || raw.startsWith("https://")) {
                    fetchAndStoreSubscription(name, raw)
                } else {
                    importManualContent(name, raw)
                }
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(glassBackground(24))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(COLOR_BRAND)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(COLOR_SOFT_TEXT)
    }

    private fun subscriptionHero(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = rounded(COLOR_SURFACE, 16, COLOR_GLASS_BORDER)
            elevation = dp(2).toFloat()

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(kawaiiRibbon("SUBSCRIBE", "灵卷收纳中"))
                    addGap(7)
                    addView(label("统一管理灵卷导入、同步与切换", 13.5f, true, COLOR_INK, maxLines = 1))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(pill("${repository.subscriptions.size} 灵卷"))
        }
    }

    private fun subscriptionCard(index: Int, subscription: Subscription): View {
        val active = index == repository.activeSubscriptionIndex
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = if (active) {
                gradientRounded(intArrayOf(COLOR_NAV_INDICATOR, COLOR_SURFACE), 14, COLOR_BRAND)
            } else {
                rounded(COLOR_SURFACE, 14, COLOR_GLASS_BORDER)
            }
            elevation = dp(if (active) 4 else 2).toFloat()
            setOnClickListener {
                repository.selectSubscription(index)
                toast("当前订阅 ${subscription.name}")
                invalidatePage(TAB_HOME)
                invalidatePage(TAB_NODES)
                render()
            }

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(context).apply {
                        text = "☁"
                        textSize = 18f
                        setTextColor(COLOR_BRAND)
                        gravity = Gravity.CENTER
                        background = rounded(COLOR_BRAND_LIGHT, 13, COLOR_GLASS_BORDER)
                    },
                    LinearLayout.LayoutParams(dp(36), dp(36)),
                )
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(subscription.name, 12f, true, COLOR_INK, maxLines = 1))
                        addView(label(subscription.url, 9.5f, true, COLOR_SOFT_TEXT, maxLines = 1))
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(9)
                    },
                )
                addView(stickerBadge(if (active) "USING" else "READY"))
            }
            addView(header)
            addGap(9)

            val tags = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pill("${subscription.nodes.size} 个节点"))
                addView(pill(if (subscription.url.startsWith("manual://")) "本地配置" else "远程订阅"))
                addView(pill(formatTime(subscription.lastUpdateMillis)))
            }
            addView(tags)
            addGap(10)

            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    smallButton("使用") {
                        repository.selectSubscription(index)
                        invalidatePage(TAB_HOME)
                        invalidatePage(TAB_NODES)
                        render()
                    },
                    LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) },
                )
                addView(
                    smallButton("同步", primary = true) {
                        if (subscription.url.startsWith("manual://")) {
                            toast("本地配置无需同步")
                        } else {
                            fetchAndStoreSubscription(subscription.name, subscription.url)
                        }
                    },
                    LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                        marginStart = dp(4)
                        marginEnd = dp(4)
                    },
                )
                addView(
                    smallButton("删除", danger = true) {
                        if (index == repository.activeSubscriptionIndex) {
                            repository.removeActiveSubscription()
                        } else {
                            repository.selectSubscription(index)
                            repository.removeActiveSubscription()
                        }
                        invalidateAllPages()
                        render()
                    },
                    LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) },
                )
            }
            addView(actions)

            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun toggleVpn() {
        if (vpnActionInProgress) return
        if (currentStatus.vpn || isServiceRunning(XrayVpnService::class.java)) {
            runVpnTransition(targetVpn = false) { stopVpn() }
            return
        }
        val node = repository.selectedNode
        if (node == null) {
            toast("请先导入订阅并选择节点")
            autoUpdateBuiltInSubscription()
            return
        }
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            pendingStartNode = node
            startActivityForResult(permissionIntent, VPN_REQUEST_CODE)
        } else {
            runVpnTransition(targetVpn = true) { startVpn(node) }
        }
    }

    private fun startVpn(node: OutboundNode) {
        val intent = serviceIntent(node)
        startForegroundService(intent)
        currentStatus = currentStatus.copy(vpn = true)
        vpnActionInProgress = false
        vpnActionTarget = null
        vpnTapLocked = false
        render()
    }

    private fun stopVpn() {
        startService(Intent(this, XrayVpnService::class.java).apply { action = "STOP_VPN" })
        currentStatus = VpnStatus()
        vpnActionInProgress = false
        vpnActionTarget = null
        vpnTapLocked = false
        render()
    }

    private fun runVpnTransition(targetVpn: Boolean, action: () -> Unit) {
        vpnActionInProgress = true
        vpnActionTarget = targetVpn
        render()
        content.postDelayed({ action() }, VPN_TRANSITION_DELAY_MS)
    }

    private fun animateVpnButtonTap(view: View) {
        if (vpnTapLocked || vpnActionInProgress) return
        vpnTapLocked = true
        view.isEnabled = false
        view.animate().cancel()
        view.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .alpha(0.88f)
            .setDuration(VPN_TAP_PRESS_MS)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(VPN_TAP_RELEASE_MS)
                    .withEndAction {
                        vpnTapLocked = false
                        toggleVpn()
                    }
                    .start()
            }
            .start()
    }

    /**
     * 重启核心
     */
    private fun restartCore(node: OutboundNode) {
        startService(serviceIntent(node, "RESTART_CORE"))
        toast("已切换到 ${node.name}")
    }


    private fun serviceIntent(node: OutboundNode, actionName: String? = null): Intent {
        return Intent(this, XrayVpnService::class.java).apply {
            actionName?.let { action = it }
            putExtra("CONFIG_OUTBOUND_JSON", node.outboundJson)
        }
    }

    private fun autoUpdateBuiltInSubscription() {
        fetchAndStoreSubscription(
            BUILT_IN_SUBSCRIPTION_NAME,
            BUILT_IN_SUBSCRIPTION_URL,
            switchToNodesOnSuccess = false,
            startMessage = "正在自动更新内置订阅...",
            successPrefix = "内置订阅已更新",
            failurePrefix = "内置订阅更新失败",
        )
    }

    private fun fetchAndStoreSubscription(
        name: String,
        url: String,
        switchToNodesOnSuccess: Boolean = true,
        startMessage: String = "正在更新订阅...",
        successPrefix: String = "已更新",
        failurePrefix: String = "更新失败",
    ) {
        toast(startMessage)

        Thread {
            try {
                val content = HttpRequest.get(url)
                    .timeout(10000)
                    .header("User-Agent", "XrayPlusNative/1.0")
                    .execute()
                    .body()

                val nodes = SubscriptionParser.parse(content)

                if (nodes.isEmpty()) {
                    throw RuntimeException("订阅中没有解析到节点")
                }

                val subscription = Subscription(
                    name = name,
                    url = url,
                    nodes = nodes.toMutableList(),
                )

                runOnUiThread {
                    repository.upsertSubscription(subscription)

                    toast("$successPrefix ${subscription.nodes.size} 个节点")

                    invalidateAllPages()

                    if (switchToNodesOnSuccess) {
                        switchTab(TAB_NODES, refreshTarget = true)
                    } else {
                        render()
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()

                runOnUiThread {
                    toast("$failurePrefix: ${e.message ?: "再试试"}")
                }
            }
        }.start()
    }

    private fun importManualContent(name: String, content: String) {
        runCatching {
            val nodes = SubscriptionParser.parse(content)
            require(nodes.isNotEmpty()) { "没有解析到节点" }
            Subscription(
                name = name,
                url = "manual://${System.currentTimeMillis()}",
                nodes = nodes.toMutableList(),
                lastUpdateMillis = System.currentTimeMillis(),
            )
        }.onSuccess {
            repository.upsertSubscription(it)
            toast("已导入 ${it.nodes.size} 个节点")
            invalidateAllPages()
            switchTab(TAB_NODES, refreshTarget = true)
        }.onFailure {
            toast("导入失败: ${it.message}")
        }
    }

    private fun syncAllSubscriptions() {
        val remoteSubscriptions = repository.subscriptions.filterNot { it.url.startsWith("manual://") }
        if (remoteSubscriptions.isEmpty()) {
            toast("没有可同步的远程订阅")
            return
        }
        remoteSubscriptions.forEach { fetchAndStoreSubscription(it.name, it.url) }
    }

    private fun scroll(
        horizontal: Int,
        top: Int,
        bottom: Int,
        builder: LinearLayout.() -> Unit,
    ): ScrollView {
        val linear = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(horizontal), dp(top), dp(horizontal), dp(bottom))
            builder()
        }
        return ScrollView(this).apply {
            setBackgroundColor(COLOR_CANVAS)
            addView(linear)
        }
    }

    private fun panel(radius: Int = 14, padding: Int = 12, builder: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
            background = glassBackground(radius)
            elevation = dp(2).toFloat()
            builder()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun emptyPanel(title: String, subtitle: String, actionLabel: String? = null, onTap: (() -> Unit)? = null): View {
        return panel(radius = 14, padding = 12) {
            addView(label(title, 15f, true, COLOR_INK))
            addGap(8)
            addView(label(subtitle, 12f, color = COLOR_SOFT_TEXT))
            if (actionLabel != null && onTap != null) {
                addGap(12)
                addView(actionButton(actionLabel, true, onTap), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
            }
        }
    }

    private fun actionButton(textValue: String, primary: Boolean, onTap: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            setTextColor(if (primary) Color.WHITE else COLOR_INK)
            background = if (primary) {
                gradientRounded(intArrayOf(COLOR_BRAND, COLOR_ACCENT), 14, COLOR_BRAND)
            } else {
                rounded(COLOR_SURFACE, 14, COLOR_GLASS_BORDER)
            }
            addPressFeedback()
            setOnClickListener { onTap() }
        }
    }

    private fun smallButton(
        textValue: String,
        primary: Boolean = false,
        danger: Boolean = false,
        onTap: () -> Unit,
    ): Button {
        val backgroundColor = when {
            danger -> COLOR_DANGER_LIGHT
            primary -> COLOR_BRAND
            else -> COLOR_SURFACE
        }
        val textColor = when {
            danger -> COLOR_DANGER
            primary -> Color.WHITE
            else -> COLOR_INK
        }
        return Button(this).apply {
            text = textValue
            isAllCaps = false
            minHeight = 0
            minWidth = 0
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            setTextColor(textColor)
            background = if (primary) {
                gradientRounded(intArrayOf(COLOR_BRAND, COLOR_ACCENT), 12, COLOR_BRAND)
            } else {
                rounded(backgroundColor, 12, if (danger) COLOR_DANGER_LIGHT else COLOR_GLASS_BORDER)
            }
            addPressFeedback()
            setOnClickListener { onTap() }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.addPressFeedback() {
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel()
                    view.animate()
                        .scaleX(0.97f)
                        .scaleY(0.97f)
                        .alpha(0.86f)
                        .setDuration(BUTTON_PRESS_MS)
                        .start()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().cancel()
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(BUTTON_RELEASE_MS)
                        .start()
                }
            }
            false
        }
    }

    private fun pill(textValue: String): View {
        return TextView(this).apply {
            text = textValue
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = rounded(COLOR_GLASS, 999, COLOR_GLASS_BORDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(4)
            }
        }
    }

    private fun kawaiiRibbon(lead: String, textValue: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stickerBadge(lead))
            addView(
                View(context).apply {
                    background = rounded(COLOR_BORDER, 999)
                },
                LinearLayout.LayoutParams(dp(18), dp(2)).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                },
            )
            addView(label(textValue, 10.5f, true, COLOR_SOFT_TEXT, maxLines = 1))
        }
    }


    private fun cuteAccentDots(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cuteDot(COLOR_BRAND), LinearLayout.LayoutParams(dp(6), dp(6)))
            addView(cuteDot(COLOR_ACCENT), LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginStart = dp(5) })
            addView(cuteDot(COLOR_BORDER), LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginStart = dp(5) })
        }
    }

    private fun cuteDot(color: Int): View {
        return View(this).apply {
            background = rounded(color, 999)
        }
    }

    private fun stickerBadge(textValue: String): View {
        return TextView(this).apply {
            text = textValue
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_BRAND_DARK)
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = rounded(COLOR_STICKER, 999, COLOR_GLASS_BORDER)
        }
    }

    private fun LinearLayout.addGap(height: Int) {
        addView(View(context), LinearLayout.LayoutParams(1, dp(height)))
    }

    private fun label(
        textValue: String,
        size: Float,
        bold: Boolean = false,
        color: Int = COLOR_SOFT_TEXT,
        gravity: Int = Gravity.START,
        maxLines: Int = Int.MAX_VALUE,
    ): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            this.gravity = gravity
            this.maxLines = maxLines
            if (maxLines == 1) {
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            setLineSpacing(0f, 1.18f)
        }
    }

    private fun rounded(
        color: Int,
        radius: Int,
        strokeColor: Int? = null,
        strokeWidth: Float = 1f,
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) {
                setStroke((strokeWidth * resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
            }
        }
    }

    private fun glassBackground(radius: Int): GradientDrawable {
        return rounded(COLOR_SURFACE, radius, COLOR_GLASS_BORDER)
    }

    private fun appBackground(): GradientDrawable {
        return rounded(COLOR_CANVAS, 0)
    }

    private fun gradientRounded(colors: IntArray, radius: Int, strokeColor: Int? = null): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }
    }


    private fun formatTime(millis: Long): String {
        if (millis <= 0L) return "从未同步"
        val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))
        return "更新于 $date"
    }

    /**
     * 消息提示
     */
    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val view = currentFocus ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerAppReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun isServiceRunning(cls: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == cls.name }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class VpnStatus(
        val vpn: Boolean = false,
    )

    companion object {
        const val ACTION_STATUS = "com.non.human.xray.STATUS"
        private const val VPN_REQUEST_CODE = 42
        private const val VPN_TAP_PRESS_MS = 80L
        private const val VPN_TAP_RELEASE_MS = 120L
        private const val BUTTON_PRESS_MS = 70L
        private const val BUTTON_RELEASE_MS = 130L
        private const val VPN_TRANSITION_DELAY_MS = 180L
        private const val TAB_SWITCH_ANIMATION_MS = 220L
        private const val TAB_HOME = 0
        private const val TAB_NODES = 1
        private const val BUILT_IN_SUBSCRIPTION_NAME = "内置订阅"
        private const val BUILT_IN_SUBSCRIPTION_URL =
            "https://studyhtml.fdfgaergvrevg2.eu.cc/sub?token=c26232564c583837edc25f4e9d0dbd06"

        private const val COLOR_BRAND = 0xFF2563EB.toInt()
        private const val COLOR_BRAND_DARK = 0xFF1D4ED8.toInt()
        private const val COLOR_ACCENT = 0xFF14B8A6.toInt()
        private const val COLOR_SUCCESS = 0xFF16A34A.toInt()
        private const val COLOR_CANVAS = 0xFFF6F8FB.toInt()
        private const val COLOR_SURFACE = 0xFFFFFFFF.toInt()
        private const val COLOR_SURFACE_ALPHA = 0xEEFFFFFF.toInt()
        private const val COLOR_GLASS = 0xFFFFFFFF.toInt()
        private const val COLOR_GLASS_HIGHLIGHT = 0xFFFFFFFF.toInt()
        private const val COLOR_GLASS_COOL = 0xFFF8FAFC.toInt()
        private const val COLOR_INPUT_GLASS = 0xFFFFFFFF.toInt()
        private const val COLOR_INK = 0xFF111827.toInt()
        private const val COLOR_SOFT_TEXT = 0xFF6B7280.toInt()
        private const val COLOR_BORDER = 0xFFD7DEE8.toInt()
        private const val COLOR_GLASS_BORDER = 0xFFE2E8F0.toInt()
        private const val COLOR_HERO_MID = 0xFFFFFFFF.toInt()
        private const val COLOR_HERO_END = 0xFFF8FAFC.toInt()
        private const val COLOR_HERO_MID_GLASS = 0xFFFFFFFF.toInt()
        private const val COLOR_HERO_END_GLASS = 0xFFF8FAFC.toInt()
        private const val COLOR_STRAWBERRY_MILK = 0xFFF1F5F9.toInt()
        private const val COLOR_STICKER = 0xFFEFF6FF.toInt()
        private const val COLOR_NAV_INDICATOR = 0xFFEAF2FF.toInt()
        private const val COLOR_BRAND_LIGHT = 0x202563EB
        private const val COLOR_DANGER = 0xFFDC2626.toInt()
        private const val COLOR_DANGER_LIGHT = 0x20DC2626
    }
}
