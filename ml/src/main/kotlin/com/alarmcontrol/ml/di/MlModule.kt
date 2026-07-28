package com.alarmcontrol.ml.di

import android.content.Context
import com.alarmcontrol.core.coroutines.AppDispatcher
import com.alarmcontrol.core.coroutines.ApplicationScope
import com.alarmcontrol.core.coroutines.Dispatcher
import com.alarmcontrol.core.feedback.AdFeedbackRepository
import com.alarmcontrol.core.feedback.FeedbackRepository
import com.alarmcontrol.core.filtering.SemanticIntent
import com.alarmcontrol.ml.MlConfig
import com.alarmcontrol.ml.NotificationCategories
import com.alarmcontrol.ml.NotificationClassifier
import com.alarmcontrol.ml.SemanticNotificationClassifier
import com.alarmcontrol.ml.asset.ModelAssets
import com.alarmcontrol.ml.classifier.LiteRTNotificationClassifier
import com.alarmcontrol.ml.classifier.LiteRTSemanticNotificationClassifier
import com.alarmcontrol.ml.classifier.UnavailableSemanticNotificationClassifier
import com.alarmcontrol.ml.feature.BagOfWordsFeatureExtractor
import com.alarmcontrol.ml.feedback.RepositoryFeedbackBlender
import com.alarmcontrol.ml.inference.BundledTfLiteBackend
import com.alarmcontrol.ml.llm.AndroidModelStorageGuard
import com.alarmcontrol.ml.llm.DefaultOnDeviceLlmManager
import com.alarmcontrol.ml.llm.LocalLlmModelStore
import com.alarmcontrol.ml.llm.MediaPipeLlmEngine
import com.alarmcontrol.ml.llm.OnDeviceLlmManager
import com.alarmcontrol.ml.llm.RepositoryLlmFeedbackAdjuster
import com.alarmcontrol.ml.semantic.LiteRTSemanticEncoder
import com.alarmcontrol.ml.semantic.RepositorySemanticFeedbackBlender
import com.alarmcontrol.ml.semantic.SemanticModelAssets
import com.alarmcontrol.ml.semantic.WordPieceTokenizer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import java.io.File
import javax.inject.Singleton

/**
 * Wires the on-device classifier and optional local LLM. Public contracts are exposed while feature
 * extractors, native backends, model storage, and implementations stay internal (§5).
 */
@Module
@InstallIn(SingletonComponent::class)
object MlModule {
    @Provides
    @Singleton
    fun provideNotificationClassifier(
        @ApplicationContext context: Context,
        feedbackRepository: FeedbackRepository,
        @ApplicationScope applicationScope: CoroutineScope,
    ): NotificationClassifier {
        // Vocab and labels are loaded from the bundled assets the trainer emits next to the model,
        // so their order always matches the .tflite. Missing assets -> empty lists -> the backend
        // also finds no model -> classification degrades to rule-only filtering (§5).
        val vocabulary = ModelAssets.readLines(context, MlConfig.VOCAB_ASSET)
        val labels = ModelAssets.readLines(context, MlConfig.LABELS_ASSET)
        return LiteRTNotificationClassifier(
            featureExtractor = BagOfWordsFeatureExtractor(vocabulary),
            backend = BundledTfLiteBackend(context, MlConfig.MODEL_ASSET, labels.size),
            labels = labels,
            confidenceThreshold = MlConfig.CONFIDENCE_THRESHOLD,
            feedbackBlender = RepositoryFeedbackBlender.from(feedbackRepository, applicationScope),
        )
    }

    @Provides
    @Singleton
    fun provideSemanticNotificationClassifier(
        @ApplicationContext context: Context,
        adFeedbackRepository: AdFeedbackRepository,
        @ApplicationScope applicationScope: CoroutineScope,
    ): SemanticNotificationClassifier {
        val assets =
            SemanticModelAssets.load(
                context = context,
                manifestAsset = MlConfig.SEMANTIC_MODEL_MANIFEST_ASSET,
                modelAsset = MlConfig.SEMANTIC_MODEL_ASSET,
                vocabularyAsset = MlConfig.SEMANTIC_VOCAB_ASSET,
                labelsAsset = MlConfig.SEMANTIC_LABELS_ASSET,
            )
                ?: return UnavailableSemanticNotificationClassifier
        val labels = assets.labels
        if (labels != SemanticIntent.entries.toList()) {
            return UnavailableSemanticNotificationClassifier
        }
        val encoder =
            LiteRTSemanticEncoder(
                context = context,
                modelAsset = MlConfig.SEMANTIC_MODEL_ASSET,
                tokenizer =
                    WordPieceTokenizer(
                        vocabulary = assets.vocabulary,
                        maxSequenceLength = assets.maxSequenceLength,
                    ),
                maxSequenceLength = assets.maxSequenceLength,
                outputSize = labels.size,
                expectedInputNames = assets.inputNames,
                expectedModelSha256 = assets.modelSha256,
                expectedModelSizeBytes = assets.modelSizeBytes,
            )
        return LiteRTSemanticNotificationClassifier(
            encoder = encoder,
            labels = labels,
            confidenceThresholds = assets.confidenceThresholds,
            feedbackBlender =
                RepositorySemanticFeedbackBlender.from(
                    adFeedbackRepository,
                    applicationScope,
                ),
        )
    }

    /** The categories the recategorize UI offers — sourced from the bundled labels asset (§5). */
    @Provides
    @Singleton
    fun provideNotificationCategories(
        @ApplicationContext context: Context,
    ): NotificationCategories = NotificationCategories(ModelAssets.readLines(context, MlConfig.LABELS_ASSET))

    /**
     * The on-device LLM context analyzer (Milestone 4). Loads a LOCAL MediaPipe model on the IO
     * dispatcher; if the model file is absent it reports unavailable and callers fall back (§5). Only
     * the [OnDeviceLlmManager] interface is exposed; the engine and impl stay internal.
     */
    @Provides
    @Singleton
    fun provideOnDeviceLlmManager(
        @ApplicationContext context: Context,
        adFeedbackRepository: AdFeedbackRepository,
        @ApplicationScope applicationScope: CoroutineScope,
        @Dispatcher(AppDispatcher.IO) ioDispatcher: CoroutineDispatcher,
    ): OnDeviceLlmManager {
        val modelFile = File(context.filesDir, MlConfig.LLM_MODEL_FILE)
        return DefaultOnDeviceLlmManager(
            engine = MediaPipeLlmEngine(context, modelFile),
            dispatcher = ioDispatcher,
            modelStore =
                LocalLlmModelStore(
                    modelFile = modelFile,
                    storageGuard = AndroidModelStorageGuard(context),
                ),
            feedbackAdjuster = RepositoryLlmFeedbackAdjuster.from(adFeedbackRepository, applicationScope),
        )
    }
}
