/*
 * Copyright 2019 The Android Open Source Project
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

package androidx.fragment.app;

import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;

class FragmentStateManager {

    private static final String TARGET_REQUEST_CODE_STATE_TAG = "android:target_req_state";
    private static final String TARGET_STATE_TAG = "android:target_state";
    private static final String VIEW_STATE_TAG = "android:view_state";
    private static final String USER_VISIBLE_HINT_TAG = "android:user_visible_hint";

    private final FragmentLifecycleCallbacksDispatcher mDispatcher;
    @NonNull
    private final Fragment mFragment;

    /**
     * Create a FragmentStateManager from a brand new Fragment instance.
     *
     * @param dispatcher Dispatcher for any lifecycle callbacks triggered by this class
     * @param fragment The Fragment to manage
     */
    FragmentStateManager(@NonNull FragmentLifecycleCallbacksDispatcher dispatcher,
            @NonNull Fragment fragment) {
        mDispatcher = dispatcher;
        mFragment = fragment;
    }

    /**
     * Recreate a FragmentStateManager from a FragmentState instance, instantiating
     * a new Fragment from the {@link FragmentFactory}.
     *
     * @param dispatcher Dispatcher for any lifecycle callbacks triggered by this class
     * @param classLoader ClassLoader used to instantiate the Fragment
     * @param fragmentFactory FragmentFactory used to instantiate the Fragment
     * @param fs FragmentState used to restore the state correctly
     */
    FragmentStateManager(@NonNull FragmentLifecycleCallbacksDispatcher dispatcher,
            @NonNull ClassLoader classLoader, @NonNull FragmentFactory fragmentFactory,
            @NonNull FragmentState fs) {
        mDispatcher = dispatcher;
        mFragment = fragmentFactory.instantiate(classLoader, fs.mClassName);
        if (fs.mArguments != null) {
            fs.mArguments.setClassLoader(classLoader);
        }
        mFragment.setArguments(fs.mArguments);
        mFragment.mWho = fs.mWho;
        mFragment.mFromLayout = fs.mFromLayout;
        mFragment.mRestored = true;
        mFragment.mFragmentId = fs.mFragmentId;
        mFragment.mContainerId = fs.mContainerId;
        mFragment.mTag = fs.mTag;
        mFragment.mRetainInstance = fs.mRetainInstance;
        mFragment.mRemoving = fs.mRemoving;
        mFragment.mDetached = fs.mDetached;
        mFragment.mHidden = fs.mHidden;
        mFragment.mMaxState = Lifecycle.State.values()[fs.mMaxLifecycleState];
        if (fs.mSavedFragmentState != null) {
            mFragment.mSavedFragmentState = fs.mSavedFragmentState;
        } else {
            // When restoring a Fragment, always ensure we have a
            // non-null Bundle so that developers have a signal for
            // when the Fragment is being restored
            mFragment.mSavedFragmentState = new Bundle();
        }
        if (FragmentManager.DEBUG) {
            Log.v(FragmentManager.TAG, "Instantiated fragment " + mFragment);
        }
    }

    /**
     * Recreate the FragmentStateManager from a retained Fragment and a
     * FragmentState instance.
     *
     * @param dispatcher Dispatcher for any lifecycle callbacks triggered by this class
     * @param retainedFragment A retained fragment
     * @param fs FragmentState used to restore the state correctly
     */
    FragmentStateManager(@NonNull FragmentLifecycleCallbacksDispatcher dispatcher,
            @NonNull Fragment retainedFragment,
            @NonNull FragmentState fs) {
        mDispatcher = dispatcher;
        mFragment = retainedFragment;
        mFragment.mSavedViewState = null;
        mFragment.mBackStackNesting = 0;
        mFragment.mInLayout = false;
        mFragment.mAdded = false;
        mFragment.mTargetWho = mFragment.mTarget != null ? mFragment.mTarget.mWho : null;
        mFragment.mTarget = null;
        if (fs.mSavedFragmentState != null) {
            mFragment.mSavedFragmentState = fs.mSavedFragmentState;
        } else {
            // When restoring a Fragment, always ensure we have a
            // non-null Bundle so that developers have a signal for
            // when the Fragment is being restored
            mFragment.mSavedFragmentState = new Bundle();
        }
    }

    @NonNull
    Fragment getFragment() {
        return mFragment;
    }

    void ensureInflatedView() {
        if (mFragment.mFromLayout && !mFragment.mPerformedCreateView) {
            mFragment.performCreateView(mFragment.performGetLayoutInflater(
                    mFragment.mSavedFragmentState), null, mFragment.mSavedFragmentState);
            if (mFragment.mView != null) {
                mFragment.mView.setSaveFromParentEnabled(false);
                if (mFragment.mHidden) mFragment.mView.setVisibility(View.GONE);
                mFragment.onViewCreated(mFragment.mView, mFragment.mSavedFragmentState);
                mDispatcher.dispatchOnFragmentViewCreated(
                        mFragment, mFragment.mView, mFragment.mSavedFragmentState, false);
            }
        }
    }

    void restoreState(@NonNull ClassLoader classLoader) {
        if (mFragment.mSavedFragmentState == null) {
            return;
        }
        mFragment.mSavedFragmentState.setClassLoader(classLoader);
        mFragment.mSavedViewState = mFragment.mSavedFragmentState.getSparseParcelableArray(
                VIEW_STATE_TAG);
        mFragment.mTargetWho = mFragment.mSavedFragmentState.getString(
                TARGET_STATE_TAG);
        if (mFragment.mTargetWho != null) {
            mFragment.mTargetRequestCode = mFragment.mSavedFragmentState.getInt(
                    TARGET_REQUEST_CODE_STATE_TAG, 0);
        }
        if (mFragment.mSavedUserVisibleHint != null) {
            mFragment.mUserVisibleHint = mFragment.mSavedUserVisibleHint;
            mFragment.mSavedUserVisibleHint = null;
        } else {
            mFragment.mUserVisibleHint = mFragment.mSavedFragmentState.getBoolean(
                    USER_VISIBLE_HINT_TAG, true);
        }
    }

    @NonNull
    FragmentState saveState() {
        FragmentState fs = new FragmentState(mFragment);

        if (mFragment.mState > Fragment.INITIALIZING && fs.mSavedFragmentState == null) {
            fs.mSavedFragmentState = saveBasicState();

            if (mFragment.mTargetWho != null) {
                if (fs.mSavedFragmentState == null) {
                    fs.mSavedFragmentState = new Bundle();
                }
                fs.mSavedFragmentState.putString(
                        TARGET_STATE_TAG,
                        mFragment.mTargetWho);
                if (mFragment.mTargetRequestCode != 0) {
                    fs.mSavedFragmentState.putInt(
                            TARGET_REQUEST_CODE_STATE_TAG,
                            mFragment.mTargetRequestCode);
                }
            }

        } else {
            fs.mSavedFragmentState = mFragment.mSavedFragmentState;
        }
        return fs;
    }

    @Nullable
    Fragment.SavedState saveInstanceState() {
        if (mFragment.mState > Fragment.INITIALIZING) {
            Bundle result = saveBasicState();
            return result != null ? new Fragment.SavedState(result) : null;
        }
        return null;
    }

    private Bundle saveBasicState() {
        Bundle result = new Bundle();

        mFragment.performSaveInstanceState(result);
        mDispatcher.dispatchOnFragmentSaveInstanceState(mFragment, result, false);
        if (result.isEmpty()) {
            result = null;
        }

        if (mFragment.mView != null) {
            saveViewState();
        }
        if (mFragment.mSavedViewState != null) {
            if (result == null) {
                result = new Bundle();
            }
            result.putSparseParcelableArray(
                    VIEW_STATE_TAG, mFragment.mSavedViewState);
        }
        if (!mFragment.mUserVisibleHint) {
            if (result == null) {
                result = new Bundle();
            }
            // Only add this if it's not the default value
            result.putBoolean(USER_VISIBLE_HINT_TAG, mFragment.mUserVisibleHint);
        }

        return result;
    }

    void saveViewState() {
        if (mFragment.mView == null) {
            return;
        }
        SparseArray<Parcelable> mStateArray = new SparseArray<>();
        mFragment.mView.saveHierarchyState(mStateArray);
        if (mStateArray.size() > 0) {
            mFragment.mSavedViewState = mStateArray;
        }
    }
}