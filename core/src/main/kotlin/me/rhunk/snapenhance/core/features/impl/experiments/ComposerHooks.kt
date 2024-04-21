package me.rhunk.snapenhance.core.features.impl.experiments

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import kotlinx.coroutines.launch
import me.rhunk.snapenhance.common.ui.AppMaterialTheme
import me.rhunk.snapenhance.common.ui.createComposeAlertDialog
import me.rhunk.snapenhance.common.ui.createComposeView
import me.rhunk.snapenhance.core.features.Feature
import me.rhunk.snapenhance.core.features.FeatureLoadParams
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.hook
import me.rhunk.snapenhance.nativelib.NativeLib
import kotlin.random.Random
import kotlin.random.nextInt

class ComposerHooks: Feature("ComposerHooks", loadParams = FeatureLoadParams.ACTIVITY_CREATE_SYNC) {
    private val config by lazy { context.config.experimental.nativeHooks.composerHooks }

    private val composerConsole by lazy {
        createComposeAlertDialog(context.mainActivity!!) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                var result by remember { mutableStateOf("") }
                var codeContent by remember { mutableStateOf("") }

                Text("Composer Console", fontSize = 18.sp, fontWeight = FontWeight.Bold)

                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle.Default.copy(fontSize = 12.sp),
                    value = codeContent,
                    placeholder = { Text("Enter your JS code here:") },
                    onValueChange = {
                        codeContent = it
                    }
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        context.log.verbose("input: $codeContent", "ComposerConsole")
                        result = "Running..."
                        context.coroutineScope.launch {
                            result = (context.native.composerEval("""
                                (() => {
                                    try {
                                        $codeContent
                                    } catch (e) {
                                        return e.toString()
                                    }
                                })()
                            """.trimIndent()) ?: "(no result)").also {
                                context.log.verbose("result: $it", "ComposerConsole")
                            }
                        }
                    }
                ) {
                    Text("Run")
                }

                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(result)
                }
            }
        }
    }

    private fun injectConsole() {
        val root = context.mainActivity!!.findViewById<FrameLayout>(android.R.id.content)
        root.post {
            root.addView(createComposeView(root.context) {
                AppMaterialTheme {
                    FilledIconButton(
                        onClick = {
                            composerConsole.show()
                        },
                        modifier = Modifier.padding(top = 100.dp, end = 16.dp)
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = "Debug Console")
                    }
                }
            }.apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                }
            })
        }
    }

    private fun loadHooks() {
        val loaderConfig = JsonObject()

        if (config.composerLogs.get()) {
            val logPrefix = Random.nextInt(100000..999999).toString()
            val logTag = "ComposerLogs"

            ClipboardManager::class.java.hook("setPrimaryClip", HookStage.BEFORE) { param ->
                val clipData = param.arg<ClipData>(0).takeIf { it.itemCount == 1 } ?: return@hook
                val logText = clipData.getItemAt(0).text ?: return@hook
                if (!logText.startsWith("$logPrefix|")) return@hook

                val logContainer = logText.removePrefix("$logPrefix|").toString()
                val logType = logContainer.substringBefore("|")
                val content = logContainer.substringAfter("|")

                when (logType) {
                    "verbose" -> context.log.verbose(content, logTag)
                    "info" -> context.log.info(content, logTag)
                    "debug" -> context.log.debug(content, logTag)
                    "warn" -> context.log.warn(content, logTag)
                    "error" -> context.log.error(content, logTag)
                    else -> context.log.info(logContainer, logTag)
                }
                param.setResult(null)
            }
            loaderConfig.addProperty("logPrefix", logPrefix)
        }

        if (config.bypassCameraRollLimit.get()) {
            loaderConfig.addProperty("bypassCameraRollLimit", true)
        }

        val loaderScript = context.scriptRuntime.scripting.getScriptContent("composer/loader.js") ?: run {
            context.log.error("Failed to load composer loader script")
            return
        }

        val hookResult = context.native.composerEval("""
            (() => { try { const LOADER_CONFIG = $loaderConfig; $loaderScript
                } catch (e) {
                    return e.toString() + "\n" + e.stack;
                }
                return "success";
            })()
        """.trimIndent().trim())

        if (hookResult != "success") {
            context.shortToast(("Composer loader failed : $hookResult").also {
                context.log.error(it)
            })
        }

        if (config.composerConsole.get()) {
            injectConsole()
        }
    }

    override fun onActivityCreate() {
        if (!NativeLib.initialized || config.globalState != true) return
        var composerThreadTask: (() -> Unit)? = null

        findClass("com.snap.composer.callable.ComposerFunctionNative").hook("nativePerform", HookStage.BEFORE) {
            composerThreadTask?.invoke()
            composerThreadTask = null
        }

        context.coroutineScope.launch {
            context.native.waitForComposer()
            composerThreadTask = ::loadHooks
        }
    }
}