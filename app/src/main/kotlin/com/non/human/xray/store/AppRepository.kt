package com.non.human.xray.store

import android.content.Context
import com.non.human.xray.data.OutboundNode
import com.non.human.xray.data.Subscription
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用状态仓库，负责把订阅、节点选择和代理模式保存到本地偏好设置中。
 *
 * @param context 用于获取 SharedPreferences 的 Android 上下文。
 */
class AppRepository(context: Context) {
    /** 持久化应用状态的 SharedPreferences 实例。 */
    private val prefs = context.getSharedPreferences("native_app_state", Context.MODE_PRIVATE)

    /** 当前保存的全部订阅列表。 */
    var subscriptions: MutableList<Subscription> = mutableListOf()
        private set
    /** 当前激活订阅在 subscriptions 中的索引，-1 表示未选择。 */
    var activeSubscriptionIndex: Int = -1
        private set
    /** 当前选中节点在激活订阅节点列表中的索引，-1 表示未选择。 */
    var selectedNodeIndex: Int = -1
        private set
    /** 当前代理模式，0 和 1 分别对应应用支持的两种模式。 */
    var proxyMode: Int = 0
        private set

    /** 根据 activeSubscriptionIndex 计算得到的当前激活订阅。 */
    val activeSubscription: Subscription?
        get() = subscriptions.getOrNull(activeSubscriptionIndex)

    /** 根据 selectedNodeIndex 计算得到的当前选中节点。 */
    val selectedNode: OutboundNode?
        get() = activeSubscription?.nodes?.getOrNull(selectedNodeIndex)

    /** 从本地偏好设置加载订阅、选择状态和代理模式。 */
    fun load() {
        // 清空内存中的订阅，避免重复加载时保留旧数据。
        subscriptions.clear()
        // 本地保存的 JSON 字符串；为空时说明尚未保存过状态。
        val raw = prefs.getString(KEY_STATE, null) ?: return
        runCatching {
            // 根 JSON 对象，包含订阅列表、选择索引和代理模式。
            val root = JSONObject(raw)
            // 保存订阅数据的 JSON 数组，缺失时使用空数组兜底。
            val array = root.optJSONArray("subscriptions") ?: JSONArray()
            // index 表示当前遍历的订阅 JSON 数组下标。
            for (index in 0 until array.length()) {
                subscriptions += Subscription.fromJson(array.getJSONObject(index))
            }
            activeSubscriptionIndex = root.optInt("activeSubscriptionIndex", if (subscriptions.isNotEmpty()) 0 else -1)
            selectedNodeIndex = root.optInt("selectedNodeIndex", if (activeSubscription?.nodes?.isNotEmpty() == true) 0 else -1)
            proxyMode = root.optInt("proxyMode", 0).coerceIn(0, 1)
            normalizeSelection()
        }
    }

    /** 将当前内存中的订阅、选择状态和代理模式保存到本地偏好设置。 */
    fun save() {
        // 用于保存全部订阅 JSON 的数组。
        val array = JSONArray()
        subscriptions.forEach { subscription ->
            // subscription 表示当前正在写入 JSON 数组的订阅对象。
            array.put(subscription.toJson())
        }
        // 根 JSON 对象，汇总所有需要持久化的应用状态。
        val root = JSONObject()
            .put("subscriptions", array)
            .put("activeSubscriptionIndex", activeSubscriptionIndex)
            .put("selectedNodeIndex", selectedNodeIndex)
            .put("proxyMode", proxyMode)
        prefs.edit().putString(KEY_STATE, root.toString()).apply()
    }

    /**
     * 新增或更新订阅；URL 相同则覆盖旧订阅，否则追加到列表末尾。
     *
     * @param subscription 需要新增或更新的订阅。
     */
    fun upsertSubscription(subscription: Subscription) {
        // URL 相同的现有订阅索引，找不到时为 -1。
        val existing = subscriptions.indexOfFirst { savedSubscription ->
            // savedSubscription 表示列表中正在比较 URL 的已有订阅对象。
            savedSubscription.url == subscription.url
        }
        if (existing >= 0) {
            subscriptions[existing] = subscription
            if (activeSubscriptionIndex == -1) activeSubscriptionIndex = existing
        } else {
            subscriptions += subscription
            if (activeSubscriptionIndex == -1) activeSubscriptionIndex = subscriptions.lastIndex
        }
        normalizeSelection()
        save()
    }

    /** 删除当前激活订阅，并重新调整订阅和节点选择。 */
    fun removeActiveSubscription() {
        if (activeSubscriptionIndex !in subscriptions.indices) return
        subscriptions.removeAt(activeSubscriptionIndex)
        activeSubscriptionIndex = if (subscriptions.isEmpty()) -1 else activeSubscriptionIndex.coerceAtMost(subscriptions.lastIndex)
        selectedNodeIndex = if (activeSubscription?.nodes?.isNotEmpty() == true) 0 else -1
        save()
    }

    /**
     * 切换当前激活订阅。
     *
     * @param index 目标订阅在 subscriptions 中的索引。
     */
    fun selectSubscription(index: Int) {
        if (index !in subscriptions.indices) return
        activeSubscriptionIndex = index
        selectedNodeIndex = if (subscriptions[index].nodes.isNotEmpty()) 0 else -1
        save()
    }

    /**
     * 切换当前激活订阅下的选中节点。
     *
     * @param index 目标节点在当前订阅节点列表中的索引。
     */
    fun selectNode(index: Int) {
        if (index !in (activeSubscription?.nodes?.indices ?: IntRange.EMPTY)) return
        selectedNodeIndex = index
        save()
    }

    /**
     * 设置代理模式，并将模式限制在应用支持的范围内。
     *
     * @param mode 目标代理模式。
     */
    fun setProxyMode(mode: Int) {
        proxyMode = mode.coerceIn(0, 1)
        save()
    }

    /** 规范化订阅和节点索引，保证它们始终落在有效范围内。 */
    private fun normalizeSelection() {
        if (subscriptions.isEmpty()) {
            activeSubscriptionIndex = -1
            selectedNodeIndex = -1
            return
        }
        activeSubscriptionIndex = activeSubscriptionIndex.coerceIn(0, subscriptions.lastIndex)
        // 当前激活订阅的节点列表；没有激活订阅时使用空列表。
        val nodes = activeSubscription?.nodes.orEmpty()
        selectedNodeIndex = if (nodes.isEmpty()) -1 else selectedNodeIndex.coerceIn(0, nodes.lastIndex)
    }

    /** 仓库内部使用的常量集合。 */
    private companion object {
        /** SharedPreferences 中保存应用状态 JSON 的键名。 */
        const val KEY_STATE = "state"
    }
}
