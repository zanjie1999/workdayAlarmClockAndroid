package com.zyyme.workdayalarmclock.wake;

import android.content.res.AssetManager;

/**
 * 语音唤醒插件对外暴露的接口。宿主和插件各编译一份同名类，
 * 宿主用 DexClassLoader 把 parent 指定成自己的 ClassLoader，
 * 这样插件里的实现类强转成这个接口时解析到的就是宿主那份，不会出现两个 Class 对象。
 *
 * 纯 Java、无 lambda / stream / 默认方法，保证 API 14 也能加载这个接口。
 */
public interface WakeWordHost {

    /**
     * @param assets    插件自己的 AssetManager（模型放在插件的 assets/kws 下）
     * @param keywords  关键词文本，一行一个，sherpa 的格式
     * @param threshold 触发阈值，越小越灵敏
     */
    void init(AssetManager assets, String keywords, float threshold, Listener listener);

    void start();

    void stop();

    void release();

    /** 宿主采集线程扇出来的 16kHz 单声道 PCM，实现方必须立刻返回，不能阻塞 */
    void acceptPcm(short[] samples, int length);

    interface Listener {
        void onWake(String keyword);

        void onError(String message);
    }
}
