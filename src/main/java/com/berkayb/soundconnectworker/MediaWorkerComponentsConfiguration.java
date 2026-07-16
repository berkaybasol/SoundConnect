package com.berkayb.soundconnectworker;

import com.berkayb.soundconnect.modules.media.image.FfmpegImageThumbnailProcessor;
import com.berkayb.soundconnect.modules.media.image.ImageThumbnailService;
import com.berkayb.soundconnect.modules.media.image.ImageVariantBackfillFinalizer;
import com.berkayb.soundconnect.modules.media.image.ImageVariantBackfillScheduler;
import com.berkayb.soundconnect.modules.media.image.ImageVariantBackfillService;
import com.berkayb.soundconnect.modules.media.image.ImageVariantJobDispatcher;
import com.berkayb.soundconnect.modules.media.image.ImageVariantWorkerConfiguration;
import com.berkayb.soundconnect.modules.media.image.MediaImageVariantProperties;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicyImpl;
import com.berkayb.soundconnect.modules.media.storage.S3StorageClient;
import com.berkayb.soundconnect.modules.media.transcode.MediaAssetStatusUpdater;
import com.berkayb.soundconnect.modules.media.transcode.MediaHlsWorkExecutor;
import com.berkayb.soundconnect.modules.media.transcode.MediaRabbitListenerConfiguration;
import com.berkayb.soundconnect.modules.media.transcode.MediaTranscodeLeaseHeartbeat;
import com.berkayb.soundconnect.modules.media.transcode.VideoHlsListener;
import com.berkayb.soundconnect.modules.media.transcode.VideoHlsWorkflow;
import com.berkayb.soundconnect.modules.media.transcode.config.MediaTranscodeLeaseProperties;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfmpegService;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfprobeService;
import com.berkayb.soundconnect.modules.media.transcode.upload.HlsUploader;
import com.berkayb.soundconnect.modules.media.transcode.validation.TranscodeDiskSpaceInspector;
import com.berkayb.soundconnect.modules.media.transcode.validation.TranscodeTempBudgetManager;
import com.berkayb.soundconnect.modules.media.transcode.validation.VideoTranscodeResourceValidator;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Explicit allow-list for the worker's application capabilities. */
@Configuration(proxyBeanMethods = false)
@Import({
		MediaWorkerExecutionConfiguration.class,
		MediaWorkerRabbitConnectionConfiguration.class,
		MediaWorkerSafetyValidator.class,
		MediaWorkerHealthFile.class,
		S3StorageClient.class,
		MediaPolicyImpl.class,
		TranscodeProperties.class,
		MediaTranscodeLeaseProperties.class,
		MediaImageVariantProperties.class,
		MediaRabbitListenerConfiguration.class,
		MediaAssetStatusUpdater.class,
		MediaTranscodeLeaseHeartbeat.class,
		TranscodeDiskSpaceInspector.class,
		VideoTranscodeResourceValidator.class,
		TranscodeTempBudgetManager.class,
		FfmpegService.class,
		FfprobeService.class,
		HlsUploader.class,
		VideoHlsWorkflow.class,
		MediaHlsWorkExecutor.class,
		VideoHlsListener.class,
		ImageVariantWorkerConfiguration.class,
		FfmpegImageThumbnailProcessor.class,
		ImageThumbnailService.class,
		ImageVariantBackfillFinalizer.class,
		ImageVariantBackfillService.class,
		ImageVariantJobDispatcher.class,
		ImageVariantBackfillScheduler.class
})
public class MediaWorkerComponentsConfiguration {
}
