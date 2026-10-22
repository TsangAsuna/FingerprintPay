package com.surcumference.fingerprint.plugin.impl;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.hjq.toast.Toaster;
import com.surcumference.fingerprint.BuildConfig;
import com.surcumference.fingerprint.Lang;
import com.surcumference.fingerprint.R;
import com.surcumference.fingerprint.util.DpUtils;
import com.surcumference.fingerprint.util.StyleUtils;
import com.surcumference.fingerprint.util.ViewUtils;
import com.surcumference.fingerprint.util.log.L;

/**
 * 在宿主应用（微信 / 支付宝 / 淘宝 / QQ / 云闪付）的设置页注入「指纹支付」入口。
 *
 * <p>背景：上游自 v7.0.0 起移除了应用内设置入口（CHANGELOG：「新增统一配置页面，替代应用内设置入口」），
 * 同时删除了 {@code view/SettingsView.java} 及其依赖的 6 个视图类。v6.1.0 尚保留该入口，
 * 因此这里把入口恢复回来。
 *
 * <p>与 v6.1.0 的关键差异：入口点击后<b>不再</b>打开 v6.1.0 的本地 {@code SettingsView}，原因是
 * 该对话框通过 {@code Config.from(context).setOn()/setPasswordEncrypted()} 直接写宿主侧的
 * SharedPreferences，而 7.x 的配置已改为 {@code ConfigStores} 的 revision/patch JSON 协议
 * （Zygisk 后端落 {@code /data/adb/fingerprintpay/user-0.json}，Xposed 后端落 APK 侧
 * {@code unified_config_v1}）。若沿用旧对话框，会出现「开关能拨动但实际不生效」的假配置。
 * 因此入口改为直接拉起 7.x 的统一配置页 {@code ConfigActivity}，所改配置必然生效。
 *
 * <p>跨进程说明：本类代码运行在宿主应用进程内，与指纹支付 APK 不同 UID，因此
 * 必须 {@code FLAG_ACTIVITY_NEW_TASK}，且目标 Activity 必须在 Manifest 中 exported=true。
 */
public final class SettingEntry {

    /**
     * 组件包名 = applicationId。
     */
    private static final String HOST_PACKAGE = BuildConfig.APPLICATION_ID;

    /**
     * 候选配置页，按优先级排列。主候选失败（组件不存在、未导出、被停用、装在分身用户）时逐个降级：
     * 7.x 统一配置页 → 7.x 启动页（{@code ConfigActivity} 的子类，Manifest 中必然 exported）→ 6.x 旧设置页。
     *
     * <p>类名必须用 <b>namespace</b>（com.surcumference.fingerprint）而不是 applicationId
     * （com.surcumference.fingerprintpay）：本工程两者不同，而 Manifest 里的相对名
     * {@code .activity.ConfigActivity} 是按 namespace 解析的，构建产物中的真实组件名是
     * com.surcumference.fingerprint.activity.ConfigActivity（已解析 APK 二进制清单确认）。
     * 用字符串常量而非 {@code ConfigActivity.class}，是为了避免在宿主进程里触发类加载。
     */
    private static final String[] CONFIG_ACTIVITY_CANDIDATES = {
            "com.surcumference.fingerprint.activity.ConfigActivity",
            "com.surcumference.fingerprint.activity.HomeActivity",
            "com.surcumference.fingerprint.activity.SettingsActivity",
    };

    /**
     * 入口要打开的配置页必须 {@code exported=true}，而这个改动是本 fork 在 7.0.3 才引入的：
     * 7.0.0 ~ 7.0.2 以及 6.x 的 {@code ConfigActivity} 都是 exported=false，跨 UID 根本起不来。
     * 因此低于 7.0.3 的 APK 一律提示升级，而不是让用户去点一个注定失败的入口。
     * 这里刻意不随 {@code BuildConfig.VERSION_NAME} 走——它标记的是「历史上修好导出的那一版」。
     */
    private static final String MIN_SUPPORTED_VERSION_NAME = "7.0.3";

    private SettingEntry() {
    }

    /** 已安装的指纹支付 APK 的关键信息，用于失败时给出可定位的结论。 */
    private static final class Installed {
        private final String versionName;
        private final int activityCount;

        private Installed(String versionName, int activityCount) {
            this.versionName = versionName;
            this.activityCount = activityCount;
        }

        /** @return 版本号可比较时为负/零/正；版本号缺失或无法解析时返回 0（按「相同」处理） */
        private int compareTo(String other) {
            if (versionName == null) {
                return 0;
            }
            return compareVersions(versionName, other);
        }

        private String describe() {
            return versionName == null ? "未知版本" : versionName;
        }
    }

    /** 逐段数值比较 "7.0.2" 与 "7.0.3"；任一段缺失按 0 计，段内只取前导数字。 */
    private static int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int x = i < left.length ? leadingInt(left[i]) : 0;
            int y = i < right.length ? leadingInt(right[i]) : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    private static int leadingInt(String part) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            digits.append(c);
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 打开指纹支付的统一配置页。
     *
     * <p>必须做到「失败必报，且报出可定位的原因」。此前只试一个显式组件，用户侧一旦出现
     * {@code ActivityNotFoundException}，既可能是 APK 根本没装、装的是 6.x，也可能是装了
     * 7.0.2 及更早版本（那一版的 {@code ConfigActivity} 还是 {@code exported=false}，
     * 跨 UID 起不来），还可能装在分身用户或被系统停用——而旧代码只会甩一句异常类名。
     * 现在按「查版本 → 版本不符直接提示升级 → 逐级降级启动 → 汇总诊断」四步走。
     */
    public static void open(Context context) {
        if (context == null) {
            return;
        }
        Context from = context.getApplicationContext();
        if (from == null) {
            from = context;
        }

        // 1) 先问清楚用户装的是什么。查不到不立刻下结论——Android 11+ 宿主可能看不到本包，
        //    但显式 Intent 仍有机会启动成功，所以真正的判定留给后面的启动尝试。
        Installed installed = queryInstalled(from);
        L.d("SettingEntry: installed " + HOST_PACKAGE + " " + (installed == null
                ? "<not visible>" : installed.describe() + " activities=" + installed.activityCount));

        int compare = installed == null ? 0 : installed.compareTo(MIN_SUPPORTED_VERSION_NAME);
        if (compare < 0) {
            toast(from, "当前安装的指纹支付是 " + installed.describe()
                    + "，该版本的配置页未对外导出，无法从其他应用内打开，请升级到 "
                    + MIN_SUPPORTED_VERSION_NAME + " 或更新版本");
            return;
        }

        // 2) 依次尝试候选配置页，任何一路成功即返回
        for (String clz : CONFIG_ACTIVITY_CANDIDATES) {
            if (startComponent(from, clz)) {
                return;
            }
        }

        // 3) 再退一步：直接拉起应用的启动入口（Manifest 里的 .Main activity-alias）
        if (startLaunchIntent(from)) {
            return;
        }

        // 4) 全部失败：给出能区分「没装」和「装了但打不开」的结论
        if (installed == null) {
            L.e("SettingEntry: host package not installed or not visible: " + HOST_PACKAGE);
            toast(from, "未检测到「指纹支付」APK：请安装模块配套 APK，或在 Root 管理器中打开该模块的 WebUI");
        } else {
            L.e("SettingEntry: no config page found in " + HOST_PACKAGE + " " + installed.describe());
            toast(from, "打开指纹支付设置失败：已安装 " + installed.describe()
                    + " 但找不到配置页，请确认它装在本机主用户且未被停用；也可改用 Root 管理器里的模块 WebUI");
        }
    }

    /** 查已安装版本；未安装或宿主不可见时返回 null。 */
    private static Installed queryInstalled(Context from) {
        try {
            PackageManager pm = from.getPackageManager();
            if (pm == null) {
                return null;
            }
            PackageInfo info = pm.getPackageInfo(HOST_PACKAGE, PackageManager.GET_ACTIVITIES);
            if (info == null) {
                return null;
            }
            int count = info.activities == null ? 0 : info.activities.length;
            return new Installed(info.versionName, count);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean startComponent(Context from, String clz) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(HOST_PACKAGE, clz));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            from.startActivity(intent);
            L.d("SettingEntry: opened " + clz);
            return true;
        } catch (Exception e) {
            // ActivityNotFoundException（组件不存在/未安装）、SecurityException（未导出）都会走到这里
            L.d("SettingEntry: cannot start " + clz + ": " + e);
            return false;
        }
    }

    private static boolean startLaunchIntent(Context from) {
        try {
            Intent intent = from.getPackageManager().getLaunchIntentForPackage(HOST_PACKAGE);
            if (intent == null) {
                L.d("SettingEntry: no launch intent for " + HOST_PACKAGE);
                return false;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            from.startActivity(intent);
            L.d("SettingEntry: opened launcher intent for " + HOST_PACKAGE);
            return true;
        } catch (Exception e) {
            L.d("SettingEntry: cannot launch " + HOST_PACKAGE + ": " + e);
            return false;
        }
    }

    private static void toast(Context context, String message) {
        // 不能静默：否则用户只感到"点了没反应"，无从判断是入口坏了还是目标页不存在
        try {
            Toaster.showLong(message);
        } catch (Exception ignored) {
            // Toaster 在个别宿主上可能不可用，退回纯日志
        }
    }

    /**
     * 构建一行设置入口：左侧「指纹支付」，右侧版本号，整行可点击。
     *
     * @param darkMode      宿主是否处于深色模式
     * @param hPaddingDp    右侧版本号的水平内边距（dp），各宿主取值不同以对齐原生样式
     * @param cornerRadiusDp 圆角半径（dp），0 表示直角
     */
    public static LinearLayout buildRow(Context context, boolean darkMode, int hPaddingDp, int cornerRadiusDp) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(1);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(itemBackground(context, darkMode, cornerRadiusDp));
        row.setOnClickListener(view -> open(view.getContext()));
        // 幂等标记：同一页面重复注入时靠它判重
        row.setTag(R.id.app_settings_name, Boolean.TRUE);

        TextView name = new TextView(context);
        name.setText(Lang.getString(R.id.app_settings_name));
        name.setTextColor(darkMode ? 0xFFD3D3D3 : 0xFF353535);
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setPadding(DpUtils.dip2px(context, 16), 0, 0, 0);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, StyleUtils.TEXT_SIZE_BIG);

        TextView version = new TextView(context);
        version.setText(BuildConfig.VERSION_NAME);
        version.setGravity(Gravity.CENTER_VERTICAL);
        version.setPadding(0, 0, DpUtils.dip2px(context, hPaddingDp), 0);
        version.setTextColor(darkMode ? 0xFF656565 : 0xFF999999);
        version.setTextSize(TypedValue.COMPLEX_UNIT_DIP, StyleUtils.TEXT_SIZE_SMALL);

        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        row.addView(version, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return row;
    }

    /** 构建「分割线 + 入口行」的竖向容器，供需要整块插入的宿主（微信 / 淘宝）使用。 */
    public static LinearLayout buildSection(Context context, boolean darkMode, int hPaddingDp) {
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);

        View divider = new View(context);
        divider.setBackgroundColor(darkMode ? 0xFF2E2E2E : 0xFFD5D5D5);

        section.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        section.addView(buildRow(context, darkMode, hPaddingDp, 0),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, DpUtils.dip2px(context, 55)));
        return section;
    }

    /**
     * 通用注入：以 {@code anchorTexts} 在设置页里定位到一个已知设置项，
     * 把入口插到该行上方。锚点找不到时静默返回 false，不抛异常、不影响宿主。
     *
     * <p>相比上游 v6.1.0 为每个宿主硬编码「父容器往上第 N 层」，这里改为逐层试探
     * 列表行容器，宿主改版时更容易存活。
     */
    public static boolean injectAboveAnchor(Activity activity, String... anchorTexts) {
        return activity == null ? false
                : injectAboveAnchor(activity.getWindow().getDecorView(), activity, anchorTexts);
    }

    /**
     * 同 {@link #injectAboveAnchor(Activity, String...)}，但作用于指定根视图。
     * 宿主设置页是 Fragment 时（如微信 8.0.20+ 的 VASCommonFragment），
     * 只能用 Fragment 根视图而不能用 Activity 的 decorView。
     */
    public static boolean injectAboveAnchor(View root, Context context, String... anchorTexts) {
        if (root == null || context == null) {
            return false;
        }
        try {
            if (hasEntry(root)) {
                return false;
            }
            View anchor = ViewUtils.findViewByText(root, anchorTexts);
            if (anchor == null) {
                return false;
            }
            View row = findRow(anchor);
            if (row == null) {
                return false;
            }
            View parentView = (View) row.getParent();
            if (!(parentView instanceof ViewGroup)) {
                return false;
            }
            ViewGroup parent = (ViewGroup) parentView;
            int index = parent.indexOfChild(row);
            if (index < 0) {
                return false;
            }
            LinearLayout section = buildSection(context, StyleUtils.isDarkMode(context), 15);
            parent.addView(section, index, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            L.d("SettingEntry injected above anchor: " + anchorTexts[0]);
            return true;
        } catch (Exception e) {
            L.e("SettingEntry.injectAboveAnchor failed", e);
            return false;
        }
    }

    /**
     * 从锚点文字视图向上找出「一行」：其父容器含多个子视图、且自身宽度铺满。
     * 最多上溯 4 层，避免把整页根容器当成一行。
     */
    private static View findRow(View anchor) {
        View current = anchor;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            View parentView = (View) current.getParent();
            if (!(parentView instanceof ViewGroup)) {
                return null;
            }
            ViewGroup parent = (ViewGroup) parentView;
            ViewGroup.LayoutParams params = current.getLayoutParams();
            boolean fullWidth = params != null
                    && (params.width == ViewGroup.LayoutParams.MATCH_PARENT || params.width < 0);
            if (parent.getChildCount() >= 2 && fullWidth) {
                return current;
            }
            current = parent;
        }
        return null;
    }

    /**
     * 判重：视图树里是否已经存在本模块注入的入口。
     * 用 tag 而非文字匹配，避免宿主自身存在同名文案时误判。
     */
    public static boolean hasEntry(View root) {
        if (root == null) {
            return false;
        }
        if (Boolean.TRUE.equals(root.getTag(R.id.app_settings_name))) {
            return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasEntry(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 条目背景：普通态 + 按下态，用 StateListDrawable 替代上游已删除的 XDrawable。 */
    private static Drawable itemBackground(Context context, boolean darkMode, int cornerRadiusDp) {
        int normal = darkMode ? 0xFF191919 : Color.WHITE;
        int pressed = darkMode ? 0xFF1D1D1D : 0xFFE5E5E5;
        int radius = cornerRadiusDp > 0 ? DpUtils.dip2px(context, cornerRadiusDp) : 0;

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, fill(pressed, radius));
        states.addState(new int[]{}, fill(normal, radius));
        return states;
    }

    private static GradientDrawable fill(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        if (radius > 0) {
            drawable.setCornerRadius(radius);
        }
        return drawable;
    }
}
