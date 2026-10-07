package me.pipi.easyshare.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class OutgoingTransferPresentation(
    val taskId: Int,
    val deviceId: String,
    val deviceName: String,
    val brandId: Int?,
    val fileName: String,
    val mimeType: String,
    val fileCount: Int,
    val totalSize: Long,
    val isText: Boolean,
    val nameIsFallback: Boolean = false,
) : Parcelable {
    companion object {
        fun from(task: TaskInfo): OutgoingTransferPresentation {
            val first = task.files.first()
            return OutgoingTransferPresentation(
                taskId = task.id,
                deviceId = task.device.id,
                deviceName = task.device.displayName,
                brandId = task.device.brandId,
                fileName = first.name,
                mimeType = task.files.map { it.mimeType }.distinct().singleOrNull() ?: "*/*",
                fileCount = task.files.size,
                totalSize = task.files.sumOf { it.size },
                isText = task.files.size == 1 && first.textContent != null,
                nameIsFallback = first.nameIsFallback,
            )
        }
    }
}
