/*
 * Copyright 2013 Roman Nurik, Tim Roes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.timroes.swipetodismiss;

import android.graphics.Rect;
import android.os.Build;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ListView;

import com.nineoldandroids.animation.Animator;
import com.nineoldandroids.animation.AnimatorListenerAdapter;
import com.nineoldandroids.animation.ValueAnimator;

import java.util.SortedSet;
import java.util.TreeSet;

import static com.nineoldandroids.view.ViewHelper.setAlpha;
import static com.nineoldandroids.view.ViewHelper.setTranslationX;
import static com.nineoldandroids.view.ViewPropertyAnimator.animate;

/**
 * A {@link android.view.View.OnTouchListener} that makes the list items in a
 * {@link ListView} dismissable. {@link ListView} is given special treatment
 * because by default it handles touches for its list items... i.e. it's in
 * charge of drawing the pressed state (the list selector), handling list item
 * clicks, etc.
 * <p/>
 * Read the README file for a detailed explanation on how to use this class.
 */
public class SwipeDismissList extends DismissList implements View.OnTouchListener {

    // Cached ViewConfiguration and system-wide constant values
    protected final int mSlop;
    protected final int mMinFlingVelocity;
    protected final int mMaxFlingVelocity;
    protected final long mAnimationTime;

    // Fixed properties
    protected int mViewWidth = 1; // 1 and not 0 to prevent dividing by zero

    // Transient properties
    protected final SortedSet<PendingDismissData> mPendingDismisses = new TreeSet<PendingDismissData>();
    protected int mDismissAnimationRefCount = 0;
    protected float mDownX;
    protected boolean mSwiping;
    protected VelocityTracker mVelocityTracker;
    protected int mDownPosition;
    protected View mDownView;
    protected boolean mPaused;
    protected boolean mSwipeDisabled;

    private SwipeDirection mSwipeDirection = SwipeDirection.BOTH;

    /**
     * Defines the direction in which the swipe to delete can be done. The default
     * is {@link SwipeDirection#BOTH}. Use {@link #setSwipeDirection(de.timroes.swipetodismiss.SwipeDismissList.SwipeDirection)}
     * to set the direction.
     */
    public enum SwipeDirection {
        /**
         * The user can swipe each item into both directions (left and right)
         * to delete it.
         */
        BOTH,
        /**
         * The user can only swipe the items to the beginning of the item to
         * delete it. The start of an item is in Left-To-Right languages the left
         * side and in Right-To-Left languages the right side. Before API level
         * 17 this is always the left side.
         */
        START,
        /**
         * The user can only swipe the items to the end of the item to delete it.
         * This is in Left-To-Right languages the right side in Right-To-Left
         * languages the left side. Before API level 17 this will always be the
         * right side.
         */
        END
    }

    /**
     * Constructs a new swipe-to-dismiss touch listener for the given list view.
     *
     * @param listView The list view whose items should be dismissable.
     * @param callback The callback to trigger when the user has indicated that
     *                 she would like to dismiss one or more list items.
     */
    public SwipeDismissList(AbsListView listView, OnDismissCallback callback) {
        this(listView, callback, UndoMode.SINGLE_UNDO);
    }

    /**
     * Constructs a new swipe-to-dismiss touch listener for the given list view.
     *
     * @param listView The list view whose items should be dismissable.
     * @param callback The callback to trigger when the user has indicated that
     *                 she would like to dismiss one or more list items.
     * @param mode     The mode this list handles multiple undos.
     */
    public SwipeDismissList(AbsListView listView, OnDismissCallback callback, UndoMode mode) {
        this(listView, callback, mode, true);
    }

    /**
     * Constructs a new swipe-to-dismiss touch listener for the given list view.
     *
     * @param listView The list view whose items should be dismissable.
     * @param callback The callback to trigger when the user has indicated that
     *                 she would like to dismiss one or more list items.
     * @param mode     The mode this list handles multiple undos.
     */
    public SwipeDismissList(AbsListView listView, OnDismissCallback callback, UndoMode mode, boolean bindListeners) {
        super(listView, callback, mode);

        ViewConfiguration vc = ViewConfiguration.get(listView.getContext());
        mSlop = vc.getScaledTouchSlop();
        mMinFlingVelocity = vc.getScaledMinimumFlingVelocity();
        mMaxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        mAnimationTime = listView.getContext().getResources().getInteger(
                android.R.integer.config_shortAnimTime);

        mListView.setOnTouchListener(this);
        mListView.setOnScrollListener(this.makeScrollListener());
    }

    /**
     * Sets the time in milliseconds after which the undo popup automatically
     * disappears.
     *
     * @param delay Delay in milliseconds.
     */
    public void setAutoHideDelay(int delay) {
        mAutoHideDelay = delay;
    }

    /**
     * Enables or disables (pauses or resumes) watching for swipe-to-dismiss
     * gestures.
     *
     * @param enabled Whether or not to watch for gestures.
     */
    private void setEnabled(boolean enabled) {
        mPaused = !enabled;
    }

    /**
     * Sets the directions in which a list item can be swiped to delete.
     * By default this is set to {@link SwipeDirection#BOTH} so that an item
     * can be swiped into both directions.
     *
     * @param direction The direction to limit the swipe to.
     */
    public void setSwipeDirection(SwipeDirection direction) {
        mSwipeDirection = direction;
    }

    /**
     * Returns an {@link android.widget.AbsListView.OnScrollListener} to be
     * added to the {@link ListView} using
     * {@link ListView#setOnScrollListener(android.widget.AbsListView.OnScrollListener)}.
     * If a scroll listener is already assigned, the caller should still pass
     * scroll changes through to this listener. This will ensure that this
     * {@link SwipeDismissList} is paused during list view
     * scrolling.</p>
     */
    private AbsListView.OnScrollListener makeScrollListener() {
        return new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView absListView, int scrollState) {
                setEnabled(scrollState != AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
            }

            @Override
            public void onScroll(AbsListView absListView, int i, int i1, int i2) {
            }
        };
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        if (mSwipeDisabled) {
            return false;
        }

        if (mViewWidth < 2) {
            mViewWidth = mListView.getWidth();
        }

        switch (motionEvent.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (mPaused) {
                    return false;
                }

                // TODO: ensure this is a finger, and set a flag

                // Find the child view that was touched (perform a hit test)
                Rect rect = new Rect();
                int childCount = mListView.getChildCount();
                int[] listViewCoords = new int[2];
                mListView.getLocationOnScreen(listViewCoords);
                int x = (int) motionEvent.getRawX() - listViewCoords[0];
                int y = (int) motionEvent.getRawY() - listViewCoords[1];
                View child;
                for (int i = 0; i < childCount; i++) {
                    child = mListView.getChildAt(i);
                    child.getHitRect(rect);
                    if (rect.contains(x, y)) {
                        mDownView = child;
                        break;
                    }
                }

                if (mDownView != null) {
                    mDownX = motionEvent.getRawX();
                    mDownPosition = mListView.getPositionForView(mDownView);

                    mVelocityTracker = VelocityTracker.obtain();
                    mVelocityTracker.addMovement(motionEvent);
                }
                view.onTouchEvent(motionEvent);
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (mVelocityTracker == null) {
                    break;
                }

                float deltaX = motionEvent.getRawX() - mDownX;
                mVelocityTracker.addMovement(motionEvent);
                mVelocityTracker.computeCurrentVelocity(1000);
                float velocityX = Math.abs(mVelocityTracker.getXVelocity());
                float velocityY = Math.abs(mVelocityTracker.getYVelocity());
                boolean dismiss = false;
                boolean dismissRight = false;
                if (Math.abs(deltaX) > mViewWidth / 2 && mSwiping) {
                    dismiss = true;
                    dismissRight = deltaX > 0;
                } else if (mMinFlingVelocity <= velocityX && velocityX <= mMaxFlingVelocity
                        && velocityY < velocityX && mSwiping && isDirectionValid(mVelocityTracker.getXVelocity())
                        && deltaX >= mViewWidth * 0.2f) {
                    dismiss = true;
                    dismissRight = mVelocityTracker.getXVelocity() > 0;
                }
                if (dismiss) {
                    // dismiss
                    final View downView = mDownView; // mDownView gets null'd before animation ends
                    final int downPosition = mDownPosition;
                    ++mDismissAnimationRefCount;
                    animate(mDownView)
                            .translationX(dismissRight ? mViewWidth : -mViewWidth)
                            .alpha(0)
                            .setDuration(mAnimationTime)
                            .setListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    performDismiss(downView, downPosition);
                                }
                            });
                } else {
                    // cancel
                    animate(mDownView)
                            .translationX(0)
                            .alpha(1)
                            .setDuration(mAnimationTime)
                            .setListener(null);
                }
                mVelocityTracker = null;
                mDownX = 0;
                mDownView = null;
                mDownPosition = ListView.INVALID_POSITION;
                mSwiping = false;
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                hidePopup();

                if (mVelocityTracker == null || mPaused) {
                    break;
                }

                mVelocityTracker.addMovement(motionEvent);
                float deltaX = motionEvent.getRawX() - mDownX;
                // Only start swipe in correct direction
                if (isDirectionValid(deltaX)) {
                    if (Math.abs(deltaX) > mSlop) {
                        mSwiping = true;
                        mListView.requestDisallowInterceptTouchEvent(true);

                        // Cancel ListView's touch (un-highlighting the item)
                        MotionEvent cancelEvent = MotionEvent.obtain(motionEvent);
                        cancelEvent.setAction(MotionEvent.ACTION_CANCEL
                                | (motionEvent.getActionIndex()
                                << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
                        mListView.onTouchEvent(cancelEvent);
                    }
                } else {
                    // If we swiped into wrong direction, act like this was the new
                    // touch down point
                    mDownX = motionEvent.getRawX();
                    deltaX = 0;
                }

                if (mSwiping) {
                    setTranslationX(mDownView, deltaX);
                    setAlpha(mDownView, Math.max(0f, Math.min(1f,
                            1f - 2f * Math.abs(deltaX) / mViewWidth)));
                    return true;
                }
                break;
            }
        }
        return false;
    }

    /**
     * Checks whether the delta of a swipe indicates, that the swipe is in the
     * correct direction, regarding the direction set via
     * {@link #setSwipeDirection(de.timroes.swipetodismiss.SwipeDismissList.SwipeDirection)}
     *
     * @param deltaX The delta of x coordinate of the swipe.
     * @return Whether the delta of a swipe is in the right direction.
     */
    private boolean isDirectionValid(float deltaX) {

        int rtlSign = 1;
        // On API level 17 and above, check if we are in a Right-To-Left layout
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            if (mListView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                rtlSign = -1;
            }
        }

        // Check if swipe has been done in the corret direction
        switch (mSwipeDirection) {
            default:
            case BOTH:
                return true;
            case START:
                return rtlSign * deltaX < 0;
            case END:
                return rtlSign * deltaX > 0;
        }

    }

    class PendingDismissData implements Comparable<PendingDismissData> {

        public int position;
        public View view;

        public PendingDismissData(int position, View view) {
            this.position = position;
            this.view = view;
        }

        @Override
        public int compareTo(PendingDismissData other) {
            // Sort by descending position
            return other.position - position;
        }
    }

    private void performDismiss(final View dismissView, final int dismissPosition) {
        // Animate the dismissed list item to zero-height and fire the dismiss callback when
        // all dismissed list item animations have completed. This triggers layout on each animation
        // frame; in the future we may want to do something smarter and more performant.

        final ViewGroup.LayoutParams lp = dismissView.getLayoutParams();
        final int originalHeight = dismissView.getHeight();

        ValueAnimator animator = ValueAnimator.ofInt(originalHeight, 1).setDuration(mAnimationTime);

        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                --mDismissAnimationRefCount;
                if (mDismissAnimationRefCount == 0) {
                    // No active animations, process all pending dismisses.
                    int[] dismissPositions = new int[mPendingDismisses.size()];
                    int i = 0;
                    for (PendingDismissData dismiss : mPendingDismisses) {
                        dismissPositions[i++] = dismiss.position;
                    }
                    dismiss(dismissPositions);

                    ViewGroup.LayoutParams lp;
                    for (PendingDismissData pendingDismiss : mPendingDismisses) {
                        // Reset view presentation
                        setAlpha(pendingDismiss.view, 1f);
                        setTranslationX(pendingDismiss.view, 0);
                        lp = pendingDismiss.view.getLayoutParams();
                        lp.height = originalHeight;
                        pendingDismiss.view.setLayoutParams(lp);
                    }

                    mPendingDismisses.clear();
                }
            }
        });

        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                lp.height = (Integer) valueAnimator.getAnimatedValue();
                dismissView.setLayoutParams(lp);
            }
        });

        mPendingDismisses.add(new PendingDismissData(dismissPosition, dismissView));
        animator.start();
    }

    /**
     * Changes text in the popup depending on stored undos.
     */
    private void changePopupText() {
        String msg = "";
        if (mUndoActions.size() > 1 && mDeleteMultipleString != null) {
            msg = String.format(mDeleteMultipleString, mUndoActions.size());
        } else if (mUndoActions.size() >= 1) {
            // Set title from single undoable or when no multiple deletion string
            // is given
            if (mUndoActions.get(mUndoActions.size() - 1).getTitle() != null) {
                msg = mUndoActions.get(mUndoActions.size() - 1).getTitle();
            } else {
                msg = mDeleteString;
            }
        }
        mUndoText.setText(msg);
    }

    private void changeButtonLabel() {
        String msg;
        if (mUndoActions.size() > 1 && mMode == UndoMode.COLLAPSED_UNDO) {
            msg = mListView.getResources().getString(R.string.undoall);
        } else {
            msg = mListView.getResources().getString(R.string.undo);
        }
        mUndoButton.setText(msg);
    }

    /**
     * Enable/disable swipe.
     */
    public void setSwipeDisabled(boolean disabled) {
        this.mSwipeDisabled = disabled;
    }

}
