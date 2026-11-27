package com.ruanmei.ithome.adapters

/** Minimal read-only contract fixture. No Android or upstream implementation. */
class CommentListAdapterV8(private val values: List<*>, private val headers: Int = 1,
    private val count: Int = values.size + headers) {
    @JvmField val mData: List<*> = values
    fun getData(): List<*> = values
    fun getHeaderLayoutCount(): Int = headers
    fun getItemCount(): Int = count
}
