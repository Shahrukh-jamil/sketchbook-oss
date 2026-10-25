package com.sketchbook.oss

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SketchWorkspaceView.HostCallbacks {

    private lateinit var rootLayout: FrameLayout
    private lateinit var workspaceView: SketchWorkspaceView
    private lateinit var inlineEditText: EditText
    private var currentEditingField: InlineEditField = InlineEditField.NONE
    private var suppressWatcher: Boolean = false

    companion object {
        private const val REQ_IMPORT_IMAGE = 1001
        private const val REQ_STORAGE_PERM = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = Color.rgb(117, 117, 117)
        window.navigationBarColor = Color.rgb(127, 127, 127)

        rootLayout = FrameLayout(this)
        workspaceView = SketchWorkspaceView(this, this)
        rootLayout.addView(
            workspaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        inlineEditText = EditText(this).apply {
            visibility = View.GONE
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            setPadding(12, 0, 12, 0)

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!suppressWatcher && currentEditingField != InlineEditField.NONE) {
                        workspaceView.onInlineTextUpdatedLive(currentEditingField, s?.toString() ?: "")
                    }
                }
            })

            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_NEXT ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    workspaceView.closeInlineEditor()
                    true
                } else {
                    false
                }
            }
        }
        rootLayout.addView(inlineEditText, FrameLayout.LayoutParams(0, 0))

        setContentView(rootLayout)
        requestLegacyStoragePermissionsIfNeeded()
    }

    override fun onBeginInlineEdit(
        field: InlineEditField,
        bounds: RectF,
        initialText: String,
        numeric: Boolean,
        centered: Boolean,
        textSizePx: Float,
        textColor: Int,
        bgColor: Int
    ) {
        currentEditingField = field
        suppressWatcher = true

        val lp = FrameLayout.LayoutParams(
            bounds.width().roundToInt().coerceAtLeast(40),
            bounds.height().roundToInt().coerceAtLeast(30)
        ).apply {
            leftMargin = bounds.left.roundToInt()
            topMargin = bounds.top.roundToInt()
        }
        inlineEditText.layoutParams = lp
        inlineEditText.setBackgroundColor(Color.TRANSPARENT)
        inlineEditText.setTextColor(textColor)
        inlineEditText.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
        inlineEditText.gravity = if (centered) Gravity.CENTER else (Gravity.START or Gravity.CENTER_VERTICAL)
        inlineEditText.inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        inlineEditText.setText(initialText)
        inlineEditText.setSelection(inlineEditText.text.length)
        inlineEditText.visibility = View.VISIBLE
        inlineEditText.requestFocus()

        suppressWatcher = false

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(inlineEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onEndInlineEdit() {
        currentEditingField = InlineEditField.NONE
        inlineEditText.clearFocus()
        inlineEditText.visibility = View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(inlineEditText.windowToken, 0)
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
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_IMPORT_IMAGE)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
            }
            @Suppress("DEPRECATION")
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
        if (workspaceView.isSavingInProgress) return
        if (workspaceView.activeInlineField != InlineEditField.NONE) {
            workspaceView.closeInlineEditor()
            return
        }
        if (workspaceView.popupState != TopPopupState.NONE) {
            if ((workspaceView.popupState == TopPopupState.BRUSH_MENU || workspaceView.popupState == TopPopupState.COLOR_MENU) &&
                workspaceView.brushAndColorModal.subOverlay != BrushSubOverlay.NONE
            ) {
                workspaceView.brushAndColorModal.subOverlay = BrushSubOverlay.NONE
                workspaceView.invalidate()
                return
            }
            workspaceView.popupState = when (workspaceView.popupState) {
                TopPopupState.EDIT_PRESET -> TopPopupState.NEW_SKETCH
                TopPopupState.EXPORT_AS_MENU, TopPopupState.SHARE_AS_MENU -> TopPopupState.SHARE_EXPORT_MAIN
                TopPopupState.NEW_SKETCH, TopPopupState.SAVE_MENU,
                TopPopupState.SHARE_EXPORT_MAIN, TopPopupState.MOD_MENU -> TopPopupState.BUTTON1_MAIN
                TopPopupState.BUTTON1_MAIN, TopPopupState.BRUSH_MENU, TopPopupState.COLOR_MENU -> TopPopupState.NONE
                else -> TopPopupState.NONE
            }
            workspaceView.invalidate()
            return
        }
        super.onBackPressed()
    }
}
