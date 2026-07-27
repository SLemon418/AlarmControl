package com.alarmcontrol.ml

import android.content.Context
import java.io.File

/** Debug-only probe that reveals model presence without exposing the private model path. */
fun hasImportedLlmModel(context: Context): Boolean = File(context.filesDir, MlConfig.LLM_MODEL_FILE).isFile
