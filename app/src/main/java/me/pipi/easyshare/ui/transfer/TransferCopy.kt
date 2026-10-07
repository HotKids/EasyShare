package me.pipi.easyshare.ui.transfer

import android.content.Context
import android.text.format.Formatter
import me.pipi.easyshare.R

/** Shared presentation copy for half sheets and native transfer notifications. */
object TransferCopy {
    fun itemLabel(context: Context, fileName: String, mimeType: String?, isText: Boolean, count: Int): String {
        val kind = attachmentKind(fileName, mimeType, isText, count)
        if (kind == AttachmentKind.TEXT) return context.getString(R.string.shared_text)
        val quantity = count.coerceAtLeast(1)
        val resource = when (kind) {
            AttachmentKind.IMAGE -> R.plurals.transfer_images
            AttachmentKind.VIDEO -> R.plurals.transfer_videos
            else -> R.plurals.incoming_transfer_multiple
        }
        return context.resources.getQuantityString(resource, quantity, quantity)
    }

    fun request(context: Context, sender: String, item: String): String =
        context.getString(R.string.transfer_request_summary, sender, item)

    fun receiving(context: Context, item: String): String =
        context.getString(R.string.transfer_receiving, item)

    fun received(context: Context, sender: String, item: String): String =
        context.getString(R.string.transfer_received_summary, sender, item)

    fun withSize(context: Context, text: String, totalSize: Long, isText: Boolean): String =
        if (totalSize > 0L && !isText) text + context.getString(
            R.string.transfer_request_size, Formatter.formatFileSize(context, totalSize),
        ) else text
}
