package io.github.ghsssss.ithomeadkill

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * IT之家(com.ruanmei.ithome)去广告模块
 *
 * 拦截规则（均为视图级）：
 * 1. 角标广告卡：信息流广告不走第三方SDK渲染，识别角标 TextView(id/tv_small_tags)
 *    上的"广告"文字，向上找到 RecyclerView 的直接子项（卡片根）高度置零
 * 2. WebView 推广卡：信息流内嵌 WebView 的推广位（如红包倒计时卡），
 *    卡片根为 id/ll_web，同样高度置零
 * 卡片被复用为普通内容时自动恢复原始高度，不影响正常信息流
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "ITHomeAdKill"
        const val TARGET_PACKAGE = "com.ruanmei.ithome"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        XposedBridge.log("[$TAG] 已注入 IT之家, process=${lpparam.processName}")
        hookAdCardView(lpparam)
        hookWebViewPromo(lpparam)
    }

    /**
     * 视图层拦截：信息流广告卡片的角标是 id/tv_small_tags 的 TextView，
     * 普通新闻该控件显示时间戳（如 "07:25 视频"），只有广告显示 "广告"。
     * 命中后向上找到 RecyclerView 的直接子项（卡片根），高度置零不留空位。
     * 注意：RecyclerView 的 LayoutManager 布局时不跳过 GONE 子项，
     * 只 setVisibility(GONE) 会留白，必须把 layoutParams.height 改成 0。
     * ViewHolder 复用时（角标变回时间戳）恢复原始高度，防止普通新闻消失。
     */
    private fun hookAdCardView(lpparam: XC_LoadPackage.LoadPackageParam) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val tv = param.thisObject as? TextView ?: return
                if (tv.id == View.NO_ID) return
                val matched = when (entryName(tv)) {
                    // 角标：广告 / 置顶
                    "tv_small_tags" -> true
                    // 事件日历卡标题
                    "tv_title" -> tv.text?.toString()?.trim() == "事件日历"
                    else -> false
                }
                if (!matched) return
                // setText 发生在 RecyclerView 绑定阶段，此时卡片尚未挂载，
                // post 到 attach 之后执行；仍失败由重试兜底
                tv.post { processTagView(tv, 0) }
            }
        }
        try {
            // 覆盖 setText 的常见重载，统一走 after 钩子读取最终文本：
            // (CharSequence)、(int)、(CharSequence, BufferType)
            val bufferType = XposedHelpers.findClass(
                "android.widget.TextView\$BufferType", lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                "android.widget.TextView", lpparam.classLoader,
                "setText", CharSequence::class.java, hook
            )
            XposedHelpers.findAndHookMethod(
                "android.widget.TextView", lpparam.classLoader,
                "setText", Int::class.javaPrimitiveType, hook
            )
            XposedHelpers.findAndHookMethod(
                "android.widget.TextView", lpparam.classLoader,
                "setText", CharSequence::class.java, bufferType, hook
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] hook TextView.setText 失败: $t")
        }
    }

    /** 记录被置零卡片的原始尺寸 [height, topMargin, bottomMargin]，复用还原时取回 */
    private val originalMetrics = java.util.WeakHashMap<View, IntArray>()

    /**
     * WebView 推广卡拦截：信息流里嵌 WebView 的推广位（如"无门槛红包"倒计时卡）
     * 根节点是 id/ll_web 的 LinearLayout，WebView 挂载后把整卡高度置零
     */
    private fun hookWebViewPromo(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.webkit.WebView", lpparam.classLoader,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val web = param.thisObject as? View ?: return
                        // 等 attach 完成后再爬父链
                        web.post { processPromoCard(web, 0) }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] hook WebView.onAttachedToWindow 失败: $t")
        }
    }

    private fun processPromoCard(view: View, attempt: Int) {
        val card = findCardRoot(view)
        if (card == null) {
            if (attempt < 3) {
                view.postDelayed({ processPromoCard(view, attempt + 1) }, 200)
            }
            return
        }
        when (entryName(card)) {
            "ll_web" -> collapseCard(card, "已隐藏WebView推广卡片")
            else -> restoreIfCollapsed(card)
        }
    }

    /** 根据角标/标题文字处理卡片：
     *  命中隐藏词 → 高度置零；其他文字 → 若此前被置零（复用）则恢复 */
    private fun processTagView(tv: TextView, attempt: Int) {
        val card = findCardRoot(tv)
        if (card == null) {
            if (attempt < 3) {
                tv.postDelayed({ processTagView(tv, attempt + 1) }, 200)
            } else {
                XposedBridge.log("[$TAG] 命中隐藏规则但未找到列表根 (retry=$attempt)")
            }
            return
        }
        val text = tv.text?.toString()?.trim() ?: ""
        val shouldHide = text.startsWith("广告") || text.startsWith("置顶") || text == "事件日历"
        if (shouldHide) {
            collapseCard(card, "已隐藏[$text]卡片")
        } else {
            restoreIfCollapsed(card)
        }
    }

    private fun collapseCard(card: View, logWhat: String) {
        val lp = card.layoutParams ?: return
        if (card.visibility == View.GONE && lp.height == 0) return
        val ml = lp as? ViewGroup.MarginLayoutParams
        originalMetrics[card] = intArrayOf(lp.height, ml?.topMargin ?: 0, ml?.bottomMargin ?: 0)
        ml?.topMargin = 0
        ml?.bottomMargin = 0
        lp.height = 0
        card.visibility = View.GONE
        card.layoutParams = lp
        card.requestLayout()
        XposedBridge.log("[$TAG] $logWhat(高度置零): ${card.javaClass.name}")
    }

    private fun restoreIfCollapsed(card: View) {
        val saved = originalMetrics.remove(card)
        val lp = card.layoutParams ?: return
        if (saved != null && card.visibility == View.GONE) {
            lp.height = saved[0]
            (lp as? ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = saved[1]
                it.bottomMargin = saved[2]
            }
            card.visibility = View.VISIBLE
            card.layoutParams = lp
            card.requestLayout()
            XposedBridge.log("[$TAG] 卡片复用为普通内容, 已恢复高度: ${card.javaClass.name}")
        }
    }

    private fun entryName(v: View): String? = try {
        v.resources.getResourceEntryName(v.id)
    } catch (t: Throwable) {
        null
    }

    /** 向上找到 RecyclerView（含其子类）的直接子项，未挂载/未找到返回 null */
    private fun findCardRoot(view: View): View? {
        var child: Any = view
        var p = view.parent
        var depth = 0
        val rcClass = recyclerViewClass(view)
        while (p != null && depth < 30) {
            // 用 instanceof 匹配：App 内 RecyclerView 是自定义子类，精确类名匹配不到
            if (rcClass != null && rcClass.isInstance(p)) {
                return child as? View
            }
            child = p
            p = p.parent
            depth++
        }
        return null
    }

    /** 在宿主 App 的类加载器里解析 RecyclerView 类（含子类判断用），缓存结果 */
    private fun recyclerViewClass(view: View): Class<*>? {
        cachedRvClass?.let { return it }
        return try {
            Class.forName(
                "androidx.recyclerview.widget.RecyclerView",
                false,
                view.javaClass.classLoader
            ).also { cachedRvClass = it }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 无法解析 RecyclerView 类: $t")
            null
        }
    }

    private var cachedRvClass: Class<*>? = null
}
