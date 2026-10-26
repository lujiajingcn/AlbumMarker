package com.albummarker.ui

import android.app.Activity
import com.albummarker.R
import com.albummarker.data.MediaQuery
import com.albummarker.data.model.ModelManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 自动打标前的模型选择器；只有安装多个模型时才会使用。 */
object ModelPickerDialog {

    fun show(
        activity: Activity,
        models: List<ModelManager.InstalledModel>,
        activePath: String?,
        onPick: (ModelManager.InstalledModel) -> Unit,
    ) {
        if (models.isEmpty()) return
        val labels = models.map { model ->
            val size = MediaQuery.formatSize(model.sizeBytes)
            val current = if (model.file.absolutePath == activePath) {
                " · " + activity.getString(R.string.model_current)
            } else {
                ""
            }
            "${model.displayName}  ·  $size$current"
        }.toTypedArray()

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.model_choose_title)
            .setItems(labels) { _, which -> onPick(models[which]) }
            .setNegativeButton(R.string.auto_tag_cancel, null)
            .show()
    }
}
