package com.zyyme.workdayalarmclock.wake;

/**
 * 唤醒词与灵敏度的默认值。宿主（设置页回显）和插件（Activity 无参打开时）共用同一份，
 * 避免两边各写一份慢慢走偏。
 */
public final class WakeWordDefaults {

    private WakeWordDefaults() {
    }

    /**
     * 默认唤醒词：你好小智 的 4 种发音变体 + 小智小智 的 4 种变体。
     * token 都是拼音（声母 + 韵母），必须和模型自带的 tokens.txt 对得上。
     */
    public static final String KEYWORDS =
            "n ǐ h ǎo x iǎo zh ì @你好小智\n"
                    + "n ǐ h ǎo x iǎo zh @你好小智\n"
                    + "n ǐ h ǎo x iǎo z ì @你好小智\n"
                    + "n ǐ h ǎo x iǎo z @你好小智\n"
                    + "x iǎo zh ì x iǎo zh ì @小智小智\n"
                    + "x iǎo zh x iǎo zh @小智小智\n"
                    + "x iǎo z ì x iǎo z ì @小智小智\n"
                    + "x iǎo z x iǎo z @小智小智";

    /** sherpa-onnx 的默认触发阈值 */
    public static final float THRESHOLD = 0.25f;

    /** 灵敏度滑条映射出的阈值范围 */
    public static final float THRESHOLD_MIN = 0.10f;

    public static final float THRESHOLD_MAX = 0.90f;

    /** 灵敏度 0~100 -> 阈值，灵敏度越高阈值越低 */
    public static float thresholdOfSensitivity(int sensitivity) {
        int s = sensitivity;
        if (s < 0) s = 0;
        if (s > 100) s = 100;
        return THRESHOLD_MAX - (THRESHOLD_MAX - THRESHOLD_MIN) * s / 100f;
    }

    /** 阈值 -> 灵敏度，用于打开界面时回显滑条位置 */
    public static int sensitivityOfThreshold(float threshold) {
        if (threshold >= THRESHOLD_MAX) return 0;
        if (threshold <= THRESHOLD_MIN) return 100;
        float ratio = (THRESHOLD_MAX - threshold) / (THRESHOLD_MAX - THRESHOLD_MIN);
        return Math.round(ratio * 100f);
    }
}
