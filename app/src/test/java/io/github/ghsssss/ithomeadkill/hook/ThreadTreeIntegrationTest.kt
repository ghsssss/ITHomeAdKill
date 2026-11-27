package io.github.ghsssss.ithomeadkill.hook

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Android lifecycle/ownership contracts, complemented by real installed WSA A/B. */
class ThreadTreeIntegrationTest {
    private fun source(name: String): String {
        val relative = "src/main/java/io/github/ghsssss/ithomeadkill/hook/$name.kt"
        return (File(relative).takeIf { it.exists() } ?: File("app/$relative")).readText()
    }

    @Test fun lifecycleAndMasterGateAreWiredToTheNewOwner() {
        val host = source("HostRuntime")
        assertTrue(host.contains("enabled(Toggle.COMMENT_TREE)"))
        assertTrue(host.contains("commentTree.removeFor(activity)"))
        assertTrue(host.contains("commentTree.releaseWithin(view)"))
        assertTrue(host.contains("commentTree.desiredLayout(view, params)"))
        assertTrue(host.contains("commentTree.clearAll()"))
        assertTrue(host.contains("commentTree.adapterChanged("))
        assertTrue(source("ThreadTreeController").contains("private var generation = 0L"))
    }

    @Test fun controllerOwnsOnlyOverlayAndMarginLeaseNotTouchListenersOrHostBackground() {
        val source = source("ThreadTreeController")
        assertTrue(source.contains("overlay.add("))
        assertTrue(source.contains("overlay.remove("))
        assertTrue(source.contains("OnPreDrawListener"))
        assertTrue(source.contains("WeakReference"))
        assertFalse(source.contains("setOnTouchListener"))
        assertFalse(source.contains("setOnClickListener"))
        assertFalse(source.contains("setBackground"))
        assertFalse(source.contains("postInvalidateOnAnimation"))
        assertFalse(source.contains("setLayerType"))
    }

    @Test fun discoveryAndControllerUseTheSameVerifiedAdmissionOwner() {
        val admission = source("ThreadTreeAdmission")
        val controller = source("ThreadTreeController")
        assertTrue(admission.contains("com.ruanmei.ithome.ui.NewsInfoV8Activity"))
        assertTrue(admission.contains("com.ruanmei.ithome.ui.CommentListV8Activity"))
        assertTrue(admission.contains("com.ruanmei.ithome.ui.NewsCommentUnfoldActivity"))
        assertTrue(admission.contains("\"rv_comment\""))
        assertTrue(admission.contains("\"rv_commentList\""))
        assertTrue(admission.contains("\"rv_hotComment\""))
        assertTrue(controller.contains("ThreadTreeAdmission.isSupported(activity.javaClass.name, entryName(list))"))
        assertTrue(controller.contains("hasSuperclass(list, \"androidx.recyclerview.widget.RecyclerView\")"))
        assertFalse(controller.contains("com.ruanmei.ithome.ui.NewsInfoV8Activity"))
        assertFalse(controller.contains("com.ruanmei.ithome.ui.CommentListV8Activity"))
        assertFalse(controller.contains("com.ruanmei.ithome.ui.NewsCommentUnfoldActivity"))
        val host = source("HostRuntime")
        assertTrue(host.contains("ThreadTreeAdmission.isSupported(activity.javaClass.name,"))
        assertFalse(host.contains("setOf(\"rv_comment\", \"rv_commentList\")"))
        assertTrue(source("ThreadTreeDataSource").contains("com.ruanmei.ithome.adapters.CommentListAdapterV8"))
    }

    @Test fun orderOwnershipMovesBeforeEarlyReturnAndReleasesOnRemoval() {
        val order = source("ThreadTreeOrderController")
        assertTrue(order.contains("ThreadTreeOrderQueue(list, handler::post, handler::removeCallbacks)"))
        assertFalse(order.contains("list.post"))
        assertTrue(order.indexOf("state.work.bind(list)") < order.indexOf("state.work.posted"))
        assertTrue(order.contains("state.work.bind(null)"))
        assertTrue(order.contains("list?.let { adapterOf(it) } !== adapter"))
        val controller = source("ThreadTreeController")
        val removal = controller.substringAfter("private fun remove(list: ViewGroup) {")
        assertTrue(removal.indexOf("releaseOrder(list)") < removal.indexOf("owners.remove(list)"))
        assertTrue(source("HostRuntime").contains("commentOrder::restoreList"))
    }

    @Test fun shadowFillLeaseIsReleasedBeforeReuseAndWhenTheFeatureIsDisabled() {
        val controller = source("ThreadTreeController")
        assertTrue(controller.contains("fills.apply(row, list)"))
        assertTrue(controller.contains("fills.releaseWithin(root)"))
        assertTrue(controller.contains("fills.releaseList(list)"))
        assertTrue(controller.contains("fills.clear()"))
        val fill = source("ThreadTreeFillController")
        assertTrue(fill.contains("setLayoutBackground"))
        assertTrue(fill.contains("mBackGroundColor"))
        assertTrue(fill.contains("WeakHashMap"))
        assertFalse(fill.contains("setBackground("))
        assertFalse(fill.contains("setOnClickListener"))
    }
}
