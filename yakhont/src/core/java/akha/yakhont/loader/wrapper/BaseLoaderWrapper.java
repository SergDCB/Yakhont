/*
 * Copyright (C) 2015-2017 akha, a.k.a. Alexander Kharitonov
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

package akha.yakhont.loader.wrapper;

import akha.yakhont.Core.ConfigurableLoader;
import akha.yakhont.Core.Utils;
import akha.yakhont.CoreLogger;
import akha.yakhont.fragment.WorkerFragment;
import akha.yakhont.loader.BaseLoader;
import akha.yakhont.loader.BaseLoader.ProgressWrapper;
import akha.yakhont.loader.CacheLoader;
import akha.yakhont.loader.wrapper.BaseResponseLoaderWrapper.CoreLoad;

import android.annotation.TargetApi;
import android.app.Fragment;
import android.app.LoaderManager;
import android.content.Loader;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.CallSuper;
import android.support.annotation.IdRes;
import android.support.annotation.NonNull;
import android.support.annotation.Size;
import android.support.annotation.WorkerThread;
import android.support.v4.widget.SwipeRefreshLayout;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The <code>BaseLoaderWrapper</code> class is a wrapper for {@link LoaderManager} and {@link Loader} instances associated with it.
 * It's designed to be a root class for standalone, independent, self-sufficient data loading components
 * (hiding low-level implementation details and <code>LoaderManager</code>'s related boilerplate code).
 * Most implementations should not use <code>BaseLoaderWrapper</code> directly, but instead utilise
 * {@link akha.yakhont.technology.retrofit.RetrofitLoaderWrapper}
 * or {@link akha.yakhont.technology.retrofit.RetrofitLoaderWrapper.RetrofitLoaderBuilder}.
 *
 * @param <D>
 *        The type of data
 *
 * @see BaseResponseLoaderWrapper
 *
 * @author akha
 */
@TargetApi(Build.VERSION_CODES.HONEYCOMB)                       //YakhontPreprocessor:removeInFlavor
public abstract class BaseLoaderWrapper<D> implements LoaderManager.LoaderCallbacks<D> {

    private static final String                             ARG_FORCE_CACHE             = "force_cache";
    private static final String                             ARG_NO_PROGRESS             = "no_progress";
    private static final String                             ARG_MERGE                   = "merge";
    private static final String                             ARG_CONFIGURABLE            = "configurable";

    private static final String                             FORMAT_INFO                 = "id = %d";

    private final WeakReference<Fragment>                   mFragment;

    private final int                                       mLoaderId;
    private final boolean                                   mLoaderIdAutoGenerated;

    private SwipeRefreshWrapper                             mSwipeRefreshWrapper;
    private ProgressWrapper                                 mProgress;

    private CountDownLatch                                  mCountDownLatch;
    private D                                               mData;
    private final AtomicBoolean                             mLoading                    = new AtomicBoolean();

    private LoaderManager.LoaderCallbacks<D>                mLoaderCallbacks;
    private LoaderFactory<D>                                mLoaderFactory;

    /**
     * The API to create new {@code BaseLoaderWrapper} instances.
     *
     * @param <D>
     *        The type of data in the loader created
     */
    public interface LoaderBuilder<D> {

        /**
         * Returns a new {@code BaseLoaderWrapper} instance.
         *
         * @return  The {@code BaseLoaderWrapper} instance
         */
        BaseLoaderWrapper<D> create();
    }

    /**
     * The API to create new {@code Loader} instances.
     *
     * @param <D>
     *        The type of data in the loader created
     */
    public interface LoaderFactory<D> {

        /**
         * Returns a new {@code Loader} instance.
         *
         * @param merge
         *        {@code true} to merge the newly loaded data with already existing, {@code false} otherwise
         *
         * @return  The {@code Loader} instance
         */
        @NonNull Loader<D> getLoader(boolean merge);
    }

    /**
     * Initialises a newly created {@code BaseLoaderWrapper} object.
     *
     * @param fragment
     *        The fragment
     *
     * @param loaderId
     *        The loader ID
     */
    @SuppressWarnings("WeakerAccess")
    public BaseLoaderWrapper(@NonNull final Fragment fragment, final Integer loaderId) {
        mFragment                   = new WeakReference<>(getFragment(fragment));
        mLoaderId                   = loaderId == null ? generateLoaderId(): loaderId;
        mLoaderIdAutoGenerated      = loaderId == null;

        if (mLoaderIdAutoGenerated)
            CoreLogger.log("auto generated id " + mLoaderId);
    }

    /**
     * Sets "swipe refresh" component.
     *
     * @param swipeRefreshWrapper
     *        The component
     *
     * @return  This {@code BaseLoaderWrapper} object
     */
    @NonNull
    @SuppressWarnings("UnusedReturnValue")
    public BaseLoaderWrapper<D> setSwipeRefreshWrapper(final SwipeRefreshWrapper swipeRefreshWrapper) {
        mSwipeRefreshWrapper        = swipeRefreshWrapper;
        return this;
    }

    /**
     * Sets component to display progress.
     *
     * @param progressWrapper
     *        The progress component
     *
     * @return  This {@code BaseLoaderWrapper} object
     */
    @NonNull
    @SuppressWarnings({"UnusedReturnValue", "SameParameterValue"})
    public BaseLoaderWrapper<D> setProgress(final ProgressWrapper progressWrapper) {
        mProgress                   = progressWrapper;
        return this;
    }

    /**
     * Sets loader factory.
     *
     * @param loaderFactory
     *        The factory
     *
     * @return  This {@code BaseLoaderWrapper} object
     */
    @NonNull
    @SuppressWarnings({"UnusedReturnValue", "WeakerAccess"})
    public BaseLoaderWrapper<D> setLoaderFactory(@NonNull final LoaderFactory<D> loaderFactory) {
        mLoaderFactory              = loaderFactory;
        return this;
    }

    /**
     * Returns the loader factory.
     *
     * @return  The loader factory
     */
    @NonNull
    @SuppressWarnings("WeakerAccess")
    public LoaderFactory<D> geLoaderFactory() {
        return mLoaderFactory;
    }

    /**
     * Sets loader callbacks.
     *
     * @param loaderCallbacks
     *        The callbacks
     *
     * @return  This {@code BaseLoaderWrapper} object
     */
    @NonNull
    @SuppressWarnings({"UnusedReturnValue", "WeakerAccess"})
    public BaseLoaderWrapper<D> setLoaderCallbacks(final LoaderManager.LoaderCallbacks<D> loaderCallbacks) {
        mLoaderCallbacks            = loaderCallbacks;
        return this;
    }

    /**
     * Returns the loader callbacks.
     *
     * @return  The loader callbacks
     */
    @SuppressWarnings("unused")
    public LoaderManager.LoaderCallbacks<D> geLoaderCallbacks() {
        return mLoaderCallbacks;
    }

    private static final Random                             sRandom                     = new Random();

    /**
     * Generates new loader ID.
     *
     * @return  The loader ID
     */
    @SuppressWarnings("WeakerAccess")
    protected int generateLoaderId() {
        return sRandom.nextInt(Integer.MAX_VALUE);
    }

    /**
     * Returns the loader ID.
     *
     * @return  The loader ID
     */
    public int getLoaderId() {
        return mLoaderId;
    }

    /**
     * Indicates whether the loader ID was auto generated or not.
     *
     * @return  {@code true} if the loader ID was auto generated, {@code false} otherwise
     */
    @SuppressWarnings("WeakerAccess")
    public boolean isLoaderIdAutoGenerated() {
        return mLoaderIdAutoGenerated;
    }

    private static Fragment getFragment(@NonNull final Fragment fragment) {
        final WorkerFragment workerFragment = WorkerFragment.findInstance(fragment);
        if (workerFragment != null) return workerFragment;

        CoreLogger.logError("WorkerFragment not found; argument fragment will be used, so some functionality will be lost");
        return fragment;
    }

    /**
     * Returns fragment this {@code BaseLoaderWrapper} object is associated with.
     *
     * @return  The fragment
     */
    @SuppressWarnings("WeakerAccess")
    public Fragment getFragment() {
        final Fragment fragment = mFragment.get();
        if (fragment == null)
            CoreLogger.logError("fragment == null");
        return fragment;
    }

    /**
     * Tries to find this {@code BaseLoaderWrapper} object in the given collection.
     *
     * @param loaders
     *        The loaders collection
     *
     * @return  The {@code BaseLoaderWrapper} object or null (if not found)
     */
    public BaseLoaderWrapper findLoader(final Collection<BaseLoaderWrapper> loaders) {
        final BaseLoaderWrapper loader = findLoaderHelper(loaders);
        CoreLogger.log((loader == null ? "not " : "") + "found loader, id: " + mLoaderId + ", auto generated: " + mLoaderIdAutoGenerated);
        return loader;
    }

    private BaseLoaderWrapper findLoaderHelper(final Collection<BaseLoaderWrapper> loaders) {
        if (loaders == null) return null;

        for (final BaseLoaderWrapper loader: loaders)
            if (mLoaderIdAutoGenerated) {
                if (loader.isLoaderIdAutoGenerated()) return loader;
            }
            else
                if (!loader.isLoaderIdAutoGenerated() && mLoaderId == loader.getLoaderId()) return loader;

        return null;
    }

    /**
     * Please refer to the base method description.
     */
    @Override
    public String toString() {
        return String.format(CoreLogger.getLocale(), FORMAT_INFO, getLoaderId());
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Starts loading data.
     *
     * @return  This {@code BaseLoaderWrapper} object
     */
    @NonNull
    @SuppressWarnings("unused")
    public BaseLoaderWrapper<D> start() {
        return start(false, false, false);
    }

    /**
     * Starts loading data.
     *
     * @param forceCache
     *        {@code true} to force loading data from cache, {@code false} otherwise
     *
     * @param noProgress
     *        {@code true} to not display loading progress, {@code false} otherwise
     *
     * @param merge
     *        {@code true} to merge the newly loaded data with already existing, {@code false} otherwise
     *
     * @return  This {@code BaseLoaderWrapper} object
     */
    @NonNull
    public BaseLoaderWrapper<D> start(final boolean forceCache, final boolean noProgress, final boolean merge) {
        CoreLogger.log("forceCache: " + forceCache + ", noProgress: " + noProgress + ", merge: " + merge);

        if (!validateArguments(forceCache, noProgress, merge)) return this;

        final Bundle bundle = new Bundle();
        bundle.putBoolean(ARG_FORCE_CACHE,      forceCache);
        bundle.putBoolean(ARG_NO_PROGRESS,      noProgress);
        bundle.putBoolean(ARG_MERGE,            merge);
        bundle.putBoolean(ARG_CONFIGURABLE,     true);

        return startHelper(bundle);
    }

    @SuppressWarnings({"BooleanMethodIsAlwaysInverted", "UnusedParameters"})
    private static boolean validateArguments(final boolean forceCache, final boolean noProgress, final boolean merge) {
        if (forceCache && merge) {
            CoreLogger.logError("wrong combination: forceCache and merge");
            return false;
        }
        return true;
    }

    @NonNull
    @SuppressWarnings("UnusedReturnValue")
    private BaseLoaderWrapper<D> startConfigured() {
        final Bundle bundle = new Bundle();
        bundle.putBoolean(ARG_CONFIGURABLE, false);

        return startHelper(bundle);
    }

    @NonNull
    private BaseLoaderWrapper<D> startHelper(@NonNull final Bundle bundle) {
        mData           = null;
        mProgress       = null;

        final Fragment fragment = getFragment();
        if (fragment == null) return this;

        final LoaderManager loaderManager = fragment.getLoaderManager();
        if (loaderManager == null)
            CoreLogger.logError("loaderManager == null");
        else {
            loaderManager.restartLoader(mLoaderId, bundle, this);

            mLoading.set(true);
        }

        return this;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Please refer to the base method description.
     */
    @CallSuper
    @Override
    public Loader<D> onCreateLoader(int id, Bundle args) {
        CoreLogger.log("mLoaderCallbacks: " + mLoaderCallbacks);
        if (mLoaderCallbacks != null) {

            final Loader<D> loader = mLoaderCallbacks.onCreateLoader(id, args);
            CoreLogger.log("loader: " + loader);

            if (loader != null) return loader;
        }

        final Loader<D> loader = mLoaderFactory.getLoader(args.getBoolean(ARG_MERGE));
        if (!args.getBoolean(ARG_CONFIGURABLE)) return loader;

        if (loader instanceof ConfigurableLoader) {
            ((ConfigurableLoader) loader).setForceCache(args.getBoolean(ARG_FORCE_CACHE));
            ((ConfigurableLoader) loader).setNoProgress(args.getBoolean(ARG_NO_PROGRESS));
        }
        else {
            if (loader instanceof CacheLoader)
                ((CacheLoader) loader).setForceCache(args.getBoolean(ARG_FORCE_CACHE));
            if (loader instanceof BaseLoader)
                ((BaseLoader)  loader).setProgress(new ProgressWrapper(mFragment, args.getBoolean(ARG_NO_PROGRESS)));
        }

        if (!(loader instanceof BaseLoader)) {
            mProgress = new ProgressWrapper(mFragment, args.getBoolean(ARG_NO_PROGRESS));
            mProgress.doProgress(true, null);
        }

        return loader;
    }

    /**
     * Please refer to the base method description.
     */
    @CallSuper
    @Override
    public void onLoadFinished(Loader<D> loader, D data) {
        mLoading.set(false);

        if (CoreLogger.isFullInfo()) {
            CoreLogger.log("loader: " + loader);
            CoreLogger.log(data.toString());
        }
        mData = data;

        handleSync();
        setRefreshing();

        if (mProgress != null) mProgress.doProgress(false, null);
        mProgress = null;

        if (mLoaderCallbacks != null) mLoaderCallbacks.onLoadFinished(loader, data);
    }

    /**
     * Please refer to the base method description.
     */
    @CallSuper
    @Override
    public void onLoaderReset(Loader<D> loader) {
        mLoading.set(false);

        CoreLogger.logWarning("loader: " + loader);

        handleSync();
        setRefreshing();

        if (mLoaderCallbacks != null) mLoaderCallbacks.onLoaderReset(loader);
    }

    /**
     * Indicates whether the loader is busy with data loading or not.
     *
     * @return  {@code true} if the loader is busy, {@code false} otherwise
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isLoading() {
        return mLoading.get();
    }

    private void setRefreshing() {
        if (mSwipeRefreshWrapper != null) mSwipeRefreshWrapper.setRefreshing();
    }

    private void handleSync() {
        if (mCountDownLatch != null) mCountDownLatch.countDown();
        mCountDownLatch = null;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Starts loading data synchronously.
     *
     * @param forceCache
     *        {@code true} to force loading data from cache, {@code false} otherwise
     *
     * @param noProgress
     *        {@code true} to not display loading progress, {@code false} otherwise
     *
     * @param merge
     *        {@code true} to merge the newly loaded data with already existing, {@code false} otherwise
     *
     * @return  The loaded data
     */
    @WorkerThread
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    public D startSync(final boolean forceCache, final boolean noProgress, final boolean merge) {
        startSync(Arrays.asList(new BaseLoaderWrapper[] {this}), false, forceCache, noProgress, merge);
        return mData;
    }

    /**
     * Starts all loaders in the given collection.
     *
     * @param loaders
     *        The loaders collection
     */
    @WorkerThread
    @SuppressWarnings("unused")
    public static void startSync(@NonNull final Collection<BaseLoaderWrapper> loaders) {
        startSync(loaders, true, false /* ignored */, false /* ignored */, false /* ignored */);
    }

    /**
     * Starts all loaders in the given collection.
     *
     * @param loaders
     *        The loaders collection
     *
     * @param forceCache
     *        {@code true} to force loading data from cache, {@code false} otherwise
     *
     * @param noProgress
     *        {@code true} to not display loading progress, {@code false} otherwise
     *
     * @param merge
     *        {@code true} to merge the newly loaded data with already existing, {@code false} otherwise
     */
    @WorkerThread
    @SuppressWarnings("unused")
    public static void startSync(@NonNull final Collection<BaseLoaderWrapper> loaders,
                                 final boolean forceCache, final boolean noProgress, final boolean merge) {
        startSync(loaders, false, forceCache, noProgress, merge);
    }

    @WorkerThread
    private static void startSync(@NonNull final Collection<BaseLoaderWrapper> loaders, final boolean configured,
                                  final boolean forceCache, final boolean noProgress, final boolean merge) {
        CoreLogger.log("configured: " + configured + ", forceCache: " + forceCache + ", noProgress: " + noProgress + ", merge: " + merge);

        if (!validateArguments(forceCache, noProgress, merge)) return;

        if (Utils.isCurrentThreadMain()) {
            CoreLogger.logError("not allowed to run from the main thread");
            return;
        }

        //noinspection ConstantConditions
        if (loaders == null || loaders.size() == 0) {
            CoreLogger.logError("empty loaders list");
            return;
        }
        CoreLogger.log("loaders list size: " + loaders.size());

        final CountDownLatch countDownLatch = new CountDownLatch(loaders.size());

        for (final BaseLoaderWrapper loader: loaders)
            loader.setCountDownLatch(countDownLatch);

        Utils.runInBackground(new Runnable() {
            @Override
            public void run() {
                for (final BaseLoaderWrapper loader : loaders)
                    if (configured)
                        loader.startConfigured();
                    else
                        loader.start(forceCache, noProgress, merge);
            }
        });

        await(countDownLatch);

        for (final BaseLoaderWrapper loader: loaders)
            loader.setCountDownLatch(null);

        CoreLogger.log("completed");
    }

    private static void await(final CountDownLatch countDownLatch) {
        if (countDownLatch == null) return;
        try {
            countDownLatch.await();
        }
        catch (InterruptedException e) {
            CoreLogger.log("interrupted", e);
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    private BaseLoaderWrapper<D> setCountDownLatch(final CountDownLatch countDownLatch) {
        mCountDownLatch = countDownLatch;
        return this;
    }

    /**
     * Returns the loaded data.
     *
     * @return  The loaded data
     */
    @SuppressWarnings("unused")
    public D getResult() {
        return mData;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * The <code>SwipeRefreshWrapper</code> class is intended to support "swipe refresh" feature. To enable swipe refresh,
     * fragment should contain {@link SwipeRefreshLayout} and register it via one of the methods provided
     * (for example, {@link #register(Fragment, int) register()}). For example:
     *
     * <p><pre style="background-color: silver; border: thin solid black;">
     * public class MyFragment extends Fragment {
     *
     *     &#064;Override
     *     public void onActivityCreated(Bundle savedInstanceState) {
     *         super.onActivityCreated(savedInstanceState);
     *         ...
     *         SwipeRefreshWrapper.register(this, R.id.swipeContainer);
     *     }
     * }
     * </pre>
     *
     * And in the layout XML:
     *
     * <p><pre style="background-color: silver; border: thin solid black;">
     * &lt;android.support.v4.widget.SwipeRefreshLayout
     *     android:id="&#064;+id/swipeContainer"
     *     ... &gt;
     *
     *     &lt;ListView ... /&gt;
     *
     * &lt;/android.support.v4.widget.SwipeRefreshLayout&gt;
     * </pre>
     *
     * @see SwipeRefreshLayout
     */
    @SuppressWarnings("WeakerAccess")
    public static class SwipeRefreshWrapper {

        private static final Map<Fragment, Collection<FragmentData>>
                                                            sFragmentData               = Utils.newWeakMap();
        private final WeakReference<SwipeRefreshLayout>     mSwipeRefreshLayout;

        /**
         * Initialises a newly created {@code SwipeRefreshWrapper} object.
         *
         * @param layout
         *        The {@code SwipeRefreshLayout}
         */
        protected SwipeRefreshWrapper(@NonNull final SwipeRefreshLayout layout) {
            mSwipeRefreshLayout = new WeakReference<>(layout);
        }

        private void setRefreshing() {
            CoreLogger.log("mSwipeRefreshLayout.get(): " + mSwipeRefreshLayout.get());

            if (mSwipeRefreshLayout.get() != null) mSwipeRefreshLayout.get().setRefreshing(false);
        }

        /**
         * Enables "swipe refresh" feature for the given fragment.
         *
         * @param fragment
         *        The fragment
         *
         * @param resId
         *        The resource ID of the {@code SwipeRefreshLayout}
         */
        @SuppressWarnings("unused")
        public static void register(@NonNull final Fragment fragment, final @IdRes int resId) {
            register(fragment, new int[] {resId});
        }

        /**
         * Enables "swipe refresh" feature for the given fragment.
         *
         * @param fragment
         *        The fragment
         *
         * @param resIds
         *        The list of {@code SwipeRefreshLayout}'s resource IDs
         */
        public static void register(@NonNull final Fragment fragment, @NonNull @Size(min = 1) final @IdRes int[] resIds) {
            final FragmentData[] fragmentData = new FragmentData[resIds.length];
            for (int i = 0; i < resIds.length; i++)
                fragmentData[i] = new FragmentData(fragment, resIds[i], false, false, null);
            register(fragment, Arrays.asList(fragmentData));
        }

        /**
         * Enables "swipe refresh" feature for the given fragment.
         *
         * @param fragment
         *        The fragment
         *
         * @param data
         *        The data to register
         */
        @SuppressWarnings("unused")
        public static void register(@NonNull final Fragment fragment, @NonNull final FragmentData data) {
            register(fragment, Arrays.asList(new FragmentData[]{data}));
        }

        /**
         * Enables "swipe refresh" feature for the given fragment.
         *
         * @param fragment
         *        The fragment
         *
         * @param data
         *        The data to register
         */
        public static void register(@NonNull final Fragment fragment, @NonNull @Size(min = 1) final Collection<FragmentData> data) {
            CoreLogger.log("about to register: " + Arrays.deepToString(data.toArray()));
            sFragmentData.put(fragment, data);
        }

        private static void onPauseOrResume(@NonNull final Fragment fragment, final @IdRes int resId, final boolean resume) {
            final Collection<FragmentData> fragmentData = sFragmentData.get(fragment);
            if (fragmentData == null) {
                if (resId != Utils.NOT_VALID_RES_ID) CoreLogger.logError("data not found for fragment " + fragment);
                return;
            }

            CoreLogger.log("subject to call by weaver");
            for (final FragmentData data: fragmentData) {
                if (resId != Utils.NOT_VALID_RES_ID && resId != data.mResId) continue;

                if (resume)
                    onResume(fragment, data.mView, data.mResId);
                else
                    onPause(data.mView, data.mResId);
            }
        }

        /**
         * Called by the Yakhont Weaver to support "swipe refresh" feature for the given fragment.
         *
         * @param fragment
         *        The fragment
         */
        @SuppressWarnings("unused")
        public static void onResume(@NonNull final Fragment fragment) {
            onPauseOrResume(fragment, Utils.NOT_VALID_RES_ID, true);
        }

        /**
         * Should be called from {@link Fragment#onResume()} to support "swipe refresh" feature for the given fragment.
         *
         * @param fragment
         *        The fragment
         *
         * @param resId
         *        The resource ID of the {@code SwipeRefreshLayout}
         */
        @SuppressWarnings("unused")
        public static void onResume(@NonNull final Fragment fragment, final @IdRes int resId) {
            onPauseOrResume(fragment, resId, true);
        }

        /**
         * Should be called from {@link Fragment#onResume()} to support "swipe refresh" feature for the given fragment.
         *
         * @param fragment
         *        The fragment
         *
         * @param view
         *        The view which contains {@code SwipeRefreshLayout}
         *
         * @param resId
         *        The resource ID of the {@code SwipeRefreshLayout}
         */
        @SuppressWarnings("SameParameterValue")
        public static void onResume(@NonNull final Fragment fragment, final View view, final @IdRes int resId) {
            final SwipeRefreshLayout swipeRefreshLayout = getSwipeContainer(view, resId);
            if (swipeRefreshLayout == null) return;

            swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
                @Override
                public void onRefresh() {
                    final Collection<FragmentData> fragmentData = sFragmentData.get(fragment);
                    if (fragmentData == null) {
                        CoreLogger.logError("data not found for fragment " + fragment);
                        return;
                    }

                    final FragmentData data = getData(fragmentData, resId);
                    if (data == null) {
                        CoreLogger.logError("data not found for resId " + resId);
                        return;
                    }

                    if (!data.mSwipeProgress) swipeRefreshLayout.setRefreshing(false);

                    final CoreLoad coreLoad = BaseLoader.getCoreLoad(fragment);
                    if (coreLoad == null) return;

                    coreLoad.setGoBackOnLoadingCanceled(false);

                    if (data.mRunnable != null) data.mRunnable.run();

                    boolean started = false;
                    for (final BaseLoaderWrapper loader: coreLoad.getLoaders()) {
                        if (data.mId != null && data.mId != loader.getLoaderId()) continue;

                        loader.setSwipeRefreshWrapper(new SwipeRefreshWrapper(swipeRefreshLayout));
                        loader.start(data.mForceCache, data.mSwipeProgress, data.mMerge);

                        started = true;
                    }

                    if (started) return;

                    if (data.mId != null)
                        CoreLogger.logError("not found loader with id " + data.mId);
                    else
                        CoreLogger.logWarning("no loaders to start");
                }
            });
        }

        private static FragmentData getData(@NonNull final Collection<FragmentData> fragmentData, final @IdRes int resId) {
            for (final FragmentData data: fragmentData)
                if (resId == data.mResId) return data;
            return null;
        }

        /**
         * Called by the Yakhont Weaver to support "swipe refresh" feature for the given fragment.
         *
         * @param fragment
         *        The fragment
         */
        @SuppressWarnings("unused")
        public static void onPause(@NonNull final Fragment fragment) {
            onPauseOrResume(fragment, Utils.NOT_VALID_RES_ID, false);
        }

        /**
         * Should be called from {@link Fragment#onPause()} to support "swipe refresh" feature for the given fragment.
         *
         * @param fragment
         *        The fragment
         *
         * @param resId
         *        The resource ID of the {@code SwipeRefreshLayout}
         */
        @SuppressWarnings("unused")
        public static void onPause(@NonNull final Fragment fragment, final @IdRes int resId) {
            onPauseOrResume(fragment, resId, false);
        }

        /**
         * Should be called from {@link Fragment#onPause()} to support "swipe refresh" feature for the given fragment.
         *
         * @param view
         *        The view which contains {@code SwipeRefreshLayout}
         *
         * @param resId
         *        The resource ID of the {@code SwipeRefreshLayout}
         */
        public static void onPause(final View view, final @IdRes int resId) {
            final SwipeRefreshLayout swipeRefreshLayout = getSwipeContainer(view, resId);
            if (swipeRefreshLayout == null) return;

            swipeRefreshLayout.setOnRefreshListener(null);
        }

        private static SwipeRefreshLayout getSwipeContainer(final View view, final @IdRes int resId) {
            if (view == null) {
                CoreLogger.logError("view == null");
                return null;
            }

            final View swipeRefreshLayout = view.findViewById(resId);
            if (swipeRefreshLayout == null) {
                CoreLogger.logError("SwipeRefreshLayout == null, id " + resId);
                return null;
            }

            if (!(swipeRefreshLayout instanceof SwipeRefreshLayout)) {
                CoreLogger.logError("view with id " + resId + " is not SwipeRefreshLayout but " +
                        swipeRefreshLayout.getClass().getName());
                return null;
            }

            return (SwipeRefreshLayout) swipeRefreshLayout;
        }

        /**
         * The <code>FragmentData</code> class is a container to store data required to support "swipe refresh" feature.
         */
        public static class FragmentData {

            /** @exclude */ @SuppressWarnings("JavaDoc")
            final View                                      mView;
            /** @exclude */ @SuppressWarnings("JavaDoc")
            final @IdRes int                                mResId;
            /** @exclude */ @SuppressWarnings("JavaDoc")
            final boolean                                   mForceCache;
            /** @exclude */ @SuppressWarnings("JavaDoc")
            final boolean                                   mSwipeProgress;
            /** @exclude */ @SuppressWarnings("JavaDoc")
            final boolean                                   mMerge;
            /** @exclude */ @SuppressWarnings("JavaDoc")
            final Integer                                   mId;
            /** @exclude */ @SuppressWarnings("JavaDoc")
            final Runnable                                  mRunnable;

            /**
             * Initialises a newly created {@code FragmentData} object.
             *
             * @param fragment
             *        The fragment
             *
             * @param resId
             *        The resource ID of the {@code SwipeRefreshLayout}
             *
             * @param forceCache
             *        {@code true} to force loading data from cache, {@code false} otherwise
             *
             * @param merge
             *        {@code true} to merge the newly loaded data with already existing, {@code false} otherwise
             *
             * @param runnable
             *        The {@code Runnable} to run before loading will be started
             */
            @SuppressWarnings("SameParameterValue")
            public FragmentData(@NonNull final Fragment fragment, final @IdRes int resId, final boolean forceCache,
                                final boolean merge, final Runnable runnable) {
                this(fragment.getView(), resId, forceCache, false, merge, null, runnable);
            }

            /**
             * Initialises a newly created {@code FragmentData} object.
             *
             * @param view
             *        The view which contains {@code SwipeRefreshLayout}
             *
             * @param resId
             *        The resource ID of the {@code SwipeRefreshLayout}
             *
             * @param forceCache
             *        {@code true} to force loading data from cache, {@code false} otherwise
             *
             * @param swipeProgress
             *        Whether or not the view should show refresh progress; see {@link SwipeRefreshLayout#setRefreshing(boolean)}
             *
             * @param merge
             *        {@code true} to merge the newly loaded data with already existing, {@code false} otherwise
             *
             * @param id
             *        The loader ID (or null for default one)
             *
             * @param runnable
             *        The {@code Runnable} to run before loading will be started
             */
            @SuppressWarnings("SameParameterValue")
            public FragmentData(final View view, final @IdRes int resId, final boolean forceCache, final boolean swipeProgress,
                                final boolean merge, final Integer id, final Runnable runnable) {
                mView                       = view;
                mResId                      = resId;
                mForceCache                 = forceCache;
                mSwipeProgress              = swipeProgress;
                mMerge                      = merge;
                mId                         = id;
                mRunnable                   = runnable;
            }

            /**
             * Please refer to the base method description.
             */
            @Override
            public String toString() {
                return String.format(CoreLogger.getLocale(), "forceCache %b, swipeProgress %b, merge %b, resId %d, id %d",
                        mForceCache, mSwipeProgress, mMerge, mResId, mId);
            }
        }
    }
}
