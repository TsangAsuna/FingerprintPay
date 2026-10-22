package com.surcumference.fingerprint.util;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import com.surcumference.fingerprint.util.log.L;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 观察宿主 Activity 当前承载的 Fragment，命中指定类名时回调其根 View。
 *
 * <p>微信 8.0.20 起把设置页做成了 {@code LauncherUI} 内的
 * {@code com.tencent.mm.ui.vas.VASCommonFragment}，Activity 层面拿不到设置页，
 * 只能从 FragmentManager 取栈顶 Fragment 再判断。
 *
 * <p>该类在 7.x 与 {@code view/SettingsView} 一起被删除，此处按 6.1.0 行为重新实现，
 * 但不再依赖同样被删除的 {@code ReflectionUtils}，改用自带的逐层方法查找。
 */
public class FragmentObserver {

    private static final long IGNORE_TIME_MILLIS = 100;

    private final WeakReference<Activity> mActivityRef;
    private final Handler mTasksHandler = new Handler(Looper.getMainLooper());
    private boolean mRunning;
    private String mFragmentIdentifyClassName;
    private ViewTreeObserver.OnGlobalLayoutListener mOnGlobalLayoutListener;
    private long mLastTriggerTimeMillis;
    private IFragmentViewListener mListener;

    public FragmentObserver(Activity activity) {
        this.mActivityRef = new WeakReference<>(activity);
    }

    public void setFragmentIdentifyClassName(String fragmentIdentifyClassName) {
        this.mFragmentIdentifyClassName = fragmentIdentifyClassName;
    }

    public void start(IFragmentViewListener listener) {
        if (TextUtils.isEmpty(mFragmentIdentifyClassName)) {
            throw new IllegalArgumentException("FragmentIdentifyClassName not set");
        }
        mListener = listener;
        mRunning = true;
        Activity activity = mActivityRef.get();
        if (activity == null) {
            mRunning = false;
            return;
        }
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        if (mOnGlobalLayoutListener != null) {
            observer.removeOnGlobalLayoutListener(mOnGlobalLayoutListener);
        }
        mOnGlobalLayoutListener = () -> {
            if (!mRunning) {
                return;
            }
            // 布局回调会连续触发，节流后再查 Fragment 栈
            long now = SystemClock.uptimeMillis();
            if (mLastTriggerTimeMillis != 0 && now - mLastTriggerTimeMillis <= IGNORE_TIME_MILLIS) {
                mTasksHandler.removeCallbacks(mPollRunnable);
                mTasksHandler.postDelayed(mPollRunnable, IGNORE_TIME_MILLIS);
                return;
            }
            mLastTriggerTimeMillis = now;
            mPollRunnable.run();
        };
        observer.addOnGlobalLayoutListener(mOnGlobalLayoutListener);
    }

    public void stop() {
        mRunning = false;
        mTasksHandler.removeCallbacks(mPollRunnable);
        Activity activity = mActivityRef.get();
        if (activity == null) {
            return;
        }
        if (mOnGlobalLayoutListener != null) {
            activity.getWindow().getDecorView().getViewTreeObserver()
                    .removeOnGlobalLayoutListener(mOnGlobalLayoutListener);
            mOnGlobalLayoutListener = null;
        }
    }

    private final Runnable mPollRunnable = () -> {
        if (!mRunning) {
            return;
        }
        Activity activity = mActivityRef.get();
        if (activity == null) {
            return;
        }
        try {
            Object fragmentManager = invokeNoArg(activity, "getSupportFragmentManager");
            if (fragmentManager == null) {
                fragmentManager = invokeNoArg(activity, "getFragmentManager");
            }
            if (fragmentManager == null) {
                return;
            }
            Object fragments = invokeNoArg(fragmentManager, "getFragments");
            if (!(fragments instanceof List)) {
                return;
            }
            List<?> list = (List<?>) fragments;
            Object fragment = list.isEmpty() ? null : list.get(list.size() - 1);
            if (fragment == null || !fragment.getClass().getName().contains(mFragmentIdentifyClassName)) {
                return;
            }
            View fragmentView = (View) invokeNoArg(fragment, "getView");
            if (fragmentView == null || mListener == null) {
                return;
            }
            mListener.onFragmentFounded(this, fragment, fragmentView);
        } catch (Exception e) {
            L.e("FragmentObserver poll failed", e);
        }
    };

    /** 按名调用无参方法，沿类层级查找，避免依赖已删除的 ReflectionUtils。 */
    @Nullable
    private static Object invokeNoArg(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // 继续向父类找
            } catch (Exception e) {
                L.e("FragmentObserver invoke failed: " + name, e);
                return null;
            }
        }
        return null;
    }

    public interface IFragmentViewListener {
        void onFragmentFounded(FragmentObserver observer, Object fragmentObject, View fragmentRootView);
    }
}
