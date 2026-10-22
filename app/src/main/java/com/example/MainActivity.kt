package com.example

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MainActivity : ComponentActivity() {

  private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

  // System file picker for restoring JSON backups
  private val filePickerLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
      if (uri != null) {
        fileUploadCallback?.onReceiveValue(arrayOf(uri))
      } else {
        fileUploadCallback?.onReceiveValue(null)
      }
      fileUploadCallback = null
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme {
        LearnerScreeningScreen(
          onOpenFileChooser = { callback ->
            fileUploadCallback = callback
            try {
              filePickerLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
            } catch (e: Exception) {
              callback.onReceiveValue(null)
              fileUploadCallback = null
            }
          },
          onCloseApp = { finish() }
        )
      }
    }
  }
}

/**
 * JavaScript interface bridge connecting the web application with Android native features:
 * - Persistent local downloads to Downloads folder
 * - Native system Share Sheet for HTML reports, CSVs, and JSON backups
 * - Native Toast notifications
 */
class AndroidBridge(private val context: Context) {
  private val mainHandler = Handler(Looper.getMainLooper())

  @JavascriptInterface
  fun saveFile(fileName: String, content: String, mimeType: String) {
    mainHandler.post {
      try {
        // 1. Save to app cache directory for FileProvider sharing
        val cacheDir = File(context.cacheDir, "exports")
        if (!cacheDir.exists()) {
          cacheDir.mkdirs()
        }
        val exportFile = File(cacheDir, fileName)
        FileOutputStream(exportFile).use { fos ->
          fos.write(content.toByteArray(Charsets.UTF_8))
        }

        // 2. Also save directly to user's device Downloads
        try {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
              put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
              put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
              put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LearnerScreening")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
              context.contentResolver.openOutputStream(uri)?.use { os: OutputStream ->
                os.write(content.toByteArray(Charsets.UTF_8))
              }
            }
          } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val publicFile = File(downloadsDir, fileName)
            FileOutputStream(publicFile).use { fos ->
              fos.write(content.toByteArray(Charsets.UTF_8))
            }
          }
        } catch (ignored: Exception) {
          // Fallback to cache sharing if storage permission is restricted
        }

        // 3. Launch Android system share sheet with FileProvider
        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, exportFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
          type = mimeType
          putExtra(Intent.EXTRA_STREAM, contentUri)
          putExtra(Intent.EXTRA_SUBJECT, fileName)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share or Save: $fileName").apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)

        Toast.makeText(context, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
      } catch (e: Exception) {
        Toast.makeText(context, "Export error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
      }
    }
  }

  @JavascriptInterface
  fun showToast(message: String) {
    mainHandler.post {
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LearnerScreeningScreen(
  onOpenFileChooser: (ValueCallback<Array<Uri>>) -> Unit,
  onCloseApp: () -> Unit
) {
  val context = LocalContext.current
  var webViewRef by remember { mutableStateOf<WebView?>(null) }
  var isLoading by remember { mutableStateOf(true) }

  // Handle Android system back gesture / back button
  BackHandler {
    val wv = webViewRef
    if (wv != null) {
      wv.evaluateJavascript("window.handleAndroidBack ? window.handleAndroidBack() : false") { result ->
        val handled = result == "true" || result == "\"true\""
        if (!handled) {
          if (wv.canGoBack()) {
            wv.goBack()
          } else {
            onCloseApp()
          }
        }
      }
    } else {
      onCloseApp()
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .statusBarsPadding()
      .navigationBarsPadding()
      .background(MaterialTheme.colorScheme.background)
      .testTag("webview_container")
  ) {
    AndroidView(
      modifier = Modifier
        .fillMaxSize()
        .testTag("main_webview"),
      factory = { ctx ->
        WebView(ctx).apply {
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )

          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // Required for localStorage persistence
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
          }

          // Register JavaScript Interface
          addJavascriptInterface(AndroidBridge(ctx), "AndroidBridge")

          webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
              webView: WebView?,
              filePathCallback: ValueCallback<Array<Uri>>?,
              fileChooserParams: FileChooserParams?
            ): Boolean {
              if (filePathCallback != null) {
                onOpenFileChooser(filePathCallback)
                return true
              }
              return false
            }
          }

          webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
              super.onPageFinished(view, url)
              isLoading = false
            }

            override fun onReceivedError(
              view: WebView?,
              request: WebResourceRequest?,
              error: WebResourceError?
            ) {
              super.onReceivedError(view, request, error)
              isLoading = false
            }
          }

          loadUrl("file:///android_asset/index.html")
          webViewRef = this
        }
      },
      update = {
        webViewRef = it
      }
    )

    if (isLoading) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
      ) {
        CircularProgressIndicator()
      }
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      webViewRef?.destroy()
      webViewRef = null
    }
  }
}
