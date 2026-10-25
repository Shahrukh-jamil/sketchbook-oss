package com.sketchbook.oss

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SketchWorkspaceView.HostCallbacks {

    private lateinit var workspaceView: SketchWorkspaceView

    companion object {
        private const val REQ_IMPORT_IMAGE = 1001
        private const val REQ_STORAGE_PERM = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = Color.rgb(117, 117, 117)
        window.navigationBarColor = Color.rgb(127, 127, 127)

        workspaceView = SketchWorkspaceView(this, this)
        setContentView(workspaceView)

        requestLegacyStoragePermissionsIfNeeded()
    }

    private fun requestLegacyStoragePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                    REQ_STORAGE_PERM
                )
            }
        }
    }

    override fun onRequestImportImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            startActivityForResult(intent, REQ_IMPORT_IMAGE)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
            }
            startActivityForResult(Intent.createChooser(fallback, "Import Image as Project"), REQ_IMPORT_IMAGE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_IMAGE && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val decoded = BitmapFactory.decodeStream(inputStream)
                    if (decoded != null) {
                        val fileName = queryFileName(uri).substringBeforeLast(".")
                        workspaceView.applyImportedBitmapAsNewProject(decoded, fileName)
                    } else {
                        workspaceView.showBanner("Could not decode selected image")
                    }
                }
            } catch (e: Exception) {
                workspaceView.showBanner("Import error: ${e.message}")
            }
        }
    }

    private fun queryFileName(uri: Uri): String {
        var name = "Imported-Sketch"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(idx) ?: name
                }
            }
        } catch (_: Exception) {}
        return name
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (workspaceView.popupState != TopPopupState.NONE) {
            workspaceView.popupState = when (workspaceView.popupState) {
                TopPopupState.EDIT_PRESET -> TopPopupState.NEW_SKETCH
                TopPopupState.EXPORT_AS_MENU, TopPopupState.SHARE_AS_MENU -> TopPopupState.SHARE_EXPORT_MAIN
                TopPopupState.NEW_SKETCH, TopPopupState.SAVE_MENU,
                TopPopupState.SHARE_EXPORT_MAIN, TopPopupState.MOD_MENU -> TopPopupState.BUTTON1_MAIN
                TopPopupState.BUTTON1_MAIN -> TopPopupState.NONE
                TopPopupState.NONE -> TopPopupState.NONE
            }
            workspaceView.invalidate()
            return
        }
        super.onBackPressed()
    }
}
