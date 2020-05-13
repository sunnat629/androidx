/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.camera.core;

import static androidx.camera.core.impl.ImageAnalysisConfig.OPTION_BACKPRESSURE_STRATEGY;
import static androidx.camera.core.impl.ImageAnalysisConfig.OPTION_IMAGE_QUEUE_DEPTH;
import static androidx.camera.core.impl.ImageAnalysisConfig.OPTION_IMAGE_READER_PROXY_PROVIDER;
import static androidx.camera.core.impl.ImageOutputConfig.OPTION_MAX_RESOLUTION;
import static androidx.camera.core.impl.ImageOutputConfig.OPTION_SUPPORTED_RESOLUTIONS;
import static androidx.camera.core.impl.ImageOutputConfig.OPTION_TARGET_ASPECT_RATIO;
import static androidx.camera.core.impl.ImageOutputConfig.OPTION_TARGET_ASPECT_RATIO_CUSTOM;
import static androidx.camera.core.impl.ImageOutputConfig.OPTION_TARGET_RESOLUTION;
import static androidx.camera.core.impl.ImageOutputConfig.OPTION_TARGET_ROTATION;
import static androidx.camera.core.impl.UseCaseConfig.OPTION_CAMERA_SELECTOR;
import static androidx.camera.core.impl.UseCaseConfig.OPTION_CAPTURE_CONFIG_UNPACKER;
import static androidx.camera.core.impl.UseCaseConfig.OPTION_DEFAULT_CAPTURE_CONFIG;
import static androidx.camera.core.impl.UseCaseConfig.OPTION_DEFAULT_SESSION_CONFIG;
import static androidx.camera.core.impl.UseCaseConfig.OPTION_SESSION_CONFIG_UNPACKER;
import static androidx.camera.core.impl.UseCaseConfig.OPTION_SURFACE_OCCUPANCY_PRIORITY;
import static androidx.camera.core.impl.UseCaseConfig.OPTION_TARGET_CLASS;
import static androidx.camera.core.impl.UseCaseConfig.OPTION_TARGET_NAME;
import static androidx.camera.core.impl.UseCaseConfig.OPTION_USE_CASE_EVENT_CALLBACK;
import static androidx.camera.core.internal.ThreadConfig.OPTION_BACKGROUND_EXECUTOR;

import android.media.ImageReader;
import android.util.Log;
import android.util.Pair;
import android.util.Rational;
import android.util.Size;
import android.view.Display;
import android.view.Surface;

import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.camera.core.impl.CameraInfoInternal;
import androidx.camera.core.impl.CaptureConfig;
import androidx.camera.core.impl.ConfigProvider;
import androidx.camera.core.impl.DeferrableSurface;
import androidx.camera.core.impl.ImageAnalysisConfig;
import androidx.camera.core.impl.ImageOutputConfig;
import androidx.camera.core.impl.ImageOutputConfig.RotationValue;
import androidx.camera.core.impl.ImageReaderProxy;
import androidx.camera.core.impl.ImmediateSurface;
import androidx.camera.core.impl.MutableConfig;
import androidx.camera.core.impl.MutableOptionsBundle;
import androidx.camera.core.impl.OptionsBundle;
import androidx.camera.core.impl.SessionConfig;
import androidx.camera.core.impl.UseCaseConfig;
import androidx.camera.core.impl.utils.Threads;
import androidx.camera.core.impl.utils.executor.CameraXExecutors;
import androidx.camera.core.internal.TargetConfig;
import androidx.camera.core.internal.ThreadConfig;
import androidx.camera.core.internal.utils.UseCaseConfigUtil;
import androidx.core.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * A use case providing CPU accessible images for an app to perform image analysis on.
 *
 * <p>ImageAnalysis acquires images from the camera via an {@link ImageReader}. Each image
 * is provided to an {@link ImageAnalysis.Analyzer} function which can be implemented by application
 * code, where it can access image data for application analysis via an {@link ImageProxy}.
 *
 * <p>The application is responsible for calling {@link ImageProxy#close()} to close the image.
 * Failing to close the image will cause future images to be stalled or dropped depending on the
 * backpressure strategy.
 */
public final class ImageAnalysis extends UseCase {

    /**
     * Only deliver the latest image to the analyzer, dropping images as they arrive.
     *
     * <p>This strategy ignores the value set by {@link Builder#setImageQueueDepth(int)}.
     * Only one image will be delivered for analysis at a time. If more images are produced
     * while that image is being analyzed, they will be dropped and not queued for delivery.
     * Once the image being analyzed is closed by calling {@link ImageProxy#close()}, the
     * next latest image will be delivered.
     *
     * <p>Internally this strategy may make use of an internal {@link Executor} to receive
     * and drop images from the producer. A performance-tuned executor will be created
     * internally unless one is explicitly provided by
     * {@link Builder#setBackgroundExecutor(Executor)}. In order to
     * ensure smooth operation of this backpressure strategy, any user supplied
     * {@link Executor} must be able to quickly respond to tasks posted to it, so setting
     * the executor manually should only be considered in advanced use cases.
     *
     * @see Builder#setBackgroundExecutor(Executor)
     */
    public static final int STRATEGY_KEEP_ONLY_LATEST = 0;
    /**
     * Block the producer from generating new images.
     *
     * <p>Once the producer has produced the number of images equal to the image queue depth,
     * and none have been closed, the producer will stop producing images. Note that images
     * may be queued internally and not be delivered to the analyzer until the last delivered
     * image has been closed with {@link ImageProxy#close()}. These internally queued images
     * will count towards the total number of images that the producer can provide at any one
     * time.
     *
     * <p>When the producer stops producing images, it may also stop producing images for
     * other use cases, such as {@link Preview}, so it is important for the analyzer to keep
     * up with frame rate, <i>on average</i>. Failure to keep up with frame rate may lead to
     * jank in the frame stream and a diminished user experience. If more time is needed for
     * analysis on <i>some</i> frames, consider increasing the image queue depth with
     * {@link Builder#setImageQueueDepth(int)}.
     *
     * @see Builder#setImageQueueDepth(int)
     */
    public static final int STRATEGY_BLOCK_PRODUCER = 1;

    /**
     * Provides a static configuration with implementation-agnostic options.
     *
     * @hide
     */
    @RestrictTo(Scope.LIBRARY_GROUP)
    public static final Defaults DEFAULT_CONFIG = new Defaults();
    private static final String TAG = "ImageAnalysis";
    // ImageReader depth for KEEP_ONLY_LATEST mode.
    private static final int NON_BLOCKING_IMAGE_DEPTH = 4;

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final ImageAnalysisAbstractAnalyzer mImageAnalysisAbstractAnalyzer;
    @GuardedBy("mAnalysisLock")
    private ImageAnalysis.Analyzer mSubscribedAnalyzer;

    @Nullable
    private DeferrableSurface mDeferrableSurface;

    private final Object mAnalysisLock = new Object();

    /**
     * Creates a new image analysis use case from the given configuration.
     *
     * @param config for this use case instance
     */
    @SuppressWarnings("WeakerAccess")
    ImageAnalysis(@NonNull ImageAnalysisConfig config) {
        super(config);

        // Get the combined configuration with defaults
        ImageAnalysisConfig combinedConfig = (ImageAnalysisConfig) getUseCaseConfig();

        if (combinedConfig.getBackpressureStrategy() == STRATEGY_BLOCK_PRODUCER) {
            mImageAnalysisAbstractAnalyzer = new ImageAnalysisBlockingAnalyzer();
        } else {
            mImageAnalysisAbstractAnalyzer = new ImageAnalysisNonBlockingAnalyzer(
                    config.getBackgroundExecutor(CameraXExecutors.highPriorityExecutor()));
        }
    }

    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    SessionConfig.Builder createPipeline(@NonNull String cameraId,
            @NonNull ImageAnalysisConfig config, @NonNull Size resolution) {
        Threads.checkMainThread();

        Executor backgroundExecutor = Preconditions.checkNotNull(config.getBackgroundExecutor(
                CameraXExecutors.highPriorityExecutor()));

        int imageQueueDepth =
                config.getBackpressureStrategy() == STRATEGY_BLOCK_PRODUCER
                        ? config.getImageQueueDepth() : NON_BLOCKING_IMAGE_DEPTH;
        ImageReaderProxy imageReaderProxy;
        if (config.getImageReaderProxyProvider() != null) {
            imageReaderProxy = config.getImageReaderProxyProvider().newInstance(
                    resolution.getWidth(), resolution.getHeight(), getImageFormat(),
                    imageQueueDepth, 0);
        } else {
            imageReaderProxy = ImageReaderProxys.createIsolatedReader(
                    resolution.getWidth(),
                    resolution.getHeight(),
                    getImageFormat(),
                    imageQueueDepth);
        }

        tryUpdateRelativeRotation();

        mImageAnalysisAbstractAnalyzer.open();
        imageReaderProxy.setOnImageAvailableListener(mImageAnalysisAbstractAnalyzer,
                backgroundExecutor);

        SessionConfig.Builder sessionConfigBuilder = SessionConfig.Builder.createFrom(config);

        if (mDeferrableSurface != null) {
            mDeferrableSurface.close();
        }
        mDeferrableSurface = new ImmediateSurface(imageReaderProxy.getSurface());
        mDeferrableSurface.getTerminationFuture().addListener(imageReaderProxy::close,
                CameraXExecutors.mainThreadExecutor());

        sessionConfigBuilder.addSurface(mDeferrableSurface);

        sessionConfigBuilder.addErrorListener(new SessionConfig.ErrorListener() {
            @Override
            public void onError(@NonNull SessionConfig sessionConfig,
                    @NonNull SessionConfig.SessionError error) {
                clearPipeline();

                // Ensure the attached camera has not changed before resetting.
                // TODO(b/143915543): Ensure this never gets called by a camera that is not attached
                //  to this use case so we don't need to do this check.
                if (isCurrentCamera(cameraId)) {
                    // Only reset the pipeline when the bound camera is the same.
                    SessionConfig.Builder sessionConfigBuilder = createPipeline(cameraId, config,
                            resolution);
                    updateSessionConfig(sessionConfigBuilder.build());

                    notifyReset();
                }
            }
        });

        return sessionConfigBuilder;
    }

    /**
     * Clear the internal pipeline so that the pipeline can be set up again.
     */
    @SuppressWarnings("WeakerAccess") /* synthetic accessor */
    void clearPipeline() {
        Threads.checkMainThread();
        mImageAnalysisAbstractAnalyzer.close();

        if (mDeferrableSurface != null) {
            mDeferrableSurface.close();
            mDeferrableSurface = null;
        }
    }

    /**
     * Removes a previously set analyzer.
     *
     * <p>This will stop data from streaming to the {@link ImageAnalysis}.
     */
    public void clearAnalyzer() {
        synchronized (mAnalysisLock) {
            mImageAnalysisAbstractAnalyzer.setAnalyzer(null, null);
            mImageAnalysisAbstractAnalyzer.close();
            if (mSubscribedAnalyzer != null) {
                notifyInactive();
            }
            mSubscribedAnalyzer = null;
        }
    }

    /**
     * Sets the target rotation.
     *
     * <p>This adjust the {@link ImageInfo#getRotationDegrees()} of the {@link ImageProxy} passed
     * to {@link Analyzer#analyze(ImageProxy)}. The rotation value of ImageInfo will be the
     * rotation, which if applied to the output image, will make the image match target rotation
     * specified here.
     *
     * <p>While rotation can also be set via {@link Builder#setTargetRotation(int)}, using
     * {@link ImageAnalysis#setTargetRotation(int)} allows the target rotation to be set
     * dynamically.
     *
     * <p>In general, it is best to use an {@link android.view.OrientationEventListener} to
     * set the target rotation.  This way, the rotation output to the Analyzer will indicate
     * which way is down for a given image.  This is important since display orientation may be
     * locked by device default, user setting, or app configuration, and some devices may not
     * transition to a reverse-portrait display orientation.  In these cases, use
     * {@link ImageAnalysis#setTargetRotation} to set target rotation dynamically according to
     * the {@link android.view.OrientationEventListener}, without re-creating the use case. Note
     * the OrientationEventListener output of degrees in the range [0..359] should be converted to
     * a surface rotation, i.e. one of {@link Surface#ROTATION_0}, {@link Surface#ROTATION_90},
     * {@link Surface#ROTATION_180}, or {@link Surface#ROTATION_270}.
     *
     * <p>When this function is called, value set by
     * {@link ImageAnalysis.Builder#setTargetResolution(Size)} will be updated automatically to
     * make sure the suitable resolution can be selected when the use case is bound.
     *
     * <p>If not set here or by configuration, the target rotation will default to the value of
     * {@link Display#getRotation()} of the default display at the time the
     * use case is created.
     *
     * @param rotation Target rotation of the output image, expressed as one of
     *                 {@link Surface#ROTATION_0}, {@link Surface#ROTATION_90},
     *                 {@link Surface#ROTATION_180}, or {@link Surface#ROTATION_270}.
     */
    public void setTargetRotation(@RotationValue int rotation) {
        ImageAnalysisConfig oldConfig = (ImageAnalysisConfig) getUseCaseConfig();
        Builder builder = Builder.fromConfig(oldConfig);
        int oldRotation = oldConfig.getTargetRotation(ImageOutputConfig.INVALID_ROTATION);
        if (oldRotation == ImageOutputConfig.INVALID_ROTATION || oldRotation != rotation) {
            UseCaseConfigUtil.updateTargetRotationAndRelatedConfigs(builder, rotation);
            updateUseCaseConfig(builder.getUseCaseConfig());

            // TODO(b/122846516): Update session configuration and possibly reconfigure session.
            // For now we'll just update the relative rotation value.
            // Attempt to get the camera ID and update the relative rotation. If we can't, we
            // probably
            // don't yet have permission, so we will try again in onSuggestedResolutionUpdated().
            // Old
            // configuration lens facing should match new configuration.
            try {
                tryUpdateRelativeRotation();
            } catch (Exception e) {
                // Likely don't yet have permissions. This is expected if this method is called
                // before
                // this use case becomes active. That's OK though since we've updated the use case
                // configuration. We'll try to update relative rotation again in
                // onSuggestedResolutionUpdated().
                Log.w(TAG, "Unable to get camera id for the use case.");
            }
        }
    }

    /**
     * Returns the rotation of the intended target for images.
     *
     * <p>
     * The rotation can be set when constructing an {@link ImageAnalysis} instance using
     * {@link ImageAnalysis.Builder#setTargetRotation(int)}, or dynamically by calling
     * {@link ImageAnalysis#setTargetRotation(int)}. If not set, the target rotation defaults to
     * the value of {@link Display#getRotation()} of the default display at the time the use case
     * is created.
     * </p>
     *
     * @return The rotation of the intended target for images.
     * @see ImageAnalysis#setTargetRotation(int)
     */
    @RotationValue
    public int getTargetRotation() {
        return ((ImageAnalysisConfig) getUseCaseConfig()).getTargetRotation();
    }

    /**
     * Sets an analyzer to receive and analyze images.
     *
     * <p>Setting an analyzer will signal to the camera that it should begin sending data. The
     * stream of data can be stopped by calling {@link #clearAnalyzer()}.
     *
     * <p>Applications can process or copy the image by implementing the {@link Analyzer}.  If
     * frames should be skipped (no analysis), the analyzer function should return, instead of
     * disconnecting the analyzer function completely.
     *
     * <p>Setting an analyzer function replaces any previous analyzer.  Only one analyzer can be
     * set at any time.
     *
     * @param executor The executor in which the
     *                 {@link ImageAnalysis.Analyzer#analyze(ImageProxy)} will be run.
     * @param analyzer of the images.
     */
    public void setAnalyzer(@NonNull Executor executor, @NonNull Analyzer analyzer) {
        synchronized (mAnalysisLock) {
            mImageAnalysisAbstractAnalyzer.open();
            mImageAnalysisAbstractAnalyzer.setAnalyzer(executor, image -> {
                if (getViewPortCropRect() != null) {
                    image.setViewPortRect(getViewPortCropRect());
                    image.setCropRect(getViewPortCropRect());
                }
                analyzer.analyze(image);
            });
            if (mSubscribedAnalyzer == null) {
                notifyActive();
            }
            mSubscribedAnalyzer = analyzer;
        }
    }

    /**
     * Returns the mode with which images are acquired from the {@linkplain ImageReader image
     * producer}.
     *
     * <p>
     * The backpressure strategy is set when constructing an {@link ImageAnalysis} instance using
     * {@link ImageAnalysis.Builder#setBackpressureStrategy(int)}. If not set, it defaults to
     * {@link ImageAnalysis#STRATEGY_KEEP_ONLY_LATEST}.
     * </p>
     *
     * @return The backpressure strategy applied to the image producer.
     * @see ImageAnalysis.Builder#setBackpressureStrategy(int)
     */
    @BackpressureStrategy
    public int getBackpressureStrategy() {
        return ((ImageAnalysisConfig) getUseCaseConfig()).getBackpressureStrategy();
    }

    /**
     * Returns the number of images available to the camera pipeline, including the image being
     * analyzed, for the {@link #STRATEGY_BLOCK_PRODUCER} backpressure mode.
     *
     * <p>
     * The image queue depth is set when constructing an {@link ImageAnalysis} instance using
     * {@link ImageAnalysis.Builder#setImageQueueDepth(int)}. If not set, and this option is used
     * by the backpressure strategy, the default will be a queue depth of 6 images.
     * </p>
     *
     * @return The image queue depth for the {@link #STRATEGY_BLOCK_PRODUCER} backpressure mode.
     * @see ImageAnalysis.Builder#setImageQueueDepth(int)
     * @see ImageAnalysis.Builder#setBackpressureStrategy(int)
     */
    public int getImageQueueDepth() {
        return ((ImageAnalysisConfig) getUseCaseConfig()).getImageQueueDepth();
    }

    @Override
    @NonNull
    public String toString() {
        return TAG + ":" + getName();
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @RestrictTo(Scope.LIBRARY_GROUP)
    @Override
    public void clear() {
        clearPipeline();
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @RestrictTo(Scope.LIBRARY_GROUP)
    @Override
    public void onDestroy() {
        clearAnalyzer();
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @Override
    @Nullable
    @RestrictTo(Scope.LIBRARY_GROUP)
    protected UseCaseConfig.Builder<?, ?, ?> getDefaultBuilder(@Nullable CameraInfo cameraInfo) {
        ImageAnalysisConfig defaults = CameraX.getDefaultUseCaseConfig(ImageAnalysisConfig.class,
                cameraInfo);
        if (defaults != null) {
            return Builder.fromConfig(defaults);
        }

        return null;
    }

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @Override
    @RestrictTo(Scope.LIBRARY_GROUP)
    @NonNull
    protected Size onSuggestedResolutionUpdated(@NonNull Size suggestedResolution) {
        final ImageAnalysisConfig config = (ImageAnalysisConfig) getUseCaseConfig();

        SessionConfig.Builder sessionConfigBuilder = createPipeline(getCameraId(), config,
                suggestedResolution);
        updateSessionConfig(sessionConfigBuilder.build());

        return suggestedResolution;
    }

    private void tryUpdateRelativeRotation() {
        ImageOutputConfig config = (ImageOutputConfig) getUseCaseConfig();
        CameraInfoInternal cameraInfoInternal = getCamera().getCameraInfoInternal();
        mImageAnalysisAbstractAnalyzer.setRelativeRotation(
                cameraInfoInternal.getSensorRotationDegrees(
                        config.getTargetRotation(Surface.ROTATION_0)));
    }

    /**
     * How to apply backpressure to the source producing images for analysis.
     *
     * <p>Sometimes, images may be produced faster than they can be analyzed. Since images
     * generally reserve a large portion of the device's memory, they cannot be buffered
     * unbounded and indefinitely. The backpressure strategy defines how to deal with this scenario.
     *
     * <p>The receiver of the {@link ImageProxy} is responsible for explicitly closing the image
     * by calling {@link ImageProxy#close()}. However, the image will only be valid when the
     * ImageAnalysis instance is bound to a camera.
     *
     * @hide
     * @see Builder#setBackpressureStrategy(int)
     */
    @IntDef({STRATEGY_KEEP_ONLY_LATEST, STRATEGY_BLOCK_PRODUCER})
    @Retention(RetentionPolicy.SOURCE)
    @RestrictTo(Scope.LIBRARY_GROUP)
    public @interface BackpressureStrategy {
    }

    /**
     * Interface for analyzing images.
     *
     * <p>Implement Analyzer and pass it to {@link ImageAnalysis#setAnalyzer(Executor, Analyzer)}
     * to receive images and perform custom processing by implementing the
     * {@link ImageAnalysis.Analyzer#analyze(ImageProxy)} function.
     */
    public interface Analyzer {
        /**
         * Analyzes an image to produce a result.
         *
         * <p>This method is called once for each image from the camera, and called at the
         * frame rate of the camera. Each analyze call is executed sequentially.
         *
         * <p>It is the responsibility of the application to close the image once done with it.
         * If the images are not closed then it may block further images from being produced
         * (causing the preview to stall) or drop images as determined by the configured
         * backpressure strategy. The exact behavior is configurable via
         * {@link ImageAnalysis.Builder#setBackpressureStrategy(int)}.
         *
         * <p>Images produced here will no longer be valid after the {@link ImageAnalysis}
         * instance that produced it has been unbound from the camera.
         *
         * <p>The image provided has format {@link android.graphics.ImageFormat#YUV_420_888}.
         *
         * <p>The provided image is typically in the orientation of the sensor, meaning CameraX
         * does not perform an internal rotation of the data.  The rotationDegrees parameter allows
         * the analysis to understand the image orientation when processing or to apply a rotation.
         * For example, if the
         * {@linkplain ImageAnalysis#setTargetRotation(int) target rotation}) is natural
         * orientation, rotationDegrees would be the rotation which would align the buffer
         * data ordering to natural orientation.
         *
         * <p>Timestamps are in nanoseconds and monotonic and can be compared to timestamps from
         * images produced from UseCases bound to the same camera instance.  More detail is
         * available depending on the implementation.  For example with CameraX using a
         * {@link androidx.camera.camera2} implementation additional detail can be found in
         * {@link android.hardware.camera2.CameraDevice} documentation.
         *
         * @param image The image to analyze
         * @see android.media.Image#getTimestamp()
         * @see android.hardware.camera2.CaptureResult#SENSOR_TIMESTAMP
         */
        void analyze(@NonNull ImageProxy image);
    }

    /**
     * Provides a base static default configuration for the ImageAnalysis.
     *
     * <p>These values may be overridden by the implementation. They only provide a minimum set of
     * defaults that are implementation independent.
     *
     * @hide
     */
    @RestrictTo(Scope.LIBRARY_GROUP)
    public static final class Defaults implements ConfigProvider<ImageAnalysisConfig> {
        @BackpressureStrategy
        private static final int DEFAULT_BACKPRESSURE_STRATEGY = STRATEGY_KEEP_ONLY_LATEST;
        private static final int DEFAULT_IMAGE_QUEUE_DEPTH = 6;
        private static final Size DEFAULT_TARGET_RESOLUTION = new Size(640, 480);
        private static final Size DEFAULT_MAX_RESOLUTION = new Size(1920, 1080);
        private static final int DEFAULT_SURFACE_OCCUPANCY_PRIORITY = 1;

        private static final ImageAnalysisConfig DEFAULT_CONFIG;

        static {
            Builder builder = new Builder()
                    .setBackpressureStrategy(DEFAULT_BACKPRESSURE_STRATEGY)
                    .setImageQueueDepth(DEFAULT_IMAGE_QUEUE_DEPTH)
                    .setDefaultResolution(DEFAULT_TARGET_RESOLUTION)
                    .setMaxResolution(DEFAULT_MAX_RESOLUTION)
                    .setSurfaceOccupancyPriority(DEFAULT_SURFACE_OCCUPANCY_PRIORITY);

            DEFAULT_CONFIG = builder.getUseCaseConfig();
        }

        @NonNull
        @Override
        public ImageAnalysisConfig getConfig(@Nullable CameraInfo cameraInfo) {
            return DEFAULT_CONFIG;
        }
    }

    /** Builder for a {@link ImageAnalysis}. */
    public static final class Builder
            implements ImageOutputConfig.Builder<Builder>,
            ThreadConfig.Builder<Builder>,
            UseCaseConfig.Builder<ImageAnalysis, ImageAnalysisConfig, Builder> {

        private final MutableOptionsBundle mMutableConfig;

        /** Creates a new Builder object. */
        public Builder() {
            this(MutableOptionsBundle.create());
        }

        private Builder(MutableOptionsBundle mutableConfig) {
            mMutableConfig = mutableConfig;

            Class<?> oldConfigClass =
                    mutableConfig.retrieveOption(TargetConfig.OPTION_TARGET_CLASS, null);
            if (oldConfigClass != null && !oldConfigClass.equals(ImageAnalysis.class)) {
                throw new IllegalArgumentException(
                        "Invalid target class configuration for "
                                + Builder.this
                                + ": "
                                + oldConfigClass);
            }

            setTargetClass(ImageAnalysis.class);
        }

        /**
         * Generates a Builder from another Config object.
         *
         * @param configuration An immutable configuration to pre-populate this builder.
         * @return The new Builder.
         * @hide
         */
        @RestrictTo(Scope.LIBRARY_GROUP)
        @NonNull
        public static Builder fromConfig(@NonNull ImageAnalysisConfig configuration) {
            return new Builder(MutableOptionsBundle.from(configuration));
        }

        /**
         * Sets the backpressure strategy to apply to the image producer to deal with scenarios
         * where images may be produced faster than they can be analyzed.
         *
         * <p>The available values are {@link #STRATEGY_BLOCK_PRODUCER} and
         * {@link #STRATEGY_KEEP_ONLY_LATEST}.
         *
         * <p>If not set, the backpressure strategy will default to
         * {@link #STRATEGY_KEEP_ONLY_LATEST}.
         *
         * @param strategy The strategy to use.
         * @return The current Builder.
         */
        @NonNull
        public Builder setBackpressureStrategy(@BackpressureStrategy int strategy) {
            getMutableConfig().insertOption(OPTION_BACKPRESSURE_STRATEGY, strategy);
            return this;
        }

        /**
         * Sets the number of images available to the camera pipeline for
         * {@link #STRATEGY_BLOCK_PRODUCER} mode.
         *
         * <p>The image queue depth is the number of images available to the camera to fill with
         * data. This includes the image currently being analyzed by {@link
         * ImageAnalysis.Analyzer#analyze(ImageProxy)}. Increasing the image queue depth
         * may make camera operation smoother, depending on the backpressure strategy, at
         * the cost of increased memory usage.
         *
         * <p>When the backpressure strategy is set to {@link #STRATEGY_BLOCK_PRODUCER},
         * increasing the image queue depth may make the camera pipeline run smoother on systems
         * under high load. However, the time spent analyzing an image should still be kept under
         * a single frame period for the current frame rate, <i>on average</i>, to avoid stalling
         * the camera pipeline.
         *
         * <p>The value only applies to {@link #STRATEGY_BLOCK_PRODUCER} mode.
         * For {@link #STRATEGY_KEEP_ONLY_LATEST} the value is ignored.
         *
         * <p>If not set, and this option is used by the selected backpressure strategy,
         * the default will be a queue depth of 6 images.
         *
         * @param depth The total number of images available to the camera.
         * @return The current Builder.
         */
        @NonNull
        public Builder setImageQueueDepth(int depth) {
            getMutableConfig().insertOption(OPTION_IMAGE_QUEUE_DEPTH, depth);
            return this;
        }

        /**
         * {@inheritDoc}
         *
         * @hide
         */
        @RestrictTo(Scope.LIBRARY_GROUP)
        @Override
        @NonNull
        public MutableConfig getMutableConfig() {
            return mMutableConfig;
        }

        /**
         * {@inheritDoc}
         *
         * @hide
         */
        @RestrictTo(Scope.LIBRARY_GROUP)
        @NonNull
        @Override
        public ImageAnalysisConfig getUseCaseConfig() {
            return new ImageAnalysisConfig(OptionsBundle.from(mMutableConfig));
        }

        /**
         * Builds an {@link ImageAnalysis} from the current state.
         *
         * @return A {@link ImageAnalysis} populated with the current state.
         * @throws IllegalArgumentException if attempting to set both target aspect ratio and
         *                                  target resolution.
         */
        @Override
        @NonNull
        public ImageAnalysis build() {
            // Error at runtime for using both setTargetResolution and setTargetAspectRatio on
            // the same config.
            if (getMutableConfig().retrieveOption(OPTION_TARGET_ASPECT_RATIO, null) != null
                    && getMutableConfig().retrieveOption(OPTION_TARGET_RESOLUTION, null) != null) {
                throw new IllegalArgumentException(
                        "Cannot use both setTargetResolution and setTargetAspectRatio on the same"
                                + " config.");
            }
            return new ImageAnalysis(getUseCaseConfig());
        }

        // Implementations of TargetConfig.Builder default methods

        /** @hide */
        @RestrictTo(Scope.LIBRARY_GROUP)
        @Override
        @NonNull
        public Builder setTargetClass(@NonNull Class<ImageAnalysis> targetClass) {
            getMutableConfig().insertOption(OPTION_TARGET_CLASS, targetClass);

            // If no name is set yet, then generate a unique name
            if (null == getMutableConfig().retrieveOption(OPTION_TARGET_NAME, null)) {
                String targetName = targetClass.getCanonicalName() + "-" + UUID.randomUUID();
                setTargetName(targetName);
            }

            return this;
        }

        /**
         * Sets the name of the target object being configured, used only for debug logging.
         *
         * <p>The name should be a value that can uniquely identify an instance of the object being
         * configured.
         *
         * <p>If not set, the target name will default to a unique name automatically generated
         * with the class canonical name and random UUID.
         *
         * @param targetName A unique string identifier for the instance of the class being
         *                   configured.
         * @return the current Builder.
         */
        @Override
        @NonNull
        public Builder setTargetName(@NonNull String targetName) {
            getMutableConfig().insertOption(OPTION_TARGET_NAME, targetName);
            return this;
        }

        // Implementations of ImageOutputConfig.Builder default methods

        /**
         * Sets the aspect ratio of the intended target for images from this configuration.
         *
         * <p>This is the ratio of the target's width to the image's height, where the numerator of
         * the provided {@link Rational} corresponds to the width, and the denominator corresponds
         * to the height.
         *
         * <p>The target aspect ratio is used as a hint when determining the resulting output aspect
         * ratio which may differ from the request, possibly due to device constraints.
         * Application code should check the resulting output's resolution.
         *
         * <p>This method can be used to request an aspect ratio that is not from the standard set
         * of aspect ratios defined in the {@link AspectRatio}.
         *
         * <p>This method will remove any value set by setTargetAspectRatio().
         *
         * <p>For ImageAnalysis, the output is the {@link ImageProxy} passed to the analyzer
         * function.
         *
         * @param aspectRatio A {@link Rational} representing the ratio of the target's width and
         *                    height.
         * @return The current Builder.
         * @hide
         */
        @NonNull
        @RestrictTo(Scope.LIBRARY_GROUP)
        @Override
        public Builder setTargetAspectRatioCustom(@NonNull Rational aspectRatio) {
            getMutableConfig().insertOption(OPTION_TARGET_ASPECT_RATIO_CUSTOM, aspectRatio);
            getMutableConfig().removeOption(OPTION_TARGET_ASPECT_RATIO);
            return this;
        }

        /**
         * Sets the aspect ratio of the intended target for images from this configuration.
         *
         * <p>The aspect ratio is the ratio of width to height in the sensor orientation.
         *
         * <p>It is not allowed to set both target aspect ratio and target resolution on the same
         * use case.  Attempting so will throw an IllegalArgumentException when building the
         * Config.
         *
         * <p>The target aspect ratio is used as a hint when determining the resulting output aspect
         * ratio which may differ from the request, possibly due to device constraints.
         * Application code should check the resulting output's resolution.
         *
         * <p>If not set, resolutions with aspect ratio 4:3 will be considered in higher
         * priority.
         *
         * @param aspectRatio The desired ImageAnalysis {@link AspectRatio}
         * @return The current Builder.
         */
        @NonNull
        @Override
        public Builder setTargetAspectRatio(@AspectRatio.Ratio int aspectRatio) {
            getMutableConfig().insertOption(OPTION_TARGET_ASPECT_RATIO, aspectRatio);
            return this;
        }

        /**
         * Sets the rotation of the intended target for images from this configuration.
         *
         * <p>This adjust the {@link ImageInfo#getRotationDegrees()} of the {@link ImageProxy}
         * passed to {@link Analyzer#analyze(ImageProxy)}. The rotation value of ImageInfo will
         * be the rotation, which if applied to the output image, will make the image match
         * target rotation specified here.
         *
         * <p>This is one of four valid values: {@link Surface#ROTATION_0}, {@link
         * Surface#ROTATION_90}, {@link Surface#ROTATION_180}, {@link Surface#ROTATION_270}.
         * Rotation values are relative to the "natural" rotation, {@link Surface#ROTATION_0}.
         *
         * <p>In general, it is best to additionally set the target rotation dynamically on the use
         * case.  See
         * {@link androidx.camera.core.ImageAnalysis#setTargetRotation(int)} for additional
         * documentation.
         *
         * <p>If not set, the target rotation will default to the value of
         * {@link android.view.Display#getRotation()} of the default display at the time the
         * use case is created.
         *
         * @param rotation The rotation of the intended target.
         * @return The current Builder.
         * @see androidx.camera.core.ImageAnalysis#setTargetRotation(int)
         * @see android.view.OrientationEventListener
         */
        @NonNull
        @Override
        public Builder setTargetRotation(@RotationValue int rotation) {
            getMutableConfig().insertOption(OPTION_TARGET_ROTATION, rotation);
            return this;
        }

        /**
         * Sets the resolution of the intended target from this configuration.
         *
         * <p>The target resolution attempts to establish a minimum bound for the image resolution.
         * The actual image resolution will be the closest available resolution in size that is not
         * smaller than the target resolution, as determined by the Camera implementation. However,
         * if no resolution exists that is equal to or larger than the target resolution, the
         * nearest available resolution smaller than the target resolution will be chosen.
         * Resolutions with the same aspect ratio of the provided {@link Size} will be considered in
         * higher priority before resolutions of different aspect ratios.
         *
         * <p>It is not allowed to set both target aspect ratio and target resolution on the same
         * use case.  Attempting so will throw an IllegalArgumentException when building the
         * Config.
         *
         * <p>The resolution {@link Size} should be expressed at the use cases's target rotation.
         * For example, a device with portrait natural orientation in natural target rotation
         * requesting a portrait image may specify 480x640, and the same device, rotated 90 degrees
         * and targeting landscape orientation may specify 640x480.
         *
         * <p>The maximum available resolution that could be selected for an {@link ImageAnalysis}
         * is limited to be under 1080p. The limitation of 1080p for {@link ImageAnalysis} has
         * considered both performance and quality factors so that users can obtain reasonable
         * quality and smooth output stream under 1080p.
         *
         * <p>If not set, resolution of 640x480 will be selected to use in priority.
         *
         * @param resolution The target resolution to choose from supported output sizes list.
         * @return The current Builder.
         */
        @NonNull
        @Override
        public Builder setTargetResolution(@NonNull Size resolution) {
            getMutableConfig()
                    .insertOption(ImageOutputConfig.OPTION_TARGET_RESOLUTION, resolution);
            getMutableConfig().insertOption(OPTION_TARGET_ASPECT_RATIO_CUSTOM,
                    new Rational(resolution.getWidth(), resolution.getHeight()));
            return this;
        }

        /**
         * Sets the default resolution of the intended target from this configuration.
         *
         * @param resolution The default resolution to choose from supported output sizes list.
         * @return The current Builder.
         * @hide
         */
        @NonNull
        @RestrictTo(Scope.LIBRARY_GROUP)
        @Override
        public Builder setDefaultResolution(@NonNull Size resolution) {
            getMutableConfig().insertOption(ImageOutputConfig.OPTION_DEFAULT_RESOLUTION,
                    resolution);
            return this;
        }

        /** @hide */
        @NonNull
        @RestrictTo(Scope.LIBRARY_GROUP)
        @Override
        public Builder setMaxResolution(@NonNull Size resolution) {
            getMutableConfig().insertOption(OPTION_MAX_RESOLUTION, resolution);
            return this;
        }

        /** @hide */
        @RestrictTo(Scope.LIBRARY_GROUP)
        @Override
        @NonNull
        public Builder setSupportedResolutions(@NonNull List<Pair<Integer, Size[]>> resolutions) {
            getMutableConfig().insertOption(OPTION_SUPPORTED_RESOLUTIONS, resolutions);
            return this;
        }

        // Implementations of ThreadConfig.Builder default methods

        /**
         * Sets the default executor that will be used for background tasks.
         *
         * <p>If not set, the background executor will default to an automatically generated
         * {@link Executor}.
         *
         * @param executor The executor which will be used for background tasks.
         * @return the current Builder.
         */
        @Override
        @NonNull
        public Builder setBackgroundExecutor(@NonNull Executor executor) {
            getMutableConfig().insertOption(OPTION_BACKGROUND_EXECUTOR, executor);
            return this;
        }

        // Implementations of UseCaseConfig.Builder default methods

        /** @hide */
        @RestrictTo(Scope.LIBRARY_GROUP)
        @Override
        @NonNull
        public Builder setDefaultSessionConfig(@NonNull SessionConfig sessionConfig) {
            getMutableConfig().insertOption(OPTION_DEFAULT_SESSION_CONFIG, sessionConfig);
            return this;
        }

        /** @hide */
        @RestrictTo(Scope.LIBRARY_GROUP)
        @Override
        @NonNull
        public Builder setDefaultCaptureConfig(@NonNull CaptureConfig captureConfig) {
            getMutableConfig().insertOption(OPTION_DEFAULT_CAPTURE_CONFIG, captureConfig);
            return this;
        }

        /** @hide */
        @RestrictTo(Scope.LIBRARY_GROUP)
        @Override
        @NonNull
        public Builder setSessionOptionUnpacker(
                @NonNull SessionConfig.OptionUnpacker optionUnpacker) {
            getMutableConfig().insertOption(OPTION_SESSION_CONFIG_UNPACKER, optionUnpacker);
            return this;
        }

        /** @hide */
        @RestrictTo(Scope.LIBRARY_GROUP)
        @Override
        @NonNull
        public Builder setCaptureOptionUnpacker(
                @NonNull CaptureConfig.OptionUnpacker optionUnpacker) {
            getMutableConfig().insertOption(OPTION_CAPTURE_CONFIG_UNPACKER, optionUnpacker);
            return this;
        }

        /** @hide */
        @RestrictTo(Scope.LIBRARY_GROUP)
        @Override
        @NonNull
        public Builder setSurfaceOccupancyPriority(int priority) {
            getMutableConfig().insertOption(OPTION_SURFACE_OCCUPANCY_PRIORITY, priority);
            return this;
        }

        /** @hide */
        @RestrictTo(Scope.LIBRARY)
        @Override
        @NonNull
        public Builder setCameraSelector(@NonNull CameraSelector cameraSelector) {
            getMutableConfig().insertOption(OPTION_CAMERA_SELECTOR, cameraSelector);
            return this;
        }

        /** @hide */
        @RestrictTo(Scope.LIBRARY_GROUP)
        @Override
        @NonNull
        public Builder setUseCaseEventCallback(
                @NonNull UseCase.EventCallback useCaseEventCallback) {
            getMutableConfig().insertOption(OPTION_USE_CASE_EVENT_CALLBACK, useCaseEventCallback);
            return this;
        }

        /** @hide */
        @NonNull
        @RestrictTo(Scope.LIBRARY_GROUP)
        public Builder setImageReaderProxyProvider(
                @NonNull ImageReaderProxyProvider imageReaderProxyProvider) {
            getMutableConfig().insertOption(OPTION_IMAGE_READER_PROXY_PROVIDER,
                    imageReaderProxyProvider);
            return this;
        }
    }
}
