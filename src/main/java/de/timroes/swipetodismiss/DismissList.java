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

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link android.view.View.OnTouchListener} that makes the list items in a
 * {@link android.widget.ListView} dismissable. {@link android.widget.ListView} is given special treatment
 * because by default it handles touches for its list items... i.e. it's in
 * charge of drawing the pressed state (the list selector), handling list item
 * clicks, etc.
 * <p/>
 * Read the README file for a detailed explanation on how to use this class.
 */
public class DismissList {

    // Fixed properties
    protected final AbsListView mListView;
    protected final OnDismissCallback mCallback;

    protected final float mDensity;

    protected final UndoMode mMode;
    protected final List<Undoable> mUndoActions;
    protected final Handler mHandler;

    protected final PopupWindow mUndoPopup;
    protected final TextView mUndoText;
    protected final Button mUndoButton;

    protected int mAutoHideDelay = 5000;
    protected String mDeleteString = "Item deleted";
    protected String mDeleteMultipleString = "%d items deleted";

    private int mDelayedMsgId;

    /**
     * Defines the mode a {@link DismissList} handles multiple undos.
     */
    public enum UndoMode {
        /**
         * Only give the user the possibility to undo the last action.
         * As soon as another item is deleted, there is no chance to undo
         * the previous deletion.
         */
        SINGLE_UNDO,

        /**
         * Give the user the possibility to undo multiple deletions one by one.
         * Every click on Undo will undo the previous deleted item. Undos will be
         * collected as long as the undo popup stays open. As soon as the popup
         * vanished (because {@link SwipeDismissList#setAutoHideDelay(int) autoHideDelay} is over)
         * all saved undos will be discarded.
         */
        MULTI_UNDO,

        /**
         * Give the user the possibility to undo multiple deletions all together.
         * As long as the popup stays open all further deletions will be collected.
         * A click on the undo button will undo ALL deletions saved. As soon as
         * the popup vanished (because {@link SwipeDismissList#setAutoHideDelay(int) autoHideDelay}
         * is over) all saved undos will be discarded.
         */
        COLLAPSED_UNDO
    }

    /**
     * The callback interface used by {@link DismissList}
     * to inform its client about a successful dismissal of one or more list
     * item positions.
     */
    public interface OnDismissCallback {

        /**
         * Called when the user has indicated they she would like to dismiss one
         * or more list item positions.
         *
         * @param listView The originating {@link android.widget.ListView}.
         * @param position The position of the item to dismiss.
         */
        Undoable onDismiss(AbsListView listView, int position);
    }

    /**
     * An implementation of this abstract class must be returned by the
     * {@link OnDismissCallback#onDismiss(android.widget.AbsListView, int)} method,
     * if the user should be able to undo that dismiss. If the action will be undone
     * by the user {@link #undo()} will be called. That method should undo the previous
     * deletion of the item and add it back to the adapter. Read the README file for
     * more details. If you implement the {@link #getTitle()} method, the undo popup
     * will show an individual title for that item. Otherwise the default title
     * (set via {@link #setUndoString(String)}) will be shown.
     */
    public abstract static class Undoable {

        /**
         * Returns the individual undo message for this item shown in the
         * popup dialog.
         *
         * @return The individual undo message.
         */
        public String getTitle() {
            return null;
        }

        /**
         * Undoes the deletion.
         */
        public abstract void undo();

        /**
         * Will be called when this Undoable won't be able to undo anymore,
         * meaning the undo popup has disappeared from the screen.
         */
        public void discard() {
        }

    }

    /**
     * Constructs a new swipe-to-dismiss touch listener for the given list view.
     *
     * @param listView The list view whose items should be dismissable.
     * @param callback The callback to trigger when the user has indicated that
     *                 she would like to dismiss one or more list items.
     */
    public DismissList(AbsListView listView, OnDismissCallback callback) {
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
    public DismissList(AbsListView listView, OnDismissCallback callback, UndoMode mode) {
        if (listView == null) {
            throw new IllegalArgumentException("listview must not be null.");
        }

        mHandler = new HideUndoPopupHandler();
        mListView = listView;
        mCallback = callback;
        mMode = mode;

        mDensity = mListView.getResources().getDisplayMetrics().density;

        // -- Load undo popup --
        LayoutInflater inflater = (LayoutInflater) mListView.getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflater.inflate(R.layout.undo_popup, null);
        mUndoButton = (Button) v.findViewById(R.id.undo);
        mUndoButton.setOnClickListener(new UndoHandler());
        mUndoButton.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                // If user tabs "undo" button, reset delay time to remove popup
                interruptHidePopup();
                return false;
            }
        });
        mUndoText = (TextView) v.findViewById(R.id.text);

        mUndoPopup = new PopupWindow(v);
        mUndoPopup.setAnimationStyle(R.style.fade_animation);
        // Get screen width in dp and set width respectively
        int xdensity = (int) (mListView.getContext().getResources().getDisplayMetrics().widthPixels / mDensity);
        if (xdensity < 300) {
            mUndoPopup.setWidth((int) (mDensity * 280));
        } else if (xdensity < 350) {
            mUndoPopup.setWidth((int) (mDensity * 300));
        } else if (xdensity < 500) {
            mUndoPopup.setWidth((int) (mDensity * 330));
        } else {
            mUndoPopup.setWidth((int) (mDensity * 450));
        }
        mUndoPopup.setHeight((int) (mDensity * 56));
        // -- END Load undo popup --

        switch (mode) {
            case SINGLE_UNDO:
                mUndoActions = new ArrayList<Undoable>(1);
                break;
            default:
                mUndoActions = new ArrayList<Undoable>(10);
                break;
        }
    }

    /**
     * Sets the string shown in the undo popup. This will only show if
     * the {@link Undoable} returned by the {@link OnDismissCallback} returns
     * {@code null} from its {@link Undoable#getTitle()} method.
     *
     * @param msg The string shown in the undo popup.
     */
    public void setUndoString(String msg) {
        mDeleteString = msg;
    }

    /**
     * Sets the string shown in the undo popup, when {@link UndoMode} is set to
     * {@link UndoMode#MULTI_UNDO} or {@link UndoMode#COLLAPSED_UNDO} and
     * multiple deletions has been stored for undo. If this string contains
     * one {@code %d} inside, this will be filled by the numbers of stored undos.
     *
     * @param msg The string shown in the undo popup for multiple undos.
     */
    public void setUndoMultipleString(String msg) {
        mDeleteMultipleString = msg;
    }


    /**
     * Discard all stored undos and hide the undo popup dialog.
     */
    public void discardUndo() {
        for (Undoable undoable : mUndoActions) {
            undoable.discard();
        }
        mUndoActions.clear();
        mUndoPopup.dismiss();
    }

    /**
     * Dismisses the items at the given positions.
     *
     * @param positions The item positions.
     */
    public void dismiss(int... positions) {
        for (int position : positions) {
            if (mMode == UndoMode.SINGLE_UNDO) {
                for (Undoable undoable : mUndoActions) {
                    undoable.discard();
                }
                mUndoActions.clear();
            }
            Undoable undoable = mCallback.onDismiss(mListView, position);
            if (undoable != null) {
                mUndoActions.add(undoable);
            }
            interruptHidePopup();
        }

        if (!mUndoActions.isEmpty()) {
            changePopupText();
            changeButtonLabel();

            // Show undo popup
            mUndoPopup.showAtLocation(mListView,
                    Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM,
                    0, (int) (mDensity * 15));
        }
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
     * Takes care of undoing a dismiss. This will be added as a
     * {@link android.view.View.OnClickListener} to the undo button in the undo popup.
     */
    private class UndoHandler implements View.OnClickListener {

        public void onClick(View v) {
            if (!mUndoActions.isEmpty()) {
                switch (mMode) {
                    case SINGLE_UNDO:
                        mUndoActions.get(0).undo();
                        mUndoActions.clear();
                        break;
                    case COLLAPSED_UNDO:
                        Collections.reverse(mUndoActions);
                        for (Undoable undo : mUndoActions) {
                            undo.undo();
                        }
                        mUndoActions.clear();
                        break;
                    case MULTI_UNDO:
                        mUndoActions.get(mUndoActions.size() - 1).undo();
                        mUndoActions.remove(mUndoActions.size() - 1);
                        break;
                }
            }

            // Dismiss dialog or change text
            if (mUndoActions.isEmpty()) {
                mUndoPopup.dismiss();
            } else {
                changePopupText();
                changeButtonLabel();
            }

            interruptHidePopup();
        }

    }

    /**
     * Hide the popup after the configured delay, unless interrupted
     * by another event using {@link #interruptHidePopup()}.
     */
    protected void hidePopup() {
        if (mUndoPopup.isShowing()) {
            // Send a delayed message to hide popup
            mHandler.sendMessageDelayed(mHandler.obtainMessage(mDelayedMsgId),
                    mAutoHideDelay);
        }
    }

    /**
     * Interrupt the popup hiding, causing the popup to stay opened
     * until {@link #hidePopup()} is called again.
     */
    protected void interruptHidePopup() {
        ++mDelayedMsgId;
    }

    /**
     * Handler used to hide the undo popup after a special delay.
     */
    private class HideUndoPopupHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == mDelayedMsgId) {
                // Call discard on any element
                for (Undoable undo : mUndoActions) {
                    undo.discard();
                }
                mUndoActions.clear();
                mUndoPopup.dismiss();
            }
        }

    }

}
