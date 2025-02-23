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
package com.android.launcher3.hybridhotseat;

import static com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.anim.AnimatorListeners.forSuccessCallback;
import static com.android.launcher3.hybridhotseat.HotseatEduController.getSettingsIntent;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_HOTSEAT_PREDICTION_PINNED;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_HOTSEAT_RANKED;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.ComponentName;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.DragSource;
import com.android.launcher3.DropTarget;
import com.android.launcher3.Hotseat;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.R;
import com.android.launcher3.anim.AnimationSuccessListener;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.dragndrop.DragController;
import com.android.launcher3.dragndrop.DragOptions;
import com.android.launcher3.graphics.DragPreviewProvider;
import com.android.launcher3.logger.LauncherAtom.ContainerInfo;
import com.android.launcher3.logger.LauncherAtom.PredictedHotseatContainer;
import com.android.launcher3.logging.InstanceId;
import com.android.launcher3.model.BgDataModel.FixedContainerItems;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.popup.SystemShortcut;
import com.android.launcher3.touch.ItemLongClickListener;
import com.android.launcher3.uioverrides.PredictedAppIcon;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.launcher3.util.OnboardingPrefs;
import com.android.launcher3.views.ArrowTipView;
import com.android.launcher3.views.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Provides prediction ability for the hotseat. Fills gaps in hotseat with predicted items, allows
 * pinning of predicted apps and manages replacement of predicted apps with user drag.
 */
public class HotseatPredictionController implements DragController.DragListener,
        SystemShortcut.Factory<QuickstepLauncher>, DeviceProfile.OnDeviceProfileChangeListener,
        DragSource, ViewGroup.OnHierarchyChangeListener {

    private static final int FLAG_UPDATE_PAUSED = 1 << 0;
    private static final int FLAG_DRAG_IN_PROGRESS = 1 << 1;
    private static final int FLAG_FILL_IN_PROGRESS = 1 << 2;
    private static final int FLAG_REMOVING_PREDICTED_ICON = 1 << 3;

    private int mHotSeatItemsCount;

    private QuickstepLauncher mLauncher;
    private final Hotseat mHotseat;
    private final Runnable mUpdateFillIfNotLoading = this::updateFillIfNotLoading;

    private List<ItemInfo> mPredictedItems = Collections.emptyList();

    private AnimatorSet mIconRemoveAnimators;
    private int mPauseFlags = 0;

    private List<PredictedAppIcon.PredictedIconOutlineDrawing> mOutlineDrawings = new ArrayList<>();

    private final View.OnLongClickListener mPredictionLongClickListener = v -> {
        if (!ItemLongClickListener.canStartDrag(mLauncher)) return false;
        if (mLauncher.getWorkspace().isSwitchingState()) return false;
        if (!mLauncher.getOnboardingPrefs().getBoolean(
                OnboardingPrefs.HOTSEAT_LONGPRESS_TIP_SEEN)) {
            Snackbar.show(mLauncher, R.string.hotseat_tip_gaps_filled,
                    R.string.hotseat_prediction_settings, null,
                    () -> mLauncher.startActivity(getSettingsIntent()));
            mLauncher.getOnboardingPrefs().markChecked(OnboardingPrefs.HOTSEAT_LONGPRESS_TIP_SEEN);
            mLauncher.getDragLayer().performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return true;
        }

        // Start the drag
        // Use a new itemInfo so that the original predicted item is stable
        WorkspaceItemInfo dragItem = new WorkspaceItemInfo((WorkspaceItemInfo) v.getTag());
        v.setVisibility(View.INVISIBLE);
        mLauncher.getWorkspace().beginDragShared(
                v, null, this, dragItem, new DragPreviewProvider(v),
                mLauncher.getDefaultWorkspaceDragOptions());
        return true;
    };

    public HotseatPredictionController(QuickstepLauncher launcher) {
        mLauncher = launcher;
        mHotseat = launcher.getHotseat();
        mHotSeatItemsCount = mLauncher.getDeviceProfile().numShownHotseatIcons;
        mLauncher.getDragController().addDragListener(this);

        launcher.addOnDeviceProfileChangeListener(this);
        mHotseat.getShortcutsAndWidgets().setOnHierarchyChangeListener(this);
    }

    @Override
    public void onChildViewAdded(View parent, View child) {
        onHotseatHierarchyChanged();
    }

    @Override
    public void onChildViewRemoved(View parent, View child) {
        onHotseatHierarchyChanged();
    }

    private void onHotseatHierarchyChanged() {
        if (mPauseFlags == 0 && !mLauncher.isWorkspaceLoading()) {
            // Post update after a single frame to avoid layout within layout
            MAIN_EXECUTOR.getHandler().removeCallbacks(mUpdateFillIfNotLoading);
            MAIN_EXECUTOR.getHandler().post(mUpdateFillIfNotLoading);
        }
    }

    private void updateFillIfNotLoading() {
        if (mPauseFlags == 0 && !mLauncher.isWorkspaceLoading()) {
            fillGapsWithPrediction(true);
        }
    }

    /**
     * Shows appropriate hotseat education based on prediction enabled and migration states.
     */
    public void showEdu() {
        mLauncher.getStateManager().goToState(NORMAL, true, forSuccessCallback(() -> {
            if (mPredictedItems.isEmpty()) {
                // launcher has empty predictions set
                Snackbar.show(mLauncher, R.string.hotsaet_tip_prediction_disabled,
                        R.string.hotseat_prediction_settings, null,
                        () -> mLauncher.startActivity(getSettingsIntent()));
            } else if (getPredictedIcons().size() >= (mHotSeatItemsCount + 1) / 2) {
                showDiscoveryTip();
            } else {
                HotseatEduController eduController = new HotseatEduController(mLauncher);
                eduController.setPredictedApps(mPredictedItems.stream()
                        .map(i -> (WorkspaceItemInfo) i)
                        .collect(Collectors.toList()));
                eduController.showEdu();
            }
        }));
    }

    /**
     * Shows educational tip for hotseat if user does not go through Tips app.
     */
    private void showDiscoveryTip() {
        if (getPredictedIcons().isEmpty()) {
            new ArrowTipView(mLauncher).show(
                    mLauncher.getString(R.string.hotseat_tip_no_empty_slots), mHotseat.getTop());
        } else {
            Snackbar.show(mLauncher, R.string.hotseat_tip_gaps_filled,
                    R.string.hotseat_prediction_settings, null,
                    () -> mLauncher.startActivity(getSettingsIntent()));
        }
    }

    /**
     * Returns if hotseat client has predictions
     */
    public boolean hasPredictions() {
        return !mPredictedItems.isEmpty();
    }

    private void fillGapsWithPrediction() {
        fillGapsWithPrediction(false);
    }

    private void fillGapsWithPrediction(boolean animate) {
        if (mPauseFlags != 0) {
            return;
        }

        int predictionIndex = 0;
        ArrayList<WorkspaceItemInfo> newItems = new ArrayList<>();
        // make sure predicted icon removal and filling predictions don't step on each other
        if (mIconRemoveAnimators != null && mIconRemoveAnimators.isRunning()) {
            mIconRemoveAnimators.addListener(new AnimationSuccessListener() {
                @Override
                public void onAnimationSuccess(Animator animator) {
                    fillGapsWithPrediction(animate);
                    mIconRemoveAnimators.removeListener(this);
                }
            });
            return;
        }

        mPauseFlags |= FLAG_FILL_IN_PROGRESS;
        for (int rank = 0; rank < mHotSeatItemsCount; rank++) {
            View child = mHotseat.getChildAt(
                    mHotseat.getCellXFromOrder(rank),
                    mHotseat.getCellYFromOrder(rank));

            if (child != null && !isPredictedIcon(child)) {
                continue;
            }
            if (mPredictedItems.size() <= predictionIndex) {
                // Remove predicted apps from the past
                if (isPredictedIcon(child)) {
                    mHotseat.removeView(child);
                }
                continue;
            }
            WorkspaceItemInfo predictedItem =
                    (WorkspaceItemInfo) mPredictedItems.get(predictionIndex++);
            if (isPredictedIcon(child) && child.isEnabled()) {
                PredictedAppIcon icon = (PredictedAppIcon) child;
                icon.applyFromWorkspaceItem(predictedItem);
                icon.finishBinding(mPredictionLongClickListener);
            } else {
                newItems.add(predictedItem);
            }
            preparePredictionInfo(predictedItem, rank);
        }
        bindItems(newItems, animate);

        mPauseFlags &= ~FLAG_FILL_IN_PROGRESS;
    }

    private void bindItems(List<WorkspaceItemInfo> itemsToAdd, boolean animate) {
        AnimatorSet animationSet = new AnimatorSet();
        for (WorkspaceItemInfo item : itemsToAdd) {
            PredictedAppIcon icon = PredictedAppIcon.createIcon(mHotseat, item);
            mLauncher.getWorkspace().addInScreenFromBind(icon, item);
            icon.finishBinding(mPredictionLongClickListener);
            if (animate) {
                animationSet.play(ObjectAnimator.ofFloat(icon, SCALE_PROPERTY, 0.2f, 1));
            }
        }
        if (animate) {
            animationSet.addListener(
                    forSuccessCallback(this::removeOutlineDrawings));
            animationSet.start();
        } else {
            removeOutlineDrawings();
        }

        if (mLauncher.getTaskbarUIController() != null) {
            mLauncher.getTaskbarUIController().onHotseatUpdated();
        }
    }

    private void removeOutlineDrawings() {
        if (mOutlineDrawings.isEmpty()) return;
        for (PredictedAppIcon.PredictedIconOutlineDrawing outlineDrawing : mOutlineDrawings) {
            mHotseat.removeDelegatedCellDrawing(outlineDrawing);
        }
        mHotseat.invalidate();
        mOutlineDrawings.clear();
    }


    /**
     * Unregisters callbacks and frees resources
     */
    public void destroy() {
        mLauncher.removeOnDeviceProfileChangeListener(this);
    }

    /**
     * start and pauses predicted apps update on the hotseat
     */
    public void setPauseUIUpdate(boolean paused) {
        mPauseFlags = paused
                ? (mPauseFlags | FLAG_UPDATE_PAUSED)
                : (mPauseFlags & ~FLAG_UPDATE_PAUSED);
        if (!paused) {
            fillGapsWithPrediction();
        }
    }

    /**
     * Sets or updates the predicted items
     */
    public void setPredictedItems(FixedContainerItems items) {
        boolean shouldIgnoreVisibility = FeatureFlags.ENABLE_APP_PREDICTIONS_WHILE_VISIBLE.get()
                || mLauncher.isWorkspaceLoading()
                || mPredictedItems.equals(items.items)
                || mHotseat.getShortcutsAndWidgets().getChildCount() < mHotSeatItemsCount;
        if (!shouldIgnoreVisibility
                && mHotseat.isShown()
                && mHotseat.getWindowVisibility() == View.VISIBLE) {
            mHotseat.setOnVisibilityAggregatedCallback((isVisible) -> {
                if (isVisible) {
                    return;
                }
                mHotseat.setOnVisibilityAggregatedCallback(null);

                applyPredictedItems(items);
            });
        } else {
            mHotseat.setOnVisibilityAggregatedCallback(null);

            applyPredictedItems(items);
        }
    }

    /**
     * Sets or updates the predicted items only once the hotseat becomes hidden to the user
     */
    private void applyPredictedItems(FixedContainerItems items) {
        mPredictedItems = items.items;
        if (mPredictedItems.isEmpty()) {
            HotseatRestoreHelper.restoreBackup(mLauncher);
        }
        fillGapsWithPrediction();
    }

    /**
     * Pins a predicted app icon into place.
     */
    public void pinPrediction(ItemInfo info) {
        PredictedAppIcon icon = (PredictedAppIcon) mHotseat.getChildAt(
                mHotseat.getCellXFromOrder(info.rank),
                mHotseat.getCellYFromOrder(info.rank));
        if (icon == null) {
            return;
        }
        WorkspaceItemInfo workspaceItemInfo = new WorkspaceItemInfo((WorkspaceItemInfo) info);
        mLauncher.getModelWriter().addItemToDatabase(workspaceItemInfo,
                LauncherSettings.Favorites.CONTAINER_HOTSEAT, workspaceItemInfo.screenId,
                workspaceItemInfo.cellX, workspaceItemInfo.cellY);
        ObjectAnimator.ofFloat(icon, SCALE_PROPERTY, 1, 0.8f, 1).start();
        icon.pin(workspaceItemInfo);
        mLauncher.getStatsLogManager().logger()
                .withItemInfo(workspaceItemInfo)
                .log(LAUNCHER_HOTSEAT_PREDICTION_PINNED);
    }

    private List<PredictedAppIcon> getPredictedIcons() {
        List<PredictedAppIcon> icons = new ArrayList<>();
        ViewGroup vg = mHotseat.getShortcutsAndWidgets();
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (isPredictedIcon(child)) {
                icons.add((PredictedAppIcon) child);
            }
        }
        return icons;
    }

    private void removePredictedApps(List<PredictedAppIcon.PredictedIconOutlineDrawing> outlines,
                                     DropTarget.DragObject dragObject) {
        if (mIconRemoveAnimators != null) {
            mIconRemoveAnimators.end();
        }
        mIconRemoveAnimators = new AnimatorSet();
        removeOutlineDrawings();
        for (PredictedAppIcon icon : getPredictedIcons()) {
            if (!icon.isEnabled()) {
                continue;
            }
            if (dragObject.dragSource == this && icon.equals(dragObject.originalView)) {
                removeIconWithoutNotify(icon);
                continue;
            }
            int rank = ((WorkspaceItemInfo) icon.getTag()).rank;
            outlines.add(new PredictedAppIcon.PredictedIconOutlineDrawing(
                    mHotseat.getCellXFromOrder(rank), mHotseat.getCellYFromOrder(rank), icon));
            icon.setEnabled(false);
            ObjectAnimator animator = ObjectAnimator.ofFloat(icon, SCALE_PROPERTY, 0);
            animator.addListener(new AnimationSuccessListener() {
                @Override
                public void onAnimationSuccess(Animator animator) {
                    if (icon.getParent() != null) {
                        removeIconWithoutNotify(icon);
                    }
                }
            });
            mIconRemoveAnimators.play(animator);
        }
        mIconRemoveAnimators.start();
    }

    /**
     * Removes icon while suppressing any extra tasks performed on view-hierarchy changes.
     * This avoids recursive/redundant updates as the control updates the UI anyway after
     * it's animation.
     */
    private void removeIconWithoutNotify(PredictedAppIcon icon) {
        mPauseFlags |= FLAG_REMOVING_PREDICTED_ICON;
        mHotseat.removeView(icon);
        mPauseFlags &= ~FLAG_REMOVING_PREDICTED_ICON;
    }

    @Override
    public void onDragStart(DropTarget.DragObject dragObject, DragOptions options) {
        removePredictedApps(mOutlineDrawings, dragObject);
        if (mOutlineDrawings.isEmpty()) return;
        for (PredictedAppIcon.PredictedIconOutlineDrawing outlineDrawing : mOutlineDrawings) {
            mHotseat.addDelegatedCellDrawing(outlineDrawing);
        }
        mPauseFlags |= FLAG_DRAG_IN_PROGRESS;
        mHotseat.invalidate();
    }

    @Override
    public void onDragEnd() {
        mPauseFlags &= ~FLAG_DRAG_IN_PROGRESS;
        fillGapsWithPrediction(true);
    }

    @Nullable
    @Override
    public SystemShortcut<QuickstepLauncher> getShortcut(QuickstepLauncher activity,
                                                         ItemInfo itemInfo) {
        if (itemInfo.container != LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION) {
            return null;
        }
        return new PinPrediction(activity, itemInfo);
    }

    private void preparePredictionInfo(WorkspaceItemInfo itemInfo, int rank) {
        itemInfo.container = LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION;
        itemInfo.rank = rank;
        itemInfo.cellX = mHotseat.getCellXFromOrder(rank);
        itemInfo.cellY = mHotseat.getCellYFromOrder(rank);
        itemInfo.screenId = rank;
    }

    @Override
    public void onDeviceProfileChanged(DeviceProfile profile) {
        this.mHotSeatItemsCount = profile.numShownHotseatIcons;
    }

    @Override
    public void onDropCompleted(View target, DropTarget.DragObject d, boolean success) {
        //Does nothing
    }

    /**
     * Logs rank info based on current list of predicted items
     */
    public void logLaunchedAppRankingInfo(@NonNull ItemInfo itemInfo, InstanceId instanceId) {
        ComponentName targetCN = itemInfo.getTargetComponent();
        if (targetCN == null) {
            return;
        }
        int rank = -1;
        for (int i = mPredictedItems.size() - 1; i >= 0; i--) {
            ItemInfo info = mPredictedItems.get(i);
            if (targetCN.equals(info.getTargetComponent()) && itemInfo.user.equals(info.user)) {
                rank = i;
                break;
            }
        }
        if (rank < 0) {
            return;
        }

        int cardinality = 0;
        for (PredictedAppIcon icon : getPredictedIcons()) {
            ItemInfo info = (ItemInfo) icon.getTag();
            cardinality |= 1 << info.screenId;
        }

        PredictedHotseatContainer.Builder containerBuilder = PredictedHotseatContainer.newBuilder();
        containerBuilder.setCardinality(cardinality);
        if (itemInfo.container == LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION) {
            containerBuilder.setIndex(rank);
        }
        mLauncher.getStatsLogManager().logger()
                .withInstanceId(instanceId)
                .withRank(rank)
                .withContainerInfo(ContainerInfo.newBuilder()
                        .setPredictedHotseatContainer(containerBuilder)
                        .build())
                .log(LAUNCHER_HOTSEAT_RANKED);
    }

    private class PinPrediction extends SystemShortcut<QuickstepLauncher> {

        private PinPrediction(QuickstepLauncher target, ItemInfo itemInfo) {
            super(R.drawable.ic_pin, R.string.pin_prediction, target,
                    itemInfo);
        }

        @Override
        public void onClick(View view) {
            dismissTaskMenuView(mTarget);
            pinPrediction(mItemInfo);
        }
    }

    private static boolean isPredictedIcon(View view) {
        return view instanceof PredictedAppIcon && view.getTag() instanceof WorkspaceItemInfo
                && ((WorkspaceItemInfo) view.getTag()).container
                == LauncherSettings.Favorites.CONTAINER_HOTSEAT_PREDICTION;
    }
}
