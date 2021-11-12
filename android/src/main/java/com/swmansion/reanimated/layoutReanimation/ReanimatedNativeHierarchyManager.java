package com.swmansion.reanimated.layoutReanimation;

import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.Nullable;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.uimanager.IllegalViewOperationException;
import com.facebook.react.uimanager.NativeViewHierarchyManager;
import com.facebook.react.uimanager.RootViewManager;
import com.facebook.react.uimanager.ViewAtIndex;
import com.facebook.react.uimanager.ViewGroupManager;
import com.facebook.react.uimanager.ViewManager;
import com.facebook.react.uimanager.ViewManagerRegistry;
import com.facebook.react.uimanager.layoutanimation.LayoutAnimationController;
import com.facebook.react.uimanager.layoutanimation.LayoutAnimationListener;
import com.swmansion.reanimated.ReanimatedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

class ReaLayoutAnimator extends LayoutAnimationController {
  private AnimationsManager mAnimationsManager = null;
  private volatile boolean mInitialized = false;
  private ReactApplicationContext mContext;
  private WeakReference<NativeViewHierarchyManager> mWeakNativeViewHierarchyManage =
      new WeakReference<>(null);

  ReaLayoutAnimator(
      ReactApplicationContext context, NativeViewHierarchyManager nativeViewHierarchyManager) {
    mContext = context;
    mWeakNativeViewHierarchyManage = new WeakReference<>(nativeViewHierarchyManager);
  }

  public void maybeInit() {
    if (!mInitialized) {
      mInitialized = true;
      ReanimatedModule reanimatedModule = mContext.getNativeModule(ReanimatedModule.class);
      mAnimationsManager = reanimatedModule.getNodesManager().getAnimationsManager();
      mAnimationsManager.setReanimatedNativeHierarchyManager(
          (ReanimatedNativeHierarchyManager) mWeakNativeViewHierarchyManage.get());
    }
  }

  public boolean shouldAnimateLayout(View viewToAnimate) {
    // if view parent is null, skip animation: view have been clipped, we don't want animation to
    // resume when view is re-attached to parent, which is the standard android animation behavior.
    // If there's a layout handling animation going on, it should be animated nonetheless since the
    // ongoing animation needs to be updated.
    if (viewToAnimate == null) {
      return false;
    }
    return (viewToAnimate.getParent() != null);
  }

  /**
   * Update layout of given view, via immediate update or animation depending on the current batch
   * layout animation configuration supplied during initialization. Handles create and update
   * animations.
   *
   * @param view the view to update layout of
   * @param x the new X position for the view
   * @param y the new Y position for the view
   * @param width the new width value for the view
   * @param height the new height value for the view
   */
  public void applyLayoutUpdate(View view, int x, int y, int width, int height) {
    UiThreadUtil.assertOnUiThread();
    maybeInit();
    // Determine which animation to use : if view is initially invisible, use create animation,
    // otherwise use update animation. This approach is easier than maintaining a list of tags
    // for recently created views.
    if (view.getWidth() == 0 || view.getHeight() == 0) {
      view.layout(x, y, x + width, y + height);
      if (view.getId() != -1) {
        mAnimationsManager.onViewCreate(
            view,
            (ViewGroup) view.getParent(),
            new Snapshot(view, mWeakNativeViewHierarchyManage.get()));
      }
    } else {
      Snapshot before = new Snapshot(view, mWeakNativeViewHierarchyManage.get());
      view.layout(x, y, x + width, y + height);
      Snapshot after = new Snapshot(view, mWeakNativeViewHierarchyManage.get());
      mAnimationsManager.onViewUpdate(view, before, after);
    }
  }

  /**
   * Animate a view deletion using the layout animation configuration supplied during
   * initialization.
   *
   * @param view The view to animate.
   * @param listener Called once the animation is finished, should be used to completely remove the
   *     view.
   */
  public void deleteView(final View view, final LayoutAnimationListener listener) {
    UiThreadUtil.assertOnUiThread();
    NativeViewHierarchyManager nativeViewHierarchyManager = mWeakNativeViewHierarchyManage.get();
    ViewManager viewManager = nativeViewHierarchyManager.resolveViewManager(view.getId());
    // we don't want layout animations in native-stack since it is currently buggy there
    // so we check if it is a (grand)child of ScreenStack
    if (viewManager.getName().equals("RNSScreen")
        && view.getParent() != null
        && view.getParent().getParent() instanceof View) {
      // we check grandparent of Screen since the parent is a ScreenStackFragment
      View screenParentView = (View) view.getParent().getParent();
      ViewManager screenParentViewManager =
          nativeViewHierarchyManager.resolveViewManager(screenParentView.getId());
      String parentName = screenParentViewManager.getName();
      if (parentName.equals("RNSScreenStack")) {
        super.deleteView(view, listener);
        return;
      }
    }
    maybeInit();
    Snapshot before = new Snapshot(view, mWeakNativeViewHierarchyManage.get());
    mAnimationsManager.onViewRemoval(
        view, (ViewGroup) view.getParent(), before, () -> listener.onAnimationEnd());
    if (viewManager instanceof ViewGroupManager) {
      ViewGroupManager vgm = (ViewGroupManager) viewManager;
      for (int i = 0; i < vgm.getChildCount((ViewGroup) view); ++i) {
        dfs(vgm.getChildAt((ViewGroup) view, i), nativeViewHierarchyManager);
      }
    }
  }

  private void dfs(View view, NativeViewHierarchyManager nativeViewHierarchyManager) {
    int tag = view.getId();
    if (tag == -1) {
      return;
    }
    ViewManager vm = null;
    try {
      vm = nativeViewHierarchyManager.resolveViewManager(tag);
      Snapshot before = new Snapshot(view, mWeakNativeViewHierarchyManage.get());
      mAnimationsManager.onViewRemoval(
          view,
          (ViewGroup) view.getParent(),
          before,
          () -> {
            ReanimatedNativeHierarchyManager reanimatedNativeHierarchyManager =
                (ReanimatedNativeHierarchyManager) nativeViewHierarchyManager;
            reanimatedNativeHierarchyManager.publicDropView(view);
          });
    } catch (IllegalViewOperationException e) {
      // (IllegalViewOperationException) == (vm == null)
      // do nothing
    }
    if (vm instanceof ViewGroupManager) {
      ViewGroupManager vgm = (ViewGroupManager) vm;
      for (int i = 0; i < vgm.getChildCount((ViewGroup) view); ++i) {
        dfs(vgm.getChildAt((ViewGroup) view, i), nativeViewHierarchyManager);
      }
    }
  }
}

public class ReanimatedNativeHierarchyManager extends NativeViewHierarchyManager {
  private HashMap<Integer, ArrayList<View>> toBeRemoved = new HashMap();
  private HashMap<Integer, Runnable> cleanerCallback = new HashMap();
  private LayoutAnimationController mReaLayoutAnimator = null;
  private HashMap<Integer, Set<Integer>> mPendingDeletionsForTag = null;

  public ReanimatedNativeHierarchyManager(
      ViewManagerRegistry viewManagers, ReactApplicationContext reactContext) {
    super(viewManagers);
    Class clazz = this.getClass().getSuperclass();
    try {
      Field field = clazz.getDeclaredField("mLayoutAnimator");
      field.setAccessible(true);
      Field modifiersField = Field.class.getDeclaredField("accessFlags");
      modifiersField.setAccessible(true);
      modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
      mReaLayoutAnimator = new ReaLayoutAnimator(reactContext, this);
      field.set(this, mReaLayoutAnimator);

      Field pendingTagsField = clazz.getDeclaredField("mPendingDeletionsForTag");
      pendingTagsField.setAccessible(true);
      Field pendingTagsFieldModifiers = Field.class.getDeclaredField("accessFlags");
      pendingTagsFieldModifiers.setAccessible(true);
      pendingTagsFieldModifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
      mPendingDeletionsForTag = new HashMap<Integer, Set<Integer>>();
      pendingTagsField.set(this, mPendingDeletionsForTag);

    } catch (NoSuchFieldException | IllegalAccessException e) {
      e.printStackTrace();
    }
    setLayoutAnimationEnabled(true);
  }

  public ReanimatedNativeHierarchyManager(
      ViewManagerRegistry viewManagers, RootViewManager manager) {
    super(viewManagers, manager);
  }

  public synchronized void updateLayout(
      int parentTag, int tag, int x, int y, int width, int height) {
    super.updateLayout(parentTag, tag, x, y, width, height);
    View viewToUpdate = this.resolveView(tag);
    ViewManager parentViewManager = this.resolveViewManager(parentTag);
    String parentViewManagerName = parentViewManager.getName();
    View container = resolveView(parentTag);
    if (container != null
        && parentViewManagerName.equals("RNSScreenContainer")
        && this.mReaLayoutAnimator != null) {
      this.mReaLayoutAnimator.applyLayoutUpdate(
          viewToUpdate, 0, 0, container.getWidth(), container.getHeight());
    }
  }

  @Override
  public synchronized void manageChildren(
      int tag,
      @Nullable int[] indicesToRemove,
      @Nullable ViewAtIndex[] viewsToAdd,
      @Nullable int[] tagsToDelete) {
    ViewGroup viewGroup = (ViewGroup) resolveView(tag);
    ViewGroupManager viewGroupManager = (ViewGroupManager) resolveViewManager(tag);

    // we don't want layout animations in native-stack since it is currently buggy there
    if (viewGroupManager.getName().equals("RNSScreenStack")) {
      super.manageChildren(tag, indicesToRemove, viewsToAdd, tagsToDelete);
      return;
    }

    if (toBeRemoved.containsKey(tag)) {
      ArrayList<View> childrenToBeRemoved = toBeRemoved.get(tag);
      HashSet<Integer> tagsToRemove = new HashSet<Integer>();
      for (View childToRemove : childrenToBeRemoved) {
        tagsToRemove.add(childToRemove.getId());
      }
      while (viewGroupManager.getChildCount(viewGroup) != 0) {
        View child =
            viewGroupManager.getChildAt(viewGroup, viewGroupManager.getChildCount(viewGroup) - 1);
        if (tagsToRemove.contains(child.getId())) {
          viewGroupManager.removeViewAt(viewGroup, viewGroupManager.getChildCount(viewGroup) - 1);
        } else {
          break;
        }
      }
    }
    if (tagsToDelete != null) {
      if (!toBeRemoved.containsKey(tag)) {
        toBeRemoved.put(tag, new ArrayList<>());
      }
      ArrayList<View> toBeRemovedChildren = toBeRemoved.get(tag);
      for (Integer childtag : tagsToDelete) {
        View view = resolveView(childtag);
        toBeRemovedChildren.add(view);
        cleanerCallback.put(
            view.getId(),
            new Runnable() {
              @Override
              public void run() {
                toBeRemovedChildren.remove(view);
                viewGroupManager.removeView(viewGroup, view);
              } // It's far from optimal but let's leave it as it is for now
            });
      }
    }

    // mPendingDeletionsForTag is modify by React
    if (mPendingDeletionsForTag != null) {
      Set<Integer> pendingTags = mPendingDeletionsForTag.get(tag);
      if (pendingTags != null) {
        pendingTags.clear();
      }
    }

    super.manageChildren(tag, indicesToRemove, viewsToAdd, null);
    if (toBeRemoved.containsKey(tag)) {
      ArrayList<View> childrenToBeRemoved = toBeRemoved.get(tag);
      for (View child : childrenToBeRemoved) {
        viewGroupManager.addView(viewGroup, child, viewGroupManager.getChildCount(viewGroup));
      }
    }
    super.manageChildren(tag, null, null, tagsToDelete);
  }

  public void publicDropView(View view) {
    dropView(view);
  }

  @Override
  protected synchronized void dropView(View view) {
    if (toBeRemoved.containsKey(view.getId())) {
      toBeRemoved.remove(view.getId());
    }
    if (cleanerCallback.containsKey(view.getId())) {
      Runnable runnable = cleanerCallback.get(view.getId());
      cleanerCallback.remove(view.getId());
      runnable.run();
    }
    // childrens' callbacks should be cleaned by former publicDropView calls as Animation Manager
    // stripes views from bottom to top
    super.dropView(view);
  }
}
